package app.hushcast.automute.dsp

import kotlinx.serialization.Serializable

@Serializable
enum class MuteActionMode {
    HARD_SILENCE,
    VOLUME_DUCK,
    SAMPLE_DROP_MICRO_SKIP
}

@Serializable
enum class AdPolicyMode {
    AUTO_SKIP,
    MARK_ONLY,
    OFF
}

enum class ReadinessBadge {
    NOT_PROCESSED,
    ANALYZING,
    WORD_MUTE_READY,
    READY
}

@Serializable
data class MuteWindow(
    val startMs: Long,
    val endMs: Long,
    val word: String,
    val ruleId: Long = 0L,
    val actionMode: MuteActionMode = MuteActionMode.HARD_SILENCE,
    val enabled: Boolean = true
)

@Serializable
data class AdWindow(
    val startMs: Long,
    val endMs: Long,
    val confidence: Float,
    val isHighConfidence: Boolean = confidence >= 0.80f,
    val label: String = if (confidence >= 0.80f) "Sponsor / Ad" else "Suggested Ad",
    val adClass: String = "sponsor_ad"
)
