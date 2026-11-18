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
    val modelsLoaded: Boolean = false
)

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
    val reprocessStage: String? = null
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
