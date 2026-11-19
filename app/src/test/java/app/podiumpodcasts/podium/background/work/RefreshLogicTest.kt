package app.podiumpodcasts.podium.background.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshLogicTest {

    @Test
    fun `re-run cap is 3, or 10 while charging on unmetered wifi`() {
        assertEquals(3, RerunPlanner.capFor(isCharging = false, isUnmeteredWifi = false))
        assertEquals(3, RerunPlanner.capFor(isCharging = true, isUnmeteredWifi = false))
        assertEquals(3, RerunPlanner.capFor(isCharging = false, isUnmeteredWifi = true))
        assertEquals(10, RerunPlanner.capFor(isCharging = true, isUnmeteredWifi = true))
    }

    @Test
    fun `pick skips pending ones and stops asking once the cap is reached`() {
        val asked = mutableListOf<Int>()
        val picked = RerunPlanner.pick((1..20).toList(), cap = 3) { n ->
            asked += n
            n % 2 == 0 // odd ones are "already queued"
        }
        assertEquals(listOf(2, 4, 6), picked)
        assertEquals((1..6).toList(), asked)
        assertEquals(emptyList<Int>(), RerunPlanner.pick((1..5).toList(), cap = 0) { true })
    }

    @Test
    fun `refresh fails only when every feed failed`() {
        assertNull(RefreshSummary(totalFeeds = 0).errorMessage())
        assertNull(RefreshSummary(totalFeeds = 3, failedFeeds = 2, firstError = Exception("x")).errorMessage())
        val offline = RefreshSummary(totalFeeds = 2, failedFeeds = 2, firstError = java.net.UnknownHostException("h"))
        assertEquals("Couldn't reach the feeds. Check your connection.", offline.errorMessage())
    }

    @Test
    fun `short reasons stay short and readable`() {
        assertEquals(
            "Couldn't reach the feeds. Check your connection.",
            RefreshSummary.shortReason(RuntimeException("wrap", java.net.SocketTimeoutException("t")))
        )
        assertEquals(
            "The feeds answered with an error (404 Not Found)",
            RefreshSummary.shortReason(Exception("UNHANDLED STATUS CODE 404 Not Found"))
        )
        assertEquals("Couldn't update your podcasts", RefreshSummary.shortReason(null))
        val long = RefreshSummary.shortReason(Exception("x".repeat(500)))
        assertTrue(long.length < 120)
    }
}
