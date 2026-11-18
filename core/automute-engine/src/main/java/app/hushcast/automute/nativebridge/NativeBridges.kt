package app.hushcast.automute.nativebridge

/**
 * Called from native whisper_full(): onProgress() on progress ticks and shouldAbort() from
 * whisper's abort_callback so a cancelled WorkManager job stops native work promptly.
 */
internal class WhisperCallbacks(
    private val progressFn: ((Int) -> Unit)?,
    private val abortFn: () -> Boolean
) {
    fun onProgress(percent: Int) {
        progressFn?.invoke(percent)
    }

    fun shouldAbort(): Boolean = abortFn()
}

private fun hasArmDotProd(): Boolean = try {
    java.io.File("/proc/cpuinfo").takeIf { it.canRead() }?.useLines { lines ->
        lines.any { line ->
            line.startsWith("Features", ignoreCase = true) &&
                line.contains("asimddp", ignoreCase = true)
        }
    } == true
} catch (_: Throwable) {
    false
}

private fun loadNativeWithDotProd(baseName: String) {
    if (hasArmDotProd()) {
        try {
            System.loadLibrary("${baseName}_dp")
            return
        } catch (_: UnsatisfiedLinkError) {
        }
    }
    System.loadLibrary(baseName)
}

internal object WhisperJni {
    val isAvailable: Boolean = try {
        loadNativeWithDotProd("hushcast_whisper")
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
        callbacks: WhisperCallbacks?
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
    external fun pcmKeepTail(pcm: Long, keepSamples: Int)

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

    @JvmStatic
    external fun mp3Open(path: String): Long

    @JvmStatic
    external fun mp3SampleRate(handle: Long): Int

    @JvmStatic
    external fun mp3Channels(handle: Long): Int

    @JvmStatic
    external fun mp3TotalFrames(handle: Long): Long

    @JvmStatic
    external fun mp3ReadChunk(handle: Long, midOut: FloatArray, sideOut: FloatArray?): Int

    @JvmStatic
    external fun mp3Close(handle: Long)
}

internal object LlamaJni {
    init {
        loadNativeWithDotProd("hushcast_llama")
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
