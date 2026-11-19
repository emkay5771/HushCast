package app.podiumpodcasts.podium.ui.component.common

import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * The pager's standard snapping fling, minus the second bounce.
 *
 * Android's stretch overscroll gives one stretch at the first or last page.  In Compose, the
 * stretch takes whatever fling velocity is left *after* the whole fling has finished, and the
 * pager's snap animation is part of that fling: when the snap reaches the first or last page it
 * returns the velocity it couldn't use, so the page slides, stops, and then stretches again a
 * moment later.  That "double bounce with a pause" is why 1.0.16 turned overscroll off app-wide.
 *
 * This keeps the stretch while dragging past the edge (and its normal release) but reports the
 * leftover velocity as used, so nothing is left for a second stretch after the snap.
 */
@Composable
fun rememberSingleBouncePagerFlingBehavior(state: PagerState): TargetedFlingBehavior {
    val base = PagerDefaults.flingBehavior(state = state)
    return remember(base) { SingleBounceFlingBehavior(base) }
}

internal class SingleBounceFlingBehavior(
    private val base: TargetedFlingBehavior
) : TargetedFlingBehavior {
    override suspend fun ScrollScope.performFling(
        initialVelocity: Float,
        onRemainingDistanceUpdated: (Float) -> Unit
    ): Float {
        with(base) { performFling(initialVelocity, onRemainingDistanceUpdated) }
        return 0f
    }
}
