package app.podiumpodcasts.podium.background.car

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import app.hushcast.automute.dsp.AdPolicyMode
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.background.HushCastEngineBridge
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.formatAdDisplayLabel

/**
 * Android Auto's Now Playing extras: Skip sponsor / Undo, Next chapter, the live subtitle, and
 * plain-language errors.
 *
 * Android Auto shows the platform session's buttons, which the phone's notification shares, so all
 * of this is on only while the phone is connected to a car ("car mode"). Main thread only.
 */
@UnstableApi
class CarCommands(
    private val context: Context,
    private val strings: CarStrings,
    private val sessionPlayer: HushCastSessionPlayer,
    private val session: () -> MediaSession?,
    /** Rebuilds the session's buttons (PlaybackService.buildMediaButtons). */
    private val onButtonsChanged: () -> Unit,
    private val jumpToNextChapter: () -> Boolean,
    /** Asks the car to reload its lists (Up Next, New, Downloads). */
    private val onListsChanged: () -> Unit
) {
    private var carConnected = false
    private val handler = Handler(Looper.getMainLooper())

    val isCarMode: Boolean get() = carConnected

    var state: CarNowPlaying = CarNowPlaying.NONE
        private set

    // ---- car mode (see CarConnectionWatcher) ----

    fun setCarConnected(connected: Boolean) {
        if (connected == carConnected) return
        carConnected = connected
        if (connected) {
            state = CarNowPlaying.NONE
            refresh(forceButtons = true)
            scheduleTick()
        } else {
            handler.removeCallbacks(tick)
            handler.removeCallbacks(clearNotice)
            state = CarNowPlaying.NONE
            sessionPlayer.subtitle = null
            clearError()
            onButtonsChanged()
        }
    }

    // ---- state ----

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            scheduleTick()
        }
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        if (isCarMode && sessionPlayer.isPlaying) handler.postDelayed(tick, TICK_MS)
    }

    /** Recomputes the buttons and subtitle; buttons are rebuilt only when one appears or goes. */
    fun refresh(forceButtons: Boolean = false) {
        if (!isCarMode) return
        val next = compute()
        sessionPlayer.subtitle = next.subtitle
        val changed = (next.skipAdToMs != null) != (state.skipAdToMs != null) ||
            next.showUndo != state.showUndo ||
            next.hasNextChapter != state.hasNextChapter
        state = next
        if (changed || forceButtons) onButtonsChanged()
    }

    private fun compute(): CarNowPlaying {
        if (sessionPlayer.currentMediaItem == null) return CarNowPlaying.NONE
        val pos = sessionPlayer.currentPosition
        val observer = HushCastEngineBridge.adSkipObserver
        val ads = observer.getAdWindows().map {
            CarAd(it.startMs, it.endMs, formatAdDisplayLabel(it), observer.willAutoSkip(it))
        }
        val pending = HushCastEngineBridge.pendingUndoEvent.value
        val undoValid = pending != null &&
            HushCastEngineBridge.isUndoValid(pending, HushCastEngineBridge.currentEpisodeId, pos)
        return CarNowPlayingRules.compute(
            s = strings,
            positionMs = pos,
            sponsorSkippingOn = observer.policyMode != AdPolicyMode.OFF,
            ads = ads,
            undoValid = undoValid,
            undoAdLabel = pending?.event?.adWindow?.let { formatAdDisplayLabel(it) },
            undoSkippedMs = pending?.event?.let { (it.jumpedToMs - it.jumpedFromMs).coerceAtLeast(0L) } ?: 0L,
            chapters = HushCastEngineBridge.currentResolvedChapters.map { CarChapter(it.startMs, it.title) },
            skippedChapterStarts = HushCastEngineBridge.getSkippedChapterRanges().mapTo(HashSet()) { it.startMs }
        )
    }

    /** Player events that can change what Now Playing shows. */
    val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (!isCarMode) return
            if (events.containsAny(
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED
                )
            ) {
                refresh()
                scheduleTick()
            }
            if (player.isPlaying && events.contains(Player.EVENT_IS_PLAYING_CHANGED)) clearError()
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) clearError()
            // The car keeps a list until told it changed: a new episode or queue, or a pause
            // (which saves the position shown as "31m left").
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED) ||
                (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && !player.isPlaying)
            ) listsChanged()
        }
    }

    /** Debounced, so a burst of player events reloads the car's lists once. */
    fun listsChanged() {
        if (!isCarMode) return
        handler.removeCallbacks(reloadLists)
        handler.postDelayed(reloadLists, LISTS_DELAY_MS)
    }

    private val reloadLists = Runnable { onListsChanged() }

    // ---- buttons ----

    /** The car's extra buttons, in priority order, before Speed. Empty outside car mode. */
    fun buttons(): List<CommandButton> {
        if (!isCarMode) return emptyList()
        val list = ArrayList<CommandButton>(2)
        if (state.showUndo) {
            list += CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setCustomIconResId(R.drawable.ic_car_undo)
                .setSessionCommand(SessionCommand(CarActions.UNDO_SKIP, Bundle.EMPTY))
                .setDisplayName(context.getString(R.string.car_action_undo_skip))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build()
        } else if (state.skipAdToMs != null) {
            list += CommandButton.Builder(CommandButton.ICON_FAST_FORWARD)
                .setCustomIconResId(R.drawable.ic_car_skip_ad)
                .setSessionCommand(SessionCommand(CarActions.SKIP_AD, Bundle.EMPTY))
                .setDisplayName(context.getString(R.string.car_action_skip_sponsor))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build()
        }
        if (state.hasNextChapter) {
            list += CommandButton.Builder(CommandButton.ICON_NEXT)
                .setSessionCommand(SessionCommand(CarActions.NEXT_CHAPTER, Bundle.EMPTY))
                .setDisplayName(context.getString(R.string.car_action_next_chapter))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build()
        }
        return list
    }

    /** Runs a Now Playing button. Returns false for commands this class doesn't own. */
    fun handle(action: String): Boolean {
        when (action) {
            CarActions.SKIP_AD -> {
                // As the phone's sponsor row: seek to the ad's end, nothing else.
                compute().skipAdToMs?.let { sessionPlayer.seekTo(it) }
            }
            CarActions.UNDO_SKIP -> {
                // As the phone's Undo: play this ad once, from its start.
                val pending = HushCastEngineBridge.pendingUndoEvent.value
                if (pending != null &&
                    HushCastEngineBridge.isUndoValid(pending, HushCastEngineBridge.currentEpisodeId, sessionPlayer.currentPosition)
                ) {
                    val start = pending.event.adWindow.startMs
                    HushCastEngineBridge.adSkipObserver.bypassAd(start)
                    HushCastEngineBridge.revertSavingsForAdStart(context, start)
                    sessionPlayer.seekTo(start)
                    HushCastEngineBridge.clearPendingUndo()
                }
            }
            CarActions.NEXT_CHAPTER -> jumpToNextChapter()
            else -> return false
        }
        refresh()
        return true
    }

    // ---- errors (VI-1: plain words, check the phone only when it's safe) ----

    private var errorShown = false

    fun onPlayerError(error: PlaybackException, isLocalFile: Boolean) {
        if (!isCarMode) return
        val text = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                context.getString(R.string.car_error_offline)
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                if (isLocalFile) context.getString(R.string.car_error_file_missing)
                else context.getString(R.string.car_error_generic)
            else -> context.getString(R.string.car_error_generic)
        }
        showError(PlaybackException(text, null, error.errorCode))
    }

    /** A voice request found nothing. Shown for a few seconds; playback is untouched. */
    fun showNotFound(query: String) {
        if (!isCarMode) return
        showError(
            PlaybackException(
                context.getString(R.string.car_error_not_found, query),
                null,
                PlaybackException.ERROR_CODE_UNSPECIFIED
            )
        )
        handler.removeCallbacks(clearNotice)
        handler.postDelayed(clearNotice, NOTICE_MS)
    }

    private val clearNotice = Runnable { clearError() }

    private fun showError(e: PlaybackException) {
        val s = session() ?: return
        val target = s.mediaNotificationControllerInfo
        if (target != null) s.setPlaybackException(target, e) else s.setPlaybackException(e)
        errorShown = true
    }

    fun clearError() {
        if (!errorShown) return
        errorShown = false
        val s = session() ?: return
        val target = s.mediaNotificationControllerInfo
        if (target != null) s.setPlaybackException(target, null) else s.setPlaybackException(null)
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        carConnected = false
    }

    companion object {
        private const val TICK_MS = 1000L
        private const val NOTICE_MS = 6000L
        private const val LISTS_DELAY_MS = 1500L
    }
}
