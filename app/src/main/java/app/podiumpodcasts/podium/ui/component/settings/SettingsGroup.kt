package app.podiumpodcasts.podium.ui.component.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Where a row sits inside a settings group.  M3E grouped lists round only the outer corners of
 * the first and last row, so every row needs to know its own position and the group size.
 * [settingsGroup] works both out itself, so rows that are shown or hidden can't break the shape.
 */
@Immutable
data class SettingsGroupPosition(
    val index: Int,
    val count: Int
) {
    val isFirst: Boolean get() = index == 0
    val isLast: Boolean get() = index == count - 1

    companion object {
        /** A row that is a group on its own. */
        val Single = SettingsGroupPosition(0, 1)
    }
}

object SettingsGroupDefaults {
    /** Space above a group that follows another group. */
    val GroupSpacing: Dp = 16.dp

    /** Corner radius for a group that holds a single row (matches [SettingsListItem]). */
    val SingleRowCorner: Dp = 16.dp
}

/** Collects the rows of one settings group; see [settingsGroup]. */
class SettingsGroupBuilder internal constructor() {
    internal class Row(
        val key: Any?,
        val contentType: Any?,
        val content: @Composable LazyItemScope.(SettingsGroupPosition) -> Unit
    )

    internal val rows = mutableListOf<Row>()

    /**
     * Adds one row.  [content] receives the row's position; pass it on as `index`/`count` to
     * [SettingsListItem] and friends, or to [SettingsGroupSurface] for a custom row.
     */
    fun row(
        key: Any? = null,
        contentType: Any? = null,
        content: @Composable LazyItemScope.(position: SettingsGroupPosition) -> Unit
    ) {
        rows += Row(key, contentType, content)
    }
}

/**
 * One settings section: an optional [SettingsHeader], then a grouped list whose rows number
 * themselves.  Wrap a row in `if (…)` inside [content] to show it conditionally; the remaining
 * rows still get the right first/middle/last shapes.
 *
 * @param key prefix for the lazy item keys.  Give one when the page animates items or has more
 *   than one group with keyed rows; row keys are scoped under it.
 * @param spaceAbove space emitted above the group (use 0.dp for the first group on a page).
 */
fun LazyListScope.settingsGroup(
    header: String? = null,
    description: String? = null,
    key: String? = null,
    spaceAbove: Dp = SettingsGroupDefaults.GroupSpacing,
    content: SettingsGroupBuilder.() -> Unit
) {
    val builder = SettingsGroupBuilder().apply(content)
    val rows = builder.rows
    if (rows.isEmpty() && header == null) return

    if (spaceAbove > 0.dp) {
        item(key = key?.let { "$it:space" }, contentType = "settings_group_space") {
            Spacer(Modifier.height(spaceAbove))
        }
    }

    if (header != null) {
        item(key = key?.let { "$it:header" }, contentType = "settings_group_header") {
            SettingsHeader(label = header, description = description)
        }
    }

    val count = rows.size
    rows.forEachIndexed { index, row ->
        val position = SettingsGroupPosition(index, count)
        val itemKey = when {
            key != null && row.key != null -> "$key:${row.key}"
            key != null -> "$key:#$index"
            else -> row.key
        }
        item(key = itemKey, contentType = row.contentType) {
            row.content(this, position)
        }
    }
}

/** Collects the rows of a [SettingsGroupColumn]. */
class SettingsColumnGroupBuilder internal constructor() {
    internal val rows = mutableListOf<@Composable (SettingsGroupPosition) -> Unit>()

    fun row(content: @Composable (position: SettingsGroupPosition) -> Unit) {
        rows += content
    }
}

/**
 * [settingsGroup] for places that aren't a lazy list, such as bottom sheets: an optional header,
 * then rows that number themselves.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsGroupColumn(
    modifier: Modifier = Modifier,
    header: String? = null,
    description: String? = null,
    content: SettingsColumnGroupBuilder.() -> Unit
) {
    val rows = SettingsColumnGroupBuilder().apply(content).rows
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
    ) {
        if (header != null) {
            SettingsHeader(label = header, description = description)
        }
        rows.forEachIndexed { index, row ->
            row(SettingsGroupPosition(index, rows.size))
        }
    }
}

/** The shape a grouped row at [position] should have. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun settingsGroupShape(position: SettingsGroupPosition): Shape =
    if (position.count <= 1) {
        RoundedCornerShape(SettingsGroupDefaults.SingleRowCorner)
    } else {
        ListItemDefaults.segmentedShapes(index = position.index, count = position.count).shape
    }

/**
 * A grouped row with free-form content (previews, swatch rows, segmented buttons, text fields),
 * drawn with the same shape and color as [SettingsListItem] so it sits inside a group.
 */
@Composable
fun SettingsGroupSurface(
    position: SettingsGroupPosition,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    content: @Composable BoxScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = settingsGroupShape(position),
        color = containerColor
    ) {
        Box(Modifier.padding(contentPadding)) {
            content()
        }
    }
}
