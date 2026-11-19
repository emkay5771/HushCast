package app.podiumpodcasts.podium.ui.component.common

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.unit.Velocity

/**
 * Standard stretch overscroll for everything that scrolls inside a Material 3 `ModalBottomSheet`,
 * without the "stretch, pause, bounce" the sheet causes.
 *
 * Wrap the sheet's content in this.  See [ReleaseOnLiftOverscrollEffect] for why.
 */
@Composable
fun ColumnScope.SheetOverscroll(content: @Composable ColumnScope.() -> Unit) {
    val base = LocalOverscrollFactory.current
    val factory = remember(base) { base?.let(::ReleaseOnLiftOverscrollFactory) }
    CompositionLocalProvider(LocalOverscrollFactory provides factory) {
        this@SheetOverscroll.content()
    }
}

private data class ReleaseOnLiftOverscrollFactory(
    private val inner: OverscrollFactory
) : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect =
        ReleaseOnLiftOverscrollEffect(inner.createOverscrollEffect())
}

/**
 * The platform stretch, but it lets go as soon as the finger lifts, and the fling that follows
 * can't hold it or start a new one.
 *
 * Compose only lets go of a stretch when the whole fling has finished, including the part handed
 * to the scrollable's parents, and then stretches by whatever velocity it is told is left.  Inside
 * a Material 3 `ModalBottomSheet` (material3 1.5.0-alpha14) both of those go wrong:
 *
 * - The sheet takes part in every fling inside it.  Its nested-scroll `onPostFling` runs a 300 ms
 *   settle tween, even when the sheet is already where it belongs, so a stretch you pull and let
 *   go of stays frozen for 300 ms before it springs back.
 * - It reports the child's own sideways velocity as used a second time (`Velocity(consumed.x, …)`
 *   in `SheetDefaults`), so after those 300 ms Compose thinks the pager has the whole swipe's
 *   velocity left over, the other way, and stretches the page that just landed toward the opposite
 *   edge.  That is the player's "double bounce with a pause" when switching tabs, on any tab.
 *
 * Here the stretch is released (and relaxed by a fling moving away from it, as standard) before
 * the fling runs, and the fling's own scrolling skips the stretch.  Dragging past the edge and the
 * release look exactly as standard.  The one thing given up is the small extra stretch when a fling
 * hits the end of a list; inside a sheet that could only ever arrive 300 ms late.
 */
internal class ReleaseOnLiftOverscrollEffect(
    private val inner: OverscrollEffect
) : OverscrollEffect {
    private var flingRunning = false

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset
    ): Offset =
        if (flingRunning) performScroll(delta) else inner.applyToScroll(delta, source, performScroll)

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity
    ) {
        var toFling = Velocity.Zero
        inner.applyToFling(velocity) { remaining ->
            toFling = remaining
            remaining // nothing left over: no fling stretch, and the release starts now
        }
        flingRunning = true
        try {
            performFling(toFling)
        } finally {
            flingRunning = false
        }
    }

    override val isInProgress: Boolean
        get() = inner.isInProgress

    override val node: DelegatableNode
        get() = inner.node
}
