package app.hushcast.automute.engine

import app.hushcast.automute.nativebridge.WhisperJni
import kotlin.math.roundToInt

/**
 * 16 kHz mono float samples for whisper, kept in native memory off the Java heap.
 */
class PcmBuffer(initialSamples: Int = 16000 * 60 * 10) : AutoCloseable {
    internal var handle = WhisperJni.pcmNew(initialSamples)
        private set
    var size = 0
        private set

    val durationSeconds: Double
        get() = size / 16000.0

    fun append(x: FloatArray, offset: Int = 0, count: Int = x.size - offset) {
        check(handle != 0L) { "PcmBuffer closed" }
        WhisperJni.pcmAppend(handle, x, offset, count)
        size += count
    }

    override fun close() {
        if (handle != 0L) {
            WhisperJni.pcmFree(handle)
            handle = 0
        }
    }
}

data class TranscriptionOutput(
    val segments: List<Segment>,
    val words: List<Word>
)

data class SpeechSpan(
    val startSec: Double,
    val endSec: Double
)

/**
 * Speech to text with whisper.cpp + DTW token timestamps (for base.en word alignment)
 * and range-based transcription for 30s spot-checks and uncovered speech gaps.
 */
class Transcriber(
    modelPath: String,
    private val threads: Int = Embedder.defaultThreads(),
    private val language: String = "en",
    private val beamSize: Int = 1,
    useDtw: Boolean = true,
) : AutoCloseable {
    private var handle = WhisperJni.load(modelPath, useDtw)

    @Synchronized
    fun transcribe(pcm: PcmBuffer, onProgress: ((Int) -> Unit)? = null): TranscriptionOutput {
        return transcribeRange(pcm, 0.0, null, onProgress)
    }

    @Synchronized
    fun transcribeRange(
        pcm: PcmBuffer,
        startSec: Double = 0.0,
        endSec: Double? = null,
        onProgress: ((Int) -> Unit)? = null
    ): TranscriptionOutput {
        check(handle != 0L) { "Transcriber closed" }
        if (pcm.size == 0) return TranscriptionOutput(emptyList(), emptyList())

        val startSample = (startSec * 16000.0).roundToInt().coerceIn(0, pcm.size)
        val endSample = endSec?.let { (it * 16000.0).roundToInt().coerceIn(startSample, pcm.size) } ?: -1

        val cb = onProgress?.let { app.hushcast.automute.nativebridge.WhisperProgressCallback { pct -> it(pct) } }
        val r = WhisperJni.transcribeRange(handle, pcm.handle, startSample, endSample, threads, language, beamSize, cb)
        val times = r[0] as FloatArray
        val texts = r[1] as Array<*>

        val segments = ArrayList<Segment>()
        val allWords = ArrayList<Word>(texts.size)
        var group = ArrayList<Word>()
        var current = -1

        for (i in texts.indices) {
            val seg = times[3 * i].toInt()
            if (seg != current && group.isNotEmpty()) {
                segments += SentenceSplitter.split(group)
                group = ArrayList()
            }
            current = seg
            val start = times[3 * i + 1].toDouble()
            val end = times[3 * i + 2].toDouble().coerceAtLeast(start + 0.01)
            val wordText = String(texts[i] as ByteArray, Charsets.UTF_8)
            val word = Word(start, end, wordText)
            allWords.add(word)
            group.add(word)
        }
        if (group.isNotEmpty()) {
            segments += SentenceSplitter.split(group)
        }
        val normalizedSegments = segments.map {
            if (it.end <= it.start) it.copy(end = it.start + 0.01) else it
        }
        return TranscriptionOutput(normalizedSegments, allWords)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            WhisperJni.free(handle)
            handle = 0
        }
    }

    companion object {
        const val BASE_EN_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin"
        const val SILERO_VAD_URL = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin"
    }
}

object VadDetector {
    /**
     * Runs ggml Silero VAD conservatively (threshold = 0.35, minSilenceDurationMs = 1000)
     * to detect speech spans across the episode.
     */
    fun detectSpeech(
        vadModelPath: String,
        pcm: PcmBuffer,
        threads: Int = Embedder.defaultThreads(),
        threshold: Float = 0.35f,
        minSilenceDurationMs: Int = 1000
    ): List<SpeechSpan> {
        if (pcm.size == 0) return emptyList()
        val raw = WhisperJni.vadDetectSpeech(vadModelPath, pcm.handle, threads, threshold, minSilenceDurationMs)
        val out = ArrayList<SpeechSpan>(raw.size / 2)
        var i = 0
        while (i + 1 < raw.size) {
            out.add(SpeechSpan(raw[i].toDouble(), raw[i + 1].toDouble()))
            i += 2
        }
        return out
    }
}
