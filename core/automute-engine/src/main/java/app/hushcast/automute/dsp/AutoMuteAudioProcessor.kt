package app.hushcast.automute.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Sample-accurate Media3 AudioProcessor for Feature A (Word-Level Auto-Muting).
 * Runs BEFORE SilenceSkippingAudioProcessor and SonicAudioProcessor so timestamps
 * match the original audio timeline.
 *
 * Supports:
 * - Hard Silence (0.0 gain with 8ms cosine crossfade)
 * - Volume Ducking (0.10 gain floor with 8ms cosine crossfade)
 * - Sample-Drop Micro-Skip (drops interior frames with 12ms cosine splice & reports skippedFrames)
 */
@OptIn(UnstableApi::class)
class AutoMuteAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var windows: List<MuteWindow> = emptyList()

    @Volatile
    var isEnabled: Boolean = true

    @Volatile
    var defaultActionMode: MuteActionMode = MuteActionMode.HARD_SILENCE

    @Volatile
    var encoderDelayOffsetMs: Long = 30L

    private val skippedFramesCounter = AtomicLong(0L)
    val skippedFrames: Long
        get() = skippedFramesCounter.get()

    private var currentInputFrameIndex: Long = 0L
    private var sampleRateHz: Int = 44100
    private var channelCount: Int = 2
    private var bytesPerFrame: Int = 4
    private var isFloatPcm: Boolean = false

    fun updateWindows(newWindows: List<MuteWindow>) {
        windows = newWindows.filter { it.enabled && it.endMs > it.startMs }.sortedBy { it.startMs }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRateHz = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        isFloatPcm = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        bytesPerFrame = if (isFloatPcm) channelCount * 4 else channelCount * 2
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val offsetUs = streamMetadata.positionOffsetUs
        currentInputFrameIndex = if (offsetUs != C.TIME_UNSET && offsetUs >= 0L) {
            (offsetUs * sampleRateHz) / 1_000_000L
        } else {
            0L
        }
        // Reset skipped-frame counter on every flush (seek), matching SilenceSkippingAudioProcessor
        skippedFramesCounter.set(0L)
    }

    override fun onReset() {
        currentInputFrameIndex = 0L
        skippedFramesCounter.set(0L)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val frameCount = remaining / bytesPerFrame
        if (frameCount == 0) return

        val activeWindows = windows
        if (!isEnabled || activeWindows.isEmpty()) {
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            currentInputFrameIndex += frameCount
            return
        }

        val startMs = (currentInputFrameIndex * 1000L) / sampleRateHz
        val endMs = ((currentInputFrameIndex + frameCount) * 1000L) / sampleRateHz
        val delayMs = encoderDelayOffsetMs

        // Quick check if any window overlaps [startMs, endMs]
        val overlapping = activeWindows.filter { w ->
            val ws = (w.startMs + delayMs).coerceAtLeast(0L)
            val we = (w.endMs + delayMs).coerceAtLeast(ws + 1L)
            we >= startMs && ws <= endMs
        }

        if (overlapping.isEmpty()) {
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            currentInputFrameIndex += frameCount
            return
        }

        val out = replaceOutputBuffer(remaining)
        val rampFramesSilence = ((8.0 * sampleRateHz) / 1000.0).roundToInt().coerceAtLeast(1)
        val rampFramesDrop = ((12.0 * sampleRateHz) / 1000.0).roundToInt().coerceAtLeast(1)

        var droppedInBuffer = 0L

        for (f in 0 until frameCount) {
            val frameIndex = currentInputFrameIndex + f
            val frameMs = (frameIndex * 1000.0) / sampleRateHz

            var matchedWindow: MuteWindow? = null
            var wsMs = 0.0
            var weMs = 0.0
            for (w in overlapping) {
                val s = (w.startMs + delayMs).coerceAtLeast(0L).toDouble()
                val e = (w.endMs + delayMs).coerceAtLeast(w.startMs + delayMs + 1L).toDouble()
                if (frameMs in s..e) {
                    matchedWindow = w
                    wsMs = s
                    weMs = e
                    break
                }
            }

            if (matchedWindow == null) {
                copyFrame(inputBuffer, out)
                continue
            }

            val mode = matchedWindow.actionMode
            val framesFromStart = ((frameMs - wsMs) * sampleRateHz / 1000.0).toInt().coerceAtLeast(0)
            val framesToEnd = ((weMs - frameMs) * sampleRateHz / 1000.0).toInt().coerceAtLeast(0)

            when (mode) {
                MuteActionMode.HARD_SILENCE -> {
                    val gain = cosineRampGain(framesFromStart, framesToEnd, rampFramesSilence, floor = 0.0f)
                    copyFrameWithGain(inputBuffer, out, gain)
                }
                MuteActionMode.VOLUME_DUCK -> {
                    val gain = cosineRampGain(framesFromStart, framesToEnd, rampFramesSilence, floor = 0.10f)
                    copyFrameWithGain(inputBuffer, out, gain)
                }
                MuteActionMode.SAMPLE_DROP_MICRO_SKIP -> {
                    // Keep 12ms cosine fade-out at start and 12ms cosine fade-in at end; drop interior frames
                    if (framesFromStart < rampFramesDrop) {
                        val t = framesFromStart.toFloat() / rampFramesDrop.toFloat()
                        val gain = 0.5f * (1.0f + cos(PI * t).toFloat())
                        copyFrameWithGain(inputBuffer, out, gain)
                    } else if (framesToEnd < rampFramesDrop) {
                        val t = framesToEnd.toFloat() / rampFramesDrop.toFloat()
                        val gain = 0.5f * (1.0f + cos(PI * t).toFloat())
                        copyFrameWithGain(inputBuffer, out, gain)
                    } else {
                        skipFrame(inputBuffer)
                        droppedInBuffer++
                    }
                }
            }
        }

        if (droppedInBuffer > 0L) {
            skippedFramesCounter.addAndGet(droppedInBuffer)
        }
        out.flip()
        currentInputFrameIndex += frameCount
    }

    private fun cosineRampGain(
        framesFromStart: Int,
        framesToEnd: Int,
        rampFrames: Int,
        floor: Float
    ): Float {
        return when {
            framesFromStart < rampFrames -> {
                val t = framesFromStart.toFloat() / rampFrames.toFloat()
                val factor = 0.5f * (1.0f + cos(PI * t).toFloat()) // 1.0 -> 0.0
                floor + (1.0f - floor) * factor
            }
            framesToEnd < rampFrames -> {
                val t = framesToEnd.toFloat() / rampFrames.toFloat()
                val factor = 0.5f * (1.0f + cos(PI * t).toFloat()) // 1.0 -> 0.0 as framesToEnd -> 0
                floor + (1.0f - floor) * factor
            }
            else -> floor
        }
    }

    private fun copyFrame(src: ByteBuffer, dst: ByteBuffer) {
        for (c in 0 until channelCount) {
            if (isFloatPcm) {
                dst.putFloat(src.getFloat())
            } else {
                dst.putShort(src.getShort())
            }
        }
    }

    private fun copyFrameWithGain(src: ByteBuffer, dst: ByteBuffer, gain: Float) {
        for (c in 0 until channelCount) {
            if (isFloatPcm) {
                dst.putFloat(src.getFloat() * gain)
            } else {
                val sample = (src.getShort() * gain).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                dst.putShort(sample.toShort())
            }
        }
    }

    private fun skipFrame(src: ByteBuffer) {
        src.position(src.position() + bytesPerFrame)
    }
}
