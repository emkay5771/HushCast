package app.hushcast.automute.desktop

import app.hushcast.automute.storage.WordIndexBlob
import kotlinx.serialization.Serializable

@Serializable
data class DesktopHealthResponse(
    val status: String = "ok",
    val version: String = "",
    val hostname: String = "",
    val accelerator: String = "cpu",
    val modelsReady: Boolean = false,
    val whisperModel: String = "",
    val chatModelReady: Boolean = false,
    val cachedEpisodes: Int = 0,
    val apiVersion: Int = 1,
    val pipelineVersion: Int = 1,
    val deployment: String = "local",
    val gpuName: String? = null,
    val modelsLoaded: Boolean = false,
    /** Jobs waiting for the worker (optional; older workers don't send it). */
    val queuePosition: Int? = null
) {
    /** Cloud (Modal) worker: a queued job may be waiting for a GPU container to start. */
    val isCloud: Boolean get() = deployment.equals("modal", ignoreCase = true)

    /** Older than this app expects: results still work but may miss newer fixes (V15). */
    fun isOutdated(
        currentPipelineVersion: Int = app.hushcast.automute.pipeline.AutoMuteAnalyzer.CURRENT_PIPELINE_VERSION,
        minApiVersion: Int = DesktopWorkerClient.MIN_WORKER_API_VERSION
    ): Boolean = pipelineVersion < currentPipelineVersion || apiVersion < minApiVersion
}

@Serializable
data class DesktopJobRequest(
    val episodeId: String,
    val audioUrl: String,
    val title: String = "",
    val feedTranscriptRaw: String? = null,
    val feedTranscriptMime: String = "",
    val feedTranscriptUrl: String? = null,
    val feedChaptersUrl: String? = null,
    val feedChaptersType: String? = null,
    val language: String? = null,
    val force: Boolean = false,
    val reprocessStage: String? = null,
    /** Helps the worker's transcription prompt (spellings of names and the show). */
    val podcastTitle: String? = null
)

@Serializable
data class DesktopJobResponse(
    val jobId: String,
    val episodeId: String,
    val state: String,
    val progressPercent: Int = 0,
    val stageLabel: String = "",
    val fileSha256: String? = null,
    val errorMessage: String? = null,
    val stage: String? = null,
    val stageProgress: Float = 0f,
    val errorCode: String? = null,
    val retryable: Boolean = false,
    val queuePosition: Int = 0,
    val etaSec: Int? = null,
    val rev: Long = 0L
)

@Serializable
data class DesktopAdSegmentDto(
    val startMs: Long,
    val endMs: Long,
    val confidence: Float,
    val isHighConfidence: Boolean,
    val label: String,
    val adClass: String = "sponsor_ad"
)

@Serializable
data class DesktopChapterDto(
    val startMs: Long,
    val title: String,
    val source: String = "auto"
)

@Serializable
data class DesktopAnalysisBundle(
    val fileSha256: String,
    val episodeId: String,
    val durationSec: Double,
    val wordIndexBlob: WordIndexBlob,
    val adSegments: List<DesktopAdSegmentDto> = emptyList(),
    val summary: String? = null,
    val chapters: List<DesktopChapterDto> = emptyList(),
    val whisperModel: String = "",
    val analysisDurationMs: Long = 0L,
    val workerAccelerator: String = "cpu"
)

@Serializable
data class DesktopReadyEpisode(
    val fileSha256: String,
    val episodeId: String,
    val title: String = "",
    val durationSec: Double = 0.0,
    val audioBytes: Long = 0L,
    val wordCount: Int = 0,
    val adCount: Int = 0,
    val createdAtMs: Long = 0L
)

@Serializable
data class DesktopSearchRequest(
    val query: String,
    val topK: Int = 15
)

@Serializable
data class DesktopSearchHit(
    val fileSha256: String,
    val episodeId: String,
    val episodeTitle: String = "",
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val score: Float
)

@Serializable
data class DesktopSearchResponse(
    val query: String,
    val hits: List<DesktopSearchHit> = emptyList()
)

/** Short, user-facing text for worker states (pure, so it's unit-tested). */
object DesktopWorkerStatusText {
    const val OUTDATED_NOTE = "Worker is out of date. Update it for the best results"

    /** How long a job may sit in QUEUED before the phone explains the wait (V20). */
    const val COLD_START_AFTER_MS = 10_000L

    /**
     * The label for a job that's been QUEUED for [queuedForMs], or null while that's still short.
     * [jobQueuePosition] is the job's "jobs ahead of it"; [healthQueuePosition] the worker's queue
     * length from /v1/health.
     */
    fun queuedLabel(
        queuedForMs: Long,
        isCloud: Boolean,
        jobQueuePosition: Int = 0,
        healthQueuePosition: Int? = null
    ): String? {
        if (queuedForMs < COLD_START_AFTER_MS) return null
        val base = if (isCloud) "Starting the cloud GPU…" else "Waiting for the worker…"
        return when {
            jobQueuePosition > 0 -> "$base ($jobQueuePosition ahead)"
            healthQueuePosition != null && healthQueuePosition > 0 -> "$base ($healthQueuePosition in queue)"
            else -> base
        }
    }

    /**
     * A worker's failure reason, short enough for an episode row (V10): first non-blank line,
     * without "Desktop worker failed:" or a leading `SomeError:` class name, at most [maxLen].
     */
    fun shortReason(raw: String?, maxLen: Int = 80): String? {
        var line = raw?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
        line = line.removePrefix("Desktop worker failed:").trim()
        line = ERROR_CLASS_PREFIX.replace(line, "").trim()
        line = WHITESPACE.replace(line, " ")
        if (line.isEmpty()) return null
        line = line.replaceFirstChar { it.uppercaseChar() }
        return if (line.length <= maxLen) line else line.take(maxLen - 1).trimEnd() + "…"
    }

    private val ERROR_CLASS_PREFIX = Regex("^(?:[A-Za-z_][\\w.]*(?:Error|Exception)):\\s*")
    private val WHITESPACE = Regex("\\s+")
}
