package app.hushcast.automute.transcript

import app.hushcast.automute.dsp.MuteWindow
import app.hushcast.automute.engine.Word
import app.hushcast.automute.matcher.WordMatcher
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/**
 * A stretch of the download timeline whose words are a publisher cue (SRT/VTT) spread evenly over
 * the cue, so their times are estimates.  [attempted] is set once a re-alignment ran and failed, so
 * it is not retried on every play.
 */
@Serializable
data class ApproxSpan(val start: Double, val end: Double, val attempted: Boolean = false)

/**
 * Word times for SRT/VTT transcripts.  Cues only give a start and end per line, so the analyzer
 * spreads each cue's words evenly over it.  Where a word matches a mute rule, Whisper is run on
 * the cue +-[PAD_SEC] and the cue's words are aligned to what it heard.  Rules added later find
 * matches in cues that were never aligned; those are re-aligned the next time the episode is
 * analyzed or played ([realign]), and until then their mute windows cover the likely range
 * ([widenApproximate]).
 */
object CueRealigner {
    const val PAD_SEC = 2.0
    private const val WIDEN_MS = 2_000L

    fun pseudoWords(text: String, start: Double, end: Double): List<Word> {
        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val step = (end - start).coerceAtLeast(0.05) / tokens.size
        return tokens.mapIndexed { idx, tok ->
            val ws = start + idx * step
            Word(ws, ws + step, " $tok")
        }
    }

    data class Aligned(val words: List<Word>, val matched: Int)

    /**
     * Gives each cue word the time Whisper heard it at.  The cue's words and their count are kept
     * (publisher text is usually the better text); a longest-common-subsequence of normalized
     * tokens pairs them with [heard] words, and unmatched cue words are spread evenly between
     * their matched neighbours.  Among equally long pairings the one closest to the cue's own
     * times wins, so a "the" heard in the +-2 s padding does not steal the cue's first "the".
     */
    internal fun alignTokens(cueWords: List<Word>, heard: List<Word>): Aligned {
        val n = cueWords.size
        if (n == 0) return Aligned(emptyList(), 0)
        val a = cueWords.map { WordMatcher.normalizeToken(it.text) }
        val b = heard.map { WordMatcher.normalizeToken(it.text) }
        val m = b.size
        // Each pair scores 1 plus a closeness bonus; all bonuses together stay below 1, so the
        // number of pairs always wins and the bonus only breaks ties.
        val bonusScale = 0.5 / n
        val dp = Array(n + 1) { DoubleArray(m + 1) }
        val choice = Array(n) { ByteArray(m) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            var best = dp[i + 1][j]
            var c: Byte = 0
            if (dp[i][j + 1] > best) { best = dp[i][j + 1]; c = 1 }
            if (a[i].isNotEmpty() && a[i] == b[j]) {
                val dt = kotlin.math.abs(heard[j].start - cueWords[i].start).coerceAtMost(5.0)
                val take = dp[i + 1][j + 1] + 1.0 + bonusScale * (1.0 - dt / 5.0)
                if (take > best) { best = take; c = 2 }
            }
            dp[i][j] = best
            choice[i][j] = c
        }
        val starts = DoubleArray(n) { Double.NaN }
        val ends = DoubleArray(n) { Double.NaN }
        var i = 0
        var j = 0
        var matched = 0
        while (i < n && j < m) {
            when (choice[i][j].toInt()) {
                2 -> {
                    starts[i] = heard[j].start
                    ends[i] = max(heard[j].end, heard[j].start + 0.01)
                    matched++
                    i++; j++
                }
                1 -> j++
                else -> i++
            }
        }
        if (matched == 0) return Aligned(cueWords, 0)

        val typical = (cueWords.first().end - cueWords.first().start).coerceIn(0.15, 0.6)
        var k = 0
        while (k < n) {
            if (!starts[k].isNaN()) { k++; continue }
            var e = k
            while (e < n && starts[e].isNaN()) e++
            val count = e - k
            val prevEnd = if (k > 0) ends[k - 1] else Double.NaN
            val nextStart = if (e < n) starts[e] else Double.NaN
            val lo: Double
            val hi: Double
            when {
                !prevEnd.isNaN() && !nextStart.isNaN() -> { lo = prevEnd; hi = max(nextStart, prevEnd) }
                !prevEnd.isNaN() -> { lo = prevEnd; hi = prevEnd + count * typical }
                else -> { hi = nextStart; lo = nextStart - count * typical }
            }
            val step = (hi - lo) / count
            for (t in 0 until count) {
                starts[k + t] = lo + t * step
                ends[k + t] = lo + (t + 1) * step
            }
            k = e
        }
        val out = cueWords.mapIndexed { idx, w ->
            w.copy(start = starts[idx], end = max(ends[idx], starts[idx] + 0.01))
        }
        return Aligned(out, matched)
    }

    /** Word indices whose start lies in [span]; pseudo-words are generated inside their cue. */
    private fun indicesIn(words: List<Word>, span: ApproxSpan): IntRange? {
        var lo = 0
        var hi = words.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (words[mid].start < span.start) lo = mid + 1 else hi = mid
        }
        var end = lo
        while (end < words.size && words[end].start < span.end) end++
        return if (end > lo) lo until end else null
    }

    /** Spans not yet tried whose words match one of [compiled]'s rules. */
    fun spansNeedingRealign(words: List<Word>, spans: List<ApproxSpan>, compiled: WordMatcher.CompiledRules): List<ApproxSpan> {
        if (spans.isEmpty() || compiled.isEmpty || words.isEmpty()) return emptyList()
        return spans.filter { span ->
            !span.attempted && indicesIn(words, span)?.let { r ->
                WordMatcher.matchWords(words.subList(r.first, r.last + 1), compiled).isNotEmpty()
            } == true
        }
    }

    data class RealignResult(val words: List<Word>, val spans: List<ApproxSpan>, val realigned: Int, val failed: Int)

    /**
     * Runs Whisper around each approximate span that holds a rule match and aligns its words.
     * [words] must be sorted by start.  Spans that align leave the list; ones that do not are
     * kept with [ApproxSpan.attempted] set.
     */
    suspend fun realign(
        words: List<Word>,
        spans: List<ApproxSpan>,
        compiled: WordMatcher.CompiledRules,
        durationSec: Double,
        clips: ClipTranscriber
    ): RealignResult {
        val todo = spansNeedingRealign(words, spans, compiled).toHashSet()
        if (todo.isEmpty()) return RealignResult(words, spans, 0, 0)
        val out = words.toMutableList()
        val keptSpans = ArrayList<ApproxSpan>(spans.size)
        var realigned = 0
        var failed = 0
        for (span in spans) {
            if (span !in todo) {
                keptSpans.add(span)
                continue
            }
            yield()
            val range = indicesIn(words, span)
            if (range == null) continue
            val cueWords = words.subList(range.first, range.last + 1)
            val s = (span.start - PAD_SEC).coerceAtLeast(0.0)
            val e = if (durationSec > 0.0) min(durationSec, span.end + PAD_SEC) else span.end + PAD_SEC
            val heard = clips.transcribe(s, e).words.map { it.copy(start = s + it.start, end = s + it.end) }
            val aligned = alignTokens(cueWords, heard)
            if (isGoodAlignment(aligned, cueWords.size)) {
                for ((k, w) in aligned.words.withIndex()) out[range.first + k] = w
                realigned++
            } else {
                keptSpans.add(span.copy(attempted = true))
                failed++
            }
        }
        out.sortBy { it.start }
        return RealignResult(out, keptSpans, realigned, failed)
    }

    /** At least a third of the cue (and one word) was heard. */
    internal fun isGoodAlignment(aligned: Aligned, cueSize: Int): Boolean =
        aligned.matched >= 1 && aligned.matched * 3 >= cueSize

    /**
     * Mute windows that fall in an approximate span are widened to the span, at most
     * [WIDEN_MS] either side, so the word is muted wherever in its cue it is said.
     */
    fun widenApproximate(windows: List<MuteWindow>, spans: List<ApproxSpan>): List<MuteWindow> {
        if (windows.isEmpty() || spans.isEmpty()) return windows
        val sorted = spans.sortedBy { it.start }
        val starts = DoubleArray(sorted.size) { sorted[it].start }
        var changed = false
        val widened = windows.map { w ->
            val center = (w.startMs + w.endMs) / 2000.0
            var idx = java.util.Arrays.binarySearch(starts, center)
            if (idx < 0) idx = -idx - 2
            val span = sorted.getOrNull(idx)?.takeIf { center < it.end } ?: return@map w
            val spanStartMs = (span.start * 1000.0).toLong()
            val spanEndMs = (span.end * 1000.0).toLong()
            val newStart = min(w.startMs, max(spanStartMs, w.startMs - WIDEN_MS)).coerceAtLeast(0L)
            val newEnd = max(w.endMs, min(spanEndMs, w.endMs + WIDEN_MS))
            if (newStart != w.startMs || newEnd != w.endMs) changed = true
            w.copy(startMs = newStart, endMs = newEnd)
        }
        return if (changed) WordMatcher.mergeOverlappingWindows(widened) else windows
    }
}
