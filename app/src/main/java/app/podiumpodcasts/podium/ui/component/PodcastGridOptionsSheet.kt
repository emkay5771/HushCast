package app.podiumpodcasts.podium.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.podiumpodcasts.podium.SubscriptionsBadgeMode
import app.podiumpodcasts.podium.SubscriptionsLayout
import app.podiumpodcasts.podium.SubscriptionsSort
import app.podiumpodcasts.podium.ui.theme.Typography

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PodcastGridOptionsSheet(
    layout: SubscriptionsLayout,
    sort: SubscriptionsSort,
    badgeMode: SubscriptionsBadgeMode,
    showGridTitles: Boolean,
    hasFolders: Boolean,
    reorderMode: Boolean,
    onLayoutChange: (SubscriptionsLayout) -> Unit,
    onSortChange: (SubscriptionsSort) -> Unit,
    onBadgeModeChange: (SubscriptionsBadgeMode) -> Unit,
    onShowGridTitlesChange: (Boolean) -> Unit,
    onToggleReorderMode: (Boolean) -> Unit,
    onNewFolder: () -> Unit,
    onOrganizeFolders: () -> Unit,
    onDismiss: () -> Unit,
    /** False where folders aren't shown (Subscriptions): hides the folder buttons. */
    showFolderActions: Boolean = true
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("Podcast options", style = Typography.titleLargeEmphasized, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(16.dp))

            Text("Layout", style = Typography.labelLarge, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            val layoutOptions = listOf(
                SubscriptionsLayout.LARGE_GRID to "Large grid",
                SubscriptionsLayout.SMALL_GRID to "Small grid",
                SubscriptionsLayout.LIST to "List"
            )
            app.podiumpodcasts.podium.ui.component.common.ConnectedSingleChoiceGroup(
                options = layoutOptions,
                selected = layoutOptions.first { it.first == layout },
                onSelect = { onLayoutChange(it.first) }
            ) { (_, label) ->
                Text(label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }

            Spacer(Modifier.height(16.dp))
            Text("Sort by", style = Typography.labelLarge, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            val sortOptions = listOf(
                SubscriptionsSort.RECENTLY_ADDED to "New episodes",
                SubscriptionsSort.A_Z to "A–Z",
                SubscriptionsSort.CUSTOM to "Custom"
            )
            app.podiumpodcasts.podium.ui.component.common.ConnectedSingleChoiceGroup(
                options = sortOptions,
                selected = sortOptions.first { it.first == sort },
                onSelect = { onSortChange(it.first) }
            ) { (_, label) ->
                Text(label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }

            Spacer(Modifier.height(16.dp))
            Text("Badges", style = Typography.labelLarge, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            val badgeOptions = listOf(
                SubscriptionsBadgeMode.UNPLAYED_COUNT to "Count",
                SubscriptionsBadgeMode.LATEST_ONLY to "Dot",
                SubscriptionsBadgeMode.OFF to "Off"
            )
            app.podiumpodcasts.podium.ui.component.common.ConnectedSingleChoiceGroup(
                options = badgeOptions,
                selected = badgeOptions.first { it.first == badgeMode },
                onSelect = { onBadgeModeChange(it.first) }
            ) { (_, label) ->
                Text(label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }

            val gridHaptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            if (layout != SubscriptionsLayout.LIST) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Reorder by dragging", style = Typography.bodyLarge)
                    Switch(
                        checked = reorderMode,
                        onCheckedChange = { checked ->
                            gridHaptic.performHapticFeedback(
                                if (checked) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn
                                else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff
                            )
                            onToggleReorderMode(checked)
                        }
                    )
                }
            }

            if (layout == SubscriptionsLayout.LARGE_GRID) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Show titles in grid", style = Typography.bodyLarge)
                    Switch(
                        checked = showGridTitles,
                        onCheckedChange = { checked ->
                            gridHaptic.performHapticFeedback(
                                if (checked) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn
                                else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff
                            )
                            onShowGridTitlesChange(checked)
                        }
                    )
                }
            }

            if (showFolderActions) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = {
                    onDismiss()
                    onNewFolder()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("New folder")
            }
            if (hasFolders) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onOrganizeFolders()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Organize folders")
                }
            }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
