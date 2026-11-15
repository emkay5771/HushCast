package app.hushcast.automute.dsp

import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class AdSkipEvent(
    val adWindow: AdWindow,
    val jumpedFromMs: Long,
    val jumpedToMs: Long
)

/**
 * Playback boundary observer for Feature B (On-Device Sponsor / Ad Blocker).
 * Auto-skips high-confidence ad spans (p >= 0.80) when AdPolicyMode == AUTO_SKIP
 * and emits an AdSkipEvent for the 6-second One-Tap Undo snackbar.
 */
class AdSkipPlaybackObserver {

    @Volatile
    var policyMode: AdPolicyMode = AdPolicyMode.AUTO_SKIP

    @Volatile
    private var adWindows: List<AdWindow> = emptyList()

    private val bypassedStartMs = HashSet<Long>()

    private val _skipEvents = MutableSharedFlow<AdSkipEvent>(extraBufferCapacity = 4)
    val skipEvents: SharedFlow<AdSkipEvent> = _skipEvents.asSharedFlow()

    fun setEpisodeAds(windows: List<AdWindow>, policy: AdPolicyMode) {
        policyMode = policy
        adWindows = windows.sortedBy { it.startMs }
        bypassedStartMs.clear()
    }

    fun clear() {
        adWindows = emptyList()
        bypassedStartMs.clear()
    }

    /**
     * Call periodically (e.g., every 250-500ms) while playing.
     * Returns true if an auto-skip seek was triggered.
     */
    fun checkPosition(player: Player): Boolean {
        if (policyMode != AdPolicyMode.AUTO_SKIP) return false
        if (!player.isPlaying) return false

        val pos = player.currentPosition
        for (ad in adWindows) {
            if (!ad.isHighConfidence) continue
            if (ad.startMs in bypassedStartMs) continue
            if (pos in ad.startMs until (ad.endMs - 500L)) {
                bypassedStartMs.add(ad.startMs)
                player.seekTo(ad.endMs)
                _skipEvents.tryEmit(
                    AdSkipEvent(
                        adWindow = ad,
                        jumpedFromMs = ad.startMs,
                        jumpedToMs = ad.endMs
                    )
                )
                return true
            }
        }
        return false
    }

    /**
     * Called when the user taps "Undo" on the 6-second snackbar.
     * Seeks back to the ad start and suppresses re-skipping that span for this playback.
     */
    fun undoSkip(player: Player, event: AdSkipEvent) {
        bypassedStartMs.add(event.adWindow.startMs)
        player.seekTo(event.jumpedFromMs)
    }
}
