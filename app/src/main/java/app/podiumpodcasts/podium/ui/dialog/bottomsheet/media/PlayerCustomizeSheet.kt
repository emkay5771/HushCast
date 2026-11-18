package app.podiumpodcasts.podium.ui.dialog.bottomsheet.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.podiumpodcasts.podium.PlayerTabConfig
import app.podiumpodcasts.podium.PlayerToolbarAction

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
    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Customize player",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Tabs",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))

            val editableTabs = tabsConfig.filter { it.id != "PLAYER" }
            editableTabs.forEachIndexed { idx, tab ->
                val label = when (tab.id) {
                    "NOTES" -> "Details"
                    "CHAPTERS" -> "Chapters"
                    else -> tab.id
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val updated = tabsConfig.map {
                                if (it.id == tab.id) it.copy(visible = !it.visible) else it
                            }
                            onUpdateTabs(updated)
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        enabled = idx > 0,
                        onClick = {
                            val mutable = editableTabs.toMutableList()
                            val moved = mutable.removeAt(idx)
                            mutable.add(idx - 1, moved)
                            onUpdateTabs(listOf(PlayerTabConfig("PLAYER", true)) + mutable)
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move up")
                    }
                    IconButton(
                        enabled = idx < editableTabs.lastIndex,
                        onClick = {
                            val mutable = editableTabs.toMutableList()
                            val moved = mutable.removeAt(idx)
                            mutable.add(idx + 1, moved)
                            onUpdateTabs(listOf(PlayerTabConfig("PLAYER", true)) + mutable)
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move down")
                    }
                    Spacer(Modifier.width(4.dp))
                    Switch(
                        checked = tab.visible,
                        onCheckedChange = { checked ->
                            onUpdateTabs(tabsConfig.map {
                                if (it.id == tab.id) it.copy(visible = checked) else it
                            })
                        }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Text(
                text = "Toolbar actions (first 4 shown)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))

            val orderedActions = (actionsOrder.filter { it in availableActions } +
                availableActions.filter { it !in actionsOrder }).distinct()

            orderedActions.forEachIndexed { idx, action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = action.label,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (idx < 4) {
                            Text(
                                text = "Visible in toolbar",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                text = "In More menu",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row {
                        IconButton(
                            enabled = idx > 0,
                            onClick = {
                                val mutable = orderedActions.toMutableList()
                                val moved = mutable.removeAt(idx)
                                mutable.add(idx - 1, moved)
                                onUpdateActions(mutable)
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move up")
                        }
                        IconButton(
                            enabled = idx < orderedActions.lastIndex,
                            onClick = {
                                val mutable = orderedActions.toMutableList()
                                val moved = mutable.removeAt(idx)
                                mutable.add(idx + 1, moved)
                                onUpdateActions(mutable)
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move down")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
