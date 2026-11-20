package app.podiumpodcasts.podium.api.rss

import com.prof18.rssparser.RssParser
import com.prof18.rssparser.model.RssChannel
import com.prof18.rssparser.model.RssItem
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

data class RssEpisodeExtras(
    val transcriptUrl: String = "",
    val transcriptType: String = "",
    val chaptersUrl: String = "",
    val chaptersType: String = "",
    val inlineChapters: List<app.podiumpodcasts.podium.api.chapters.PodcastChapter> = emptyList(),
    /** Every other `<podcast:transcript>` of the item, best first (T5 fall-through). */
    val transcriptAlternates: List<TranscriptRef> = emptyList(),
    val persons: List<PodcastPerson> = emptyList(),
    val soundbites: List<PodcastSoundbite> = emptyList(),
    val explicit: Boolean = false
) {
    /** [transcriptAlternates] as stored in `podcastEpisode.transcriptAlternatesJson`. */
    val transcriptAlternatesJson: String
        get() = TranscriptRanking.encodeAlternates(transcriptAlternates)
}

data class RssPodcastExtras(
    val language: String = "",
    val funding: List<PodcastFunding> = emptyList(),
    val persons: List<PodcastPerson> = emptyList(),
    val trailer: PodcastTrailer? = null,
    val explicit: Boolean = false,
    val byItemKey: Map<String, RssEpisodeExtras> = emptyMap(),
    /** Exact per-item extras from the single parse (identity keys: the parsed RssItem objects). */
    val byItem: Map<RssItem, RssEpisodeExtras> = emptyMap(),
    /** Every item of that parse (identity set), so an item without extras isn't matched by key. */
    val parsedItems: Set<RssItem> = emptySet()
) {
    fun lookup(item: RssItem): RssEpisodeExtras? {
        byItem[item]?.let { return it }
        // An item from the same parse that has no extras: don't borrow another item's extras
        // through a shared key (many feeds give every item the show's homepage as <link>).
        if (item in parsedItems) return null
        val keys = listOfNotNull(
            item.guid?.takeIf { it.isNotBlank() },
            item.audio?.takeIf { it.isNotBlank() },
            item.video?.takeIf { it.isNotBlank() },
            item.link?.takeIf { it.isNotBlank() },
            "${item.title.orEmpty()}|${item.pubDate.orEmpty()}"
        )
        for (k in keys) {
            byItemKey[k]?.let { return it }
        }
        return null
    }
}

interface FetchPodcastClientResult {
    data class Success(
        val rssChannel: RssChannel,
        val fileSize: Long,
        val lastModified: String,
        val eTag: String,
        val contentLength: String,
        val extras: RssPodcastExtras = RssPodcastExtras()
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
        val (channel, extras) = parseFeed(string)

        return FetchPodcastClientResult.Success(
            rssChannel = channel,
            fileSize = bytes.size.toLong(),
            eTag = response.headers[HttpHeaders.ETag] ?: "",
            lastModified = response.headers[HttpHeaders.LastModified] ?: "",
            contentLength = response.headers[HttpHeaders.ContentLength] ?: "",
            extras = extras
        )
    }

    suspend fun fetchNoCache(
        origin: String
    ): FetchPodcastClientResult {
        return get(origin = origin)
    }

    companion object {
        val sharedClient: HttpClient by lazy { HttpClient { } }

        /**
         * The Podcasting 2.0 extras of a feed.  Kept for callers that only have the XML; feed
         * fetches get them from the same single parse as the channel ([RssFeedParser]).
         */
        fun parsePodcastExtras(xml: String): RssPodcastExtras =
            RssFeedParser.parse(xml)?.extras ?: RssPodcastExtras()

        /** One parse for RSS feeds; RssParser (no extras) for Atom/RDF or XML our parser rejects. */
        suspend fun parseFeed(xml: String): Pair<RssChannel, RssPodcastExtras> {
            val parsed = withContext(Dispatchers.Default) { RssFeedParser.parse(xml) }
            if (parsed != null) return parsed.channel to parsed.extras
            return RssParser().parse(xml) to RssPodcastExtras()
        }
    }
}
