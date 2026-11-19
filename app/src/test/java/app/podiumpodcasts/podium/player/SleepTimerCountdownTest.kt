package app.podiumpodcasts.podium.player

import app.hushcast.automute.dsp.SkipAwareSeekCalculator.SkipRange
import app.podiumpodcasts.podium.background.SleepTimerModes
import app.podiumpodcasts.podium.ui.vm.SleepTimerCountdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerCountdownTest {

    @Test
    fun `end of episode stops at the duration, end of chapter at the chapter end`() {
        assertEquals(3_600_000L, SleepTimerCountdown.targetEndMs(SleepTimerModes.END_OF_EPISODE, -1L, 3_600_000L))
        assertEquals(900_000L, SleepTimerCountdown.targetEndMs(SleepTimerModes.END_OF_CHAPTER, 900_000L, 3_600_000L))
    }

    @Test
    fun `no target for plain timers or unknown values`() {
        assertNull(SleepTimerCountdown.targetEndMs(SleepTimerModes.TIME, 900_000L, 3_600_000L))
        assertNull(SleepTimerCountdown.targetEndMs(null, 900_000L, 3_600_000L))
        // Duration not known yet (the player reports 1 ms).
        assertNull(SleepTimerCountdown.targetEndMs(SleepTimerModes.END_OF_EPISODE, -1L, 1L))
        assertNull(SleepTimerCountdown.targetEndMs(SleepTimerModes.END_OF_CHAPTER, -1L, 3_600_000L))
    }

    @Test
    fun `remaining time is audio left divided by speed`() {
        // 10 minutes of audio at 1x, 2x and 1.5x.
        assertEquals(600_000L, SleepTimerCountdown.remainingWallMs(0L, 600_000L, 1f))
        assertEquals(300_000L, SleepTimerCountdown.remainingWallMs(0L, 600_000L, 2f))
        assertEquals(400_000L, SleepTimerCountdown.remainingWallMs(0L, 600_000L, 1.5f))
    }

    @Test
    fun `remaining time depends only on position, so it holds still while paused`() {
        val position = 120_000L
        val first = SleepTimerCountdown.remainingWallMs(position, 600_000L, 1f)
        // Same position later (paused): same answer, no wall-clock run-down to zero.
        val later = SleepTimerCountdown.remainingWallMs(position, 600_000L, 1f)
        assertEquals(first, later)
        assertEquals(8, SleepTimerCountdown.displayMinutes(later))
    }

    @Test
    fun `skipped ranges are not counted`() {
        val skips = listOf(SkipRange(100_000L, 160_000L))
        assertEquals(540_000L, SleepTimerCountdown.remainingWallMs(0L, 600_000L, 1f, skips))
        // A skip that's already behind the position doesn't count.
        assertEquals(400_000L, SleepTimerCountdown.remainingWallMs(200_000L, 600_000L, 1f, skips))
    }

    @Test
    fun `past the target or bad speed`() {
        assertEquals(0L, SleepTimerCountdown.remainingWallMs(700_000L, 600_000L, 1f))
        assertEquals(600_000L, SleepTimerCountdown.remainingWallMs(-5_000L, 600_000L, 1f))
        // Zero/NaN speed is treated as 1x instead of dividing by zero.
        assertEquals(600_000L, SleepTimerCountdown.remainingWallMs(0L, 600_000L, 0f))
        assertEquals(600_000L, SleepTimerCountdown.remainingWallMs(0L, 600_000L, Float.NaN))
    }

    @Test
    fun `minutes round up and reach zero only at the end`() {
        assertEquals(0, SleepTimerCountdown.displayMinutes(0L))
        assertEquals(1, SleepTimerCountdown.displayMinutes(1L))
        assertEquals(1, SleepTimerCountdown.displayMinutes(59_000L))
        assertEquals(1, SleepTimerCountdown.displayMinutes(60_000L))
        assertEquals(2, SleepTimerCountdown.displayMinutes(60_001L))
    }

    @Test
    fun `plain timers count down in wall-clock time`() {
        val now = 1_000_000L
        assertEquals(15, SleepTimerCountdown.wallClockMinutes(now + 15 * 60_000L, now))
        assertEquals(0, SleepTimerCountdown.wallClockMinutes(now - 10_000L, now))
    }

    @Test
    fun `only chapter and episode timers are position based`() {
        assertTrue(SleepTimerModes.isPositionBased(SleepTimerModes.END_OF_CHAPTER))
        assertTrue(SleepTimerModes.isPositionBased(SleepTimerModes.END_OF_EPISODE))
        assertFalse(SleepTimerModes.isPositionBased(SleepTimerModes.TIME))
        assertFalse(SleepTimerModes.isPositionBased(null))
    }
}
