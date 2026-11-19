package app.podiumpodcasts.podium.ui.dialog.bottomsheet.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import app.hushcast.automute.dsp.AdPolicyMode
import app.hushcast.automute.dsp.AdWindow

/**
 * The ad the playhead is inside, for the player's segment row.  The end is exclusive: right after
 * Skip (or an auto-skip) the position is exactly `endMs`, and the row must not keep showing
 * "Sponsor · 0:00 left" with a Skip button until the next position tick.
 */
internal fun activeAdAt(adWindows: List<AdWindow>, positionMs: Long): AdWindow? =
    adWindows.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }

/**
 * The ads the segment row offers to skip.  With sponsor skipping off for this podcast (its own
 * setting, else the global one) the row stays a chapter row; the seek bar still marks the ads.
 */
internal fun sponsorRowAds(adWindows: List<AdWindow>, policy: AdPolicyMode): List<AdWindow> =
    if (policy == AdPolicyMode.OFF) emptyList() else adWindows

/**
 * A row with a start slot, a centre slot and an end slot where the centre is always centred on the
 * row: both sides take the width of the wider one.  The segment row needs this because it often
 * has a button on only one side (Skip on the right, or Undo on the left, on episodes without
 * chapters), which pushed the centred text off the player's centre line.  Mirrors in RTL.
 */
@Composable
internal fun SymmetricSlotsRow(
    modifier: Modifier = Modifier,
    start: @Composable () -> Unit,
    center: @Composable () -> Unit,
    end: @Composable () -> Unit
) {
    Layout(
        contents = listOf(start, center, end),
        modifier = modifier
    ) { (startMeasurables, centerMeasurables, endMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val startPlaceables = startMeasurables.map { it.measure(loose) }
        val endPlaceables = endMeasurables.map { it.measure(loose) }
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            constraints.minWidth
        }
        val side = symmetricSideWidth(
            startWidth = startPlaceables.maxOfOrNull { it.width } ?: 0,
            endWidth = endPlaceables.maxOfOrNull { it.width } ?: 0,
            rowWidth = width
        )
        val centerMax = (width - 2 * side).coerceAtLeast(0)
        val centerPlaceables = centerMeasurables.map {
            it.measure(loose.copy(maxWidth = centerMax))
        }
        val height = maxOf(
            constraints.minHeight,
            startPlaceables.maxOfOrNull { it.height } ?: 0,
            endPlaceables.maxOfOrNull { it.height } ?: 0,
            centerPlaceables.maxOfOrNull { it.height } ?: 0
        )
        layout(width, height) {
            startPlaceables.forEach { it.placeRelative(0, (height - it.height) / 2) }
            endPlaceables.forEach { it.placeRelative(width - it.width, (height - it.height) / 2) }
            centerPlaceables.forEach {
                it.placeRelative((width - it.width) / 2, (height - it.height) / 2)
            }
        }
    }
}

/**
 * Each side's share: the wider slot, so the centre never overlaps a button (the centre text
 * ellipsizes instead), and never more than half the row.
 */
internal fun symmetricSideWidth(startWidth: Int, endWidth: Int, rowWidth: Int): Int =
    maxOf(startWidth, endWidth).coerceIn(0, (rowWidth / 2).coerceAtLeast(0))
