package app.podiumpodcasts.podium.ui.dialog.bottomsheet.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.podiumpodcasts.podium.PlayerTabConfig
import app.podiumpodcasts.podium.PlayerToolbarAction
import app.podiumpodcasts.podium.ui.component.common.SheetOverscroll
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableColumn

/** Toolbar order rules shared by the player and Customize player (pure, unit-tested). */
object PlayerToolbarOrder {
    /** The out-of-the-box toolbar, before any customizing. */
    val DEFAULT_ACTIONS: List<PlayerToolbarAction> = listOf(
        PlayerToolbarAction.HUSHCAST,
        PlayerToolbarAction.SPEED,
        PlayerToolbarAction.SLEEP,
        PlayerToolbarAction.TRANSCRIPT
    )
    const val DEFAULT_VISIBLE_COUNT = 4
    val DEFAULT_TABS: List<PlayerTabConfig> = listOf(
        PlayerTabConfig("PLAYER", true),
        PlayerTabConfig("NOTES", true),
        PlayerTabConfig("CHAPTERS", true)
    )

    /**
     * Every available action once, in the user's order: the old ANALYZE id counts as HUSHCAST,
     * and actions the user never placed come after theirs with Transcript first (T1).
     */
    fun ordered(configured: List<PlayerToolbarAction>, available: List<PlayerToolbarAction>): List<PlayerToolbarAction> {
        val mapped = configured
            .map { if (it == PlayerToolbarAction.ANALYZE) PlayerToolbarAction.HUSHCAST else it }
            .filter { it in available }
            .distinct()
        val missing = available
            .filter { it !in mapped }
            .sortedBy { if (it == PlayerToolbarAction.TRANSCRIPT) 0 else 1 }
        return mapped + missing
    }

    /** [list] with the item at [from] moved to [to] (indices clamped). */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices) return list
        val target = to.coerceIn(0, list.lastIndex)
        if (from == target) return list
        val mutable = list.toMutableList()
        mutable.add(target, mutable.removeAt(from))
        return mutable
    }
}

/** Sentence-case names for toolbar actions, matching the copy rules ("Mark as played"). */
internal fun PlayerToolbarAction.uiLabel(): String = when (this) {
    PlayerToolbarAction.HUSHCAST, PlayerToolbarAction.ANALYZE -> "HushCast"
    PlayerToolbarAction.SPEED -> "Speed"
    PlayerToolbarAction.SLEEP -> "Sleep timer"
    PlayerToolbarAction.TRANSCRIPT -> "Transcript"
    PlayerToolbarAction.FAVORITE -> "Favorite"
    PlayerToolbarAction.DOWNLOAD -> "Download"
    PlayerToolbarAction.GO_TO_PODCAST -> "Go to podcast"
    PlayerToolbarAction.EPISODE_DETAILS -> "Episode details"
    PlayerToolbarAction.MARK_PLAYED -> "Mark as played"
}

/** The plain (idle-state) icon for an action, used by the Customize preview. */
internal fun PlayerToolbarAction.previewIcon(): ImageVector = when (this) {
    PlayerToolbarAction.HUSHCAST, PlayerToolbarAction.ANALYZE -> Icons.Outlined.Shield
    PlayerToolbarAction.SPEED -> Icons.Filled.Speed
    PlayerToolbarAction.SLEEP -> Icons.Filled.Bedtime
    PlayerToolbarAction.TRANSCRIPT -> Icons.Rounded.Subtitles
    PlayerToolbarAction.FAVORITE -> Icons.Rounded.StarBorder
    PlayerToolbarAction.DOWNLOAD -> Icons.Rounded.Download
    PlayerToolbarAction.GO_TO_PODCAST -> Icons.Rounded.Podcasts
    PlayerToolbarAction.EPISODE_DETAILS -> Icons.Rounded.Info
    PlayerToolbarAction.MARK_PLAYED -> Icons.Rounded.Done
}

/** Tab names exactly as the player's tab row shows them. */
internal fun playerTabLabel(id: String): String = when (id) {
    "PLAYER" -> "Playing"
    "NOTES" -> "Details"
    "CHAPTERS" -> "Chapters"
    else -> id
}

/**
 * Customize player (I9): drag the tabs and toolbar actions into order (the same reorderable
 * library as Up Next), see the toolbar as it will look, pick how many icons show, and reset
 * everything to the defaults.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerCustomizeSheet(
    tabsConfig: List<PlayerTabConfig>,
    onUpdateTabs: (List<PlayerTabConfig>) -> Unit,
    actionsOrder: List<PlayerToolbarAction>,
    availableActions: List<PlayerToolbarAction>,
    onUpdateActions: (List<PlayerToolbarAction>) -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val settingsRepository = app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository.current
    val visibleCount by settingsRepository.behavior.playerToolbarVisibleCount
        .collectAsState(initial = PlayerToolbarOrder.DEFAULT_VISIBLE_COUNT)

    // Local copies, so a drag shows its result at once; the settings follow.
    var localTabs by remember { mutableStateOf(tabsConfig.filter { it.id != "PLAYER" }) }
    LaunchedEffect(tabsConfig) { localTabs = tabsConfig.filter { it.id != "PLAYER" } }
    var localActions by remember { mutableStateOf(PlayerToolbarOrder.ordered(actionsOrder, availableActions)) }
    LaunchedEffect(actionsOrder, availableActions) {
        localActions = PlayerToolbarOrder.ordered(actionsOrder, availableActions)
    }

    fun saveTabs(updated: List<PlayerTabConfig>) {
        localTabs = updated
        onUpdateTabs(listOf(PlayerTabConfig("PLAYER", true)) + updated)
    }

    fun saveActions(updated: List<PlayerToolbarAction>) {
        localActions = updated
        onUpdateActions(updated)
    }

    val isDefault = localActions == PlayerToolbarOrder.ordered(PlayerToolbarOrder.DEFAULT_ACTIONS, availableActions) &&
        visibleCount == PlayerToolbarOrder.DEFAULT_VISIBLE_COUNT &&
        (listOf(PlayerTabConfig("PLAYER", true)) + localTabs) == PlayerToolbarOrder.DEFAULT_TABS

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismiss
    ) {
        SheetOverscroll {
        PlayerSheetBlur()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Customize player",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    enabled = !isDefault,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        saveTabs(PlayerToolbarOrder.DEFAULT_TABS.filter { it.id != "PLAYER" })
                        saveActions(PlayerToolbarOrder.ordered(PlayerToolbarOrder.DEFAULT_ACTIONS, availableActions))
                        scope.launch {
                            settingsRepository.behavior.setPlayerToolbarVisibleCount(PlayerToolbarOrder.DEFAULT_VISIBLE_COUNT)
                        }
                    }
                ) {
                    Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Reset to default")
                }
            }

            Spacer(Modifier.height(12.dp))

            // Live preview of the toolbar.
            ToolbarPreview(
                visible = localActions.take(visibleCount),
                hasOverflow = true
            )

            Spacer(Modifier.height(16.dp))

            SectionLabel("Tabs")
            Text(
                text = "Drag to reorder. Playing always comes first.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))

            // Playing: pinned first, can't be hidden.
            CustomizeRow(
                label = playerTabLabel("PLAYER"),
                supporting = "Always shown",
                leading = {
                    Icon(
                        Icons.Rounded.PushPin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )

            ReorderableColumn(
                list = localTabs,
                onSettle = { from, to ->
                    saveTabs(PlayerToolbarOrder.move(localTabs, from, to))
                },
                onMove = { haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) },
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) { _, tab, isDragging ->
                key(tab.id) {
                    ReorderableItem {
                        val label = playerTabLabel(tab.id)
                        CustomizeRow(
                            label = label,
                            supporting = if (tab.visible) "Shown" else "Hidden",
                            dragging = isDragging,
                            modifier = Modifier.toggleable(
                                value = tab.visible,
                                role = Role.Switch,
                                onValueChange = { checked ->
                                    haptic.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                                    saveTabs(localTabs.map { if (it.id == tab.id) it.copy(visible = checked) else it })
                                }
                            ),
                            leading = {
                                DragHandleIcon(
                                    description = "Reorder $label",
                                    modifier = Modifier.draggableHandle(
                                        onDragStarted = {
                                            haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                        },
                                        onDragStopped = {
                                            haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        }
                                    )
                                )
                            },
                            trailing = { Switch(checked = tab.visible, onCheckedChange = null) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // R17: choose how many actions show in the toolbar before the ⋯ menu.
            SectionLabel("Toolbar")
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Visible icons",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                app.podiumpodcasts.podium.ui.component.common.ConnectedSingleChoiceGroup(
                    options = listOf(3, 4, 5),
                    selected = visibleCount,
                    onSelect = { n ->
                        scope.launch { settingsRepository.behavior.setPlayerToolbarVisibleCount(n) }
                    },
                    modifier = Modifier.width(156.dp)
                ) { n ->
                    Text("$n")
                }
            }
            Text(
                text = "Drag to reorder. The rest are in the ⋯ menu.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            ReorderableColumn(
                list = localActions,
                onSettle = { from, to ->
                    saveActions(PlayerToolbarOrder.move(localActions, from, to))
                },
                onMove = { haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) },
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) { idx, action, isDragging ->
                key(action) {
                    ReorderableItem {
                        val inToolbar = idx < visibleCount
                        CustomizeRow(
                            label = action.uiLabel(),
                            supporting = if (inToolbar) "In the toolbar" else "In the ⋯ menu",
                            supportingHighlighted = inToolbar,
                            dragging = isDragging,
                            leading = {
                                DragHandleIcon(
                                    description = "Reorder ${action.uiLabel()}",
                                    modifier = Modifier.draggableHandle(
                                        onDragStarted = {
                                            haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                        },
                                        onDragStopped = {
                                            haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        }
                                    )
                                )
                            },
                            trailing = {
                                Icon(
                                    imageVector = action.previewIcon(),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun DragHandleIcon(description: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(48.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.DragHandle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CustomizeRow(
    label: String,
    supporting: String,
    modifier: Modifier = Modifier,
    supportingHighlighted: Boolean = false,
    dragging: Boolean = false,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = if (dragging) 6.dp else 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { leading() }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (supportingHighlighted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            trailing()
        }
    }
}

/** A small, non-interactive copy of the toolbar as it will look (I9). */
@Composable
private fun ToolbarPreview(visible: List<PlayerToolbarAction>, hasOverflow: Boolean) {
    val description = "Toolbar preview: " + visible.joinToString(", ") { it.uiLabel() } +
        if (hasOverflow) ", then More" else ""
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            visible.forEach { action ->
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = action.previewIcon(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (hasOverflow) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.MoreHoriz,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
