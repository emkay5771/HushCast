package app.hushcast.automute

import app.hushcast.automute.engine.SentenceSplitter
import app.hushcast.automute.engine.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitterTest {

    /** faster-whisper style: back-to-back word times (no gaps), no punctuation. */
    private fun contiguous(n: Int, total: Double, startAt: Double = 0.0): List<Word> {
        val d = total / n
        return List(n) { i -> Word(startAt + i * d, startAt + (i + 1) * d, " w$i") }
    }

    @Test
    fun unpunctuatedContiguousWordsDoNotSplitOneWordAtATime() {
        val segs = SentenceSplitter.split(contiguous(90, 30.0))
        assertTrue("expected 2-3 segments, got ${segs.size}", segs.size in 2..3)
        segs.forEach { s ->
            assertTrue("segment too short: ${s.end - s.start}s", s.end - s.start >= SentenceSplitter.MIN_FALLBACK_S)
            assertTrue("segment has ${s.nWords} words", s.nWords >= SentenceSplitter.MIN_FALLBACK_WORDS)
        }
        assertEquals(90, segs.sumOf { it.nWords })
    }

    @Test
    fun prefersARealPauseOverTheMaxLengthBoundary() {
        val a = contiguous(20, 8.0)
        val b = contiguous(30, 10.0, startAt = 8.6) // 0.6 s pause after 8 s
        val segs = SentenceSplitter.split(a + b)
        assertEquals(20, segs.first().nWords)
    }

    @Test
    fun punctuationStillEndsSentences() {
        val words = listOf(
            Word(0.0, 0.4, " Hello"), Word(0.4, 0.8, " there."),
            Word(0.8, 1.2, " How"), Word(1.2, 1.6, " are"), Word(1.6, 2.0, " you?")
        )
        val segs = SentenceSplitter.split(words)
        assertEquals(listOf("Hello there.", "How are you?"), segs.map { it.text })
    }
}
