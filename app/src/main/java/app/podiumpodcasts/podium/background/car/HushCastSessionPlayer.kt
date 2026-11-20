package app.podiumpodcasts.podium.background.car

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.hushcast.automute.dsp.SkipAwareSeekCalculator
import app.podiumpodcasts.podium.background.HushCastEngineBridge
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The player the media session sees. It wraps ExoPlayer for two things:
 * - **Ad-aware ±seek:** the back and forward buttons step over skipped ads and chapters, as before.
 * - **Live subtitle:** while [subtitle] is set (car mode only), it replaces the line under the episode
 *   title: the ad, the skipped ad, or the chapter. The episode itself is unchanged.
 */
@UnstableApi
class HushCastSessionPlayer(
    private val player: Player,
    private val seekBackIncrementMs: () -> Long,
    private val seekForwardIncrementMs: () -> Long
) : ForwardingSimpleBasePlayer(player) {

    /** Main thread only. Null shows the podcast's name. */
    var subtitle: String? = null
        set(value) {
            if (field == value) return
            field = value
            invalidateState()
        }

    override fun getState(): State {
        val state = super.getState()
        val text = subtitle ?: return state
        if (state.timeline.isEmpty) return state
        return state.buildUpon()
            .setPlaylist(
                state.timeline,
                state.currentTracks,
                state.currentMetadata.buildUpon().setSubtitle(text).build()
            )
            .build()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_FORWARD -> {
                val ranges = HushCastEngineBridge.getActiveSkipRanges()
                if (ranges.isNotEmpty()) {
                    player.seekTo(
                        SkipAwareSeekCalculator.computeForwardTargetMs(
                            fromMs = player.currentPosition,
                            incrementMs = seekForwardIncrementMs(),
                            durationMs = player.duration,
                            skipRanges = ranges
                        )
                    )
                    return Futures.immediateVoidFuture()
                }
            }
            Player.COMMAND_SEEK_BACK -> {
                val ranges = HushCastEngineBridge.getActiveSkipRanges()
                if (ranges.isNotEmpty()) {
                    player.seekTo(
                        SkipAwareSeekCalculator.computeBackTargetMs(
                            fromMs = player.currentPosition,
                            incrementMs = seekBackIncrementMs(),
                            skipRanges = ranges
                        )
                    )
                    return Futures.immediateVoidFuture()
                }
            }
            else -> Unit
        }
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }
}
