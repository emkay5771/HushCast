package app.podiumpodcasts.podium.ui.component.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastRefreshTest {

    @Test
    fun `spinner shows only while running`() {
        assertTrue(refreshIndicatorVisible(listOf(RefreshJob("1", RefreshJobState.RUNNING)), awaitingStart = false))
        // Waiting for a network: no spinner.
        assertFalse(refreshIndicatorVisible(listOf(RefreshJob("1", RefreshJobState.ENQUEUED)), awaitingStart = false))
        assertFalse(refreshIndicatorVisible(emptyList(), awaitingStart = false))
        assertFalse(refreshIndicatorVisible(listOf(RefreshJob("1", RefreshJobState.SUCCEEDED)), awaitingStart = false))
    }

    @Test
    fun `short grace right after the pull, until the job finishes`() {
        assertTrue(refreshIndicatorVisible(emptyList(), awaitingStart = true))
        assertTrue(refreshIndicatorVisible(listOf(RefreshJob("1", RefreshJobState.ENQUEUED)), awaitingStart = true))
        assertFalse(refreshIndicatorVisible(listOf(RefreshJob("1", RefreshJobState.FAILED)), awaitingStart = true))
    }

    @Test
    fun `reports a failure it watched happen, with the reason`() {
        val tracker = RefreshFailureTracker()
        assertEquals(emptyList<String?>(), tracker.onUpdate(listOf(RefreshJob("1", RefreshJobState.ENQUEUED))))
        assertEquals(emptyList<String?>(), tracker.onUpdate(listOf(RefreshJob("1", RefreshJobState.RUNNING))))
        assertEquals(
            listOf("Refresh failed: Timeout"),
            tracker.onUpdate(listOf(RefreshJob("1", RefreshJobState.FAILED, error = "Timeout")))
        )
        // Only once.
        assertEquals(
            emptyList<String?>(),
            tracker.onUpdate(listOf(RefreshJob("1", RefreshJobState.FAILED, error = "Timeout")))
        )
    }

    @Test
    fun `a failure without a reason is still reported`() {
        val tracker = RefreshFailureTracker()
        tracker.onUpdate(listOf(RefreshJob("1", RefreshJobState.RUNNING)))
        assertEquals(listOf("Refresh failed"), tracker.onUpdate(listOf(RefreshJob("1", RefreshJobState.FAILED, error = " "))))
    }

    @Test
    fun `an old failure found on opening the screen is not reported`() {
        val tracker = RefreshFailureTracker()
        assertEquals(emptyList<String?>(), tracker.onUpdate(listOf(RefreshJob("old", RefreshJobState.FAILED, error = "x"))))
    }

    @Test
    fun `a refresh where some feeds failed is reported once`() {
        val tracker = RefreshFailureTracker()
        tracker.onUpdate(listOf(RefreshJob("1", RefreshJobState.RUNNING)))
        val done = RefreshJob("1", RefreshJobState.SUCCEEDED, failedFeeds = 2, totalFeeds = 30)
        assertEquals(listOf("Couldn't refresh 2 of 30 podcasts"), tracker.onUpdate(listOf(done)))
        assertEquals(emptyList<String>(), tracker.onUpdate(listOf(done)))
    }

    @Test
    fun `spinner stops once only chapter downloads remain`() {
        assertFalse(refreshIndicatorVisible(listOf(RefreshJob("1", RefreshJobState.RUNNING, finishing = true)), awaitingStart = false))
        assertFalse(refreshIndicatorVisible(listOf(RefreshJob("1", RefreshJobState.RUNNING, finishing = true)), awaitingStart = true))
    }

    @Test
    fun `success is not reported`() {
        val tracker = RefreshFailureTracker()
        tracker.onUpdate(listOf(RefreshJob("1", RefreshJobState.RUNNING)))
        assertEquals(emptyList<String?>(), tracker.onUpdate(listOf(RefreshJob("1", RefreshJobState.SUCCEEDED))))
    }

    @Test
    fun `messages`() {
        assertEquals("Refresh failed", refreshFailedMessage(null))
        assertEquals("Refresh failed", refreshFailedMessage(""))
        assertEquals("Refresh failed: No network", refreshFailedMessage("No network"))
        assertEquals("You're offline. Pull to refresh when you're back online.", REFRESH_OFFLINE_MESSAGE)
    }
}
