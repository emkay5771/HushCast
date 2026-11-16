package app.hushcast.automute.engine

import android.content.Context
import java.io.File

data class ModelPaths(
    val whisperBaseEn: File,
    val whisperTinyEn: File,
    val sileroVad: File,
    val qwenEmbedder: File
)

data class ModelDownloadProgress(
    val modelName: String,
    val bytesDownloaded: Long,
    val totalBytes: Long
)

/**
 * Manages on-device AI models (~455 MB total with quantized models, Wi-Fi opt-in):
 * - ggml-base.en-q5_1.bin (~57 MB)
 * - ggml-tiny.en-q5_1.bin (~31 MB, optional fast mode)
 * - ggml-silero-v5.1.2.bin (~2 MB)
 * - Qwen3-Embedding-0.6B-q4_k_m.gguf (~395 MB)
 * - ad_tagger.onnx (12 MB, bundled in APK assets)
 */
class ModelManager(private val context: Context) {
    private val modelsDir: File
        get() = File(context.noBackupFilesDir, "hushcast_models").apply { mkdirs() }

    fun getPaths(): ModelPaths = ModelPaths(
        whisperBaseEn = File(modelsDir, "ggml-base.en-q5_1.bin"),
        whisperTinyEn = File(modelsDir, "ggml-tiny.en-q5_1.bin"),
        sileroVad = File(modelsDir, "ggml-silero-v5.1.2.bin"),
        qwenEmbedder = File(modelsDir, "Qwen3-Embedding-0.6B-q4_k_m.gguf")
    )

    fun areWordMuteModelsReady(): Boolean {
        val p = getPaths()
        val baseReady = p.whisperBaseEn.exists() && p.whisperBaseEn.length() > 40_000_000L
        val tinyReady = p.whisperTinyEn.exists() && p.whisperTinyEn.length() > 20_000_000L
        return if (HushCastPrefs.isFastModelEnabled(context)) tinyReady else baseReady
    }

    fun isTinyModelReady(): Boolean {
        val p = getPaths()
        return p.whisperTinyEn.exists() && p.whisperTinyEn.length() > 20_000_000L
    }

    fun areAllModelsReady(): Boolean {
        val p = getPaths()
        return areWordMuteModelsReady() &&
            p.sileroVad.exists() && p.sileroVad.length() > 500_000L &&
            p.qwenEmbedder.exists() && p.qwenEmbedder.length() > 300_000_000L
    }

    fun totalDownloadedBytes(): Long {
        val p = getPaths()
        return listOf(p.whisperBaseEn, p.whisperTinyEn, p.sileroVad, p.qwenEmbedder)
            .filter { it.exists() }
            .sumOf { it.length() }
    }

    fun loadTaggerBytes(): ByteArray {
        return context.assets.open("ad_tagger.onnx").use { it.readBytes() }
    }

    fun ensureWordMuteModels(onProgress: (ModelDownloadProgress) -> Unit = {}): ModelPaths {
        cleanupLegacyUnquantizedModels()
        val p = getPaths()
        if (HushCastPrefs.isFastModelEnabled(context)) {
            Downloads.fetch(Transcriber.TINY_EN_URL, p.whisperTinyEn) { done, total ->
                onProgress(ModelDownloadProgress("Whisper tiny.en q5_1 (32 MB)", done, total))
            }
        } else {
            Downloads.fetch(Transcriber.BASE_EN_URL, p.whisperBaseEn) { done, total ->
                onProgress(ModelDownloadProgress("Whisper base.en q5_1 (58 MB)", done, total))
            }
        }
        Downloads.fetch(Transcriber.SILERO_VAD_URL, p.sileroVad) { done, total ->
            onProgress(ModelDownloadProgress("Silero VAD (2 MB)", done, total))
        }
        return p
    }

    fun ensureTinyModel(onProgress: (ModelDownloadProgress) -> Unit = {}): ModelPaths {
        val p = getPaths()
        Downloads.fetch(Transcriber.TINY_EN_URL, p.whisperTinyEn) { done, total ->
            onProgress(ModelDownloadProgress("Whisper tiny.en q5_1 (32 MB)", done, total))
        }
        return p
    }

    fun ensureAllModels(onProgress: (ModelDownloadProgress) -> Unit = {}): ModelPaths {
        val p = ensureWordMuteModels(onProgress)
        Downloads.fetch(Embedder.MODEL_URL, p.qwenEmbedder, Embedder.MODEL_SHA256) { done, total ->
            onProgress(ModelDownloadProgress("Qwen3 Embedder Q4 (395 MB)", done, total))
        }
        return p
    }

    fun deleteAllModels() {
        modelsDir.deleteRecursively()
    }

    private fun cleanupLegacyUnquantizedModels() {
        listOf("ggml-base.en.bin", "Qwen3-Embedding-0.6B-Q8_0.gguf").forEach { oldName ->
            val oldFile = File(modelsDir, oldName)
            if (oldFile.exists()) oldFile.delete()
        }
    }
}
