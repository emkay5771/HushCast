package app.podiumpodcasts.podium.ui.component.common

import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.TabIndicatorScope
import androidx.compose.material3.TabPosition
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.lerp
import kotlin.math.abs

/** A tab's horizontal span, as [TabPosition] reports it (its constructor isn't public). */
internal data class TabSpan(val left: Dp, val width: Dp, val contentWidth: Dp)

internal fun TabPosition.toTabSpan() = TabSpan(left, width, contentWidth)

/**
 * Where a tab row's indicator sits while its pager moves: between the current page's tab and the
 * neighbour it's moving toward, by how far the page has moved.  Returns (left, width).
 * [matchContentSize] follows the tab's text (Discover); otherwise the whole tab (the player).
 */
internal fun pagerIndicatorSpan(
    tabPositions: List<TabSpan>,
    currentPage: Int,
    offsetFraction: Float,
    matchContentSize: Boolean
): Pair<Dp, Dp> {
    val page = currentPage.coerceIn(0, tabPositions.lastIndex)
    val target = (if (offsetFraction > 0f) page + 1 else page - 1).coerceIn(0, tabPositions.lastIndex)
    val fraction = abs(offsetFraction).coerceIn(0f, 1f)
    val cur = tabPositions[page]
    val next = tabPositions[target]
    fun TabSpan.spanLeft() = if (matchContentSize) left + (width - contentWidth) / 2 else left
    fun TabSpan.spanWidth() = if (matchContentSize) contentWidth else width
    return lerp(cur.spanLeft(), next.spanLeft(), fraction) to lerp(cur.spanWidth(), next.spanWidth(), fraction)
}

/**
 * An indicator modifier that follows [pagerState] while it's swiped or animating, instead of
 * moving once the pager has settled (the player's tabs until 1.0.36: the page slid in and stopped,
 * then the indicator slid across).  The position is read during layout, so a swipe doesn't
 * recompose the row.
 *
 * Right-to-left falls back to the standard offset on the current page, which still switches
 * halfway through the swipe rather than after the settle.
 */
@Composable
fun TabIndicatorScope.pagerFollowingIndicator(
    pagerState: PagerState,
    matchContentSize: Boolean
): Modifier {
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        return Modifier.tabIndicatorOffset(pagerState.currentPage, matchContentSize = matchContentSize)
    }
    return Modifier.tabIndicatorLayout { measurable, constraints, tabPositions ->
        if (tabPositions.isEmpty()) return@tabIndicatorLayout layout(0, 0) { }
        val (left, width) = pagerIndicatorSpan(
            tabPositions = tabPositions.map { it.toTabSpan() },
            currentPage = pagerState.currentPage,
            offsetFraction = pagerState.currentPageOffsetFraction,
            matchContentSize = matchContentSize
        )
        val widthPx = width.roundToPx().coerceAtLeast(0)
        val placeable = measurable.measure(constraints.copy(minWidth = widthPx, maxWidth = widthPx))
        layout(placeable.width, placeable.height) {
            placeable.place(left.roundToPx(), 0)
        }
    }
}
