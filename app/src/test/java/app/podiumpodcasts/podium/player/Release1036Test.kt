package app.podiumpodcasts.podium.player

import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.ui.unit.dp
import app.hushcast.automute.dsp.AdPolicyMode
import app.hushcast.automute.dsp.AdWindow
import app.podiumpodcasts.podium.ui.component.common.SingleBounceFlingBehavior
import app.podiumpodcasts.podium.ui.component.media.FloatingMediaPlayerHeight
import app.podiumpodcasts.podium.ui.component.media.floatingMediaPlayerInset
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.activeAdAt
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.sponsorRowAds
import app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.symmetricSideWidth
import app.podiumpodcasts.podium.ui.route.settings.pane.themeModeLabelChoice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the 1.0.36 fixes. */
class Release1036Test {

    // --- The 1.0.35 crash: "Padding must be non-negative" when the full player opened ---

    @Test
    fun `mini player inset never goes negative while the spring overshoots`() {
        // The expressive spatial spring dips below 0 (about -0.015) when the mini player hides.
        assertEquals(0.dp, floatingMediaPlayerInset(-0.015f))
        assertEquals(0.dp, floatingMediaPlayerInset(-1f))
        assertEquals(0.dp, floatingMediaPlayerInset(0f))
    }

    @Test
    fun `mini player inset is capped at full height on the way up`() {
        val full = FloatingMediaPlayerHeight + 8.dp
        assertEquals(full, floatingMediaPlayerInset(1f))
        assertEquals(full, floatingMediaPlayerInset(1.02f))
        assertEquals(full / 2, floatingMediaPlayerInset(0.5f))
    }

    // --- Segment row ---

    private val ad = AdWindow(startMs = 60_000L, endMs = 120_000L, confidence = 0.9f)

    @Test
    fun `an ad's end is not inside it, so Skip disappears as soon as playback reaches the end`() {
        assertNull(activeAdAt(listOf(ad), 59_999L))
        assertSame(ad, activeAdAt(listOf(ad), 60_000L))
        assertSame(ad, activeAdAt(listOf(ad), 119_999L))
        assertNull(activeAdAt(listOf(ad), 120_000L))
    }

    @Test
    fun `with sponsor skipping off the row offers no Skip`() {
        assertTrue(sponsorRowAds(listOf(ad), AdPolicyMode.OFF).isEmpty())
        assertEquals(listOf(ad), sponsorRowAds(listOf(ad), AdPolicyMode.MARK_ONLY))
        assertEquals(listOf(ad), sponsorRowAds(listOf(ad), AdPolicyMode.AUTO_SKIP))
    }

    @Test
    fun `both sides take the wider slot so the centre stays centred`() {
        // Skip on the right only (no chapters): the left side reserves the same width.
        assertEquals(240, symmetricSideWidth(startWidth = 0, endWidth = 240, rowWidth = 1000))
        assertEquals(240, symmetricSideWidth(startWidth = 240, endWidth = 120, rowWidth = 1000))
        // Never more than half the row.
        assertEquals(300, symmetricSideWidth(startWidth = 900, endWidth = 0, rowWidth = 600))
    }

    // --- Overscroll: one stretch at the first/last page, not two ---

    private class RecordingFling(private val leftover: Float) : TargetedFlingBehavior {
        var calls = 0
        override suspend fun ScrollScope.performFling(
            initialVelocity: Float,
            onRemainingDistanceUpdated: (Float) -> Unit
        ): Float {
            calls++
            return leftover
        }
    }

    private object NoOpScrollScope : ScrollScope {
        override fun scrollBy(pixels: Float): Float = pixels
    }

    @Test
    fun `the pager's snap runs, but its leftover velocity isn't handed to a second stretch`() = runBlocking {
        val base = RecordingFling(leftover = 1_500f)
        val fling = SingleBounceFlingBehavior(base)
        val left = with(fling) { NoOpScrollScope.performFling(3_000f) {} }
        assertEquals(1, base.calls)
        assertEquals(0f, left)
    }

    // --- Appearance: Automatic / Light / Dark on connected toggle buttons ---

    @Test
    fun `full labels with icons when they fit, then short labels, then no icons`() {
        // 20 dp padding + 26 dp icon room around the label.
        assertEquals(false to true, themeModeLabelChoice(120.dp, fullLabelWidth = 74.dp, shortLabelWidth = 30.dp))
        assertEquals(true to true, themeModeLabelChoice(100.dp, fullLabelWidth = 74.dp, shortLabelWidth = 30.dp))
        assertEquals(true to false, themeModeLabelChoice(70.dp, fullLabelWidth = 74.dp, shortLabelWidth = 30.dp))
    }
}
