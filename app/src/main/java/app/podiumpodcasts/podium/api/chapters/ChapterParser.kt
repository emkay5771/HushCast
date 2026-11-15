package app.podiumpodcasts.podium.api.chapters

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class PodcastChapter(
    val startTimeMs: Long,
    val title: String,
    val url: String? = null,
    val img: String? = null
)

/**
 * Chapter parser supporting Podcasting 2.0 JSON chapters and Podlove Simple Chapters (PSC XML).
 */
object ChapterParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parsePodcasting20Json(raw: String): List<PodcastChapter> {
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val arr = root["chapters"]?.jsonArray ?: return emptyList()
            arr.mapNotNull { elem ->
                val obj = elem.jsonObject
                val startSec = obj["startTime"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                PodcastChapter(
                    startTimeMs = (startSec * 1000.0).toLong(),
                    title = title,
                    url = obj["url"]?.jsonPrimitive?.content,
                    img = obj["img"]?.jsonPrimitive?.content
                )
            }.sortedBy { it.startTimeMs }
        }.getOrDefault(emptyList())
    }

    fun parsePodloveSimpleChaptersXml(xml: String): List<PodcastChapter> {
        val regex = Regex("""<psc:chapter\s+([^>]+)/?>""")
        val startAttr = Regex("""start=["']([^"']+)["']""")
        val titleAttr = Regex("""title=["']([^"']+)["']""")
        val hrefAttr = Regex("""href=["']([^"']+)["']""")

        return regex.findAll(xml).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val startStr = startAttr.find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = titleAttr.find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
            val href = hrefAttr.find(attrs)?.groupValues?.get(1)
            PodcastChapter(
                startTimeMs = parseNptTimeMs(startStr),
                title = title,
                url = href
            )
        }.sortedBy { it.startTimeMs }.toList()
    }

    private fun parseNptTimeMs(npt: String): Long {
        val parts = npt.trim().split(":")
        return when (parts.size) {
            3 -> {
                val h = parts[0].toLongOrNull() ?: 0L
                val m = parts[1].toLongOrNull() ?: 0L
                val s = ((parts[2].toDoubleOrNull() ?: 0.0) * 1000.0).toLong()
                (h * 3600L + m * 60L) * 1000L + s
            }
            2 -> {
                val m = parts[0].toLongOrNull() ?: 0L
                val s = ((parts[1].toDoubleOrNull() ?: 0.0) * 1000.0).toLong()
                m * 60_000L + s
            }
            else -> ((npt.toDoubleOrNull() ?: 0.0) * 1000.0).toLong()
        }
    }
}
