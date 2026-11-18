package app.podiumpodcasts.podium.background

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
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
import androidx.room.withTransaction
import app.hushcast.automute.dsp.AdPolicyMode
import app.hushcast.automute.dsp.HushCastAudioProcessorChain
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.storage.loadResolvedMuteWindows
import app.hushcast.automute.storage.loadResolvedMuteWindowsByEpisode
import app.hushcast.automute.storage.toDomain
import app.podiumpodcasts.podium.HeadsetNextAction
import app.podiumpodcasts.podium.HeadsetPrevAction
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val COMMAND_SLEEP_TIMER_SET = "app.podiumpodcasts.podium.COMMAND_SLEEP_TIMER_SET"
const val COMMAND_SLEEP_TIMER_GET = "app.podiumpodcasts.podium.COMMAND_SLEEP_TIMER_GET"
const val COMMAND_CYCLE_SPEED = "app.podiumpodcasts.podium.COMMAND_CYCLE_SPEED"

const val COMMAND_SET_SEEK_BACK_INCREMENT =
    "app.podiumpodcasts.podium.COMMAND_SET_SEEK_BACK_INCREMENT"
const val COMMAND_SET_SEEK_FORWARD_INCREMENT =
    "app.podiumpodcasts.podium.COMMAND_SET_SEEK_FORWARD_INCREMENT"

const val EXTRA_SLEEP_TIMER_TRIGGER = "sleep_timer_trigger"

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    val db by lazy {
        DatabaseManager.build(this)
    }

    private val autoMuteDb by lazy {
        AutoMuteDatabase.getInstance(this)
    }

    private val settingsRepository by lazy { SettingsRepository(this) }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main)

    private lateinit var exoPlayer: ExoPlayer
    private var mediaSession: MediaLibrarySession? = null
    private var sessionCallback: MediaLibrarySessionCallback? = null

    private var currentSkipEndingValue: Int = 0
    @Volatile var currentSeekBackIncrementMs: Long = 10_000L
    @Volatile var currentSeekForwardIncrementMs: Long = 10_000L
    @Volatile var speedPresets: List<Float> = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f)
    @Volatile var headsetNextAction: HeadsetNextAction = HeadsetNextAction.SKIP_FORWARD
    @Volatile var headsetPrevAction: HeadsetPrevAction = HeadsetPrevAction.SKIP_BACK

    private val scheduledMessages = mutableListOf<PlayerMessage>()
    private var lastStartedEpisodeId: String? = null
    @Volatile private var intelligentPlaybackResumptionEnabled: Boolean = false
    private var lastPausedAtMs: Long = 0L
    private var lastPausedMediaId: String? = null

    private val updatePlayStateHandler: Handler = Handler(Looper.getMainLooper())
    private val updatePlayStateRunnable = Runnable {
        if (mediaSession?.player?.isPlaying != true) return@Runnable
        enqueueUpdatePlayState()
        updatePlayState()
    }

    private val sleepTimerHandler: Handler = Handler(Looper.getMainLooper())
    var sleepTimerTrigger: Long? = null
        private set
    private val sleepTimerFadeDurationMs = 10_000L
    private val sleepTimerRunnable = Runnable {
        sleepTimerTrigger = null
        publishSleepTimerExtra()
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
            if (remaining <= 0L) return
            if (remaining <= sleepTimerFadeDurationMs) {
                player.volume = (remaining.toFloat() / sleepTimerFadeDurationMs.toFloat()).coerceIn(0.05f, 1.0f)
                sleepTimerHandler.postDelayed(this, 250L)
            } else {
                player.volume = 1.0f
                sleepTimerHandler.postDelayed(this, (remaining - sleepTimerFadeDurationMs).coerceAtLeast(250L))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build()
                .apply {
                    setSmallIcon(R.drawable.ic_notification_icon)
                }
        )

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

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 1_500, 3_000)
            .build()

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractorsFactory))
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(currentSeekBackIncrementMs)
            .setSeekForwardIncrementMs(currentSeekForwardIncrementMs)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        val player = object : ForwardingPlayer(exoPlayer) {
            override fun seekForward() {
                val skipRanges = HushCastEngineBridge.getActiveSkipRanges()
                if (skipRanges.isEmpty()) {
                    super.seekForward()
                    return
                }
                val target = app.hushcast.automute.dsp.SkipAwareSeekCalculator.computeForwardTargetMs(
                    fromMs = currentPosition,
                    incrementMs = currentSeekForwardIncrementMs,
                    durationMs = duration,
                    skipRanges = skipRanges
                )
                seekTo(target)
            }

            override fun seekBack() {
                val skipRanges = HushCastEngineBridge.getActiveSkipRanges()
                if (skipRanges.isEmpty()) {
                    super.seekBack()
                    return
                }
                val target = app.hushcast.automute.dsp.SkipAwareSeekCalculator.computeBackTargetMs(
                    fromMs = currentPosition,
                    incrementMs = currentSeekBackIncrementMs,
                    skipRanges = skipRanges
                )
                seekTo(target)
            }
        }

        val callback = MediaLibrarySessionCallback(this)
        sessionCallback = callback

        mediaSession = MediaLibrarySession.Builder(this, player, callback)
            .setMediaButtonPreferences(buildMediaButtons(exoPlayer))
            .setSessionActivity(
                DeepLink.OpenMediaPlayer()
                    .asPendingIntent(this, 42)!!
            )
            .build()

        // Load persisted settings asynchronously on startup (F2, P9, P22)
        scope.launch {
            HushCastEngineBridge.loadFromSettings(settingsRepository, this@PlaybackService)
            val backInc = settingsRepository.behavior.playerSeekBackIncrement.first()
            val fwdInc = settingsRepository.behavior.playerSeekForwardIncrement.first()
            val speed = settingsRepository.behavior.playerPlaybackSpeed.first()
            val trimSilence = settingsRepository.behavior.trimSilence.first()
            currentSeekBackIncrementMs = backInc
            currentSeekForwardIncrementMs = fwdInc
            exoPlayer.setSeekBackIncrementMs(backInc)
            exoPlayer.setSeekForwardIncrementMs(fwdInc)
            exoPlayer.setPlaybackSpeed(speed)
            exoPlayer.skipSilenceEnabled = trimSilence
            mediaSession?.setMediaButtonPreferences(buildMediaButtons(exoPlayer))
        }

        scope.launch {
            settingsRepository.behavior.trimSilence.distinctUntilChanged().collect { exoPlayer.skipSilenceEnabled = it }
        }
        scope.launch {
            settingsRepository.behavior.volumeBoost.distinctUntilChanged().collect { HushCastEngineBridge.volumeBoostEnabled = it }
        }
        scope.launch {
            settingsRepository.hushCast.sponsorSkipSoundEnabled.distinctUntilChanged().collect {
                HushCastEngineBridge.sponsorSkipSoundEnabled = it
            }
        }
        scope.launch {
            settingsRepository.hushCast.sponsorSkipSoundUri.distinctUntilChanged().collect {
                HushCastEngineBridge.sponsorSkipSoundUri = it
            }
        }
        scope.launch {
            settingsRepository.hushCast.muteCustomSoundUri.distinctUntilChanged().collect {
                HushCastEngineBridge.loadCustomMuteSound(this@PlaybackService, it)
            }
        }
        scope.launch {
            settingsRepository.hushCast.muteActionMode.distinctUntilChanged().collect { mode ->
                HushCastEngineBridge.defaultActionMode = mode
                val activeEpId = exoPlayer.currentMediaItem?.getEpisodeId() ?: HushCastEngineBridge.currentEpisodeId
                if (!activeEpId.isNullOrBlank()) {
                    withContext(Dispatchers.IO) {
                        val updated = autoMuteDb.dao().loadResolvedMuteWindowsByEpisode(activeEpId, mode)
                        HushCastEngineBridge.autoMuteProcessor.updateWindows(updated)
                    }
                }
            }
        }
        scope.launch {
            settingsRepository.hushCast.wordMutingEnabled.distinctUntilChanged().collect { enabled ->
                HushCastEngineBridge.wordMutingEnabled = enabled
                val origin = getOrigin()
                val podcastOverride = if (origin != null) {
                    withContext(Dispatchers.IO) {
                        settingsRepository.hushCast.podcastWordMuting(origin).firstOrNull()
                    }
                } else null
                HushCastEngineBridge.autoMuteProcessor.isEnabled = podcastOverride ?: enabled
            }
        }
        scope.launch {
            settingsRepository.hushCast.adPolicyMode.distinctUntilChanged().collect { mode ->
                HushCastEngineBridge.defaultAdPolicy = mode
                val origin = getOrigin()
                val podcastOverride = if (origin != null) {
                    withContext(Dispatchers.IO) {
                        autoMuteDb.dao().getPodcastAdPolicy(origin)?.policyMode?.let {
                            runCatching { AdPolicyMode.valueOf(it) }.getOrNull()
                        }
                    }
                } else null
                HushCastEngineBridge.adSkipObserver.policyMode = podcastOverride ?: mode
                checkActiveSkipsNow()
                schedulePositionCallbacks()
            }
        }

        scope.launch {
            settingsRepository.behavior.speedPresets.distinctUntilChanged().collect { speedPresets = it }
        }
        scope.launch {
            settingsRepository.behavior.headsetNextAction.distinctUntilChanged().collect { headsetNextAction = it }
        }
        scope.launch {
            settingsRepository.behavior.headsetPrevAction.distinctUntilChanged().collect { headsetPrevAction = it }
        }
        scope.launch {
            settingsRepository.behavior.intelligentPlaybackResumption.distinctUntilChanged().collect {
                intelligentPlaybackResumptionEnabled = it
            }
        }

        scope.launch {
            HushCastEngineBridge.adSkipEvents.collect { event ->
                HushCastEngineBridge.notifyAdSkipped(event)
                HushCastEngineBridge.playSponsorSkipIndicator(this@PlaybackService)
            }
        }

        scope.launch {
            downloadedEpisodeEvents.collect { downloadedId ->
                val bundle = withContext(Dispatchers.IO) {
                    db.podcastEpisodes().getSyncNullable(downloadedId)
                } ?: return@collect
                val localItem = bundle.createMediaItem(this@PlaybackService)

                val p = mediaSession?.player
                if (p != null) {
                    val currentIdx = p.currentMediaItemIndex
                    for (i in 0 until p.mediaItemCount) {
                        val item = p.getMediaItemAt(i)
                        val extras = item.mediaMetadata.extras
                        val epId = extras?.getString(MediaMetadataExtra.EPISODE_ID.name)
                        val isAlreadyDownload = extras?.getBoolean(MediaMetadataExtra.IS_DOWNLOAD.name) ?: false
                        if (epId == downloadedId && !isAlreadyDownload) {
                            if (i == currentIdx) {
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

        scope.launch {
            app.hushcast.automute.worker.EpisodeAnalysisWorker.analysisCompletedEvents.collect { analyzedId ->
                val p = mediaSession?.player
                val activeEpId = p?.currentMediaItem?.getEpisodeId() ?: HushCastEngineBridge.currentEpisodeId
                if (activeEpId == analyzedId) {
                    val activeOrigin = p?.currentMediaItem?.getOrigin()
                        ?: withContext(Dispatchers.IO) {
                            db.podcastEpisodes().getSyncNullable(analyzedId)?.episode?.origin
                        }
                        ?: return@collect
                    loadHushCastForEpisode(analyzedId, activeOrigin)
                }
            }
        }

        registerPlayStateListener()
    }

    fun updateSeekBackIncrement(incrementMs: Long) {
        currentSeekBackIncrementMs = incrementMs
        exoPlayer.setSeekBackIncrementMs(incrementMs)
        mediaSession?.setMediaButtonPreferences(buildMediaButtons(exoPlayer))
    }

    fun updateSeekForwardIncrement(incrementMs: Long) {
        currentSeekForwardIncrementMs = incrementMs
        exoPlayer.setSeekForwardIncrementMs(incrementMs)
        mediaSession?.setMediaButtonPreferences(buildMediaButtons(exoPlayer))
    }

    private var loadHushCastJob: Job? = null

    private fun loadHushCastForEpisode(episodeId: String, origin: String) {
        HushCastEngineBridge.currentEpisodeId = episodeId
        HushCastEngineBridge.clearSkippedChapterRanges()
        loadHushCastJob?.cancel()
        loadHushCastJob = scope.launch(Dispatchers.IO) {
            val globalWordMuting = settingsRepository.hushCast.wordMutingEnabled.firstOrNull() ?: true
            val podcastWordMuting = settingsRepository.hushCast.podcastWordMuting(origin).firstOrNull()
            HushCastEngineBridge.autoMuteProcessor.isEnabled = podcastWordMuting ?: globalWordMuting

            val status = autoMuteDb.dao().getStatus(episodeId)
            val sha = status?.fileSha256?.takeIf { it.isNotBlank() }
            if (sha != null) {
                val mutes = autoMuteDb.dao().loadResolvedMuteWindows(sha, HushCastEngineBridge.defaultActionMode)
                val ads = autoMuteDb.dao().getAdSegments(sha).map { it.toDomain() }
                val policyStr = autoMuteDb.dao().getPodcastAdPolicy(origin)?.policyMode
                val policy = policyStr?.let {
                    runCatching { AdPolicyMode.valueOf(it) }.getOrNull()
                } ?: HushCastEngineBridge.defaultAdPolicy
                HushCastEngineBridge.autoMuteProcessor.updateWindows(mutes)
                HushCastEngineBridge.adSkipObserver.setEpisodeAds(ads, policy)
            } else {
                HushCastEngineBridge.autoMuteProcessor.updateWindows(emptyList())
                HushCastEngineBridge.adSkipObserver.clear()
            }

            val epBundle = db.podcastEpisodes().getSyncNullable(episodeId)
            val episodeDurationMs = (epBundle?.episode?.duration ?: 0) * 1000L

            // C1: fetch feed chapters if not yet stored
            val chaptersUrl = epBundle?.episode?.chaptersUrl.orEmpty()
            val chaptersType = epBundle?.episode?.chaptersType.orEmpty()
            if (chaptersUrl.isNotBlank()) {
                val existing = db.episodeChapters().getByEpisodeSync(episodeId)
                if (existing.none { it.isPublisher }) {
                    app.podiumpodcasts.podium.api.chapters.EpisodeChapterHelper.fetchAndStoreFeedChapters(
                        db = db,
                        episodeId = episodeId,
                        chaptersUrl = chaptersUrl,
                        chaptersType = chaptersType,
                        durationMs = episodeDurationMs
                    )
                }
            }

            // Backfill generated chapters from insights if no publisher chapters exist
            val insights = autoMuteDb.dao().getInsights(episodeId)
            val generatedDtos = insights?.chaptersJson?.let { raw ->
                runCatching {
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString<List<app.hushcast.automute.desktop.DesktopChapterDto>>(raw)
                        .sortedBy { it.startMs }
                }.getOrDefault(emptyList())
            } ?: emptyList()
            if (generatedDtos.isNotEmpty()) {
                val generatedEntities = generatedDtos.map { dto ->
                    app.podiumpodcasts.podium.api.db.model.EpisodeChapterModel(
                        episodeId = episodeId,
                        source = app.podiumpodcasts.podium.api.db.model.EpisodeChapterModel.SOURCE_GENERATED,
                        startMs = dto.startMs,
                        title = dto.title
                    )
                }
                db.episodeChapters().upsertGeneratedChaptersIfNoPublisher(episodeId, generatedEntities)
            }

            kotlinx.coroutines.flow.combine(
                app.podiumpodcasts.podium.api.chapters.EpisodeChapterHelper.observeResolvedChapters(
                    db = db,
                    autoMuteDb = autoMuteDb,
                    episodeId = episodeId,
                    durationMs = episodeDurationMs
                ),
                db.chapterSkips().observe(episodeId)
            ) { resolvedChapters, skipModel ->
                resolvedChapters to (skipModel?.skippedStartsSet() ?: emptySet())
            }.collect { (resolvedChapters, skippedStarts) ->
                HushCastEngineBridge.updateSkippedChapterRangesResolved(
                    episodeId = episodeId,
                    chapters = resolvedChapters,
                    skippedStarts = skippedStarts
                )
                withContext(Dispatchers.Main) {
                    checkActiveSkipsNow()
                    schedulePositionCallbacks()
                }
            }
        }
    }

    fun jumpToNextChapter(): Boolean {
        val player = mediaSession?.player ?: return false
        val chapters = HushCastEngineBridge.currentResolvedChapters
        if (chapters.isEmpty()) return false
        val pos = player.currentPosition
        val skipped = HushCastEngineBridge.getSkippedChapterRanges().map { it.startMs }.toHashSet()
        val next = chapters.firstOrNull { it.startMs > pos + 500L && it.startMs !in skipped } ?: return false
        player.seekTo(next.startMs)
        return true
    }

    private fun checkActiveSkipsNow() {
        val player = mediaSession?.player ?: return
        if (!HushCastEngineBridge.adSkipObserver.checkPosition(player)) {
            HushCastEngineBridge.checkSkippedChapterPosition(player)
        }
    }

    fun schedulePositionCallbacks() {
        scheduledMessages.forEach { runCatching { it.cancel() } }
        scheduledMessages.clear()

        val player = mediaSession?.player ?: return

        // Schedule PlayerMessages for auto-skip ads (P5)
        for (range in HushCastEngineBridge.adSkipObserver.getActiveAutoSkipRanges()) {
            val msg = exoPlayer.createMessage { _, _ ->
                HushCastEngineBridge.adSkipObserver.checkPosition(player)
            }
                .setPosition(range.startMs)
                .setLooper(Looper.getMainLooper())
                .setDeleteAfterDelivery(false)
                .send()
            scheduledMessages.add(msg)
        }

        // Schedule PlayerMessages for skipped chapters (P4, P5)
        for (range in HushCastEngineBridge.getSkippedChapterRanges()) {
            val msg = exoPlayer.createMessage { _, _ ->
                HushCastEngineBridge.checkSkippedChapterPosition(player)
            }
                .setPosition(range.startMs)
                .setLooper(Looper.getMainLooper())
                .setDeleteAfterDelivery(false)
                .send()
            scheduledMessages.add(msg)
        }

        // Schedule PlayerMessage for podcast skipEnding (P5)
        val duration = exoPlayer.duration
        if (currentSkipEndingValue > 0 && duration > currentSkipEndingValue * 1000L) {
            val targetMs = duration - (currentSkipEndingValue * 1000L)
            val msg = exoPlayer.createMessage { _, _ ->
                if (exoPlayer.isPlaying && exoPlayer.currentPosition >= targetMs) {
                    exoPlayer.seekTo(exoPlayer.duration)
                }
            }
                .setPosition(targetMs)
                .setLooper(Looper.getMainLooper())
                .setDeleteAfterDelivery(false)
                .send()
            scheduledMessages.add(msg)
        }
    }

    companion object {
        private val downloadedEpisodeEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)

        fun notifyEpisodeDownloaded(episodeId: String) {
            downloadedEpisodeEvents.tryEmit(episodeId)
        }
    }

    fun buildMediaButtons(player: ExoPlayer): List<CommandButton> {
        val seekBack = CommandButton.Builder(
            when (currentSeekBackIncrementMs) {
                5000L -> CommandButton.ICON_SKIP_BACK_5
                10000L -> CommandButton.ICON_SKIP_BACK_10
                15000L -> CommandButton.ICON_SKIP_BACK_15
                30000L -> CommandButton.ICON_SKIP_BACK_30
                else -> CommandButton.ICON_SKIP_BACK
            }
        )
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setCustomIconResId(
                when (currentSeekBackIncrementMs) {
                    5000L -> R.drawable.ic_replay_5
                    10000L -> R.drawable.ic_replay_10
                    30000L -> R.drawable.ic_replay_30
                    else -> R.drawable.ic_replay
                }
            )
            .setDisplayName(getString(R.string.common_action_seek_back))
            .build()

        val seekForward = CommandButton.Builder(
            when (currentSeekForwardIncrementMs) {
                5000L -> CommandButton.ICON_SKIP_FORWARD_5
                10000L -> CommandButton.ICON_SKIP_FORWARD_10
                15000L -> CommandButton.ICON_SKIP_FORWARD_15
                30000L -> CommandButton.ICON_SKIP_FORWARD_30
                else -> CommandButton.ICON_SKIP_FORWARD
            }
        )
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setCustomIconResId(
                when (currentSeekForwardIncrementMs) {
                    5000L -> R.drawable.ic_forward_5
                    10000L -> R.drawable.ic_forward_10
                    30000L -> R.drawable.ic_forward_30
                    else -> R.drawable.ic_forward
                }
            )
            .setDisplayName(getString(R.string.common_action_seek_forward))
            .build()

        val currentSpeed = player.playbackParameters.speed
        val speedLabel = if (kotlin.math.abs(currentSpeed * 100f - kotlin.math.round(currentSpeed * 100f)) < 0.1f && kotlin.math.round(currentSpeed * 100f).toInt() % 10 != 0) {
            String.format(java.util.Locale.US, "%.2fx", currentSpeed)
        } else {
            String.format(java.util.Locale.US, "%.1fx", currentSpeed)
        }
        val playbackSpeed = CommandButton.Builder(CommandButton.ICON_PLAYBACK_SPEED)
            .setSessionCommand(SessionCommand(COMMAND_CYCLE_SPEED, Bundle.EMPTY))
            .setCustomIconResId(speedIconResId(currentSpeed))
            .setDisplayName("${getString(R.string.common_action_toggle_playback_speed)} ($speedLabel)")
            .build()

        return listOf(seekBack, seekForward, playbackSpeed)
    }

    private fun speedIconResId(speed: Float): Int {
        val candidates = listOf(
            0.50f to R.drawable.ic_speed_0_5,
            0.60f to R.drawable.ic_speed_0_6,
            0.70f to R.drawable.ic_speed_0_7,
            0.75f to R.drawable.ic_speed_0_75,
            0.80f to R.drawable.ic_speed_0_8,
            0.90f to R.drawable.ic_speed_0_9,
            1.00f to R.drawable.ic_speed_1_0,
            1.10f to R.drawable.ic_speed_1_1,
            1.20f to R.drawable.ic_speed_1_2,
            1.25f to R.drawable.ic_speed_1_25,
            1.30f to R.drawable.ic_speed_1_3,
            1.40f to R.drawable.ic_speed_1_4,
            1.50f to R.drawable.ic_speed_1_5,
            1.60f to R.drawable.ic_speed_1_6,
            1.70f to R.drawable.ic_speed_1_7,
            1.75f to R.drawable.ic_speed_1_75,
            1.80f to R.drawable.ic_speed_1_8,
            1.90f to R.drawable.ic_speed_1_9,
            2.00f to R.drawable.ic_speed_2_0,
            2.25f to R.drawable.ic_speed_2_25,
            2.50f to R.drawable.ic_speed_2_5,
            2.75f to R.drawable.ic_speed_2_75,
            3.00f to R.drawable.ic_speed_3_0,
        )
        val best = candidates.minByOrNull { kotlin.math.abs(it.first - speed) }
        return if (best != null && kotlin.math.abs(best.first - speed) <= 0.04f) {
            best.second
        } else {
            R.drawable.ic_playback_speed
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    private fun enqueueUpdatePlayState() {
        updatePlayStateHandler.removeCallbacks(updatePlayStateRunnable)
        updatePlayStateHandler.postDelayed(updatePlayStateRunnable, 60 * 1000L)
    }

    private fun updatePlayState() {
        val origin = getOrigin() ?: return
        val episodeId = getEpisodeId() ?: return
        val audioUrl = getAudioUrl() ?: return
        val currentPosition = mediaSession?.player?.currentPosition ?: return
        val duration = mediaSession?.player?.duration ?: return

        if (duration < 1000) return

        val state = (currentPosition / 1000).toInt()
        val remainingSeconds = (duration / 1000) - (currentPosition / 1000)
        val isPlayed = remainingSeconds < 90

        scope.launch(Dispatchers.IO) {
            db.withTransaction {
                if (isPlayed) {
                    db.podcastEpisodePlayStates().savePlayed(
                        episodeId = episodeId,
                        played = true
                    )
                }
                db.podcastEpisodePlayStates().saveState(
                    episodeId = episodeId,
                    state = state.coerceAtLeast(1)
                )
                db.syncActions().addPlayState(
                    origin = origin,
                    episodeId = episodeId,
                    audioUrl = audioUrl,
                    duration = (duration / 1000L).toInt(),
                    state = state,
                    played = isPlayed
                )
            }
        }
    }

    private fun getOrigin() = mediaSession?.player?.currentMediaItem?.getOrigin()
    private fun getEpisodeId() = mediaSession?.player?.currentMediaItem?.getEpisodeId()
    private fun getAudioUrl() = mediaSession?.player?.currentMediaItem?.getAudioUrl()

    private fun onEpisodeStarted(mediaItem: MediaItem?) {
        val origin = mediaItem?.getOrigin() ?: return
        val episodeId = mediaItem.getEpisodeId() ?: return

        mediaItem.getResumeAt()?.let { resumeAt ->
            mediaSession?.player?.seekTo(resumeAt)
        }

        NewPodcastEpisodeNotification.cancel(applicationContext, episodeId)
        loadHushCastForEpisode(episodeId, origin)

        if (lastStartedEpisodeId == episodeId) return
        lastStartedEpisodeId = episodeId

        val player = mediaSession?.player
        scope.launch(Dispatchers.IO) {
            val podcast = db.podcasts().getSync(origin)
            currentSkipEndingValue = podcast?.skipEnding ?: 0

            if (podcast != null && podcast.skipBeginning > 0) {
                withContext(Dispatchers.Main) {
                    if (player != null && player.currentPosition <= 3000L) {
                        player.seekTo(podcast.skipBeginning * 1000L)
                    }
                    schedulePositionCallbacks()
                }
            }

            val last = db.podcastHistory().getLast().first()
            if (last?.episode?.id == episodeId) {
                val timestamp = System.currentTimeMillis()
                if ((timestamp - last.history.timestamp) < 1000L * 60 * 60 * 4) {
                    db.podcastHistory().updateTimestamp(episodeId = episodeId, timestamp = timestamp)
                    return@launch
                }
            }
            db.podcastHistory().insert(origin = origin, episodeId = episodeId)
        }
    }

    private fun registerPlayStateListener() {
        mediaSession?.player?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updatePlayState()
                onEpisodeStarted(mediaItem)
                mediaSession?.let { session ->
                    onUpdateNotification(session, session.player.playWhenReady)
                }
                super.onMediaItemTransition(mediaItem, reason)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    val prevItem = oldPosition.mediaItem
                    if (prevItem != null && prevItem != newPosition.mediaItem) {
                        val prevOrigin = prevItem.getOrigin()
                        val prevEpisodeId = prevItem.getEpisodeId()
                        val prevAudioUrl = prevItem.getAudioUrl()
                        val prevPosSec = (oldPosition.positionMs / 1000L).toInt().coerceAtLeast(1)
                        if (prevOrigin != null && prevEpisodeId != null && prevAudioUrl != null) {
                            scope.launch(Dispatchers.IO) {
                                db.withTransaction {
                                    db.podcastEpisodePlayStates().savePlayed(prevEpisodeId, true)
                                    db.podcastEpisodePlayStates().saveState(prevEpisodeId, prevPosSec)
                                    db.syncActions().addPlayState(
                                        origin = prevOrigin,
                                        episodeId = prevEpisodeId,
                                        audioUrl = prevAudioUrl,
                                        duration = prevPosSec,
                                        state = prevPosSec,
                                        played = true
                                    )
                                }
                            }
                        }
                    }
                } else if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT ||
                    reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                ) {
                    checkActiveSkipsNow()
                }
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val dur = exoPlayer.duration
                    val epId = getEpisodeId()
                    if (dur > 1000L && epId != null) {
                        scope.launch(Dispatchers.IO) {
                            db.podcastEpisodes().updateDuration(epId, (dur / 1000L).toInt())
                        }
                    }
                    checkActiveSkipsNow()
                    schedulePositionCallbacks()
                } else if (playbackState == Player.STATE_ENDED) {
                    getEpisodeId()?.let { episodeId ->
                        val durationSec = exoPlayer.duration.let { if (it > 0) (it / 1000L).toInt() else 1 }
                        val origin = getOrigin()
                        val audioUrl = getAudioUrl()
                        scope.launch(Dispatchers.IO) {
                            db.withTransaction {
                                db.podcastEpisodePlayStates().savePlayed(episodeId = episodeId, played = true)
                                if (origin != null && audioUrl != null) {
                                    db.syncActions().addPlayState(
                                        origin = origin,
                                        episodeId = episodeId,
                                        audioUrl = audioUrl,
                                        duration = durationSec,
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
                if (isPlaying) {
                    val mediaId = exoPlayer.currentMediaItem?.mediaId
                    if (intelligentPlaybackResumptionEnabled &&
                        lastPausedAtMs > 0L &&
                        mediaId != null &&
                        mediaId == lastPausedMediaId
                    ) {
                        val pausedDurationMs = System.currentTimeMillis() - lastPausedAtMs
                        val rewindMs = when {
                            pausedDurationMs >= 3_600_000L -> 15_000L
                            pausedDurationMs >= 300_000L -> 10_000L
                            pausedDurationMs >= 30_000L -> 3_000L
                            else -> 0L
                        }
                        if (rewindMs > 0L && exoPlayer.currentPosition > 1_000L) {
                            exoPlayer.seekTo((exoPlayer.currentPosition - rewindMs).coerceAtLeast(0L))
                        }
                    }
                    lastPausedAtMs = 0L
                    enqueueUpdatePlayState()
                    checkActiveSkipsNow()
                } else {
                    if (exoPlayer.playbackState == Player.STATE_READY && !exoPlayer.playWhenReady) {
                        lastPausedAtMs = System.currentTimeMillis()
                        lastPausedMediaId = exoPlayer.currentMediaItem?.mediaId
                    }
                    updatePlayState()
                    updatePlayStateHandler.removeCallbacks(updatePlayStateRunnable)
                    syncIfEnabled()
                }
                super.onIsPlayingChanged(isPlaying)
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                scope.launch { settingsRepository.behavior.setPlayerPlaybackSpeed(playbackParameters.speed) }
                mediaSession?.setCustomLayout(buildMediaButtons(exoPlayer))
                super.onPlaybackParametersChanged(playbackParameters)
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("PlaybackService", "Player error: ${error.errorCodeName} (${error.message})", error)
                updatePlayStateHandler.removeCallbacks(updatePlayStateRunnable)
                super.onPlayerError(error)
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val epId = getEpisodeId() ?: return
                val id3 = app.podiumpodcasts.podium.api.chapters.EpisodeChapterHelper.extractId3ChaptersFromTracks(tracks)
                if (id3.isNotEmpty()) {
                    val dur = exoPlayer.duration.takeIf { it > 0L } ?: 0L
                    val entities = app.podiumpodcasts.podium.api.chapters.ChapterParser.toEntities(
                        episodeId = epId,
                        source = app.podiumpodcasts.podium.api.db.model.EpisodeChapterModel.SOURCE_ID3,
                        raw = id3,
                        durationMs = dur,
                        forceFirstAtZero = true
                    )
                    if (entities.isNotEmpty()) {
                        scope.launch(Dispatchers.IO) {
                            db.episodeChapters().upsertPublisherChapters(
                                epId,
                                app.podiumpodcasts.podium.api.db.model.EpisodeChapterModel.SOURCE_ID3,
                                entities
                            )
                        }
                    }
                }
                super.onTracksChanged(tracks)
            }

            override fun onMetadata(metadata: androidx.media3.common.Metadata) {
                val epId = getEpisodeId() ?: return
                val id3 = app.podiumpodcasts.podium.api.chapters.EpisodeChapterHelper.extractId3ChaptersFromMetadata(metadata)
                if (id3.isNotEmpty()) {
                    val dur = exoPlayer.duration.takeIf { it > 0L } ?: 0L
                    val entities = app.podiumpodcasts.podium.api.chapters.ChapterParser.toEntities(
                        episodeId = epId,
                        source = app.podiumpodcasts.podium.api.db.model.EpisodeChapterModel.SOURCE_ID3,
                        raw = id3,
                        durationMs = dur,
                        forceFirstAtZero = true
                    )
                    if (entities.isNotEmpty()) {
                        scope.launch(Dispatchers.IO) {
                            db.episodeChapters().upsertPublisherChapters(
                                epId,
                                app.podiumpodcasts.podium.api.db.model.EpisodeChapterModel.SOURCE_ID3,
                                entities
                            )
                        }
                    }
                }
                super.onMetadata(metadata)
            }
        })
    }

    private fun publishSleepTimerExtra() {
        val extras = Bundle().apply {
            putLong(EXTRA_SLEEP_TIMER_TRIGGER, sleepTimerTrigger ?: 0L)
        }
        mediaSession?.setSessionExtras(extras)
    }

    fun stopSleepTimer() {
        sleepTimerTrigger = null
        sleepTimerHandler.removeCallbacks(sleepTimerRunnable)
        sleepTimerHandler.removeCallbacks(sleepTimerFadeRunnable)
        mediaSession?.player?.volume = 1.0f
        publishSleepTimerExtra()
    }

    fun startSleepTimer(trigger: Long) {
        sleepTimerTrigger = trigger
        val delay = (trigger - System.currentTimeMillis()).coerceAtLeast(0L)

        sleepTimerHandler.removeCallbacks(sleepTimerRunnable)
        sleepTimerHandler.removeCallbacks(sleepTimerFadeRunnable)
        sleepTimerHandler.postDelayed(sleepTimerRunnable, delay)
        sleepTimerHandler.post(sleepTimerFadeRunnable)
        publishSleepTimerExtra()
    }

    fun syncIfEnabled() {
        scope.launch {
            if (!settingsRepository.sync.enable.first()) return@launch
            PartialSynchronizationWorker.enqueue(
                context = this@PlaybackService,
                debounceSeconds = 30L
            )
        }
    }

    override fun onDestroy() {
        updatePlayStateHandler.removeCallbacks(updatePlayStateRunnable)
        sleepTimerHandler.removeCallbacksAndMessages(null)
        scheduledMessages.forEach { runCatching { it.cancel() } }
        scheduledMessages.clear()

        stopSleepTimer()
        sessionCallback?.destroy()
        job.cancel()

        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}