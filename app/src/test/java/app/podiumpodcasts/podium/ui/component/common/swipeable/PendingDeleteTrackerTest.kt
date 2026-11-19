package app.podiumpodcasts.podium.ui.component.common.swipeable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingDeleteTrackerTest {

    private val published = ArrayList<Set<String>>()
    private val tracker = PendingDeleteTracker { published += it }

    @Test
    fun `marking hides the row without deleting`() {
        assertTrue(tracker.mark("a"))
        assertTrue(tracker.isPending("a"))
        assertTrue(tracker.isHidden("a"))
        assertEquals(setOf("a"), published.last())
        // Marking twice is a no-op.
        assertFalse(tracker.mark("a"))
    }

    @Test
    fun `undo restores the row and nothing is deleted`() {
        tracker.mark("a")
        assertTrue(tracker.undo("a"))
        assertFalse(tracker.isHidden("a"))
        assertEquals(emptySet<String>(), published.last())
        // The snackbar then goes away: there is nothing left to commit.
        assertFalse(tracker.commit("a", isCurrentEpisode = false))
    }

    @Test
    fun `timeout commits the delete and the row stays hidden until it finishes`() {
        tracker.mark("a")
        assertTrue(tracker.commit("a", isCurrentEpisode = false))
        assertFalse(tracker.isPending("a"))
        assertTrue(tracker.isHidden("a"))
        // Too late to undo once the delete has started.
        assertFalse(tracker.undo("a"))
        tracker.finish("a")
        assertFalse(tracker.isHidden("a"))
        assertEquals(emptySet<String>(), published.last())
    }

    @Test
    fun `the playing episode is never deleted on commit`() {
        tracker.mark("a")
        assertFalse(tracker.commit("a", isCurrentEpisode = true))
        assertFalse(tracker.isHidden("a"))
    }

    @Test
    fun `commit only once`() {
        tracker.mark("a")
        assertTrue(tracker.commit("a", isCurrentEpisode = false))
        assertFalse(tracker.commit("a", isCurrentEpisode = false))
    }

    @Test
    fun `episodes are tracked separately`() {
        tracker.mark("a")
        tracker.mark("b")
        tracker.undo("a")
        assertFalse(tracker.isHidden("a"))
        assertTrue(tracker.isHidden("b"))
        assertEquals(setOf("b"), tracker.hidden)
    }

    @Test
    fun `swipe decisions`() {
        assertEquals(
            DownloadSwipeDecision.MARK_PENDING,
            decideDownloadSwipe(isDownloaded = true, isPending = false, isCurrentEpisode = false)
        )
        assertEquals(
            DownloadSwipeDecision.BLOCKED_PLAYING,
            decideDownloadSwipe(isDownloaded = true, isPending = false, isCurrentEpisode = true)
        )
        assertEquals(
            DownloadSwipeDecision.RESTORE_PENDING,
            decideDownloadSwipe(isDownloaded = true, isPending = true, isCurrentEpisode = false)
        )
        assertEquals(
            DownloadSwipeDecision.DOWNLOAD,
            decideDownloadSwipe(isDownloaded = false, isPending = false, isCurrentEpisode = false)
        )
        // Downloading the playing (streamed) episode is fine.
        assertEquals(
            DownloadSwipeDecision.DOWNLOAD,
            decideDownloadSwipe(isDownloaded = false, isPending = false, isCurrentEpisode = true)
        )
    }
}
