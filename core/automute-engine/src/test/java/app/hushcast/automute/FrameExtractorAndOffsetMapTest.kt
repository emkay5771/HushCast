package app.hushcast.automute

import app.hushcast.automute.engine.FrameExtractor
import app.hushcast.automute.transcript.OffsetMap
import app.hushcast.automute.transcript.OffsetStretch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FrameExtractorAndOffsetMapTest {

    @Test
    fun frameExtractorPackedFftProducesFiniteFeatures() {
        val extractor = FrameExtractor()
        val sr = FrameExtractor.SR
        val nSamples = (sr * 1.2).toInt()
        val mid = FloatArray(nSamples) { i -> (0.25 * sin(2.0 * PI * 220.0 * i / sr)).toFloat() }
        val side = FloatArray(nSamples) { i -> (0.05 * sin(2.0 * PI * 440.0 * i / sr)).toFloat() }
        extractor.feed(mid, side)
        val frames = extractor.finish()

        assertEquals(13, frames.n)
        assertTrue(frames.stereo)
        for (i in 0 until frames.n) {
            assertTrue(frames.lk[i].isFinite())
            assertTrue(frames.centroid[i].isFinite() && frames.centroid[i] > 0f)
            assertTrue(frames.flatness[i].isFinite())
            assertTrue(frames.flux[i].isFinite())
            assertTrue(frames.cpp[i].isFinite())
            assertTrue(frames.f0[i].isFinite())
        }
        // Parity assertions for a pure 220 Hz sine wave (mid=0.25)
        val midIdx = frames.n / 2
        assertEquals(220f, frames.centroid[midIdx], 60f)
        assertEquals(220f, frames.f0[midIdx], 25f)
        assertTrue("Expected tonal low flatness, got ${frames.flatness[midIdx]}", frames.flatness[midIdx] < 0.15f)
        assertTrue("Expected loudness near -15 dBFS, got ${frames.lk[midIdx]}", frames.lk[midIdx] in -25f..-5f)
    }

    @Test
    fun offsetMapShiftsTimesAndFindsUncoveredGaps() {
        val map = OffsetMap(
            listOf(
                OffsetStretch(srcStartMs = 0L, srcEndMs = 300_000L, shiftMs = 30_000L),
                OffsetStretch(srcStartMs = 300_000L, srcEndMs = 600_000L, shiftMs = 90_000L)
            )
        )
        assertEquals(45_000L, map.shiftTimeMs(15_000L))
        assertEquals(410_000L, map.shiftTimeMs(320_000L))

        val gaps = map.uncoveredGaps(totalDurationMs = 750_000L, minGapMs = 3_500L)
        assertEquals(
            listOf(
                0L to 30_000L,        // pre-roll DAI ad (measured shift > 0)
                330_000L to 390_000L  // mid-roll DAI ad (interior gap between stretches)
            ),
            gaps
        )

        // Leading/trailing gaps with zero offset shift (intro/outro music) must NOT be flagged
        val noDaiMap = OffsetMap(
            listOf(OffsetStretch(srcStartMs = 10_000L, srcEndMs = 590_000L, shiftMs = 0L))
        )
        assertEquals(emptyList<Pair<Long, Long>>(), noDaiMap.uncoveredGaps(totalDurationMs = 600_000L, minGapMs = 3_500L))
    }
}
