package app.hushcast.automute.pipeline

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.engine.AudioDecoder
import app.hushcast.automute.engine.Decoder
import app.hushcast.automute.engine.Embedder
import app.hushcast.automute.engine.FrameExtractor
import app.hushcast.automute.engine.Frames
import app.hushcast.automute.engine.HushCastPrefs
import app.hushcast.automute.engine.ModelManager
import app.hushcast.automute.engine.PcmBuffer
import app.hushcast.automute.engine.Segment
import app.hushcast.automute.engine.SegmentFeatures
import app.hushcast.automute.engine.Tagger
import app.hushcast.automute.engine.Transcriber
import app.hushcast.automute.engine.VadDetector
import app.hushcast.automute.engine.Word
import app.hushcast.automute.hash.StreamingSha256
import app.hushcast.automute.matcher.WordMatcher
import app.hushcast.automute.storage.AdSegmentEntity
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.storage.BlobCodec
import app.hushcast.automute.storage.EpisodeAnalysisStatusEntity
import app.hushcast.automute.storage.EpisodeWordIndexEntity
import app.hushcast.automute.storage.MuteSegmentEntity
import app.hushcast.automute.storage.WordIndexBlob
import app.hushcast.automute.storage.toDomain
import app.hushcast.automute.transcript.ParsedTranscript
import app.hushcast.automute.transcript.TranscriptParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.yield
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.math.abs

data class AnalysisProgress(
    val percent: Int,
    val stage: String,
    val badge: ReadinessBadge
)

data class AnalysisResult(
    val fileSha256: String,
    val badge: ReadinessBadge,
    val wordCount: Int,
    val muteCount: Int,
    val adCount: Int,
    val peakRssMb: Int,
    val durationMs: Long
)

/**
 * Sequential, memory-bounded on-device analysis pipeline:
 * 1. Verifies/computes file SHA-256 and checks required models are already downloaded.
 * 2. Streams 180s+5s overlapping chunks through whisper.cpp while extracting 24 kHz acoustic frames.
 * 3. Saves WordIndexBlob + MuteSegments atomically and unloads Whisper before loading Qwen3.
 * 4. If full mode: embeds segments with Qwen3, unloads Embedder, runs ad_tagger.onnx, and saves AdSegments.
 */
class AutoMuteAnalyzer(
    private val context: Context,
    private val db: AutoMuteDatabase = AutoMuteDatabase.getInstance(context),
    private val modelManager: ModelManager = ModelManager(context)
) {
    private val dao = db.dao()

    suspend fun analyzeEpisode(
        episodeId: String,
        audioFile: File,
        knownSha256: String? = null,
        quickWordMuteOnly: Boolean = false,
        feedTranscriptRaw: String? = null,
        feedTranscriptMime: String = "",
        prePadMs: Long = 80L,
        postPadMs: Long = 100L,
        defaultActionMode: MuteActionMode = MuteActionMode.HARD_SILENCE,
        onProgress: (AnalysisProgress) -> Unit = {}
    ): AnalysisResult {
        var stage = "Starting"
        try {
            return runPipeline(
                episodeId, audioFile, knownSha256, quickWordMuteOnly, feedTranscriptRaw,
                feedTranscriptMime, prePadMs, postPadMs, defaultActionMode, onProgress
            ) { stage = it }
        } catch (c: CancellationException) {
            android.util.Log.w("HushCastPerf", "Analysis cancelled during [$stage]")
            throw c
        } catch (t: Throwable) {
            throw IllegalStateException("[$stage] ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    private suspend fun runPipeline(
        episodeId: String,
        audioFile: File,
        knownSha256: String?,
        quickWordMuteOnly: Boolean,
        feedTranscriptRaw: String?,
        feedTranscriptMime: String,
        prePadMs: Long,
        postPadMs: Long,
        defaultActionMode: MuteActionMode,
        onProgress: (AnalysisProgress) -> Unit,
        setStage: (String) -> Unit
    ): AnalysisResult {
        val startTime = System.currentTimeMillis()
        var peakRssMb = currentRssMb()
        var currentSha = knownSha256?.takeIf { it.isNotBlank() } ?: ""
        var encoderDelayMs = 0
        val job = currentCoroutineContext()[Job]
        val shouldAbort: () -> Boolean = { job?.isActive == false }

        var lastDbWriteMs = 0L

        suspend fun writeStatus(
            pct: Int,
            stage: String,
            badge: ReadinessBadge,
            force: Boolean = true,
            durationMs: Long = 0L
        ) {
            setStage(stage)
            peakRssMb = maxOf(peakRssMb, currentRssMb())
            onProgress(AnalysisProgress(pct, stage, badge))
            val now = System.currentTimeMillis()
            if (force || now - lastDbWriteMs >= STATUS_WRITE_INTERVAL_MS) {
                lastDbWriteMs = now
                dao.upsertStatus(
                    EpisodeAnalysisStatusEntity(
                        episodeId = episodeId,
                        fileSha256 = currentSha,
                        badge = badge.name,
                        progressPercent = pct,
                        stageLabel = stage,
                        peakRssMb = peakRssMb,
                        analysisDurationMs = durationMs,
                        encoderDelayMs = encoderDelayMs
                    )
                )
            }
        }

        // E3: Fail fast if word-mute models are missing; fall back to word-mute-only if only ad models are missing
        if (!modelManager.areWordMuteModelsReady()) {
            error("AI models missing. Download models on Wi-Fi in HushCast setup.")
        }
        val effectiveQuickWordMuteOnly = quickWordMuteOnly || !modelManager.areAdModelsReady()

        writeStatus(2, "Verifying audio file", ReadinessBadge.ANALYZING)
        val sha256 = knownSha256?.takeIf { it.isNotBlank() } ?: StreamingSha256.hashFile(audioFile)
        currentSha = sha256

        val existingIndex = dao.getWordIndex(sha256)
        var words: List<Word>
        var segments: List<Segment>
        var frames: Frames? = null

        if (existingIndex != null) {
            val blob = BlobCodec.decompress(existingIndex.compressedBlob)
            words = blob.words
            segments = blob.segments
            encoderDelayMs = existingIndex.encoderDelayMs
            android.util.Log.i("HushCastPerf", "Reusing cached transcript (words=${words.size}, segs=${segments.size}) quick=$effectiveQuickWordMuteOnly")

            if (!effectiveQuickWordMuteOnly && segments.isNotEmpty()) {
                writeStatus(5, "Extracting acoustic features", ReadinessBadge.WORD_MUTE_READY)
                val extractor = FrameExtractor()
                var lastPct = 5
                val info = AudioDecoder.decode(
                    path = audioFile.absolutePath,
                    frames = extractor,
                    pcm16k = null,
                    shouldAbort = shouldAbort
                ) { fraction ->
                    val pct = (5 + (fraction * 54f).toInt()).coerceIn(5, 59)
                    if (pct > lastPct) {
                        lastPct = pct
                        onProgress(AnalysisProgress(pct, "Preparing audio ($pct%)", ReadinessBadge.WORD_MUTE_READY))
                    }
                }
                encoderDelayMs = info.encoderDelayMs
                frames = extractor.finish()
            }
        } else {
            val parsedFeed = feedTranscriptRaw?.let {
                runCatching { TranscriptParser.parse(it, feedTranscriptMime) }.getOrNull()
            }?.takeIf { it.segments.isNotEmpty() && it.hasWordLevelTimings }

            val paths = modelManager.getPaths()
            val useFastModel = HushCastPrefs.isFastModelEnabled(context)
            val useFlashAttn = HushCastPrefs.isFastAttentionEnabled(context)
            val whisperModelPath = if (useFastModel) paths.whisperTinyEn.absolutePath else paths.whisperBaseEn.absolutePath
            val vadModelStr = paths.sileroVad.takeIf { it.exists() }?.absolutePath

            val extractor = if (!quickWordMuteOnly) FrameExtractor() else null

            if (parsedFeed != null) {
                writeStatus(5, "Decoding audio", ReadinessBadge.ANALYZING)
                val pcm = PcmBuffer()
                try {
                    var lastDecodePct = 5
                    val info = AudioDecoder.decode(
                        path = audioFile.absolutePath,
                        frames = extractor,
                        pcm16k = pcm,
                        shouldAbort = shouldAbort
                    ) { fraction ->
                        val pct = (5 + (fraction * 20f).toInt()).coerceIn(5, 25)
                        if (pct > lastDecodePct) {
                            lastDecodePct = pct
                            onProgress(AnalysisProgress(pct, "Decoding audio ($pct%)", ReadinessBadge.ANALYZING))
                        }
                    }
                    encoderDelayMs = info.encoderDelayMs
                    frames = extractor?.finish()

                    writeStatus(25, "Verifying feed transcript", ReadinessBadge.ANALYZING)
                    val transcriber = Transcriber(
                        modelPath = whisperModelPath,
                        useDtw = true,
                        useFlashAttn = useFlashAttn,
                        isTiny = useFastModel
                    )
                    try {
                        val output = transcribeWithFeedFallback(
                            transcriber = transcriber,
                            vadPath = paths.sileroVad,
                            pcm = pcm,
                            parsedFeed = parsedFeed,
                            shouldAbort = shouldAbort
                        ) { wPct ->
                            val pct = (25 + (wPct * 34) / 100).coerceIn(25, 59)
                            onProgress(AnalysisProgress(pct, "Transcribing speech ($pct%)", ReadinessBadge.ANALYZING))
                        }
                        words = output.first
                        segments = output.second
                    } finally {
                        transcriber.close()
                    }
                } finally {
                    pcm.close()
                }
            } else {
                // E1 + E5: Stream 180s+5s overlapping chunks directly into Whisper (~11.8 MB peak PCM)
                writeStatus(5, "Loading Whisper", ReadinessBadge.ANALYZING)
                val transcriber = Transcriber(
                    modelPath = whisperModelPath,
                    useDtw = true,
                    useFlashAttn = useFlashAttn,
                    isTiny = useFastModel
                )
                val allWords = ArrayList<Word>()
                val allSegments = ArrayList<Segment>()
                var chunkIdx = 0
                var lastTranscribePct = 5
                try {
                    setStage("Transcribing speech (Whisper)")
                    val info = AudioDecoder.decode(
                        path = audioFile.absolutePath,
                        frames = extractor,
                        pcm16k = null,
                        shouldAbort = shouldAbort,
                        onPcmChunk = { chunkStartSec, chunkPcm ->
                            if (shouldAbort()) throw CancellationException("Analysis cancelled")
                            chunkIdx++
                            val out = transcriber.transcribe(
                                pcm = chunkPcm,
                                vadModelPath = vadModelStr,
                                shouldAbort = shouldAbort
                            )
                            mergeChunkOutput(chunkStartSec, out.words, out.segments, allWords, allSegments)
                        },
                        onProgress = { fraction ->
                            val pct = maxOf(lastTranscribePct, (5 + (fraction * 54f).toInt()).coerceIn(5, 59))
                            if (pct > lastTranscribePct) {
                                lastTranscribePct = pct
                                onProgress(AnalysisProgress(pct, "Transcribing speech ($pct%)", ReadinessBadge.ANALYZING))
                            }
                        }
                    )
                    encoderDelayMs = info.encoderDelayMs
                    frames = extractor?.finish()
                } finally {
                    transcriber.close()
                }
                words = allWords
                segments = allSegments
            }

            val durationSec = frames?.let { Decoder.round(it.n / 10.0, 1) }
                ?: (words.lastOrNull()?.end ?: 0.0)
            dao.upsertWordIndex(
                EpisodeWordIndexEntity(
                    fileSha256 = sha256,
                    episodeId = episodeId,
                    durationSec = durationSec,
                    wordCount = words.size,
                    compressedBlob = BlobCodec.compress(WordIndexBlob(words, segments)),
                    encoderDelayMs = encoderDelayMs
                )
            )
            dao.pruneHashCache()
        }

        writeStatus(60, "Matching muted words", ReadinessBadge.ANALYZING)
        val muteCount = applyWordRules(
            sha256 = sha256,
            episodeId = episodeId,
            words = words,
            prePadMs = prePadMs,
            postPadMs = postPadMs,
            defaultActionMode = defaultActionMode
        )

        val finalFrames = frames
        if (effectiveQuickWordMuteOnly || segments.isEmpty() || finalFrames == null) {
            val elapsed = System.currentTimeMillis() - startTime
            writeStatus(100, "Word-Mute Ready", ReadinessBadge.WORD_MUTE_READY, force = true, durationMs = elapsed)
            return AnalysisResult(sha256, ReadinessBadge.WORD_MUTE_READY, words.size, muteCount, 0, peakRssMb, elapsed)
        }

        writeStatus(65, "Embedding sentences for ad detection", ReadinessBadge.WORD_MUTE_READY, force = true)
        val paths = modelManager.getPaths()
        val embeddings: FloatArray
        val embedder = Embedder(paths.qwenEmbedder.absolutePath)
        try {
            setStage("Embedding ${segments.size} sentences (Qwen3)")
            check(embedder.dim == Tagger.EMB_DIM) { "embedder dim ${embedder.dim} != ${Tagger.EMB_DIM}" }
            var lastEmbedPct = 70
            embeddings = embedder.embed(
                texts = segments.map { it.text },
                shouldCancel = shouldAbort
            ) { fraction ->
                val pct = (70 + (fraction * 19f).toInt()).coerceIn(70, 89)
                if (pct > lastEmbedPct) {
                    lastEmbedPct = pct
                    onProgress(AnalysisProgress(pct, "Scanning for ads ($pct%)", ReadinessBadge.WORD_MUTE_READY))
                }
            }
            peakRssMb = maxOf(peakRssMb, currentRssMb())
        } finally {
            embedder.close()
        }

        writeStatus(90, "Detecting sponsor segments", ReadinessBadge.WORD_MUTE_READY, force = true)
        val tagger = Tagger(modelManager.loadTaggerBytes())
        val adEntities = try {
            val probs = tagger.probs(embeddings, SegmentFeatures.compute(finalFrames, segments))
            val highSpans = Decoder.findSpans(probs, segments)
            val candidates = Decoder.findCandidates(probs, highSpans, segments)

            val list = ArrayList<AdSegmentEntity>(highSpans.size + candidates.size)
            for (s in highSpans) {
                // F6: Only sponsor_ad is auto-skippable high confidence; promos/memberships/boilerplate are marked only
                val isSponsor = s.type == "sponsor_ad"
                val displayLabel = when (s.type) {
                    "sponsor_ad" -> "Sponsor / Ad"
                    "house_promo" -> "House Promo"
                    "membership_pitch" -> "Membership Pitch"
                    "boilerplate" -> "Boilerplate"
                    else -> "Sponsor / Ad"
                }
                list.add(
                    AdSegmentEntity(
                        fileSha256 = sha256,
                        episodeId = episodeId,
                        startMs = (s.start * 1000.0).toLong(),
                        endMs = (s.end * 1000.0).toLong(),
                        confidence = s.p.toFloat(),
                        isHighConfidence = isSponsor && s.p >= HIGH_CONFIDENCE_THRESHOLD,
                        label = displayLabel,
                        adClass = s.type
                    )
                )
            }
            for (c in candidates) {
                list.add(
                    AdSegmentEntity(
                        fileSha256 = sha256,
                        episodeId = episodeId,
                        startMs = (c.start * 1000.0).toLong(),
                        endMs = (c.end * 1000.0).toLong(),
                        confidence = c.p.toFloat(),
                        isHighConfidence = false,
                        label = "Suggested Ad",
                        adClass = "sponsor_ad"
                    )
                )
            }
            list
        } finally {
            tagger.close()
        }

        setStage("Saving ad segments")
        dao.replaceAdSegments(sha256, adEntities)

        val elapsed = System.currentTimeMillis() - startTime
        writeStatus(100, "Ready", ReadinessBadge.READY, force = true, durationMs = elapsed)
        android.util.Log.i("HushCastPerf", "Analysis READY in ${elapsed}ms (words=${words.size}, mutes=$muteCount, ads=${adEntities.size})")

        return AnalysisResult(sha256, ReadinessBadge.READY, words.size, muteCount, adEntities.size, peakRssMb, elapsed)
    }

    /**
     * Merges a chunk's words and segments (in chunk-local seconds) into the episode-wide lists,
     * shifting by [chunkStartSec] and deduplicating the 5s overlap window.
     */
    private fun mergeChunkOutput(
        chunkStartSec: Double,
        chunkWords: List<Word>,
        chunkSegments: List<Segment>,
        allWords: MutableList<Word>,
        allSegments: MutableList<Segment>
    ) {
        if (chunkStartSec <= 0.0) {
            for (w in chunkWords) {
                allWords.add(w.copy(start = chunkStartSec + w.start, end = chunkStartSec + w.end))
            }
            for (s in chunkSegments) {
                allSegments.add(s.copy(start = chunkStartSec + s.start, end = chunkStartSec + s.end))
            }
            return
        }

        val overlapMidSec = chunkStartSec + (OVERLAP_DEDUP_TOLERANCE_SEC * 0.5)
        while (allWords.isNotEmpty() && allWords.last().start >= overlapMidSec) {
            allWords.removeAt(allWords.lastIndex)
        }
        for (w in chunkWords) {
            val shifted = w.copy(start = chunkStartSec + w.start, end = chunkStartSec + w.end)
            if (shifted.start >= overlapMidSec) {
                allWords.add(shifted)
            }
        }
        while (allSegments.isNotEmpty() && allSegments.last().start >= overlapMidSec) {
            allSegments.removeAt(allSegments.lastIndex)
        }
        for (s in chunkSegments) {
            val shifted = s.copy(start = chunkStartSec + s.start, end = chunkStartSec + s.end)
            if (shifted.start >= overlapMidSec) {
                allSegments.add(shifted)
            }
        }
    }

    /**
     * Re-evaluates mute rules against cached word timings in <50ms without re-running Whisper.
     */
    suspend fun reevaluateMuteRules(
        fileSha256: String,
        episodeId: String,
        prePadMs: Long = 80L,
        postPadMs: Long = 100L,
        defaultActionMode: MuteActionMode = MuteActionMode.HARD_SILENCE
    ): Int {
        val index = dao.getWordIndex(fileSha256) ?: return 0
        val blob = BlobCodec.decompress(index.compressedBlob)
        return applyWordRules(fileSha256, episodeId, blob.words, prePadMs, postPadMs, defaultActionMode)
    }

    private suspend fun applyWordRules(
        sha256: String,
        episodeId: String,
        words: List<Word>,
        prePadMs: Long,
        postPadMs: Long,
        defaultActionMode: MuteActionMode
    ): Int {
        val rules = dao.getEnabledRules().map { it.toDomain() }
        val windows = WordMatcher.matchWords(words, rules, prePadMs, postPadMs, defaultActionMode)
        dao.replaceMuteSegments(
            sha256,
            windows.map { w ->
                MuteSegmentEntity(
                    fileSha256 = sha256,
                    episodeId = episodeId,
                    startMs = w.startMs,
                    endMs = w.endMs,
                    word = w.word,
                    ruleId = w.ruleId,
                    actionMode = w.actionMode.name,
                    enabled = w.enabled
                )
            }
        )
        return windows.size
    }

    private suspend fun transcribeChunked(
        transcriber: Transcriber,
        vadModelStr: String?,
        pcm: PcmBuffer,
        shouldAbort: () -> Boolean,
        onWhisperProgress: ((Int) -> Unit)? = null
    ): Pair<List<Word>, List<Segment>> {
        val dur = pcm.durationSeconds
        if (dur <= CHUNK_SEC) {
            val out = transcriber.transcribe(pcm, vadModelPath = vadModelStr, shouldAbort = shouldAbort, onProgress = onWhisperProgress)
            return out.words to out.segments
        }

        val allWords = ArrayList<Word>()
        val allSegments = ArrayList<Segment>()
        var start = 0.0
        var lastOverall = 0
        while (start < dur - 0.1) {
            yield()
            val end = (start + CHUNK_SEC).coerceAtMost(dur)
            val basePct = (start / dur) * 100.0
            val spanPct = ((end - start) / dur) * 100.0
            val out = transcriber.transcribeRange(
                pcm = pcm,
                startSec = start,
                endSec = end,
                vadModelPath = vadModelStr,
                shouldAbort = shouldAbort,
                onProgress = { subPct ->
                    val overall = maxOf(lastOverall, (basePct + (subPct * spanPct) / 100.0).toInt().coerceIn(0, 100))
                    if (overall > lastOverall) {
                        lastOverall = overall
                        onWhisperProgress?.invoke(overall)
                    }
                }
            )
            mergeChunkOutput(0.0, out.words, out.segments, allWords, allSegments)
            val endOverall = maxOf(lastOverall, ((end / dur) * 100.0).toInt().coerceIn(0, 100))
            if (endOverall > lastOverall) {
                lastOverall = endOverall
                onWhisperProgress?.invoke(endOverall)
            }
            if (end >= dur - 0.1) break
            start = (end - OVERLAP_SEC).coerceAtLeast(start + 1.0)
        }
        return allWords to allSegments
    }

    /**
     * Verifies a feed transcript using 30-second spot-checks (start, middle, end) requiring both
     * word overlap (>= 55%) and median timestamp offset < 0.3s (E17).
     */
    private suspend fun transcribeWithFeedFallback(
        transcriber: Transcriber,
        vadPath: File,
        pcm: PcmBuffer,
        parsedFeed: ParsedTranscript?,
        shouldAbort: () -> Boolean,
        onWhisperProgress: ((Int) -> Unit)? = null
    ): Pair<List<Word>, List<Segment>> {
        val vadModelStr = vadPath.takeIf { it.exists() }?.absolutePath
        if (parsedFeed == null || parsedFeed.segments.isEmpty() || !parsedFeed.hasWordLevelTimings) {
            return transcribeChunked(transcriber, vadModelStr, pcm, shouldAbort, onWhisperProgress)
        }

        val dur = pcm.durationSeconds
        if (dur < MIN_FEED_CHECK_DUR_SEC) {
            return transcribeChunked(transcriber, vadModelStr, pcm, shouldAbort, onWhisperProgress)
        }

        val checkStarts = SPOT_CHECK_FRACTIONS.map { dur * it }
        var passed = 0
        for (s in checkStarts) {
            yield()
            val end = (s + SPOT_CHECK_SEC).coerceAtMost(dur)
            val sampleOut = transcriber.transcribeRange(pcm, s, end, shouldAbort = shouldAbort)
            val sampleWords = sampleOut.words
                .mapNotNull { w -> WordMatcher.normalizeToken(w.text).takeIf { it.isNotEmpty() }?.let { it to w.start } }
            val feedWords = parsedFeed.words
                .filter { it.start >= s - 2.0 && it.end <= end + 2.0 }
                .mapNotNull { w -> WordMatcher.normalizeToken(w.text).takeIf { it.isNotEmpty() }?.let { it to w.start } }
            if (sampleWords.isNotEmpty() && feedWords.isNotEmpty()) {
                val feedMap = feedWords.groupBy({ it.first }, { it.second })
                val offsets = ArrayList<Double>()
                var matched = 0
                for ((tok, tSample) in sampleWords) {
                    val candidates = feedMap[tok] ?: continue
                    matched++
                    val bestDelta = candidates.minOf { abs(it - tSample) }
                    offsets.add(bestDelta)
                }
                val overlap = matched.toDouble() / minOf(sampleWords.size, feedWords.size)
                val medianOffset = if (offsets.isNotEmpty()) {
                    offsets.sort()
                    offsets[offsets.size / 2]
                } else Double.MAX_VALUE
                if (overlap >= SPOT_CHECK_OVERLAP_MIN && medianOffset < MAX_FEED_MEDIAN_OFFSET_SEC) {
                    passed++
                }
            }
        }

        if (passed < 2) {
            return transcribeChunked(transcriber, vadModelStr, pcm, shouldAbort, onWhisperProgress)
        }

        val mergedWords = parsedFeed.words.toMutableList()
        val mergedSegments = parsedFeed.segments.toMutableList()
        if (vadPath.exists()) {
            val speechSpans = VadDetector.detectSpeech(vadPath.absolutePath, pcm, threshold = VAD_THRESHOLD, minSilenceDurationMs = VAD_MIN_SILENCE_MS)
            for (span in speechSpans) {
                if (span.endSec - span.startSec < MIN_UNCOVERED_GAP_SEC) continue
                val covered = parsedFeed.segments.any { seg ->
                    seg.end >= span.startSec + 1.0 && seg.start <= span.endSec - 1.0
                }
                if (!covered) {
                    yield()
                    val gapOut = transcriber.transcribeRange(pcm, span.startSec, span.endSec, shouldAbort = shouldAbort)
                    mergedWords.addAll(gapOut.words)
                    mergedSegments.addAll(gapOut.segments)
                }
            }
        }
        return mergedWords.sortedBy { it.start } to mergedSegments.sortedBy { it.start }
    }

    private fun currentRssMb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val info = am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull() ?: return 0
        return info.totalPss / 1024
    }

    private companion object {
        const val CHUNK_SEC = 180.0
        const val OVERLAP_SEC = 5.0
        const val OVERLAP_DEDUP_TOLERANCE_SEC = 0.15
        const val STATUS_WRITE_INTERVAL_MS = 5_000L
        const val HIGH_CONFIDENCE_THRESHOLD = 0.80
        const val MIN_FEED_CHECK_DUR_SEC = 90.0
        const val SPOT_CHECK_SEC = 30.0
        val SPOT_CHECK_FRACTIONS = listOf(0.10, 0.50, 0.85)
        const val SPOT_CHECK_OVERLAP_MIN = 0.55
        const val MAX_FEED_MEDIAN_OFFSET_SEC = 0.30
        const val MIN_UNCOVERED_GAP_SEC = 3.5
        const val VAD_THRESHOLD = 0.35f
        const val VAD_MIN_SILENCE_MS = 1000
    }
}
