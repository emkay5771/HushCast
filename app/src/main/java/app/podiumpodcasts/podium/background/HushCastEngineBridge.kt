package app.podiumpodcasts.podium.background

import app.hushcast.automute.dsp.AdPolicyMode
import app.hushcast.automute.dsp.AdSkipEvent
import app.hushcast.automute.dsp.AdSkipPlaybackObserver
import app.hushcast.automute.dsp.AutoMuteAudioProcessor
import app.hushcast.automute.dsp.MuteActionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Minimal bridge between :core:automute-engine and Podium's PlaybackService / UI.
 * Keeps fork isolation clean so upstream Podium changes remain easy to merge.
 */
object HushCastEngineBridge {
    val autoMuteProcessor = AutoMuteAudioProcessor()
    val adSkipObserver = AdSkipPlaybackObserver()

    val adSkipEvents: SharedFlow<AdSkipEvent>
        get() = adSkipObserver.skipEvents

    data class PendingUndoAdSkip(
        val episodeId: String?,
        val event: AdSkipEvent
    )

    private val _pendingUndoEvent = MutableStateFlow<PendingUndoAdSkip?>(null)
    val pendingUndoEvent: StateFlow<PendingUndoAdSkip?> = _pendingUndoEvent.asStateFlow()

    private val _isCastingExternal = MutableStateFlow(false)
    val isCastingExternal: StateFlow<Boolean> = _isCastingExternal.asStateFlow()

    var defaultActionMode: MuteActionMode
        get() = autoMuteProcessor.defaultActionMode
        set(value) {
            autoMuteProcessor.defaultActionMode = value
        }

    var wordMutingEnabled: Boolean
        get() = autoMuteProcessor.isEnabled
        set(value) {
            autoMuteProcessor.isEnabled = value
        }

    /** Built-in tone for the BEEP mute mode. */
    var bleepStyle: app.hushcast.automute.dsp.BleepStyle
        get() = autoMuteProcessor.bleepStyle
        set(value) {
            autoMuteProcessor.bleepStyle = value
        }

    /** Built-in sound for skipped ads, used when no custom skip sound is chosen. */
    @Volatile
    var skipSound: app.podiumpodcasts.podium.SkipSound = app.podiumpodcasts.podium.SkipSound.WHOOSH

    var volumeBoostEnabled: Boolean
        get() = autoMuteProcessor.volumeBoostEnabled
        set(value) {
            autoMuteProcessor.volumeBoostEnabled = value
        }

    var defaultAdPolicy: AdPolicyMode = AdPolicyMode.AUTO_SKIP

    var autoSkipScope: app.hushcast.automute.dsp.AdAutoSkipScope
        get() = adSkipObserver.autoSkipScope
        set(value) {
            adSkipObserver.autoSkipScope = value
        }

    var autoSkipClasses: Set<String>
        get() = adSkipObserver.autoSkipClasses
        set(value) {
            adSkipObserver.autoSkipClasses = value
        }

    @Volatile
    var sponsorSkipSoundEnabled: Boolean = false

    @Volatile
    var sponsorSkipSoundUri: String? = null

    @Volatile
    var currentEpisodeId: String? = null

    @Volatile
    var currentResolvedChapters: List<app.podiumpodcasts.podium.api.chapters.ResolvedChapter> = emptyList()

    @Volatile
    private var skippedChapterRanges: List<app.hushcast.automute.dsp.SkipAwareSeekCalculator.SkipRange> = emptyList()

    fun updateSkippedChapterRangesResolved(
        episodeId: String,
        chapters: List<app.podiumpodcasts.podium.api.chapters.ResolvedChapter>,
        skippedStarts: Set<Long>
    ) {
        if (episodeId != currentEpisodeId && !currentEpisodeId.isNullOrBlank()) return
        currentResolvedChapters = chapters
        if (chapters.isEmpty() || skippedStarts.isEmpty()) {
            skippedChapterRanges = emptyList()
            return
        }
        skippedChapterRanges = chapters
            .filter { it.publishedStartMs in skippedStarts && it.endMs > it.startMs }
            .map { app.hushcast.automute.dsp.SkipAwareSeekCalculator.SkipRange(it.startMs, it.endMs) }
    }

    fun updateSkippedChapterRanges(
        episodeId: String,
        chapters: List<app.hushcast.automute.desktop.DesktopChapterDto>,
        skippedStarts: Set<Long>,
        durationMs: Long
    ) {
        if (episodeId != currentEpisodeId && !currentEpisodeId.isNullOrBlank()) return
        if (chapters.isEmpty() || skippedStarts.isEmpty()) {
            skippedChapterRanges = emptyList()
            return
        }
        val sorted = chapters.sortedBy { it.startMs }
        val ranges = ArrayList<app.hushcast.automute.dsp.SkipAwareSeekCalculator.SkipRange>()
        for (i in sorted.indices) {
            val ch = sorted[i]
            if (ch.startMs in skippedStarts) {
                val endMs = sorted.getOrNull(i + 1)?.startMs ?: durationMs.coerceAtLeast(ch.startMs)
                if (endMs > ch.startMs) {
                    ranges.add(app.hushcast.automute.dsp.SkipAwareSeekCalculator.SkipRange(ch.startMs, endMs))
                }
            }
        }
        skippedChapterRanges = ranges
    }

    fun clearSkippedChapterRanges() {
        currentResolvedChapters = emptyList()
        skippedChapterRanges = emptyList()
    }

    fun getSkippedChapterRanges(): List<app.hushcast.automute.dsp.SkipAwareSeekCalculator.SkipRange> =
        skippedChapterRanges

    fun checkSkippedChapterPosition(player: androidx.media3.common.Player): Boolean {
        if (!player.isPlaying) return false
        val pos = player.currentPosition
        for (range in skippedChapterRanges) {
            if (pos in range.startMs until (range.endMs - 500L)) {
                player.seekTo(range.endMs)
                return true
            }
        }
        return false
    }

    fun getActiveSkipRanges(): List<app.hushcast.automute.dsp.SkipAwareSeekCalculator.SkipRange> {
        return adSkipObserver.getActiveAutoSkipRanges() + skippedChapterRanges
    }

    fun notifyAdSkipped(episodeId: String?, event: AdSkipEvent) {
        _pendingUndoEvent.value = PendingUndoAdSkip(episodeId = episodeId, event = event)
    }

    fun isUndoValid(
        pending: PendingUndoAdSkip?,
        currentEpisodeId: String?,
        positionMs: Long
    ): Boolean {
        if (pending == null) return false
        if (pending.episodeId == null || currentEpisodeId == null || pending.episodeId != currentEpisodeId) {
            return false
        }
        val jumpedTo = pending.event.jumpedToMs
        return positionMs in (jumpedTo - 1_000L)..(jumpedTo + 8_000L)
    }

    fun clearPendingUndo() {
        _pendingUndoEvent.value = null
    }

    fun setCastingExternal(casting: Boolean) {
        _isCastingExternal.value = casting
    }

    suspend fun loadFromSettings(
        settingsRepository: app.podiumpodcasts.podium.SettingsRepository,
        context: android.content.Context? = null
    ) {
        wordMutingEnabled = settingsRepository.hushCast.wordMutingEnabled.firstOrNull() ?: true
        defaultActionMode = settingsRepository.hushCast.muteActionMode.firstOrNull()
            ?: MuteActionMode.HARD_SILENCE
        defaultAdPolicy = settingsRepository.hushCast.adPolicyMode.firstOrNull()
            ?: AdPolicyMode.AUTO_SKIP
        autoSkipScope = settingsRepository.hushCast.adAutoSkipScope.firstOrNull()
            ?: app.hushcast.automute.dsp.AdAutoSkipScope.HIGH_CONFIDENCE_ONLY
        autoSkipClasses = settingsRepository.hushCast.adAutoSkipClasses.firstOrNull()
            ?: setOf("sponsor_ad")
        volumeBoostEnabled = settingsRepository.behavior.volumeBoost.firstOrNull() ?: false
        sponsorSkipSoundEnabled = settingsRepository.hushCast.sponsorSkipSoundEnabled.firstOrNull() ?: false
        sponsorSkipSoundUri = settingsRepository.hushCast.sponsorSkipSoundUri.firstOrNull()
        bleepStyle = settingsRepository.hushCast.bleepStyle.firstOrNull() ?: app.hushcast.automute.dsp.BleepStyle.DEFAULT
        skipSound = settingsRepository.hushCast.skipSound.firstOrNull() ?: app.podiumpodcasts.podium.SkipSound.WHOOSH
        val muteUri = settingsRepository.hushCast.muteCustomSoundUri.firstOrNull()
        if (context != null) {
            loadCustomMuteSound(context, muteUri)
        }
    }

    fun loadCustomMuteSound(context: android.content.Context, uriString: String?) {
        if (uriString.isNullOrBlank()) {
            autoMuteProcessor.setCustomSoundPcm(null)
            return
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val decoded = decodeUriToMonoPcm(context, android.net.Uri.parse(uriString))
            if (decoded != null) {
                autoMuteProcessor.setCustomSoundPcm(decoded.first, decoded.second)
            } else {
                autoMuteProcessor.setCustomSoundPcm(null)
            }
        }
    }

    fun playSponsorSkipIndicator(context: android.content.Context, forceTest: Boolean = false) {
        if (!forceTest && !sponsorSkipSoundEnabled) return
        val customUri = sponsorSkipSoundUri
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            if (!customUri.isNullOrBlank()) {
                val played = runCatching {
                    val mp = android.media.MediaPlayer().apply {
                        setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        setDataSource(context, android.net.Uri.parse(customUri))
                        setVolume(0.75f, 0.75f)
                        prepare()
                        setOnCompletionListener { it.release() }
                        setOnErrorListener { player, _, _ -> player.release(); true }
                        start()
                    }
                    mp != null
                }.getOrDefault(false)
                if (played) return@launch
            }
            playBuiltInSkipSound(context, skipSound)
        }
    }

    /** Plays one built-in skip sound (a short clip in res/raw, mastered below speech level). */
    fun playBuiltInSkipSound(context: android.content.Context, sound: app.podiumpodcasts.podium.SkipSound) {
        val resId = when (sound) {
            app.podiumpodcasts.podium.SkipSound.WHOOSH -> app.podiumpodcasts.podium.R.raw.skip_whoosh
            app.podiumpodcasts.podium.SkipSound.TWO_NOTE -> app.podiumpodcasts.podium.R.raw.skip_two_note
        }
        runCatching {
            val afd = context.resources.openRawResourceFd(resId) ?: return
            android.media.MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                afd.use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                setOnCompletionListener { it.release() }
                setOnErrorListener { player, _, _ -> player.release(); true }
                prepare()
                start()
            }
        }
    }

    /** "Test" button for a custom sound. */
    private var customPreviewPlayer: android.media.MediaPlayer? = null
    private val previewHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val stopCustomPreview = Runnable { releaseCustomPreview() }

    @Synchronized
    private fun releaseCustomPreview() {
        customPreviewPlayer?.let { runCatching { it.stop() }; runCatching { it.release() } }
        customPreviewPlayer = null
    }

    /**
     * Settings "Test" button for a custom mute sound.  A long file (someone picked a song) is cut off
     * after 5 seconds, and a second tap replaces the first preview instead of playing over it.
     */
    fun previewCustomSound(context: android.content.Context, uri: String) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            previewHandler.removeCallbacks(stopCustomPreview)
            releaseCustomPreview()
            runCatching {
                val player = android.media.MediaPlayer().apply {
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(context.applicationContext, android.net.Uri.parse(uri))
                    setOnCompletionListener { releaseCustomPreview() }
                    setOnErrorListener { _, _, _ -> releaseCustomPreview(); true }
                    prepare()
                }
                synchronized(this@HushCastEngineBridge) { customPreviewPlayer = player }
                player.start()
                previewHandler.postDelayed(stopCustomPreview, 5_000L)
            }.onFailure { releaseCustomPreview() }
        }
    }

    /** Settings "Test" button for a skip sound. */
    fun previewSkipSound(context: android.content.Context, sound: app.podiumpodcasts.podium.SkipSound) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            playBuiltInSkipSound(context.applicationContext, sound)
        }
    }

    /**
     * Settings "Test" button for a mute tone.  Uses the same generator as playback
     * ([app.hushcast.automute.dsp.BleepTone]), so it sounds exactly like a muted word.
     */
    fun previewBleep(style: app.hushcast.automute.dsp.BleepStyle) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                val sampleRate = 44100
                val samples = app.hushcast.automute.dsp.BleepTone.renderPreview(style, sampleRate, durationMs = 600)
                val track = android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .build()
                track.write(samples, 0, samples.size)
                track.play()
                kotlinx.coroutines.delay(600L + 300L)
                runCatching { track.stop() }
                track.release()
            }
        }
    }

    private fun decodeUriToMonoPcm(
        context: android.content.Context,
        uri: android.net.Uri
    ): Pair<FloatArray, Int>? {
        return runCatching {
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var format: android.media.MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || format == null) {
                extractor.release()
                return null
            }
            extractor.selectTrack(audioTrackIndex)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME)!!
            val sampleRate = if (format.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            val channels = if (format.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            } else 1
            val maxSamples = sampleRate * 5 // cap at 5 seconds

            val codec = android.media.MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val outSamples = ArrayList<Float>(sampleRate)
            val info = android.media.MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && outSamples.size < maxSamples) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(5000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 5000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    val shortBuf = outBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    while (shortBuf.remaining() >= channels && outSamples.size < maxSamples) {
                        var sum = 0f
                        for (c in 0 until channels) {
                            sum += shortBuf.get().toFloat() / 32768f
                        }
                        outSamples.add((sum / channels) * 0.45f)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }
            codec.stop()
            codec.release()
            extractor.release()
            if (outSamples.isEmpty()) null else outSamples.toFloatArray() to sampleRate
        }.getOrNull()
    }
}
