package app.hushcast.automute.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AutoMuteAudioProcessorTest {

    private fun makePcm16Buffer(sampleCount: Int, channels: Int, value: Short): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(sampleCount * channels * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until sampleCount * channels) {
            buf.putShort(value)
        }
        buf.flip()
        return buf
    }

    @Test
    fun passthroughWhenDisabledOrNoWindows() {
        val proc = AutoMuteAudioProcessor()
        proc.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        proc.flush(AudioProcessor.StreamMetadata(0L))
        proc.isEnabled = false
        proc.updateWindows(listOf(MuteWindow(0L, 1000L, "test")))

        val input = makePcm16Buffer(480, 1, 10000)
        proc.queueInput(input)
        val out = proc.output
        assertEquals(480 * 2, out.remaining())
        for (i in 0 until 480) {
            assertEquals(10000.toShort(), out.short)
        }
    }

    @Test
    fun hardSilenceMutesInsideWindowAndPreservesOutside() {
        val proc = AutoMuteAudioProcessor()
        proc.encoderDelayOffsetMs = 0L
        proc.configure(AudioProcessor.AudioFormat(1000, 1, C.ENCODING_PCM_16BIT))
        proc.flush(AudioProcessor.StreamMetadata(0L))
        proc.isEnabled = true
        // 1000 Hz => 1 sample = 1 ms. Mute [100ms, 200ms]
        proc.updateWindows(listOf(MuteWindow(100L, 200L, "bad", actionMode = MuteActionMode.HARD_SILENCE)))

        val input = makePcm16Buffer(300, 1, 10000)
        proc.queueInput(input)
        val out = proc.output
        val samples = ShortArray(300) { out.short }

        // Before window (0..90ms) should be untouched
        assertEquals(10000.toShort(), samples[10])
        // Middle of window (150ms) should be 0
        assertEquals(0.toShort(), samples[150])
        // After window (250ms) should be untouched
        assertEquals(10000.toShort(), samples[250])
    }

    @Test
    fun shortWindowRampDoesNotJumpAboveHalf() {
        val proc = AutoMuteAudioProcessor()
        proc.encoderDelayOffsetMs = 0L
        proc.configure(AudioProcessor.AudioFormat(1000, 1, C.ENCODING_PCM_16BIT))
        proc.flush(AudioProcessor.StreamMetadata(0L))
        proc.isEnabled = true
        // 4ms window (< 2 * 8ms ramp)
        proc.updateWindows(listOf(MuteWindow(100L, 104L, "um", actionMode = MuteActionMode.HARD_SILENCE)))

        val input = makePcm16Buffer(200, 1, 10000)
        proc.queueInput(input)
        val out = proc.output
        val samples = ShortArray(200) { out.short }

        // At midpoint 102ms (2ms into a 4ms window, ramp=8ms), gain = min(startRamp, endRamp) < 1.0
        assertTrue("midpoint should be attenuated", samples[102] < 9500)
        assertEquals(10000.toShort(), samples[50])
        assertEquals(10000.toShort(), samples[150])
    }

    @Test
    fun seekTo30MinutesAnchorsTimeline() {
        val proc = AutoMuteAudioProcessor()
        proc.encoderDelayOffsetMs = 0L
        proc.configure(AudioProcessor.AudioFormat(1000, 1, C.ENCODING_PCM_16BIT))
        val thirtyMinUs = 30L * 60L * 1_000_000L
        proc.flush(AudioProcessor.StreamMetadata(thirtyMinUs))
        proc.isEnabled = true

        val thirtyMinMs = 30L * 60L * 1000L
        proc.updateWindows(
            listOf(MuteWindow(thirtyMinMs + 50L, thirtyMinMs + 150L, "ad", actionMode = MuteActionMode.HARD_SILENCE))
        )

        val input = makePcm16Buffer(200, 1, 10000)
        proc.queueInput(input)
        val out = proc.output
        val samples = ShortArray(200) { out.short }

        assertEquals(10000.toShort(), samples[10])
        assertEquals(0.toShort(), samples[100])
        assertEquals(10000.toShort(), samples[180])
    }
}
