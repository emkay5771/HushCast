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
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException
import javax.xml.parsers.DocumentBuilderFactory

data class RssEpisodeExtras(
    val transcriptUrl: String = "",
    val transcriptType: String = "",
    val chaptersUrl: String = "",
    val chaptersType: String = ""
)

data class RssPodcastExtras(
    val language: String = "",
    val byItemKey: Map<String, RssEpisodeExtras> = emptyMap()
) {
    fun lookup(item: RssItem): RssEpisodeExtras? {
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

        return FetchPodcastClientResult.Success(
            rssChannel = RssParser().parse(string),
            fileSize = bytes.size.toLong(),
            eTag = response.headers[HttpHeaders.ETag] ?: "",
            lastModified = response.headers[HttpHeaders.LastModified] ?: "",
            contentLength = response.headers[HttpHeaders.ContentLength] ?: "",
            extras = parsePodcastExtras(string)
        )
    }

    suspend fun fetchNoCache(
        origin: String
    ): FetchPodcastClientResult {
        return get(origin = origin)
    }

    companion object {
        val sharedClient: HttpClient by lazy { HttpClient { } }

        private fun transcriptTypeRank(mime: String): Int {
            val lower = mime.lowercase()
            return when {
                "json" in lower -> 4
                "vtt" in lower -> 3
                "srt" in lower || "subrip" in lower -> 2
                "html" in lower -> 0
                else -> 1
            }
        }

        fun parsePodcastExtras(xml: String): RssPodcastExtras {
            return runCatching {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                }
                val doc = factory.newDocumentBuilder()
                    .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
                val channels = doc.getElementsByTagName("channel")
                var channelLang = ""
                if (channels.length > 0) {
                    val ch = channels.item(0)
                    val children = ch.childNodes
                    for (i in 0 until children.length) {
                        val node = children.item(i)
                        if (node.nodeType == Node.ELEMENT_NODE &&
                            (node.localName == "language" || node.nodeName == "language")
                        ) {
                            channelLang = node.textContent.orEmpty().trim()
                            break
                        }
                    }
                }

                val map = HashMap<String, RssEpisodeExtras>()
                val items = doc.getElementsByTagName("item")
                for (i in 0 until items.length) {
                    val item = items.item(i) as? Element ?: continue
                    var guid = ""
                    var enclosureUrl = ""
                    var link = ""
                    var title = ""
                    var pubDate = ""
                    var bestTranscriptUrl = ""
                    var bestTranscriptType = ""
                    var bestTranscriptRank = 0
                    var chaptersUrl = ""
                    var chaptersType = ""

                    val children = item.childNodes
                    for (j in 0 until children.length) {
                        val node = children.item(j) as? Element ?: continue
                        val name = node.localName ?: node.nodeName.substringAfter(':')
                        when (name) {
                            "guid" -> if (guid.isBlank()) guid = node.textContent.orEmpty().trim()
                            "link" -> if (link.isBlank()) link = node.textContent.orEmpty().trim()
                            "title" -> if (title.isBlank()) title = node.textContent.orEmpty().trim()
                            "pubDate" -> if (pubDate.isBlank()) pubDate = node.textContent.orEmpty().trim()
                            "enclosure" -> if (enclosureUrl.isBlank()) {
                                enclosureUrl = node.getAttribute("url").trim()
                            }
                            "transcript" -> {
                                val url = node.getAttribute("url").trim()
                                val type = node.getAttribute("type").trim()
                                val rank = transcriptTypeRank(type)
                                if (url.isNotBlank() && rank > bestTranscriptRank) {
                                    bestTranscriptRank = rank
                                    bestTranscriptUrl = url
                                    bestTranscriptType = type
                                }
                            }
                            "chapters" -> {
                                val url = node.getAttribute("url").trim()
                                val type = node.getAttribute("type").trim()
                                if (url.isNotBlank() && chaptersUrl.isBlank()) {
                                    chaptersUrl = url
                                    chaptersType = type.ifBlank { "application/json+chapters" }
                                }
                            }
                        }
                    }

                    if (bestTranscriptUrl.isNotBlank() || chaptersUrl.isNotBlank()) {
                        val extra = RssEpisodeExtras(
                            transcriptUrl = bestTranscriptUrl,
                            transcriptType = bestTranscriptType,
                            chaptersUrl = chaptersUrl,
                            chaptersType = chaptersType
                        )
                        if (guid.isNotBlank()) map[guid] = extra
                        if (enclosureUrl.isNotBlank()) map[enclosureUrl] = extra
                        if (link.isNotBlank()) map[link] = extra
                        if (title.isNotBlank() || pubDate.isNotBlank()) {
                            map["$title|$pubDate"] = extra
                        }
                    }
                }
                RssPodcastExtras(language = channelLang, byItemKey = map)
            }.getOrDefault(RssPodcastExtras())
        }
    }
}