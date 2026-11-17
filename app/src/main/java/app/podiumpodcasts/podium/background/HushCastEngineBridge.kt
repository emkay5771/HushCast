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

    @Volatile
    var currentEpisodeId: String? = null

    @Volatile
    private var skippedChapterRanges: List<app.hushcast.automute.dsp.SkipAwareSeekCalculator.SkipRange> = emptyList()

    fun updateSkippedChapterRanges(
        episodeId: String,
        chapters: List<app.hushcast.automute.desktop.DesktopChapterDto>,
        skippedStarts: Set<Long>,
        durationMs: Long
    ) {
        if (episodeId != currentEpisodeId && !currentEpisodeId.isNullOrBlank()) return
        if (chapters.isEmpty() || skippedStarts.isEmpty()) {
            skippedChapterRanges = emptyList()
            return
        }
        val sorted = chapters.sortedBy { it.startMs }
        val ranges = ArrayList<app.hushcast.automute.dsp.SkipAwareSeekCalculator.SkipRange>()
        for (i in sorted.indices) {
            val ch = sorted[i]
            if (ch.startMs in skippedStarts) {
                val endMs = sorted.getOrNull(i + 1)?.startMs ?: durationMs.coerceAtLeast(ch.startMs)
                if (endMs > ch.startMs) {
                    ranges.add(app.hushcast.automute.dsp.SkipAwareSeekCalculator.SkipRange(ch.startMs, endMs))
                }
            }
        }
        skippedChapterRanges = ranges
    }

    fun clearSkippedChapterRanges() {
        skippedChapterRanges = emptyList()
    }

    fun getActiveSkipRanges(): List<app.hushcast.automute.dsp.SkipAwareSeekCalculator.SkipRange> {
        return adSkipObserver.getActiveAutoSkipRanges() + skippedChapterRanges
    }

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
