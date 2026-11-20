package app.podiumpodcasts.podium.api.rss

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PodcastFunding(
    val url: String,
    val label: String = ""
)

@Serializable
data class PodcastPerson(
    val name: String,
    val role: String = "",
    val group: String = "",
    val img: String = "",
    val href: String = ""
)

@Serializable
data class PodcastSoundbite(
    val startTimeSec: Double,
    val durationSec: Double,
    val title: String = ""
)

@Serializable
data class PodcastTrailer(
    val url: String,
    val title: String = "",
    val pubDate: String = "",
    val length: Long = 0L,
    val type: String = "",
    val season: Int? = null
)

object PodcastMetadataJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeStrings(list: List<String>): String =
        if (list.isEmpty()) "" else json.encodeToString(list)

    fun decodeStrings(raw: String): List<String> =
        if (raw.isBlank()) emptyList() else runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

    fun encodeFunding(list: List<PodcastFunding>): String =
        if (list.isEmpty()) "" else json.encodeToString(list)

    fun decodeFunding(raw: String): List<PodcastFunding> =
        if (raw.isBlank()) emptyList() else runCatching { json.decodeFromString<List<PodcastFunding>>(raw) }.getOrDefault(emptyList())

    fun encodePersons(list: List<PodcastPerson>): String =
        if (list.isEmpty()) "" else json.encodeToString(list)

    fun decodePersons(raw: String): List<PodcastPerson> =
        if (raw.isBlank()) emptyList() else runCatching { json.decodeFromString<List<PodcastPerson>>(raw) }.getOrDefault(emptyList())

    fun encodeSoundbites(list: List<PodcastSoundbite>): String =
        if (list.isEmpty()) "" else json.encodeToString(list)

    fun decodeSoundbites(raw: String): List<PodcastSoundbite> =
        if (raw.isBlank()) emptyList() else runCatching { json.decodeFromString<List<PodcastSoundbite>>(raw) }.getOrDefault(emptyList())
}
