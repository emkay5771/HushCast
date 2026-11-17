package app.hushcast.automute

import app.hushcast.automute.engine.Decoder
import app.hushcast.automute.engine.FrameExtractor
import app.hushcast.automute.engine.Frames
import app.hushcast.automute.engine.Segment
import app.hushcast.automute.engine.SegmentFeatures
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden parity test for SegmentFeatures and Decoder (W6).
 * Matches the deterministic fixture in desktop-worker/tests/test_worker_api.py.
 */
class FeaturesAndDecoderParityTest {

    @Test
    fun goldenSegmentFeaturesAndDecoderMatchReference() {
        val nFrames = 40 // 4.0 seconds at 10 Hz
        val nb = FrameExtractor.N_BANDS
        val lk = FloatArray(nFrames) { i -> -24.0f + (i % 5) * 0.5f }
        val rms = FloatArray(nFrames) { -22.0f }
        val rms10max = FloatArray(nFrames) { -18.0f }
        val rms10min = FloatArray(nFrames) { -30.0f }
        val flatness = FloatArray(nFrames) { 0.15f }
        val side = FloatArray(nFrames) { -35.0f }
        val zcr = FloatArray(nFrames) { 0.08f }
        val cpp = FloatArray(nFrames) { i -> if (i % 2 == 0) 4.5f else 0.5f }
        val f0 = FloatArray(nFrames) { i -> 140.0f + (i % 4) * 10.0f }
        val centroid = FloatArray(nFrames) { 1200.0f }
        val flux = FloatArray(nFrames) { -30.0f }
        val bands = FloatArray(nFrames * nb) { idx -> -40.0f + (idx % nb) }

        val frames = Frames(
            n = nFrames,
            stereo = true,
            lk = lk,
            rms = rms,
            rms10max = rms10max,
            rms10min = rms10min,
            flatness = flatness,
            side = side,
            zcr = zcr,
            cpp = cpp,
            f0 = f0,
            centroid = centroid,
            flux = flux,
            bands = bands
        )
        val segments = listOf(
            Segment(0.0, 1.0, "Hello world", nWords = 2, speaker = 0),
            Segment(1.0, 2.0, "Sponsored by Acme", nWords = 3, speaker = 1),
            Segment(2.0, 3.0, "Use code HUSH", nWords = 3, speaker = 1),
            Segment(3.0, 4.0, "Back to the show", nWords = 4, speaker = 0)
        )

        val feats = SegmentFeatures.compute(frames, segments)
        assertEquals(4 * SegmentFeatures.COUNT, feats.size)
        // Check rel_pos, seg_dur, n_words, spk_change for segment 1
        val row1 = 1 * SegmentFeatures.COUNT
        assertEquals(0.25f, feats[row1 + 14], 1e-5f) // rel_pos = 1.0 / 4.0
        assertEquals(1.0f, feats[row1 + 15], 1e-5f)  // seg_dur
        assertEquals(3.0f, feats[row1 + 16], 1e-5f)  // n_words
        assertEquals(1.0f, feats[row1 + 17], 1e-5f)  // spk_change

        // Decoder golden test: 5 classes x 4 segments
        val probs = floatArrayOf(
            0.90f, 0.05f, 0.02f, 0.02f, 0.01f, // content
            0.05f, 0.90f, 0.02f, 0.02f, 0.01f, // sponsor_ad
            0.05f, 0.85f, 0.05f, 0.03f, 0.02f, // sponsor_ad
            0.92f, 0.03f, 0.02f, 0.02f, 0.01f  // content
        )
        val spans = Decoder.findSpans(probs, segments)
        assertEquals(1, spans.size)
        assertEquals("sponsor_ad", spans[0].type)
        assertEquals(1.0, spans[0].start, 1e-6)
        assertEquals(3.0, spans[0].end, 1e-6)
        assertEquals(0.875, spans[0].p, 1e-3)
    }
}
