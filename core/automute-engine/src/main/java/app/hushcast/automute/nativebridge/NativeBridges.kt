package app.hushcast.automute.nativebridge

internal fun interface WhisperProgressCallback {
    fun onProgress(percent: Int)
}

internal object WhisperJni {
    init {
        System.loadLibrary("hushcast_whisper")
    }

    @JvmStatic
    external fun load(path: String, useDtw: Boolean, useFlashAttn: Boolean, isTiny: Boolean): Long

    @JvmStatic
    external fun transcribeRange(
        handle: Long,
        pcm: Long,
        startSample: Int,
        endSample: Int,
        threads: Int,
        language: String,
        beamSize: Int,
        vadModelPath: String?,
        progressCallback: WhisperProgressCallback?
    ): Array<Any>

    @JvmStatic
    external fun vadDetectSpeech(
        vadModelPath: String,
        pcm: Long,
        threads: Int,
        threshold: Float,
        minSilenceDurationMs: Int
    ): FloatArray

    @JvmStatic
    external fun free(handle: Long)

    @JvmStatic
    external fun pcmNew(capacity: Int): Long

    @JvmStatic
    external fun pcmAppend(pcm: Long, x: FloatArray, offset: Int, count: Int)

    @JvmStatic
    external fun pcmSize(pcm: Long): Int

    @JvmStatic
    external fun pcmFree(pcm: Long)
}

internal object LlamaJni {
    init {
        System.loadLibrary("hushcast_llama")
    }

    @JvmStatic
    external fun load(path: String, threads: Int): Long

    @JvmStatic
    external fun dim(handle: Long): Int

    @JvmStatic
    external fun embed(handle: Long, texts: Array<ByteArray>): FloatArray

    @JvmStatic
    external fun free(handle: Long)
}
