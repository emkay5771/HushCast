package app.hushcast.automute.dsp

import androidx.annotation.OptIn
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor

/**
 * Subclasses Media3's public DefaultAudioProcessorChain so Sample-Drop Micro-Skip
 * reports dropped frames alongside SilenceSkippingAudioProcessor without position drift
 * or losing playback speed / silence-skipping capabilities.
 */
@OptIn(UnstableApi::class)
class HushCastAudioProcessorChain(
    private val autoMuteAudioProcessor: AutoMuteAudioProcessor,
    private val silenceSkippingAudioProcessor: SilenceSkippingAudioProcessor = SilenceSkippingAudioProcessor(),
    sonicAudioProcessor: SonicAudioProcessor = SonicAudioProcessor()
) : DefaultAudioSink.DefaultAudioProcessorChain(
    arrayOf(autoMuteAudioProcessor),
    silenceSkippingAudioProcessor,
    sonicAudioProcessor
) {
    override fun getSkippedOutputFrameCount(): Long {
        return silenceSkippingAudioProcessor.skippedFrames + autoMuteAudioProcessor.skippedFrames
    }
}
