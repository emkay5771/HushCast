package app.podiumpodcasts.podium.background

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import app.hushcast.automute.dsp.AutoMuteAudioProcessor
import app.hushcast.automute.dsp.HushCastAudioProcessorChain
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.storage.loadResolvedMuteWindowsByEpisode
import app.podiumpodcasts.podium.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Short previews (an episode's soundbites, a show's trailer) on a player of their own.
 *
 * A preview isn't playback of the episode: it doesn't touch Now Playing or Up Next, doesn't save
 * progress or mark anything played, and never starts a download or HushCast processing.  It
 * takes audio focus like any other player, so whatever was playing pauses; when the preview
 * ends, that stays paused.  Starting normal playback (which takes focus back) stops the preview.
 *
 * Word muting applies only when the user turns on "Mute words in previews", and only for
 * episodes HushCast has already processed (their stored mute windows).  Trailers aren't
 * episodes, so they always play as published.
 */
@OptIn(UnstableApi::class)
object PreviewPlayer {

    data class Request(
        val id: String,
        val uri: String,
        val startMs: Long = 0L,
        /** Where the preview stops; null plays to the end (a trailer). */
        val endMs: Long? = null,
        /** The episode whose mute windows apply when muting previews is on; null for a trailer. */
        val episodeId: String? = null,
        val podcastOrigin: String? = null
    )

    data class State(
        val id: String,
        val buffering: Boolean,
        /** Time left, or null until a trailer's length is known. */
        val remainingMs: Long?
    )

    fun soundbiteId(episodeId: String, index: Int) = "soundbite:$episodeId:$index"
    fun trailerId(origin: String) = "trailer:$origin"

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var job: Job? = null

    /** Starts [request], or stops it if it's the preview already playing. */
    fun toggle(context: Context, request: Request) {
        if (_state.value?.id == request.id) stop() else start(context, request)
    }

    fun stop() {
        job?.cancel()
        job = null
        player?.release()
        player = null
        _state.value = null
    }

    /** Stops the current preview if [owns] it (a screen that closes stops only its own preview). */
    fun stopIf(owns: (id: String) -> Boolean) {
        val id = _state.value?.id ?: return
        if (owns(id)) stop()
    }

    private fun stopIfCurrent(id: String) {
        // Posted, so a callback never releases the player from inside its own dispatch, and a
        // late callback from an older preview never stops a newer one.
        scope.launch { if (_state.value?.id == id) stop() }
    }

    private fun start(context: Context, request: Request) {
        stop()
        val appContext = context.applicationContext
        _state.value = State(request.id, buffering = true, remainingMs = request.endMs?.minus(request.startMs))

        job = scope.launch {
            val processor = muteProcessorFor(appContext, request)
            val exo = buildPlayer(appContext, processor)
            player = exo

            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) stopIfCurrent(request.id)
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    // Normal playback started (or a call came in): the preview gives way.
                    if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                        stopIfCurrent(request.id)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    stopIfCurrent(request.id)
                }
            })

            exo.setMediaItem(MediaItem.fromUri(request.uri), request.startMs)
            request.endMs?.let { endMs ->
                exo.createMessage { _, _ -> stopIfCurrent(request.id) }
                    .setLooper(Looper.getMainLooper())
                    .setPosition(endMs)
                    .setDeleteAfterDelivery(true)
                    .send()
            }
            exo.prepare()
            exo.play()

            while (isActive) {
                val endMs = request.endMs ?: exo.duration.takeIf { it != C.TIME_UNSET }
                _state.value = State(
                    id = request.id,
                    buffering = exo.playbackState == Player.STATE_BUFFERING || exo.playbackState == Player.STATE_IDLE,
                    remainingMs = endMs?.let { (it - exo.currentPosition).coerceAtLeast(0L) }
                )
                delay(250L)
            }
        }
    }

    /** A mute processor for [request], or null when the preview plays as published. */
    private suspend fun muteProcessorFor(context: Context, request: Request): AutoMuteAudioProcessor? {
        val episodeId = request.episodeId ?: return null
        val settings = SettingsRepository(context)
        val previewMuting = settings.hushCast.previewMuting.first()
        if (!previewMuting) return null

        val podcastOverride = request.podcastOrigin?.let { settings.hushCast.podcastWordMuting(it).firstOrNull() }
        val globalWordMuting = settings.hushCast.wordMutingEnabled.first()
        val mode = settings.hushCast.muteActionMode.firstOrNull() ?: HushCastEngineBridge.defaultActionMode
        val windows = withContext(Dispatchers.IO) {
            AutoMuteDatabase.getInstance(context).dao().loadResolvedMuteWindowsByEpisode(episodeId, mode)
        }
        if (!previewMutesWords(previewMuting, globalWordMuting, podcastOverride, windows.isNotEmpty())) return null

        return AutoMuteAudioProcessor().apply {
            copySettingsFrom(HushCastEngineBridge.autoMuteProcessor)
            defaultActionMode = mode
            isEnabled = true
            updateWindows(windows)
        }
    }

    private fun buildPlayer(context: Context, processor: AutoMuteAudioProcessor?): ExoPlayer {
        val renderersFactory = if (processor == null) {
            DefaultRenderersFactory(context)
        } else {
            object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(HushCastAudioProcessorChain(processor))
                    .build()
            }
        }
        // Index seeking like the main player: a soundbite must start (and its mute windows line
        // up) at the exact second, also in variable-bitrate MP3s.
        val extractors = DefaultExtractorsFactory()
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractors))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
}

/**
 * Whether a soundbite preview mutes words: only with "Mute words in previews" on, word muting on
 * for the podcast (its override, else the global switch), and mute windows to apply (the episode
 * has been processed).
 */
internal fun previewMutesWords(
    previewMuting: Boolean,
    globalWordMuting: Boolean,
    podcastOverride: Boolean?,
    hasMuteWindows: Boolean
): Boolean = previewMuting && (podcastOverride ?: globalWordMuting) && hasMuteWindows
