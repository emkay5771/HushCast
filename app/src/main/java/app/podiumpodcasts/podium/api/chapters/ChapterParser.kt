package app.podiumpodcasts.podium.api.chapters

import app.hushcast.automute.transcript.OffsetMap
import app.podiumpodcasts.podium.api.db.model.EpisodeChapterModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class PodcastChapter(
    val startTimeMs: Long,
    val title: String,
    val url: String? = null,
    val img: String? = null,
    val endTimeMs: Long? = null,
    val toc: Boolean = true
)

data class ResolvedChapter(
    val index: Int,
    val publishedStartMs: Long,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val imageUrl: String? = null,
    val linkUrl: String? = null,
    val source: String
)

/**
 * Chapter parser supporting Podcasting 2.0 JSON chapters and Podlove Simple Chapters (PSC XML),
 * plus C8 normalization and DAI OffsetMap resolution.
 */
object ChapterParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parsePodcasting20Json(raw: String): List<PodcastChapter> {
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val arr = root["chapters"]?.jsonArray ?: return emptyList()
            arr.mapNotNull { elem ->
                val obj = elem.jsonObject
                val toc = obj["toc"]?.jsonPrimitive?.booleanOrNull ?: true
                if (!toc) return@mapNotNull null
                val startSec = obj["startTime"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val endSec = obj["endTime"]?.jsonPrimitive?.doubleOrNull
                val title = obj["title"]?.jsonPrimitive?.content.orEmpty().trim()
                PodcastChapter(
                    startTimeMs = (startSec * 1000.0).toLong().coerceAtLeast(0L),
                    title = title,
                    url = obj["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                    img = obj["img"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                    endTimeMs = endSec?.let { (it * 1000.0).toLong() },
                    toc = true
                )
            }.sortedBy { it.startTimeMs }
        }.getOrDefault(emptyList())
    }

    fun parsePodloveSimpleChaptersXml(xml: String): List<PodcastChapter> {
        val regex = Regex("""<psc:chapter\s+([^>]+)/?>""")
        val startAttr = Regex("""start=["']([^"']+)["']""")
        val titleAttr = Regex("""title=["']([^"']+)["']""")
        val hrefAttr = Regex("""href=["']([^"']+)["']""")
        val imageAttr = Regex("""image=["']([^"']+)["']""")

        return regex.findAll(xml).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val startStr = startAttr.find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = titleAttr.find(attrs)?.groupValues?.get(1).orEmpty().trim()
            val href = hrefAttr.find(attrs)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            val image = imageAttr.find(attrs)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            PodcastChapter(
                startTimeMs = parseNptTimeMs(startStr).coerceAtLeast(0L),
                title = title,
                url = href,
                img = image
            )
        }.sortedBy { it.startTimeMs }.toList()
    }

    /**
     * Converts raw parsed chapters into [EpisodeChapterModel] rows following C8 edge-case rules:
     * - Forces first publisher chapter to start at 0 ms.
     * - Drops zero/negative-length chapters.
     * - Uses explicit endTime only if <= next chapter start.
     * - Replaces blank titles with "Chapter N".
     */
    fun toEntities(
        episodeId: String,
        source: String,
        raw: List<PodcastChapter>,
        durationMs: Long = 0L,
        forceFirstAtZero: Boolean = true
    ): List<EpisodeChapterModel> {
        if (raw.isEmpty()) return emptyList()
        val sorted = raw.sortedBy { it.startTimeMs }.toMutableList()
        if (forceFirstAtZero && sorted.isNotEmpty()) {
            sorted[0] = sorted[0].copy(startTimeMs = 0L)
        }
        val valid = ArrayList<EpisodeChapterModel>(sorted.size)
        for (i in sorted.indices) {
            val cur = sorted[i]
            val nextStart = sorted.getOrNull(i + 1)?.startTimeMs
            val candidateEnd = when {
                cur.endTimeMs != null && nextStart != null ->
                    if (cur.endTimeMs <= nextStart) cur.endTimeMs else nextStart
                cur.endTimeMs != null && durationMs > 0L ->
                    cur.endTimeMs.coerceAtMost(durationMs)
                cur.endTimeMs != null -> cur.endTimeMs
                nextStart != null -> nextStart
                durationMs > 0L -> durationMs
                else -> null
            }
            if (candidateEnd != null && candidateEnd <= cur.startTimeMs) {
                continue
            }
            val displayTitle = cur.title.ifBlank { "Chapter ${valid.size + 1}" }
            valid.add(
                EpisodeChapterModel(
                    episodeId = episodeId,
                    source = source,
                    startMs = cur.startTimeMs.coerceAtLeast(0L),
                    endMs = candidateEnd,
                    title = displayTitle,
                    imageUrl = cur.img,
                    linkUrl = cur.url
                )
            )
        }
        return valid
    }

    /**
     * Resolves stored [EpisodeChapterModel]s onto the playback timeline:
     * - Feed chapters ("json", "podlove") are shifted using [offsetMap] if present (DAI Bridge).
     * - Embedded ID3 and generated chapters are already on the download timeline and are not shifted.
     * - Preserves [ResolvedChapter.publishedStartMs] for ChapterSkipModel keying.
     * - Clamps end times per C8 (endMs <= nextStart, last chapter ends at durationMs).
     */
    fun resolveForPlayback(
        chapters: List<EpisodeChapterModel>,
        offsetMap: OffsetMap?,
        durationMs: Long
    ): List<ResolvedChapter> {
        if (chapters.isEmpty()) return emptyList()
        val sorted = chapters.sortedBy { it.startMs }
        val shifted = sorted.mapIndexed { idx, ch ->
            val useMap = ch.needsDaiOffsetMap && offsetMap != null && !offsetMap.isEmpty()
            val mappedStart = if (useMap) offsetMap!!.shiftTimeMs(ch.startMs) else ch.startMs
            val mappedEnd = ch.endMs?.let { if (useMap) offsetMap!!.shiftTimeMs(it) else it }
            Triple(ch, mappedStart.coerceAtLeast(0L), mappedEnd)
        }.sortedBy { it.second }

        val resolved = ArrayList<ResolvedChapter>(shifted.size)
        for (i in shifted.indices) {
            val (ch, start, rawEnd) = shifted[i]
            val nextStart = shifted.getOrNull(i + 1)?.second
            val fallbackEnd = nextStart ?: durationMs.takeIf { it > start } ?: (start + 60_000L)
            val end = if (rawEnd != null && rawEnd > start && (nextStart == null || rawEnd <= nextStart)) {
                rawEnd
            } else {
                fallbackEnd
            }
            if (end <= start) continue
            resolved.add(
                ResolvedChapter(
                    index = resolved.size,
                    publishedStartMs = ch.startMs,
                    startMs = start,
                    endMs = end,
                    title = ch.title.ifBlank { "Chapter ${resolved.size + 1}" },
                    imageUrl = ch.imageUrl,
                    linkUrl = ch.linkUrl,
                    source = ch.source
                )
            )
        }
        return resolved
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
