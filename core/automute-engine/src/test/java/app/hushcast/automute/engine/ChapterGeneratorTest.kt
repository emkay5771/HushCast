package app.hushcast.automute.engine

import app.hushcast.automute.storage.AdSegmentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterGeneratorTest {

    @Test
    fun returnsEmptyWhenTooFewSegments() {
        val segs = listOf(
            Segment(0.0, 5.0, "Hello world today"),
            Segment(5.0, 10.0, "Second short segment")
        )
        val emb = FloatArray(segs.size * 4) { 1f }
        assertTrue(ChapterGenerator.generateChapters(segs, emb, 4, emptyList()).isEmpty())
    }

    @Test
    fun detectsTopicShiftBoundariesAndExcludesAds() {
        // 3 distinct topics across ~18 minutes (36 segments, 30s each), with an ad in the middle
        val dim = 4
        val segments = ArrayList<Segment>()
        val embList = ArrayList<Float>()

        fun addSegment(idx: Int, text: String, vec: FloatArray) {
            segments.add(Segment(idx * 30.0, (idx + 1) * 30.0, text))
            vec.forEach { embList.add(it) }
        }

        // Topic 1: Quantum computing (0..11) -> [1, 0, 0, 0]
        for (i in 0 until 12) {
            addSegment(i, "Quantum computing qubits superconducting processors quantum research", floatArrayOf(1f, 0f, 0f, 0f))
        }
        // Ad break (12..13) -> [0, 0, 0, 1]
        for (i in 12 until 14) {
            addSegment(i, "Sponsor promo code mattress discount sleep better tonight", floatArrayOf(0f, 0f, 0f, 1f))
        }
        // Topic 2: Mars exploration (14..24) -> [0, 1, 0, 0]
        for (i in 14 until 25) {
            addSegment(i, "Mars rover Perseverance crater geology Martian atmosphere", floatArrayOf(0f, 1f, 0f, 0f))
        }
        // Topic 3: Sourdough baking (25..35) -> [0, 0, 1, 0]
        for (i in 25 until 36) {
            addSegment(i, "Sourdough starter fermentation artisan bread crust hydration", floatArrayOf(0f, 0f, 1f, 0f))
        }

        val ads = listOf(
            AdSegmentEntity(
                fileSha256 = "abc",
                episodeId = "ep1",
                startMs = 12 * 30_000L,
                endMs = 14 * 30_000L,
                confidence = 0.95f,
                isHighConfidence = true,
                label = "Sponsor / Ad",
                adClass = "sponsor_ad"
            )
        )

        val chapters = ChapterGenerator.generateChapters(segments, embList.toFloatArray(), dim, ads)
        assertTrue("Expected at least 3 chapters, got ${chapters.size}", chapters.size >= 3)
        assertEquals(0L, chapters.first().startMs)
        assertTrue(chapters.all { it.source == "auto" })
        // None of the chapters should be titled after the ad
        assertTrue(chapters.none { it.title.contains("Mattress", ignoreCase = true) })
        // Topic titles should reflect the distinct topics
        val allTitles = chapters.joinToString(" | ") { it.title }
        assertTrue(
            "Expected Topic 1 terms in titles: $allTitles",
            listOf("Quantum", "Qubits", "Superconducting").any { allTitles.contains(it, ignoreCase = true) }
        )
        assertTrue(
            "Expected Topic 2 terms in titles: $allTitles",
            listOf("Mars", "Rover", "Perseverance", "Crater", "Martian").any { allTitles.contains(it, ignoreCase = true) }
        )
        assertTrue(
            "Expected Topic 3 terms in titles: $allTitles",
            listOf("Sourdough", "Starter", "Fermentation", "Artisan", "Bread").any { allTitles.contains(it, ignoreCase = true) }
        )
    }

    @Test
    fun benchmarkOneHourEpisodePerformance() {
        // 1-hour episode: ~600 sentences, 1024-dim embeddings (Qwen3-Embedding-0.6B)
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
        val chapters = ChapterGenerator.generateChapters(segments, embeddings, dim, emptyList())
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0

        assertTrue("Expected multiple chapters, got ${chapters.size}", chapters.size >= 4)
        assertTrue("Chapter generation should be fast (<500ms), was ${elapsedMs}ms", elapsedMs < 500.0)
        println("ChapterGenerator 1h benchmark (600 segs, 1024d): ${String.format("%.2f", elapsedMs)} ms, ${chapters.size} chapters")
    }
}
