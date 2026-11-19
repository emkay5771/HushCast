package app.podiumpodcasts.podium.player

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.podiumpodcasts.podium.ui.component.common.ReleaseOnLiftOverscrollEffect
import app.podiumpodcasts.podium.ui.component.common.TabSpan
import app.podiumpodcasts.podium.ui.component.common.pagerIndicatorSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression tests for the 1.0.37 fixes: the player's "double bounce" when switching tabs. */
class Release1037Test {

    // --- Overscroll inside a bottom sheet ---

    /**
     * Stands in for the platform stretch: it hands the fling whatever a relaxing stretch didn't
     * use, then stretches by whatever the fling reports as left over, then releases.
     */
    private class FakeStretch(private val relaxBy: Velocity = Velocity.Zero) : OverscrollEffect {
        val events = mutableListOf<String>()
        var stretchedBy: Velocity? = null

        override fun applyToScroll(
            delta: Offset,
            source: NestedScrollSource,
            performScroll: (Offset) -> Offset
        ): Offset {
            events += "stretch saw scroll"
            return performScroll(delta)
        }

        override suspend fun applyToFling(
            velocity: Velocity,
            performFling: suspend (Velocity) -> Velocity
        ) {
            val remaining = velocity - relaxBy
            val used = performFling(remaining)
            stretchedBy = remaining - used
            events += "released"
        }

        override val isInProgress = false
        override val node: DelegatableNode = object : Modifier.Node() {}
    }

    @Test
    fun `the stretch lets go before the fling, and the sheet's wrong leftover can't stretch the page`() {
        val stretch = FakeStretch()
        val effect = ReleaseOnLiftOverscrollEffect(stretch)
        var flungWith: Velocity? = null
        runBlocking {
            effect.applyToFling(Velocity(-3_000f, 0f)) { velocity ->
                stretch.events += "fling"
                flungWith = velocity
                // The fling's own scrolling doesn't touch the stretch.
                effect.applyToScroll(Offset(-10f, 0f), NestedScrollSource.SideEffect) { it }
                // Material 3's sheet reports the pager's velocity as used a second time.
                velocity * 2f
            }
        }
        assertEquals(listOf("released", "fling"), stretch.events)
        assertEquals(Velocity.Zero, stretch.stretchedBy)
        assertEquals(Velocity(-3_000f, 0f), flungWith)

        // After the fling, a new drag reaches the stretch again.
        effect.applyToScroll(Offset(5f, 0f), NestedScrollSource.UserInput) { it }
        assertEquals("stretch saw scroll", stretch.events.last())
    }

    @Test
    fun `a fling away from a stretch still relaxes it first, and only the rest is flung`() {
        val stretch = FakeStretch(relaxBy = Velocity(0f, 800f))
        val effect = ReleaseOnLiftOverscrollEffect(stretch)
        var flungWith: Velocity? = null
        runBlocking {
            effect.applyToFling(Velocity(0f, 2_000f)) { velocity -> flungWith = velocity; velocity }
        }
        assertEquals(Velocity(0f, 1_200f), flungWith)
    }

    @Test
    fun `a fling cut short by a new touch hands scrolling back to the stretch`() {
        val stretch = FakeStretch()
        val effect = ReleaseOnLiftOverscrollEffect(stretch)
        runBlocking {
            try {
                effect.applyToFling(Velocity(1_000f, 0f)) { throw CancellationException("new touch") }
            } catch (_: CancellationException) {
            }
        }
        effect.applyToScroll(Offset(5f, 0f), NestedScrollSource.UserInput) { it }
        assertEquals("stretch saw scroll", stretch.events.last())
    }

    // --- Tab indicators follow the pager ---

    private val tabs = listOf(
        TabSpan(left = 0.dp, width = 100.dp, contentWidth = 60.dp),
        TabSpan(left = 100.dp, width = 100.dp, contentWidth = 40.dp),
        TabSpan(left = 200.dp, width = 100.dp, contentWidth = 80.dp)
    )

    @Test
    fun `player indicator covers the whole tab and moves with the page`() {
        assertEquals(0.dp to 100.dp, pagerIndicatorSpan(tabs, currentPage = 0, offsetFraction = 0f, matchContentSize = false))
        // Halfway from the first tab to the second.
        assertEquals(50.dp to 100.dp, pagerIndicatorSpan(tabs, currentPage = 0, offsetFraction = 0.5f, matchContentSize = false))
        // Swiping back from the third tab toward the second.
        assertEquals(175.dp to 100.dp, pagerIndicatorSpan(tabs, currentPage = 2, offsetFraction = -0.25f, matchContentSize = false))
    }

    @Test
    fun `discover indicator follows the tab text and stays on the last tab at the edge`() {
        // First tab's text: 60 dp wide, centred in 100 dp.
        assertEquals(20.dp to 60.dp, pagerIndicatorSpan(tabs, currentPage = 0, offsetFraction = 0f, matchContentSize = true))
        // Past the last tab there's no neighbour: it stays put.
        assertEquals(210.dp to 80.dp, pagerIndicatorSpan(tabs, currentPage = 2, offsetFraction = 0.3f, matchContentSize = true))
    }
}
