package app.hushcast.automute.engine

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

/**
 * Decodes an audio file or URL with MediaExtractor + MediaCodec and streams it into a
 * [FrameExtractor] (24 kHz mid and side) and, optionally, a 16 kHz mono [PcmBuffer] for whisper.
 */
object AudioDecoder {
    data class Info(val sampleRate: Int, val channels: Int, val durationUs: Long)

    fun decode(
        path: String,
        frames: FrameExtractor? = null,
        pcm16k: PcmBuffer? = null,
        onProgress: ((Float) -> Unit)? = null
    ): Info {
        val ex = MediaExtractor()
        ex.setDataSource(path)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("no audio track in $path")
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        // Ask for float output: 16-bit samples turn near-silence into exact zeros, which shifts a few
        // of the audio features.  Decoders that can't do it either ignore the request or refuse it.
        try {
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            codec.configure(format, null, null, 0)
        } catch (e: Exception) {
            codec.reset()
            codec.configure(ex.getTrackFormat(track), null, null, 0)
        }
        codec.start()

        var toFrames = if (frames != null) Resampler(rate, FrameExtractor.SR) else null
        var toFramesSide = if (frames != null) Resampler(rate, FrameExtractor.SR) else null
        var toWhisper = if (pcm16k != null) {
            Resampler(if (frames != null) FrameExtractor.SR else rate, 16000)
        } else null
        var started = false
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var floatScratch = FloatArray(0)
        var shortScratch = ShortArray(0)
        var midScratch = FloatArray(0)
        var sideScratch = FloatArray(0)
        var lastReportedFraction = -1f

        val frameQueue = if (frames != null) java.util.concurrent.ArrayBlockingQueue<Pair<FloatArray, FloatArray?>>(64) else null
        val frameError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val frameThread = if (frames != null && frameQueue != null) {
            Thread({
                try {
                    while (true) {
                        val item = frameQueue.take()
                        if (item.first.isEmpty() && item.second == null) break
                        feed(frames, item.first, item.second)
                    }
                } catch (t: Throwable) {
                    frameError.compareAndSet(null, t)
                }
            }, "HushCast-FrameExtractor").apply { start() }
        } else null

        fun enqueueFrame(mid24k: FloatArray, side24k: FloatArray?) {
            val err = frameError.get()
            if (err != null) throw err
            if (mid24k.isNotEmpty()) {
                frameQueue?.put(mid24k to side24k)
            }
        }

        val decodeStartMs = System.currentTimeMillis()
        var lastLogMs = decodeStartMs
        android.util.Log.i(
            "HushCastPerf",
            "Decode start: rate=$rate ch=$channels durSec=${duration / 1_000_000L} native=${app.hushcast.automute.nativebridge.WhisperJni.isAvailable}"
        )

        try {
            while (!outputDone) {
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
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    if (newRate != rate && !started) {
                        rate = newRate
                        toFrames = if (frames != null) Resampler(rate, FrameExtractor.SR) else null
                        toFramesSide = if (frames != null) Resampler(rate, FrameExtractor.SR) else null
                        toWhisper = if (pcm16k != null) {
                            Resampler(if (frames != null) FrameExtractor.SR else rate, 16000)
                        } else null
                        android.util.Log.i("HushCastPerf", "Decode format: rate=$rate ch=$channels enc=$encoding")
                    }
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val stereo = channels >= 2
                    val count = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) info.size / 4 / channels else info.size / 2 / channels
                    if (count > 0) {
                        started = true
                        val totalSamples = count * channels
                        if (midScratch.size < count) midScratch = FloatArray(count)
                        if (stereo && sideScratch.size < count) sideScratch = FloatArray(count)
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            if (floatScratch.size < totalSamples) floatScratch = FloatArray(totalSamples)
                            buf.asFloatBuffer().get(floatScratch, 0, totalSamples)
                            if (stereo) {
                                for (k in 0 until count) {
                                    val idx = k * channels
                                    val l = floatScratch[idx]
                                    val r = floatScratch[idx + 1]
                                    midScratch[k] = (l + r) * 0.5f
                                    sideScratch[k] = (l - r) * 0.5f
                                }
                            } else if (channels == 1) {
                                System.arraycopy(floatScratch, 0, midScratch, 0, count)
                            } else {
                                for (k in 0 until count) midScratch[k] = floatScratch[k * channels]
                            }
                        } else {
                            if (shortScratch.size < totalSamples) shortScratch = ShortArray(totalSamples)
                            buf.asShortBuffer().get(shortScratch, 0, totalSamples)
                            if (stereo) {
                                for (k in 0 until count) {
                                    val idx = k * channels
                                    val l = shortScratch[idx] / 32768f
                                    val r = shortScratch[idx + 1] / 32768f
                                    midScratch[k] = (l + r) * 0.5f
                                    sideScratch[k] = (l - r) * 0.5f
                                }
                            } else {
                                for (k in 0 until count) midScratch[k] = shortScratch[k * channels] / 32768f
                            }
                        }
                        if (frames != null && toFrames != null) {
                            val mid24k = toFrames.process(midScratch, 0, count)
                            val side24k = if (stereo) toFramesSide?.process(sideScratch, 0, count) else null
                            enqueueFrame(mid24k, side24k)
                            if (pcm16k != null && toWhisper != null && mid24k.isNotEmpty()) {
                                val w = toWhisper.process(mid24k, 0, mid24k.size)
                                if (w.isNotEmpty()) pcm16k.append(w)
                            }
                        } else if (pcm16k != null && toWhisper != null) {
                            val w = toWhisper.process(midScratch, 0, count)
                            if (w.isNotEmpty()) pcm16k.append(w)
                        }

                        if (onProgress != null && duration > 0L) {
                            val fraction = (info.presentationTimeUs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                            if (fraction - lastReportedFraction >= 0.03f) {
                                lastReportedFraction = fraction
                                onProgress(fraction)
                            }
                        }
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastLogMs >= 5000L) {
                            lastLogMs = nowMs
                            val elapsedSec = ((nowMs - decodeStartMs).coerceAtLeast(1L)) / 1000.0
                            val audioSec = info.presentationTimeUs / 1_000_000.0
                            val speed = String.format(java.util.Locale.US, "%.1f", audioSec / elapsedSec)
                            val pct = if (duration > 0L) (info.presentationTimeUs * 100L / duration).toInt() else 0
                            android.util.Log.i("HushCastPerf", "Decode $pct%: ${audioSec.toInt()}s in ${elapsedSec.toInt()}s (${speed}x RT) q=${frameQueue?.size ?: 0}")
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            if (frames != null && toFrames != null) {
                val mid24k = toFrames.finish()
                val side24k = if (channels >= 2) toFramesSide?.finish() else null
                enqueueFrame(mid24k, side24k)
                if (pcm16k != null && toWhisper != null) {
                    if (mid24k.isNotEmpty()) {
                        val w = toWhisper.process(mid24k, 0, mid24k.size)
                        if (w.isNotEmpty()) pcm16k.append(w)
                    }
                    val wf = toWhisper.finish()
                    if (wf.isNotEmpty()) pcm16k.append(wf)
                }
            } else if (pcm16k != null && toWhisper != null) {
                val wf = toWhisper.finish()
                if (wf.isNotEmpty()) pcm16k.append(wf)
            }
            if (frameQueue != null && frameThread != null) {
                frameQueue.put(FloatArray(0) to null)
                frameThread.join()
                frameError.get()?.let { throw it }
            }
        } finally {
            frameThread?.interrupt()
            toFrames?.close()
            toFramesSide?.close()
            toWhisper?.close()
            codec.stop(); codec.release(); ex.release()
        }
        return Info(rate, channels, duration)
    }

    private fun feed(frames: FrameExtractor, mid: FloatArray, side: FloatArray?) {
        if (mid.isEmpty()) return
        frames.feed(mid, side?.takeIf { it.size == mid.size })
    }
}
