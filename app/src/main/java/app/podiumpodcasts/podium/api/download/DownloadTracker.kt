package app.podiumpodcasts.podium.api.download

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

data class DownloadProgress(
    val progressBytes: Long,
    val totalBytes: Long,
    val sourceLabel: String = ""
) {
    val fraction: Float
        get() = if (totalBytes > 0L) (progressBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val percent: Int
        get() = if (totalBytes > 0L) ((progressBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
}

object DownloadTracker {
    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    fun observe(episodeId: String): Flow<DownloadProgress?> =
        progress.map { it[episodeId] }.distinctUntilChanged()

    fun update(episodeId: String, progressBytes: Long, totalBytes: Long, sourceLabel: String = "") {
        _progress.update { current ->
            current + (episodeId to DownloadProgress(progressBytes, totalBytes, sourceLabel))
        }
    }

    fun clear(episodeId: String) {
        _progress.update { current ->
            if (episodeId in current) current - episodeId else current
        }
    }
}
