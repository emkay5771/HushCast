package app.podiumpodcasts.podium.api.podcastindex

import app.podiumpodcasts.podium.BuildConfig
import app.podiumpodcasts.podium.api.model.PodcastPreviewModel
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

@Serializable
data class PodcastIndexSearchResponse(
    val status: String = "",
    val feeds: List<PodcastIndexFeed> = emptyList()
)

@Serializable
data class PodcastIndexFeed(
    val id: Long = 0L,
    val title: String = "",
    val url: String = "",
    val originalUrl: String = "",
    val link: String = "",
    val description: String = "",
    val author: String = "",
    val image: String = "",
    val artwork: String = "",
        val language: String = "",
        val categories: Map<String, String>? = null
)

/**
 * Podcast Index API v1.0 discovery client (ported capability from AntennaPod's Podcast Index integration).
 * Credentials are loaded from local.properties via BuildConfig.
 */
class PodcastIndexClient {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    fun isConfigured(): Boolean =
        BuildConfig.PODCAST_INDEX_API_KEY.isNotBlank() &&
            BuildConfig.PODCAST_INDEX_API_SECRET.isNotBlank()

    suspend fun search(query: String, max: Int = 25): List<PodcastPreviewModel> {
        if (!isConfigured()) return emptyList()

        val apiKey = BuildConfig.PODCAST_INDEX_API_KEY
        val apiSecret = BuildConfig.PODCAST_INDEX_API_SECRET
        val authTime = (System.currentTimeMillis() / 1000L).toString()
        val authHash = sha1Hex("$apiKey$apiSecret$authTime")

        val response: PodcastIndexSearchResponse = client.get("https://api.podcastindex.org/api/1.0/search/byterm") {
            parameter("q", query)
            parameter("max", max)
            header("X-Auth-Key", apiKey)
            header("X-Auth-Date", authTime)
            header("Authorization", authHash)
            header("User-Agent", "HushCast/1.0")
        }.body()

        return response.feeds.mapNotNull { feed ->
            val rss = feed.url.ifBlank { feed.originalUrl }
            if (rss.isBlank()) return@mapNotNull null
            PodcastPreviewModel(
                fetchUrl = rss,
                link = feed.link.ifBlank { rss },
                title = feed.title.ifBlank { "Untitled Podcast" },
                description = feed.description,
                author = feed.author.ifBlank { "Unknown" },
                imageUrl = feed.artwork.ifBlank { feed.image },
                languageCode = feed.language
            )
        }
    }

    private fun sha1Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
