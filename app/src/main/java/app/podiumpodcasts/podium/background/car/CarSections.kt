package app.podiumpodcasts.podium.background.car

import java.util.Locale

/** List shaping for the car, kept free of Android so it can be tested. */
object CarSections {

    data class Row<T>(val item: T, val group: String?)

    const val MAX_CONTINUE = 5
    const val MAX_NEW = 50

    /**
     * The New tab: up to [MAX_CONTINUE] in-progress episodes under "Continue listening", then new
     * episodes under "New episodes", without repeating one already above. A section with nothing in
     * it is left out.
     */
    fun <T> newTab(
        continuing: List<T>,
        newEpisodes: List<T>,
        id: (T) -> String,
        continueTitle: String,
        newTitle: String
    ): List<Row<T>> {
        val top = continuing.take(MAX_CONTINUE)
        val seen = top.mapTo(HashSet()) { id(it) }
        val rest = newEpisodes.filter { id(it) !in seen }.take(MAX_NEW)
        return top.map { Row(it, continueTitle) } + rest.map { Row(it, newTitle) }
    }

    /**
     * A serial show, already in season order: "Season 1", "Season 2", then "Other episodes" for
     * those without a season. A show with no seasons at all gets no headings.
     */
    fun <T> serial(
        episodes: List<T>,
        season: (T) -> Int?,
        seasonTitle: (Int) -> String,
        otherTitle: String
    ): List<Row<T>> {
        if (episodes.none { season(it) != null }) return episodes.map { Row(it, null) }
        return episodes.map { e ->
            val s = season(e)
            Row(e, if (s != null) seasonTitle(s) else otherTitle)
        }
    }

    /**
     * The root tabs within the host's limit. Downloads is dropped first, then New; Up Next and
     * Podcasts stay. A limit of 0 or less means the host gave none.
     */
    fun <T> tabsWithin(tabs: List<T>, limit: Int, id: (T) -> String): List<T> {
        if (limit <= 0 || limit >= tabs.size) return tabs
        val dropOrder = listOf(CarIds.TAB_DOWNLOADS, CarIds.TAB_NEW)
        var kept = tabs
        for (drop in dropOrder) {
            if (kept.size <= limit) break
            kept = kept.filter { id(it) != drop }
        }
        return kept.take(limit)
    }

    /** Page [page] of [list]; the car asks for page 0 with a huge size when it doesn't page. */
    fun <T> page(list: List<T>, page: Int, pageSize: Int): List<T> {
        if (page < 0 || pageSize <= 0) return emptyList()
        val from = page.toLong() * pageSize
        if (from >= list.size) return emptyList()
        val to = minOf(list.size.toLong(), from + pageSize).toInt()
        return list.subList(from.toInt(), to)
    }
}

/** Voice requests ("Hey Google, play Serial on HushCast"). */
object CarVoice {

    data class Candidate(
        val id: String,
        val pubDate: Long,
        val positionSec: Int,
        val played: Boolean,
        val lastUpdate: Long
    )

    /** Lower case, no punctuation or "the", no trailing "podcast"/"show". */
    fun normalize(text: String): String {
        var t = text.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (t.startsWith("the ")) t = t.removePrefix("the ")
        for (suffix in listOf(" podcast", " show")) if (t.endsWith(suffix)) t = t.removeSuffix(suffix)
        return t.trim()
    }

    /**
     * The podcast the query names: same name, else a name starting with it, else a name containing
     * it, else a name the query contains ("the latest serial episode"). Returns the key of [podcasts].
     */
    fun <K> matchPodcast(query: String, podcasts: List<Pair<K, String>>): K? {
        val q = normalize(query)
        if (q.isEmpty()) return null
        val named = podcasts.map { it.first to normalize(it.second) }.filter { it.second.isNotEmpty() }
        return named.firstOrNull { it.second == q }?.first
            ?: named.firstOrNull { it.second.startsWith(q) }?.first
            ?: named.firstOrNull { q.length >= 3 && it.second.contains(q) }?.first
            ?: named.filter { it.second.length >= 3 && q.contains(it.second) }
                .maxByOrNull { it.second.length }?.first
    }

    /**
     * Which episode of a podcast to play: the one in progress (most recently played), else the
     * next unplayed one (a serial show's earliest, otherwise the newest), else the newest.
     * [serialOrder] lists a serial show's episodes from its start; empty for other shows.
     */
    fun pickEpisode(episodes: List<Candidate>, serialOrder: List<String> = emptyList()): String? {
        if (episodes.isEmpty()) return null
        episodes.filter { it.positionSec > 0 && !it.played }
            .maxByOrNull { it.lastUpdate }
            ?.let { return it.id }
        val unplayed = episodes.filter { !it.played }
        if (unplayed.isNotEmpty()) {
            if (serialOrder.isNotEmpty()) {
                val ids = unplayed.mapTo(HashSet()) { it.id }
                serialOrder.firstOrNull { it in ids }?.let { return it }
            }
            return unplayed.maxBy { it.pubDate }.id
        }
        return episodes.maxBy { it.pubDate }.id
    }
}
