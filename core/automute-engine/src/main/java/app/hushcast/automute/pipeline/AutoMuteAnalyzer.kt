package app.hushcast.automute.pipeline

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.engine.AnalysisThrottle
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
import app.hushcast.automute.matcher.MuteRule
import app.hushcast.automute.matcher.WordMatcher
import app.hushcast.automute.storage.AdSegmentEntity
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.storage.BlobCodec
import app.hushcast.automute.storage.effectiveRules
import app.hushcast.automute.storage.EpisodeAnalysisStatusEntity
import app.hushcast.automute.storage.EpisodeChunkCheckpointEntity
import app.hushcast.automute.storage.EpisodeOffsetMapEntity
import app.hushcast.automute.storage.EpisodeWordIndexEntity
import app.hushcast.automute.storage.MuteSegmentEntity
import app.hushcast.automute.storage.WordIndexBlob
import app.hushcast.automute.storage.toDomain
import app.hushcast.automute.transcript.ApproxSpan
import app.hushcast.automute.transcript.CueRealigner
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

        var existingStatusSnapshot: EpisodeAnalysisStatusEntity? = null

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
            val prev = existingStatusSnapshot
            val preserveExistingReady = prev != null &&
                prev.badge == ReadinessBadge.READY.name &&
                prev.fileSha256 == currentSha &&
                prev.pipelineVersion < CURRENT_PIPELINE_VERSION &&
                badge != ReadinessBadge.READY
            if (preserveExistingReady) {
                // A re-run of an already-Ready episode keeps its Ready badge while it works.  When the
                // re-run ends without ad detection (pct 100), still record the new pipeline version,
                // or every feed refresh would queue it again.
                if (pct >= 100) {
                    dao.upsertStatus(prev!!.copy(pipelineVersion = CURRENT_PIPELINE_VERSION, progressPercent = 100))
                }
                return
            }
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
        pruneOrphanCheckpointsOnce()

        val sha256 = knownSha256?.takeIf { it.isNotBlank() } ?: StreamingSha256.hashFile(audioFile)
        currentSha = sha256

        // R2: Skip full run if already READY for this sha256 at current pipeline version
        val existingIndex = dao.getWordIndex(sha256)
        val existingStatus = dao.getStatus(episodeId)
        existingStatusSnapshot = existingStatus
        val hasAdSegments = dao.getAdSegments(sha256).isNotEmpty()
        if (shouldSkipFullAnalysis(
                existingIndex = existingIndex,
                existingStatus = existingStatus,
                sha256 = sha256,
                quickWordMuteOnly = effectiveQuickWordMuteOnly,
                hasAdSegments = hasAdSegments
            )
        ) {
            val blob = realignIfNeeded(
                existingIndex!!, BlobCodec.decompress(existingIndex.compressedBlob), audioFile, defaultActionMode,
                shouldAbort = shouldAbort
            )
            // applyWordRules also removes other hashes' results (A5), in the same transaction.
            val muteCount = applyWordRules(sha256, episodeId, blob.words, prePadMs, postPadMs, defaultActionMode, blob.approxSpans)
            dao.deleteAllCheckpointsForSha(sha256)
            val adCount = dao.getAdSegments(sha256).size
            val elapsed = System.currentTimeMillis() - startTime
            writeStatus(100, "Ready", ReadinessBadge.READY, force = true, durationMs = elapsed)
            return AnalysisResult(sha256, ReadinessBadge.READY, blob.words.size, muteCount, adCount, peakRssMb, elapsed)
        }

        writeStatus(2, "Verifying audio file", ReadinessBadge.ANALYZING)

        var words: List<Word>
        var segments: List<Segment>
        var approxSpans: List<ApproxSpan> = emptyList()
        var frames: Frames? = null
        val daiCandidateSpans = ArrayList<Pair<Double, Double>>()

        if (existingIndex != null && (existingStatus?.pipelineVersion ?: 0) >= CURRENT_PIPELINE_VERSION) {
            val blob = realignIfNeeded(
                existingIndex, BlobCodec.decompress(existingIndex.compressedBlob), audioFile, defaultActionMode,
                shouldAbort = shouldAbort
            )
            words = blob.words
            segments = blob.segments
            approxSpans = blob.approxSpans
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
            val threads = AnalysisThrottle.threads(context)
            val threadsNow = { AnalysisThrottle.threads(context) }

            var daiSucceeded = false
            var daiWords: List<Word> = emptyList()
            var daiSegments: List<Segment> = emptyList()

            if (parsedFeed != null) {
                val durSec = AudioDecoder.probeDurationSec(audioFile.absolutePath)
                if (durSec >= MIN_FEED_CHECK_DUR_SEC) {
                    writeStatus(5, "Aligning publisher transcript", ReadinessBadge.ANALYZING)
                    val enabledRules = dao.effectiveRules(context, episodeId)
                    val transcriber = Transcriber(
                        modelPath = whisperModelPath,
                        threads = threads,
                        useDtw = true,
                        useFlashAttn = useFlashAttn,
                        isTiny = useFastModel,
                        threadsProvider = threadsNow
                    )
                    val daiResult = try {
                        runCatching {
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
                        }.onFailure { err ->
                            android.util.Log.w("HushCastPerf", "DAI retimer failed, falling back to Whisper: ${err.message}")
                        }.getOrNull()
                    } finally {
                        transcriber.close()
                    }
                    if (daiResult != null) {
                        daiSucceeded = true
                        daiWords = daiResult.words
                        daiSegments = daiResult.segments
                        approxSpans = daiResult.approximateSpans
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
                segments = app.hushcast.automute.engine.SentenceSplitter.split(daiWords)
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
                val checkpointKey = checkpointKey(sha256, useFastModel)
                val checkpoints = dao.getCheckpoints(checkpointKey).associateBy { it.chunkIndex }
                writeStatus(5, "Loading Whisper", ReadinessBadge.ANALYZING)
                val transcriber = Transcriber(
                    modelPath = whisperModelPath,
                    threads = threads,
                    useDtw = true,
                    useFlashAttn = useFlashAttn,
                    isTiny = useFastModel,
                    threadsProvider = threadsNow
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
                                            fileSha256 = checkpointKey,
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
                    compressedBlob = BlobCodec.compress(WordIndexBlob(words, segments, approxSpans)),
                    encoderDelayMs = encoderDelayMs
                )
            )
            dao.pruneHashCache()
        }
        // The transcript for this file is saved: drop every checkpoint row of it (all models,
        // pipeline versions and the legacy keys that earlier versions leaked).
        dao.deleteAllCheckpointsForSha(sha256)

        writeStatus(60, "Matching muted words", ReadinessBadge.ANALYZING)
        // A5: the mute segments and the removal of other hashes' results share one transaction,
        // and run before ad detection, so a failed or stopped ad stage cannot skip the cleanup.
        val muteCount = applyWordRules(
            sha256 = sha256,
            episodeId = episodeId,
            words = words,
            prePadMs = prePadMs,
            postPadMs = postPadMs,
            defaultActionMode = defaultActionMode,
            approxSpans = approxSpans
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
        val embedder = Embedder(
            paths.qwenEmbedder.absolutePath,
            threads = AnalysisThrottle.threads(context),
            threadsProvider = { AnalysisThrottle.threads(context) }
        )
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
        dao.replaceAdSegmentsAndClearOtherHashes(episodeId, sha256, adEntities)

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
        return applyWordRules(fileSha256, episodeId, blob.words, prePadMs, postPadMs, defaultActionMode, blob.approxSpans)
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
        // SRT/VTT words that were never heard only have estimated times: their windows cover the
        // likely range until the cue is re-aligned (next analysis or play, see realignApproximateMatches).
        val windows = CueRealigner.widenApproximate(
            WordMatcher.matchWords(blob.words, compiled, prePadMs, postPadMs),
            blob.approxSpans
        )
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
        defaultActionMode: MuteActionMode,
        approxSpans: List<ApproxSpan> = emptyList(),
        saveIndex: EpisodeWordIndexEntity? = null
    ): Int {
        val rules = dao.effectiveRules(context, episodeId)
        val rulesById = rules.associateBy { it.id }
        val windows = CueRealigner.widenApproximate(
            WordMatcher.matchWords(words, rules, prePadMs, postPadMs, defaultActionMode),
            approxSpans
        )
        val segments = windows.map { w ->
            val isDefaultRule = rulesById[w.ruleId]?.useDefaultMode == true
            MuteSegmentEntity(
                fileSha256 = sha256,
                episodeId = episodeId,
                startMs = w.startMs,
                endMs = w.endMs,
                word = w.word,
                ruleId = w.ruleId,
                actionMode = if (isDefaultRule) "DEFAULT" else w.actionMode.name,
                enabled = w.enabled
            )
        }
        // A5: written with the removal of other hashes' results, in one transaction.
        if (saveIndex != null) {
            dao.saveWordIndexAndMuteSegments(saveIndex, episodeId, segments)
        } else {
            dao.replaceMuteSegmentsAndClearOtherHashes(episodeId, sha256, segments)
        }
        return windows.size
    }

    /**
     * SRT/VTT transcripts: words a current rule matches that still have estimated (evenly spread)
     * times get Whisper run within +-2 s of their cue and take the times it heard.  Rule
     * re-evaluation can't do this (it has no audio file and runs in the background or on the
     * player's thread), so it widens those windows instead and this runs the next time the
     * episode is analyzed or played ([EpisodeAnalysisWorker.enqueueRealignIfNeeded]).
     *
     * @return the number of cues re-aligned (failed ones stay approximate); 0 when nothing needed it.
     */
    suspend fun realignApproximateMatches(
        episodeId: String,
        audioFile: File,
        defaultActionMode: MuteActionMode = MuteActionMode.HARD_SILENCE,
        prePadMs: Long? = null,
        postPadMs: Long? = null
    ): Int = withContext(CpuTopology.analysisDispatcher) {
        val sha = dao.getStatus(episodeId)?.fileSha256?.takeIf { it.isNotBlank() } ?: return@withContext 0
        val index = dao.getWordIndex(sha) ?: return@withContext 0
        val blob = BlobCodec.decompress(index.compressedBlob)
        if (blob.approxSpans.isEmpty() || !audioFile.exists()) return@withContext 0
        val job = currentCoroutineContext()[Job]
        val updated = realignIfNeeded(index, blob, audioFile, defaultActionMode, persist = false) { job?.isActive == false }
        if (updated === blob) return@withContext 0
        val precision = HushCastPrefs.getMuteTimingPrecision(context)
        // The new word times and the mute segments matched on them are saved in one transaction.
        applyWordRules(
            sha, episodeId, updated.words,
            prePadMs ?: precision.prePadMs, postPadMs ?: precision.postPadMs,
            defaultActionMode, updated.approxSpans,
            saveIndex = index.copy(wordCount = updated.words.size, compressedBlob = BlobCodec.compress(updated))
        )
        blob.approxSpans.size - updated.approxSpans.size
    }

    /** Whether [realignApproximateMatches] has work for the episode (cheap: no models loaded). */
    suspend fun needsRealign(episodeId: String, defaultActionMode: MuteActionMode = MuteActionMode.HARD_SILENCE): Boolean {
        val sha = dao.getStatus(episodeId)?.fileSha256?.takeIf { it.isNotBlank() } ?: return false
        val index = dao.getWordIndex(sha) ?: return false
        val blob = BlobCodec.decompress(index.compressedBlob)
        if (blob.approxSpans.isEmpty()) return false
        val compiled = WordMatcher.compileRules(dao.effectiveRules(context, episodeId), defaultActionMode)
        return CueRealigner.spansNeedingRealign(blob.words, blob.approxSpans, compiled).isNotEmpty()
    }

    /**
     * Runs [CueRealigner.realign] when a rule matches an approximate cue and returns the updated
     * blob (saved to the word index when [persist]); returns [blob] itself when nothing changed.
     */
    private suspend fun realignIfNeeded(
        index: EpisodeWordIndexEntity,
        blob: WordIndexBlob,
        audioFile: File,
        defaultActionMode: MuteActionMode,
        persist: Boolean = true,
        shouldAbort: () -> Boolean
    ): WordIndexBlob {
        if (blob.approxSpans.isEmpty() || !audioFile.exists()) return blob
        val rules: List<MuteRule> = dao.effectiveRules(context, index.episodeId)
        val compiled = WordMatcher.compileRules(rules, defaultActionMode)
        if (CueRealigner.spansNeedingRealign(blob.words, blob.approxSpans, compiled).isEmpty()) return blob
        if (!modelManager.areWordMuteModelsReady()) return blob
        val paths = modelManager.getPaths()
        val useFastModel = HushCastPrefs.isFastModelEnabled(context)
        val transcriber = Transcriber(
            modelPath = if (useFastModel) paths.whisperTinyEn.absolutePath else paths.whisperBaseEn.absolutePath,
            threads = AnalysisThrottle.threads(context),
            useDtw = true,
            useFlashAttn = HushCastPrefs.isFastAttentionEnabled(context),
            isTiny = useFastModel,
            threadsProvider = { AnalysisThrottle.threads(context) }
        )
        // A cue only takes new times when at least a third of its words were heard near it, so a
        // file that no longer matches the transcript just leaves the cue approximate.
        val result = try {
            CueRealigner.realign(
                words = blob.words,
                spans = blob.approxSpans,
                compiled = compiled,
                durationSec = index.durationSec,
                clips = DaiRetimer.whisperClips(
                    audioFile.absolutePath, transcriber,
                    paths.sileroVad.takeIf { it.exists() }?.absolutePath, shouldAbort
                )
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Re-alignment is a refinement: the widened windows stay, and analysis carries on.
            android.util.Log.w("HushCastPerf", "Cue re-alignment failed: ${t.javaClass.simpleName}: ${t.message}")
            return blob
        } finally {
            transcriber.close()
        }
        if (result.realigned == 0 && result.failed == 0) return blob
        android.util.Log.i("HushCastPerf", "Re-aligned ${result.realigned} SRT/VTT cue(s) (${result.failed} kept approximate)")
        val updated = blob.copy(words = result.words, approxSpans = result.spans)
        if (persist) {
            dao.upsertWordIndex(index.copy(wordCount = updated.words.size, compressedBlob = BlobCodec.compress(updated)))
        }
        return updated
    }

    /**
     * One-time cleanup of checkpoint rows that 1.0.30 and earlier leaked (written under
     * "sha:model:dtw", deleted by bare sha).  Rows whose file finished more than 7 days ago, or
     * that have no transcript and no recent status, go.
     */
    private suspend fun pruneOrphanCheckpointsOnce() {
        if (HushCastPrefs.isCheckpointPruneDone(context)) return
        val removed = runCatching {
            dao.pruneOrphanCheckpoints(System.currentTimeMillis() - CHECKPOINT_ORPHAN_AGE_MS)
        }.getOrElse { err ->
            android.util.Log.w("HushCastPerf", "Checkpoint prune failed: ${err.message}")
            return
        }
        HushCastPrefs.setCheckpointPruneDone(context)
        android.util.Log.i("HushCastPerf", "Pruned $removed orphaned checkpoint row(s)")
    }

    private fun currentRssMb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val info = am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull() ?: return 0
        return info.totalPss / 1024
    }

    companion object {
        const val CURRENT_PIPELINE_VERSION = 6
        private const val OVERLAP_DEDUP_TOLERANCE_SEC = 0.15
        private const val STATUS_WRITE_INTERVAL_MS = 5_000L
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.80
        private const val MIN_FEED_CHECK_DUR_SEC = 90.0
        private const val CHECKPOINT_ORPHAN_AGE_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * Chunk checkpoints are only valid for the same file, model, DTW setting and pipeline
         * version.  Every key starts with "$sha256:", so [AutoMuteDao.deleteAllCheckpointsForSha]
         * removes them all.
         */
        internal fun checkpointKey(sha256: String, tinyModel: Boolean): String =
            "$sha256:v$CURRENT_PIPELINE_VERSION:${if (tinyModel) "tiny" else "base"}:dtw"

        internal fun shouldSkipFullAnalysis(
            existingIndex: EpisodeWordIndexEntity?,
            existingStatus: EpisodeAnalysisStatusEntity?,
            sha256: String,
            quickWordMuteOnly: Boolean,
            hasAdSegments: Boolean
        ): Boolean {
            return existingIndex != null &&
                existingStatus != null &&
                existingStatus.fileSha256 == sha256 &&
                existingStatus.badge == ReadinessBadge.READY.name &&
                existingStatus.pipelineVersion >= CURRENT_PIPELINE_VERSION &&
                (quickWordMuteOnly || hasAdSegments)
        }
    }
}
