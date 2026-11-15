package app.hushcast.automute.engine

import android.content.Context
import java.io.File

data class ModelPaths(
    val whisperBaseEn: File,
    val sileroVad: File,
    val qwenEmbedder: File
)

data class ModelDownloadProgress(
    val modelName: String,
    val bytesDownloaded: Long,
    val totalBytes: Long
)

/**
 * Manages on-device AI models (~800 MB total, Wi-Fi opt-in):
 * - ggml-base.en.bin (148 MB)
 * - ggml-silero-v5.1.2.bin (~2 MB)
 * - Qwen3-Embedding-0.6B-Q8_0.gguf (~640 MB)
 * - ad_tagger.onnx (12 MB, bundled in APK assets)
 */
class ModelManager(private val context: Context) {
    private val modelsDir: File
        get() = File(context.noBackupFilesDir, "hushcast_models").apply { mkdirs() }

    fun getPaths(): ModelPaths = ModelPaths(
        whisperBaseEn = File(modelsDir, "ggml-base.en.bin"),
        sileroVad = File(modelsDir, "ggml-silero-v5.1.2.bin"),
        qwenEmbedder = File(modelsDir, "Qwen3-Embedding-0.6B-Q8_0.gguf")
    )

    fun areWordMuteModelsReady(): Boolean {
        val p = getPaths()
        return p.whisperBaseEn.exists() && p.whisperBaseEn.length() > 100_000_000L
    }

    fun areAllModelsReady(): Boolean {
        val p = getPaths()
        return areWordMuteModelsReady() &&
            p.sileroVad.exists() && p.sileroVad.length() > 500_000L &&
            p.qwenEmbedder.exists() && p.qwenEmbedder.length() > 500_000_000L
    }

    fun totalDownloadedBytes(): Long {
        val p = getPaths()
        return listOf(p.whisperBaseEn, p.sileroVad, p.qwenEmbedder)
            .filter { it.exists() }
            .sumOf { it.length() }
    }

    fun loadTaggerBytes(): ByteArray {
        return context.assets.open("ad_tagger.onnx").use { it.readBytes() }
    }

    fun ensureWordMuteModels(onProgress: (ModelDownloadProgress) -> Unit = {}): ModelPaths {
        val p = getPaths()
        Downloads.fetch(Transcriber.BASE_EN_URL, p.whisperBaseEn) { done, total ->
            onProgress(ModelDownloadProgress("Whisper base.en (148 MB)", done, total))
        }
        Downloads.fetch(Transcriber.SILERO_VAD_URL, p.sileroVad) { done, total ->
            onProgress(ModelDownloadProgress("Silero VAD (2 MB)", done, total))
        }
        return p
    }

    fun ensureAllModels(onProgress: (ModelDownloadProgress) -> Unit = {}): ModelPaths {
        val p = ensureWordMuteModels(onProgress)
        Downloads.fetch(Embedder.MODEL_URL, p.qwenEmbedder, Embedder.MODEL_SHA256) { done, total ->
            onProgress(ModelDownloadProgress("Qwen3 Embedder (640 MB)", done, total))
        }
        return p
    }

    fun deleteAllModels() {
        modelsDir.deleteRecursively()
    }
}
