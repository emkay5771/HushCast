package app.hushcast.automute.nativebridge

internal fun interface WhisperProgressCallback {
    fun onProgress(percent: Int)
}

internal object WhisperJni {
    val isAvailable: Boolean = try {
        System.loadLibrary("hushcast_whisper")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
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

    @JvmStatic
    external fun resamplerNew(inRate: Int, outRate: Int): Long

    @JvmStatic
    external fun resamplerProcess(handle: Long, input: FloatArray, offset: Int, count: Int): FloatArray

    @JvmStatic
    external fun resamplerFinish(handle: Long): FloatArray

    @JvmStatic
    external fun resamplerFree(handle: Long)

    @JvmStatic
    external fun fftNew(n: Int): Long

    @JvmStatic
    external fun fftTransform(handle: Long, re: DoubleArray, im: DoubleArray, inverse: Boolean)

    @JvmStatic
    external fun fftFree(handle: Long)
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
