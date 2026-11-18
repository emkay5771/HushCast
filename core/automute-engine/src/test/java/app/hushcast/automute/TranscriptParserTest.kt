package app.hushcast.automute

import app.hushcast.automute.transcript.TranscriptFormat
import app.hushcast.automute.transcript.TranscriptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptParserTest {

    @Test
    fun parsesPodcasting20JsonWithWordTimings() {
        val json = """
            {
              "version": "1.0.0",
              "segments": [
                {
                  "startTime": 1.0,
                  "endTime": 2.5,
                  "body": "Hello world",
                  "words": [
                    { "startTime": 1.0, "endTime": 1.6, "word": "Hello" },
                    { "startTime": 1.7, "endTime": 2.5, "word": "world" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val parsed = TranscriptParser.parse(json, "application/json")
        assertEquals(TranscriptFormat.PODCASTING_20_JSON, parsed.format)
        assertTrue(parsed.hasWordLevelTimings)
        assertEquals(1, parsed.segments.size)
        assertEquals(2, parsed.words.size)
        assertEquals(" Hello", parsed.words[0].text)
    }

    @Test
    fun parsesVttAndSrtSubtitles() {
        val vtt = """
            WEBVTT

            00:00:01.000 --> 00:00:03.500
            Welcome to <b>HushCast</b>

            00:01:00.000 --> 00:01:02.000
            Second segment
        """.trimIndent()

        val parsed = TranscriptParser.parse(vtt, "text/vtt")
        assertEquals(TranscriptFormat.VTT, parsed.format)
        assertFalse(parsed.hasWordLevelTimings)
        assertEquals(2, parsed.segments.size)
        assertEquals(1.0, parsed.segments[0].start, 0.01)
        assertEquals("Welcome to HushCast", parsed.segments[0].text)
        assertEquals(60.0, parsed.segments[1].start, 0.01)
    }

    @Test
    fun parsesPodcasting20JsonWithSingleWordSegments() {
        val json = """
            {
              "version": "1.0.0",
              "segments": [
                { "startTime": 0.5, "endTime": 0.8, "body": "Hello" },
                { "startTime": 0.9, "endTime": 1.3, "body": "HushCast" }
              ]
            }
        """.trimIndent()
        val parsed = TranscriptParser.parse(json, "application/json")
        assertTrue(parsed.hasWordLevelTimings)
        assertEquals(2, parsed.words.size)
        assertEquals(" HushCast", parsed.words[1].text)
    }
}
