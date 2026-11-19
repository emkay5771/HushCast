package app.hushcast.automute.transcript

import app.hushcast.automute.dsp.MuteWindow
import app.hushcast.automute.engine.Segment
import app.hushcast.automute.engine.TranscriptionOutput
import app.hushcast.automute.engine.Word
import app.hushcast.automute.matcher.MuteRule
import app.hushcast.automute.matcher.WordMatcher
import app.hushcast.automute.storage.BlobCodec
import app.hushcast.automute.storage.WordIndexBlob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs

class CueRealignerTest {

    private fun w(start: Double, text: String, dur: Double = 0.3) = Word(start, start + dur, " $text")

    @Test
    fun `cue words take the times Whisper heard, and padding words do not steal them`() {
        // Cue 10.0-13.0 "the cat sat on the mat", spread evenly (0.5 s each).
        val cue = CueRealigner.pseudoWords("the cat sat on the mat", 10.0, 13.0)
        // Heard +-2 s: the previous cue ends "...and the", then the cue, then "then".
        val heard = listOf(
            w(8.6, "and"), w(9.2, "the"),
            w(10.4, "the"), w(10.7, "cat"), w(11.6, "sat"), w(11.9, "on"), w(12.3, "the"), w(12.5, "mat"),
            w(13.6, "then")
        )
        val aligned = CueRealigner.alignTokens(cue, heard)
        assertEquals(6, aligned.matched)
        assertEquals(listOf(10.4, 10.7, 11.6, 11.9, 12.3, 12.5), aligned.words.map { it.start })
        assertEquals(cue.map { it.text }, aligned.words.map { it.text })
    }

    @Test
    fun `cue words Whisper missed are spread between their heard neighbours`() {
        val cue = CueRealigner.pseudoWords("one two three four five", 20.0, 22.5)
        val heard = listOf(w(20.2, "one"), w(21.9, "four"), w(22.4, "five"))
        val aligned = CueRealigner.alignTokens(cue, heard)
        assertEquals(3, aligned.matched)
        val s = aligned.words.map { it.start }
        assertEquals(20.2, s[0], 1e-9)
        // "two" and "three" share 20.5 .. 21.9 evenly.
        assertEquals(20.5, s[1], 1e-9)
        assertEquals(21.2, s[2], 1e-9)
        assertEquals(21.9, s[3], 1e-9)
        assertTrue(s.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `realign fixes only spans a rule matches and keeps failed ones as attempted`() = runBlocking {
        // Three cues with estimated words; the truth for "darn" (cue 2) is 31.85 s.
        val cue1 = CueRealigner.pseudoWords("hello there friends", 10.0, 12.0)
        val cue2 = CueRealigner.pseudoWords("well darn it all", 30.0, 33.0)
        val cue3 = CueRealigner.pseudoWords("darn again", 50.0, 51.0)
        val words = cue1 + cue2 + cue3
        val spans = listOf(ApproxSpan(10.0, 12.0), ApproxSpan(30.0, 33.0), ApproxSpan(50.0, 51.0))
        val heardByClip = mapOf(
            28.0 to listOf(w(30.9, "well"), w(31.85, "darn"), w(32.2, "it"), w(32.5, "all")),
            48.0 to listOf(w(49.0, "music")) // Whisper heard nothing useful for cue 3
        )
        val calls = ArrayList<Pair<Double, Double>>()
        val clips = ClipTranscriber { s, e ->
            calls.add(s to e)
            val abs = heardByClip[s].orEmpty()
            TranscriptionOutput(emptyList(), abs.map { it.copy(start = it.start - s, end = it.end - s) })
        }
        val compiled = WordMatcher.compileRules(listOf(MuteRule(id = 1, pattern = "darn")))

        assertEquals(2, CueRealigner.spansNeedingRealign(words, spans, compiled).size)
        val r = CueRealigner.realign(words, spans, compiled, durationSec = 100.0, clips = clips)

        assertEquals(listOf(28.0 to 35.0, 48.0 to 53.0), calls) // +-2 s around each matching cue
        assertEquals(1, r.realigned)
        assertEquals(1, r.failed)
        assertEquals(words.size, r.words.size)
        assertEquals(31.85, r.words.first { it.text == " darn" }.start, 1e-9)
        // Cue 1 (no match) stays approximate untouched; cue 3 failed and is not retried.
        assertEquals(listOf(ApproxSpan(10.0, 12.0), ApproxSpan(50.0, 51.0, attempted = true)), r.spans)
        assertTrue(CueRealigner.spansNeedingRealign(r.words, r.spans, compiled).isEmpty())
    }

    @Test
    fun `windows inside an approximate span widen to the span, at most 2 s each side`() {
        val spans = listOf(ApproxSpan(10.0, 13.0), ApproxSpan(30.0, 40.0))
        val windows = listOf(
            MuteWindow(11_000, 11_400, "a"),   // in span 1 -> 10.0 .. 13.0
            MuteWindow(35_000, 35_400, "b"),   // in span 2 -> capped at 33.0 .. 37.4
            MuteWindow(20_000, 20_400, "c")    // not approximate
        )
        val out = CueRealigner.widenApproximate(windows, spans)
        assertEquals(listOf(10_000L to 13_000L, 20_000L to 20_400L, 33_000L to 37_400L), out.map { it.startMs to it.endMs })
        assertEquals(windows, CueRealigner.widenApproximate(windows, emptyList()))
    }

    @Test
    fun `blobs written before approxSpans existed still decode`() {
        val oldJson = """{"words":[{"start":1.0,"end":1.4,"text":" hi"}],"segments":[{"start":1.0,"end":1.4,"text":"hi","nWords":1}]}"""
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(oldJson.toByteArray()) }
        val blob = BlobCodec.decompress(bos.toByteArray())
        assertEquals(1, blob.words.size)
        assertTrue(blob.approxSpans.isEmpty())

        val withSpans = WordIndexBlob(blob.words, listOf(Segment(1.0, 1.4, "hi")), listOf(ApproxSpan(1.0, 1.4)))
        assertEquals(withSpans, BlobCodec.decompress(BlobCodec.compress(withSpans)))
        assertFalse(abs(withSpans.approxSpans[0].end - 1.4) > 1e-9)
    }
}
