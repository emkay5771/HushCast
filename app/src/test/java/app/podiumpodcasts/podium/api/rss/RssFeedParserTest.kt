package app.podiumpodcasts.podium.api.rss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RssFeedParserTest {

    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"
             xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
             xmlns:podcast="https://podcastindex.org/namespace/1.0"
             xmlns:content="http://purl.org/rss/1.0/modules/content/"
             xmlns:psc="http://podlove.org/simple-chapters">
          <channel>
            <title>  My Show </title>
            <link>https://show.example</link>
            <language>en-us</language>
            <description><![CDATA[About <b>the</b> show]]></description>
            <itunes:author>Host Name</itunes:author>
            <itunes:image href="https://show.example/art.jpg"/>
            <item>
              <title>Episode 2</title>
              <guid isPermaLink="false"> ep-2 </guid>
              <link>https://show.example</link>
              <pubDate>Tue, 17 Nov 2026 10:00:00 GMT</pubDate>
              <description>Second &amp; last</description>
              <itunes:duration>01:02:03</itunes:duration>
              <itunes:image href="https://show.example/ep2.jpg"/>
              <enclosure url="https://cdn.example/ep2.mp3" length="123" type="audio/mpeg"/>
              <podcast:transcript url="https://show.example/ep2.html" type="text/html"/>
              <podcast:transcript url="https://show.example/ep2.srt" type="application/x-subrip"/>
              <podcast:transcript url="https://show.example/ep2.json" type="application/json"/>
              <podcast:transcript url="https://show.example/ep2.vtt" type="text/vtt"/>
              <podcast:chapters url="https://show.example/ep2-chapters.json" type="application/json+chapters"/>
            </item>
            <item>
              <title>Episode 1</title>
              <link>https://show.example</link>
              <pubDate>Mon, 16 Nov 2026 10:00:00 GMT</pubDate>
              <enclosure url="https://cdn.example/ep1.mp3" type="audio/mpeg"/>
              <enclosure url="https://cdn.example/ep1-other.mp3" type="audio/mpeg"/>
              <psc:chapters version="1.2">
                <psc:chapter start="00:00:00.000" title="Opening"/>
                <psc:chapter start="00:01:00.000" title="Middle"/>
              </psc:chapters>
            </item>
            <item>
              <title>Episode 0</title>
              <link>https://show.example</link>
              <guid>ep-0</guid>
              <enclosure url="https://cdn.example/ep0.mp4" type="video/mp4"/>
              <content:encoded><![CDATA[<p>Hi <img src="https://img.example/pic.png"/></p>]]></content:encoded>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    @Test
    fun `channel fields match what RssParser gives`() {
        val parsed = RssFeedParser.parse(feed)
        assertNotNull(parsed)
        val channel = parsed!!.channel
        assertEquals("My Show", channel.title)
        assertEquals("https://show.example", channel.link)
        assertEquals("About <b>the</b> show", channel.description)
        assertEquals("Host Name", channel.itunesChannelData?.author)
        // No <image> element: the iTunes image stands in.
        assertEquals("https://show.example/art.jpg", channel.image?.url)
        assertEquals("en-us", parsed.extras.language)
        assertEquals(3, channel.items.size)
    }

    @Test
    fun `item fields used for episode ids are parsed like RssParser`() {
        val items = RssFeedParser.parse(feed)!!.channel.items
        val ep2 = items[0]
        assertEquals("ep-2", ep2.guid)
        assertEquals("Episode 2", ep2.title)
        assertEquals("https://show.example", ep2.link)
        assertEquals("Tue, 17 Nov 2026 10:00:00 GMT", ep2.pubDate)
        assertEquals("https://cdn.example/ep2.mp3", ep2.audio)
        assertEquals("Second & last", ep2.description)
        assertEquals("01:02:03", ep2.itunesItemData?.duration)
        assertEquals("https://show.example/ep2.jpg", ep2.itunesItemData?.image)

        val ep1 = items[1]
        assertNull(ep1.guid)
        // First audio enclosure wins.
        assertEquals("https://cdn.example/ep1.mp3", ep1.audio)

        val ep0 = items[2]
        assertNull(ep0.audio)
        assertEquals("https://cdn.example/ep0.mp4", ep0.video)
        assertEquals("https://img.example/pic.png", ep0.image)
    }

    @Test
    fun `transcripts are ranked and the rest kept as alternates`() {
        val parsed = RssFeedParser.parse(feed)!!
        val ep2 = parsed.channel.items[0]
        val extra = parsed.extras.lookup(ep2)!!
        assertEquals("https://show.example/ep2.json", extra.transcriptUrl)
        assertEquals("application/json", extra.transcriptType)
        assertEquals(
            listOf("https://show.example/ep2.vtt", "https://show.example/ep2.srt", "https://show.example/ep2.html"),
            extra.transcriptAlternates.map { it.url }
        )
        assertEquals("https://show.example/ep2-chapters.json", extra.chaptersUrl)
        assertEquals(extra, parsed.extras.byItemKey["ep-2"])
    }

    @Test
    fun `inline chapters and per-item lookup without borrowing via shared links`() {
        val parsed = RssFeedParser.parse(feed)!!
        val (ep2, ep1, ep0) = parsed.channel.items
        val ep1Extras = parsed.extras.lookup(ep1)!!
        assertEquals(listOf("Opening", "Middle"), ep1Extras.inlineChapters.map { it.title })
        assertEquals(60_000L, ep1Extras.inlineChapters[1].startTimeMs)
        assertEquals("", ep1Extras.transcriptUrl)
        // Episode 0 shares its <link> with the others but has no extras of its own.
        assertNull(parsed.extras.lookup(ep0))
        assertSame(parsed.extras.lookup(ep2), parsed.extras.byItem[ep2])
    }

    @Test
    fun `parsePodcastExtras still works on its own`() {
        val extras = FetchPodcastClient.parsePodcastExtras(feed)
        assertEquals("https://show.example/ep2.json", extras.byItemKey["ep-2"]?.transcriptUrl)
    }

    @Test
    fun `atom and broken documents are left to RssParser`() {
        assertNull(RssFeedParser.parse("""<feed xmlns="http://www.w3.org/2005/Atom"><title>x</title></feed>"""))
        assertNull(RssFeedParser.parse("<rss><channel><title>broken</channel></rss>"))
        assertNull(RssFeedParser.parse(""))
    }

    @Test
    fun `channel image element wins over the itunes image and nested text is not dispatched`() {
        val xml = """
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel>
                <title>Show</title>
                <image><url>https://show.example/logo.png</url><title>Logo</title><link>https://show.example</link></image>
                <itunes:image href="https://show.example/art.jpg"/>
                <item>
                  <title>Ep</title>
                  <description>Intro <b>bold</b> end</description>
                  <enclosure url="https://cdn.example/e.mp3" type="audio/mpeg"/>
                </item>
              </channel>
            </rss>
        """.trimIndent()
        val channel = RssFeedParser.parse(xml)!!.channel
        assertEquals("https://show.example/logo.png", channel.image?.url)
        assertEquals("Show", channel.title)
        val item = channel.items.single()
        assertEquals("Ep", item.title)
        assertTrue(item.description!!.contains("bold"))
        assertTrue(item.description!!.endsWith("end"))
    }
}
