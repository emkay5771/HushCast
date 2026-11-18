package app.hushcast.automute.dsp

/**
 * Computes fast-forward and rewind target positions that skip over
 * active auto-skip ad spans and skipped chapters so seek increments
 * count unskipped content rather than landing inside skipped regions.
 */
object SkipAwareSeekCalculator {

    data class SkipRange(val startMs: Long, val endMs: Long)

    fun mergeRanges(ranges: List<SkipRange>): List<SkipRange> {
        val valid = ranges
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }
        if (valid.isEmpty()) return emptyList()

        val merged = ArrayList<SkipRange>(valid.size)
        var curStart = valid[0].startMs
        var curEnd = valid[0].endMs
        for (i in 1 until valid.size) {
            val r = valid[i]
            if (r.startMs <= curEnd) {
                if (r.endMs > curEnd) curEnd = r.endMs
            } else {
                merged.add(SkipRange(curStart, curEnd))
                curStart = r.startMs
                curEnd = r.endMs
            }
        }
        merged.add(SkipRange(curStart, curEnd))
        return merged
    }

    fun computeForwardTargetMs(
        fromMs: Long,
        incrementMs: Long,
        durationMs: Long,
        skipRanges: List<SkipRange>
    ): Long {
        val safeFrom = fromMs.coerceAtLeast(0L)
        val safeInc = incrementMs.coerceAtLeast(0L)
        val maxPos = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        if (skipRanges.isEmpty()) {
            return (safeFrom + safeInc).coerceAtMost(maxPos)
        }

        val merged = mergeRanges(skipRanges)
        var pos = safeFrom
        var remaining = safeInc

        // If currently inside a skip range, jump to its end first
        val inside = merged.firstOrNull { pos >= it.startMs && pos < it.endMs }
        if (inside != null) {
            pos = inside.endMs
        }

        while (remaining > 0L) {
            val next = merged.firstOrNull { it.startMs >= pos }
            if (next == null || pos + remaining <= next.startMs) {
                pos += remaining
                remaining = 0L
            } else {
                val consumed = (next.startMs - pos).coerceAtLeast(0L)
                remaining -= consumed
                pos = next.endMs
            }
        }

        return pos.coerceIn(0L, maxPos)
    }

    fun computeBackTargetMs(
        fromMs: Long,
        incrementMs: Long,
        skipRanges: List<SkipRange>
    ): Long {
        val safeFrom = fromMs.coerceAtLeast(0L)
        val safeInc = incrementMs.coerceAtLeast(0L)
        if (skipRanges.isEmpty()) {
            return (safeFrom - safeInc).coerceAtLeast(0L)
        }

        val merged = mergeRanges(skipRanges)
        var pos = safeFrom
        var remaining = safeInc

        // If currently inside a skip range, jump to its start first
        val inside = merged.firstOrNull { pos > it.startMs && pos <= it.endMs }
        if (inside != null) {
            pos = inside.startMs
        }

        while (remaining > 0L && pos > 0L) {
            val prev = merged.lastOrNull { it.endMs <= pos }
            if (prev == null || pos - remaining >= prev.endMs) {
                pos -= remaining
                remaining = 0L
            } else {
                val consumed = (pos - prev.endMs).coerceAtLeast(0L)
                remaining -= consumed
                pos = prev.startMs
            }
        }

        return pos.coerceAtLeast(0L)
    }

    /**
     * Computes wall-clock milliseconds remaining from [fromMs] to [targetEndMs],
     * subtracting any overlapping [skipRanges] and dividing by [speed].
     */
    fun computeWallRemainingMs(
        fromMs: Long,
        targetEndMs: Long,
        speed: Float,
        skipRanges: List<SkipRange>
    ): Long {
        if (targetEndMs <= fromMs) return 0L
        var audioMs = targetEndMs - fromMs
        if (skipRanges.isNotEmpty()) {
            for (r in mergeRanges(skipRanges)) {
                val overlapStart = maxOf(fromMs, r.startMs)
                val overlapEnd = minOf(targetEndMs, r.endMs)
                if (overlapEnd > overlapStart) {
                    audioMs -= (overlapEnd - overlapStart)
                }
            }
        }
        val safeSpeed = speed.coerceAtLeast(0.5f).toDouble()
        return (audioMs.coerceAtLeast(0L) / safeSpeed).toLong()
    }
}
