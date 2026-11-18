package app.hushcast.automute.transcript

import app.hushcast.automute.engine.AudioDecoder
import app.hushcast.automute.engine.Segment
import app.hushcast.automute.engine.Transcriber
import app.hushcast.automute.engine.Word
import app.hushcast.automute.matcher.MuteRule
import app.hushcast.automute.matcher.WordMatcher
import kotlinx.coroutines.yield
import kotlin.math.abs

data class DaiRetimingResult(
    val words: List<Word>,
    val segments: List<Segment>,
    val offsetMap: OffsetMap,
    val adCandidateSpansSec: List<Pair<Double, Double>>
)

/**
 * DAI Retiming Bridge: aligns publisher transcripts onto a DAI download's timeline
 * using 8-12 30s anchor clips, bisection at ad breaks, gap transcription, and spot-check verification.
 */
object DaiRetimer {
    private const val ANCHOR_CLIP_SEC = 30.0
    private const val MIN_ANCHOR_DURATION_SEC = 90.0
    private const val OFFSET_DISAGREEMENT_SEC = 1.5
    private const val BISECT_STOP_WINDOW_SEC = 8.0
    private const val MAX_BISECT_DEPTH = 6
    private const val MIN_GAP_MS = 3500L

    internal data class AnchorMatch(
        val downloadCenterSec: Double,
        val transcriptCenterSec: Double,
        val shiftSec: Double,
        val matchedCount: Int,
        val transcriptWordIndex: Int
    )

    suspend fun retimeTranscript(
        audioPath: String,
        durationSec: Double,
        parsedFeed: ParsedTranscript,
        transcriber: Transcriber,
        vadModelPath: String?,
        enabledRules: List<MuteRule>,
        shouldAbort: () -> Boolean,
        onProgress: (Int) -> Unit = {}
    ): DaiRetimingResult? {
        if (durationSec < MIN_ANCHOR_DURATION_SEC || parsedFeed.segments.isEmpty()) return null

        val refWords: List<Word> = if (parsedFeed.hasWordLevelTimings && parsedFeed.words.isNotEmpty()) {
            parsedFeed.words
        } else {
            // Build pseudo-words from cue segments for anchor matching
            buildPseudoWordsFromSegments(parsedFeed.segments)
        }
        if (refWords.size < 20) return null

        val refNorm = refWords.map { WordMatcher.normalizeToken(it.text) }
        val trigramIndex = buildTrigramIndex(refNorm)

        // 1. Pick 8-12 anchor positions spread across the episode
        val anchorCount = (durationSec / 360.0).toInt().coerceIn(8, 12)
        val anchorStarts = (0 until anchorCount).map { i ->
            val frac = 0.04 + (0.88 * i) / (anchorCount - 1).coerceAtLeast(1)
            (durationSec * frac).coerceIn(0.0, (durationSec - ANCHOR_CLIP_SEC).coerceAtLeast(0.0))
        }

        val anchors = ArrayList<AnchorMatch>(anchorCount)
        for ((idx, startSec) in anchorStarts.withIndex()) {
            yield()
            val endSec = (startSec + ANCHOR_CLIP_SEC).coerceAtMost(durationSec)
            val m = matchClip(
                audioPath = audioPath,
                startSec = startSec,
                endSec = endSec,
                transcriber = transcriber,
                vadModelPath = vadModelPath,
                refWords = refWords,
                refNorm = refNorm,
                trigramIndex = trigramIndex,
                shouldAbort = shouldAbort
            )
            if (m != null) anchors.add(m)
            onProgress(10 + ((idx + 1) * 35) / anchorCount)
        }

        if (anchors.size < 3) return null
        anchors.sortBy { it.downloadCenterSec }

        // 2. Bisect where neighboring anchors disagree on offset
        val resolvedAnchors = anchors.toMutableList()
        var i = 0
        while (i < resolvedAnchors.lastIndex) {
            val a = resolvedAnchors[i]
            val b = resolvedAnchors[i + 1]
            if (abs(a.shiftSec - b.shiftSec) > OFFSET_DISAGREEMENT_SEC &&
                (b.downloadCenterSec - a.downloadCenterSec) > BISECT_STOP_WINDOW_SEC
            ) {
                bisectBreak(
                    audioPath = audioPath,
                    left = a,
                    right = b,
                    durationSec = durationSec,
                    transcriber = transcriber,
                    vadModelPath = vadModelPath,
                    refWords = refWords,
                    refNorm = refNorm,
                    trigramIndex = trigramIndex,
                    shouldAbort = shouldAbort,
                    depth = 0,
                    outAnchors = resolvedAnchors
                )
                resolvedAnchors.sortBy { it.downloadCenterSec }
            }
            i++
        }

        // 3. Build piecewise-constant OffsetMap
        val stretches = buildStretches(resolvedAnchors, refWords)
        if (stretches.isEmpty()) return null
        val offsetMap = OffsetMap(stretches)

        // 4. Spot-check 2-3 points in shifted stretches to verify alignment
        val checkStretches = if (stretches.size <= 3) stretches else listOf(
            stretches.first(),
            stretches[stretches.size / 2],
            stretches.last()
        )
        var verified = 0
        for (st in checkStretches) {
            yield()
            val midDstSec = ((st.dstStartMs + st.dstEndMs) * 0.5) / 1000.0
            val startSec = (midDstSec - 10.0).coerceIn(0.0, (durationSec - 20.0).coerceAtLeast(0.0))
            val endSec = (startSec + 20.0).coerceAtMost(durationSec)
            val clipPcm = AudioDecoder.decodeRange(audioPath, startSec, endSec, shouldAbort)
            val out = try {
                transcriber.transcribe(clipPcm, vadModelPath = vadModelPath, shouldAbort = shouldAbort)
            } finally {
                clipPcm.close()
            }
            val clipNorm = out.words.mapNotNull { w ->
                WordMatcher.normalizeToken(w.text).takeIf { it.isNotEmpty() }?.let { it to (startSec + w.start) }
            }
            if (clipNorm.isEmpty()) continue
            val expectedWords = refWords.mapNotNull { w ->
                val shiftedStart = offsetMap.shiftTimeSec(w.start)
                if (shiftedStart in (startSec - 2.0)..(endSec + 2.0)) {
                    WordMatcher.normalizeToken(w.text).takeIf { it.isNotEmpty() }?.let { it to shiftedStart }
                } else null
            }
            if (expectedWords.isEmpty()) continue
            val expMap = expectedWords.groupBy({ it.first }, { it.second })
            var hits = 0
            val deltas = ArrayList<Double>()
            for ((tok, tClip) in clipNorm) {
                val cands = expMap[tok] ?: continue
                val best = cands.minOf { abs(it - tClip) }
                if (best <= 1.2) {
                    hits++
                    deltas.add(best)
                }
            }
            val overlap = hits.toDouble() / minOf(clipNorm.size, expectedWords.size).coerceAtLeast(1)
            if (overlap >= 0.45 && deltas.isNotEmpty()) {
                verified++
            }
        }
        if (verified < minOf(2, checkStretches.size)) return null
        onProgress(55)

        // 5. Shift publisher transcript onto download timeline
        val shiftedSegments = offsetMap.applyToSegments(parsedFeed.segments).toMutableList()
        val shiftedWords = if (parsedFeed.hasWordLevelTimings && parsedFeed.words.isNotEmpty()) {
            offsetMap.applyToWords(parsedFeed.words).toMutableList()
        } else {
            // SRT/VTT cue-level: run Whisper within ±2s of cues containing a rule word,
            // and use evenly distributed cue words for the remaining cues.
            refineSrtVttCuesWithWhisper(
                audioPath = audioPath,
                durationSec = durationSec,
                shiftedCues = shiftedSegments,
                enabledRules = enabledRules,
                transcriber = transcriber,
                vadModelPath = vadModelPath,
                shouldAbort = shouldAbort
            ).toMutableList()
        }

        // 6. Transcribe uncovered gaps (dynamically inserted ad blocks)
        val totalDurationMs = (durationSec * 1000.0).toLong()
        val gaps = offsetMap.uncoveredGaps(totalDurationMs, MIN_GAP_MS)
        val adCandidateSpans = ArrayList<Pair<Double, Double>>(gaps.size)
        for ((gIdx, gap) in gaps.withIndex()) {
            yield()
            val gStartSec = gap.first / 1000.0
            val gEndSec = gap.second / 1000.0
            adCandidateSpans.add(gStartSec to gEndSec)
            val gapPcm = AudioDecoder.decodeRange(audioPath, gStartSec, gEndSec, shouldAbort)
            val gapOut = try {
                transcriber.transcribe(gapPcm, vadModelPath = vadModelPath, shouldAbort = shouldAbort)
            } finally {
                gapPcm.close()
            }
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
            adCandidateSpansSec = adCandidateSpans
        )
    }

    private suspend fun refineSrtVttCuesWithWhisper(
        audioPath: String,
        durationSec: Double,
        shiftedCues: List<Segment>,
        enabledRules: List<MuteRule>,
        transcriber: Transcriber,
        vadModelPath: String?,
        shouldAbort: () -> Boolean
    ): List<Word> {
        val outWords = ArrayList<Word>()
        val ruleRegexes = enabledRules.filter { it.enabled && it.pattern.isNotBlank() }.mapNotNull { r ->
            if (r.isRegex) runCatching { Regex(r.pattern, RegexOption.IGNORE_CASE) }.getOrNull()
            else Regex("\\b${Regex.escape(r.pattern.trim())}", RegexOption.IGNORE_CASE)
        }
        for (cue in shiftedCues) {
            val hasRuleHit = ruleRegexes.any { it.containsMatchIn(cue.text) }
            if (hasRuleHit) {
                yield()
                val s = (cue.start - 2.0).coerceAtLeast(0.0)
                val e = (cue.end + 2.0).coerceAtMost(durationSec)
                val pcm = AudioDecoder.decodeRange(audioPath, s, e, shouldAbort)
                val wOut = try {
                    transcriber.transcribe(pcm, vadModelPath = vadModelPath, shouldAbort = shouldAbort)
                } finally {
                    pcm.close()
                }
                for (w in wOut.words) {
                    outWords.add(w.copy(start = s + w.start, end = s + w.end))
                }
            } else {
                val tokens = cue.text.split(Regex("\\s+")).filter { it.isNotBlank() }
                if (tokens.isNotEmpty()) {
                    val step = (cue.end - cue.start).coerceAtLeast(0.05) / tokens.size
                    tokens.forEachIndexed { idx, tok ->
                        val ws = cue.start + idx * step
                        outWords.add(Word(ws, ws + step, " $tok"))
                    }
                }
            }
        }
        return outWords
    }

    private suspend fun bisectBreak(
        audioPath: String,
        left: AnchorMatch,
        right: AnchorMatch,
        durationSec: Double,
        transcriber: Transcriber,
        vadModelPath: String?,
        refWords: List<Word>,
        refNorm: List<String>,
        trigramIndex: Map<String, List<Int>>,
        shouldAbort: () -> Boolean,
        depth: Int,
        outAnchors: MutableList<AnchorMatch>
    ) {
        if (depth >= MAX_BISECT_DEPTH) return
        val span = right.downloadCenterSec - left.downloadCenterSec
        if (span <= BISECT_STOP_WINDOW_SEC) return
        val midCenter = (left.downloadCenterSec + right.downloadCenterSec) * 0.5
        val clipStart = (midCenter - 12.5).coerceIn(0.0, (durationSec - 25.0).coerceAtLeast(0.0))
        val clipEnd = (clipStart + 25.0).coerceAtMost(durationSec)
        val mid = matchClip(
            audioPath, clipStart, clipEnd, transcriber, vadModelPath,
            refWords, refNorm, trigramIndex, shouldAbort
        )
        if (mid == null) {
            // Clip landed inside an ad or unaligned span: probe left and right sub-windows so we still find the break
            if (span > BISECT_STOP_WINDOW_SEC * 2.0) {
                val leftProbeCenter = left.downloadCenterSec + span * 0.25
                val rightProbeCenter = left.downloadCenterSec + span * 0.75
                val leftProbe = matchClip(
                    audioPath,
                    (leftProbeCenter - 12.5).coerceIn(0.0, (durationSec - 25.0).coerceAtLeast(0.0)),
                    (leftProbeCenter + 12.5).coerceAtMost(durationSec),
                    transcriber, vadModelPath, refWords, refNorm, trigramIndex, shouldAbort
                )
                val rightProbe = matchClip(
                    audioPath,
                    (rightProbeCenter - 12.5).coerceIn(0.0, (durationSec - 25.0).coerceAtLeast(0.0)),
                    (rightProbeCenter + 12.5).coerceAtMost(durationSec),
                    transcriber, vadModelPath, refWords, refNorm, trigramIndex, shouldAbort
                )
                if (leftProbe != null) {
                    outAnchors.add(leftProbe)
                    if (abs(leftProbe.shiftSec - left.shiftSec) > OFFSET_DISAGREEMENT_SEC) {
                        bisectBreak(
                            audioPath, left, leftProbe, durationSec, transcriber, vadModelPath,
                            refWords, refNorm, trigramIndex, shouldAbort, depth + 1, outAnchors
                        )
                    }
                    if (rightProbe != null) {
                        outAnchors.add(rightProbe)
                        if (abs(rightProbe.shiftSec - leftProbe.shiftSec) > OFFSET_DISAGREEMENT_SEC) {
                            bisectBreak(
                                audioPath, leftProbe, rightProbe, durationSec, transcriber, vadModelPath,
                                refWords, refNorm, trigramIndex, shouldAbort, depth + 1, outAnchors
                            )
                        }
                    } else if (abs(right.shiftSec - leftProbe.shiftSec) > OFFSET_DISAGREEMENT_SEC) {
                        bisectBreak(
                            audioPath, leftProbe, right, durationSec, transcriber, vadModelPath,
                            refWords, refNorm, trigramIndex, shouldAbort, depth + 1, outAnchors
                        )
                    }
                } else if (rightProbe != null) {
                    outAnchors.add(rightProbe)
                    if (abs(rightProbe.shiftSec - left.shiftSec) > OFFSET_DISAGREEMENT_SEC) {
                        bisectBreak(
                            audioPath, left, rightProbe, durationSec, transcriber, vadModelPath,
                            refWords, refNorm, trigramIndex, shouldAbort, depth + 1, outAnchors
                        )
                    }
                    if (abs(right.shiftSec - rightProbe.shiftSec) > OFFSET_DISAGREEMENT_SEC) {
                        bisectBreak(
                            audioPath, rightProbe, right, durationSec, transcriber, vadModelPath,
                            refWords, refNorm, trigramIndex, shouldAbort, depth + 1, outAnchors
                        )
                    }
                }
            }
            return
        }
        outAnchors.add(mid)
        if (abs(mid.shiftSec - left.shiftSec) > OFFSET_DISAGREEMENT_SEC) {
            bisectBreak(
                audioPath, left, mid, durationSec, transcriber, vadModelPath,
                refWords, refNorm, trigramIndex, shouldAbort, depth + 1, outAnchors
            )
        }
        if (abs(right.shiftSec - mid.shiftSec) > OFFSET_DISAGREEMENT_SEC) {
            bisectBreak(
                audioPath, mid, right, durationSec, transcriber, vadModelPath,
                refWords, refNorm, trigramIndex, shouldAbort, depth + 1, outAnchors
            )
        }
    }

    private suspend fun matchClip(
        audioPath: String,
        startSec: Double,
        endSec: Double,
        transcriber: Transcriber,
        vadModelPath: String?,
        refWords: List<Word>,
        refNorm: List<String>,
        trigramIndex: Map<String, List<Int>>,
        shouldAbort: () -> Boolean
    ): AnchorMatch? {
        val pcm = AudioDecoder.decodeRange(audioPath, startSec, endSec, shouldAbort)
        val out = try {
            transcriber.transcribe(pcm, vadModelPath = vadModelPath, shouldAbort = shouldAbort)
        } finally {
            pcm.close()
        }
        val clipWords = out.words.mapNotNull { w ->
            val n = WordMatcher.normalizeToken(w.text)
            if (n.isNotEmpty()) n to (startSec + w.start) else null
        }
        if (clipWords.size < 6) return null

        // Candidate transcript start positions from trigram hits
        val candidateStarts = HashMap<Int, Int>()
        for (i in 0..(clipWords.size - 3)) {
            val key = "${clipWords[i].first}|${clipWords[i + 1].first}|${clipWords[i + 2].first}"
            val hits = trigramIndex[key] ?: continue
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
            val lo = (approxStart - 12).coerceAtLeast(0)
            val hi = (approxStart + clipWords.size + 12).coerceAtMost(refNorm.size - 1)
            val deltas = ArrayList<Double>()
            var refCursor = lo
            var matched = 0
            var midRefIdx = lo
            for ((tok, dlSec) in clipWords) {
                val searchHi = (refCursor + 8).coerceAtMost(hi)
                var foundIdx = -1
                for (r in refCursor..searchHi) {
                    if (refNorm[r] == tok) {
                        foundIdx = r
                        break
                    }
                }
                if (foundIdx >= 0) {
                    matched++
                    deltas.add(dlSec - refWords[foundIdx].start)
                    if (matched == clipWords.size / 2) midRefIdx = foundIdx
                    refCursor = foundIdx + 1
                }
            }
            if (matched > bestCount && matched >= maxOf(6, clipWords.size / 3)) {
                bestCount = matched
                deltas.sort()
                val medianShift = deltas[deltas.size / 2]
                bestAnchor = AnchorMatch(
                    downloadCenterSec = (startSec + endSec) * 0.5,
                    transcriptCenterSec = refWords[midRefIdx].start,
                    shiftSec = medianShift,
                    matchedCount = matched,
                    transcriptWordIndex = midRefIdx
                )
            }
        }
        return bestAnchor
    }

    internal fun buildStretches(anchors: List<AnchorMatch>, refWords: List<Word>): List<OffsetStretch> {
        if (anchors.isEmpty() || refWords.isEmpty()) return emptyList()
        val lastRefMs = (refWords.last().end * 1000.0).toLong().coerceAtLeast(1000L)
        val firstRefMs = (refWords.first().start * 1000.0).toLong().coerceAtLeast(0L)
        val stretches = ArrayList<OffsetStretch>()

        var groupStartSrcMs = firstRefMs
        var groupShiftSum = anchors.first().shiftSec
        var groupCount = 1

        for (i in 1 until anchors.size) {
            val prev = anchors[i - 1]
            val cur = anchors[i]
            val groupAvgShift = groupShiftSum / groupCount
            if (abs(cur.shiftSec - groupAvgShift) > OFFSET_DISAGREEMENT_SEC) {
                // Place break at the last matched transcript word before the gap
                val splitIdx = prev.transcriptWordIndex.coerceIn(0, refWords.lastIndex)
                val splitSrcMs = (refWords[splitIdx].end * 1000.0).toLong()
                if (splitSrcMs > groupStartSrcMs) {
                    stretches.add(
                        OffsetStretch(
                            srcStartMs = groupStartSrcMs,
                            srcEndMs = splitSrcMs,
                            shiftMs = (groupAvgShift * 1000.0).toLong()
                        )
                    )
                }
                groupStartSrcMs = (refWords[(splitIdx + 1).coerceAtMost(refWords.lastIndex)].start * 1000.0).toLong()
                groupShiftSum = cur.shiftSec
                groupCount = 1
            } else {
                groupShiftSum += cur.shiftSec
                groupCount++
            }
        }

        val finalShiftMs = ((groupShiftSum / groupCount) * 1000.0).toLong()
        if (lastRefMs > groupStartSrcMs) {
            stretches.add(
                OffsetStretch(
                    srcStartMs = groupStartSrcMs,
                    srcEndMs = lastRefMs,
                    shiftMs = finalShiftMs
                )
            )
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

    private fun buildPseudoWordsFromSegments(segments: List<Segment>): List<Word> {
        val out = ArrayList<Word>()
        for (seg in segments) {
            val tokens = seg.text.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (tokens.isEmpty()) continue
            val step = (seg.end - seg.start).coerceAtLeast(0.05) / tokens.size
            tokens.forEachIndexed { idx, tok ->
                val ws = seg.start + idx * step
                out.add(Word(ws, ws + step, " $tok"))
            }
        }
        return out
    }
}
