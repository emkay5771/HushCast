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

    @Test
    fun `parses podcasting 2_0 and itunes channel and item metadata`() {
        val xml = """
            <rss version="2.0"
                 xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
                 xmlns:podcast="https://podcastindex.org/namespace/1.0">
              <channel>
                <title>Serial Show</title>
                <itunes:type>serial</itunes:type>
                <itunes:explicit>yes</itunes:explicit>
                <itunes:category text="Technology" />
                <itunes:new-feed-url>https://new.example/feed.xml</itunes:new-feed-url>
                <podcast:funding url="https://ko-fi.com/show">Support on Ko-fi</podcast:funding>
                <podcast:person role="host" img="https://cdn.example/host.jpg" href="https://host.example">Alice</podcast:person>
                <podcast:trailer url="https://cdn.example/trailer.mp3" season="1">Season 1 Trailer</podcast:trailer>
                <item>
                  <title>Pilot</title>
                  <comments>https://forum.example/ep1</comments>
                  <itunes:season>1</itunes:season>
                  <itunes:episode>1</itunes:episode>
                  <itunes:episodeType>full</itunes:episodeType>
                  <itunes:explicit>true</itunes:explicit>
                  <podcast:person role="guest">Bob</podcast:person>
                  <podcast:soundbite startTime="12.5" duration="30">Best moment</podcast:soundbite>
                  <enclosure url="https://cdn.example/ep1.mp3" type="audio/mpeg" length="1000"/>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val parsed = RssFeedParser.parse(xml)!!
        assertEquals("serial", parsed.channel.itunesChannelData?.type)
        assertEquals(listOf("Technology"), parsed.channel.itunesChannelData?.categories)
        assertEquals("https://new.example/feed.xml", parsed.channel.itunesChannelData?.newsFeedUrl)
        assertTrue(parsed.extras.explicit)
        assertEquals(1, parsed.extras.funding.size)
        assertEquals("https://ko-fi.com/show", parsed.extras.funding[0].url)
        assertEquals("Support on Ko-fi", parsed.extras.funding[0].label)
        assertEquals("Alice", parsed.extras.persons.single().name)
        assertEquals("https://cdn.example/trailer.mp3", parsed.extras.trailer?.url)
        assertEquals("Season 1 Trailer", parsed.extras.trailer?.title)

        val item = parsed.channel.items.single()
        assertEquals("https://forum.example/ep1", item.commentsUrl)
        assertEquals("1", item.itunesItemData?.season)
        assertEquals("1", item.itunesItemData?.episode)
        assertEquals("full", item.itunesItemData?.episodeType)
        val epExtras = parsed.extras.lookup(item)!!
        assertTrue(epExtras.explicit)
        assertEquals("Bob", epExtras.persons.single().name)
        assertEquals(12.5, epExtras.soundbites.single().startTimeSec, 0.01)
        assertEquals(30.0, epExtras.soundbites.single().durationSec, 0.01)
        assertEquals("Best moment", epExtras.soundbites.single().title)
    }
}
