package app.hushcast.automute.transcript

import app.hushcast.automute.engine.Segment
import app.hushcast.automute.engine.Word
import kotlinx.serialization.Serializable

/**
 * A contiguous region in the source timeline (publisher transcript / chapters, or prior analysis)
 * that maps to the current download timeline via `downloadMs = srcMs + shiftMs`.
 */
@Serializable
data class OffsetStretch(
    val srcStartMs: Long,
    val srcEndMs: Long,
    val shiftMs: Long
) {
    val dstStartMs: Long get() = (srcStartMs + shiftMs).coerceAtLeast(0L)
    val dstEndMs: Long get() = (srcEndMs + shiftMs).coerceAtLeast(dstStartMs)
}

/**
 * Generic piecewise-constant timeline mapping between a clean/reference timeline and a DAI download.
 * Used both for publisher transcripts/chapters (DAI Retiming Bridge) and prior-analysis reuse (R3).
 */
@Serializable
data class OffsetMap(
    val stretches: List<OffsetStretch>
) {
    fun isEmpty(): Boolean = stretches.isEmpty()

    fun shiftTimeMs(srcMs: Long): Long {
        if (stretches.isEmpty()) return srcMs
        for (s in stretches) {
            if (srcMs in s.srcStartMs..s.srcEndMs) {
                return (srcMs + s.shiftMs).coerceAtLeast(0L)
            }
        }
        if (srcMs < stretches.first().srcStartMs) {
            return (srcMs + stretches.first().shiftMs).coerceAtLeast(0L)
        }
        for (i in 0 until stretches.lastIndex) {
            val a = stretches[i]
            val b = stretches[i + 1]
            if (srcMs > a.srcEndMs && srcMs < b.srcStartMs) {
                val nearest = if (srcMs - a.srcEndMs <= b.srcStartMs - srcMs) a else b
                return (srcMs + nearest.shiftMs).coerceAtLeast(0L)
            }
        }
        return (srcMs + stretches.last().shiftMs).coerceAtLeast(0L)
    }

    fun shiftTimeSec(srcSec: Double): Double = shiftTimeMs((srcSec * 1000.0).toLong()) / 1000.0

    /**
     * Shifts each word as a unit, by the offset at its midpoint.  (Shifting start and end
     * separately stretched a word that touches a break, e.g. contiguous words where one ends
     * exactly where the next stretch starts, across the whole ad.)
     */
    fun applyToWords(words: List<Word>): List<Word> {
        if (stretches.isEmpty()) return words
        return words.map { w ->
            val midMs = ((w.start + w.end) * 500.0).toLong()
            val d = (shiftTimeMs(midMs) - midMs) / 1000.0
            val s = (w.start + d).coerceAtLeast(0.0)
            w.copy(start = s, end = (w.end + d).coerceAtLeast(s + 0.01))
        }
    }

    fun applyToSegments(segments: List<Segment>): List<Segment> {
        if (stretches.isEmpty()) return segments
        return segments.map { seg ->
            val s = shiftTimeSec(seg.start)
            val e = shiftTimeSec(seg.end).coerceAtLeast(s + 0.01)
            seg.copy(start = s, end = e)
        }
    }

    /**
     * Returns uncovered intervals `[startMs, endMs]` in the download timeline of length >= [minGapMs].
     * These correspond to dynamically inserted ad blocks not present in the source transcript.
     */
    fun uncoveredGaps(totalDurationMs: Long, minGapMs: Long = 3500L): List<Pair<Long, Long>> {
        if (totalDurationMs <= 0L) return emptyList()
        if (stretches.isEmpty()) return listOf(0L to totalDurationMs)
        val covered = stretches
            .map { it.dstStartMs.coerceIn(0L, totalDurationMs) to it.dstEndMs.coerceIn(0L, totalDurationMs) }
            .filter { it.second > it.first }
            .sortedBy { it.first }
        if (covered.isEmpty()) return listOf(0L to totalDurationMs)

        val merged = ArrayList<Pair<Long, Long>>(covered.size)
        var cur = covered[0]
        for (i in 1 until covered.size) {
            val nxt = covered[i]
            if (nxt.first <= cur.second + 500L) {
                cur = cur.first to maxOf(cur.second, nxt.second)
            } else {
                merged.add(cur)
                cur = nxt
            }
        }
        merged.add(cur)

        val gaps = ArrayList<Pair<Long, Long>>()
        // Leading gap is only a DAI pre-roll if the first stretch has a measured offset shift
        if (merged[0].first >= minGapMs && kotlin.math.abs(stretches.first().shiftMs) >= minGapMs) {
            gaps.add(0L to merged[0].first)
        }
        for (i in 0 until merged.lastIndex) {
            val gapStart = merged[i].second
            val gapEnd = merged[i + 1].first
            if (gapEnd - gapStart >= minGapMs) {
                gaps.add(gapStart to gapEnd)
            }
        }
        return gaps
    }
}
