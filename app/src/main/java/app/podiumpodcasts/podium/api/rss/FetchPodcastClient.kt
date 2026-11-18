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
    val chaptersType: String = "",
    val inlineChapters: List<app.podiumpodcasts.podium.api.chapters.PodcastChapter> = emptyList()
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
                val spf = javax.xml.parsers.SAXParserFactory.newInstance().apply {
                    isNamespaceAware = true
                    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                }
                val saxParser = spf.newSAXParser()

                var channelLang = ""
                val map = HashMap<String, RssEpisodeExtras>()

                var depth = 0
                var inChannel = false
                var inItem = false
                var inChapters = false
                var itemDepth = 0

                var guid = ""
                var enclosureUrl = ""
                var link = ""
                var title = ""
                var pubDate = ""
                var bestTranscriptUrl = ""
                var bestTranscriptType = ""
                var bestTranscriptRank = -1
                var chaptersUrl = ""
                var chaptersType = ""
                val inlineChapters = ArrayList<app.podiumpodcasts.podium.api.chapters.PodcastChapter>()

                var capturingTag: String? = null
                val textBuf = StringBuilder()

                val handler = object : org.xml.sax.helpers.DefaultHandler() {
                    override fun startElement(
                        uri: String?,
                        localName: String?,
                        qName: String?,
                        attributes: org.xml.sax.Attributes?
                    ) {
                        depth++
                        val local = (localName?.takeIf { it.isNotEmpty() } ?: qName?.substringAfter(':')).orEmpty()
                        capturingTag = null
                        textBuf.setLength(0)

                        when {
                            local == "channel" -> inChannel = true
                            local == "item" && inChannel -> {
                                inItem = true
                                itemDepth = depth
                                guid = ""
                                enclosureUrl = ""
                                link = ""
                                title = ""
                                pubDate = ""
                                bestTranscriptUrl = ""
                                bestTranscriptType = ""
                                bestTranscriptRank = -1
                                chaptersUrl = ""
                                chaptersType = ""
                                inlineChapters.clear()
                            }
                            inChannel && !inItem && local == "language" && channelLang.isBlank() -> {
                                capturingTag = "channel:language"
                            }
                            inItem && depth == itemDepth + 1 -> {
                                when (local) {
                                    "guid", "link", "title", "pubDate" -> capturingTag = local
                                    "enclosure" -> if (enclosureUrl.isBlank()) {
                                        enclosureUrl = attributes?.getValue("url")?.trim().orEmpty()
                                    }
                                    "transcript" -> {
                                        val url = attributes?.getValue("url")?.trim().orEmpty()
                                        val type = attributes?.getValue("type")?.trim().orEmpty()
                                        val rank = transcriptTypeRank(type)
                                        if (url.isNotBlank() && rank > bestTranscriptRank) {
                                            bestTranscriptRank = rank
                                            bestTranscriptUrl = url
                                            bestTranscriptType = type
                                        }
                                    }
                                    "chapters" -> {
                                        val url = attributes?.getValue("url")?.trim().orEmpty()
                                        val type = attributes?.getValue("type")?.trim().orEmpty()
                                        if (url.isNotBlank() && chaptersUrl.isBlank()) {
                                            chaptersUrl = url
                                            chaptersType = type.ifBlank { "application/json+chapters" }
                                        } else {
                                            inChapters = true
                                        }
                                    }
                                }
                            }
                            inItem && inChapters && local == "chapter" -> {
                                val startStr = attributes?.getValue("start")?.trim().orEmpty()
                                val startMs = app.podiumpodcasts.podium.api.chapters.ChapterParser
                                    .parseNptTimeMs(startStr)
                                val chTitle = attributes?.getValue("title")?.trim().orEmpty()
                                val chHref = attributes?.getValue("href")?.trim()?.takeIf { it.isNotBlank() }
                                if (startMs >= 0L) {
                                    inlineChapters.add(
                                        app.podiumpodcasts.podium.api.chapters.PodcastChapter(
                                            startTimeMs = startMs,
                                            title = chTitle,
                                            url = chHref
                                        )
                                    )
                                }
                            }
                        }
                    }

                    override fun characters(ch: CharArray?, start: Int, length: Int) {
                        if (capturingTag != null && ch != null && length > 0) {
                            textBuf.append(ch, start, length)
                        }
                    }

                    override fun endElement(uri: String?, localName: String?, qName: String?) {
                        val local = (localName?.takeIf { it.isNotEmpty() } ?: qName?.substringAfter(':')).orEmpty()
                        when (capturingTag) {
                            "channel:language" -> if (channelLang.isBlank()) channelLang = textBuf.toString().trim()
                            "guid" -> if (guid.isBlank()) guid = textBuf.toString().trim()
                            "link" -> if (link.isBlank()) link = textBuf.toString().trim()
                            "title" -> if (title.isBlank()) title = textBuf.toString().trim()
                            "pubDate" -> if (pubDate.isBlank()) pubDate = textBuf.toString().trim()
                        }
                        capturingTag = null
                        textBuf.setLength(0)

                        if (local == "chapters") {
                            inChapters = false
                        } else if (local == "item" && inItem && depth == itemDepth) {
                            inItem = false
                            inChapters = false
                            if (bestTranscriptUrl.isNotBlank() || chaptersUrl.isNotBlank() || inlineChapters.isNotEmpty()) {
                                val extra = RssEpisodeExtras(
                                    transcriptUrl = bestTranscriptUrl,
                                    transcriptType = bestTranscriptType,
                                    chaptersUrl = chaptersUrl,
                                    chaptersType = chaptersType,
                                    inlineChapters = inlineChapters.sortedBy { it.startTimeMs }
                                )
                                if (guid.isNotBlank()) map[guid] = extra
                                if (enclosureUrl.isNotBlank()) map[enclosureUrl] = extra
                                if (link.isNotBlank()) map[link] = extra
                                if (title.isNotBlank() || pubDate.isNotBlank()) {
                                    map["$title|$pubDate"] = extra
                                }
                            }
                        } else if (local == "channel") {
                            inChannel = false
                        }
                        depth--
                    }
                }

                saxParser.parse(org.xml.sax.InputSource(java.io.StringReader(xml)), handler)
                RssPodcastExtras(language = channelLang, byItemKey = map)
            }.getOrDefault(RssPodcastExtras())
        }
    }
}