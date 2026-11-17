package app.hushcast.automute.engine

import app.hushcast.automute.nativebridge.LlamaJni
import kotlin.math.max
import kotlin.math.min

/**
 * Text embeddings: Qwen3-Embedding-0.6B (q4_k_m GGUF) through llama.cpp.
 */
class Embedder(modelPath: String, threads: Int = defaultThreads()) : AutoCloseable {
    private var handle = LlamaJni.load(modelPath, threads)
    val dim = LlamaJni.dim(handle)

    @Synchronized
    fun embed(
        texts: List<String>,
        shouldCancel: () -> Boolean = { false },
        onProgress: ((Float) -> Unit)? = null
    ): FloatArray {
        check(handle != 0L) { "Embedder closed" }
        if (texts.isEmpty()) return FloatArray(0)
        val out = FloatArray(texts.size * dim)
        var i = 0
        while (i < texts.size) {
            if (shouldCancel()) {
                throw java.util.concurrent.CancellationException("Embedding cancelled")
            }
            val chunk = texts.subList(i, min(i + 64, texts.size))
            val v = LlamaJni.embed(handle, Array(chunk.size) { input(chunk[it]).toByteArray(Charsets.UTF_8) })
            System.arraycopy(v, 0, out, i * dim, v.size)
            i += chunk.size
            if (onProgress != null) {
                onProgress(i.toFloat() / texts.size.toFloat())
            }
        }
        return out
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            LlamaJni.free(handle)
            handle = 0
        }
    }

    companion object {
        const val MODEL_URL = "https://huggingface.co/dengcao/Qwen3-Embedding-0.6B-GGUF/resolve/999d5dcd1517b8812c32bde18bd674cff6d267fe/Qwen3-Embedding-0.6B-q4_k_m.gguf"
        const val MODEL_SHA256 = "c608745221a03d45ee7328aab5ae180ef5db54c9a47eda43ef05f73156ba824b"
        const val MODEL_SIZE = 394_704_832L
        const val MAX_CHARS = 2000

        fun input(text: String): String {
            val t = text.trim()
            val cut = if (t.codePointCount(0, t.length) > MAX_CHARS) {
                t.substring(0, t.offsetByCodePoints(0, MAX_CHARS))
            } else {
                t
            }
            return cut.ifEmpty { " " }
        }

        fun defaultThreads() = CpuTopology.performanceCoreCount()
    }
}
