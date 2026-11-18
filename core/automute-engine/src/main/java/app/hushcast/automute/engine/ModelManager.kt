package app.hushcast.automute.engine

import android.content.Context
import java.io.File

data class ModelPaths(
    val whisperBaseEn: File,
    val whisperTinyEn: File,
    val sileroVad: File,
    val qwenEmbedder: File,
    val adTagger: File
)

data class ModelDownloadProgress(
    val modelName: String,
    val bytesDownloaded: Long,
    val totalBytes: Long
)

/**
 * Manages on-device AI models (~467 MB total with quantized models, Wi-Fi opt-in):
 * - ggml-base.en-q5_1.bin (~57 MB)
 * - ggml-tiny.en-q5_1.bin (~31 MB, optional fast mode)
 * - ggml-silero-v5.1.2.bin (~0.9 MB)
 * - Qwen3-Embedding-0.6B-q4_k_m.gguf (~376 MB, optional ad detection)
 * - ad_tagger.onnx (~12 MB, optional ad detection)
 */
class ModelManager(private val context: Context) {
    private val modelsDir: File
        get() = File(context.noBackupFilesDir, "hushcast_models").apply { mkdirs() }

    fun getPaths(): ModelPaths = ModelPaths(
        whisperBaseEn = File(modelsDir, "ggml-base.en-q5_1.bin"),
        whisperTinyEn = File(modelsDir, "ggml-tiny.en-q5_1.bin"),
        sileroVad = File(modelsDir, "ggml-silero-v5.1.2.bin"),
        qwenEmbedder = File(modelsDir, "Qwen3-Embedding-0.6B-q4_k_m.gguf"),
        adTagger = File(modelsDir, "ad_tagger.onnx")
    )

    fun areWordMuteModelsReady(): Boolean {
        val p = getPaths()
        val baseReady = p.whisperBaseEn.exists() && p.whisperBaseEn.length() == Transcriber.BASE_EN_SIZE
        val tinyReady = p.whisperTinyEn.exists() && p.whisperTinyEn.length() == Transcriber.TINY_EN_SIZE
        val vadReady = p.sileroVad.exists() && p.sileroVad.length() == Transcriber.SILERO_VAD_SIZE
        val whisperReady = if (HushCastPrefs.isFastModelEnabled(context)) tinyReady else baseReady
        return whisperReady && vadReady
    }

    fun isTinyModelReady(): Boolean {
        val p = getPaths()
        return p.whisperTinyEn.exists() && p.whisperTinyEn.length() == Transcriber.TINY_EN_SIZE
    }

    fun areAdModelsReady(): Boolean {
        val p = getPaths()
        return p.qwenEmbedder.exists() && p.qwenEmbedder.length() == Embedder.MODEL_SIZE &&
            p.adTagger.exists() && p.adTagger.length() > 0L
    }

    fun areAllModelsReady(): Boolean = areWordMuteModelsReady() && areAdModelsReady()

    fun totalDownloadedBytes(): Long {
        val p = getPaths()
        return listOf(p.whisperBaseEn, p.whisperTinyEn, p.sileroVad, p.qwenEmbedder, p.adTagger)
            .filter { it.exists() }
            .sumOf { it.length() }
    }

    fun taggerFile(): File {
        val p = getPaths()
        if (p.adTagger.exists() && p.adTagger.length() > 0L) {
            return p.adTagger
        }
        error("Ad tagger model not downloaded. Download models in HushCast setup.")
    }

    fun loadTaggerBytes(): ByteArray = taggerFile().readBytes()

    fun ensureWordMuteModels(
        shouldAbort: () -> Boolean = { false },
        onProgress: (ModelDownloadProgress) -> Unit = {}
    ): ModelPaths {
        cleanupLegacyUnquantizedModels()
        val p = getPaths()
        if (HushCastPrefs.isFastModelEnabled(context)) {
            Downloads.fetch(
                Transcriber.TINY_EN_URL,
                p.whisperTinyEn,
                Transcriber.TINY_EN_SHA256,
                shouldAbort
            ) { done, total ->
                onProgress(ModelDownloadProgress("Whisper tiny.en q5_1 (31 MB)", done, total))
            }
        } else {
            Downloads.fetch(
                Transcriber.BASE_EN_URL,
                p.whisperBaseEn,
                Transcriber.BASE_EN_SHA256,
                shouldAbort
            ) { done, total ->
                onProgress(ModelDownloadProgress("Whisper base.en q5_1 (57 MB)", done, total))
            }
        }
        Downloads.fetch(
            Transcriber.SILERO_VAD_URL,
            p.sileroVad,
            Transcriber.SILERO_VAD_SHA256,
            shouldAbort
        ) { done, total ->
            onProgress(ModelDownloadProgress("Silero VAD (1 MB)", done, total))
        }
        return p
    }

    fun ensureTinyModel(
        shouldAbort: () -> Boolean = { false },
        onProgress: (ModelDownloadProgress) -> Unit = {}
    ): ModelPaths {
        val p = getPaths()
        Downloads.fetch(
            Transcriber.TINY_EN_URL,
            p.whisperTinyEn,
            Transcriber.TINY_EN_SHA256,
            shouldAbort
        ) { done, total ->
            onProgress(ModelDownloadProgress("Whisper tiny.en q5_1 (31 MB)", done, total))
        }
        return p
    }

    fun ensureAllModels(
        shouldAbort: () -> Boolean = { false },
        includeAdModels: Boolean = true,
        onProgress: (ModelDownloadProgress) -> Unit = {}
    ): ModelPaths {
        val p = ensureWordMuteModels(shouldAbort, onProgress)
        if (includeAdModels) {
            Downloads.fetch(
                Tagger.TAGGER_URL,
                p.adTagger,
                Tagger.TAGGER_SHA256,
                shouldAbort
            ) { done, total ->
                onProgress(ModelDownloadProgress("Ad Tagger ONNX (12 MB)", done, total))
            }
            Downloads.fetch(
                Embedder.MODEL_URL,
                p.qwenEmbedder,
                Embedder.MODEL_SHA256,
                shouldAbort
            ) { done, total ->
                onProgress(ModelDownloadProgress("Qwen3 Embedder Q4 (376 MB)", done, total))
            }
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
