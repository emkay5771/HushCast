package app.hushcast.automute.dsp

import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

data class AdSkipEvent(
    val adWindow: AdWindow,
    val jumpedFromMs: Long,
    val jumpedToMs: Long
)

/**
 * Playback boundary observer for Feature B (On-Device Sponsor / Ad Blocker).
 * Auto-skips high-confidence sponsor_ad spans (p >= 0.80) when AdPolicyMode == AUTO_SKIP
 * and emits an AdSkipEvent for the 6-second One-Tap Undo snackbar.
 *
 * Tracks auto-skipped and user-bypassed ads separately (P6) so seeking back
 * into an auto-skipped ad still skips it unless the user explicitly chose Undo.
 */
class AdSkipPlaybackObserver {

    @Volatile
    var policyMode: AdPolicyMode = AdPolicyMode.AUTO_SKIP

    @Volatile
    var autoSkipScope: AdAutoSkipScope = AdAutoSkipScope.HIGH_CONFIDENCE_ONLY

    @Volatile
    var autoSkipClasses: Set<String> = setOf("sponsor_ad")

    @Volatile
    private var adWindows: List<AdWindow> = emptyList()

    private val autoSkippedStartMs = ConcurrentHashMap.newKeySet<Long>()
    private val userBypassedStartMs = ConcurrentHashMap.newKeySet<Long>()

    private val _skipEvents = MutableSharedFlow<AdSkipEvent>(extraBufferCapacity = 4)
    val skipEvents: SharedFlow<AdSkipEvent> = _skipEvents.asSharedFlow()

    fun shouldAutoSkip(ad: AdWindow): Boolean {
        if (ad.adClass !in autoSkipClasses) return false
        return when (autoSkipScope) {
            AdAutoSkipScope.HIGH_CONFIDENCE_ONLY -> ad.isHighConfidence || ad.confidence >= 0.80f
            AdAutoSkipScope.MODERATE_AND_HIGH -> ad.isHighConfidence || ad.confidence >= 0.65f
            AdAutoSkipScope.ALL_DETECTED -> true
        }
    }

    fun setEpisodeAds(windows: List<AdWindow>, policy: AdPolicyMode) {
        policyMode = policy
        adWindows = windows.sortedBy { it.startMs }
        autoSkippedStartMs.clear()
        userBypassedStartMs.clear()
    }

    fun clear() {
        adWindows = emptyList()
        autoSkippedStartMs.clear()
        userBypassedStartMs.clear()
    }

    fun getAdWindows(): List<AdWindow> = adWindows

    /**
     * Checks whether [pos] is inside an active sponsor ad matching [autoSkipScope] and triggers an auto-skip.
     * Used both by PlayerMessage callbacks (P5) and on position discontinuities.
     */
    fun checkPosition(player: Player, leadToleranceMs: Long = 0L): Boolean {
        if (policyMode != AdPolicyMode.AUTO_SKIP) return false
        if (!player.isPlaying) return false

        val pos = player.currentPosition
        for (ad in adWindows) {
            if (!shouldAutoSkip(ad)) continue
            if (ad.startMs in userBypassedStartMs) continue
            val effectiveStart = (ad.startMs - leadToleranceMs).coerceAtLeast(0L)
            if (pos in effectiveStart until (ad.endMs - 500L)) {
                autoSkippedStartMs.add(ad.startMs)
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

    fun bypassAd(adStartMs: Long) {
        userBypassedStartMs.add(adStartMs)
    }

    /**
     * Called when the user taps "Undo" on the 6-second snackbar.
     * Seeks back to the ad start and suppresses re-skipping that span for this playback.
     */
    fun undoSkip(player: Player, event: AdSkipEvent) {
        userBypassedStartMs.add(event.adWindow.startMs)
        player.seekTo(event.jumpedFromMs)
    }

    fun getActiveAutoSkipRanges(): List<SkipAwareSeekCalculator.SkipRange> {
        if (policyMode != AdPolicyMode.AUTO_SKIP) return emptyList()
        return adWindows
            .filter { shouldAutoSkip(it) && it.startMs !in userBypassedStartMs }
            .map { SkipAwareSeekCalculator.SkipRange(it.startMs, it.endMs) }
    }
}
