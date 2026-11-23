package app.hushcast.automute.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChapterGeneratorInstrumentedTest {

    @Test
    fun benchmarkOnDeviceChapterGeneration() {
        // 1-hour episode: 600 sentences, 1024-dim Qwen3 embeddings
        val n = 600
        val dim = 1024
        val segments = List(n) { i ->
            val topic = i / 100
            Segment(
                start = i * 6.0,
                end = (i + 1) * 6.0,
                text = "Episode topic$topic discussion about technology science podcasts chapter$topic details"
            )
        }
        val embeddings = FloatArray(n * dim) { idx ->
            val row = idx / dim
            val col = idx % dim
            val topic = row / 100
            if (col % 6 == topic) 1.0f else 0.01f
        }

        val t0 = System.nanoTime()
        val coldChapters = ChapterGenerator.generateChapters(segments, embeddings, dim, emptyList())
        val coldMs = (System.nanoTime() - t0) / 1_000_000.0

        val warmTimes = DoubleArray(5) {
            val s = System.nanoTime()
            ChapterGenerator.generateChapters(segments, embeddings, dim, emptyList())
            (System.nanoTime() - s) / 1_000_000.0
        }
        val avgWarmMs = warmTimes.average()

        Log.i(
            "HushCastPerf",
            "On-device ART ChapterGenerator (600 segs, 1024d): cold=${String.format("%.2f", coldMs)}ms, warmAvg=${String.format("%.2f", avgWarmMs)}ms, chapters=${coldChapters.size}"
        )
        assertTrue("Expected >= 4 chapters, got ${coldChapters.size}", coldChapters.size >= 4)
        assertTrue("Expected cold < 2000ms, got ${coldMs}ms", coldMs < 2000.0)
    }
}
