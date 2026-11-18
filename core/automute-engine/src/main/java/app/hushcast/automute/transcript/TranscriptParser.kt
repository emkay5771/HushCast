package app.hushcast.automute.transcript

import app.hushcast.automute.engine.Segment
import app.hushcast.automute.engine.Word
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class TranscriptFormat {
    PODCASTING_20_JSON,
    SRT,
    VTT
}

@Serializable
data class ParsedTranscript(
    val format: TranscriptFormat,
    val segments: List<Segment>,
    val words: List<Word>,
    val hasWordLevelTimings: Boolean
)

/**
 * Parses Podcasting 2.0 transcripts (JSON, SRT, VTT).
 */
object TranscriptParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String, mimeTypeOrExt: String = ""): ParsedTranscript {
        val hint = mimeTypeOrExt.lowercase()
        return when {
            hint.contains("json") || raw.trimStart().startsWith("{") -> parsePodcasting20Json(raw)
            hint.contains("vtt") || raw.trimStart().startsWith("WEBVTT") -> parseSrtOrVtt(raw, TranscriptFormat.VTT)
            else -> parseSrtOrVtt(raw, TranscriptFormat.SRT)
        }
    }

    fun parsePodcasting20Json(raw: String): ParsedTranscript {
        val root = json.parseToJsonElement(raw).jsonObject
        val segArray: JsonArray = root["segments"]?.jsonArray ?: JsonArray(emptyList())
        val segments = ArrayList<Segment>(segArray.size)
        val allWords = ArrayList<Word>()
        var hasWordTimings = false

        for (elem in segArray) {
            val obj: JsonObject = elem.jsonObject
            val start = obj["startTime"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val end = obj["endTime"]?.jsonPrimitive?.doubleOrNull ?: (start + 0.1)
            val body = obj["body"]?.jsonPrimitive?.content ?: ""

            val wordArr = obj["words"]?.jsonArray
            if (wordArr != null && wordArr.isNotEmpty()) {
                hasWordTimings = true
                for (wElem in wordArr) {
                    val wObj = wElem.jsonObject
                    val ws = wObj["startTime"]?.jsonPrimitive?.doubleOrNull ?: start
                    val we = wObj["endTime"]?.jsonPrimitive?.doubleOrNull ?: (ws + 0.05)
                    val wText = wObj["word"]?.jsonPrimitive?.content ?: wObj["body"]?.jsonPrimitive?.content ?: ""
                    if (wText.isNotBlank()) {
                        allWords.add(Word(ws, we.coerceAtLeast(ws + 0.01), " $wText"))
                    }
                }
            }
            if (body.isNotBlank()) {
                segments.add(Segment(start, end.coerceAtLeast(start + 0.01), body.trim()))
            }
        }
        // A6: Some Podcasting 2.0 feeds emit one word per segment without a nested words array.
        if (!hasWordTimings && segments.isNotEmpty()) {
            val singleTokenCount = segments.count { !it.text.any { ch -> ch.isWhitespace() } }
            if (singleTokenCount * 10 >= segments.size * 8) {
                hasWordTimings = true
                for (seg in segments) {
                    allWords.add(Word(seg.start, seg.end, " ${seg.text}"))
                }
            }
        }
        return ParsedTranscript(
            format = TranscriptFormat.PODCASTING_20_JSON,
            segments = segments,
            words = allWords,
            hasWordLevelTimings = hasWordTimings
        )
    }

    fun parseSrtOrVtt(raw: String, format: TranscriptFormat): ParsedTranscript {
        val lines = raw.replace("\r\n", "\n").split("\n")
        val segments = ArrayList<Segment>()
        val timeRegex = Regex("""(\d{1,2}:)?(\d{2}):(\d{2})[.,](\d{3})\s*-->\s*(\d{1,2}:)?(\d{2}):(\d{2})[.,](\d{3})""")

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val match = timeRegex.find(line)
            if (match != null) {
                val start = parseTimestamp(match, 1)
                val end = parseTimestamp(match, 5).coerceAtLeast(start + 0.01)
                i++
                val textBuf = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank()) {
                    val clean = lines[i].replace(Regex("<[^>]+>"), "").trim()
                    if (clean.isNotEmpty()) {
                        if (textBuf.isNotEmpty()) textBuf.append(' ')
                        textBuf.append(clean)
                    }
                    i++
                }
                val body = textBuf.toString().trim()
                if (body.isNotEmpty()) {
                    segments.add(Segment(start, end, body))
                }
            } else {
                i++
            }
        }
        return ParsedTranscript(
            format = format,
            segments = segments,
            words = emptyList(),
            hasWordLevelTimings = false
        )
    }

    private fun parseTimestamp(match: MatchResult, startGroup: Int): Double {
        val hStr = match.groupValues[startGroup].trimEnd(':')
        val h = if (hStr.isEmpty()) 0 else hStr.toIntOrNull() ?: 0
        val m = match.groupValues[startGroup + 1].toIntOrNull() ?: 0
        val s = match.groupValues[startGroup + 2].toIntOrNull() ?: 0
        val ms = match.groupValues[startGroup + 3].toIntOrNull() ?: 0
        return h * 3600.0 + m * 60.0 + s + ms / 1000.0
    }
}
