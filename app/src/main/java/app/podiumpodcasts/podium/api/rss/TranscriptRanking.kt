package app.podiumpodcasts.podium.api.rss

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One `<podcast:transcript>` tag: where it is and what format the feed says it is. */
@Serializable
data class TranscriptRef(
    val url: String,
    val type: String = ""
)

/**
 * Picks the transcript format to use when a feed lists several (T5).
 *
 * Order: JSON > VTT > SRT > HTML > anything else.  When the feed's `type` is missing or generic
 * (`text/plain`, `application/octet-stream`), the URL's extension decides.
 */
object TranscriptRanking {
    const val RANK_JSON = 4
    const val RANK_VTT = 3
    const val RANK_SRT = 2
    const val RANK_HTML = 1
    const val RANK_UNKNOWN = 0

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(TranscriptRef.serializer())

    fun rank(type: String, url: String = ""): Int {
        rankOfMime(type)?.let { return it }
        return rankOfExtension(url) ?: RANK_UNKNOWN
    }

    private fun rankOfMime(type: String): Int? {
        val lower = type.trim().lowercase()
        if (lower.isEmpty()) return null
        return when {
            "json" in lower -> RANK_JSON
            "vtt" in lower -> RANK_VTT
            "srt" in lower || "subrip" in lower -> RANK_SRT
            "html" in lower -> RANK_HTML
            else -> null
        }
    }

    private fun rankOfExtension(url: String): Int? {
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return when {
            path.endsWith(".json") -> RANK_JSON
            path.endsWith(".vtt") -> RANK_VTT
            path.endsWith(".srt") -> RANK_SRT
            path.endsWith(".html") || path.endsWith(".htm") -> RANK_HTML
            else -> null
        }
    }

    /**
     * Best first.  Blank URLs and repeated URLs are dropped; ties keep feed order.
     */
    fun order(refs: List<TranscriptRef>): List<TranscriptRef> {
        val seen = HashSet<String>()
        val clean = refs.mapNotNull { ref ->
            val url = ref.url.trim()
            if (url.isEmpty() || !seen.add(url)) null else TranscriptRef(url, ref.type.trim())
        }
        // sortedByDescending is stable, so equal ranks stay in feed order.
        return clean.sortedByDescending { rank(it.type, it.url) }
    }

    /** A MIME hint for a transcript whose feed type is blank, from its extension. */
    fun effectiveType(ref: TranscriptRef): String {
        if (ref.type.isNotBlank()) return ref.type
        return when (rankOfExtension(ref.url)) {
            RANK_JSON -> "application/json"
            RANK_VTT -> "text/vtt"
            RANK_SRT -> "application/x-subrip"
            RANK_HTML -> "text/html"
            else -> ""
        }
    }

    fun encodeAlternates(refs: List<TranscriptRef>): String =
        if (refs.isEmpty()) "" else json.encodeToString(listSerializer, refs)

    fun decodeAlternates(raw: String?): List<TranscriptRef> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }
            .getOrDefault(emptyList())
            .filter { it.url.isNotBlank() }
    }

    /** The primary transcript first, then the alternates, without repeats. */
    fun candidates(
        primaryUrl: String,
        primaryType: String,
        alternatesJson: String?
    ): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        if (primaryUrl.isNotBlank() && seen.add(primaryUrl.trim())) {
            out += primaryUrl.trim() to primaryType
        }
        for (alt in decodeAlternates(alternatesJson)) {
            if (seen.add(alt.url.trim())) out += alt.url.trim() to alt.type
        }
        return out
    }
}
