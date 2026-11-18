package app.podiumpodcasts.podium.ui.vm

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import app.podiumpodcasts.podium.SettingsRepository
import app.podiumpodcasts.podium.api.db.AppDatabase
import app.podiumpodcasts.podium.api.db.model.MediaMetadataExtra
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeDownloadState
import app.podiumpodcasts.podium.background.COMMAND_CYCLE_SPEED
import app.podiumpodcasts.podium.background.COMMAND_SET_SEEK_BACK_INCREMENT
import app.podiumpodcasts.podium.background.COMMAND_SET_SEEK_FORWARD_INCREMENT
import app.podiumpodcasts.podium.background.COMMAND_SLEEP_TIMER_SET
import app.podiumpodcasts.podium.background.EXTRA_SLEEP_TIMER_TRIGGER
import app.podiumpodcasts.podium.manager.DatabaseManager
import app.podiumpodcasts.podium.manager.DownloadManager
import app.podiumpodcasts.podium.utils.getEpisodeId
import app.podiumpodcasts.podium.utils.getImageSeedColor
import app.podiumpodcasts.podium.utils.getOrigin
import app.podiumpodcasts.podium.utils.isDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

interface SourceTypeState {
    object Unknown : SourceTypeState
    object Streaming : SourceTypeState
    object Downloaded : SourceTypeState
}

class MediaPlayerViewModel(application: Application) : AndroidViewModel(application) {

    val db: AppDatabase by lazy { DatabaseManager.build(application) }

    private var mediaController: MediaController? = null

    var isPlayerVisible by mutableStateOf(false)
    var openPlayerSheetNonce by mutableLongStateOf(0L)

    var isPlaying by mutableStateOf(false)
    var isLoading by mutableStateOf(false)
    var playbackState by mutableIntStateOf(1)

    var seekBackIncrement by mutableLongStateOf(10000)
    var seekForwardIncrement by mutableLongStateOf(10000)

    var speed by mutableFloatStateOf(1f)

    var lastProgressUpdate by mutableLongStateOf(0L)
    var currentDuration by mutableLongStateOf(1L)
    var currentPosition by mutableLongStateOf(1L)

    // unix millis when sleep timer should trigger
    var sleepTimerTrigger by mutableStateOf<Long?>(null)

    var metadataOrigin by mutableStateOf<String?>(null)
    var metadataEpisodeId by mutableStateOf<String?>(null)
    var metadataImageSeedColor by mutableStateOf<Int?>(null)

    var mediaMetadata by mutableStateOf<MediaMetadata?>(null)
    var mediaSourceType by mutableStateOf<SourceTypeState>(SourceTypeState.Unknown)

    val queue = mutableStateListOf<String>()
    val queueIndex = mutableStateListOf<Int>()
    val queueItems = mutableStateListOf<MediaItem>()
    val queueSet: Set<String> get() = queue.toSet()

    private var hasRestoredQueue = false
    private var isRestoringQueue = false
    private var appContext: Context? = application.applicationContext
    private var persistQueueJob: Job? = null

    private fun saveLastPlayingEpisodeId(episodeId: String?) {
        val ctx = appContext ?: return
        if (episodeId.isNullOrBlank()) return
        ctx.getSharedPreferences("hushcast_playback_state", Context.MODE_PRIVATE)
            .edit()
            .putString("last_playing_episode_id", episodeId)
            .apply()
    }

    private fun loadLastPlayingEpisodeId(context: Context): String? {
        return context.getSharedPreferences("hushcast_playback_state", Context.MODE_PRIVATE)
            .getString("last_playing_episode_id", null)
    }

    val canResumeCurrentEpisode: Boolean
        get() {
            val mediaItem = mediaController?.currentMediaItem ?: return false
            return mediaItem.localConfiguration?.uri != null
        }

    fun play(
        context: Context,
        bundle: PodcastEpisodeBundle
    ) {
        appContext = context.applicationContext
        saveLastPlayingEpisodeId(bundle.episode.id)

        if (bundle.episode.new) {
            viewModelScope.launch(Dispatchers.IO) {
                db.podcastEpisodes().unnewAndUpdateNewEpisodesCount(
                    origin = bundle.episode.origin,
                    episodeId = bundle.episode.id
                )
            }
        }

        val mediaItem = bundle.episode.createMediaItem(context, bundle.playState)
        val startPosMs = ((bundle.playState?.state ?: 0) * 1000L).coerceAtLeast(0L)

        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0) {
            controller.setMediaItem(mediaItem, startPosMs)
            controller.prepare()
            controller.play()
            syncQueue(persist = true)
            return
        }

        val currentIdx = controller.currentMediaItemIndex.takeIf { it in 0 until controller.mediaItemCount } ?: 0
        val currentId = controller.currentMediaItem?.getEpisodeId()

        // If tapping Play on the currently playing episode, just seek/play
        if (currentId == bundle.episode.id) {
            if (startPosMs > 0L) controller.seekTo(currentIdx, startPosMs)
            controller.prepare()
            controller.play()
            return
        }

        // Remove if already present later in queue so we don't duplicate
        for (i in controller.mediaItemCount - 1 downTo 0) {
            if (i != currentIdx && controller.getMediaItemAt(i).getEpisodeId() == bundle.episode.id) {
                controller.removeMediaItem(i)
            }
        }

        // Save old episode position before switching; remove old episode if finished (P3)
        val oldDur = controller.duration
        val oldPos = controller.currentPosition
        val oldFinished = controller.playbackState == Player.STATE_ENDED ||
            (oldDur > 1000L && (oldDur - oldPos) < 90_000L)

        if (oldIdNotBlank(currentId) && !oldFinished && oldPos > 0L) {
            val oldSec = (oldPos / 1000L).toInt().coerceAtLeast(1)
            viewModelScope.launch(Dispatchers.IO) {
                db.podcastEpisodePlayStates().saveState(currentId!!, oldSec)
            }
        }

        val insertIdx = controller.currentMediaItemIndex.coerceIn(0, controller.mediaItemCount)
        if (oldFinished) {
            controller.replaceMediaItem(insertIdx, mediaItem)
        } else {
            // Preserve current position on the shifted item so resuming from Up Next doesn't restart at 0:00
            if (oldPos > 0L && insertIdx < controller.mediaItemCount) {
                val oldItem = controller.getMediaItemAt(insertIdx)
                val updatedExtras = android.os.Bundle(oldItem.mediaMetadata.extras ?: android.os.Bundle()).apply {
                    putLong(app.podiumpodcasts.podium.api.db.model.MediaMetadataExtra.RESUME_AT.name, oldPos)
                }
                val updatedOldItem = oldItem.buildUpon()
                    .setMediaMetadata(oldItem.mediaMetadata.buildUpon().setExtras(updatedExtras).build())
                    .build()
                controller.replaceMediaItem(insertIdx, updatedOldItem)
            }
            // Insert at currentMediaItemIndex so the previous episode shifts to Up Next #1 (P3)
            controller.addMediaItem(insertIdx, mediaItem)
        }
        controller.seekTo(insertIdx, startPosMs)
        controller.prepare()
        controller.play()
        syncQueue(persist = true)
    }

    private fun oldIdNotBlank(id: String?): Boolean = !id.isNullOrBlank()

    private fun loadAsFirstAndOpenPlayer(
        context: Context,
        bundle: PodcastEpisodeBundle
    ) {
        val controller = mediaController ?: return
        appContext = context.applicationContext
        saveLastPlayingEpisodeId(bundle.episode.id)

        if (bundle.episode.new) {
            viewModelScope.launch(Dispatchers.IO) {
                db.podcastEpisodes().unnewAndUpdateNewEpisodesCount(
                    origin = bundle.episode.origin,
                    episodeId = bundle.episode.id
                )
            }
        }

        val mediaItem = bundle.episode.createMediaItem(context, bundle.playState)
        val startPosMs = ((bundle.playState?.state ?: 0) * 1000L).coerceAtLeast(0L)
        controller.setMediaItem(mediaItem, startPosMs)
        controller.prepare()
        syncQueue(persist = true)
        openPlayerSheetNonce = System.currentTimeMillis().coerceAtLeast(openPlayerSheetNonce + 1L)
        autoDownloadIfEnabled(context, bundle)
    }

    fun enqueue(
        context: Context,
        bundle: PodcastEpisodeBundle
    ) {
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0) {
            loadAsFirstAndOpenPlayer(context, bundle)
            return
        }
        if (bundle.episode.id == metadataEpisodeId || queue.contains(bundle.episode.id)) return
        val mediaItem = bundle.episode.createMediaItem(context, bundle.playState)

        controller.addMediaItem(mediaItem)
        syncQueue(persist = true)
        autoDownloadIfEnabled(context, bundle)
    }

    fun playNext(
        context: Context,
        bundle: PodcastEpisodeBundle
    ) {
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0) {
            loadAsFirstAndOpenPlayer(context, bundle)
            return
        }
        if (bundle.episode.id == metadataEpisodeId || queue.contains(bundle.episode.id)) return
        val mediaItem = bundle.episode.createMediaItem(context, bundle.playState)
        val insertIndex = (controller.currentMediaItemIndex + 1).coerceIn(0, controller.mediaItemCount)

        controller.addMediaItem(insertIndex, mediaItem)
        syncQueue(persist = true)
        autoDownloadIfEnabled(context, bundle)
    }

    fun playNextMediaItem(mediaItem: MediaItem) {
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0) {
            controller.setMediaItem(mediaItem)
            controller.prepare()
            syncQueue(persist = true)
            openPlayerSheetNonce = System.currentTimeMillis().coerceAtLeast(openPlayerSheetNonce + 1L)
            return
        }
        val insertIndex = (controller.currentMediaItemIndex + 1).coerceIn(0, controller.mediaItemCount)
        controller.addMediaItem(insertIndex, mediaItem)
        syncQueue(persist = true)
    }

    private fun autoDownloadIfEnabled(
        context: Context,
        bundle: PodcastEpisodeBundle
    ) {
        val appCtx = context.applicationContext
        val isDownloaded = bundle.download?.state == PodcastEpisodeDownloadState.DOWNLOADED.value

        viewModelScope.launch(Dispatchers.IO) {
            val repo = SettingsRepository(appCtx)
            val protectionActive = repo.hushCast.wordMutingEnabled.first() ||
                repo.hushCast.adPolicyMode.first() != app.hushcast.automute.dsp.AdPolicyMode.OFF
            val queueAutoDl = repo.behavior.autoDownloadQueue.first()
            val hushAutoDl = repo.hushCast.autoDownloadUpNext.first() && protectionActive

            if (!isDownloaded && (queueAutoDl || hushAutoDl)) {
                DownloadManager.downloadEpisode(
                    context = appCtx,
                    db = db,
                    episodeId = bundle.episode.id,
                    auto = true
                )
                return@launch
            }

            if (!protectionActive) return@launch
            val autoMethod = app.hushcast.automute.engine.HushCastPrefs.getAutoDownloadMethod(appCtx)
            if (autoMethod == app.hushcast.automute.engine.DownloadAnalysisMethod.SKIP_ANALYSIS) return@launch

            val autoMuteDao = app.hushcast.automute.storage.AutoMuteDatabase.getInstance(appCtx).dao()
            val existingBadge = autoMuteDao.getStatus(bundle.episode.id)?.badge
            if (existingBadge == app.hushcast.automute.dsp.ReadinessBadge.READY.name ||
                existingBadge == app.hushcast.automute.dsp.ReadinessBadge.ANALYZING.name
            ) {
                return@launch
            }

            val localPath = if (isDownloaded) {
                bundle.episode.craftDownloadFile(appCtx).takeIf { it.exists() }?.absolutePath
            } else {
                null
            }
            val desktopEnabled = app.hushcast.automute.engine.HushCastPrefs.isDesktopWorkerEnabled(appCtx)
            if (localPath != null) {
                app.hushcast.automute.worker.EpisodeAnalysisWorker.enqueueAnalyzeNow(
                    context = appCtx,
                    episodeId = bundle.episode.id,
                    audioPath = localPath,
                    actionMode = repo.hushCast.muteActionMode.first(),
                    episodeTitle = bundle.episode.title
                )
            } else if (desktopEnabled) {
                DownloadManager.downloadEpisode(
                    context = appCtx,
                    db = db,
                    episodeId = bundle.episode.id,
                    auto = true
                )
            }
        }
    }

    fun playFromQueue(queuePos: Int) {
        val controller = mediaController ?: return
        if (queuePos !in queueIndex.indices) return

        val playerIndex = queueIndex[queuePos]
        if (playerIndex !in 0 until controller.mediaItemCount) return

        val currentIndex = controller.currentMediaItemIndex.takeIf { it in 0 until controller.mediaItemCount } ?: 0
        if (playerIndex != currentIndex) {
            controller.moveMediaItem(playerIndex, currentIndex)
        }
        controller.seekToDefaultPosition(currentIndex)
        controller.prepare()
        controller.play()
        syncQueue(persist = true)
    }

    fun clearQueue() {
        val controller = mediaController ?: return
        val start = (controller.currentMediaItemIndex + 1).coerceAtLeast(0)
        if (start < controller.mediaItemCount) {
            controller.removeMediaItems(start, controller.mediaItemCount)
            syncQueue(persist = true)
        }
    }

    fun moveToTopOfQueue(queuePos: Int) {
        if (queuePos <= 0 || queuePos !in queueIndex.indices) return
        moveQueueItem(queuePos, 0)
    }

    fun dequeue(episodeId: String) {
        mediaController?.let { mediaController ->
            for (index in 0 until mediaController.mediaItemCount) {
                if (index == mediaController.currentMediaItemIndex) continue

                val mediaItem = mediaController.getMediaItemAt(index)
                val mediaItemEpisodeId =
                    mediaItem.mediaMetadata.extras?.getString(MediaMetadataExtra.EPISODE_ID.name)

                if (episodeId != mediaItemEpisodeId) continue

                mediaController.removeMediaItem(index)
                break
            }
        }

        syncQueue(persist = true)
    }

    fun removeEpisodeFromPlaybackAndQueue(episodeId: String) {
        val controller = mediaController
        if (controller != null) {
            for (index in controller.mediaItemCount - 1 downTo 0) {
                val mediaItem = controller.getMediaItemAt(index)
                val mediaItemEpisodeId =
                    mediaItem.mediaMetadata.extras?.getString(MediaMetadataExtra.EPISODE_ID.name)
                if (episodeId != mediaItemEpisodeId) continue

                val currentIdx = controller.currentMediaItemIndex
                if (index == currentIdx) {
                    if (controller.mediaItemCount <= 1) {
                        controller.stop()
                        controller.clearMediaItems()
                        break
                    } else {
                        if (index + 1 < controller.mediaItemCount) {
                            controller.seekToNextMediaItem()
                        } else {
                            controller.seekToPreviousMediaItem()
                        }
                        controller.removeMediaItem(index)
                    }
                } else {
                    controller.removeMediaItem(index)
                }
            }
            syncQueue(persist = true)
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                db.playbackQueue().remove(episodeId)
            }
        }
    }

    fun removeFromQueue(queuePos: Int) {
        val controller = mediaController ?: return
        if (queuePos !in queueIndex.indices) return
        val playerIndex = queueIndex[queuePos]
        if (playerIndex in 0 until controller.mediaItemCount && playerIndex != controller.currentMediaItemIndex) {
            controller.removeMediaItem(playerIndex)
            syncQueue(persist = true)
        }
    }

    fun moveQueueItem(fromQueuePos: Int, toQueuePos: Int) {
        val controller = mediaController ?: return
        if (fromQueuePos == toQueuePos) return
        if (fromQueuePos !in queueIndex.indices || toQueuePos !in queueIndex.indices) return

        val fromPlayerIndex = queueIndex[fromQueuePos]
        val toPlayerIndex = queueIndex[toQueuePos]
        controller.moveMediaItem(fromPlayerIndex, toPlayerIndex)
        syncQueue(persist = true, debouncePersist = true)
    }

    fun play() {
        mediaController?.play()
    }

    fun pause() {
        mediaController?.pause()
    }

    fun seek(position: Long) {
        val controller = mediaController ?: return
        val clamped = if (currentDuration > 0L) {
            position.coerceIn(0L, currentDuration)
        } else {
            position.coerceAtLeast(0L)
        }
        currentPosition = clamped
        lastProgressUpdate = System.currentTimeMillis()
        controller.seekTo(clamped)
    }

    fun seekBack() {
        mediaController?.seekBack()
    }

    fun seekForward() {
        mediaController?.seekForward()
    }

    fun setPlaybackSpeed(speed: Float) {
        mediaController?.setPlaybackSpeed(speed)
    }

    fun cyclePlaybackSpeed() {
        mediaController?.sendCustomCommand(
            SessionCommand(COMMAND_CYCLE_SPEED, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }

    fun setSleepTimer(sleepTimer: Long?) {
        sleepTimerTrigger = sleepTimer?.takeIf { it != 0L }
        mediaController?.sendCustomCommand(
            SessionCommand(COMMAND_SLEEP_TIMER_SET, Bundle().apply {
                putLong("trigger", sleepTimer ?: 0L)
            }),
            Bundle.EMPTY
        )
    }

    fun updateSeekBackIncrement(increment: Long) {
        mediaController?.sendCustomCommand(
            SessionCommand(COMMAND_SET_SEEK_BACK_INCREMENT, Bundle().apply {
                putLong("increment", increment)
            }),
            Bundle.EMPTY
        )
    }

    fun updateSeekForwardIncrement(increment: Long) {
        mediaController?.sendCustomCommand(
            SessionCommand(COMMAND_SET_SEEK_FORWARD_INCREMENT, Bundle().apply {
                putLong("increment", increment)
            }),
            Bundle.EMPTY
        )
    }

    @Composable
    fun getProgressState(): MutableState<Float> {
        val progressState = remember { mutableFloatStateOf(0f) }
        val lifecycleOwner = LocalLifecycleOwner.current

        LaunchedEffect(lifecycleOwner, lastProgressUpdate, isPlaying, currentPosition, currentDuration, speed) {
            val dur = currentDuration.coerceAtLeast(1L).toFloat()
            progressState.value = (currentPosition.toFloat() / dur).coerceIn(0f, 1f)
            if (!isPlaying) return@LaunchedEffect

            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val difference = (System.currentTimeMillis() - lastProgressUpdate).coerceAtLeast(0L)
                    val realCurrentPosition = currentPosition + (difference * speed)
                    progressState.value = (realCurrentPosition.toFloat() / dur).coerceIn(0f, 1f)
                    delay(100)
                }
            }
        }

        return progressState
    }

    @Composable
    fun rememberLivePositionMs(intervalMs: Long = 500L): Long {
        val posState = remember { mutableLongStateOf(currentPosition) }
        val lifecycleOwner = LocalLifecycleOwner.current

        LaunchedEffect(lifecycleOwner, lastProgressUpdate, isPlaying, currentPosition, currentDuration, speed) {
            val dur = currentDuration.coerceAtLeast(1L)
            posState.longValue = currentPosition.coerceIn(0L, dur)
            if (!isPlaying) return@LaunchedEffect

            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val difference = (System.currentTimeMillis() - lastProgressUpdate).coerceAtLeast(0L)
                    val realCurrentPosition = (currentPosition + (difference * speed).toLong()).coerceIn(0L, dur)
                    posState.longValue = realCurrentPosition
                    delay(intervalMs)
                }
            }
        }

        return posState.longValue
    }

    fun livePositionNowMs(): Long {
        val dur = currentDuration.coerceAtLeast(1L)
        if (!isPlaying) return currentPosition.coerceIn(0L, dur)
        val difference = (System.currentTimeMillis() - lastProgressUpdate).coerceAtLeast(0L)
        return (currentPosition + (difference * speed).toLong()).coerceIn(0L, dur)
    }

    /**
     * Returns sleep timer state (in minutes)
     */
    @Composable
    fun getSleepTimerState(): MutableState<Int?> {
        val sleepTimerState = remember { mutableStateOf<Int?>(null) }
        val lifecycleOwner = LocalLifecycleOwner.current

        LaunchedEffect(lifecycleOwner, sleepTimerTrigger) {
            val trigger = sleepTimerTrigger
            if (trigger == null) {
                sleepTimerState.value = null
                return@LaunchedEffect
            }
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val diff = trigger - System.currentTimeMillis()
                    sleepTimerState.value = ((diff / 1000L) / 60f).roundToInt().coerceAtLeast(0)
                    delay(30_000L)
                }
            }
        }

        return sleepTimerState
    }

    fun syncQueue(persist: Boolean = hasRestoredQueue, debouncePersist: Boolean = false) {
        val controller = mediaController ?: return
        queue.clear()
        queueIndex.clear()
        queueItems.clear()

        repeat(controller.mediaItemCount) { index ->
            if (controller.currentMediaItemIndex >= index) return@repeat

            val mediaItem = controller.getMediaItemAt(index)
            mediaItem.mediaMetadata.extras?.getString(MediaMetadataExtra.EPISODE_ID.name)
                ?.let { episodeId ->
                    queue.add(episodeId)
                    queueIndex.add(index)
                    queueItems.add(mediaItem)
                }
        }

        if (persist) {
            val snapshot = queue.toList()
            persistQueueJob?.cancel()
            persistQueueJob = viewModelScope.launch(Dispatchers.IO) {
                if (debouncePersist) delay(250L)
                db.playbackQueue().replaceQueue(snapshot)
            }
        }
    }

    fun restoreQueueIfNeeded(context: Context) {
        appContext = context.applicationContext
        if (isRestoringQueue) return
        val controller = mediaController ?: return
        if (hasRestoredQueue && controller.mediaItemCount > 0) return
        hasRestoredQueue = true

        val currentEpisodeId = controller.currentMediaItem
            ?.mediaMetadata?.extras?.getString(MediaMetadataExtra.EPISODE_ID.name)
        if (currentEpisodeId != null) {
            saveLastPlayingEpisodeId(currentEpisodeId)
        }

        if (controller.mediaItemCount > 1) {
            syncQueue(persist = true)
            return
        }

        val appCtx = context.applicationContext
        val lastEpisodeId = currentEpisodeId ?: loadLastPlayingEpisodeId(appCtx)

        isRestoringQueue = true
        viewModelScope.launch {
            try {
                val (lastMediaItemAndResume, queueMediaItems) = withContext(Dispatchers.IO) {
                    val saved = db.playbackQueue().getQueueSync()
                    val allIds = buildList {
                        if (lastEpisodeId != null) add(lastEpisodeId)
                        saved.forEach { if (it.episodeId != lastEpisodeId) add(it.episodeId) }
                    }
                    val bundlesById = if (allIds.isEmpty()) emptyMap() else {
                        db.podcastEpisodes().getByIds(allIds).associateBy { it.episode.id }
                    }
                    val lastPair = lastEpisodeId?.let { id ->
                        bundlesById[id]?.let { b ->
                            val resumeMs = ((b.playState?.state ?: 0) * 1000L).coerceAtLeast(0L)
                            b.episode.createMediaItem(appCtx, b.playState) to resumeMs
                        }
                    }
                    val queuedItems = saved.mapNotNull { item ->
                        if (item.episodeId == lastEpisodeId) null
                        else bundlesById[item.episodeId]?.let { b ->
                            b.episode.createMediaItem(appCtx, b.playState)
                        }
                    }
                    lastPair to queuedItems
                }
                if (controller != mediaController || controller.mediaItemCount > 1) return@launch

                if (controller.mediaItemCount == 0 && lastMediaItemAndResume != null) {
                    val (lastItem, resumeMs) = lastMediaItemAndResume
                    controller.setMediaItem(lastItem, resumeMs)
                }

                if (queueMediaItems.isNotEmpty()) {
                    controller.addMediaItems(queueMediaItems)
                }

                syncQueue(persist = false)
            } finally {
                isRestoringQueue = false
            }
        }
    }

    @Composable
    fun rememberMediaItemAt(index: Int): MediaItem? {
        val controller = mediaController ?: return null
        if (index !in 0 until controller.mediaItemCount) return null
        return controller.getMediaItemAt(index)
    }

    val controllerListener = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            val trigger = extras.getLong(EXTRA_SLEEP_TIMER_TRIGGER, 0L)
            sleepTimerTrigger = trigger.takeIf { it != 0L }
        }
    }

    val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@MediaPlayerViewModel.isPlaying = isPlaying
            super.onIsPlayingChanged(isPlaying)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            this@MediaPlayerViewModel.currentDuration = player.duration.coerceAtLeast(1L)
            this@MediaPlayerViewModel.currentPosition = player.currentPosition
            this@MediaPlayerViewModel.lastProgressUpdate = System.currentTimeMillis()
            super.onEvents(player, events)
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            this@MediaPlayerViewModel.speed = playbackParameters.speed
            super.onPlaybackParametersChanged(playbackParameters)
        }

        override fun onIsLoadingChanged(isLoading: Boolean) {
            this@MediaPlayerViewModel.isLoading = isLoading
            super.onIsLoadingChanged(isLoading)
        }

        override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) {
            this@MediaPlayerViewModel.seekBackIncrement = seekBackIncrementMs
            super.onSeekBackIncrementChanged(seekBackIncrementMs)
        }

        override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) {
            this@MediaPlayerViewModel.seekForwardIncrement = seekForwardIncrementMs
            super.onSeekForwardIncrementChanged(seekForwardIncrementMs)
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            this@MediaPlayerViewModel.mediaMetadata = mediaMetadata
            this@MediaPlayerViewModel.metadataOrigin = mediaMetadata.getOrigin()
            this@MediaPlayerViewModel.metadataEpisodeId = mediaMetadata.getEpisodeId()
            this@MediaPlayerViewModel.metadataImageSeedColor = mediaMetadata.getImageSeedColor()
            saveLastPlayingEpisodeId(this@MediaPlayerViewModel.metadataEpisodeId)

            this@MediaPlayerViewModel.mediaSourceType = if (mediaMetadata.isDownload()) {
                SourceTypeState.Downloaded
            } else {
                SourceTypeState.Streaming
            }

            syncQueue()
            super.onMediaMetadataChanged(mediaMetadata)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            this@MediaPlayerViewModel.playbackState = playbackState
            this@MediaPlayerViewModel.isPlayerVisible = playbackState != Player.STATE_IDLE
            super.onPlaybackStateChanged(playbackState)
        }
    }

    fun registerMediaController(mediaController: MediaController) {
        this@MediaPlayerViewModel.mediaController = mediaController

        this@MediaPlayerViewModel.isPlaying = mediaController.isPlaying
        this@MediaPlayerViewModel.currentDuration = mediaController.duration.coerceAtLeast(1L)
        this@MediaPlayerViewModel.currentPosition = mediaController.currentPosition
        this@MediaPlayerViewModel.lastProgressUpdate = System.currentTimeMillis()

        this@MediaPlayerViewModel.seekBackIncrement = mediaController.seekBackIncrement
        this@MediaPlayerViewModel.seekForwardIncrement = mediaController.seekForwardIncrement
        this@MediaPlayerViewModel.speed = mediaController.playbackParameters.speed
        this@MediaPlayerViewModel.isLoading = mediaController.isLoading

        this@MediaPlayerViewModel.mediaMetadata = mediaController.mediaMetadata
        mediaController.currentMediaItem?.let { mediaItem ->
            this@MediaPlayerViewModel.metadataOrigin = mediaItem.getOrigin()
            this@MediaPlayerViewModel.metadataEpisodeId = mediaItem.getEpisodeId()
            this@MediaPlayerViewModel.metadataImageSeedColor = mediaItem.getImageSeedColor()
            this@MediaPlayerViewModel.mediaSourceType = if (mediaItem.isDownload()) {
                SourceTypeState.Downloaded
            } else {
                SourceTypeState.Streaming
            }
        }

        val initialTimer = mediaController.sessionExtras.getLong(EXTRA_SLEEP_TIMER_TRIGGER, 0L)
        this@MediaPlayerViewModel.sleepTimerTrigger = initialTimer.takeIf { it != 0L }

        this@MediaPlayerViewModel.playbackState = mediaController.playbackState
        this@MediaPlayerViewModel.isPlayerVisible = mediaController.playbackState != Player.STATE_IDLE

        syncQueue()

        mediaController.addListener(listener)

        if (mediaController.mediaItemCount == 0) {
            appContext?.let { restoreQueueIfNeeded(it) }
        }
    }

    fun unregisterMediaController() {
        mediaController?.removeListener(listener)
        mediaController = null
    }
}