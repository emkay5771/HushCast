package app.podiumpodcasts.podium.api.rss

import com.prof18.rssparser.RssParser
import com.prof18.rssparser.model.RssChannel
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.util.concurrent.CancellationException

interface FetchPodcastClientResult {
    data class Success(
        val rssChannel: RssChannel,
        val fileSize: Long,
        val lastModified: String,
        val eTag: String,
        val contentLength: String
    ) : FetchPodcastClientResult

    class Unchanged(
        val reason: String
    ) : FetchPodcastClientResult

    data class Failure(val e: Exception) : FetchPodcastClientResult
}

class FetchPodcastClient(
    val client: HttpClient = sharedClient
) {
    suspend fun fetch(
        origin: String,
        lastModified: String,
        eTag: String,
        contentLength: String = ""
    ): FetchPodcastClientResult {
        return try {
            get(
                origin = origin,
                lastModified = lastModified.takeIf { it.isNotBlank() },
                eTag = eTag.takeIf { it.isNotBlank() }
            )
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            FetchPodcastClientResult.Failure(e)
        }
    }

    private suspend fun get(
        origin: String,
        lastModified: String? = null,
        eTag: String? = null
    ): FetchPodcastClientResult {
        val response = client.get(origin) {
            if (!eTag.isNullOrBlank()) header(HttpHeaders.IfNoneMatch, eTag)
            if (!lastModified.isNullOrBlank()) header(HttpHeaders.IfModifiedSince, lastModified)
        }

        if (response.status == HttpStatusCode.NotModified) {
            return FetchPodcastClientResult.Unchanged("ETAG or LAST-MODIFIED get")
        }

        if (response.status != HttpStatusCode.OK) {
            throw Exception("UNHANDLED STATUS CODE ${response.status}")
        }

        val bytes = response.bodyAsBytes()
        val string = bytes.decodeToString()

        return FetchPodcastClientResult.Success(
            rssChannel = RssParser().parse(string),
            fileSize = bytes.size.toLong(),
            eTag = response.headers[HttpHeaders.ETag] ?: "",
            lastModified = response.headers[HttpHeaders.LastModified] ?: "",
            contentLength = response.headers[HttpHeaders.ContentLength] ?: ""
        )
    }

    suspend fun fetchNoCache(
        origin: String
    ): FetchPodcastClientResult {
        return get(origin = origin)
    }

    companion object {
        val sharedClient: HttpClient by lazy { HttpClient { } }
    }
}