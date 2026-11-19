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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
/** Which kind of sleep timer runs: [SleepTimerModes.TIME], [SleepTimerModes.END_OF_EPISODE] or [SleepTimerModes.END_OF_CHAPTER]. */
const val EXTRA_SLEEP_TIMER_MODE = "sleep_timer_mode"
/** For "End of chapter": the playback position (ms) the timer stops at; follows seeks. */
const val EXTRA_SLEEP_TIMER_END_MS = "sleep_timer_end_ms"

/** Sleep timer kinds shared by the service and the UI (also the strings the session command sends). */
object SleepTimerModes {
    const val TIME = "TIME"
    const val END_OF_EPISODE = "END_OF_EPISODE"
    const val END_OF_CHAPTER = "END_OF_CHAPTER"

    fun isPositionBased(mode: String?): Boolean = mode == END_OF_EPISODE || mode == END_OF_CHAPTER
}

/**
 * Which playback speed applies (I7): the podcast's own speed when it has one, else the global
 * speed.  A podcast speed of 0 (or less) means "no override", which is how the settings store it.
 */
object PlaybackSpeedResolver {
    enum class Scope { GLOBAL, PODCAST }

    fun hasOverride(podcastSpeed: Float?): Boolean =
        podcastSpeed != null && !podcastSpeed.isNaN() && podcastSpeed > 0f

    fun resolve(podcastSpeed: Float?, globalSpeed: Float?): Float = when {
        hasOverride(podcastSpeed) -> podcastSpeed!!
        globalSpeed != null && !globalSpeed.isNaN() && globalSpeed > 0f -> globalSpeed
        else -> 1f
    }

    /** Where a speed the user picks should be saved when they haven't chosen a scope explicitly. */
    fun scopeFor(origin: String?, podcastSpeed: Float?): Scope =
        if (!origin.isNullOrBlank() && hasOverride(podcastSpeed)) Scope.PODCAST else Scope.GLOBAL
}

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
    private var sleepTimerChapterMessage: PlayerMessage? = null
    private var sleepTimerEndOfEpisode: Boolean = false
    /** Kept so stopping or replacing the timer can remove them (they used to leak and cancel later timers). */
    private var sleepTimerEndOfEpisodeListener: Player.Listener? = null
    private var sleepTimerChapterListener: Player.Listener? = null
    /** [SleepTimerModes] value of the running timer, published so the UI can count down from the position. */
    private var sleepTimerMode: String? = null
    /** "End of chapter" target position; -1 when not used. */
    private var sleepTimerEndPositionMs: Long = -1L
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
            .setBufferDurationsMs(60_000, 300_000, 1_500, 3_000)
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
            val trimSilence = settingsRepository.behavior.trimSilence.first()
            currentSeekBackIncrementMs = backInc
            currentSeekForwardIncrementMs = fwdInc
            exoPlayer.setSeekBackIncrementMs(backInc)
            exoPlayer.setSeekForwardIncrementMs(fwdInc)
            // Speed is applied by the per-item speed observer below (I7).
            exoPlayer.skipSilenceEnabled = trimSilence
            mediaSession?.setMediaButtonPreferences(buildMediaButtons(exoPlayer))
        }

        // I7: each item plays at its podcast's speed, or the global speed when the podcast has none.
        // Re-resolved on every item change and whenever either setting changes.
        scope.launch {
            speedItemKey
                .flatMapLatest { key ->
                    val origin = key?.origin
                    val podcastFlow = if (origin.isNullOrBlank()) flowOf(0f)
                    else settingsRepository.behavior.podcastPlaybackSpeed(origin)
                    combine(podcastFlow, settingsRepository.behavior.playerPlaybackSpeed) { podcast, global ->
                        PlaybackSpeedResolver.resolve(podcast, global)
                    }
                }
                .collect { target ->
                    if (kotlin.math.abs(exoPlayer.playbackParameters.speed - target) > 0.001f) {
                        exoPlayer.setPlaybackSpeed(target)
                    }
                }
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
            settingsRepository.hushCast.bleepStyle.distinctUntilChanged().collect { HushCastEngineBridge.bleepStyle = it }
        }
        scope.launch {
            settingsRepository.hushCast.skipSound.distinctUntilChanged().collect { HushCastEngineBridge.skipSound = it }
        }
        scope.launch {
            settingsRepository.hushCast.muteCustomSoundUri.distinctUntilChanged().collect {
                HushCastEngineBridge.loadCustomMuteSound(this@PlaybackService, it)
            }
        }
        scope.launch {
            combine(
                activeEpisodeIdFlow,
                settingsRepository.hushCast.muteActionMode.distinctUntilChanged()
            ) { epId, mode ->
                HushCastEngineBridge.defaultActionMode = mode
                epId to mode
            }
                .flatMapLatest { (epId, mode) ->
                    val activeId = epId ?: exoPlayer.currentMediaItem?.getEpisodeId() ?: HushCastEngineBridge.currentEpisodeId
                    if (activeId.isNullOrBlank()) {
                        flowOf(emptyList())
                    } else {
                        autoMuteDb.dao().observeMuteSegmentsByEpisode(activeId).distinctUntilChanged().let { segFlow ->
                            kotlinx.coroutines.flow.flow {
                                segFlow.collect {
                                    val updated = withContext(Dispatchers.IO) {
                                        autoMuteDb.dao().loadResolvedMuteWindowsByEpisode(activeId, mode)
                                    }
                                    emit(updated)
                                }
                            }
                        }
                    }
                }
                .collect { updated ->
                    if (!activeEpisodeIdFlow.value.isNullOrBlank()) {
                        HushCastEngineBridge.autoMuteProcessor.updateWindows(updated)
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
                val epId = mediaSession?.player?.currentMediaItem?.getEpisodeId()
                HushCastEngineBridge.notifyAdSkipped(epId, event)
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
    private val activeEpisodeIdFlow = MutableStateFlow<String?>(null)

    /** The playing item, for the speed observer (I7).  A new episode re-applies its speed. */
    private data class SpeedItemKey(val origin: String?, val episodeId: String?)
    private val speedItemKey = MutableStateFlow<SpeedItemKey?>(null)

    /**
     * A speed the user chose outside the speed sheet (e.g. the notification's speed button): apply
     * it and save it to the scope in use, the podcast's own speed when it has one, else the global one.
     */
    fun applyUserSpeed(speed: Float) {
        if (speed <= 0f) return
        mediaSession?.player?.setPlaybackSpeed(speed)
        val origin = getOrigin()
        scope.launch {
            val podcastSpeed = origin?.let { settingsRepository.behavior.podcastPlaybackSpeed(it).first() }
            when (PlaybackSpeedResolver.scopeFor(origin, podcastSpeed)) {
                PlaybackSpeedResolver.Scope.PODCAST -> settingsRepository.behavior.setPodcastPlaybackSpeed(origin!!, speed)
                PlaybackSpeedResolver.Scope.GLOBAL -> settingsRepository.behavior.setPlayerPlaybackSpeed(speed)
            }
        }
    }

    private fun loadHushCastForEpisode(episodeId: String, origin: String) {
        HushCastEngineBridge.currentEpisodeId = episodeId
        activeEpisodeIdFlow.value = episodeId
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

            // SRT/VTT transcripts only have cue-level times: fix the times around words that a
            // rule matches (a short Whisper pass, only when needed).
            epBundle?.episode?.craftDownloadFile(this@PlaybackService)?.takeIf { it.exists() }?.let { file ->
                app.hushcast.automute.worker.EpisodeAnalysisWorker.enqueueRealignIfNeeded(
                    this@PlaybackService, episodeId, file.absolutePath, HushCastEngineBridge.defaultActionMode
                )
            }

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
            .setSlots(CommandButton.SLOT_BACK)
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
            .setSlots(CommandButton.SLOT_FORWARD)
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
            .setSlots(CommandButton.SLOT_OVERFLOW)
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
                    db.podcastHistory().updateTimestamp(id = last.history.id, timestamp = timestamp)
                    return@launch
                }
            }
            db.podcastHistory().insert(origin = origin, episodeId = episodeId)
        }
    }

    private fun registerPlayStateListener() {
        mediaSession?.player?.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                HushCastEngineBridge.clearPendingUndo()
                speedItemKey.value = SpeedItemKey(mediaItem?.getOrigin(), mediaItem?.getEpisodeId())
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
                // S11: on-device analysis runs on half the threads while audio plays.
                app.hushcast.automute.engine.AnalysisThrottle.playbackActive = isPlaying
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
                // I7: no settings write here.  This fires for per-podcast speeds and slider previews
                // too, and used to overwrite the global speed with them.  The speed sheet and
                // applyUserSpeed() save to the chosen scope themselves.
                mediaSession?.setMediaButtonPreferences(buildMediaButtons(exoPlayer))
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
            if (sleepTimerTrigger != null) {
                putString(EXTRA_SLEEP_TIMER_MODE, sleepTimerMode ?: SleepTimerModes.TIME)
                putLong(EXTRA_SLEEP_TIMER_END_MS, sleepTimerEndPositionMs)
            }
        }
        mediaSession?.setSessionExtras(extras)
    }

    fun stopSleepTimer() {
        sleepTimerTrigger = null
        sleepTimerMode = null
        sleepTimerEndPositionMs = -1L
        sleepTimerHandler.removeCallbacks(sleepTimerRunnable)
        sleepTimerHandler.removeCallbacks(sleepTimerFadeRunnable)
        sleepTimerChapterMessage?.let { runCatching { it.cancel() } }
        sleepTimerChapterMessage = null
        sleepTimerEndOfEpisodeListener?.let { exoPlayer.removeListener(it) }
        sleepTimerEndOfEpisodeListener = null
        sleepTimerChapterListener?.let { exoPlayer.removeListener(it) }
        sleepTimerChapterListener = null
        if (sleepTimerEndOfEpisode) {
            sleepTimerEndOfEpisode = false
            exoPlayer.pauseAtEndOfMediaItems = false
        }
        mediaSession?.player?.volume = 1.0f
        publishSleepTimerExtra()
    }

    fun startSleepTimer(trigger: Long) {
        stopSleepTimer()
        sleepTimerTrigger = trigger
        sleepTimerMode = SleepTimerModes.TIME
        val delay = (trigger - System.currentTimeMillis()).coerceAtLeast(0L)

        sleepTimerHandler.postDelayed(sleepTimerRunnable, delay)
        sleepTimerHandler.post(sleepTimerFadeRunnable)
        publishSleepTimerExtra()
    }

    fun startSleepTimerEndOfEpisode(approxTrigger: Long = 0L) {
        stopSleepTimer()
        sleepTimerEndOfEpisode = true
        // The UI counts down from the position; the trigger only says a timer is on.
        sleepTimerTrigger = approxTrigger.takeIf { it > 0L } ?: System.currentTimeMillis()
        sleepTimerMode = SleepTimerModes.END_OF_EPISODE
        exoPlayer.pauseAtEndOfMediaItems = true
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    stopSleepTimer()
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                stopSleepTimer()
            }
        }
        sleepTimerEndOfEpisodeListener = listener
        exoPlayer.addListener(listener)
        publishSleepTimerExtra()
    }

    fun startSleepTimerAtPosition(positionMs: Long, approxTrigger: Long = 0L) {
        stopSleepTimer()
        sleepTimerTrigger = approxTrigger.takeIf { it > 0L } ?: System.currentTimeMillis()
        sleepTimerMode = SleepTimerModes.END_OF_CHAPTER
        armChapterEndMessage(positionMs)
        // "End of chapter" follows the listener: a seek re-arms it for the chapter now playing, and
        // another episode cancels it (the message is tied to the item it was created on).
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                stopSleepTimer()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason != Player.DISCONTINUITY_REASON_SEEK &&
                    reason != Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT &&
                    reason != Player.DISCONTINUITY_REASON_SKIP
                ) return
                val pos = newPosition.positionMs
                val chapter = HushCastEngineBridge.currentResolvedChapters
                    .firstOrNull { pos >= it.startMs && pos < it.endMs }
                if (chapter == null) {
                    stopSleepTimer()
                    return
                }
                sleepTimerChapterMessage?.let { runCatching { it.cancel() } }
                armChapterEndMessage(chapter.endMs)
                publishSleepTimerExtra()
            }
        }
        sleepTimerChapterListener = listener
        exoPlayer.addListener(listener)
        publishSleepTimerExtra()
    }

    private fun armChapterEndMessage(positionMs: Long) {
        sleepTimerEndPositionMs = positionMs.coerceAtLeast(0L)
        sleepTimerChapterMessage = exoPlayer.createMessage { _, _ ->
            Handler(Looper.getMainLooper()).post {
                sleepTimerChapterMessage = null
                exoPlayer.pause()
                stopSleepTimer()
            }
        }
            .setLooper(Looper.getMainLooper())
            .setPosition(positionMs.coerceAtLeast(0L))
            .setDeleteAfterDelivery(true)
            .send()
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
        app.hushcast.automute.engine.AnalysisThrottle.playbackActive = false
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