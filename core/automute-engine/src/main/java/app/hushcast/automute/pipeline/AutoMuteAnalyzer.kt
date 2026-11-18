package app.hushcast.automute.pipeline

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.engine.AudioDecoder
import app.hushcast.automute.engine.CpuTopology
import app.hushcast.automute.engine.Decoder
import app.hushcast.automute.engine.Embedder
import app.hushcast.automute.engine.FrameExtractor
import app.hushcast.automute.engine.Frames
import app.hushcast.automute.engine.HushCastPrefs
import app.hushcast.automute.engine.ModelManager
import app.hushcast.automute.engine.Segment
import app.hushcast.automute.engine.SegmentFeatures
import app.hushcast.automute.engine.Tagger
import app.hushcast.automute.engine.Transcriber
import app.hushcast.automute.engine.Word
import app.hushcast.automute.hash.StreamingSha256
import app.hushcast.automute.matcher.WordMatcher
import app.hushcast.automute.storage.AdSegmentEntity
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.storage.BlobCodec
import app.hushcast.automute.storage.EpisodeAnalysisStatusEntity
import app.hushcast.automute.storage.EpisodeChunkCheckpointEntity
import app.hushcast.automute.storage.EpisodeOffsetMapEntity
import app.hushcast.automute.storage.EpisodeWordIndexEntity
import app.hushcast.automute.storage.MuteSegmentEntity
import app.hushcast.automute.storage.WordIndexBlob
import app.hushcast.automute.storage.toDomain
import app.hushcast.automute.transcript.DaiRetimer
import app.hushcast.automute.transcript.TranscriptParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.CancellationException

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
 * 2. Uses DAI Retiming Bridge for publisher transcripts/chapters, or streams 180s+5s overlapping
 *    chunks through whisper.cpp with chunk checkpoints while extracting 24 kHz acoustic frames.
 * 3. Saves WordIndexBlob + MuteSegments atomically and unloads Whisper before loading Qwen3.
 * 4. If full mode: embeds segments with Qwen3, unloads Embedder, runs ad_tagger.onnx, and saves AdSegments.
 */
class AutoMuteAnalyzer(
    private val context: Context,
    private val db: AutoMuteDatabase = AutoMuteDatabase.getInstance(context),
    private val modelManager: ModelManager = ModelManager(context)
) {
    private val dao = db.dao()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun analyzeEpisode(
        episodeId: String,
        audioFile: File,
        knownSha256: String? = null,
        quickWordMuteOnly: Boolean = false,
        feedTranscriptRaw: String? = null,
        feedTranscriptMime: String = "",
        prePadMs: Long? = null,
        postPadMs: Long? = null,
        defaultActionMode: MuteActionMode = MuteActionMode.HARD_SILENCE,
        onProgress: (AnalysisProgress) -> Unit = {}
    ): AnalysisResult {
        var stage = "Starting"
        val precision = HushCastPrefs.getMuteTimingPrecision(context)
        val resolvedPrePad = prePadMs ?: precision.prePadMs
        val resolvedPostPad = postPadMs ?: precision.postPadMs
        try {
            return withContext(CpuTopology.analysisDispatcher) {
                runPipeline(
                    episodeId, audioFile, knownSha256, quickWordMuteOnly, feedTranscriptRaw,
                    feedTranscriptMime, resolvedPrePad, resolvedPostPad, defaultActionMode, onProgress
                ) { stage = it }
            }
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
                        encoderDelayMs = encoderDelayMs,
                        pipelineVersion = CURRENT_PIPELINE_VERSION
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

        // R2: Skip full run if already READY for this sha256 at current pipeline version
        val existingIndex = dao.getWordIndex(sha256)
        val existingStatus = dao.getStatus(episodeId)
        if (existingIndex != null &&
            existingStatus != null &&
            existingStatus.fileSha256 == sha256 &&
            existingStatus.badge == ReadinessBadge.READY.name &&
            existingStatus.pipelineVersion >= CURRENT_PIPELINE_VERSION &&
            (effectiveQuickWordMuteOnly || dao.getAdSegments(sha256).isNotEmpty())
        ) {
            val blob = BlobCodec.decompress(existingIndex.compressedBlob)
            val muteCount = applyWordRules(sha256, episodeId, blob.words, prePadMs, postPadMs, defaultActionMode)
            dao.clearAnalysisForOtherHashes(episodeId, sha256)
            val adCount = dao.getAdSegments(sha256).size
            val elapsed = System.currentTimeMillis() - startTime
            writeStatus(100, "Ready", ReadinessBadge.READY, force = true, durationMs = elapsed)
            return AnalysisResult(sha256, ReadinessBadge.READY, blob.words.size, muteCount, adCount, peakRssMb, elapsed)
        }

        var words: List<Word>
        var segments: List<Segment>
        var frames: Frames? = null
        val daiCandidateSpans = ArrayList<Pair<Double, Double>>()

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
            }?.takeIf { it.segments.isNotEmpty() }

            val paths = modelManager.getPaths()
            val useFastModel = HushCastPrefs.isFastModelEnabled(context)
            val useFlashAttn = HushCastPrefs.isFastAttentionEnabled(context)
            val whisperModelPath = if (useFastModel) paths.whisperTinyEn.absolutePath else paths.whisperBaseEn.absolutePath
            val vadModelStr = paths.sileroVad.takeIf { it.exists() }?.absolutePath
            val threads = CpuTopology.recommendedThreads(context)

            var daiSucceeded = false
            var daiWords: List<Word> = emptyList()
            var daiSegments: List<Segment> = emptyList()

            if (parsedFeed != null) {
                val durSec = AudioDecoder.probeDurationSec(audioFile.absolutePath)
                if (durSec >= MIN_FEED_CHECK_DUR_SEC) {
                    writeStatus(5, "Aligning publisher transcript", ReadinessBadge.ANALYZING)
                    val enabledRules = dao.getEnabledRules().map { it.toDomain() }
                    val transcriber = Transcriber(
                        modelPath = whisperModelPath,
                        threads = threads,
                        useDtw = true,
                        useFlashAttn = useFlashAttn,
                        isTiny = useFastModel
                    )
                    val daiResult = try {
                        DaiRetimer.retimeTranscript(
                            audioPath = audioFile.absolutePath,
                            durationSec = durSec,
                            parsedFeed = parsedFeed,
                            transcriber = transcriber,
                            vadModelPath = vadModelStr,
                            enabledRules = enabledRules,
                            shouldAbort = shouldAbort
                        ) { subPct ->
                            val pct = (5 + (subPct * 35) / 100).coerceIn(5, 40)
                            onProgress(AnalysisProgress(pct, "Aligning transcript ($pct%)", ReadinessBadge.ANALYZING))
                        }
                    } finally {
                        transcriber.close()
                    }
                    if (daiResult != null) {
                        daiSucceeded = true
                        daiWords = daiResult.words
                        daiSegments = daiResult.segments
                        daiCandidateSpans.addAll(daiResult.adCandidateSpansSec)
                        dao.upsertOffsetMap(
                            EpisodeOffsetMapEntity(
                                fileSha256 = sha256,
                                episodeId = episodeId,
                                stretchesJson = json.encodeToString(daiResult.offsetMap)
                            )
                        )
                    }
                }
            }

            if (daiSucceeded) {
                words = daiWords
                segments = daiSegments
                if (!effectiveQuickWordMuteOnly) {
                    writeStatus(42, "Extracting acoustic features", ReadinessBadge.ANALYZING)
                    val extractor = FrameExtractor()
                    var lastPct = 42
                    val info = AudioDecoder.decode(
                        path = audioFile.absolutePath,
                        frames = extractor,
                        pcm16k = null,
                        shouldAbort = shouldAbort
                    ) { fraction ->
                        val pct = (42 + (fraction * 17f).toInt()).coerceIn(42, 59)
                        if (pct > lastPct) {
                            lastPct = pct
                            onProgress(AnalysisProgress(pct, "Extracting audio ($pct%)", ReadinessBadge.ANALYZING))
                        }
                    }
                    encoderDelayMs = info.encoderDelayMs
                    frames = extractor.finish()
                }
            } else {
                // E1 + E5 + R2: Stream 180s+5s overlapping chunks with checkpoint resume
                val extractor = if (!effectiveQuickWordMuteOnly) FrameExtractor() else null
                val checkpoints = dao.getCheckpoints(sha256).associateBy { it.chunkIndex }
                writeStatus(5, "Loading Whisper", ReadinessBadge.ANALYZING)
                val transcriber = Transcriber(
                    modelPath = whisperModelPath,
                    threads = threads,
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
                            val idx = chunkIdx++
                            val saved = checkpoints[idx]
                            if (saved != null) {
                                val cWords = json.decodeFromString<List<Word>>(saved.wordsJson)
                                val cSegs = json.decodeFromString<List<Segment>>(saved.segmentsJson)
                                mergeChunkOutput(saved.chunkStartSec, cWords, cSegs, allWords, allSegments)
                            } else {
                                val out = transcriber.transcribe(
                                    pcm = chunkPcm,
                                    vadModelPath = vadModelStr,
                                    shouldAbort = shouldAbort
                                )
                                mergeChunkOutput(chunkStartSec, out.words, out.segments, allWords, allSegments)
                                kotlinx.coroutines.runBlocking {
                                    dao.upsertCheckpoint(
                                        EpisodeChunkCheckpointEntity(
                                            fileSha256 = sha256,
                                            chunkIndex = idx,
                                            chunkStartSec = chunkStartSec,
                                            wordsJson = json.encodeToString(out.words),
                                            segmentsJson = json.encodeToString(out.segments)
                                        )
                                    )
                                }
                            }
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
            dao.deleteCheckpoints(sha256)
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
            dao.clearAnalysisForOtherHashes(episodeId, sha256)
            val elapsed = System.currentTimeMillis() - startTime
            writeStatus(100, "Word-Mute Ready", ReadinessBadge.WORD_MUTE_READY, force = true, durationMs = elapsed)
            return AnalysisResult(sha256, ReadinessBadge.WORD_MUTE_READY, words.size, muteCount, 0, peakRssMb, elapsed)
        }

        writeStatus(65, "Embedding sentences for ad detection", ReadinessBadge.WORD_MUTE_READY, force = true)
        val paths = modelManager.getPaths()
        val embeddings: FloatArray
        val embedder = Embedder(paths.qwenEmbedder.absolutePath, threads = CpuTopology.recommendedThreads(context))
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
        val tagger = Tagger(modelManager.taggerFile())
        val adEntities = try {
            val probs = tagger.probs(embeddings, SegmentFeatures.compute(finalFrames, segments))
            val highSpans = Decoder.findSpans(probs, segments)
            val candidates = Decoder.findCandidates(probs, highSpans, segments)

            val list = ArrayList<AdSegmentEntity>(highSpans.size + candidates.size + daiCandidateSpans.size)
            for (s in highSpans) {
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
            // DAI Retiming Bridge: pass uncovered ad-break gaps as separate candidate spans
            for ((gStartSec, gEndSec) in daiCandidateSpans) {
                val gStartMs = (gStartSec * 1000.0).toLong()
                val gEndMs = (gEndSec * 1000.0).toLong()
                val overlapsHigh = highSpans.any { (it.start * 1000.0) < gEndMs && (it.end * 1000.0) > gStartMs }
                if (!overlapsHigh) {
                    list.add(
                        AdSegmentEntity(
                            fileSha256 = sha256,
                            episodeId = episodeId,
                            startMs = gStartMs,
                            endMs = gEndMs,
                            confidence = 0.72f,
                            isHighConfidence = false,
                            label = "DAI Ad Break",
                            adClass = "sponsor_ad"
                        )
                    )
                }
            }
            list
        } finally {
            tagger.close()
        }

        setStage("Saving ad segments")
        dao.deleteAdSegmentsByEpisode(episodeId)
        dao.replaceAdSegments(sha256, adEntities)
        dao.clearAnalysisForOtherHashes(episodeId, sha256)

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

    suspend fun reevaluateMuteRulesWithCompiled(
        fileSha256: String,
        episodeId: String,
        prePadMs: Long = 80L,
        postPadMs: Long = 100L,
        compiled: WordMatcher.CompiledRules
    ): Int {
        val index = dao.getWordIndex(fileSha256) ?: return 0
        val blob = BlobCodec.decompress(index.compressedBlob)
        val windows = WordMatcher.matchWords(blob.words, compiled, prePadMs, postPadMs)
        dao.replaceMuteSegments(
            fileSha256,
            windows.map { w ->
                MuteSegmentEntity(
                    fileSha256 = fileSha256,
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

    private fun currentRssMb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val info = am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull() ?: return 0
        return info.totalPss / 1024
    }

    companion object {
        const val CURRENT_PIPELINE_VERSION = 5
        private const val OVERLAP_DEDUP_TOLERANCE_SEC = 0.15
        private const val STATUS_WRITE_INTERVAL_MS = 5_000L
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.80
        private const val MIN_FEED_CHECK_DUR_SEC = 90.0
    }
}
