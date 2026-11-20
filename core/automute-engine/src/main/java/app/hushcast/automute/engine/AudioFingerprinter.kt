package app.hushcast.automute.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Compact cross-episode audio fingerprinter for detecting recurring intros,
 * outros, and pre-recorded promos across episodes of a podcast (or across shows).
 *
 * Uses 100 ms frames (1600-sample hop at 16 kHz), 2048-point Hann-windowed FFT,
 * and 17 log-spaced frequency bands (150 Hz .. 5500 Hz) to produce a 16-bit
 * time-frequency energy-difference hash per frame (~50 KB/hour compressed).
 */
object AudioFingerprinter {
    const val SAMPLE_RATE = 16_000
    const val HOP_SAMPLES = 1_600
    const val FRAME_MS = 100
    const val FFT_SIZE = 2_048
    private const val NUM_BANDS = 17
    private const val MIN_RMS = 1e-3f
    private const val MIN_MATCH_FRAMES = 60 // 6.0 seconds
    private const val MAX_HAMMING_DIST = 3
    private const val MAX_GAP_FRAMES = 4
    private const val MIN_ANCHOR_VOTES = 12

    data class Fingerprint(
        val fileSha256: String,
        val podcastOrigin: String,
        val durationMs: Long,
        val hashes: ShortArray, // unsigned 16-bit stored in Short
        val active: BooleanArray
    ) {
        fun toCompressedBytes(): ByteArray {
            val n = hashes.size
            val packedLen = (n + 7) / 8
            val buf = ByteBuffer.allocate(12 + n * 2 + packedLen).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(n)
            buf.putLong(durationMs)
            for (i in 0 until n) {
                buf.putShort(hashes[i])
            }
            val packed = ByteArray(packedLen)
            for (i in 0 until n) {
                if (active[i]) {
                    packed[i ushr 3] = (packed[i ushr 3].toInt() or (1 shl (i and 7))).toByte()
                }
            }
            buf.put(packed)
            val raw = buf.array()
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream(raw.size / 2)
            val tmp = ByteArray(4096)
            while (!deflater.finished()) {
                val count = deflater.deflate(tmp)
                out.write(tmp, 0, count)
            }
            deflater.end()
            return out.toByteArray()
        }

        companion object {
            fun fromCompressedBytes(
                data: ByteArray,
                fileSha256: String,
                podcastOrigin: String
            ): Fingerprint {
                val inflater = Inflater()
                inflater.setInput(data)
                val out = ByteArrayOutputStream(data.size * 2)
                val tmp = ByteArray(4096)
                while (!inflater.finished()) {
                    val count = inflater.inflate(tmp)
                    if (count == 0 && inflater.needsInput()) break
                    out.write(tmp, 0, count)
                }
                inflater.end()
                val raw = out.toByteArray()
                val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                val n = buf.int
                val durationMs = buf.long
                val hashes = ShortArray(n)
                for (i in 0 until n) {
                    hashes[i] = buf.short
                }
                val packed = ByteArray((n + 7) / 8)
                buf.get(packed)
                val active = BooleanArray(n)
                for (i in 0 until n) {
                    active[i] = (packed[i ushr 3].toInt() and (1 shl (i and 7))) != 0
                }
                return Fingerprint(fileSha256, podcastOrigin, durationMs, hashes, active)
            }
        }
    }

    data class DetectedSpan(
        val startMs: Long,
        val endMs: Long,
        val adClass: String,
        val label: String,
        val confidence: Float = 0.92f
    )

    private val hannWindow: DoubleArray by lazy {
        DoubleArray(FFT_SIZE) { i ->
            0.5 * (1.0 - cos(2.0 * Math.PI * i / (FFT_SIZE - 1)))
        }
    }

    private val bandEdges: IntArray by lazy {
        val fMin = 150.0
        val fMax = 5500.0
        val ratio = (fMax / fMin).pow(1.0 / NUM_BANDS)
        val edges = IntArray(NUM_BANDS + 1)
        val maxBin = FFT_SIZE / 2
        for (i in 0..NUM_BANDS) {
            val freq = fMin * ratio.pow(i.toDouble())
            var bin = (freq * FFT_SIZE / SAMPLE_RATE).roundToInt().coerceIn(1, maxBin)
            if (i > 0 && bin <= edges[i - 1]) {
                bin = min(maxBin, edges[i - 1] + 1)
            }
            edges[i] = bin
        }
        edges
    }

    fun compute(
        pcm16k: FloatArray,
        fileSha256: String = "",
        podcastOrigin: String = "",
        sampleRate: Int = SAMPLE_RATE
    ): Fingerprint {
        val durationMs = ((pcm16k.size.toDouble() * 1000.0) / sampleRate).roundToLongSafe()
        if (pcm16k.size < FFT_SIZE) {
            return Fingerprint(fileSha256, podcastOrigin, durationMs, ShortArray(0), BooleanArray(0))
        }

        val nFrames = 1 + (pcm16k.size - FFT_SIZE) / HOP_SAMPLES
        val hashes = ShortArray(nFrames)
        val active = BooleanArray(nFrames)

        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        val prevFreqDiff = DoubleArray(NUM_BANDS - 1)
        val currFreqDiff = DoubleArray(NUM_BANDS - 1)
        val bandEnergy = DoubleArray(NUM_BANDS)
        val edges = bandEdges
        val win = hannWindow

        Fft(FFT_SIZE).use { fft ->
            for (t in 0 until nFrames) {
                val base = t * HOP_SAMPLES
                var sumSq = 0.0
                for (i in 0 until FFT_SIZE) {
                    val s = pcm16k[base + i].toDouble()
                    sumSq += s * s
                    re[i] = s * win[i]
                    im[i] = 0.0
                }
                val rms = sqrt(sumSq / FFT_SIZE).toFloat()
                val isActive = rms >= MIN_RMS
                active[t] = isActive

                fft.transform(re, im, inverse = false)

                for (b in 0 until NUM_BANDS) {
                    val lo = edges[b]
                    val hi = edges[b + 1]
                    var pSum = 0.0
                    val count = max(1, hi - lo)
                    for (k in lo until hi) {
                        val mag2 = re[k] * re[k] + im[k] * im[k]
                        pSum += mag2
                    }
                    bandEnergy[b] = ln(1.0 + pSum / count)
                }

                for (b in 0 until NUM_BANDS - 1) {
                    currFreqDiff[b] = bandEnergy[b + 1] - bandEnergy[b]
                }

                if (t > 0 && isActive) {
                    var mask = 0
                    for (b in 0 until NUM_BANDS - 1) {
                        if (currFreqDiff[b] - prevFreqDiff[b] > 0.0) {
                            mask = mask or (1 shl b)
                        }
                    }
                    hashes[t] = mask.toShort()
                } else {
                    hashes[t] = 0
                }

                System.arraycopy(currFreqDiff, 0, prevFreqDiff, 0, NUM_BANDS - 1)
            }
        }

        return Fingerprint(fileSha256, podcastOrigin, durationMs, hashes, active)
    }

    fun findRepeatedSegments(
        query: Fingerprint,
        references: List<Fingerprint>,
        minDurationMs: Long = MIN_MATCH_FRAMES.toLong() * FRAME_MS
    ): List<DetectedSpan> {
        val nQ = query.hashes.size
        if (nQ < MIN_MATCH_FRAMES || references.isEmpty()) return emptyList()

        val qLoPos = HashMap<Int, MutableList<Int>>()
        val qHiPos = HashMap<Int, MutableList<Int>>()

        for (t in 0 until nQ - 1) {
            if (!query.active[t] || !query.active[t + 1]) continue
            val h0 = query.hashes[t].toInt() and 0xFFFF
            val h1 = query.hashes[t + 1].toInt() and 0xFFFF
            val loKey = ((h0 and 0xFF) shl 8) or (h1 and 0xFF)
            val hiKey = ((h0 ushr 8) shl 8) or (h1 ushr 8)
            qLoPos.getOrPut(loKey) { ArrayList(2) }.add(t)
            qHiPos.getOrPut(hiKey) { ArrayList(2) }.add(t)
        }

        if (qLoPos.isEmpty()) return emptyList()

        val rawSpans = ArrayList<DetectedSpan>()

        for (ref in references) {
            if (ref.fileSha256.isNotEmpty() && ref.fileSha256 == query.fileSha256) continue
            val nR = ref.hashes.size
            if (nR < MIN_MATCH_FRAMES) continue

            val sameShow = query.podcastOrigin.isNotEmpty() &&
                ref.podcastOrigin.isNotEmpty() &&
                query.podcastOrigin == ref.podcastOrigin

            val offsetVotes = HashMap<Int, Int>()
            for (tr in 0 until nR - 1) {
                if (!ref.active[tr] || !ref.active[tr + 1]) continue
                val h0 = ref.hashes[tr].toInt() and 0xFFFF
                val h1 = ref.hashes[tr + 1].toInt() and 0xFFFF
                val loKey = ((h0 and 0xFF) shl 8) or (h1 and 0xFF)
                val hiKey = ((h0 ushr 8) shl 8) or (h1 ushr 8)

                qLoPos[loKey]?.forEach { tq ->
                    val bucket = floorDiv2(tq - tr)
                    offsetVotes[bucket] = (offsetVotes[bucket] ?: 0) + 1
                }
                qHiPos[hiKey]?.forEach { tq ->
                    val bucket = floorDiv2(tq - tr)
                    offsetVotes[bucket] = (offsetVotes[bucket] ?: 0) + 1
                }
            }

            if (offsetVotes.isEmpty()) continue

            val topBuckets = offsetVotes.entries
                .filter { it.value >= MIN_ANCHOR_VOTES }
                .sortedByDescending { it.value }
                .take(12)

            val checkedOffsets = HashSet<Int>()
            val qIntroLimit = if (query.durationMs > 0) min(90_000L, (0.20 * query.durationMs).toLong()) else 90_000L
            val rIntroLimit = if (ref.durationMs > 0) min(120_000L, (0.25 * ref.durationMs).toLong()) else 120_000L
            val qOutroMargin = if (query.durationMs > 0) min(150_000L, (0.20 * query.durationMs).toLong()) else 0L
            val rOutroMargin = if (ref.durationMs > 0) min(150_000L, (0.20 * ref.durationMs).toLong()) else 0L

            for ((bucket, _) in topBuckets) {
                for (off in intArrayOf(bucket * 2, bucket * 2 + 1, bucket * 2 - 1)) {
                    if (!checkedOffsets.add(off)) continue
                    val matches = verifyDiagonal(query, ref, off)
                    for ((qStart, qEnd) in matches) {
                        val startMs = qStart.toLong() * FRAME_MS
                        val endMs = if (query.durationMs > 0) min(query.durationMs, qEnd.toLong() * FRAME_MS) else qEnd.toLong() * FRAME_MS
                        if (endMs - startMs < minDurationMs) continue

                        val rStartMs = (qStart - off).toLong() * FRAME_MS
                        val rEndMs = (qEnd - off).toLong() * FRAME_MS

                        val (adClass, label) = when {
                            sameShow && startMs <= qIntroLimit && rStartMs <= rIntroLimit ->
                                "boilerplate" to "Intro"
                            sameShow && query.durationMs > 0 && endMs >= query.durationMs - qOutroMargin &&
                                ref.durationMs > 0 && rEndMs >= ref.durationMs - rOutroMargin ->
                                "boilerplate" to "Outro"
                            else ->
                                "house_promo" to "Recurring Promo"
                        }
                        rawSpans.add(DetectedSpan(startMs, endMs, adClass, label))
                    }
                }
            }
        }

        if (rawSpans.isEmpty()) return emptyList()

        rawSpans.sortWith(compareBy<DetectedSpan> { it.startMs }.thenBy { it.endMs })
        val merged = ArrayList<DetectedSpan>(rawSpans.size)
        for (span in rawSpans) {
            val prev = merged.lastOrNull()
            if (prev != null && span.startMs <= prev.endMs + 1000L && span.adClass == prev.adClass) {
                merged[merged.lastIndex] = prev.copy(endMs = max(prev.endMs, span.endMs))
            } else {
                merged.add(span)
            }
        }
        return merged
    }

    private fun verifyDiagonal(
        query: Fingerprint,
        ref: Fingerprint,
        offset: Int
    ): List<Pair<Int, Int>> {
        val qLo = max(0, offset)
        val qHi = min(query.hashes.size, ref.hashes.size + offset)
        val spanLen = qHi - qLo
        if (spanLen < MIN_MATCH_FRAMES) return emptyList()

        val matched = BooleanArray(spanLen)
        val bothActive = BooleanArray(spanLen)
        for (idx in 0 until spanLen) {
            val qIdx = qLo + idx
            val rIdx = qIdx - offset
            val act = query.active[qIdx] && ref.active[rIdx]
            bothActive[idx] = act
            if (act) {
                val xor = (query.hashes[qIdx].toInt() and 0xFFFF) xor (ref.hashes[rIdx].toInt() and 0xFFFF)
                if (Integer.bitCount(xor) <= MAX_HAMMING_DIST) {
                    matched[idx] = true
                }
            }
        }

        val spans = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < spanLen) {
            if (!matched[i]) {
                i++
                continue
            }
            val start = i
            var lastGood = i
            var gap = 0
            var j = i + 1
            while (j < spanLen) {
                if (matched[j]) {
                    lastGood = j
                    gap = 0
                } else {
                    gap++
                    if (gap > MAX_GAP_FRAMES) break
                }
                j++
            }
            val length = lastGood - start + 1
            if (length >= MIN_MATCH_FRAMES) {
                var activeCount = 0
                var matchCount = 0
                for (k in start..lastGood) {
                    if (bothActive[k]) activeCount++
                    if (matched[k]) matchCount++
                }
                if (activeCount >= MIN_MATCH_FRAMES / 2 && matchCount >= (0.70 * length).toInt()) {
                    spans.add((qLo + start) to (qLo + lastGood + 1))
                }
            }
            i = j + 1
        }
        return spans
    }

    private fun floorDiv2(x: Int): Int = if (x >= 0) x shr 1 else -((-x + 1) shr 1)

    private fun Double.roundToLongSafe(): Long =
        if (isNaN() || isInfinite()) 0L else kotlin.math.round(this).toLong()
}
