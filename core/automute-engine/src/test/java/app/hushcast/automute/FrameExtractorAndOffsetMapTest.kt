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
                0L to 30_000L,        // pre-roll DAI ad
                330_000L to 390_000L, // mid-roll DAI ad
                690_000L to 750_000L  // post-roll DAI ad
            ),
            gaps
        )
    }
}
