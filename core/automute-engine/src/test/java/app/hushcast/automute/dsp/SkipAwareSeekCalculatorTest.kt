package app.hushcast.automute.dsp

import app.hushcast.automute.dsp.SkipAwareSeekCalculator.SkipRange
import org.junit.Assert.assertEquals
import org.junit.Test

class SkipAwareSeekCalculatorTest {

    @Test
    fun forwardSkipsOverAdAndCountsUnskippedTime() {
        val ranges = listOf(SkipRange(10_000L, 40_000L))
        // Start at 5s, +30s: 5s..10s (5s) + skip 10s..40s + 25s -> 65s
        val target = SkipAwareSeekCalculator.computeForwardTargetMs(
            fromMs = 5_000L,
            incrementMs = 30_000L,
            durationMs = 300_000L,
            skipRanges = ranges
        )
        assertEquals(65_000L, target)
    }

    @Test
    fun forwardInsideAdJumpsPastAdFirst() {
        val ranges = listOf(SkipRange(10_000L, 40_000L))
        val target = SkipAwareSeekCalculator.computeForwardTargetMs(
            fromMs = 20_000L,
            incrementMs = 30_000L,
            durationMs = 300_000L,
            skipRanges = ranges
        )
        assertEquals(70_000L, target)
    }

    @Test
    fun backRightAfterAdSkipsBackOverAd() {
        val ranges = listOf(SkipRange(10_000L, 40_000L))
        // At 45s, -10s: 45s..40s (5s) + skip 40s..10s + 5s -> 5s
        val target = SkipAwareSeekCalculator.computeBackTargetMs(
            fromMs = 45_000L,
            incrementMs = 10_000L,
            skipRanges = ranges
        )
        assertEquals(5_000L, target)
    }

    @Test
    fun forwardClampsToDuration() {
        val ranges = listOf(SkipRange(80_000L, 120_000L))
        val target = SkipAwareSeekCalculator.computeForwardTargetMs(
            fromMs = 75_000L,
            incrementMs = 30_000L,
            durationMs = 100_000L,
            skipRanges = ranges
        )
        assertEquals(100_000L, target)
    }
}
