package app.hushcast.automute

import app.hushcast.automute.engine.FrameExtractor
import app.hushcast.automute.engine.Frames
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * Parity between [FrameExtractor] and the worker's `extract_audio_frames`
 * (desktop-worker/hushcast_worker/ad_tagger.py) on the synthetic signal in
 * src/test/resources/frame_parity (see generate_frame_parity.py there).  Both sides get the
 * same 24 kHz mid/side samples, so any difference is in the extraction itself.
 */
class FrameExtractorParityTest {

    private class Fixture(val mid: FloatArray, val side: FloatArray, val frames: JSONObject)

    private fun load(): Fixture {
        val text = javaClass.classLoader!!.getResourceAsStream("frame_parity/frame_parity.json")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val doc = JSONObject(text)
        val raw = GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(doc.getString("input_data"))))
            .use { it.readBytes() }
        val shorts = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val n = doc.getInt("samples")
        assertEquals(2 * n, shorts.remaining())
        val mid = FloatArray(n)
        val side = FloatArray(n)
        for (i in 0 until n) {
            mid[i] = shorts.get(2 * i) / 32768f
            side[i] = shorts.get(2 * i + 1) / 32768f
        }
        return Fixture(mid, side, doc.getJSONObject("frames"))
    }

    /** Feeds in uneven chunks so the streaming buffer and drain paths are exercised too. */
    private fun extract(f: Fixture): Frames {
        val ex = FrameExtractor()
        var pos = 0
        val sizes = intArrayOf(1000, 4096, 2399, 7, 12_345, 2400)
        var k = 0
        while (pos < f.mid.size) {
            val c = minOf(sizes[k++ % sizes.size], f.mid.size - pos)
            ex.feed(f.mid.copyOfRange(pos, pos + c), f.side.copyOfRange(pos, pos + c))
            pos += c
        }
        return ex.finish()
    }

    private fun JSONArray.floats() = FloatArray(length()) { getDouble(it).toFloat() }

    private data class Diff(val col: String, val frame: Int, val kotlin: Float, val python: Float)

    private fun compare(name: String, k: FloatArray, p: FloatArray, tol: (Float) -> Float, out: MutableList<Diff>): Float {
        assertEquals("$name length", p.size, k.size)
        var worst = 0f
        for (i in k.indices) {
            val d = abs(k[i] - p[i])
            worst = max(worst, d)
            if (d > tol(p[i])) out.add(Diff(name, i, k[i], p[i]))
        }
        return worst
    }

    @Test
    fun `FrameExtractor matches the worker's AudioFrames on a synthetic chirp`() {
        val fx = load()
        val k = extract(fx)
        val p = fx.frames
        assertEquals(p.getInt("n"), k.n)
        assertEquals(p.getBoolean("stereo"), k.stereo)

        val diffs = ArrayList<Diff>()
        val worst = LinkedHashMap<String, Float>()
        // dB columns: 0.01 dB is far below anything the tagger can see (features are dB means/stdevs).
        val db = { _: Float -> 0.01f }
        worst["lk"] = compare("lk", k.lk, p.getJSONArray("lk").floats(), db, diffs)
        worst["rms"] = compare("rms", k.rms, p.getJSONArray("rms").floats(), db, diffs)
        worst["rms10max"] = compare("rms10max", k.rms10max, p.getJSONArray("rms10max").floats(), db, diffs)
        worst["rms10min"] = compare("rms10min", k.rms10min, p.getJSONArray("rms10min").floats(), db, diffs)
        worst["flatness"] = compare("flatness", k.flatness, p.getJSONArray("flatness").floats(), db, diffs)
        worst["flux"] = compare("flux", k.flux, p.getJSONArray("flux").floats(), db, diffs)
        worst["cpp"] = compare("cpp", k.cpp, p.getJSONArray("cpp").floats(), db, diffs)
        worst["side"] = compare("side", k.side, p.getJSONArray("side").floats(), db, diffs)
        // ZCR is a count / 2399, so any difference is at least one crossing.
        worst["zcr"] = compare("zcr", k.zcr, p.getJSONArray("zcr").floats(), { 1e-6f }, diffs)
        // Centroid in Hz: relative 1e-5.
        worst["centroid"] = compare("centroid", k.centroid, p.getJSONArray("centroid").floats(), { max(1e-5f * abs(it), 1e-3f) }, diffs)
        // F0 is SR / argmax quefrency: equal, or the argmax picked another bin.
        worst["f0"] = compare("f0", k.f0, p.getJSONArray("f0").floats(), { 1e-3f }, diffs)

        val pb = p.getJSONArray("bands")
        val nBands = FrameExtractor.N_BANDS
        var worstBand = 0f
        for (i in 0 until k.n) {
            val row = pb.getJSONArray(i).floats()
            assertEquals(nBands, row.size)
            for (b in 0 until nBands) {
                val kv = k.bands[i * nBands + b]
                val d = abs(kv - row[b])
                worstBand = max(worstBand, d)
                if (d > 0.01f) diffs.add(Diff("bands[$b]", i, kv, row[b]))
            }
        }
        worst["bands"] = worstBand

        println("FrameExtractor parity, worst |kotlin - python| per column: $worst")
        assertTrue(
            "FrameExtractor differs from the worker's AudioFrames:\n" + diffs.take(40).joinToString("\n") {
                "  ${it.col} frame ${it.frame}: kotlin=${it.kotlin} python=${it.python}"
            },
            diffs.isEmpty()
        )
    }
}
