package app.podiumpodcasts.podium.ui.route.settings.pane

import android.annotation.SuppressLint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Speed
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.ui.component.settings.SettingsSecondsSliderListItem
import app.podiumpodcasts.podium.ui.component.settings.SettingsSwitchListItem
import app.podiumpodcasts.podium.ui.custom.icons.Forward
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
            item {
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

                    index = 0,
                    count = 6
                )
            }

            item {
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

                    index = 1,
                    count = 6
                )
            }

            item {
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
                    index = 2,
                    count = 6
                )
            }

            item {
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
                            vm.repository.behavior.setPlayerPlaybackSpeed(speed.value)
                            mediaPlayerViewModel.setPlaybackSpeed(speed.value)
                        }
                    },
                    supportingContent = {
                        Text("${String.format(java.util.Locale.US, "%.1f", speed.value)}x")
                    },
                    index = 3,
                    count = 6
                )
            }

            item {
                val swipeRightAction = vm.repository.behavior.episodeSwipeRightAction.collectAsState(
                    app.podiumpodcasts.podium.EpisodeSwipeAction.PLAY_NEXT
                )
                val showPicker = remember { mutableStateOf(false) }

                app.podiumpodcasts.podium.ui.component.settings.SettingsListItem(
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Rounded.PlaylistAdd,
                            "Swipe right action"
                        )
                    },
                    label = "Episode swipe right action",
                    description = swipeRightAction.value.label,
                    onClick = { showPicker.value = true },
                    index = 4,
                    count = 8
                )

                if (showPicker.value) {
                    SwipeActionPickerDialog(
                        title = "Episode swipe right action",
                        selected = swipeRightAction.value,
                        onSelect = { action ->
                            showPicker.value = false
                            scope.launch {
                                vm.repository.behavior.setEpisodeSwipeRightAction(action)
                            }
                        },
                        onDismiss = { showPicker.value = false }
                    )
                }
            }

            item {
                val swipeLeftAction = vm.repository.behavior.episodeSwipeLeftAction.collectAsState(
                    app.podiumpodcasts.podium.EpisodeSwipeAction.MARK_PLAYED
                )
                val showPicker = remember { mutableStateOf(false) }

                app.podiumpodcasts.podium.ui.component.settings.SettingsListItem(
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Rounded.PlaylistAdd,
                            "Swipe left action"
                        )
                    },
                    label = "Episode swipe left action",
                    description = swipeLeftAction.value.label,
                    onClick = { showPicker.value = true },
                    index = 5,
                    count = 8
                )

                if (showPicker.value) {
                    SwipeActionPickerDialog(
                        title = "Episode swipe left action",
                        selected = swipeLeftAction.value,
                        onSelect = { action ->
                            showPicker.value = false
                            scope.launch {
                                vm.repository.behavior.setEpisodeSwipeLeftAction(action)
                            }
                        },
                        onDismiss = { showPicker.value = false }
                    )
                }
            }

            item {
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
                    index = 6,
                    count = 8
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
                                            .clickable {
                                                showPicker.value = false
                                                scope.launch { vm.repository.behavior.setHeadsetNextAction(action) }
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                    ) {
                                        androidx.compose.material3.RadioButton(
                                            selected = action == headsetNext.value,
                                            onClick = {
                                                showPicker.value = false
                                                scope.launch { vm.repository.behavior.setHeadsetNextAction(action) }
                                            }
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

            item {
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
                    index = 7,
                    count = 8
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
                                            .clickable {
                                                showPicker.value = false
                                                scope.launch { vm.repository.behavior.setHeadsetPrevAction(action) }
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                    ) {
                                        androidx.compose.material3.RadioButton(
                                            selected = action == headsetPrev.value,
                                            onClick = {
                                                showPicker.value = false
                                                scope.launch { vm.repository.behavior.setHeadsetPrevAction(action) }
                                            }
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

            item {
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
                    index = 8,
                    count = 9
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
                                            .clickable {
                                                showPicker.value = false
                                                scope.launch { vm.repository.behavior.setRemainingTimeDisplay(mode) }
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                    ) {
                                        androidx.compose.material3.RadioButton(
                                            selected = mode == remainingMode.value,
                                            onClick = {
                                                showPicker.value = false
                                                scope.launch { vm.repository.behavior.setRemainingTimeDisplay(mode) }
                                            }
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
    }
}

@Composable
private fun SwipeActionPickerDialog(
    title: String,
    selected: app.podiumpodcasts.podium.EpisodeSwipeAction,
    onSelect: (app.podiumpodcasts.podium.EpisodeSwipeAction) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.foundation.layout.Column {
                app.podiumpodcasts.podium.EpisodeSwipeAction.entries.forEach { action ->
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(action) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = action == selected,
                            onClick = { onSelect(action) }
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 4.dp))
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
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}