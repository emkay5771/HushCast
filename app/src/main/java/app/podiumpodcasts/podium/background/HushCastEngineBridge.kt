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

    private val _pendingUndoEvent = MutableStateFlow<AdSkipEvent?>(null)
    val pendingUndoEvent: StateFlow<AdSkipEvent?> = _pendingUndoEvent.asStateFlow()

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

    @Volatile
    var sponsorSkipSoundEnabled: Boolean = false

    @Volatile
    var sponsorSkipSoundUri: String? = null

    @Volatile
    var currentEpisodeId: String? = null

    @Volatile
    private var skippedChapterRanges: List<app.hushcast.automute.dsp.SkipAwareSeekCalculator.SkipRange> = emptyList()

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

    fun notifyAdSkipped(event: AdSkipEvent) {
        _pendingUndoEvent.value = event
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
        volumeBoostEnabled = settingsRepository.behavior.volumeBoost.firstOrNull() ?: false
        sponsorSkipSoundEnabled = settingsRepository.hushCast.sponsorSkipSoundEnabled.firstOrNull() ?: false
        sponsorSkipSoundUri = settingsRepository.hushCast.sponsorSkipSoundUri.firstOrNull()
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
            playDefaultSkipChime()
        }
    }

    private fun playDefaultSkipChime() {
        runCatching {
            val sampleRate = 44100
            val durationMs = 220
            val numSamples = (sampleRate * durationMs) / 1000
            val samples = ShortArray(numSamples)
            val half = numSamples / 2
            for (i in 0 until numSamples) {
                val freq = if (i < half) 880.0 else 1318.5
                val localIdx = if (i < half) i else (i - half)
                val localLen = if (i < half) half else (numSamples - half)
                val env = kotlin.math.sin(kotlin.math.PI * localIdx / localLen.coerceAtLeast(1))
                val s = kotlin.math.sin(2.0 * kotlin.math.PI * freq * i / sampleRate) * env * 0.55
                samples[i] = (s * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
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
            Thread.sleep(durationMs.toLong() + 400L)
            runCatching { track.stop() }
            track.release()
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
