package app.hushcast.automute.engine

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import app.hushcast.automute.nativebridge.WhisperJni
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Decodes an audio file or URL with MediaExtractor + MediaCodec (or native dr_mp3 for local MP3)
 * and streams it into a [FrameExtractor] (24 kHz mid/side) and, optionally, 16 kHz mono [PcmBuffer]
 * either in full or in overlapping 180s chunks via [onPcmChunk].
 */
object AudioDecoder {
    private const val WHISPER_SR = 16_000
    private const val CHUNK_SAMPLES = WHISPER_SR * 180
    private const val OVERLAP_SAMPLES = WHISPER_SR * 5

    data class Info(
        val sampleRate: Int,
        val channels: Int,
        val durationUs: Long,
        val encoderDelayMs: Int = 0
    )

    private class AsyncFrameFeeder(
        private val frames: FrameExtractor,
        private val shouldAbort: () -> Boolean
    ) : AutoCloseable {
        private val queue = ArrayBlockingQueue<Pair<FloatArray, FloatArray?>>(64)
        private val error = AtomicReference<Throwable?>(null)
        private val thread = Thread({
            try {
                while (true) {
                    val item = queue.take()
                    if (item.first.isEmpty() && item.second == null) break
                    frames.feed(item.first, item.second?.takeIf { it.size == item.first.size })
                }
            } catch (_: InterruptedException) {
                // Cancelled
            } catch (t: Throwable) {
                error.compareAndSet(null, t)
            }
        }, "HushCast-FrameExtractor").apply { start() }

        fun enqueue(mid24k: FloatArray, side24k: FloatArray?) {
            if (mid24k.isEmpty()) return
            val item = mid24k to side24k
            while (true) {
                error.get()?.let { throw it }
                if (shouldAbort()) throw CancellationException("Audio decode cancelled")
                if (queue.offer(item, 50L, TimeUnit.MILLISECONDS)) return
            }
        }

        fun finishAndJoin() {
            while (true) {
                error.get()?.let { throw it }
                if (shouldAbort()) throw CancellationException("Audio decode cancelled")
                if (queue.offer(FloatArray(0) to null, 50L, TimeUnit.MILLISECONDS)) break
            }
            thread.join()
            error.get()?.let { throw it }
        }

        override fun close() {
            thread.interrupt()
        }
    }

    private class ResamplerSet(
        var rate: Int,
        private val needFrames: Boolean,
        private val stereo: Boolean,
        private val needWhisper: Boolean
    ) : AutoCloseable {
        var toFrames: Resampler? = if (needFrames) Resampler(rate, FrameExtractor.SR) else null
            private set
        var toFramesSide: Resampler? = if (needFrames && stereo) Resampler(rate, FrameExtractor.SR) else null
            private set
        var toWhisper: Resampler? = if (needWhisper) Resampler(rate, WHISPER_SR) else null
            private set

        fun rebuildIfRateChanged(
            newRate: Int,
            feeder: AsyncFrameFeeder?,
            onWhisperSamples: (FloatArray) -> Unit
        ) {
            if (newRate <= 0 || newRate == rate) return
            flush(feeder, onWhisperSamples)
            close()
            rate = newRate
            toFrames = if (needFrames) Resampler(rate, FrameExtractor.SR) else null
            toFramesSide = if (needFrames && stereo) Resampler(rate, FrameExtractor.SR) else null
            toWhisper = if (needWhisper) Resampler(rate, WHISPER_SR) else null
        }

        fun process(
            mid: FloatArray,
            side: FloatArray?,
            count: Int,
            feeder: AsyncFrameFeeder?,
            onWhisperSamples: (FloatArray) -> Unit
        ) {
            if (count <= 0) return
            if (toFrames != null && feeder != null) {
                val mid24k = toFrames!!.process(mid, 0, count)
                val side24k = if (stereo && side != null) toFramesSide?.process(side, 0, count) else null
                feeder.enqueue(mid24k, side24k)
            }
            if (toWhisper != null) {
                val w = toWhisper!!.process(mid, 0, count)
                if (w.isNotEmpty()) onWhisperSamples(w)
            }
        }

        fun flush(feeder: AsyncFrameFeeder?, onWhisperSamples: (FloatArray) -> Unit) {
            if (toFrames != null && feeder != null) {
                val mid24k = toFrames!!.finish()
                val side24k = if (stereo) toFramesSide?.finish() else null
                feeder.enqueue(mid24k, side24k)
            }
            if (toWhisper != null) {
                val wf = toWhisper!!.finish()
                if (wf.isNotEmpty()) onWhisperSamples(wf)
            }
        }

        override fun close() {
            toFrames?.close()
            toFramesSide?.close()
            toWhisper?.close()
            toFrames = null
            toFramesSide = null
            toWhisper = null
        }
    }

    fun decode(
        path: String,
        frames: FrameExtractor? = null,
        pcm16k: PcmBuffer? = null,
        shouldAbort: () -> Boolean = { false },
        onPcmChunk: ((chunkStartSec: Double, pcm: PcmBuffer) -> Unit)? = null,
        onProgress: ((Float) -> Unit)? = null
    ): Info {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(path)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("no audio track in $path")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            if (mime == "audio/mpeg" && !path.startsWith("http", ignoreCase = true) && WhisperJni.isAvailable) {
                val mp3Handle = WhisperJni.mp3Open(path)
                if (mp3Handle != 0L) {
                    ex.release()
                    return decodeMp3Native(mp3Handle, duration, frames, pcm16k, shouldAbort, onPcmChunk, onProgress)
                }
            }

            val encoderDelaySamples = if (format.containsKey(MediaFormat.KEY_ENCODER_DELAY)) {
                format.getInteger(MediaFormat.KEY_ENCODER_DELAY).coerceAtLeast(0)
            } else {
                0
            }
            val encoderDelayMs = if (encoderDelaySamples > 0 && rate > 0) {
                (encoderDelaySamples * 1000L / rate).toInt()
            } else if (mime.contains("mp4a", ignoreCase = true) || mime.contains("aac", ignoreCase = true)) {
                46
            } else {
                0
            }

            var encoding = AudioFormat.ENCODING_PCM_16BIT
            codec = MediaCodec.createDecoderByType(mime)
            try {
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
                codec.configure(format, null, null, 0)
            } catch (_: Exception) {
                codec.reset()
                codec.configure(ex.getTrackFormat(track), null, null, 0)
            }
            codec.start()

            val needWhisper = pcm16k != null || onPcmChunk != null
            val chunkBuf = if (onPcmChunk != null) PcmBuffer(CHUNK_SAMPLES + OVERLAP_SAMPLES) else null
            var chunkStartSec = 0.0

            fun handleWhisperSamples(samples: FloatArray) {
                pcm16k?.append(samples)
                if (chunkBuf != null && onPcmChunk != null) {
                    chunkBuf.append(samples)
                    while (chunkBuf.size >= CHUNK_SAMPLES) {
                        if (shouldAbort()) throw CancellationException("Audio decode cancelled")
                        onPcmChunk(chunkStartSec, chunkBuf)
                        val prevSize = chunkBuf.size
                        val kept = OVERLAP_SAMPLES.coerceAtMost(prevSize)
                        chunkBuf.keepTail(kept)
                        chunkStartSec += (prevSize - kept).toDouble() / WHISPER_SR
                    }
                }
            }

            val resamplers = ResamplerSet(rate, frames != null, channels >= 2, needWhisper)
            val feeder = if (frames != null) AsyncFrameFeeder(frames, shouldAbort) else null
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var floatScratch = FloatArray(0)
            var shortScratch = ShortArray(0)
            var midScratch = FloatArray(0)
            var sideScratch = FloatArray(0)
            var lastReportedFraction = -1f

            try {
                while (!outputDone) {
                    if (shouldAbort()) throw CancellationException("Audio decode cancelled")
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        if (newRate != rate) {
                            rate = newRate
                            resamplers.rebuildIfRateChanged(newRate, feeder, ::handleWhisperSamples)
                        }
                    } else if (o >= 0) {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val stereo = channels >= 2
                        val count = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) info.size / 4 / channels else info.size / 2 / channels
                        if (count > 0) {
                            val totalSamples = count * channels
                            if (midScratch.size < count) midScratch = FloatArray(count)
                            if (stereo && sideScratch.size < count) sideScratch = FloatArray(count)
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                if (floatScratch.size < totalSamples) floatScratch = FloatArray(totalSamples)
                                buf.asFloatBuffer().get(floatScratch, 0, totalSamples)
                                extractMidSideFloat(floatScratch, count, channels, midScratch, if (stereo) sideScratch else null)
                            } else {
                                if (shortScratch.size < totalSamples) shortScratch = ShortArray(totalSamples)
                                buf.asShortBuffer().get(shortScratch, 0, totalSamples)
                                extractMidSideShort(shortScratch, count, channels, midScratch, if (stereo) sideScratch else null)
                            }
                            resamplers.process(
                                midScratch,
                                if (stereo) sideScratch else null,
                                count,
                                feeder,
                                ::handleWhisperSamples
                            )

                            if (onProgress != null && duration > 0L) {
                                val fraction = (info.presentationTimeUs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                                if (fraction - lastReportedFraction >= 0.03f) {
                                    lastReportedFraction = fraction
                                    onProgress(fraction)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
                resamplers.flush(feeder, ::handleWhisperSamples)
                if (chunkBuf != null && onPcmChunk != null && chunkBuf.size > 0) {
                    if (shouldAbort()) throw CancellationException("Audio decode cancelled")
                    onPcmChunk(chunkStartSec, chunkBuf)
                }
                feeder?.finishAndJoin()
            } finally {
                feeder?.close()
                resamplers.close()
                chunkBuf?.close()
            }
            return Info(rate, channels, duration, encoderDelayMs)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }

    private fun decodeMp3Native(
        mp3Handle: Long,
        extractorDurationUs: Long,
        frames: FrameExtractor?,
        pcm16k: PcmBuffer?,
        shouldAbort: () -> Boolean,
        onPcmChunk: ((chunkStartSec: Double, pcm: PcmBuffer) -> Unit)?,
        onProgress: ((Float) -> Unit)?
    ): Info {
        var rate = WhisperJni.mp3SampleRate(mp3Handle).coerceAtLeast(8000)
        val channels = WhisperJni.mp3Channels(mp3Handle).coerceAtLeast(1)
        val durationUs = extractorDurationUs.takeIf { it > 0L } ?: run {
            val totalPcmFrames = WhisperJni.mp3TotalFrames(mp3Handle)
            if (totalPcmFrames > 0L) (totalPcmFrames * 1_000_000L) / rate else 0L
        }
        val stereo = channels >= 2
        val needWhisper = pcm16k != null || onPcmChunk != null
        val chunkBuf = if (onPcmChunk != null) PcmBuffer(CHUNK_SAMPLES + OVERLAP_SAMPLES) else null
        var chunkStartSec = 0.0

        fun handleWhisperSamples(samples: FloatArray) {
            pcm16k?.append(samples)
            if (chunkBuf != null && onPcmChunk != null) {
                chunkBuf.append(samples)
                while (chunkBuf.size >= CHUNK_SAMPLES) {
                    if (shouldAbort()) throw CancellationException("Audio decode cancelled")
                    onPcmChunk(chunkStartSec, chunkBuf)
                    val prevSize = chunkBuf.size
                    val kept = OVERLAP_SAMPLES.coerceAtMost(prevSize)
                    chunkBuf.keepTail(kept)
                    chunkStartSec += (prevSize - kept).toDouble() / WHISPER_SR
                }
            }
        }

        val resamplers = ResamplerSet(rate, frames != null, stereo, needWhisper)
        val feeder = if (frames != null) AsyncFrameFeeder(frames, shouldAbort) else null

        val chunkSize = 32768
        val midChunk = FloatArray(chunkSize)
        val sideChunk = if (stereo) FloatArray(chunkSize) else null
        var decodedSec = 0.0
        var lastReportedFraction = -1f

        try {
            while (true) {
                if (shouldAbort()) throw CancellationException("Audio decode cancelled")
                val n = WhisperJni.mp3ReadChunk(mp3Handle, midChunk, sideChunk)
                if (n <= 0) break
                val newRate = WhisperJni.mp3SampleRate(mp3Handle)
                if (newRate > 0 && newRate != rate) {
                    rate = newRate
                    resamplers.rebuildIfRateChanged(newRate, feeder, ::handleWhisperSamples)
                }
                decodedSec += n.toDouble() / rate
                resamplers.process(midChunk, sideChunk, n, feeder, ::handleWhisperSamples)

                if (onProgress != null && durationUs > 0L) {
                    val fraction = ((decodedSec * 1_000_000.0) / durationUs.toDouble()).toFloat().coerceIn(0f, 1f)
                    if (fraction - lastReportedFraction >= 0.03f) {
                        lastReportedFraction = fraction
                        onProgress(fraction)
                    }
                }
            }
            resamplers.flush(feeder, ::handleWhisperSamples)
            if (chunkBuf != null && onPcmChunk != null && chunkBuf.size > 0) {
                if (shouldAbort()) throw CancellationException("Audio decode cancelled")
                onPcmChunk(chunkStartSec, chunkBuf)
            }
            feeder?.finishAndJoin()
        } finally {
            feeder?.close()
            resamplers.close()
            chunkBuf?.close()
            WhisperJni.mp3Close(mp3Handle)
        }
        return Info(rate, channels, durationUs, encoderDelayMs = 0)
    }

    private fun extractMidSideFloat(
        src: FloatArray,
        count: Int,
        channels: Int,
        mid: FloatArray,
        side: FloatArray?
    ) {
        if (side != null && channels >= 2) {
            for (k in 0 until count) {
                val idx = k * channels
                val l = src[idx]
                val r = src[idx + 1]
                mid[k] = (l + r) * 0.5f
                side[k] = (l - r) * 0.5f
            }
        } else if (channels == 1) {
            System.arraycopy(src, 0, mid, 0, count)
        } else {
            for (k in 0 until count) mid[k] = src[k * channels]
        }
    }

    private fun extractMidSideShort(
        src: ShortArray,
        count: Int,
        channels: Int,
        mid: FloatArray,
        side: FloatArray?
    ) {
        if (side != null && channels >= 2) {
            for (k in 0 until count) {
                val idx = k * channels
                val l = src[idx] / 32768f
                val r = src[idx + 1] / 32768f
                mid[k] = (l + r) * 0.5f
                side[k] = (l - r) * 0.5f
            }
        } else {
            for (k in 0 until count) mid[k] = src[k * channels] / 32768f
        }
    }
}
