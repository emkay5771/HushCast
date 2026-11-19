package app.hushcast.automute.transcript

import app.hushcast.automute.engine.AudioDecoder
import app.hushcast.automute.engine.Segment
import app.hushcast.automute.engine.Transcriber
import app.hushcast.automute.engine.TranscriptionOutput
import app.hushcast.automute.engine.Word
import app.hushcast.automute.matcher.MuteRule
import app.hushcast.automute.matcher.WordMatcher
import kotlinx.coroutines.yield
import kotlin.math.abs

data class DaiRetimingResult(
    val words: List<Word>,
    val segments: List<Segment>,
    val offsetMap: OffsetMap,
    val adCandidateSpansSec: List<Pair<Double, Double>>,
    /** SRT/VTT cues whose words were spread evenly over the cue instead of being heard. */
    val approximateSpans: List<ApproxSpan> = emptyList()
)

/** Transcribes `[startSec, endSec)` of the episode; times in the result are relative to [startSec]. */
fun interface ClipTranscriber {
    suspend fun transcribe(startSec: Double, endSec: Double): TranscriptionOutput
}

/**
 * DAI Retiming Bridge: aligns publisher transcripts onto a DAI download's timeline.
 *
 * 1. 8-12 anchor clips spread over the episode give the offset (download - transcript) at each.
 * 2. Between neighbours that disagree, probes bisect toward each break.  A probe that lands in
 *    the ad hears nothing it can match; the search then continues from each neighbouring anchor
 *    toward that miss, so both edges of the ad are found (bounded depth and probe count).
 * 3. At each break the anchor edges are extended clip by clip at their own offset, so the break
 *    sits after the last transcript word actually heard before the gap.
 * 4. Spot checks just after each break and inside the stretches verify the map.
 * 5. Uncovered gaps (inserted ads) are transcribed with Whisper.
 */
object DaiRetimer {
    private const val ANCHOR_CLIP_SEC = 30.0
    private const val PROBE_CLIP_SEC = 15.0
    private const val MIN_ANCHOR_DURATION_SEC = 90.0
    private const val OFFSET_DISAGREEMENT_SEC = 1.5
    private const val BISECT_STOP_WINDOW_SEC = 16.0
    private const val MAX_BISECT_DEPTH = 8
    private const val MAX_PROBES_PER_BREAK = 16
    private const val EDGE_TOL_SEC = 1.0
    private const val CUE_EDGE_TOL_SEC = 2.5
    private const val EDGE_CHAIN_GAP = 4
    private const val MAX_EDGE_STEPS = 3
    private const val POST_BREAK_CHECK_SEC = 15.0
    private const val MIN_GAP_MS = 3500L

    /**
     * One clip matched against the transcript.  [shiftSec] is the median download - transcript
     * offset; [firstWordIndex]/[lastWordIndex] are the first and last transcript words heard at
     * that offset.
     */
    internal data class AnchorMatch(
        val downloadCenterSec: Double,
        val shiftSec: Double,
        val matchedCount: Int,
        val firstWordIndex: Int,
        val lastWordIndex: Int
    )

    /** Decodes the range with [AudioDecoder.decodeRange] and runs Whisper on it. */
    fun whisperClips(
        audioPath: String,
        transcriber: Transcriber,
        vadModelPath: String?,
        shouldAbort: () -> Boolean
    ): ClipTranscriber = ClipTranscriber { s, e ->
        val pcm = AudioDecoder.decodeRange(audioPath, s, e, shouldAbort)
        try {
            transcriber.transcribe(pcm, vadModelPath = vadModelPath, shouldAbort = shouldAbort)
        } finally {
            pcm.close()
        }
    }

    suspend fun retimeTranscript(
        audioPath: String,
        durationSec: Double,
        parsedFeed: ParsedTranscript,
        transcriber: Transcriber,
        vadModelPath: String?,
        enabledRules: List<MuteRule>,
        shouldAbort: () -> Boolean,
        onProgress: (Int) -> Unit = {}
    ): DaiRetimingResult? = retimeTranscript(
        durationSec = durationSec,
        parsedFeed = parsedFeed,
        clips = whisperClips(audioPath, transcriber, vadModelPath, shouldAbort),
        enabledRules = enabledRules,
        onProgress = onProgress,
        log = { android.util.Log.i("HushCastPerf", "DAI: $it") }
    )

    private class Ctx(
        val clips: ClipTranscriber,
        val durationSec: Double,
        val refWords: List<Word>,
        val refNorm: List<String>,
        val trigramIndex: Map<String, List<Int>>,
        /** How far a heard word may sit from where the transcript puts it and still count. */
        val edgeTol: Double
    ) {
        /** Normalized heard tokens with absolute download times. */
        suspend fun heard(startSec: Double, endSec: Double): List<Pair<String, Double>> {
            yield()
            if (endSec - startSec < 0.5) return emptyList()
            return clips.transcribe(startSec, endSec).words.mapNotNull { w ->
                WordMatcher.normalizeToken(w.text).takeIf { it.isNotEmpty() }?.let { it to (startSec + w.start) }
            }
        }

        fun clipAround(center: Double, len: Double): Pair<Double, Double> {
            val s = (center - len / 2.0).coerceIn(0.0, (durationSec - len).coerceAtLeast(0.0))
            return s to (s + len).coerceAtMost(durationSec)
        }
    }

    internal suspend fun retimeTranscript(
        durationSec: Double,
        parsedFeed: ParsedTranscript,
        clips: ClipTranscriber,
        enabledRules: List<MuteRule>,
        onProgress: (Int) -> Unit = {},
        log: (String) -> Unit = {}
    ): DaiRetimingResult? {
        if (durationSec < MIN_ANCHOR_DURATION_SEC || parsedFeed.segments.isEmpty()) return null

        val wordLevel = parsedFeed.hasWordLevelTimings && parsedFeed.words.isNotEmpty()
        val refWords: List<Word> = if (wordLevel) {
            parsedFeed.words
        } else {
            // Pseudo-words from cue segments for anchor matching
            buildPseudoWordsFromSegments(parsedFeed.segments)
        }
        if (refWords.size < 20) return null

        val refNorm = refWords.map { WordMatcher.normalizeToken(it.text) }
        // SRT/VTT words are spread evenly over their cue, so they can be off by about a second.
        val ctx = Ctx(clips, durationSec, refWords, refNorm, buildTrigramIndex(refNorm), if (wordLevel) EDGE_TOL_SEC else CUE_EDGE_TOL_SEC)

        // 1. Pick 8-12 anchor positions spread across the episode
        val anchorCount = (durationSec / 360.0).toInt().coerceIn(8, 12)
        val anchorStarts = (0 until anchorCount).map { i ->
            val frac = 0.04 + (0.88 * i) / (anchorCount - 1).coerceAtLeast(1)
            (durationSec * frac).coerceIn(0.0, (durationSec - ANCHOR_CLIP_SEC).coerceAtLeast(0.0))
        }
        val anchors = ArrayList<AnchorMatch>(anchorCount)
        for ((idx, startSec) in anchorStarts.withIndex()) {
            val endSec = (startSec + ANCHOR_CLIP_SEC).coerceAtMost(durationSec)
            matchClip(ctx, startSec, endSec)?.let { anchors.add(it) }
            onProgress(10 + ((idx + 1) * 35) / anchorCount)
        }
        if (anchors.size < 3) {
            log("only ${anchors.size} of $anchorCount anchors matched; falling back")
            return null
        }
        anchors.sortBy { it.downloadCenterSec }
        log("anchors: " + anchors.joinToString { "%.1f@%.2f[%d..%d]".format(it.downloadCenterSec, it.shiftSec, it.firstWordIndex, it.lastWordIndex) })

        // 2. Bisect toward every break between neighbouring anchors that disagree
        val found = ArrayList<AnchorMatch>(anchors)
        for (k in 0 until anchors.lastIndex) {
            if (abs(anchors[k].shiftSec - anchors[k + 1].shiftSec) > OFFSET_DISAGREEMENT_SEC) {
                searchBreak(ctx, anchors[k], anchors[k + 1], found)
            }
        }
        found.sortBy { it.downloadCenterSec }

        // 3. Extend each side of each break to the last/first word heard at its offset
        val refined = refineEdges(ctx, found)

        // 4. Build piecewise-constant OffsetMap
        val stretches = buildStretches(refined, refWords)
        if (stretches.isEmpty()) {
            log("no stretches; falling back")
            return null
        }
        log("stretches: " + stretches.joinToString { "${it.srcStartMs}..${it.srcEndMs}+${it.shiftMs}" })
        val offsetMap = OffsetMap(stretches)

        // 5a. Spot-check just after each break: the words there must sit where the map puts them
        for (k in 1 until stretches.size) {
            val dst = stretches[k].dstStartMs / 1000.0
            if (dst >= durationSec - 2.0) continue
            val s = (dst - 0.5).coerceAtLeast(0.0)
            val e = (s + POST_BREAK_CHECK_SEC).coerceAtMost(durationSec)
            val heard = ctx.heard(s, e)
            val expected = expectedWords(ctx, offsetMap, s, e, fromSrcMs = stretches[k].srcStartMs)
            val ok = alignmentVerdict(
                heard, expected,
                hitTolSec = ctx.edgeTol,
                maxMedianSec = if (wordLevel) 0.5 else 1.0,
                minOverlap = 0.4,
                minWords = 6
            )
            if (ok == false) {
                log("break ${k} at ${"%.1f".format(dst)}s failed its spot check; falling back")
                return null
            }
        }

        // 5b. Spot-check 2-3 points inside the stretches
        val checkStretches = if (stretches.size <= 3) stretches else listOf(
            stretches.first(),
            stretches[stretches.size / 2],
            stretches.last()
        )
        var verified = 0
        for (st in checkStretches) {
            val midDstSec = ((st.dstStartMs + st.dstEndMs) * 0.5) / 1000.0
            val startSec = (midDstSec - 10.0).coerceIn(0.0, (durationSec - 20.0).coerceAtLeast(0.0))
            val endSec = (startSec + 20.0).coerceAtMost(durationSec)
            val heard = ctx.heard(startSec, endSec)
            val expected = expectedWords(ctx, offsetMap, startSec, endSec, fromSrcMs = Long.MIN_VALUE)
            if (alignmentVerdict(heard, expected, hitTolSec = 1.2, maxMedianSec = 1.2, minOverlap = 0.45, minWords = 1) == true) {
                verified++
            }
        }
        if (verified < minOf(2, checkStretches.size)) {
            log("only $verified of ${checkStretches.size} stretch spot checks passed; falling back")
            return null
        }
        onProgress(55)

        // 6. Shift publisher transcript onto download timeline
        val shiftedSegments = offsetMap.applyToSegments(parsedFeed.segments).toMutableList()
        val approximate = ArrayList<ApproxSpan>()
        val shiftedWords = if (wordLevel) {
            offsetMap.applyToWords(parsedFeed.words).toMutableList()
        } else {
            refineSrtVttCues(ctx, parsedFeed.segments, offsetMap, WordMatcher.compileRules(enabledRules), approximate).toMutableList()
        }

        // 7. Transcribe uncovered gaps (dynamically inserted ad blocks)
        val totalDurationMs = (durationSec * 1000.0).toLong()
        val gaps = offsetMap.uncoveredGaps(totalDurationMs, MIN_GAP_MS)
        val adCandidateSpans = ArrayList<Pair<Double, Double>>(gaps.size)
        for ((gIdx, gap) in gaps.withIndex()) {
            yield()
            val gStartSec = gap.first / 1000.0
            val gEndSec = gap.second / 1000.0
            adCandidateSpans.add(gStartSec to gEndSec)
            val gapOut = clips.transcribe(gStartSec, gEndSec)
            for (w in gapOut.words) {
                shiftedWords.add(w.copy(start = gStartSec + w.start, end = gStartSec + w.end))
            }
            for (s in gapOut.segments) {
                shiftedSegments.add(s.copy(start = gStartSec + s.start, end = gStartSec + s.end))
            }
            onProgress(60 + ((gIdx + 1) * 35) / gaps.size.coerceAtLeast(1))
        }

        onProgress(100)
        return DaiRetimingResult(
            words = shiftedWords.sortedBy { it.start },
            segments = shiftedSegments.sortedBy { it.start },
            offsetMap = offsetMap,
            adCandidateSpansSec = adCandidateSpans,
            approximateSpans = approximate
        )
    }

    /**
     * SRT/VTT cues: each cue's words are spread evenly over it.  Cues holding a rule match are
     * aligned with Whisper (+-[CueRealigner.PAD_SEC]); the rest are reported as approximate so a
     * rule added later can have them aligned then.
     */
    private suspend fun refineSrtVttCues(
        ctx: Ctx,
        sourceCues: List<Segment>,
        offsetMap: OffsetMap,
        compiled: WordMatcher.CompiledRules,
        approximate: MutableList<ApproxSpan>
    ): List<Word> {
        val outWords = ArrayList<Word>()
        for (cue in sourceCues) {
            // Spread in the source timeline, then shift word by word: a cue cut by an ad break
            // keeps its words on either side of the ad instead of being stretched across it.
            val shifted = offsetMap.applyToWords(CueRealigner.pseudoWords(cue.text, cue.start, cue.end))
            for (pseudo in splitAtJumps(shifted)) {
                val first = pseudo.first().start
                val last = pseudo.last().end
                val hasRuleHit = !compiled.isEmpty && WordMatcher.matchWords(pseudo, compiled).isNotEmpty()
                if (hasRuleHit) {
                    val s = (first - CueRealigner.PAD_SEC).coerceAtLeast(0.0)
                    val e = (last + CueRealigner.PAD_SEC).coerceAtMost(ctx.durationSec)
                    yield()
                    val heard = ctx.clips.transcribe(s, e).words.map { it.copy(start = s + it.start, end = s + it.end) }
                    val aligned = CueRealigner.alignTokens(pseudo, heard)
                    if (CueRealigner.isGoodAlignment(aligned, pseudo.size)) {
                        outWords.addAll(aligned.words)
                        continue
                    }
                    approximate.add(ApproxSpan(first, last, attempted = true))
                } else {
                    approximate.add(ApproxSpan(first, last))
                }
                outWords.addAll(pseudo)
            }
        }
        return outWords
    }

    /** Splits shifted words where the offset changes (a jump of more than 1 s between neighbours). */
    private fun splitAtJumps(words: List<Word>): List<List<Word>> {
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<List<Word>>()
        var from = 0
        for (i in 1 until words.size) {
            if (abs(words[i].start - words[i - 1].end) > 1.0) {
                out.add(words.subList(from, i))
                from = i
            }
        }
        out.add(words.subList(from, words.size))
        return out
    }

    /**
     * Probes between [left] and [right] (which disagree on the offset) until each break is
     * bracketed within [BISECT_STOP_WINDOW_SEC].  A probe that matches nothing is inside the ad
     * (or unmatched speech); the search then continues between it and each neighbour.
     */
    private suspend fun searchBreak(ctx: Ctx, left: AnchorMatch, right: AnchorMatch, out: MutableList<AnchorMatch>) {
        class Bound(val pos: Double, val anchor: AnchorMatch?)
        class Window(val lo: Bound, val hi: Bound, val depth: Int)

        val stack = ArrayDeque<Window>()
        stack.addLast(Window(Bound(left.downloadCenterSec, left), Bound(right.downloadCenterSec, right), 0))
        var probes = 0
        while (stack.isNotEmpty() && probes < MAX_PROBES_PER_BREAK) {
            val w = stack.removeLast()
            val la = w.lo.anchor
            val ha = w.hi.anchor
            if (la == null && ha == null) continue
            if (la != null && ha != null && abs(la.shiftSec - ha.shiftSec) <= OFFSET_DISAGREEMENT_SEC) continue
            if (w.hi.pos - w.lo.pos <= BISECT_STOP_WINDOW_SEC || w.depth >= MAX_BISECT_DEPTH) continue
            val mid = (w.lo.pos + w.hi.pos) * 0.5
            val (cs, ce) = ctx.clipAround(mid, PROBE_CLIP_SEC)
            probes++
            val m = matchClip(ctx, cs, ce)
            if (m != null) out.add(m)
            val mb = Bound(mid, m)
            // Left half is popped first.  Halves with nothing to find are pruned above.
            stack.addLast(Window(mb, w.hi, w.depth + 1))
            stack.addLast(Window(w.lo, mb, w.depth + 1))
        }
    }

    /** Groups consecutive anchors (by download time) that agree on the offset. */
    internal fun groupAnchors(anchors: List<AnchorMatch>): List<List<AnchorMatch>> {
        if (anchors.isEmpty()) return emptyList()
        val groups = ArrayList<MutableList<AnchorMatch>>()
        var cur = mutableListOf(anchors.first())
        var sum = anchors.first().shiftSec
        for (i in 1 until anchors.size) {
            val a = anchors[i]
            if (abs(a.shiftSec - sum / cur.size) > OFFSET_DISAGREEMENT_SEC) {
                groups.add(cur)
                cur = mutableListOf(a)
                sum = a.shiftSec
            } else {
                cur.add(a)
                sum += a.shiftSec
            }
        }
        groups.add(cur)
        return groups
    }

    private suspend fun refineEdges(ctx: Ctx, anchors: List<AnchorMatch>): List<AnchorMatch> {
        val groups = groupAnchors(anchors)
        if (groups.size < 2) return anchors
        val replaced = HashMap<AnchorMatch, AnchorMatch>()
        for (g in 0 until groups.lastIndex) {
            val here = groups[g]
            val next = groups[g + 1]
            val shiftA = here.map { it.shiftSec }.average()
            val shiftB = next.map { it.shiftSec }.average()
            val l = here.maxBy { it.lastWordIndex }.let { replaced[it] ?: it }
            val r = next.minBy { it.firstWordIndex }.let { replaced[it] ?: it }
            val newLast = extendForward(ctx, l.lastWordIndex, shiftA)
            val newFirst = extendBackward(ctx, r.firstWordIndex, shiftB)
            replaced[here.maxBy { it.lastWordIndex }] = l.copy(lastWordIndex = maxOf(l.lastWordIndex, newLast))
            replaced[next.minBy { it.firstWordIndex }] = r.copy(firstWordIndex = minOf(r.firstWordIndex, newFirst))
        }
        return anchors.map { replaced[it] ?: it }
    }

    /** Follows the transcript past [last] while the audio keeps matching at [shift]. */
    private suspend fun extendForward(ctx: Ctx, last: Int, shift: Double): Int {
        var cur = last
        repeat(MAX_EDGE_STEPS) {
            val edge = ctx.refWords[cur].end + shift
            if (edge >= ctx.durationSec - 0.5 || cur >= ctx.refWords.lastIndex) return cur
            val s = (edge - 3.0).coerceAtLeast(0.0)
            val e = (edge + 12.0).coerceAtMost(ctx.durationSec)
            val hits = matchAtShift(ctx, ctx.heard(s, e), (cur - 4).coerceAtLeast(0), (cur + 150).coerceAtMost(ctx.refWords.lastIndex), shift)
            var next = cur
            for ((idx, _) in hits) {
                if (idx <= next) continue
                if (idx - next > EDGE_CHAIN_GAP) break
                next = idx
            }
            if (next == cur) return cur
            cur = next
            // The words stopped inside this clip: that is the edge.
            if (ctx.refWords[cur].end + shift < e - 3.0) return cur
        }
        return cur
    }

    /** Follows the transcript back from [first] while the audio keeps matching at [shift]. */
    private suspend fun extendBackward(ctx: Ctx, first: Int, shift: Double): Int {
        var cur = first
        repeat(MAX_EDGE_STEPS) {
            val edge = ctx.refWords[cur].start + shift
            if (edge <= 0.5 || cur <= 0) return cur
            val e = (edge + 3.0).coerceAtMost(ctx.durationSec)
            val s = (edge - 12.0).coerceAtLeast(0.0)
            val hits = matchAtShift(ctx, ctx.heard(s, e), (cur - 150).coerceAtLeast(0), (cur + 4).coerceAtMost(ctx.refWords.lastIndex), shift)
            var next = cur
            for ((idx, _) in hits.asReversed()) {
                if (idx >= next) continue
                if (next - idx > EDGE_CHAIN_GAP) break
                next = idx
            }
            if (next == cur) return cur
            cur = next
            if (ctx.refWords[cur].start + shift > s + 3.0) return cur
        }
        return cur
    }

    /** In-order matches of [heard] to transcript words [from]..[to] predicted within [Ctx.edgeTol] at [shift]. */
    private fun matchAtShift(ctx: Ctx, heard: List<Pair<String, Double>>, from: Int, to: Int, shift: Double): List<Pair<Int, Double>> {
        val out = ArrayList<Pair<Int, Double>>()
        var cursor = from
        for ((tok, t) in heard) {
            val srcT = t - shift
            var r = cursor
            while (r <= to) {
                val rs = ctx.refWords[r].start
                if (rs > srcT + ctx.edgeTol) break
                if (rs >= srcT - ctx.edgeTol && ctx.refNorm[r] == tok) {
                    out.add(r to t)
                    cursor = r + 1
                    break
                }
                r++
            }
        }
        return out
    }

    private fun expectedWords(ctx: Ctx, map: OffsetMap, s: Double, e: Double, fromSrcMs: Long): List<Pair<String, Double>> =
        ctx.refWords.indices.mapNotNull { i ->
            val w = ctx.refWords[i]
            if ((w.start * 1000.0).toLong() < fromSrcMs) return@mapNotNull null
            val t = map.shiftTimeSec(w.start)
            if (t in (s - 1.0)..(e + 1.0)) ctx.refNorm[i].takeIf { it.isNotEmpty() }?.let { it to t } else null
        }

    /**
     * Whether heard words sit where the map expects them.  null: fewer than [minWords] on either
     * side to judge.  A hit is a heard word with the same expected word within [hitTolSec].
     */
    internal fun alignmentVerdict(
        heard: List<Pair<String, Double>>,
        expected: List<Pair<String, Double>>,
        hitTolSec: Double,
        maxMedianSec: Double,
        minOverlap: Double,
        minWords: Int
    ): Boolean? {
        if (heard.size < minWords || expected.size < minWords || heard.isEmpty() || expected.isEmpty()) return null
        val expMap = expected.groupBy({ it.first }, { it.second })
        val deltas = ArrayList<Double>()
        for ((tok, t) in heard) {
            val cands = expMap[tok] ?: continue
            val best = cands.minOf { abs(it - t) }
            if (best <= hitTolSec) deltas.add(best)
        }
        val overlap = deltas.size.toDouble() / minOf(heard.size, expected.size)
        if (overlap < minOverlap || deltas.isEmpty()) return false
        deltas.sort()
        return deltas[deltas.size / 2] <= maxMedianSec
    }

    private suspend fun matchClip(ctx: Ctx, startSec: Double, endSec: Double): AnchorMatch? {
        val clipWords = ctx.heard(startSec, endSec)
        if (clipWords.size < 6) return null
        val refWords = ctx.refWords
        val refNorm = ctx.refNorm

        // Candidate transcript start positions from trigram hits
        val candidateStarts = HashMap<Int, Int>()
        for (i in 0..(clipWords.size - 3)) {
            val key = "${clipWords[i].first}|${clipWords[i + 1].first}|${clipWords[i + 2].first}"
            val hits = ctx.trigramIndex[key] ?: continue
            for (refIdx in hits) {
                val alignedStart = refIdx - i
                candidateStarts[alignedStart] = (candidateStarts[alignedStart] ?: 0) + 1
            }
        }
        if (candidateStarts.isEmpty()) return null

        val topCandidates = candidateStarts.entries.sortedByDescending { it.value }.take(5)
        var bestCount = 0
        var bestAnchor: AnchorMatch? = null

        for ((approxStart, _) in topCandidates) {
            // Start just before the trigram-aligned position.  (Starting 12 words early with an
            // 8-word lookahead, as before, never reached it unless a stray common word pulled the
            // cursor forward.)  The lookahead grows with each unmatched clip word, so the cursor
            // keeps up through words Whisper misheard or the transcript lacks.
            val lo = (approxStart - 4).coerceAtLeast(0)
            val hi = (approxStart + clipWords.size + 12).coerceAtMost(refNorm.size - 1)
            if (lo > hi) continue
            val pairs = ArrayList<Pair<Int, Double>>()
            var refCursor = lo
            var missRun = 0
            for ((tok, dlSec) in clipWords) {
                val searchHi = (refCursor + 8 + missRun).coerceAtMost(hi)
                var found = false
                for (r in refCursor..searchHi) {
                    if (refNorm[r] == tok) {
                        pairs.add(r to dlSec)
                        refCursor = r + 1
                        found = true
                        break
                    }
                }
                missRun = if (found) 0 else missRun + 1
                if (refCursor > hi) break
            }
            val matched = pairs.size
            if (matched > bestCount && matched >= maxOf(6, clipWords.size / 3)) {
                val deltas = pairs.map { (r, dl) -> dl - refWords[r].start }.sorted()
                val medianShift = deltas[deltas.size / 2]
                // First/last words heard at this offset; a stray match (an ad's "the" lining up
                // with a transcript "the") is off the median and is ignored here.
                val consistent = trimIsolatedEnds(
                    pairs.filter { (r, dl) -> abs(dl - refWords[r].start - medianShift) <= ctx.edgeTol }
                )
                if (consistent.isEmpty()) continue
                bestCount = matched
                bestAnchor = AnchorMatch(
                    downloadCenterSec = (startSec + endSec) * 0.5,
                    shiftSec = medianShift,
                    matchedCount = matched,
                    firstWordIndex = consistent.first().first,
                    lastWordIndex = consistent.last().first
                )
            }
        }
        return bestAnchor
    }

    /**
     * Drops matches at either end that are not followed (or preceded) by another match within
     * [EDGE_CHAIN_GAP] transcript words: a lone hit in ad copy must not become an edge.
     */
    private fun trimIsolatedEnds(pairs: List<Pair<Int, Double>>): List<Pair<Int, Double>> {
        if (pairs.size < 2) return pairs
        var a = 0
        var b = pairs.lastIndex
        while (a < b && pairs[a + 1].first - pairs[a].first > EDGE_CHAIN_GAP) a++
        while (b > a && pairs[b].first - pairs[b - 1].first > EDGE_CHAIN_GAP) b--
        return pairs.subList(a, b + 1)
    }

    internal fun buildStretches(anchors: List<AnchorMatch>, refWords: List<Word>): List<OffsetStretch> {
        if (anchors.isEmpty() || refWords.isEmpty()) return emptyList()
        val groups = groupAnchors(anchors)
        val lastRefMs = (refWords.last().end * 1000.0).toLong().coerceAtLeast(1000L)
        val firstRefMs = (refWords.first().start * 1000.0).toLong().coerceAtLeast(0L)
        val stretches = ArrayList<OffsetStretch>()

        var groupStartSrcMs = firstRefMs
        var prevSplit = -1
        for (g in groups.indices) {
            val shiftMs = (groups[g].map { it.shiftSec }.average() * 1000.0).toLong()
            if (g == groups.lastIndex) {
                if (lastRefMs > groupStartSrcMs) {
                    stretches.add(OffsetStretch(srcStartMs = groupStartSrcMs, srcEndMs = lastRefMs, shiftMs = shiftMs))
                }
                break
            }
            // Break after the last transcript word heard at this offset.  If words between it and
            // the first word heard at the next offset were never heard, the break goes at the
            // longest pause among them (ads are inserted at pauses), earliest on ties.
            val lastHere = groups[g].maxOf { it.lastWordIndex }.coerceIn(0, refWords.lastIndex)
            val firstNext = groups[g + 1].minOf { it.firstWordIndex }.coerceIn(0, refWords.lastIndex)
            val lo = minOf(lastHere, firstNext - 1).coerceAtLeast(0)
            val hi = (firstNext - 1).coerceAtLeast(lo).coerceAtMost(refWords.lastIndex - 1)
            var split = lo
            var bestGap = Double.NEGATIVE_INFINITY
            for (i in lo..maxOf(lo, hi)) {
                if (i >= refWords.lastIndex) break
                val gap = refWords[i + 1].start - refWords[i].end
                if (gap > bestGap + 1e-9) {
                    bestGap = gap
                    split = i
                }
            }
            val splitSrcMs = (refWords[split].end * 1000.0).toLong()
            if (split > prevSplit && splitSrcMs > groupStartSrcMs) {
                stretches.add(OffsetStretch(srcStartMs = groupStartSrcMs, srcEndMs = splitSrcMs, shiftMs = shiftMs))
                prevSplit = split
                groupStartSrcMs = (refWords[(split + 1).coerceAtMost(refWords.lastIndex)].start * 1000.0).toLong()
            }
        }
        return stretches
    }

    private fun buildTrigramIndex(refNorm: List<String>): Map<String, List<Int>> {
        val map = HashMap<String, MutableList<Int>>(refNorm.size)
        for (i in 0..(refNorm.size - 3)) {
            val a = refNorm[i]; val b = refNorm[i + 1]; val c = refNorm[i + 2]
            if (a.isEmpty() || b.isEmpty() || c.isEmpty()) continue
            map.getOrPut("$a|$b|$c") { ArrayList(2) }.add(i)
        }
        return map
    }

    private fun buildPseudoWordsFromSegments(segments: List<Segment>): List<Word> =
        segments.flatMap { CueRealigner.pseudoWords(it.text, it.start, it.end) }
}
