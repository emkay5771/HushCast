package app.podiumpodcasts.podium.background

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import app.hushcast.automute.dsp.AdPolicyMode
import app.hushcast.automute.dsp.HushCastAudioProcessorChain
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.storage.toDomain
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.SettingsRepository
import app.podiumpodcasts.podium.api.db.model.MediaMetadataExtra
import app.podiumpodcasts.podium.background.notification.NewPodcastEpisodeNotification
import app.podiumpodcasts.podium.background.worker.sync.PartialSynchronizationWorker
import app.podiumpodcasts.podium.manager.DatabaseManager
import app.podiumpodcasts.podium.ui.DeepLink
import app.podiumpodcasts.podium.ui.asPendingIntent
import app.podiumpodcasts.podium.utils.getAudioUrl
import app.podiumpodcasts.podium.utils.getEpisodeId
import app.podiumpodcasts.podium.utils.getOrigin
import app.podiumpodcasts.podium.utils.getResumeAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

const val COMMAND_SLEEP_TIMER_SET = "app.podiumpodcasts.podium.COMMAND_SLEEP_TIMER_SET"
const val COMMAND_SLEEP_TIMER_GET = "app.podiumpodcasts.podium.COMMAND_SLEEP_TIMER_GET"
const val COMMAND_CYCLE_SPEED = "app.podiumpodcasts.podium.COMMAND_CYCLE_SPEED"

const val COMMAND_SET_SEEK_BACK_INCREMENT =
    "app.podiumpodcasts.podium.COMMAND_SET_SEEK_BACK_INCREMENT"
const val COMMAND_SET_SEEK_FORWARD_INCREMENT =
    "app.podiumpodcasts.podium.COMMAND_SET_SEEK_FORWARD_INCREMENT"

class PlaybackService : MediaLibraryService() {

    val db by lazy {
        DatabaseManager.build(this)
    }

    private val autoMuteDb by lazy {
        AutoMuteDatabase.getInstance(this)
    }

    private val settingsRepository = SettingsRepository(this)

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job)

    private var mediaSession: MediaLibrarySession? = null

    private var currentSkipEndingValue: Int = 0

    private var updatePlayStateHandler: Handler = Handler(Looper.getMainLooper())
    private var updatePlayStateRunnable = Runnable {
        if(mediaSession?.player?.isPlaying != true) return@Runnable
        enqueueUpdatePlayState()
        updatePlayState()
    }

    private var skipEndingHandler: Handler = Handler(Looper.getMainLooper())
    private var skipEndingRunnable = Runnable {
        if(mediaSession?.player?.isPlaying != true) return@Runnable
        enqueueSkipEnding()
        checkSkipEnding()
    }

    private val adSkipHandler: Handler = Handler(Looper.getMainLooper())
    private val adSkipRunnable = object : Runnable {
        override fun run() {
            val player = mediaSession?.player ?: return
            if(!player.isPlaying) return
            HushCastEngineBridge.adSkipObserver.checkPosition(player)
            adSkipHandler.postDelayed(this, 300L)
        }
    }

    private var sleepTimerHandler: Handler = Handler(Looper.getMainLooper())
    var sleepTimerTrigger: Long? = null
    private val sleepTimerFadeDurationMs = 10_000L
    private var sleepTimerRunnable = Runnable {
        sleepTimerTrigger = null
        mediaSession?.player?.let { p ->
            p.pause()
            p.volume = 1.0f
        }
    }
    private val sleepTimerFadeRunnable = object : Runnable {
        override fun run() {
            val trigger = sleepTimerTrigger ?: return
            val player = mediaSession?.player ?: return
            val remaining = trigger - System.currentTimeMillis()
            if(remaining <= 0L) return
            if(remaining <= sleepTimerFadeDurationMs) {
                player.volume = (remaining.toFloat() / sleepTimerFadeDurationMs.toFloat()).coerceIn(0.05f, 1.0f)
                sleepTimerHandler.postDelayed(this, 250L)
            } else {
                player.volume = 1.0f
                sleepTimerHandler.postDelayed(this, (remaining - sleepTimerFadeDurationMs).coerceAtLeast(250L))
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build()
                .apply {
                    setSmallIcon(
                        R.drawable.ic_notification_icon
                    )
                }
        )

        val seekBackIncrement = runBlocking {
            settingsRepository.behavior.playerSeekBackIncrement.first()
        }

        val seekForwardIncrement = runBlocking {
            settingsRepository.behavior.playerSeekForwardIncrement.first()
        }

        val playbackSpeed = runBlocking {
            settingsRepository.behavior.playerPlaybackSpeed.first()
        }

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(
                        HushCastAudioProcessorChain(HushCastEngineBridge.autoMuteProcessor)
                    )
                    .build()
            }
        }

        val extractorsFactory = DefaultExtractorsFactory()
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractorsFactory))
            .setSeekBackIncrementMs(seekBackIncrement)
            .setSeekForwardIncrementMs(seekForwardIncrement)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.setPlaybackSpeed(playbackSpeed)

        mediaSession = MediaLibrarySession.Builder(this, player, MediaLibrarySessionCallback(this))
            .setMediaButtonPreferences(buildMediaButtons(player))
            .setSessionActivity(
                DeepLink.OpenMediaPlayer()
                    .asPendingIntent(this, 42)!!
            )
            .build()

        scope.launch {
            HushCastEngineBridge.adSkipEvents.collect { event ->
                HushCastEngineBridge.notifyAdSkipped(event)
            }
        }

        scope.launch {
            downloadedEpisodeEvents.collect { downloadedId ->
                val bundle = withContext(Dispatchers.IO) {
                    runCatching { db.podcastEpisodes().getSync(downloadedId) }.getOrNull()
                } ?: return@collect
                val localItem = bundle.createMediaItem(this@PlaybackService)

                withContext(Dispatchers.Main) {
                    val p = mediaSession?.player
                    if (p != null) {
                        val currentIdx = p.currentMediaItemIndex
                        for(i in 0 until p.mediaItemCount) {
                            val item = p.getMediaItemAt(i)
                            val extras = item.mediaMetadata.extras
                            val epId = extras?.getString(MediaMetadataExtra.EPISODE_ID.name)
                            val isAlreadyDownload = extras?.getBoolean(MediaMetadataExtra.IS_DOWNLOAD.name) ?: false
                            if(epId == downloadedId && !isAlreadyDownload) {
                                if(i == currentIdx) {
                                    val posMs = p.currentPosition.coerceAtLeast(0L)
                                    val wasPlaying = p.playWhenReady
                                    p.replaceMediaItem(i, localItem)
                                    p.seekTo(i, posMs)
                                    p.prepare()
                                    p.playWhenReady = wasPlaying
                                } else {
                                    p.replaceMediaItem(i, localItem)
                                }
                            }
                        }
                    }
                    val activeEpId = p?.currentMediaItem?.getEpisodeId() ?: HushCastEngineBridge.currentEpisodeId
                    val activeOrigin = p?.currentMediaItem?.getOrigin() ?: bundle.episode.origin
                    if (activeEpId == downloadedId) {
                        loadHushCastForEpisode(downloadedId, activeOrigin)
                    }
                }
            }
        }

        scope.launch {
            app.hushcast.automute.worker.EpisodeAnalysisWorker.analysisCompletedEvents.collect { analyzedId ->
                withContext(Dispatchers.Main) {
                    val p = mediaSession?.player
                    val activeEpId = p?.currentMediaItem?.getEpisodeId() ?: HushCastEngineBridge.currentEpisodeId
                    if (activeEpId == analyzedId) {
                        val activeOrigin = p?.currentMediaItem?.getOrigin()
                            ?: withContext(Dispatchers.IO) {
                                runCatching { db.podcastEpisodes().getSync(analyzedId)?.episode?.origin }.getOrNull()
                            }
                            ?: return@withContext
                        loadHushCastForEpisode(analyzedId, activeOrigin)
                    }
                }
            }
        }

        registerPlayStateListener()
    }

    private var loadHushCastJob: kotlinx.coroutines.Job? = null

    private fun loadHushCastForEpisode(episodeId: String, origin: String) {
        HushCastEngineBridge.currentEpisodeId = episodeId
        loadHushCastJob?.cancel()
        loadHushCastJob = scope.launch(Dispatchers.IO) {
            val status = autoMuteDb.dao().getStatus(episodeId)
            val sha = status?.fileSha256?.takeIf { it.isNotBlank() }
            if (sha != null) {
                val mutes = autoMuteDb.dao().getMuteSegments(sha).map { it.toDomain() }
                val ads = autoMuteDb.dao().getAdSegments(sha).map { it.toDomain() }
                val policyStr = autoMuteDb.dao().getPodcastAdPolicy(origin)?.policyMode
                val policy = policyStr?.let {
                    runCatching { AdPolicyMode.valueOf(it) }.getOrNull()
                } ?: HushCastEngineBridge.defaultAdPolicy
                HushCastEngineBridge.autoMuteProcessor.updateWindows(mutes)
                HushCastEngineBridge.adSkipObserver.setEpisodeAds(ads, policy)
                android.util.Log.i(
                    "HushCastPerf",
                    "Loaded playback windows: ep=$episodeId sha=${sha.take(8)} mutes=${mutes.count { it.enabled }}/${mutes.size} ads=${ads.size} (highConf=${ads.count { it.isHighConfidence }}) policy=$policy wordMuteEnabled=${HushCastEngineBridge.wordMutingEnabled}"
                )
            } else {
                HushCastEngineBridge.autoMuteProcessor.updateWindows(emptyList())
                HushCastEngineBridge.adSkipObserver.clear()
                android.util.Log.i(
                    "HushCastPerf",
                    "Cleared playback windows (no sha): ep=$episodeId badge=${status?.badge}"
                )
            }
        }
    }

    companion object {
        private val downloadedEpisodeEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(
            extraBufferCapacity = 16
        )

        fun notifyEpisodeDownloaded(episodeId: String) {
            downloadedEpisodeEvents.tryEmit(episodeId)
        }
    }

    fun buildMediaButtons(
        player: ExoPlayer
    ): List<CommandButton> {
        val seekBack = CommandButton.Builder(
            when(player.seekBackIncrement) {
                5000L -> CommandButton.ICON_SKIP_BACK_5
                10000L -> CommandButton.ICON_SKIP_FORWARD_10
                15000L -> CommandButton.ICON_SKIP_FORWARD_15
                30000L -> CommandButton.ICON_SKIP_FORWARD_30
                else -> CommandButton.ICON_SKIP_FORWARD
            }
        )
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setCustomIconResId(
                when(player.seekBackIncrement) {
                    5000L -> R.drawable.ic_replay_5
                    10000L -> R.drawable.ic_replay_10
                    30000L -> R.drawable.ic_replay_30
                    else -> R.drawable.ic_replay
                }
            )
            .setDisplayName(getString(R.string.common_action_seek_back))
            .build()

        val seekForward = CommandButton.Builder(
            when(player.seekForwardIncrement) {
                5000L -> CommandButton.ICON_SKIP_FORWARD_5
                10000L -> CommandButton.ICON_SKIP_FORWARD_10
                15000L -> CommandButton.ICON_SKIP_FORWARD_15
                30000L -> CommandButton.ICON_SKIP_FORWARD_30
                else -> CommandButton.ICON_SKIP_FORWARD
            }
        )
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setCustomIconResId(
                when(player.seekForwardIncrement) {
                    5000L -> R.drawable.ic_forward_5
                    10000L -> R.drawable.ic_forward_10
                    30000L -> R.drawable.ic_forward_30
                    else -> R.drawable.ic_forward
                }
            )
            .setDisplayName(getString(R.string.common_action_seek_forward))
            .build()

        val playbackSpeed = CommandButton.Builder(CommandButton.ICON_PLAYBACK_SPEED)
            .setSessionCommand(SessionCommand(COMMAND_CYCLE_SPEED, Bundle.EMPTY))
            .setCustomIconResId(R.drawable.ic_playback_speed)
            .setDisplayName(getString(R.string.common_action_toggle_playback_speed))
            .build()

        return listOf(
            seekBack,
            seekForward,
            playbackSpeed
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    private fun enqueueUpdatePlayState() {
        updatePlayStateHandler.postDelayed(updatePlayStateRunnable, 20 * 1000)
    }

    private fun updatePlayState() {
        val origin = getOrigin() ?: return
        val episodeId = getEpisodeId() ?: return
        val audioUrl = getAudioUrl() ?: return
        val currentPosition = mediaSession?.player?.currentPosition ?: return
        val duration = mediaSession?.player?.duration ?: return

        if(duration < 1000) return

        val state = (currentPosition / 1000).toInt()
        val remainingSeconds = (duration / 1000) - (currentPosition / 1000)

        val isPlayed = remainingSeconds < 90

        scope.launch {
            if(isPlayed) {
                db.podcastEpisodePlayStates()
                    .savePlayed(
                        episodeId = episodeId,
                        played = true
                    )
            }

            db.podcastEpisodePlayStates()
                .saveState(
                    episodeId = episodeId,
                    state = state.coerceAtLeast(1)
                )

            db.syncActions()
                .addPlayState(
                    origin = origin,
                    episodeId = episodeId,
                    audioUrl = audioUrl,
                    duration = (duration / 1000L).toInt(),
                    state = state,
                    played = isPlayed
                )
        }
    }

    private fun enqueueSkipEnding() {
        skipEndingHandler.postDelayed(skipEndingRunnable, 1000)
    }

    private fun checkSkipEnding() {
        val player = mediaSession?.player ?: return
        if(currentSkipEndingValue == 0) return

        val diff = (player.duration) - (currentSkipEndingValue * 1000L)

        if(player.currentPosition < diff) return
        mediaSession?.player?.seekTo(player.duration)
    }

    private fun getOrigin() = mediaSession?.player?.currentMediaItem?.getOrigin()

    private fun getEpisodeId() = mediaSession?.player?.currentMediaItem?.getEpisodeId()

    private fun getAudioUrl() = mediaSession?.player?.currentMediaItem?.getAudioUrl()

    private fun registerPlayStateListener() {
        mediaSession?.player?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.getResumeAt()?.let { resumeAt ->
                    mediaSession?.player?.seekTo(resumeAt)
                }
                mediaItem?.getEpisodeId()?.let { episodeId ->
                    mediaItem.getOrigin()?.let { origin ->
                        loadHushCastForEpisode(episodeId, origin)
                    }
                }
                mediaSession?.let { session ->
                    onUpdateNotification(session, session.player.playWhenReady)
                }

                super.onMediaItemTransition(mediaItem, reason)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlayState()
                if(playbackState == Player.STATE_ENDED) {
                    getEpisodeId()?.let { episodeId ->
                        scope.launch {
                            db.podcastEpisodePlayStates()
                                .savePlayed(
                                    episodeId = episodeId,
                                    played = true
                                )
                        }

                        val duration =
                            mediaSession?.player?.duration?.let { (it / 1000L).toInt() } ?: 1

                        getOrigin()?.let { origin ->
                            getAudioUrl()?.let { audioUrl ->
                                scope.launch {
                                    db.syncActions()
                                        .addPlayState(
                                            origin = origin,
                                            episodeId = episodeId,
                                            audioUrl = audioUrl,
                                            duration = duration,
                                            state = 0,
                                            played = true
                                        )
                                }
                            }
                        }
                    }

                    syncIfEnabled()
                }

                super.onPlaybackStateChanged(playbackState)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayState()

                if(isPlaying) {
                    enqueueUpdatePlayState()
                    enqueueSkipEnding()
                    adSkipHandler.removeCallbacks(adSkipRunnable)
                    adSkipHandler.post(adSkipRunnable)
                } else {
                    updatePlayStateHandler.removeCallbacks(updatePlayStateRunnable)
                    skipEndingHandler.removeCallbacks(skipEndingRunnable)
                    adSkipHandler.removeCallbacks(adSkipRunnable)

                    syncIfEnabled()
                }

                super.onIsPlayingChanged(isPlaying)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val player = mediaSession?.player

                getOrigin()?.let { origin ->
                    getEpisodeId()?.let { episodeId ->
                        NewPodcastEpisodeNotification
                            .cancel(this@PlaybackService.applicationContext, episodeId)

                        loadHushCastForEpisode(episodeId, origin)

                        scope.launch {
                            val podcast = db.podcasts().getSync(origin)
                            currentSkipEndingValue = (podcast?.skipEnding) ?: 0

                            podcast?.let { podcast ->
                                withContext(Dispatchers.Main) {
                                    // skip beginning if defined for podcast
                                    if(podcast.skipBeginning == 0) return@withContext

                                    if(player == null) return@withContext
                                    if(player.currentPosition > 3000L) return@withContext
                                    player.seekTo(podcast.skipBeginning * 1000L)
                                }
                            }

                            val last = db.podcastHistory().getLast()
                                .first()

                            // don't save duplicate elements if playback happened in the 4 hours
                            if((last?.episode?.id == episodeId)) {
                                val timestamp = System.currentTimeMillis()
                                if((timestamp - last.history.timestamp) < 1000 * 60 * 60 * 4) {
                                    db.podcastHistory().updateTimestamp(
                                        episodeId = episodeId,
                                        timestamp = timestamp
                                    )
                                    return@launch
                                }
                            }

                            db.podcastHistory().insert(
                                origin = origin,
                                episodeId = episodeId
                            )
                        }
                    }
                }

                super.onMediaMetadataChanged(mediaMetadata)
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                scope.launch { settingsRepository.behavior.setPlayerPlaybackSpeed(playbackParameters.speed) }
                super.onPlaybackParametersChanged(playbackParameters)
            }
        })
    }

    fun stopSleepTimer() {
        sleepTimerTrigger = null
        sleepTimerHandler.removeCallbacks(sleepTimerRunnable)
        sleepTimerHandler.removeCallbacks(sleepTimerFadeRunnable)
        mediaSession?.player?.volume = 1.0f
    }

    fun startSleepTimer(trigger: Long) {
        sleepTimerTrigger = trigger

        val delay = (trigger - System.currentTimeMillis()).coerceAtLeast(0L)

        sleepTimerHandler.removeCallbacks(sleepTimerRunnable)
        sleepTimerHandler.removeCallbacks(sleepTimerFadeRunnable)
        sleepTimerHandler.postDelayed(sleepTimerRunnable, delay)
        sleepTimerHandler.post(sleepTimerFadeRunnable)
    }

    fun syncIfEnabled() {
        scope.launch {
            if(!settingsRepository.sync.enable.first())
                return@launch

            PartialSynchronizationWorker.enqueue(
                context = this@PlaybackService,
                debounceSeconds = 30L
            )
        }
    }

    override fun onDestroy() {
        updatePlayStateHandler.removeCallbacks(updatePlayStateRunnable)
        skipEndingHandler.removeCallbacks(skipEndingRunnable)
        adSkipHandler.removeCallbacks(adSkipRunnable)
        sleepTimerHandler.removeCallbacksAndMessages(null)

        stopSleepTimer()

        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}