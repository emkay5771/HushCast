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
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Sample-accurate Media3 AudioProcessor for Feature A (Word-Level Auto-Muting).
 * Runs BEFORE SilenceSkippingAudioProcessor and SonicAudioProcessor so timestamps
 * match the original audio timeline.
 *
 * Uses a sorted-window cursor and bulk-copies unmuted stretches (E9),
 * computes gain as min(startRamp, endRamp) to avoid clicks on short windows (E10),
 * and supports explicit timeline resets on seeks/transitions (E11).
 */
@OptIn(UnstableApi::class)
class AutoMuteAudioProcessor : BaseAudioProcessor() {

    companion object {
        private const val RAMP_MS_SILENCE = 8.0
        private const val RAMP_MS_DROP = 12.0
        private const val DUCK_FLOOR = 0.10f
        const val DEFAULT_ENCODER_DELAY_MS = 30L
    }

    @Volatile
    private var windows: List<MuteWindow> = emptyList()

    @Volatile
    var isEnabled: Boolean = true

    @Volatile
    var defaultActionMode: MuteActionMode = MuteActionMode.HARD_SILENCE

    @Volatile
    var encoderDelayOffsetMs: Long = DEFAULT_ENCODER_DELAY_MS

    private val skippedFramesCounter = AtomicLong(0L)
    val skippedFrames: Long
        get() = skippedFramesCounter.get()

    private var currentInputFrameIndex: Long = 0L
    private var windowCursor: Int = 0
    private var sampleRateHz: Int = 44100
    private var channelCount: Int = 2
    private var bytesPerFrame: Int = 4
    private var isFloatPcm: Boolean = false

    fun updateWindows(newWindows: List<MuteWindow>) {
        windows = newWindows.filter { it.enabled && it.endMs > it.startMs }.sortedBy { it.startMs }
        resetCursorForFrame(currentInputFrameIndex)
    }

    fun resetTimeline(positionMs: Long) {
        val posMs = positionMs.coerceAtLeast(0L)
        currentInputFrameIndex = (posMs * sampleRateHz) / 1000L
        skippedFramesCounter.set(0L)
        resetCursorForFrame(currentInputFrameIndex)
    }

    override fun isActive(): Boolean = true

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
        skippedFramesCounter.set(0L)
        resetCursorForFrame(currentInputFrameIndex)
    }

    override fun onReset() {
        currentInputFrameIndex = 0L
        windowCursor = 0
        skippedFramesCounter.set(0L)
    }

    private fun resetCursorForFrame(frameIndex: Long) {
        val active = windows
        if (active.isEmpty()) {
            windowCursor = 0
            return
        }
        val posMs = (frameIndex * 1000L) / sampleRateHz.coerceAtLeast(1)
        val delayMs = encoderDelayOffsetMs
        // Binary search for the first window whose shifted endMs >= posMs
        var lo = 0
        var hi = active.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val we = (active[mid].endMs + delayMs).coerceAtLeast(1L)
            if (we < posMs) lo = mid + 1 else hi = mid
        }
        windowCursor = lo
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

        val delayMs = encoderDelayOffsetMs
        val bufStartMs = (currentInputFrameIndex * 1000L) / sampleRateHz
        val bufEndMs = ((currentInputFrameIndex + frameCount) * 1000L) / sampleRateHz

        var idx = windowCursor.coerceIn(0, activeWindows.size)
        while (idx < activeWindows.size) {
            val we = (activeWindows[idx].endMs + delayMs).coerceAtLeast(1L)
            if (we >= bufStartMs) break
            idx++
        }
        windowCursor = idx

        // Fast path: next window starts after this buffer ends -> single bulk copy
        if (idx >= activeWindows.size || (activeWindows[idx].startMs + delayMs).coerceAtLeast(0L) > bufEndMs) {
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            currentInputFrameIndex += frameCount
            return
        }

        val out = replaceOutputBuffer(remaining)
        val rampFramesSilence = ((RAMP_MS_SILENCE * sampleRateHz) / 1000.0).roundToInt().coerceAtLeast(1)
        val rampFramesDrop = ((RAMP_MS_DROP * sampleRateHz) / 1000.0).roundToInt().coerceAtLeast(1)

        var droppedInBuffer = 0L
        var f = 0
        while (f < frameCount) {
            val frameIndex = currentInputFrameIndex + f
            val frameMs = (frameIndex * 1000.0) / sampleRateHz

            while (idx < activeWindows.size) {
                val we = (activeWindows[idx].endMs + delayMs).coerceAtLeast(
                    activeWindows[idx].startMs + delayMs + 1L
                ).toDouble()
                if (frameMs <= we) break
                idx++
            }

            if (idx >= activeWindows.size) {
                // Bulk copy rest of buffer
                val leftFrames = frameCount - f
                bulkCopyFrames(inputBuffer, out, leftFrames)
                f += leftFrames
                break
            }

            val w = activeWindows[idx]
            val wsMs = (w.startMs + delayMs).coerceAtLeast(0L).toDouble()
            val weMs = (w.endMs + delayMs).coerceAtLeast(w.startMs + delayMs + 1L).toDouble()

            if (frameMs < wsMs) {
                // Bulk copy up to the start of the window
                val framesUntilStart = ((wsMs - frameMs) * sampleRateHz / 1000.0).toInt().coerceAtLeast(1)
                val copyCount = min(framesUntilStart, frameCount - f)
                bulkCopyFrames(inputBuffer, out, copyCount)
                f += copyCount
                continue
            }

            val framesFromStart = ((frameMs - wsMs) * sampleRateHz / 1000.0).toInt().coerceAtLeast(0)
            val framesToEnd = ((weMs - frameMs) * sampleRateHz / 1000.0).toInt().coerceAtLeast(0)

            when (w.actionMode) {
                MuteActionMode.HARD_SILENCE -> {
                    val gain = cosineRampGain(framesFromStart, framesToEnd, rampFramesSilence, floor = 0.0f)
                    copyFrameWithGain(inputBuffer, out, gain)
                }
                MuteActionMode.VOLUME_DUCK -> {
                    val gain = cosineRampGain(framesFromStart, framesToEnd, rampFramesSilence, floor = DUCK_FLOOR)
                    copyFrameWithGain(inputBuffer, out, gain)
                }
                MuteActionMode.SAMPLE_DROP_MICRO_SKIP -> {
                    if (framesFromStart < rampFramesDrop || framesToEnd < rampFramesDrop) {
                        val gain = cosineRampGain(framesFromStart, framesToEnd, rampFramesDrop, floor = 0.0f)
                        copyFrameWithGain(inputBuffer, out, gain)
                    } else {
                        skipFrame(inputBuffer)
                        droppedInBuffer++
                    }
                }
            }
            f++
        }

        windowCursor = idx
        if (droppedInBuffer > 0L) {
            skippedFramesCounter.addAndGet(droppedInBuffer)
        }
        out.flip()
        currentInputFrameIndex += frameCount
    }

    private fun bulkCopyFrames(src: ByteBuffer, dst: ByteBuffer, count: Int) {
        val bytes = count * bytesPerFrame
        val oldLimit = src.limit()
        src.limit(src.position() + bytes)
        dst.put(src)
        src.limit(oldLimit)
    }

    private fun cosineRampGain(
        framesFromStart: Int,
        framesToEnd: Int,
        rampFrames: Int,
        floor: Float
    ): Float {
        val startGain = if (framesFromStart < rampFrames) {
            val t = framesFromStart.toFloat() / rampFrames.toFloat()
            val factor = 0.5f * (1.0f + cos(PI * t).toFloat())
            floor + (1.0f - floor) * factor
        } else {
            floor
        }
        val endGain = if (framesToEnd < rampFrames) {
            val t = framesToEnd.toFloat() / rampFrames.toFloat()
            val factor = 0.5f * (1.0f + cos(PI * t).toFloat())
            floor + (1.0f - floor) * factor
        } else {
            floor
        }
        return if (framesFromStart < rampFrames && framesToEnd < rampFrames) {
            // Short window (< 2 ramps): take the higher (less-attenuated) ramp so it smoothly dips and rises
            maxOf(startGain, endGain)
        } else if (framesFromStart < rampFrames) {
            startGain
        } else if (framesToEnd < rampFrames) {
            endGain
        } else {
            floor
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
