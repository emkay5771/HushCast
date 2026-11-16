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

        var toFrames = Resampler(rate, FrameExtractor.SR)
        var toFramesSide = Resampler(rate, FrameExtractor.SR)
        var toWhisper = Resampler(rate, 16000)
        var started = false
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var floatScratch = FloatArray(0)
        var shortScratch = ShortArray(0)
        var lastReportedFraction = -1f
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
                        toFrames = Resampler(rate, FrameExtractor.SR)
                        toFramesSide = Resampler(rate, FrameExtractor.SR)
                        toWhisper = Resampler(rate, 16000)
                    }
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val stereo = channels >= 2
                    val count = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) info.size / 4 / channels else info.size / 2 / channels
                    if (count > 0) {
                        started = true
                        val totalSamples = count * channels
                        val mid = FloatArray(count)
                        val side = if (stereo) FloatArray(count) else null
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            if (floatScratch.size < totalSamples) floatScratch = FloatArray(totalSamples)
                            buf.asFloatBuffer().get(floatScratch, 0, totalSamples)
                            if (stereo) {
                                for (k in 0 until count) {
                                    val idx = k * channels
                                    val l = floatScratch[idx]
                                    val r = floatScratch[idx + 1]
                                    mid[k] = (l + r) * 0.5f
                                    side!![k] = (l - r) * 0.5f
                                }
                            } else if (channels == 1) {
                                System.arraycopy(floatScratch, 0, mid, 0, count)
                            } else {
                                for (k in 0 until count) mid[k] = floatScratch[k * channels]
                            }
                        } else {
                            if (shortScratch.size < totalSamples) shortScratch = ShortArray(totalSamples)
                            buf.asShortBuffer().get(shortScratch, 0, totalSamples)
                            if (stereo) {
                                for (k in 0 until count) {
                                    val idx = k * channels
                                    val l = shortScratch[idx] / 32768f
                                    val r = shortScratch[idx + 1] / 32768f
                                    mid[k] = (l + r) * 0.5f
                                    side!![k] = (l - r) * 0.5f
                                }
                            } else {
                                for (k in 0 until count) mid[k] = shortScratch[k * channels] / 32768f
                            }
                        }
                        if (frames != null) {
                            feed(frames, toFrames.process(mid), side?.let { toFramesSide.process(it) })
                        }
                        pcm16k?.append(toWhisper.process(mid))

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
            if (frames != null) {
                feed(frames, toFrames.finish(), if (channels >= 2) toFramesSide.finish() else null)
            }
            pcm16k?.append(toWhisper.finish())
        } finally {
            codec.stop(); codec.release(); ex.release()
        }
        return Info(rate, channels, duration)
    }

    private fun feed(frames: FrameExtractor, mid: FloatArray, side: FloatArray?) {
        if (mid.isEmpty()) return
        frames.feed(mid, side?.takeIf { it.size == mid.size })
    }
}
