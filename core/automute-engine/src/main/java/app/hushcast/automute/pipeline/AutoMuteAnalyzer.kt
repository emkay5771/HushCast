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
 * 1. Computes/verifies file SHA-256 (DAI resilience).
 * 2. Decodes audio once into 16 kHz mono PcmBuffer (+ 24 kHz stereo FrameExtractor if full mode).
 * 3. Uses feed transcript if 30s spot-checks pass + Silero VAD gap detection (>= 3.5s);
 *    otherwise runs whisper.cpp in 180s chunks (progress, cancellation, per-chunk telemetry).
 * 4. Saves WordIndexBlob + MuteSegments (WORD_MUTE_READY) and STRICTLY unloads Whisper + PcmBuffer.
 * 5. If full mode: loads Qwen3-Embedding-0.6B, embeds segments, STRICTLY unloads Embedder,
 *    runs ad_tagger.onnx, and saves AdSegments (READY).
 *
 * If a word index already exists for this SHA-256 (e.g. a previous run finished Whisper but
 * failed in ad detection), full mode skips Whisper and only re-extracts the acoustic frames.
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
            // Tag the failing stage so Export Logs shows exactly where it broke
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
        val job = currentCoroutineContext()[Job]
        val shouldAbort: () -> Boolean = { job?.isActive == false }

        suspend fun report(pct: Int, stage: String, badge: ReadinessBadge = ReadinessBadge.ANALYZING) {
            setStage(stage)
            peakRssMb = maxOf(peakRssMb, currentRssMb())
            onProgress(AnalysisProgress(pct, stage, badge))
            dao.upsertStatus(
                EpisodeAnalysisStatusEntity(
                    episodeId = episodeId,
                    fileSha256 = currentSha,
                    badge = badge.name,
                    progressPercent = pct,
                    stageLabel = stage,
                    peakRssMb = peakRssMb
                )
            )
        }

        fun writeStatusBlocking(pct: Int, stage: String, badge: ReadinessBadge) {
            kotlinx.coroutines.runBlocking {
                dao.upsertStatus(
                    EpisodeAnalysisStatusEntity(
                        episodeId = episodeId,
                        fileSha256 = currentSha,
                        badge = badge.name,
                        progressPercent = pct,
                        stageLabel = stage,
                        peakRssMb = maxOf(peakRssMb, currentRssMb())
                    )
                )
            }
        }

        report(2, "Verifying audio file")
        val sha256 = knownSha256?.takeIf { it.isNotBlank() } ?: StreamingSha256.hashFile(audioFile)
        currentSha = sha256

        // Check if already indexed for this exact SHA-256
        val existingIndex = dao.getWordIndex(sha256)
        var words: List<Word>
        var segments: List<Segment>
        var frames: Frames? = null

        setStage("Preparing models")
        val paths = if (quickWordMuteOnly) {
            modelManager.ensureWordMuteModels()
        } else {
            modelManager.ensureAllModels()
        }

        if (existingIndex != null) {
            val blob = BlobCodec.decompress(existingIndex.compressedBlob)
            words = blob.words
            segments = blob.segments
            android.util.Log.i("HushCastPerf", "Reusing cached transcript (words=${words.size}, segs=${segments.size}) quick=$quickWordMuteOnly")

            if (!quickWordMuteOnly && segments.isNotEmpty()) {
                // Phase 3 retry: only the 24 kHz acoustic frames are needed, not Whisper
                report(5, "Extracting acoustic features (cached transcript)", ReadinessBadge.WORD_MUTE_READY)
                val decodeStart = System.currentTimeMillis()
                val extractor = FrameExtractor()
                var lastPct = 5
                AudioDecoder.decode(audioFile.absolutePath, extractor, null) { fraction ->
                    val pct = (5 + (fraction * 54f).toInt()).coerceIn(5, 59)
                    if (pct > lastPct) {
                        lastPct = pct
                        onProgress(AnalysisProgress(pct, "Extracting acoustic features ($pct%)", ReadinessBadge.WORD_MUTE_READY))
                        if (pct % 5 == 0) writeStatusBlocking(pct, "Extracting acoustic features", ReadinessBadge.WORD_MUTE_READY)
                    }
                }
                frames = extractor.finish()
                android.util.Log.i("HushCastPerf", "Stage Frames-only done in ${System.currentTimeMillis() - decodeStart}ms (frames=${frames?.n ?: 0})")
                peakRssMb = maxOf(peakRssMb, currentRssMb())
            }
        } else {
            report(5, "Decoding audio")
            val decodeStart = System.currentTimeMillis()
            val extractor = if (!quickWordMuteOnly) FrameExtractor() else null
            val pcm = PcmBuffer()
            try {
                var lastDecodePct = 5
                AudioDecoder.decode(audioFile.absolutePath, extractor, pcm) { fraction ->
                    val pct = (5 + (fraction * 20f).toInt()).coerceIn(5, 25)
                    if (pct > lastDecodePct) {
                        lastDecodePct = pct
                        onProgress(AnalysisProgress(pct, "Decoding audio ($pct%)", ReadinessBadge.ANALYZING))
                        if (pct % 5 == 0 || pct == 25) writeStatusBlocking(pct, "Decoding audio", ReadinessBadge.ANALYZING)
                    }
                }
                frames = extractor?.finish()
                val decodeMs = System.currentTimeMillis() - decodeStart
                android.util.Log.i("HushCastPerf", "Stage Decode+Frames done in ${decodeMs}ms (frames=${frames?.n ?: 0})")
                peakRssMb = maxOf(peakRssMb, currentRssMb())

                val parsedFeed = feedTranscriptRaw?.let {
                    runCatching { TranscriptParser.parse(it, feedTranscriptMime) }.getOrNull()
                }

                report(25, "Transcribing speech (Whisper)")
                val whisperStart = System.currentTimeMillis()
                val useFastModel = app.hushcast.automute.engine.HushCastPrefs.isFastModelEnabled(context)
                val useFlashAttn = app.hushcast.automute.engine.HushCastPrefs.isFastAttentionEnabled(context)
                val whisperModelPath = if (useFastModel) paths.whisperTinyEn.absolutePath else paths.whisperBaseEn.absolutePath
                android.util.Log.i("HushCastPerf", "Whisper load: model=${File(whisperModelPath).name} tiny=$useFastModel flashAttn=$useFlashAttn")
                setStage("Loading Whisper")
                val transcriber = Transcriber(
                    modelPath = whisperModelPath,
                    useDtw = true,
                    useFlashAttn = useFlashAttn,
                    isTiny = useFastModel
                )
                android.util.Log.i("HushCastPerf", "Whisper loaded in ${System.currentTimeMillis() - whisperStart}ms")
                setStage("Transcribing speech (Whisper)")
                try {
                    var lastWhisperPct = 25
                    val output = transcribeWithFeedFallback(
                        transcriber = transcriber,
                        vadPath = paths.sileroVad,
                        pcm = pcm,
                        parsedFeed = parsedFeed,
                        shouldAbort = shouldAbort
                    ) { wPct ->
                        val pct = (25 + (wPct * 34) / 100).coerceIn(25, 59)
                        if (pct > lastWhisperPct) {
                            lastWhisperPct = pct
                            onProgress(AnalysisProgress(pct, "Transcribing speech ($pct%)", ReadinessBadge.ANALYZING))
                            android.util.Log.i("HushCastPerf", "Whisper progress: $wPct% (overall $pct%)")
                            writeStatusBlocking(pct, "Transcribing speech", ReadinessBadge.ANALYZING)
                        }
                    }
                    words = output.first
                    segments = output.second
                } finally {
                    // Strictly unload whisper.cpp before loading Qwen3
                    transcriber.close()
                }
                val whisperMs = System.currentTimeMillis() - whisperStart
                android.util.Log.i("HushCastPerf", "Stage Whisper done in ${whisperMs}ms (words=${words.size}, segs=${segments.size})")
                peakRssMb = maxOf(peakRssMb, currentRssMb())
            } finally {
                pcm.close()
            }

            // Save WordIndexBlob checkpoint (lets a failed Phase 3 retry skip Whisper)
            val durationSec = frames?.let { Decoder.round(it.n / 10.0, 1) }
                ?: (words.lastOrNull()?.end ?: 0.0)
            dao.upsertWordIndex(
                EpisodeWordIndexEntity(
                    fileSha256 = sha256,
                    episodeId = episodeId,
                    durationSec = durationSec,
                    wordCount = words.size,
                    compressedBlob = BlobCodec.compress(WordIndexBlob(words, segments))
                )
            )
        }

        // Recompute Track A word mute segments
        report(60, "Matching muted words")
        val muteCount = applyWordRules(
            sha256 = sha256,
            episodeId = episodeId,
            words = words,
            prePadMs = prePadMs,
            postPadMs = postPadMs,
            defaultActionMode = defaultActionMode
        )

        val finalFrames = frames
        if (quickWordMuteOnly || segments.isEmpty() || finalFrames == null) {
            val elapsed = System.currentTimeMillis() - startTime
            val badge = ReadinessBadge.WORD_MUTE_READY
            dao.upsertStatus(
                EpisodeAnalysisStatusEntity(
                    episodeId = episodeId,
                    fileSha256 = sha256,
                    badge = badge.name,
                    progressPercent = 100,
                    stageLabel = "Word-Mute Ready",
                    peakRssMb = peakRssMb,
                    analysisDurationMs = elapsed
                )
            )
            return AnalysisResult(sha256, badge, words.size, muteCount, 0, peakRssMb, elapsed)
        }

        // Checkpoint intermediate WORD_MUTE_READY state before loading Qwen3
        dao.upsertStatus(
            EpisodeAnalysisStatusEntity(
                episodeId = episodeId,
                fileSha256 = sha256,
                badge = ReadinessBadge.WORD_MUTE_READY.name,
                progressPercent = 65,
                stageLabel = "Embedding sentences for ad detection",
                peakRssMb = peakRssMb
            )
        )
        report(70, "Loading Qwen3 embedder", ReadinessBadge.WORD_MUTE_READY)

        // Force GC hint after Whisper unload before loading ~395 MB Qwen3
        System.gc()

        val embedStart = System.currentTimeMillis()
        android.util.Log.i("HushCastPerf", "Qwen3 Embedder load: model=${paths.qwenEmbedder.name} segs=${segments.size}")
        val embeddings: FloatArray
        val embedder = Embedder(paths.qwenEmbedder.absolutePath)
        android.util.Log.i("HushCastPerf", "Qwen3 loaded in ${System.currentTimeMillis() - embedStart}ms (dim=${embedder.dim}, rss=${currentRssMb()}MB)")
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
                    onProgress(AnalysisProgress(pct, "Embedding sentences ($pct%)", ReadinessBadge.WORD_MUTE_READY))
                    if (pct % 5 == 0 || pct == 89) {
                        android.util.Log.i("HushCastPerf", "Qwen3 Embedder progress: $pct%")
                        writeStatusBlocking(pct, "Embedding sentences", ReadinessBadge.WORD_MUTE_READY)
                    }
                }
            }
            peakRssMb = maxOf(peakRssMb, currentRssMb())
        } finally {
            // Strictly unload Qwen3 before ONNX tagging
            embedder.close()
        }
        val embedMs = System.currentTimeMillis() - embedStart
        android.util.Log.i("HushCastPerf", "Stage Qwen3 Embedder done in ${embedMs}ms (peakRss=${peakRssMb}MB)")

        report(90, "Detecting sponsor segments", ReadinessBadge.WORD_MUTE_READY)
        val taggerStart = System.currentTimeMillis()
        val tagger = Tagger(modelManager.loadTaggerBytes())
        val adEntities = try {
            val probs = tagger.probs(embeddings, SegmentFeatures.compute(finalFrames, segments))
            val highSpans = Decoder.findSpans(probs, segments)
            val candidates = Decoder.findCandidates(probs, highSpans, segments)

            val list = ArrayList<AdSegmentEntity>(highSpans.size + candidates.size)
            for (s in highSpans) {
                list.add(
                    AdSegmentEntity(
                        fileSha256 = sha256,
                        episodeId = episodeId,
                        startMs = (s.start * 1000.0).toLong(),
                        endMs = (s.end * 1000.0).toLong(),
                        confidence = s.p.toFloat(),
                        isHighConfidence = true,
                        label = "Sponsor / Ad"
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
                        label = "Suggested Ad"
                    )
                )
            }
            list
        } finally {
            tagger.close()
        }
        val taggerMs = System.currentTimeMillis() - taggerStart
        android.util.Log.i("HushCastPerf", "Stage ONNX Tagger done in ${taggerMs}ms (ads=${adEntities.size})")

        setStage("Saving ad segments")
        dao.deleteAdSegments(sha256)
        if (adEntities.isNotEmpty()) {
            dao.insertAdSegments(adEntities)
        }

        val elapsed = System.currentTimeMillis() - startTime
        dao.upsertStatus(
            EpisodeAnalysisStatusEntity(
                episodeId = episodeId,
                fileSha256 = sha256,
                badge = ReadinessBadge.READY.name,
                progressPercent = 100,
                stageLabel = "Ready",
                peakRssMb = peakRssMb,
                analysisDurationMs = elapsed
            )
        )
        onProgress(AnalysisProgress(100, "Ready", ReadinessBadge.READY))
        android.util.Log.i("HushCastPerf", "Analysis READY in ${elapsed}ms (words=${words.size}, mutes=$muteCount, ads=${adEntities.size})")

        return AnalysisResult(sha256, ReadinessBadge.READY, words.size, muteCount, adEntities.size, peakRssMb, elapsed)
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
        dao.deleteMuteSegments(sha256)
        if (windows.isNotEmpty()) {
            dao.insertMuteSegments(
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
        }
        return windows.size
    }

    /**
     * Transcribes the whole buffer in 180-second chunks so long episodes report steady progress,
     * can be cancelled between (and, via abort_callback, within) chunks, and log per-chunk speed.
     */
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

        val totalChunks = kotlin.math.ceil(dur / CHUNK_SEC).toInt().coerceAtLeast(1)
        val allWords = ArrayList<Word>()
        val allSegments = ArrayList<Segment>()

        var start = 0.0
        var chunkIdx = 0
        while (start < dur - 0.1) {
            kotlinx.coroutines.yield()
            chunkIdx++
            val end = (start + CHUNK_SEC).coerceAtMost(dur)
            val t0 = System.currentTimeMillis()
            val basePct = (start / dur) * 100.0
            val spanPct = ((end - start) / dur) * 100.0
            val out = transcriber.transcribeRange(
                pcm = pcm,
                startSec = start,
                endSec = end,
                vadModelPath = vadModelStr,
                shouldAbort = shouldAbort,
                onProgress = { subPct ->
                    val overall = (basePct + (subPct * spanPct) / 100.0).toInt().coerceIn(0, 100)
                    onWhisperProgress?.invoke(overall)
                }
            )
            val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            val rt = ((end - start) * 1000.0) / dt
            android.util.Log.i(
                "HushCastPerf",
                "Whisper chunk $chunkIdx/$totalChunks: ${(end - start).toInt()}s in ${dt}ms (${String.format(java.util.Locale.US, "%.1f", rt)}x RT) words=${out.words.size}"
            )
            allWords.addAll(out.words)
            allSegments.addAll(out.segments)
            onWhisperProgress?.invoke(((end / dur) * 100.0).toInt().coerceIn(0, 100))
            start = end
        }
        return allWords to allSegments
    }

    /**
     * Verifies a feed transcript using 30-second spot-checks (start, middle, end).
     * If verified, uses Silero VAD to find uncovered speech gaps >= 3.5s and transcribes only those.
     * Falls back to chunked Whisper transcription if DAI shifted timestamps or spot-checks fail.
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
        if (dur < 90.0) {
            return transcribeChunked(transcriber, vadModelStr, pcm, shouldAbort, onWhisperProgress)
        }

        // 30-second spot checks at 10%, 50%, 85%
        val checkStarts = listOf(dur * 0.10, dur * 0.50, dur * 0.85)
        var passed = 0
        for (s in checkStarts) {
            kotlinx.coroutines.yield()
            val end = (s + 30.0).coerceAtMost(dur)
            val sampleOut = transcriber.transcribeRange(pcm, s, end, shouldAbort = shouldAbort)
            val sampleWords = sampleOut.words.map { WordMatcher.normalizeToken(it.text) }.filter { it.isNotEmpty() }.toSet()
            val feedWords = parsedFeed.words
                .filter { it.start >= s - 2.0 && it.end <= end + 2.0 }
                .map { WordMatcher.normalizeToken(it.text) }
                .filter { it.isNotEmpty() }
                .toSet()
            if (sampleWords.isNotEmpty() && feedWords.isNotEmpty()) {
                val overlap = sampleWords.intersect(feedWords).size.toDouble() / minOf(sampleWords.size, feedWords.size)
                if (overlap >= 0.55) passed++
            }
        }

        if (passed < 2) {
            // DAI shifted timestamps or transcript mismatch -> chunked Whisper with VAD
            return transcribeChunked(transcriber, vadModelStr, pcm, shouldAbort, onWhisperProgress)
        }

        // Feed transcript verified! Run Silero VAD to find uncovered speech gaps >= 3.5s
        val mergedWords = parsedFeed.words.toMutableList()
        val mergedSegments = parsedFeed.segments.toMutableList()
        if (vadPath.exists()) {
            val speechSpans = VadDetector.detectSpeech(vadPath.absolutePath, pcm, threshold = 0.35f, minSilenceDurationMs = 1000)
            for (span in speechSpans) {
                if (span.endSec - span.startSec < 3.5) continue
                val covered = parsedFeed.segments.any { seg ->
                    seg.end >= span.startSec + 1.0 && seg.start <= span.endSec - 1.0
                }
                if (!covered) {
                    kotlinx.coroutines.yield()
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
    }
}
