package app.podiumpodcasts.podium.ui.route.settings.pane

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.ui.component.common.SheetOverscroll
import app.podiumpodcasts.podium.ui.component.settings.SettingsGroupColumn
import app.podiumpodcasts.podium.ui.component.settings.SettingsGroupPosition
import app.podiumpodcasts.podium.ui.component.settings.SettingsGroupSurface
import app.podiumpodcasts.podium.ui.component.settings.SettingsListItem
import app.podiumpodcasts.podium.ui.component.settings.SettingsSecondsSliderListItem
import app.podiumpodcasts.podium.ui.component.settings.SettingsSwitchListItem
import app.podiumpodcasts.podium.ui.custom.icons.Forward
import app.podiumpodcasts.podium.ui.component.settings.settingsGroup
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository
import app.podiumpodcasts.podium.ui.route.settings.SettingsPaneKey
import app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel
import app.podiumpodcasts.podium.ui.vm.SettingsViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@kotlinx.parcelize.Parcelize
@Serializable
class SettingsPlaybackKey : SettingsPaneKey()

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsPlaybackPane(
    navigationIcon: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()

    val db = LocalDatabase.current
    val settingsRepository = LocalSettingsRepository.current

    val vm = viewModel { SettingsViewModel(db, settingsRepository) }
    val mediaPlayerViewModel = viewModel<MediaPlayerViewModel>()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = navigationIcon,

                title = {
                    Text(stringResource(R.string.route_settings_playback))
                }
            )
        }
    ) {
        LazyColumn(
            Modifier
                .padding(it)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),

            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
        ) {
            settingsGroup(header = "Seeking", key = "seek", spaceAbove = 0.dp) {
                row { pos ->
                    val increment = vm.repository.behavior.playerSeekBackIncrement.collectAsState(10000)

                    val seconds = remember { mutableStateOf(10) }
                    LaunchedEffect(increment.value) { seconds.value = (increment.value / 1000).toInt() }

                    SettingsSecondsSliderListItem(
                        icon = {
                            Icon(
                                Icons.Rounded.Replay,
                                stringResource(R.string.route_settings_player_seek_back_increment)
                            )
                        },
                        label = stringResource(R.string.route_settings_player_seek_back_increment),

                        value = seconds.value,
                        onValueChange = { seconds.value = it },

                        min = 1,
                        max = 120,

                        onValueChangeFinished = {
                            scope.launch {
                                vm.repository.behavior.setPlayerSeekBackIncrement(seconds.value * 1000L)
                                mediaPlayerViewModel.updateSeekBackIncrement(seconds.value * 1000L)
                            }
                        },

                        index = pos.index,
                        count = pos.count
                    )
                }
                row { pos ->
                    val increment =
                        vm.repository.behavior.playerSeekForwardIncrement.collectAsState(10000)

                    val seconds = remember { mutableStateOf(10) }
                    LaunchedEffect(increment.value) { seconds.value = (increment.value / 1000).toInt() }

                    SettingsSecondsSliderListItem(
                        icon = {
                            Icon(
                                Icons.Rounded.Forward,
                                stringResource(R.string.route_settings_player_seek_forward_increment)
                            )
                        },
                        label = stringResource(R.string.route_settings_player_seek_forward_increment),

                        value = seconds.value,
                        onValueChange = { seconds.value = it },

                        min = 1,
                        max = 120,

                        onValueChangeFinished = {
                            scope.launch {
                                vm.repository.behavior.setPlayerSeekForwardIncrement(seconds.value * 1000L)
                                mediaPlayerViewModel.updateSeekForwardIncrement(seconds.value * 1000L)
                            }
                        },

                        index = pos.index,
                        count = pos.count
                    )
                }
            }

            settingsGroup(header = "Playback", key = "playback") {
                row { pos ->
                    val savedSpeed = vm.repository.behavior.playerPlaybackSpeed.collectAsState(1f)
                    val speed = remember { mutableStateOf(1f) }
                    LaunchedEffect(savedSpeed.value) { speed.value = savedSpeed.value }

                    app.podiumpodcasts.podium.ui.component.settings.SettingsSliderListItem(
                        icon = {
                            Icon(
                                androidx.compose.material.icons.Icons.Rounded.Speed,
                                "Default playback speed"
                            )
                        },
                        label = "Default playback speed",
                        value = speed.value,
                        onValueChange = { speed.value = (kotlin.math.round(it * 10f) / 10f).coerceIn(0.5f, 3.0f) },
                        valueRange = 0.5f..3.0f,
                        steps = 24,
                        onValueChangeFinished = {
                            scope.launch {
                                // PlaybackService applies it unless the playing podcast has its own speed.
                                vm.repository.behavior.setPlayerPlaybackSpeed(speed.value)
                            }
                        },
                        supportingContent = {
                            Text(app.podiumpodcasts.podium.ui.component.settings.formatSettingsPlaybackSpeed(speed.value))
                        },
                        index = pos.index,
                        count = pos.count
                    )
                }
                row { pos ->
                    val intelligentResumption = vm.repository.behavior.intelligentPlaybackResumption
                        .collectAsState(false)

                    SettingsSwitchListItem(
                        checked = intelligentResumption.value,
                        onCheckedChange = {
                            scope.launch {
                                vm.repository.behavior.setIntelligentPlaybackResumption(it)
                            }
                        },
                        icon = {
                            Icon(
                                Icons.Rounded.Replay,
                                "Intelligent playback resumption"
                            )
                        },
                        label = "Intelligent playback resumption",
                        description = "Automatically rewind a few seconds when resuming after a pause",
                        index = pos.index,
                        count = pos.count
                    )
                }
                row { pos ->
                    val remainingMode = vm.repository.behavior.remainingTimeDisplay.collectAsState(
                        app.podiumpodcasts.podium.RemainingTimeDisplayMode.BOTH
                    )
                    val showPicker = remember { mutableStateOf(false) }

                    app.podiumpodcasts.podium.ui.component.settings.SettingsListItem(
                        icon = {
                            Icon(
                                androidx.compose.material.icons.Icons.Rounded.Speed,
                                "Remaining time display"
                            )
                        },
                        label = "Remaining time display",
                        description = remainingMode.value.label,
                        onClick = { showPicker.value = true },
                        index = pos.index,
                        count = pos.count
                    )

                    if (showPicker.value) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showPicker.value = false },
                            title = { Text("Remaining time display") },
                            text = {
                                androidx.compose.foundation.layout.Column {
                                    app.podiumpodcasts.podium.RemainingTimeDisplayMode.entries.forEach { mode ->
                                        androidx.compose.foundation.layout.Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .selectable(
                                                    selected = mode == remainingMode.value,
                                                    role = androidx.compose.ui.semantics.Role.RadioButton
                                                ) {
                                                    showPicker.value = false
                                                    scope.launch { vm.repository.behavior.setRemainingTimeDisplay(mode) }
                                                }
                                                .padding(vertical = 8.dp),
                                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                        ) {
                                            androidx.compose.material3.RadioButton(
                                                selected = mode == remainingMode.value,
                                                onClick = null,
                                                // Same 48 dp footprint as an interactive radio button.
                                                modifier = Modifier.padding(14.dp)
                                            )
                                            Text(mode.label, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            },
                            confirmButton = {},
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = { showPicker.value = false }) { Text("Cancel") }
                            }
                        )
                    }
                }
            }

            settingsGroup(header = "Up Next & lists", key = "lists") {
                row { pos ->
                    val tapUpNextToPlay = vm.repository.behavior.tapUpNextToPlay.collectAsState(true)

                    SettingsSwitchListItem(
                        checked = tapUpNextToPlay.value,
                        onCheckedChange = {
                            scope.launch {
                                vm.repository.behavior.setTapUpNextToPlay(it)
                            }
                        },
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Rounded.PlaylistAdd,
                                "Tap Up Next to play"
                            )
                        },
                        label = "Tap Up Next to play",
                        description = "Tapping a queued episode plays it immediately instead of opening episode details",
                        index = pos.index,
                        count = pos.count
                    )
                }
                row { pos ->
                    val swipeRightPrimary by vm.repository.behavior.episodeSwipeRightAction.collectAsState(
                        app.podiumpodcasts.podium.EpisodeSwipeAction.PLAY_NEXT
                    )
                    val swipeRightSecondary by vm.repository.behavior.episodeSwipeRightSecondaryAction.collectAsState(
                        app.podiumpodcasts.podium.EpisodeSwipeAction.PLAY_LAST
                    )
                    val swipeLeftPrimary by vm.repository.behavior.episodeSwipeLeftAction.collectAsState(
                        app.podiumpodcasts.podium.EpisodeSwipeAction.MARK_PLAYED
                    )
                    val swipeLeftSecondary by vm.repository.behavior.episodeSwipeLeftSecondaryAction.collectAsState(
                        app.podiumpodcasts.podium.EpisodeSwipeAction.DOWNLOAD
                    )
                    val showSwipeSheet = remember { mutableStateOf(false) }

                    app.podiumpodcasts.podium.ui.component.settings.SettingsListItem(
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Rounded.PlaylistAdd,
                                "Row swipe actions"
                            )
                        },
                        label = "Row swipe actions",
                        description = "Right: ${swipeRightPrimary.label} • Left: ${swipeLeftPrimary.label}",
                        onClick = { showSwipeSheet.value = true },
                        index = pos.index,
                        count = pos.count
                    )

                    if (showSwipeSheet.value) {
                        PocketCastsSwipeActionsSheet(
                            rightPrimary = swipeRightPrimary,
                            rightSecondary = swipeRightSecondary,
                            leftPrimary = swipeLeftPrimary,
                            leftSecondary = swipeLeftSecondary,
                            onRightPrimaryChange = { action ->
                                scope.launch { vm.repository.behavior.setEpisodeSwipeRightAction(action) }
                            },
                            onRightSecondaryChange = { action ->
                                scope.launch { vm.repository.behavior.setEpisodeSwipeRightSecondaryAction(action) }
                            },
                            onLeftPrimaryChange = { action ->
                                scope.launch { vm.repository.behavior.setEpisodeSwipeLeftAction(action) }
                            },
                            onLeftSecondaryChange = { action ->
                                scope.launch { vm.repository.behavior.setEpisodeSwipeLeftSecondaryAction(action) }
                            },
                            onDismiss = { showSwipeSheet.value = false }
                        )
                    }
                }
            }

            settingsGroup(header = "Headset buttons", key = "headset") {
                row { pos ->
                    val headsetNext = vm.repository.behavior.headsetNextAction.collectAsState(
                        app.podiumpodcasts.podium.HeadsetNextAction.SKIP_FORWARD
                    )
                    val showPicker = remember { mutableStateOf(false) }

                    app.podiumpodcasts.podium.ui.component.settings.SettingsListItem(
                        icon = {
                            Icon(
                                Icons.Rounded.Forward,
                                "Headset Next button"
                            )
                        },
                        label = "Headset Next button",
                        description = headsetNext.value.label,
                        onClick = { showPicker.value = true },
                        index = pos.index,
                        count = pos.count
                    )

                    if (showPicker.value) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showPicker.value = false },
                            title = { Text("Headset Next button") },
                            text = {
                                androidx.compose.foundation.layout.Column {
                                    app.podiumpodcasts.podium.HeadsetNextAction.entries.forEach { action ->
                                        androidx.compose.foundation.layout.Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .selectable(
                                                    selected = action == headsetNext.value,
                                                    role = androidx.compose.ui.semantics.Role.RadioButton
                                                ) {
                                                    showPicker.value = false
                                                    scope.launch { vm.repository.behavior.setHeadsetNextAction(action) }
                                                }
                                                .padding(vertical = 8.dp),
                                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                        ) {
                                            androidx.compose.material3.RadioButton(
                                                selected = action == headsetNext.value,
                                                onClick = null,
                                                // Same 48 dp footprint as an interactive radio button.
                                                modifier = Modifier.padding(14.dp)
                                            )
                                            Text(action.label, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            },
                            confirmButton = {},
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = { showPicker.value = false }) { Text("Cancel") }
                            }
                        )
                    }
                }
                row { pos ->
                    val headsetPrev = vm.repository.behavior.headsetPrevAction.collectAsState(
                        app.podiumpodcasts.podium.HeadsetPrevAction.SKIP_BACK
                    )
                    val showPicker = remember { mutableStateOf(false) }

                    app.podiumpodcasts.podium.ui.component.settings.SettingsListItem(
                        icon = {
                            Icon(
                                Icons.Rounded.Replay,
                                "Headset Previous button"
                            )
                        },
                        label = "Headset Previous button",
                        description = headsetPrev.value.label,
                        onClick = { showPicker.value = true },
                        index = pos.index,
                        count = pos.count
                    )

                    if (showPicker.value) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showPicker.value = false },
                            title = { Text("Headset Previous button") },
                            text = {
                                androidx.compose.foundation.layout.Column {
                                    app.podiumpodcasts.podium.HeadsetPrevAction.entries.forEach { action ->
                                        androidx.compose.foundation.layout.Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .selectable(
                                                    selected = action == headsetPrev.value,
                                                    role = androidx.compose.ui.semantics.Role.RadioButton
                                                ) {
                                                    showPicker.value = false
                                                    scope.launch { vm.repository.behavior.setHeadsetPrevAction(action) }
                                                }
                                                .padding(vertical = 8.dp),
                                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                        ) {
                                            androidx.compose.material3.RadioButton(
                                                selected = action == headsetPrev.value,
                                                onClick = null,
                                                // Same 48 dp footprint as an interactive radio button.
                                                modifier = Modifier.padding(14.dp)
                                            )
                                            Text(action.label, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            },
                            confirmButton = {},
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = { showPicker.value = false }) { Text("Cancel") }
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PocketCastsSwipeActionsSheet(
    rightPrimary: app.podiumpodcasts.podium.EpisodeSwipeAction,
    rightSecondary: app.podiumpodcasts.podium.EpisodeSwipeAction,
    leftPrimary: app.podiumpodcasts.podium.EpisodeSwipeAction,
    leftSecondary: app.podiumpodcasts.podium.EpisodeSwipeAction,
    onRightPrimaryChange: (app.podiumpodcasts.podium.EpisodeSwipeAction) -> Unit,
    onRightSecondaryChange: (app.podiumpodcasts.podium.EpisodeSwipeAction) -> Unit,
    onLeftPrimaryChange: (app.podiumpodcasts.podium.EpisodeSwipeAction) -> Unit,
    onLeftSecondaryChange: (app.podiumpodcasts.podium.EpisodeSwipeAction) -> Unit,
    onDismiss: () -> Unit
) {
    var editingSlot by remember { mutableStateOf<String?>(null) }

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetOverscroll {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Row swipe actions",
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = "Swipe halfway to reveal both actions, or swipe all the way across to trigger the primary action.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Live Pocket Casts-style two-row preview (swipe right & swipe left)
            SettingsGroupColumn(header = "Preview") {
                row { pos ->
                    SwipeDirectionPreviewRow(
                        position = pos,
                        directionLabel = "Swipe right →",
                        isSwipeRight = true,
                        primary = rightPrimary,
                        secondary = rightSecondary
                    )
                }
                row { pos ->
                    SwipeDirectionPreviewRow(
                        position = pos,
                        directionLabel = "← Swipe left",
                        isSwipeRight = false,
                        primary = leftPrimary,
                        secondary = leftSecondary
                    )
                }
            }

            SettingsGroupColumn(header = "Swipe right") {
                row { pos ->
                    SwipeConfigSlotRow(
                        position = pos,
                        title = "Primary action",
                        subtitle = "Full swipe or outer button",
                        action = rightPrimary,
                        onClick = { editingSlot = "RIGHT_PRIMARY" }
                    )
                }
                row { pos ->
                    SwipeConfigSlotRow(
                        position = pos,
                        title = "Secondary action",
                        subtitle = "Half swipe inner button",
                        action = rightSecondary,
                        onClick = { editingSlot = "RIGHT_SECONDARY" }
                    )
                }
            }

            SettingsGroupColumn(header = "Swipe left") {
                row { pos ->
                    SwipeConfigSlotRow(
                        position = pos,
                        title = "Primary action",
                        subtitle = "Full swipe or outer button",
                        action = leftPrimary,
                        onClick = { editingSlot = "LEFT_PRIMARY" }
                    )
                }
                row { pos ->
                    SwipeConfigSlotRow(
                        position = pos,
                        title = "Secondary action",
                        subtitle = "Half swipe inner button",
                        action = leftSecondary,
                        onClick = { editingSlot = "LEFT_SECONDARY" }
                    )
                }
            }

            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 16.dp))
        }
        }
    }

    editingSlot?.let { slot ->
        val (title, current, onSelect) = when (slot) {
            "RIGHT_PRIMARY" -> Triple("Swipe right: primary action", rightPrimary, onRightPrimaryChange)
            "RIGHT_SECONDARY" -> Triple("Swipe right: secondary action", rightSecondary, onRightSecondaryChange)
            "LEFT_PRIMARY" -> Triple("Swipe left: primary action", leftPrimary, onLeftPrimaryChange)
            else -> Triple("Swipe left: secondary action", leftSecondary, onLeftSecondaryChange)
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editingSlot = null },
            title = { Text(title) },
            text = {
                androidx.compose.foundation.layout.Column {
                    app.podiumpodcasts.podium.EpisodeSwipeAction.entries.forEach { action ->
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = action == current,
                                    role = androidx.compose.ui.semantics.Role.RadioButton
                                ) {
                                    onSelect(action)
                                    editingSlot = null
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = action == current,
                                onClick = null,
                                // Same 48 dp footprint as an interactive radio button.
                                modifier = Modifier.padding(14.dp)
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 4.dp))
                            Icon(
                                imageVector = swipeActionIcon(action),
                                contentDescription = null,
                                tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 6.dp))
                            Text(
                                text = action.label,
                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { editingSlot = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SwipeDirectionPreviewRow(
    position: SettingsGroupPosition,
    directionLabel: String,
    isSwipeRight: Boolean,
    primary: app.podiumpodcasts.podium.EpisodeSwipeAction,
    secondary: app.podiumpodcasts.podium.EpisodeSwipeAction
) {
    SettingsGroupSurface(
        position = position,
        modifier = Modifier.height(56.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isSwipeRight) {
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    SwipeActionPreviewChip(primary, primary = true)
                    if (secondary != app.podiumpodcasts.podium.EpisodeSwipeAction.NONE) {
                        SwipeActionPreviewChip(secondary, primary = false)
                    }
                }
                SwipeMockEpisodeTile(
                    label = directionLabel,
                    modifier = Modifier.weight(1f)
                )
            } else {
                SwipeMockEpisodeTile(
                    label = directionLabel,
                    modifier = Modifier.weight(1f)
                )
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    if (secondary != app.podiumpodcasts.podium.EpisodeSwipeAction.NONE) {
                        SwipeActionPreviewChip(secondary, primary = false)
                    }
                    SwipeActionPreviewChip(primary, primary = true)
                }
            }
        }
    }
}

@Composable
private fun SwipeMockEpisodeTile(
    label: String,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxSize(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        color = androidx.compose.material3.MaterialTheme.colorScheme.surface
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            contentAlignment = androidx.compose.ui.Alignment.CenterStart
        ) {
            Text(
                text = label,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SwipeActionPreviewChip(
    action: app.podiumpodcasts.podium.EpisodeSwipeAction,
    primary: Boolean
) {
    val (bg, fg) = swipeActionColors(action)
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        color = if (primary) bg else bg.copy(alpha = 0.78f),
        contentColor = fg
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Icon(
                imageVector = swipeActionIcon(action),
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = swipeActionShortLabel(action),
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun swipeActionColors(
    action: app.podiumpodcasts.podium.EpisodeSwipeAction
): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> = when (action) {
    app.podiumpodcasts.podium.EpisodeSwipeAction.PLAY_NEXT ->
        androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer to androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer
    app.podiumpodcasts.podium.EpisodeSwipeAction.PLAY_LAST ->
        androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer to androidx.compose.material3.MaterialTheme.colorScheme.onSecondaryContainer
    app.podiumpodcasts.podium.EpisodeSwipeAction.DOWNLOAD ->
        androidx.compose.material3.MaterialTheme.colorScheme.tertiaryContainer to androidx.compose.material3.MaterialTheme.colorScheme.onTertiaryContainer
    app.podiumpodcasts.podium.EpisodeSwipeAction.MARK_PLAYED ->
        androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant to androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    app.podiumpodcasts.podium.EpisodeSwipeAction.NONE ->
        androidx.compose.material3.MaterialTheme.colorScheme.surface to androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
}

private fun swipeActionIcon(
    action: app.podiumpodcasts.podium.EpisodeSwipeAction
): androidx.compose.ui.graphics.vector.ImageVector = when (action) {
    app.podiumpodcasts.podium.EpisodeSwipeAction.PLAY_NEXT -> androidx.compose.material.icons.Icons.Rounded.QueuePlayNext
    app.podiumpodcasts.podium.EpisodeSwipeAction.PLAY_LAST -> androidx.compose.material.icons.Icons.AutoMirrored.Rounded.PlaylistAdd
    app.podiumpodcasts.podium.EpisodeSwipeAction.DOWNLOAD -> androidx.compose.material.icons.Icons.Rounded.Download
    app.podiumpodcasts.podium.EpisodeSwipeAction.MARK_PLAYED -> androidx.compose.material.icons.Icons.Rounded.DoneAll
    app.podiumpodcasts.podium.EpisodeSwipeAction.NONE -> androidx.compose.material.icons.Icons.Rounded.Block
}

private fun swipeActionShortLabel(
    action: app.podiumpodcasts.podium.EpisodeSwipeAction
): String = when (action) {
    app.podiumpodcasts.podium.EpisodeSwipeAction.PLAY_NEXT -> "Play next"
    app.podiumpodcasts.podium.EpisodeSwipeAction.PLAY_LAST -> "Play last"
    app.podiumpodcasts.podium.EpisodeSwipeAction.DOWNLOAD -> "Download"
    app.podiumpodcasts.podium.EpisodeSwipeAction.MARK_PLAYED -> "Played"
    app.podiumpodcasts.podium.EpisodeSwipeAction.NONE -> "None"
}

@Composable
private fun SwipeConfigSlotRow(
    position: SettingsGroupPosition,
    title: String,
    subtitle: String,
    action: app.podiumpodcasts.podium.EpisodeSwipeAction,
    onClick: () -> Unit
) {
    SettingsListItem(
        label = title,
        description = subtitle,
        trailingContent = { SwipeActionPreviewChip(action, primary = true) },
        index = position.index,
        count = position.count,
        onClick = onClick
    )
}
