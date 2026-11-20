package app.podiumpodcasts.podium.player

import app.podiumpodcasts.podium.api.db.dao.PodcastEpisodesOrder
import app.podiumpodcasts.podium.api.db.dao.PodcastEpisodesOrderBy
import app.podiumpodcasts.podium.api.db.model.FEED_EXTRAS_VERSION
import app.podiumpodcasts.podium.api.rss.ParsedFeed
import app.podiumpodcasts.podium.api.rss.RssFeedParser
import app.podiumpodcasts.podium.background.previewMutesWords
import app.podiumpodcasts.podium.background.work.FeedCacheValues
import app.podiumpodcasts.podium.background.work.feedCacheValuesAfterFetch
import app.podiumpodcasts.podium.ui.component.model.podcast.PodcastSearchFilterOrderBarState
import app.podiumpodcasts.podium.ui.component.model.podcast.formatPreviewClock
import app.podiumpodcasts.podium.utils.rss.toPodcast
import app.podiumpodcasts.podium.utils.rss.toPodcastEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for 1.0.45: the fixes from the 1.0.44 Podcasting 2.0 review. */
class Release1045Test {

    private fun feed(channelExtra: String, items: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"
             xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
             xmlns:podcast="https://podcastindex.org/namespace/1.0">
          <channel>
            <title>The Real Show</title>
            <description>The show's own description.</description>
            $channelExtra
            $items
          </channel>
        </rss>
    """.trimIndent()

    private fun item(guid: String, extra: String) = """
        <item>
          <title>Episode $guid</title>
          <guid>$guid</guid>
          <enclosure url="https://cdn.example/$guid.mp3" type="audio/mpeg" length="1"/>
          $extra
        </item>
    """

    private fun parse(xml: String): ParsedFeed = RssFeedParser.parse(xml)!!

    private fun episodes(parsed: ParsedFeed) = parsed.channel.toPodcast(
        origin = "https://show.example/feed",
        fileSize = 0L,
        oldPodcast = null,
        extras = parsed.extras
    ).let { podcast ->
        parsed.channel.items.map { it.toPodcastEpisode(podcast = podcast, extras = parsed.extras) }
    }

    // --- M2: <podcast:liveItem> is not the show ---

    @Test
    fun `a live item's title, description and people don't become the show's`() {
        val parsed = parse(
            feed(
                channelExtra = """
                    <podcast:person role="host">Real Host</podcast:person>
                    <podcast:liveItem status="ended" start="2026-10-30T19:00:00Z" end="2026-10-30T21:00:00Z">
                      <title>PC2.0 277 Live</title>
                      <description>Live show text</description>
                      <podcast:person role="guest">Live Guest</podcast:person>
                      <enclosure url="https://live.example/stream" type="audio/mpeg" length="0"/>
                    </podcast:liveItem>
                """,
                items = item("ep1", "")
            )
        )
        assertEquals("The Real Show", parsed.channel.title)
        assertEquals("The show's own description.", parsed.channel.description)
        assertEquals(listOf("Real Host"), parsed.extras.persons.map { it.name })
        // Items after the live item are still read.
        assertEquals(listOf("Episode ep1"), parsed.channel.items.map { it.title })
    }

    // --- podcast:season / podcast:episode where the iTunes tags are missing ---

    @Test
    fun `the 2_0 season and episode fill in when the iTunes ones are missing`() {
        val parsed = parse(
            feed(
                channelExtra = "",
                items = item("a", "<podcast:season>2</podcast:season><podcast:episode>7</podcast:episode>") +
                    item("b", "<itunes:season>3</itunes:season><itunes:episode>4</itunes:episode><podcast:season>9</podcast:season><podcast:episode>9</podcast:episode>") +
                    item("c", "<podcast:episode>1.5</podcast:episode>")
            )
        )
        val (a, b, c) = episodes(parsed)
        assertEquals(2, a.season)
        assertEquals(7, a.episodeNumber)
        // The iTunes tags win when both are there.
        assertEquals(3, b.season)
        assertEquals(4, b.episodeNumber)
        // "1.5" can't be an "EP. n" badge.
        assertNull(c.episodeNumber)
    }

    // --- podcast:socialInteract as the comments link ---

    @Test
    fun `socialInteract becomes the comments link only when there's no comments tag`() {
        val parsed = parse(
            feed(
                channelExtra = "",
                items = item(
                    "a",
                    """<podcast:socialInteract protocol="activitypub" uri="https://mastodon.example/@show/2" priority="2"/>
                       <podcast:socialInteract protocol="activitypub" uri="https://mastodon.example/@show/1" priority="1"/>"""
                ) +
                    item("b", """<comments>https://forum.example/b</comments><podcast:socialInteract protocol="activitypub" uri="https://mastodon.example/@show/b"/>""") +
                    item("c", """<podcast:socialInteract protocol="disabled" uri="https://mastodon.example/@show/c"/>""")
            )
        )
        val (a, b, c) = episodes(parsed)
        assertEquals("https://mastodon.example/@show/1", a.commentsUrl)
        assertEquals("https://forum.example/b", b.commentsUrl)
        assertEquals("", c.commentsUrl)
    }

    // --- H2 / L2: refresh ---

    @Test
    fun `existing subscriptions get one full fetch for the new metadata`() {
        // 1 was the 1.0.31 transcripts/chapters pass; 2 makes 304-answering feeds re-read once.
        assertEquals(2, FEED_EXTRAS_VERSION)
    }

    @Test
    fun `a feed move starts the new URL without the old URL's validators`() {
        assertEquals(
            FeedCacheValues("", "", ""),
            feedCacheValuesAfterFetch(movedFeed = true, lastModified = "Mon, 02 Nov 2026 08:00:00 GMT", eTag = "\"a\"", contentLength = "10")
        )
        assertEquals(
            FeedCacheValues("Mon, 02 Nov 2026 08:00:00 GMT", "\"a\"", "10"),
            feedCacheValuesAfterFetch(movedFeed = false, lastModified = "Mon, 02 Nov 2026 08:00:00 GMT", eTag = "\"a\"", contentLength = "10")
        )
    }

    // --- Previews ---

    @Test
    fun `previews mute words only when the user opted in and the episode was processed`() {
        // Off by default.
        assertFalse(previewMutesWords(previewMuting = false, globalWordMuting = true, podcastOverride = null, hasMuteWindows = true))
        assertTrue(previewMutesWords(previewMuting = true, globalWordMuting = true, podcastOverride = null, hasMuteWindows = true))
        // Not processed: nothing to mute.
        assertFalse(previewMutesWords(previewMuting = true, globalWordMuting = true, podcastOverride = null, hasMuteWindows = false))
        // The podcast's own setting wins over the global one, both ways.
        assertFalse(previewMutesWords(previewMuting = true, globalWordMuting = true, podcastOverride = false, hasMuteWindows = true))
        assertTrue(previewMutesWords(previewMuting = true, globalWordMuting = false, podcastOverride = true, hasMuteWindows = true))
    }

    @Test
    fun `the preview countdown rounds up so it never reads 0_00 while playing`() {
        assertEquals("0:08", formatPreviewClock(8_000L))
        assertEquals("0:08", formatPreviewClock(7_200L))
        assertEquals("1:00", formatPreviewClock(60_000L))
        assertEquals("1:05", formatPreviewClock(64_100L))
        assertEquals("0:00", formatPreviewClock(-5L))
    }

    // --- L1: serial defaults that arrive after the page opened ---

    @Test
    fun `a show found to be serial later reads oldest first, unless the order was changed`() {
        val untouched = PodcastSearchFilterOrderBarState(isSerial = false)
        untouched.applySerialDefault()
        assertTrue(untouched.isSerial)
        assertEquals(PodcastEpisodesOrder.ASCENDING, untouched.order.value)

        val changed = PodcastSearchFilterOrderBarState(isSerial = false)
        changed.orderBy.value = PodcastEpisodesOrderBy.entries.first { it != PodcastEpisodesOrderBy.DATE }
        changed.applySerialDefault()
        assertEquals(PodcastEpisodesOrder.DESCENDING, changed.order.value)
    }

    @Test
    fun `season grouping turns on by default, but not over the user's own choice`() {
        val state = PodcastSearchFilterOrderBarState(isSerial = true)
        state.applySeasonGroupingDefault()
        assertTrue(state.groupBySeason.value)

        val userTurnedItOff = PodcastSearchFilterOrderBarState(isSerial = true)
        userTurnedItOff.setGroupBySeason(false)
        userTurnedItOff.applySeasonGroupingDefault()
        assertFalse(userTurnedItOff.groupBySeason.value)
    }
}
