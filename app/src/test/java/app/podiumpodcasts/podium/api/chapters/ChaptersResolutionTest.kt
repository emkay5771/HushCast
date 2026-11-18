package app.podiumpodcasts.podium.api.chapters
import app.hushcast.automute.transcript.OffsetMap
import app.hushcast.automute.transcript.OffsetStretch
import app.podiumpodcasts.podium.api.db.model.EpisodeChapterModel
import app.podiumpodcasts.podium.api.rss.FetchPodcastClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaptersResolutionTest {

    @Test
    fun `offset map shifts chapters for matching fileSha256 and preserves publishedStartMs`() {
        val chapters = listOf(
            EpisodeChapterModel(
                episodeId = "ep1",
                title = "Intro",
                startMs = 0L,
                endMs = 60_000L,
                source = EpisodeChapterModel.SOURCE_JSON
            ),
            EpisodeChapterModel(
                episodeId = "ep1",
                title = "Main Topic",
                startMs = 60_000L,
                endMs = 300_000L,
                source = EpisodeChapterModel.SOURCE_JSON
            )
        )
        val mapForCurrentFile = OffsetMap(
            listOf(
                OffsetStretch(srcStartMs = 0L, srcEndMs = 300_000L, shiftMs = 30_000L)
            )
        )

        val resolved = ChapterParser.resolveForPlayback(
            chapters = chapters,
            offsetMap = mapForCurrentFile,
            durationMs = 330_000L
        )
        assertEquals(2, resolved.size)
        assertEquals(30_000L, resolved[0].startMs)
        assertEquals(0L, resolved[0].publishedStartMs)
        assertEquals(90_000L, resolved[1].startMs)
        assertEquals(60_000L, resolved[1].publishedStartMs)
    }

    @Test
    fun `inline podlove chapters are parsed from RSS item before first play`() {
        val rss = """
            <rss version="2.0" xmlns:psc="http://podlove.org/simple-chapters">
              <channel>
                <language>en</language>
                <item>
                  <guid>ep-123</guid>
                  <title>Test Episode</title>
                  <enclosure url="https://example.com/ep.mp3" type="audio/mpeg"/>
                  <psc:chapters version="1.2">
                    <psc:chapter start="00:00:00.000" title="Opening" />
                    <psc:chapter start="00:02:30.000" title="Deep Dive" href="https://example.com/dive" />
                  </psc:chapters>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val extras = FetchPodcastClient.parsePodcastExtras(rss)
        val epExtras = extras.byItemKey["ep-123"]
        assertTrue(epExtras != null)
        assertEquals(2, epExtras!!.inlineChapters.size)
        assertEquals(0L, epExtras.inlineChapters[0].startTimeMs)
        assertEquals("Opening", epExtras.inlineChapters[0].title)
        assertEquals(150_000L, epExtras.inlineChapters[1].startTimeMs)
        assertEquals("Deep Dive", epExtras.inlineChapters[1].title)
        assertEquals("https://example.com/dive", epExtras.inlineChapters[1].url)
    }
}
