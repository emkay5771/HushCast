package app.podiumpodcasts.podium.background

import app.hushcast.automute.dsp.AdPolicyMode
import app.hushcast.automute.dsp.AdSkipEvent
import app.hushcast.automute.dsp.AdSkipPlaybackObserver
import app.hushcast.automute.dsp.AutoMuteAudioProcessor
import app.hushcast.automute.dsp.MuteActionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Minimal bridge between :core:automute-engine and Podium's PlaybackService / UI.
 * Keeps fork isolation clean so upstream Podium changes remain easy to merge.
 */
object HushCastEngineBridge {
    val autoMuteProcessor = AutoMuteAudioProcessor()
    val adSkipObserver = AdSkipPlaybackObserver()

    val adSkipEvents: SharedFlow<AdSkipEvent>
        get() = adSkipObserver.skipEvents

    private val _pendingUndoEvent = MutableStateFlow<AdSkipEvent?>(null)
    val pendingUndoEvent: StateFlow<AdSkipEvent?> = _pendingUndoEvent.asStateFlow()

    private val _isCastingExternal = MutableStateFlow(false)
    val isCastingExternal: StateFlow<Boolean> = _isCastingExternal.asStateFlow()

    var defaultActionMode: MuteActionMode
        get() = autoMuteProcessor.defaultActionMode
        set(value) {
            autoMuteProcessor.defaultActionMode = value
        }

    var wordMutingEnabled: Boolean
        get() = autoMuteProcessor.isEnabled
        set(value) {
            autoMuteProcessor.isEnabled = value
        }

    var defaultAdPolicy: AdPolicyMode = AdPolicyMode.AUTO_SKIP

    fun notifyAdSkipped(event: AdSkipEvent) {
        _pendingUndoEvent.value = event
    }

    fun clearPendingUndo() {
        _pendingUndoEvent.value = null
    }

    fun setCastingExternal(casting: Boolean) {
        _isCastingExternal.value = casting
    }
}
