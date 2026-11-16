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
    val cachedEpisodes: Int = 0
)

@Serializable
data class DesktopJobRequest(
    val episodeId: String,
    val audioUrl: String,
    val title: String = "",
    val feedTranscriptRaw: String? = null,
    val feedTranscriptMime: String = ""
)

@Serializable
data class DesktopJobResponse(
    val jobId: String,
    val episodeId: String,
    val state: String,
    val progressPercent: Int = 0,
    val stageLabel: String = "",
    val fileSha256: String? = null,
    val errorMessage: String? = null
)

@Serializable
data class DesktopAdSegmentDto(
    val startMs: Long,
    val endMs: Long,
    val confidence: Float,
    val isHighConfidence: Boolean,
    val label: String
)

@Serializable
data class DesktopAnalysisBundle(
    val fileSha256: String,
    val episodeId: String,
    val durationSec: Double,
    val wordIndexBlob: WordIndexBlob,
    val adSegments: List<DesktopAdSegmentDto> = emptyList(),
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
