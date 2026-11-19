package app.podiumpodcasts.podium.api.chapters

import app.podiumpodcasts.podium.api.db.model.EpisodeFeedExtrasState
import app.podiumpodcasts.podium.api.rss.RssEpisodeExtras
import app.podiumpodcasts.podium.api.rss.TranscriptRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterFetchPolicyTest {
    private val now = 1_000_000_000L
    private val url = "https://show.example/ch.json"

    private fun shouldFetch(
        isNew: Boolean = false,
        stored: String = url,
        feed: String = url,
        failures: Int = 0,
        retryAfter: Long = 0L,
        hasPublisher: Boolean = false
    ) = ChapterFetchPolicy.shouldFetch(isNew, stored, feed, failures, retryAfter, hasPublisher, now)

    @Test
    fun `new episodes are fetched, old unchanged ones are not`() {
        assertTrue(shouldFetch(isNew = true))
        assertFalse(shouldFetch(isNew = false))
        assertFalse(shouldFetch(isNew = true, feed = ""))
    }

    @Test
    fun `a changed URL is fetched even with chapters stored`() {
        assertTrue(shouldFetch(stored = "https://show.example/old.json", hasPublisher = true))
        // Only the query differs (rotating signed URLs): not a change.
        assertFalse(shouldFetch(stored = "$url?token=a", feed = "$url?token=b"))
        // Never stored before (feed answered 304 until now): left to playback.
        assertFalse(shouldFetch(stored = ""))
    }

    @Test
    fun `episodes with publisher chapters are skipped`() {
        assertFalse(shouldFetch(isNew = true, hasPublisher = true))
        assertFalse(shouldFetch(failures = 1, retryAfter = 0L, hasPublisher = true))
    }

    @Test
    fun `failures retry after their backoff and stop after the last attempt`() {
        assertTrue(shouldFetch(failures = 1, retryAfter = now - 1))
        assertFalse(shouldFetch(failures = 1, retryAfter = now + 1))
        assertTrue(shouldFetch(failures = ChapterFetchPolicy.MAX_ATTEMPTS - 1, retryAfter = now))
        assertFalse(shouldFetch(failures = ChapterFetchPolicy.MAX_ATTEMPTS, retryAfter = 0L))
    }

    @Test
    fun `backoff grows 4x from an hour and caps at a week`() {
        val hour = 60L * 60L * 1000L
        assertEquals(0L, ChapterFetchPolicy.backoffMs(0))
        assertEquals(hour, ChapterFetchPolicy.backoffMs(1))
        assertEquals(4 * hour, ChapterFetchPolicy.backoffMs(2))
        assertEquals(16 * hour, ChapterFetchPolicy.backoffMs(3))
        assertEquals(64 * hour, ChapterFetchPolicy.backoffMs(4))
        assertEquals(7 * 24 * hour, ChapterFetchPolicy.backoffMs(5))
        assertEquals(7 * 24 * hour, ChapterFetchPolicy.backoffMs(30))
        assertEquals(now + hour, ChapterFetchPolicy.nextRetryAt(1, now))
    }

    @Test
    fun `only changed extras are written`() {
        val stored = EpisodeFeedExtrasState(
            id = "e",
            transcriptUrl = "https://x/t.json",
            transcriptType = "application/json",
            transcriptAlternatesJson = "",
            chaptersUrl = url,
            chaptersType = "application/json+chapters",
            chaptersFetchFailures = 0,
            chaptersRetryAfter = 0L
        )
        val same = RssEpisodeExtras(
            transcriptUrl = "https://x/t.json",
            transcriptType = "application/json",
            chaptersUrl = url,
            chaptersType = "application/json+chapters"
        )
        assertFalse(FeedExtrasDiff.needsUpdate(stored, same))
        assertTrue(
            FeedExtrasDiff.needsUpdate(
                stored,
                same.copy(transcriptAlternates = listOf(TranscriptRef("https://x/t.vtt", "text/vtt")))
            )
        )
        assertTrue(FeedExtrasDiff.needsUpdate(stored, same.copy(chaptersUrl = "https://x/new.json")))
        // Blank values never overwrite what's stored, so they're not a change either.
        assertFalse(FeedExtrasDiff.needsUpdate(stored, RssEpisodeExtras()))
    }
}
