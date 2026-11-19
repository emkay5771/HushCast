package app.podiumpodcasts.podium.ui.component.settings

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * A [androidx.compose.material3.SegmentedButton] label that stays readable on narrow screens and
 * with large fonts: it shrinks a little (down to [minFontSize]) before it would be cut off, and
 * only then ellipsizes.
 *
 * M3 measures the label with the button's full content width and adds room for the icon (18 dp
 * plus 8 dp) on top, then centers the result.  Without an icon that room is split evenly around
 * the text, so the full width is usable.  Pass [hasIcon] when the button shows an icon (its own,
 * or M3's default check mark on the selected segment): the label is then kept [IconReserve]
 * narrower, so the icon and label both stay at least 4 dp inside the button.
 */
@Composable
fun SegmentedButtonLabel(
    text: String,
    modifier: Modifier = Modifier,
    hasIcon: Boolean = false,
    minFontSize: TextUnit = 11.sp
) {
    val style = LocalTextStyle.current
    val maxFontSize = if (style.fontSize.isSpecified) style.fontSize else 14.sp
    Text(
        text = text,
        modifier = if (hasIcon) modifier.reserveWidth(IconReserve) else modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        autoSize = TextAutoSize.StepBased(
            minFontSize = minOf(minFontSize, maxFontSize),
            maxFontSize = maxFontSize,
            stepSize = 0.5.sp
        )
    )
}

/** See [SegmentedButtonLabel]: icon (18) + spacing (8) − 2 × the 12 dp padding + 2 × 4 dp kept. */
private val IconReserve: Dp = 10.dp

private fun minOf(a: TextUnit, b: TextUnit): TextUnit = if (a.value <= b.value) a else b

/** Measures the content [amount] narrower than offered. */
private fun Modifier.reserveWidth(amount: Dp): Modifier = layout { measurable, constraints ->
    val reserve = amount.roundToPx()
    val maxWidth = if (constraints.hasBoundedWidth) {
        (constraints.maxWidth - reserve).coerceAtLeast(0)
    } else {
        constraints.maxWidth
    }
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth.coerceAtMost(maxWidth), maxWidth = maxWidth)
    )
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}
