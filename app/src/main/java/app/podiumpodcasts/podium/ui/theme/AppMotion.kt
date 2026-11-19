@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.podiumpodcasts.podium.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment

/**
 * The app's show/hide and content-swap transitions on the M3E motion scheme, so every
 * `AnimatedVisibility` / `AnimatedContent` moves the same way.
 *
 * - Opacity and colour use the **effects** springs (no overshoot).
 * - Position and size use the **spatial** springs (the expressive scheme overshoots a little).
 *   Sizes are safe with that overshoot because Compose clamps animated sizes at zero; an
 *   animated `Dp` used as padding is not (see `floatingMediaPlayerInset`).
 * - Content swaps scale with an effects spring, not a spatial one: several of them swap a whole
 *   screen (Restore, OPML import, Discover, search results), and a bouncing full screen looks wrong
 *   for the same reason the tab fade-through keeps its tweens.
 */
object AppMotion {
    @Composable
    fun fadeInEffects(): EnterTransition =
        fadeIn(animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec())

    @Composable
    fun fadeOutEffects(): ExitTransition =
        fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec())

    /** Grows in vertically (a section or row appearing in a column). */
    @Composable
    fun expandVerticallyIn(): EnterTransition =
        fadeInEffects() + expandVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec())

    @Composable
    fun shrinkVerticallyOut(): ExitTransition =
        shrinkVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeOutEffects()

    /** Grows from [alignment] (an icon button appearing in place). */
    @Composable
    fun expandFrom(alignment: Alignment): EnterTransition =
        fadeInEffects() + expandIn(
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            expandFrom = alignment
        )

    @Composable
    fun shrinkTowards(alignment: Alignment): ExitTransition =
        fadeOutEffects() + shrinkOut(
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            shrinkTowards = alignment
        )

    /** Pops in (a small icon, chip or FAB). */
    @Composable
    fun popIn(): EnterTransition =
        fadeInEffects() + scaleIn(animationSpec = MaterialTheme.motionScheme.fastSpatialSpec())

    @Composable
    fun popOut(): ExitTransition =
        fadeOutEffects() + scaleOut(animationSpec = MaterialTheme.motionScheme.fastSpatialSpec())

    /** Slides up from the bottom edge (a bottom bar). */
    @Composable
    fun slideUpIn(): EnterTransition =
        slideInVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) { it } + fadeInEffects()

    @Composable
    fun slideDownOut(): ExitTransition =
        slideOutVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) { it } + fadeOutEffects()

    /**
     * `AnimatedContent`'s own default (fade out, then fade and scale in from 92 %), on the motion
     * scheme's effects springs instead of fixed tweens.
     */
    @Composable
    fun <S> contentSwap(): AnimatedContentTransitionScope<S>.() -> ContentTransform {
        val enterSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val exitSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        return {
            (fadeIn(animationSpec = enterSpec) + scaleIn(initialScale = 0.92f, animationSpec = enterSpec))
                .togetherWith(fadeOut(animationSpec = exitSpec))
        }
    }
}
