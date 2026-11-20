package app.podiumpodcasts.podium.background

import android.os.Bundle
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.podiumpodcasts.podium.HeadsetNextAction
import app.podiumpodcasts.podium.HeadsetPrevAction
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.db.model.MediaMetadataExtra
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle
import app.podiumpodcasts.podium.background.car.CarActions
import app.podiumpodcasts.podium.background.car.CarIds
import app.podiumpodcasts.podium.background.car.CarKeys
import app.podiumpodcasts.podium.background.car.CarSections
import app.podiumpodcasts.podium.manager.DownloadManager
import app.podiumpodcasts.podium.utils.getEpisodeId
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withContext

@UnstableApi
class MediaLibrarySessionCallback(
    val service: PlaybackService
) : MediaLibrarySession.Callback {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    /** The last search, so the car's result request doesn't run it twice. */
    @Volatile
    private var lastSearch: Pair<String, List<MediaItem>>? = null

    fun destroy() {
        job.cancel()
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .add(SessionCommand(COMMAND_SLEEP_TIMER_SET, Bundle.EMPTY))
            .add(SessionCommand(COMMAND_SLEEP_TIMER_GET, Bundle.EMPTY))
            .add(SessionCommand(COMMAND_CYCLE_SPEED, Bundle.EMPTY))
            .add(SessionCommand(COMMAND_SET_SEEK_BACK_INCREMENT, Bundle.EMPTY))
            .add(SessionCommand(COMMAND_SET_SEEK_FORWARD_INCREMENT, Bundle.EMPTY))
            .apply {
                (CarActions.ROW + CarActions.NOW_PLAYING).forEach { add(SessionCommand(it, Bundle.EMPTY)) }
            }
            .build()

        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {

            COMMAND_SLEEP_TIMER_SET -> {
                val mode = customCommand.customExtras.getString("mode")
                when (mode) {
                    "END_OF_EPISODE" -> {
                        service.startSleepTimerEndOfEpisode(
                            approxTrigger = customCommand.customExtras.getLong("approxTrigger", 0L)
                        )
                    }
                    "END_OF_CHAPTER" -> {
                        val posMs = customCommand.customExtras.getLong("positionMs", -1L)
                        if (posMs >= 0L) {
                            service.startSleepTimerAtPosition(
                                positionMs = posMs,
                                approxTrigger = customCommand.customExtras.getLong("approxTrigger", 0L)
                            )
                        }
                    }
                    else -> {
                        val trigger = customCommand.customExtras.getLong("trigger")
                        if (trigger != 0L) {
                            service.startSleepTimer(trigger = trigger)
                        } else {
                            service.stopSleepTimer()
                        }
                    }
                }

                return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_SUCCESS)
                )
            }

            COMMAND_SLEEP_TIMER_GET -> {
                val extras = Bundle().apply {
                    putLong("trigger", service.sleepTimerTrigger ?: 0L)
                }

                return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_SUCCESS, extras)
                )
            }

            COMMAND_CYCLE_SPEED -> {
                val currentSpeed = session.player.playbackParameters.speed
                val presets = service.speedPresets.sorted()
                val nextSpeed = presets.firstOrNull { it > currentSpeed + 0.01f } ?: presets.firstOrNull() ?: 1.0f
                // Saves to the podcast's own speed or the global one, whichever is in effect.
                service.applyUserSpeed(nextSpeed)

                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            COMMAND_SET_SEEK_BACK_INCREMENT -> {
                val increment = customCommand.customExtras.getLong("increment")
                service.updateSeekBackIncrement(increment)

                return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_SUCCESS)
                )
            }

            COMMAND_SET_SEEK_FORWARD_INCREMENT -> {
                val increment = customCommand.customExtras.getLong("increment")
                service.updateSeekForwardIncrement(increment)

                return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_SUCCESS)
                )
            }

            in CarActions.NOW_PLAYING -> {
                val handled = service.carCommands?.handle(customCommand.customAction) == true
                return Futures.immediateFuture(
                    SessionResult(if (handled) SessionResult.RESULT_SUCCESS else SessionError.ERROR_NOT_SUPPORTED)
                )
            }

            in CarActions.ROW -> return onRowAction(session, customCommand.customAction, args)

            else -> return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    // ---- Android Auto row actions: Add to Up Next, Download, Mark played ----

    private fun onRowAction(
        session: MediaSession,
        action: String,
        args: Bundle
    ): ListenableFuture<SessionResult> = scope.future {
        val episodeId = args.getString(MediaConstants.EXTRA_KEY_MEDIA_ID)
            ?.takeIf { it.startsWith(CarIds.EPISODE_PREFIX) }
            ?.removePrefix(CarIds.EPISODE_PREFIX)
        val bundle = episodeId?.let { withContext(Dispatchers.IO) { service.db.podcastEpisodes().getSyncNullable(it) } }
            ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)

        val message = when (action) {
            CarActions.ADD_UP_NEXT -> {
                service.addToUpNextFromCar(bundle)
                R.string.car_result_added_up_next
            }
            CarActions.DOWNLOAD -> {
                withContext(Dispatchers.IO) {
                    DownloadManager.downloadEpisode(service, service.db, bundle.episode.id)
                }
                R.string.car_result_downloading
            }
            CarActions.MARK_PLAYED -> {
                withContext(Dispatchers.IO) {
                    service.db.podcastEpisodePlayStates().upsertPlayed(bundle.episode.id, true)
                    service.db.podcastEpisodes().unnewAndUpdateNewEpisodesCount(bundle.episode.origin, bundle.episode.id)
                }
                R.string.car_result_marked_played
            }
            else -> return@future SessionResult(SessionError.ERROR_NOT_SUPPORTED)
        }
        notifyCarListsChanged(session, bundle.episode.origin)
        SessionResult(
            SessionResult.RESULT_SUCCESS,
            Bundle().apply {
                putString(CarKeys.ACTION_RESULT_MESSAGE, service.getString(message))
                putBoolean(CarKeys.ACTION_RESULT_REFRESH_ITEM, true)
            }
        )
    }

    /** Asks the car to reload the lists an episode change can show up in. Main thread. */
    fun notifyCarListsChanged(session: MediaSession, origin: String? = null) {
        val library = session as? MediaLibrarySession ?: return
        val parents = mutableListOf(
            CarIds.TAB_UP_NEXT, CarIds.TAB_NEW, CarIds.TAB_DOWNLOADS, CarIds.LEGACY_CONTINUE
        )
        if (origin != null) parents += CarIds.podcast(origin)
        for (id in parents) library.notifyChildrenChanged(id, 1, null)
    }

    override fun onPlayerCommandRequest(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        playerCommand: Int
    ): Int {
        if (playerCommand == Player.COMMAND_SEEK_TO_PREVIOUS && service.headsetPrevAction == HeadsetPrevAction.SKIP_BACK) {
            session.player.seekBack()
            return SessionResult.RESULT_INFO_SKIPPED
        } else if (playerCommand == Player.COMMAND_SEEK_TO_NEXT) {
            when (service.headsetNextAction) {
                HeadsetNextAction.SKIP_FORWARD -> {
                    session.player.seekForward()
                    return SessionResult.RESULT_INFO_SKIPPED
                }
                HeadsetNextAction.NEXT_CHAPTER -> {
                    if (!service.jumpToNextChapter()) {
                        session.player.seekForward()
                    }
                    return SessionResult.RESULT_INFO_SKIPPED
                }
                HeadsetNextAction.NEXT_EPISODE -> Unit
            }
        }

        return super.onPlayerCommandRequest(session, controller, playerCommand)
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        return scope.future(Dispatchers.IO) {
            resumptionItems() ?: throw UnsupportedOperationException("No episode to resume")
        }
    }

    /** The last played episode, then Up Next. Null when there's nothing. */
    private suspend fun resumptionItems(): MediaSession.MediaItemsWithStartPosition? {
        val queued = service.db.playbackQueue().getQueueSync()
        val lastHistory = service.db.podcastHistory().getLast().first()
        val lastId = lastHistory?.episode?.id ?: queued.firstOrNull()?.episodeId

        val items = mutableListOf<MediaItem>()
        var startPositionMs = 0L

        if (lastId != null) {
            val bundle = service.db.podcastEpisodes().getSyncNullable(lastId)
            if (bundle != null) {
                startPositionMs = ((bundle.playState?.state ?: 0) * 1000L).coerceAtLeast(0L)
                items.add(bundle.createMediaItem(service))
            }
        }

        for (q in queued) {
            if (q.episodeId == lastId) continue
            val bundle = service.db.podcastEpisodes().getSyncNullable(q.episodeId) ?: continue
            items.add(bundle.createMediaItem(service))
        }

        if (items.isEmpty()) return null
        return MediaSession.MediaItemsWithStartPosition(items, 0, startPositionMs)
    }

    // ---- browse tree (Android Auto and other media browsers) ----

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        params?.extras?.let { hints ->
            if (app.podiumpodcasts.podium.BuildConfig.DEV_TOOLS) {
                Log.i("HushCastCar", "Root hints from ${browser.packageName}: " +
                    hints.keySet().joinToString { "$it=${hints.get(it)}" })
            }
            if (hints.containsKey(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT)) {
                service.carLibrary.rootLimit = hints.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, 4)
            }
        }
        val extras = Bundle().apply {
            // Podcasts as tiles, episodes as rows.
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            )
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            )
        }

        return Futures.immediateFuture(
            LibraryResult.ofItem(
                service.carItems.root(),
                MediaLibraryService.LibraryParams.Builder()
                    .setExtras(extras)
                    .build()
            )
        )
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return scope.future(Dispatchers.IO) {
            val items = service.carLibrary.children(parentId)
            LibraryResult.ofItemList(ImmutableList.copyOf(CarSections.page(items, page, pageSize)), params)
        }
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {
        return scope.future(Dispatchers.IO) {
            service.carLibrary.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }
    }

    // ---- search (F13) ----

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        return scope.future {
            val results = withContext(Dispatchers.IO) { service.carLibrary.search(query) }
            lastSearch = query to results
            session.notifySearchResultChanged(browser, query, results.size, params)
            LibraryResult.ofVoid()
        }
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return scope.future(Dispatchers.IO) {
            val results = lastSearch?.takeIf { it.first == query }?.second
                ?: service.carLibrary.search(query)
            LibraryResult.ofItemList(ImmutableList.copyOf(CarSections.page(results, page, pageSize)), params)
        }
    }

    // ---- playing from the car: rows, voice (F14) ----

    /** A request with no id and no URI: a voice search ("play Serial"), possibly empty ("play HushCast"). */
    private fun isVoiceRequest(items: List<MediaItem>): Boolean {
        val item = items.singleOrNull() ?: return false
        return item.localConfiguration == null &&
            item.mediaId.isEmpty() &&
            item.requestMetadata.mediaUri == null
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>
    ): ListenableFuture<List<MediaItem>> {
        return scope.future(Dispatchers.IO) {
            if (isVoiceRequest(mediaItems)) {
                val query = mediaItems.single().requestMetadata.searchQuery.orEmpty()
                val bundle = resolveVoiceOrNotify(query) ?: throw NoSuchElementException(query)
                return@future listOf(bundle.createMediaItem(service))
            }
            mediaItems.mapNotNull { item ->
                if (item.localConfiguration != null) return@mapNotNull item
                if (!item.mediaId.startsWith(CarIds.EPISODE_PREFIX)) return@mapNotNull item

                val episodeId = item.mediaId.removePrefix(CarIds.EPISODE_PREFIX)
                service.db.podcastEpisodes().getNullable(episodeId).first()?.createMediaItem(service)
            }
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        return scope.future(Dispatchers.IO) {
            if (isVoiceRequest(mediaItems)) {
                val query = mediaItems.single().requestMetadata.searchQuery.orEmpty()
                if (query.isBlank()) {
                    return@future resumptionItems() ?: throw NoSuchElementException("Nothing to resume")
                }
                val bundle = resolveVoiceOrNotify(query) ?: throw NoSuchElementException(query)
                return@future playKeepingQueue(mediaSession, bundle)
            }

            // One episode the car asked for by id: play it the way the phone does (P3), so Up Next survives.
            val single = mediaItems.singleOrNull()
            if (single != null && single.localConfiguration == null && single.mediaId.startsWith(CarIds.EPISODE_PREFIX)) {
                val bundle = service.db.podcastEpisodes().getSyncNullable(single.mediaId.removePrefix(CarIds.EPISODE_PREFIX))
                if (bundle != null) return@future playKeepingQueue(mediaSession, bundle)
            }

            val resolved = mediaItems.mapNotNull { item ->
                if (item.localConfiguration != null) return@mapNotNull item
                if (!item.mediaId.startsWith(CarIds.EPISODE_PREFIX)) return@mapNotNull item

                val episodeId = item.mediaId.removePrefix(CarIds.EPISODE_PREFIX)
                service.db.podcastEpisodes().getNullable(episodeId).first()?.createMediaItem(service)
            }
            MediaSession.MediaItemsWithStartPosition(
                resolved,
                startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0)),
                startPositionMs
            )
        }
    }

    /** Debug builds: the voice path without Assistant (CarDebugReceiver). */
    suspend fun voiceItems(session: MediaSession, query: String): MediaSession.MediaItemsWithStartPosition? =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) resumptionItems()
            else resolveVoiceOrNotify(query)?.let { playKeepingQueue(session, it) }
        }

    private suspend fun resolveVoiceOrNotify(query: String): PodcastEpisodeBundle? {
        val bundle = service.carLibrary.resolveVoice(query)
        if (bundle == null) {
            Log.i("HushCastCar", "Voice: nothing matches \"$query\"")
            withContext(Dispatchers.Main) { service.carCommands?.showNotFound(query) }
        }
        return bundle
    }

    /**
     * The new playlist when the car starts [bundle]: the episode first, then the episode that was
     * playing (unless finished, kept at its position), then the rest of Up Next. Matches the
     * phone's play(); the car can only replace the whole playlist, so it's rebuilt here.
     */
    private suspend fun playKeepingQueue(
        session: MediaSession,
        bundle: PodcastEpisodeBundle
    ): MediaSession.MediaItemsWithStartPosition {
        data class Snapshot(val currentId: String?, val positionMs: Long, val finished: Boolean, val upNext: List<String>)

        val snap = withContext(Dispatchers.Main) {
            val p = session.player
            val idx = p.currentMediaItemIndex
            val upNext = if (p.mediaItemCount > 0 && idx >= 0) {
                (idx + 1 until p.mediaItemCount).mapNotNull { p.getMediaItemAt(it).getEpisodeId() }
            } else emptyList()
            val dur = p.duration
            val pos = p.currentPosition
            Snapshot(
                currentId = p.currentMediaItem?.getEpisodeId(),
                positionMs = pos,
                finished = p.playbackState == Player.STATE_ENDED || (dur > 1000L && dur - pos < 90_000L),
                upNext = upNext
            )
        }
        // No player playlist yet (fresh start): use the saved Up Next.
        val upNext = if (snap.currentId == null) {
            service.db.playbackQueue().getQueueSync().map { it.episodeId }
        } else snap.upNext

        val id = bundle.episode.id
        if (bundle.episode.new) {
            service.db.podcastEpisodes().unnewAndUpdateNewEpisodesCount(bundle.episode.origin, id)
        }

        val items = ArrayList<MediaItem>()
        var startPositionMs = ((bundle.playState?.state ?: 0) * 1000L).coerceAtLeast(0L)
        val keepOld = snap.currentId != null && snap.currentId != id && !snap.finished
        if (snap.currentId == id) {
            // The car tapped what's already playing: carry on from here, not from the last save.
            startPositionMs = snap.positionMs
            items += bundle.createMediaItem(service).withResumeAt(snap.positionMs)
        } else {
            items += bundle.createMediaItem(service)
        }

        if (keepOld) {
            val oldId = snap.currentId!!
            if (snap.positionMs > 0L) {
                service.db.podcastEpisodePlayStates().saveState(oldId, (snap.positionMs / 1000L).toInt().coerceAtLeast(1))
            }
            service.db.podcastEpisodes().getSyncNullable(oldId)?.createMediaItem(service)?.let { old ->
                items += old.withResumeAt(snap.positionMs)
            }
        }

        val skip = setOfNotNull(id, if (keepOld) snap.currentId else null)
        val rest = upNext.filter { it !in skip }.distinct()
        if (rest.isNotEmpty()) {
            val byId = service.db.podcastEpisodes().getByIds(rest).associateBy { it.episode.id }
            rest.mapNotNullTo(items) { byId[it]?.createMediaItem(service) }
        }

        service.db.playbackQueue().replaceQueue(items.drop(1).mapNotNull { it.getEpisodeId() })
        return MediaSession.MediaItemsWithStartPosition(items, 0, startPositionMs)
    }

    private fun MediaItem.withResumeAt(positionMs: Long): MediaItem {
        val extras = Bundle(mediaMetadata.extras ?: Bundle()).apply {
            putLong(MediaMetadataExtra.RESUME_AT.name, positionMs)
        }
        return buildUpon()
            .setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }
}
