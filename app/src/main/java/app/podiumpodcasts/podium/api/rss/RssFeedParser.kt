package app.podiumpodcasts.podium.api.rss

import app.podiumpodcasts.podium.api.chapters.ChapterParser
import app.podiumpodcasts.podium.api.chapters.PodcastChapter
import com.prof18.rssparser.model.ItunesChannelData
import com.prof18.rssparser.model.ItunesItemData
import com.prof18.rssparser.model.ItunesOwner
import com.prof18.rssparser.model.RawEnclosure
import com.prof18.rssparser.model.RssChannel
import com.prof18.rssparser.model.RssImage
import com.prof18.rssparser.model.RssItem
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.util.IdentityHashMap
import javax.xml.parsers.SAXParserFactory

class ParsedFeed(
    val channel: RssChannel,
    val extras: RssPodcastExtras
)

/**
 * Reads an RSS 2.0 feed in ONE pass: the [RssChannel] the app already uses (built with the
 * RssParser library's own builders, so field values match what `RssParser().parse()` gives) plus
 * the Podcasting 2.0 extras (transcripts, chapters, inline Podlove chapters, channel language).
 *
 * The channel/item mapping mirrors RssParser 6.1.4's RSS reader (`internal/rss/RssParser.kt`,
 * `ChannelFactory`, and the model builders, which are internal to the library so they're
 * re-created below): case-insensitive prefixed tag names, "last wins" vs "first wins" per field,
 * enclosure type matching, image fallbacks, items with no data dropped.  That matters most for the fields episode ids are built from (guid,
 * enclosure audio/video URL, link, title, pubDate): a drift there would duplicate episodes.
 *
 * Returns null when the document isn't RSS (Atom, RDF) or isn't well-formed; the caller then
 * falls back to RssParser, without extras (as before, when the extras parse failed too).
 */
object RssFeedParser {

    private class NotRssException : RuntimeException("Not an RSS document", null, false, false)

    fun parse(xml: String): ParsedFeed? {
        return try {
            val factory = SAXParserFactory.newInstance().apply {
                // Like RssParser (namespace processing off): tags are matched by their literal
                // prefixed names, and an undeclared prefix doesn't fail the whole feed.
                isNamespaceAware = false
                isValidating = false
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            }
            val handler = Handler()
            val reader = factory.newSAXParser().xmlReader
            reader.contentHandler = handler
            reader.errorHandler = handler
            // Never fetch anything a DOCTYPE points at.
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
            reader.parse(InputSource(StringReader(xml.trimStart('\uFEFF', ' ', '\t', '\r', '\n'))))
            if (!handler.sawRss) null else handler.result()
        } catch (e: Exception) {
            null
        }
    }

    private const val CAPTURE_NONE = 0
    /** RssParser's `nextTrimmedText()`: all text inside the element; children aren't handled. */
    private const val CAPTURE_TEXT = 1
    /** RssParser's pubDate read: only the text before the first child element. */
    private const val CAPTURE_LEADING = 2

    private class Handler : DefaultHandler() {
        var sawRss = false

        // ---- RssParser state (see extractRSSContent / ChannelFactory) ----
        private var insideItem = false
        private var insideChannel = false
        private var insideChannelImage = false
        private var insideItunesOwner = false

        private val channelBuilder = ChannelBuilder()
        private var articleBuilder = ItemBuilder()
        private var ownerName: String? = null
        private var ownerEmail: String? = null
        private var imageUrlFromContent: String? = null

        // ---- text capture ----
        private var depth = 0

        /**
         * Depth of a subtree being skipped (a `<podcast:liveItem>`), or -1.  A live item has its
         * own `<title>`, `<description>`, people and so on; none of them are the show's.
         */
        private var skipDepth = -1
        private var captureMode = CAPTURE_NONE
        private var captureDepth = -1
        private var captureHadChild = false
        private var captureLeadingClosed = false
        private var captureSawText = false
        private var onCaptured: ((String?) -> Unit)? = null
        private val buf = StringBuilder()

        // An <image> inside an <item> (RssParser reads its leading text, or a first <link> child).
        private var itemImageDepth = -1
        private var itemImagePending = false
        private val itemImageLead = StringBuilder()

        // ---- extras state ----
        private var channelLang = ""
        private val channelFunding = ArrayList<PodcastFunding>()
        private val channelPersons = ArrayList<PodcastPerson>()
        private var channelTrailer: PodcastTrailer? = null
        private var channelExplicit = false

        private var itemDepth = -1
        private var inInlineChapters = false
        private val transcripts = ArrayList<TranscriptRef>()
        private var chaptersUrl = ""
        private var chaptersType = ""
        private val inlineChapters = ArrayList<PodcastChapter>()
        private val itemPersons = ArrayList<PodcastPerson>()
        private val itemSoundbites = ArrayList<PodcastSoundbite>()
        private var itemExplicit = false
        private var itemPodcastSeason: Int? = null
        private var itemPodcastEpisode: Int? = null
        private var itemSocialUrl = ""
        private var itemSocialPriority = Int.MAX_VALUE

        private val byItemKey = HashMap<String, RssEpisodeExtras>()
        private val byItem = IdentityHashMap<RssItem, RssEpisodeExtras>()
        private val parsedItems: MutableSet<RssItem> = java.util.Collections.newSetFromMap(IdentityHashMap())

        private fun isExplicitValue(raw: String?): Boolean {
            val v = raw?.trim()?.lowercase() ?: return false
            return v == "true" || v == "yes" || v == "explicit"
        }

        fun result(): ParsedFeed {
            return ParsedFeed(
                channel = channelBuilder.build(),
                extras = RssPodcastExtras(
                    language = channelLang,
                    funding = channelFunding.toList(),
                    persons = channelPersons.toList(),
                    trailer = channelTrailer,
                    explicit = channelExplicit,
                    byItemKey = byItemKey,
                    byItem = byItem,
                    parsedItems = parsedItems
                )
            )
        }

        private fun capture(mode: Int, block: (String?) -> Unit) {
            captureMode = mode
            captureDepth = depth
            captureHadChild = false
            captureLeadingClosed = false
            captureSawText = false
            onCaptured = block
            buf.setLength(0)
        }

        private fun is_(name: String, keyword: String) = name.equals(keyword, ignoreCase = true)

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            depth++
            val name = qName?.takeIf { it.isNotEmpty() } ?: localName.orEmpty()
            val attrs = attributes

            if (depth == 1) {
                if (!is_(name, "rss")) throw NotRssException()
                sawRss = true
                return
            }

            // Inside a skipped subtree: nothing in it is handled.
            if (skipDepth >= 0) return

            if (captureMode != CAPTURE_NONE) {
                // Children of an element being read as text are not handled on their own.
                captureHadChild = true
                captureLeadingClosed = true
                return
            }

            if (itemImageDepth >= 0 && itemImagePending && depth == itemImageDepth + 1) {
                itemImagePending = false
                val lead = itemImageLead.toString().trim()
                if (lead.isNotEmpty()) {
                    articleBuilder.image(lead)
                } else if (is_(name, "link")) {
                    capture(CAPTURE_TEXT) { articleBuilder.image(it) }
                    return
                } else {
                    // RssParser steps over this first child's start tag without handling it.
                    return
                }
            }

            if (insideChannel && !insideItem && is_(name, "podcast:liveItem")) {
                skipDepth = depth
                return
            }

            handleExtrasStart(name, attrs)
            if (captureMode != CAPTURE_NONE) return
            handleRssStart(name, attrs)
        }

        private fun attr(attrs: Attributes?, name: String): String? = attrs?.getValue(name)

        private fun handleRssStart(name: String, attrs: Attributes?) {
            when {
                is_(name, "channel") -> insideChannel = true
                is_(name, "item") -> insideItem = true
                is_(name, "itunes:owner") -> insideItunesOwner = true

                // Channel tags
                is_(name, "lastBuildDate") -> if (insideChannel) capture(CAPTURE_TEXT) { channelBuilder.lastBuildDate = it }
                is_(name, "sy:updatePeriod") -> if (insideChannel) capture(CAPTURE_TEXT) { channelBuilder.updatePeriod = it }
                is_(name, "url") -> if (insideChannelImage) capture(CAPTURE_TEXT) { channelBuilder.imageUrl = it }
                is_(name, "itunes:category") -> if (insideChannel) channelBuilder.addItunesCategory(attr(attrs, "text"))
                is_(name, "itunes:type") -> if (insideChannel) capture(CAPTURE_TEXT) { channelBuilder.itunesType = it }
                is_(name, "itunes:new-feed-url") -> if (insideChannel) capture(CAPTURE_TEXT) { channelBuilder.itunesNewFeedUrl = it }

                // Item tags
                is_(name, "dc:creator") -> if (insideItem) capture(CAPTURE_TEXT) { articleBuilder.author = it }
                is_(name, "dc:date") -> if (insideItem) capture(CAPTURE_TEXT) { articleBuilder.pubDateIfNull(it) }
                is_(name, "category") -> if (insideItem) capture(CAPTURE_TEXT) { articleBuilder.addCategory(it) }
                is_(name, "media:thumbnail") -> if (insideItem) articleBuilder.image(attr(attrs, "url"))
                is_(name, "media:content") -> if (insideItem) articleBuilder.image(attr(attrs, "url"))
                is_(name, "enclosure") -> if (insideItem) {
                    val type = attr(attrs, "type")
                    val length = attr(attrs, "length")
                    val urlAttr = attr(attrs, "url")
                    if (urlAttr == null) {
                        // No url attribute: RssParser takes the element's text.
                        capture(CAPTURE_TEXT) { applyEnclosure(type, it, length) }
                    } else {
                        applyEnclosure(type, urlAttr, length)
                    }
                }
                is_(name, "source") -> if (insideItem) {
                    val sourceUrl = attr(attrs, "url")
                    capture(CAPTURE_TEXT) { _ ->
                        // nextText(), not trimmed.
                        articleBuilder.sourceName = buf.toString()
                        articleBuilder.sourceUrl = sourceUrl
                    }
                }
                is_(name, "time") -> if (insideItem) capture(CAPTURE_TEXT) { articleBuilder.pubDate = it }
                is_(name, "guid") -> if (insideItem) capture(CAPTURE_TEXT) { articleBuilder.guid = it }
                is_(name, "content:encoded") -> if (insideItem) capture(CAPTURE_TEXT) {
                    articleBuilder.content = it
                    setImageFromContent(it)
                }
                is_(name, "pubDate") -> if (insideItem) capture(CAPTURE_LEADING) { text ->
                    if (text != null) articleBuilder.pubDate = text
                }
                is_(name, "News:Image") -> if (insideItem) capture(CAPTURE_TEXT) { articleBuilder.image(it) }
                is_(name, "itunes:episode") -> if (insideItem) capture(CAPTURE_TEXT) { articleBuilder.itEpisode = it }
                is_(name, "itunes:episodeType") -> if (insideItem) capture(CAPTURE_TEXT) { articleBuilder.itEpisodeType = it }
                is_(name, "itunes:season") -> if (insideItem) capture(CAPTURE_TEXT) { articleBuilder.itSeason = it }
                is_(name, "comments") -> if (insideItem) capture(CAPTURE_TEXT) { articleBuilder.commentUrl = it }
                is_(name, "thumb") -> if (insideItem) capture(CAPTURE_TEXT) { articleBuilder.image(it) }

                // iTunes owner
                is_(name, "itunes:name") -> if (insideItunesOwner) capture(CAPTURE_TEXT) { ownerName = it }
                is_(name, "itunes:email") -> if (insideItunesOwner) capture(CAPTURE_TEXT) { ownerEmail = it }

                // Mixed tags
                is_(name, "image") -> when {
                    insideChannel && !insideItem -> insideChannelImage = true
                    insideItem -> {
                        itemImageDepth = depth
                        itemImagePending = true
                        itemImageLead.setLength(0)
                    }
                }
                is_(name, "title") -> if (insideChannel) capture(CAPTURE_TEXT) { text ->
                    when {
                        insideChannelImage -> channelBuilder.imageTitle = text
                        insideItem -> articleBuilder.title = text
                        else -> channelBuilder.title = text
                    }
                }
                is_(name, "link") -> if (insideChannel) capture(CAPTURE_TEXT) { text ->
                    when {
                        insideChannelImage -> channelBuilder.imageLink = text
                        insideItem -> articleBuilder.link(text)
                        else -> channelBuilder.link = text
                    }
                }
                is_(name, "description") -> if (insideChannel) capture(CAPTURE_TEXT) { text ->
                    when {
                        insideItem -> {
                            articleBuilder.description = text
                            setImageFromContent(text)
                        }
                        insideChannelImage -> channelBuilder.imageDescription = text
                        else -> channelBuilder.description = text
                    }
                }
                is_(name, "itunes:author") -> when {
                    insideItem -> capture(CAPTURE_TEXT) { articleBuilder.itAuthor = it }
                    insideChannel -> capture(CAPTURE_TEXT) { channelBuilder.itAuthor = it }
                }
                is_(name, "itunes:duration") -> when {
                    insideItem -> capture(CAPTURE_TEXT) { articleBuilder.itDuration = it }
                    insideChannel -> capture(CAPTURE_TEXT) { channelBuilder.itDuration = it }
                }
                is_(name, "itunes:keywords") -> capture(CAPTURE_TEXT) { text ->
                    val keywords = text?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                    when {
                        insideItem -> articleBuilder.itKeywords = keywords
                        insideChannel -> channelBuilder.itKeywords = keywords
                    }
                }
                is_(name, "itunes:image") -> when {
                    insideItem -> articleBuilder.itImage = attr(attrs, "href")
                    insideChannel -> channelBuilder.itImage = attr(attrs, "href")
                }
                is_(name, "itunes:explicit") -> when {
                    insideItem -> capture(CAPTURE_TEXT) { articleBuilder.itExplicit = it }
                    insideChannel -> capture(CAPTURE_TEXT) { channelBuilder.itExplicit = it }
                }
                is_(name, "itunes:subtitle") -> when {
                    insideItem -> capture(CAPTURE_TEXT) { articleBuilder.itSubtitle = it }
                    insideChannel -> capture(CAPTURE_TEXT) { channelBuilder.itSubtitle = it }
                }
                is_(name, "itunes:summary") -> when {
                    insideItem -> capture(CAPTURE_TEXT) { articleBuilder.itSummary = it }
                    insideChannel -> capture(CAPTURE_TEXT) { channelBuilder.itSummary = it }
                }
            }
        }

        private fun applyEnclosure(type: String?, url: String?, length: String?) {
            articleBuilder.encLength = length?.toLongOrNull()
            articleBuilder.encType = type
            articleBuilder.encUrl = url
            when {
                type != null && type.contains("image") -> articleBuilder.image(url)
                type != null && type.contains("audio") -> { if (articleBuilder.audio == null) articleBuilder.audio = url }
                type != null && type.contains("video") -> { if (articleBuilder.video == null) articleBuilder.video = url }
                type == null && url != null -> articleBuilder.image(url)
            }
        }

        private fun setImageFromContent(content: String?) {
            if (content == null) return
            runCatching {
                val decoded = content
                    .replace("&amp;amp;", "&amp;")
                    .replace("&amp;", "&")
                    .replace("&quot;", "\"")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                val match = CONTENT_IMAGE_REGEX.find(decoded) ?: return
                val url = match.value.trim()
                if (!url.contains("https://s.w.org/images/core/emoji") && !url.contains("/smilies/")) {
                    imageUrlFromContent = url
                }
            }
        }

        private fun buildArticle() {
            articleBuilder.image(imageUrlFromContent)
            articleBuilder.image(articleBuilder.itImage)
            val item = articleBuilder.build()
            if (item != null) {
                channelBuilder.items.add(item)
                parsedItems.add(item)
                finishItemExtras(item)
            } else {
                resetItemExtras()
            }
            imageUrlFromContent = null
            articleBuilder = ItemBuilder()
        }

        // ---- extras ----

        private fun handleExtrasStart(name: String, attrs: Attributes?) {
            val local = name.substringAfter(':')
            when {
                is_(name, "item") && insideChannel -> {
                    itemDepth = depth
                    inInlineChapters = false
                    transcripts.clear()
                    chaptersUrl = ""
                    chaptersType = ""
                    inlineChapters.clear()
                    itemPersons.clear()
                    itemSoundbites.clear()
                    itemExplicit = false
                    itemPodcastSeason = null
                    itemPodcastEpisode = null
                    itemSocialUrl = ""
                    itemSocialPriority = Int.MAX_VALUE
                }
                insideChannel && !insideItem && local == "language" && channelLang.isBlank() -> {
                    capture(CAPTURE_TEXT) { if (channelLang.isBlank()) channelLang = it.orEmpty() }
                }
                insideChannel && !insideItem && is_(name, "itunes:explicit") -> {
                    capture(CAPTURE_TEXT) { text ->
                        channelExplicit = isExplicitValue(text)
                        channelBuilder.itExplicit = text
                    }
                }
                insideChannel && !insideItem && is_(name, "podcast:funding") -> {
                    val url = attr(attrs, "url")?.trim().orEmpty()
                    if (url.isNotBlank()) {
                        capture(CAPTURE_TEXT) { label ->
                            channelFunding += PodcastFunding(
                                url = url,
                                label = label?.trim().orEmpty()
                            )
                        }
                    }
                }
                insideChannel && !insideItem && is_(name, "podcast:person") -> {
                    val role = attr(attrs, "role")?.trim().orEmpty()
                    val group = attr(attrs, "group")?.trim().orEmpty()
                    val img = attr(attrs, "img")?.trim().orEmpty()
                    val href = attr(attrs, "href")?.trim().orEmpty()
                    capture(CAPTURE_TEXT) { personName ->
                        val clean = personName?.trim().orEmpty()
                        if (clean.isNotBlank()) {
                            channelPersons += PodcastPerson(
                                name = clean,
                                role = role,
                                group = group,
                                img = img,
                                href = href
                            )
                        }
                    }
                }
                insideChannel && !insideItem && is_(name, "podcast:trailer") -> {
                    val url = attr(attrs, "url")?.trim().orEmpty()
                    if (url.isNotBlank() && channelTrailer == null) {
                        val pubDate = attr(attrs, "pubdate")?.trim().orEmpty()
                        val length = attr(attrs, "length")?.trim()?.toLongOrNull() ?: 0L
                        val type = attr(attrs, "type")?.trim().orEmpty()
                        val season = attr(attrs, "season")?.trim()?.toIntOrNull()
                        capture(CAPTURE_TEXT) { title ->
                            channelTrailer = PodcastTrailer(
                                url = url,
                                title = title?.trim().orEmpty(),
                                pubDate = pubDate,
                                length = length,
                                type = type,
                                season = season
                            )
                        }
                    }
                }
                itemDepth >= 0 && depth == itemDepth + 1 -> when {
                    local == "transcript" -> {
                        val url = attr(attrs, "url")?.trim().orEmpty()
                        if (url.isNotBlank()) {
                            transcripts += TranscriptRef(url, attr(attrs, "type")?.trim().orEmpty())
                        }
                    }
                    local == "chapters" -> {
                        val url = attr(attrs, "url")?.trim().orEmpty()
                        val type = attr(attrs, "type")?.trim().orEmpty()
                        if (url.isNotBlank()) {
                            if (chaptersUrl.isBlank()) {
                                chaptersUrl = url
                                chaptersType = type.ifBlank { "application/json+chapters" }
                            }
                        } else {
                            inInlineChapters = true
                        }
                    }
                    is_(name, "itunes:explicit") -> {
                        capture(CAPTURE_TEXT) { text ->
                            itemExplicit = isExplicitValue(text)
                            articleBuilder.itExplicit = text
                        }
                    }
                    is_(name, "podcast:person") -> {
                        val role = attr(attrs, "role")?.trim().orEmpty()
                        val group = attr(attrs, "group")?.trim().orEmpty()
                        val img = attr(attrs, "img")?.trim().orEmpty()
                        val href = attr(attrs, "href")?.trim().orEmpty()
                        capture(CAPTURE_TEXT) { personName ->
                            val clean = personName?.trim().orEmpty()
                            if (clean.isNotBlank()) {
                                itemPersons += PodcastPerson(
                                    name = clean,
                                    role = role,
                                    group = group,
                                    img = img,
                                    href = href
                                )
                            }
                        }
                    }
                    // The Podcasting 2.0 season and episode, used where the iTunes ones are missing.
                    is_(name, "podcast:season") -> capture(CAPTURE_TEXT) { text ->
                        itemPodcastSeason = text?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
                    }
                    is_(name, "podcast:episode") -> capture(CAPTURE_TEXT) { text ->
                        // Decimals ("1.5") are allowed; only whole numbers make an "EP. n" badge.
                        val number = text?.trim()?.toDoubleOrNull()
                        itemPodcastEpisode = number?.takeIf { it >= 1.0 && it % 1.0 == 0.0 }?.toInt()
                    }
                    // Where the episode is discussed (a Mastodon post, for example): the comments
                    // link when the item has no <comments>.  Lowest priority number wins.
                    is_(name, "podcast:socialInteract") -> {
                        val protocol = attr(attrs, "protocol")?.trim().orEmpty()
                        val uri = attr(attrs, "uri")?.trim().orEmpty()
                        val priority = attr(attrs, "priority")?.trim()?.toIntOrNull() ?: (Int.MAX_VALUE - 1)
                        val isWebLink = uri.startsWith("https://", ignoreCase = true) ||
                            uri.startsWith("http://", ignoreCase = true)
                        if (!protocol.equals("disabled", ignoreCase = true) && isWebLink &&
                            priority < itemSocialPriority
                        ) {
                            itemSocialUrl = uri
                            itemSocialPriority = priority
                        }
                    }
                    is_(name, "podcast:soundbite") -> {
                        val startTime = attr(attrs, "startTime")?.trim()?.toDoubleOrNull()
                        val duration = attr(attrs, "duration")?.trim()?.toDoubleOrNull()
                        if (startTime != null && duration != null && startTime >= 0.0 && duration > 0.0) {
                            capture(CAPTURE_TEXT) { title ->
                                itemSoundbites += PodcastSoundbite(
                                    startTimeSec = startTime,
                                    durationSec = duration,
                                    title = title?.trim().orEmpty()
                                )
                            }
                        }
                    }
                }
                itemDepth >= 0 && inInlineChapters && local == "chapter" -> {
                    val startMs = ChapterParser.parseNptTimeMs(attr(attrs, "start")?.trim().orEmpty())
                    if (startMs >= 0L) {
                        inlineChapters += PodcastChapter(
                            startTimeMs = startMs,
                            title = attr(attrs, "title")?.trim().orEmpty(),
                            url = attr(attrs, "href")?.trim()?.takeIf { it.isNotBlank() }
                        )
                    }
                }
            }
        }

        private fun resetItemExtras() {
            itemDepth = -1
            inInlineChapters = false
        }

        private fun finishItemExtras(item: RssItem) {
            resetItemExtras()
            val ordered = TranscriptRanking.order(transcripts)
            if (ordered.isEmpty() && chaptersUrl.isBlank() && inlineChapters.isEmpty() &&
                itemPersons.isEmpty() && itemSoundbites.isEmpty() && !itemExplicit &&
                itemPodcastSeason == null && itemPodcastEpisode == null && itemSocialUrl.isBlank()
            ) return
            val primary = ordered.firstOrNull()
            val extra = RssEpisodeExtras(
                transcriptUrl = primary?.url.orEmpty(),
                transcriptType = primary?.type.orEmpty(),
                chaptersUrl = chaptersUrl,
                chaptersType = chaptersType,
                inlineChapters = inlineChapters.sortedBy { it.startTimeMs },
                transcriptAlternates = if (ordered.size > 1) ordered.drop(1) else emptyList(),
                persons = itemPersons.toList(),
                soundbites = itemSoundbites.toList(),
                explicit = itemExplicit,
                season = itemPodcastSeason,
                episode = itemPodcastEpisode,
                socialInteractUrl = itemSocialUrl
            )
            byItem[item] = extra
            // The same keys RssPodcastExtras.lookup() tries, from the same parsed values.
            item.guid?.takeIf { it.isNotBlank() }?.let { byItemKey[it] = extra }
            item.audio?.takeIf { it.isNotBlank() }?.let { byItemKey[it] = extra }
            item.video?.takeIf { it.isNotBlank() }?.let { byItemKey[it] = extra }
            item.link?.takeIf { it.isNotBlank() }?.let { byItemKey[it] = extra }
            if (!item.title.isNullOrBlank() || !item.pubDate.isNullOrBlank()) {
                byItemKey["${item.title.orEmpty()}|${item.pubDate.orEmpty()}"] = extra
            }
        }

        // ---- SAX plumbing ----

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (ch == null || length <= 0) return
            if (captureMode == CAPTURE_TEXT) {
                buf.append(ch, start, length)
                captureSawText = true
            } else if (captureMode == CAPTURE_LEADING) {
                if (!captureLeadingClosed) {
                    buf.append(ch, start, length)
                    captureSawText = true
                }
            } else if (itemImageDepth >= 0 && itemImagePending && depth == itemImageDepth) {
                itemImageLead.append(ch, start, length)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = qName?.takeIf { it.isNotEmpty() } ?: localName.orEmpty()
            try {
                if (skipDepth >= 0) {
                    if (depth == skipDepth) skipDepth = -1
                    return
                }

                if (captureMode != CAPTURE_NONE) {
                    if (depth != captureDepth) return
                    val mode = captureMode
                    val raw = buf.toString()
                    val text: String? = when (mode) {
                        CAPTURE_LEADING -> if (captureSawText) raw.trim() else null
                        // RssParser gives null for an empty element that had child elements.
                        else -> raw.trim().let { if (it.isEmpty() && captureHadChild) null else it }
                    }
                    val block = onCaptured
                    captureMode = CAPTURE_NONE
                    captureDepth = -1
                    onCaptured = null
                    block?.invoke(text)
                    buf.setLength(0)
                    return
                }

                if (itemImageDepth >= 0 && depth == itemImageDepth) {
                    if (itemImagePending) {
                        val lead = itemImageLead.toString().trim()
                        if (lead.isNotEmpty()) articleBuilder.image(lead)
                    }
                    itemImageDepth = -1
                    itemImagePending = false
                }

                when {
                    is_(name, "chapters") -> inInlineChapters = false
                    is_(name, "item") -> {
                        insideItem = false
                        buildArticle()
                    }
                    is_(name, "channel") -> insideChannel = false
                    is_(name, "image") -> insideChannelImage = false
                    is_(name, "itunes:owner") -> {
                        channelBuilder.itOwner = if (ownerName.isNullOrBlank() && ownerEmail.isNullOrBlank()) {
                            null
                        } else {
                            ItunesOwner(name = ownerName, email = ownerEmail)
                        }
                        ownerName = null
                        ownerEmail = null
                        insideItunesOwner = false
                    }
                }
                // Also leave inline-chapter mode for prefixed closers (psc:chapters).
                if (name.substringAfter(':') == "chapters") inInlineChapters = false
            } finally {
                depth--
            }
        }

        override fun fatalError(e: org.xml.sax.SAXParseException) {
            throw e
        }
    }

    /** RssParser's RssItem.Builder + ItunesItemData.Builder + RawEnclosure.Builder (internal there). */
    private class ItemBuilder {
        var guid: String? = null
        var title: String? = null
        var author: String? = null
        var link: String? = null
        var pubDate: String? = null
        var description: String? = null
        var content: String? = null
        var image: String? = null
        var audio: String? = null
        var video: String? = null
        var sourceName: String? = null
        var sourceUrl: String? = null
        val categories = ArrayList<String>()
        var commentUrl: String? = null

        var itImage: String? = null
        var itDuration: String? = null
        var itExplicit: String? = null
        var itKeywords: List<String> = emptyList()
        var itSubtitle: String? = null
        var itEpisode: String? = null
        var itEpisodeType: String? = null
        var itAuthor: String? = null
        var itSummary: String? = null
        var itSeason: String? = null

        var encUrl: String? = null
        var encLength: Long? = null
        var encType: String? = null

        /** RSS links have no `rel`, so the first non-blank one wins. */
        fun link(value: String?) {
            if (!value.isNullOrBlank() && link == null) link = value
        }

        /** First non-empty image wins. */
        fun image(value: String?) {
            if (image == null && !value.isNullOrEmpty()) image = value
        }

        fun pubDateIfNull(value: String?) {
            if (pubDate == null) pubDate = value
        }

        fun addCategory(value: String?) {
            if (value != null) categories.add(value)
        }

        private fun itunes(): ItunesItemData? {
            val empty = itImage.isNullOrBlank() && itDuration.isNullOrBlank() && itExplicit.isNullOrBlank() &&
                itKeywords.isEmpty() && itSubtitle.isNullOrBlank() && itEpisode.isNullOrBlank() &&
                itEpisodeType.isNullOrBlank() && itAuthor.isNullOrBlank() && itSummary.isNullOrBlank() &&
                itSeason.isNullOrBlank()
            if (empty) return null
            return ItunesItemData(
                author = itAuthor,
                duration = itDuration,
                episode = itEpisode,
                episodeType = itEpisodeType,
                explicit = itExplicit,
                image = itImage,
                keywords = itKeywords,
                subtitle = itSubtitle,
                summary = itSummary,
                season = itSeason
            )
        }

        private fun rawEnclosure(): RawEnclosure? {
            if (encUrl.isNullOrBlank() && encLength == null && encType.isNullOrBlank()) return null
            return RawEnclosure(url = encUrl, length = encLength, type = encType)
        }

        /** Null when the item has no data at all (RssParser drops those). */
        fun build(): RssItem? {
            val itunes = itunes()
            val raw = rawEnclosure()
            val empty = guid.isNullOrBlank() && title.isNullOrBlank() && author.isNullOrBlank() &&
                link.isNullOrBlank() && pubDate.isNullOrBlank() && description.isNullOrBlank() &&
                content.isNullOrBlank() && image.isNullOrBlank() && audio.isNullOrBlank() &&
                video.isNullOrBlank() && sourceName.isNullOrBlank() && sourceUrl.isNullOrBlank() &&
                categories.isEmpty() && itunes == null && commentUrl.isNullOrBlank() && raw == null
            if (empty) return null
            return RssItem(
                guid = guid,
                title = title,
                author = author,
                link = link,
                pubDate = pubDate,
                description = description,
                content = content,
                image = image,
                audio = audio,
                video = video,
                sourceName = sourceName,
                sourceUrl = sourceUrl,
                categories = categories.toList(),
                itunesItemData = itunes,
                commentsUrl = commentUrl,
                youtubeItemData = null,
                rawEnclosure = raw
            )
        }
    }

    /** RssParser's RssChannel/RssImage/ItunesChannelData builders + ChannelFactory.build(). */
    private class ChannelBuilder {
        var title: String? = null
        var link: String? = null
        var description: String? = null
        var lastBuildDate: String? = null
        var updatePeriod: String? = null
        val items = ArrayList<RssItem>()

        var imageTitle: String? = null
        var imageUrl: String? = null
        var imageLink: String? = null
        var imageDescription: String? = null

        var itAuthor: String? = null
        val itCategories = ArrayList<String>()
        var itDuration: String? = null
        var itExplicit: String? = null
        var itImage: String? = null
        var itKeywords: List<String> = emptyList()
        var itunesNewFeedUrl: String? = null
        var itOwner: ItunesOwner? = null
        var itSubtitle: String? = null
        var itSummary: String? = null
        var itunesType: String? = null

        fun addItunesCategory(value: String?) {
            if (!value.isNullOrEmpty()) itCategories.add(value)
        }

        private fun itunes(): ItunesChannelData? {
            val empty = itExplicit.isNullOrBlank() && itunesType.isNullOrBlank() && itSubtitle.isNullOrBlank() &&
                itAuthor.isNullOrBlank() && itSummary.isNullOrBlank() && itImage.isNullOrBlank() &&
                itCategories.isEmpty() && itunesNewFeedUrl.isNullOrBlank() && itOwner == null &&
                itDuration.isNullOrBlank() && itKeywords.isEmpty()
            if (empty) return null
            return ItunesChannelData(
                author = itAuthor,
                categories = itCategories.toList(),
                duration = itDuration,
                explicit = itExplicit,
                image = itImage,
                keywords = itKeywords,
                newsFeedUrl = itunesNewFeedUrl,
                owner = itOwner,
                subtitle = itSubtitle,
                summary = itSummary,
                type = itunesType
            )
        }

        fun build(): RssChannel {
            val itunes = itunes()
            val imageEmpty = imageTitle.isNullOrBlank() && imageUrl.isNullOrBlank() &&
                imageLink.isNullOrBlank() && imageDescription.isNullOrBlank()
            // The <image> element when it has a URL or link, else the iTunes artwork.
            val image = if (!imageEmpty && (!imageUrl.isNullOrBlank() || !imageLink.isNullOrBlank())) {
                RssImage(title = imageTitle, url = imageUrl, link = imageLink, description = imageDescription)
            } else if (itunes?.image != null) {
                RssImage(title = null, url = itunes.image, link = null, description = null)
            } else {
                null
            }
            return RssChannel(
                title = title,
                link = link,
                description = description,
                image = image,
                lastBuildDate = lastBuildDate,
                updatePeriod = updatePeriod,
                items = items.toList(),
                itunesChannelData = itunes,
                youtubeChannelData = null
            )
        }
    }

    private val CONTENT_IMAGE_REGEX = Regex(
        "https?://[^\\s<>\"']+\\.(?:jpg|jpeg|png|gif|bmp|webp)(?:\\?[^\\s<>\"']*)?",
        RegexOption.IGNORE_CASE
    )
}
