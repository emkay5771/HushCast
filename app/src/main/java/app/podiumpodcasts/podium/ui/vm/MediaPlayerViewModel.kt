package app.podiumpodcasts.podium.ui.vm

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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.podiumpodcasts.podium.SettingsRepository
import app.podiumpodcasts.podium.api.db.AppDatabase
import app.podiumpodcasts.podium.api.db.model.MediaMetadataExtra
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle
import app.podiumpodcasts.podium.background.COMMAND_SET_SEEK_BACK_INCREMENT
import app.podiumpodcasts.podium.background.COMMAND_SET_SEEK_FORWARD_INCREMENT
import app.podiumpodcasts.podium.background.COMMAND_SLEEP_TIMER_GET
import app.podiumpodcasts.podium.background.COMMAND_SLEEP_TIMER_SET
import app.podiumpodcasts.podium.manager.DownloadManager
import app.podiumpodcasts.podium.utils.getEpisodeId
import app.podiumpodcasts.podium.utils.getImageSeedColor
import app.podiumpodcasts.podium.utils.getOrigin
import app.podiumpodcasts.podium.utils.isDownload
import kotlinx.coroutines.Dispatchers
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

class MediaPlayerViewModel : ViewModel() {

    lateinit var db: AppDatabase

    private var mediaController: MediaController? = null

    var isPlayerVisible by mutableStateOf(false)

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
    private var hasRestoredQueue = false

    val canResumeCurrentEpisode: Boolean
        get() {
            val mediaItem = mediaController?.currentMediaItem ?: return false
            return mediaItem.localConfiguration?.uri != null
        }

    fun play(
        context: Context,
        bundle: PodcastEpisodeBundle
    ) {
        if(bundle.episode.new) viewModelScope.launch {
            db.podcastEpisodes().unnewAndUpdateNewEpisodesCount(
                origin = bundle.episode.origin,
                episodeId = bundle.episode.id
            )
        }

        val mediaItem = bundle.episode
            .createMediaItem(context)

        mediaController?.setMediaItem(mediaItem, bundle.playState!!.state * 1000L)
        mediaController?.play()
    }

    fun enqueue(
        context: Context,
        bundle: PodcastEpisodeBundle
    ) {
        if (bundle.episode.id == metadataEpisodeId || queue.contains(bundle.episode.id)) return
        val mediaItem = bundle.createMediaItem(context)

        mediaController?.addMediaItem(mediaItem)
        syncQueue(persist = true)
        autoDownloadIfEnabled(context, bundle)
    }

    fun playNext(
        context: Context,
        bundle: PodcastEpisodeBundle
    ) {
        if (bundle.episode.id == metadataEpisodeId || queue.contains(bundle.episode.id)) return
        val controller = mediaController ?: return
        val mediaItem = bundle.createMediaItem(context)
        val insertIndex = if(controller.mediaItemCount == 0) 0
        else (controller.currentMediaItemIndex + 1).coerceIn(0, controller.mediaItemCount)

        controller.addMediaItem(insertIndex, mediaItem)
        syncQueue(persist = true)
        autoDownloadIfEnabled(context, bundle)
    }

    private fun autoDownloadIfEnabled(
        context: Context,
        bundle: PodcastEpisodeBundle
    ) {
        if(!::db.isInitialized) return
        val appCtx = context.applicationContext
        if(bundle.episode.craftDownloadFile(appCtx).exists()) return

        viewModelScope.launch(Dispatchers.IO) {
            val enabled = SettingsRepository(appCtx).behavior.autoDownloadQueue.first()
            if(enabled) {
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
        if(queuePos !in queueIndex.indices) return

        val playerIndex = queueIndex[queuePos]
        if(playerIndex !in 0 until controller.mediaItemCount) return

        val currentIndex = controller.currentMediaItemIndex.takeIf { it in 0 until controller.mediaItemCount } ?: 0
        if(playerIndex != currentIndex) {
            // Moving playerIndex -> currentIndex places the selected episode in Now Playing
            // and shifts the previously playing episode to the very top of Up Next.
            controller.moveMediaItem(playerIndex, currentIndex)
        }
        controller.seekToDefaultPosition(currentIndex)
        controller.play()
        syncQueue(persist = true)
    }

    fun clearQueue() {
        val controller = mediaController ?: return
        val start = (controller.currentMediaItemIndex + 1).coerceAtLeast(0)
        if(start < controller.mediaItemCount) {
            controller.removeMediaItems(start, controller.mediaItemCount)
            syncQueue(persist = true)
        }
    }

    fun moveToTopOfQueue(queuePos: Int) {
        if(queuePos <= 0 || queuePos !in queueIndex.indices) return
        moveQueueItem(queuePos, 0)
    }

    fun dequeue(
        episodeId: String
    ) {
        mediaController?.let { mediaController ->
            for(index in 0 until mediaController.mediaItemCount) {
                if(index == mediaController.currentMediaItemIndex) continue

                val mediaItem = mediaController.getMediaItemAt(index)
                val mediaItemEpisodeId =
                    mediaItem.mediaMetadata.extras?.getString(MediaMetadataExtra.EPISODE_ID.name)

                if(episodeId != mediaItemEpisodeId) continue

                mediaController.removeMediaItem(index)
                break
            }
        }

        syncQueue(persist = true)
    }

    fun removeFromQueue(queuePos: Int) {
        val controller = mediaController ?: return
        if(queuePos !in queueIndex.indices) return
        val playerIndex = queueIndex[queuePos]
        if(playerIndex in 0 until controller.mediaItemCount && playerIndex != controller.currentMediaItemIndex) {
            controller.removeMediaItem(playerIndex)
            syncQueue(persist = true)
        }
    }

    fun moveQueueItem(fromQueuePos: Int, toQueuePos: Int) {
        val controller = mediaController ?: return
        if(fromQueuePos == toQueuePos) return
        if(fromQueuePos !in queueIndex.indices || toQueuePos !in queueIndex.indices) return

        val fromPlayerIndex = queueIndex[fromQueuePos]
        val toPlayerIndex = queueIndex[toQueuePos]
        controller.moveMediaItem(fromPlayerIndex, toPlayerIndex)
        syncQueue(persist = true)
    }

    fun play() {
        mediaController?.play()
    }

    fun pause() {
        mediaController?.pause()
    }

    fun seek(position: Long) {
        mediaController?.seekTo(position)
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

    fun setSleepTimer(sleepTimer: Long?): Boolean {
        val result = mediaController?.sendCustomCommand(
            SessionCommand(COMMAND_SLEEP_TIMER_SET, Bundle().apply {
                putLong("trigger", sleepTimer ?: 0L)
            }),
            Bundle()
        )?.get()

        updateSleepTimer()
        return result?.resultCode == SessionResult.RESULT_SUCCESS
    }

    fun updateSleepTimer() {
        val result = mediaController?.sendCustomCommand(
            SessionCommand(COMMAND_SLEEP_TIMER_GET, Bundle()),
            Bundle()
        )?.get()

        this.sleepTimerTrigger = result?.extras?.getLong("trigger", 0L)
            .takeIf { it != 0L }
    }

    fun updateSeekBackIncrement(
        increment: Long
    ) {
        mediaController?.sendCustomCommand(
            SessionCommand(COMMAND_SET_SEEK_BACK_INCREMENT, Bundle().apply {
                putLong("increment", increment)
            }),
            Bundle()
        )
    }

    fun updateSeekForwardIncrement(
        increment: Long
    ) {
        mediaController?.sendCustomCommand(
            SessionCommand(COMMAND_SET_SEEK_FORWARD_INCREMENT, Bundle().apply {
                putLong("increment", increment)
            }),
            Bundle()
        )
    }

    @Composable
    fun getProgressState(): MutableState<Float> {
        val progressState = remember { mutableFloatStateOf(0f) }

        LaunchedEffect(lastProgressUpdate) {
            while(true) {
                val difference = System.currentTimeMillis() - lastProgressUpdate
                val realCurrentPosition = if(isPlaying) {
                    currentPosition + (difference * speed)
                } else {
                    currentPosition
                }

                progressState.value = realCurrentPosition.toFloat() / currentDuration

                delay(100)
            }
        }

        return progressState
    }

    /**
     * Returns sleep timer state (in minutes)
     */
    @Composable
    fun getSleepTimerState(): MutableState<Int?> {
        val sleepTimerState = remember { mutableStateOf<Int?>(null) }

        LaunchedEffect(sleepTimerTrigger) {
            while(true) {
                sleepTimerState.value = sleepTimerTrigger?.let { unixMillis ->
                    val diff = unixMillis - System.currentTimeMillis()
                    ((diff / 1000L) / 60f).roundToInt()
                }

                delay(1000 * 30)
            }
        }

        return sleepTimerState
    }

    fun syncQueue(persist: Boolean = hasRestoredQueue) {
        mediaController?.let { mediaController ->
            queue.clear()
            queueIndex.clear()

            repeat(mediaController.mediaItemCount) { index ->
                if(mediaController.currentMediaItemIndex >= index) return@repeat

                val mediaItem = mediaController.getMediaItemAt(index)
                mediaItem.mediaMetadata.extras?.getString(MediaMetadataExtra.EPISODE_ID.name)
                    ?.let { episodeId ->
                        queue.add(episodeId)
                        queueIndex.add(index)
                    }
            }

            if(persist && ::db.isInitialized) {
                val snapshot = queue.toList()
                viewModelScope.launch(Dispatchers.IO) {
                    db.playbackQueue().replaceQueue(snapshot)
                }
            }
        }
    }

    fun restoreQueueIfNeeded(context: Context) {
        if(hasRestoredQueue || !::db.isInitialized) return
        val controller = mediaController ?: return
        hasRestoredQueue = true

        if(controller.mediaItemCount > 1) {
            syncQueue(persist = true)
            return
        }

        val currentEpisodeId = controller.currentMediaItem
            ?.mediaMetadata?.extras?.getString(MediaMetadataExtra.EPISODE_ID.name)

        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                db.playbackQueue().getQueueSync()
            }
            if(saved.isEmpty()) return@launch

            val appCtx = context.applicationContext
            for(item in saved) {
                if(item.episodeId == currentEpisodeId) continue
                val bundle = withContext(Dispatchers.IO) {
                    runCatching { db.podcastEpisodes().getSync(item.episodeId) }.getOrNull()
                } ?: continue
                controller.addMediaItem(bundle.createMediaItem(appCtx))
            }

            syncQueue(persist = false)
        }
    }

    @Composable
    fun rememberMediaItemAt(index: Int): MediaItem? {
        var mediaItem by remember(index) { mutableStateOf<MediaItem?>(null) }
        LaunchedEffect(index, queue.toList()) {
            val controller = mediaController
            mediaItem = if(controller != null && index in 0 until controller.mediaItemCount) {
                controller.getMediaItemAt(index)
            } else {
                null
            }
        }

        return mediaItem
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
            this@MediaPlayerViewModel.updateSleepTimer()
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

            this@MediaPlayerViewModel.mediaSourceType = when(mediaMetadata.isDownload()) {
                true -> SourceTypeState.Downloaded
                false -> SourceTypeState.Streaming
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

            this@MediaPlayerViewModel.mediaSourceType = when(mediaItem.isDownload()) {
                true -> SourceTypeState.Downloaded
                false -> SourceTypeState.Streaming
            }
        }

        this@MediaPlayerViewModel.playbackState = mediaController.playbackState
        this@MediaPlayerViewModel.isPlayerVisible =
            mediaController.playbackState != Player.STATE_IDLE

        syncQueue()

        mediaController.addListener(listener)
    }

    fun unregisterMediaController() {
        mediaController?.removeListener(listener)
    }

    fun passDB(db: AppDatabase) {
        this.db = db
    }

}