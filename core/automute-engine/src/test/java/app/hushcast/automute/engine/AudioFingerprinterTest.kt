package app.hushcast.automute.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class AudioFingerprinterTest {

    private fun synthSegment(durationSec: Double, seed: Int): FloatArray {
        val rng = Random(seed)
        val n = (durationSec * AudioFingerprinter.SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        val freqs = DoubleArray(6) { 220.0 + rng.nextDouble() * 2500.0 }
        val mods = DoubleArray(6) { 1.5 + rng.nextDouble() * 4.5 }
        for (i in 0 until n) {
            val t = i.toDouble() / AudioFingerprinter.SAMPLE_RATE
            var acc = 0.0
            for (k in 0 until 6) {
                acc += 0.15 * sin(2.0 * PI * (freqs[k] + 40.0 * sin(2.0 * PI * mods[k] * t)) * t)
            }
            acc += 0.04 * (rng.nextDouble() * 2.0 - 1.0)
            out[i] = acc.toFloat()
        }
        return out
    }

    @Test
    fun `detects recurring intro, outro, and mid-roll promo across episodes`() {
        val intro = synthSegment(12.0, seed = 101)
        val outro = synthSegment(10.0, seed = 202)
        val promo = synthSegment(9.0, seed = 303)

        val ep1 = intro + synthSegment(35.0, seed = 1) + promo + synthSegment(35.0, seed = 2) + outro
        val preroll = synthSegment(4.0, seed = 404)
        val ep2Raw = preroll + intro + synthSegment(30.0, seed = 3) + promo + synthSegment(30.0, seed = 4) + outro
        val rng = Random(999)
        val ep2 = FloatArray(ep2Raw.size) { i ->
            (ep2Raw[i] * 0.85f + 0.004f * (rng.nextFloat() * 2f - 1f))
        }

        val fp1 = AudioFingerprinter.compute(ep1, fileSha256 = "aaaa", podcastOrigin = "https://example.com/feed.xml")
        val fp2 = AudioFingerprinter.compute(ep2, fileSha256 = "bbbb", podcastOrigin = "https://example.com/feed.xml")

        val spans = AudioFingerprinter.findRepeatedSegments(fp2, listOf(fp1))
        val classes = spans.map { it.adClass to it.label }.toSet()

        assertTrue("Expected Intro in $classes", ("boilerplate" to "Intro") in classes)
        assertTrue("Expected Outro in $classes", ("boilerplate" to "Outro") in classes)
        assertTrue("Expected Recurring Promo in $classes", ("house_promo" to "Recurring Promo") in classes)
    }

    @Test
    fun `compressed serialization round-trips cleanly`() {
        val pcm = synthSegment(15.0, seed = 42)
        val fp = AudioFingerprinter.compute(pcm, fileSha256 = "sha1", podcastOrigin = "origin1")
        val bytes = fp.toCompressedBytes()
        val restored = AudioFingerprinter.Fingerprint.fromCompressedBytes(bytes, "sha1", "origin1")

        assertEquals(fp.durationMs, restored.durationMs)
        assertEquals(fp.hashes.size, restored.hashes.size)
        assertTrue(fp.hashes.contentEquals(restored.hashes))
        assertTrue(fp.active.contentEquals(restored.active))
    }

    @Test
    fun `silence does not false-match`() {
        val silence = FloatArray(20 * AudioFingerprinter.SAMPLE_RATE)
        val ep1 = silence + synthSegment(15.0, seed = 11)
        val ep2 = silence + synthSegment(15.0, seed = 22)

        val fp1 = AudioFingerprinter.compute(ep1, fileSha256 = "c1", podcastOrigin = "show")
        val fp2 = AudioFingerprinter.compute(ep2, fileSha256 = "c2", podcastOrigin = "show")

        val spans = AudioFingerprinter.findRepeatedSegments(fp2, listOf(fp1))
        assertTrue(spans.isEmpty())
    }
}
