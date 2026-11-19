package app.podiumpodcasts.podium.ui.route.settings.pane

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.ui.component.settings.SettingsListItem
import app.podiumpodcasts.podium.ui.component.settings.SettingsSliderListItem
import app.podiumpodcasts.podium.ui.component.settings.SettingsSwitchListItem
import app.podiumpodcasts.podium.ui.component.settings.settingsGroup
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository
import app.podiumpodcasts.podium.ui.route.settings.RoamingWarningDialog
import app.podiumpodcasts.podium.ui.route.settings.SettingsPaneKey
import app.podiumpodcasts.podium.ui.vm.DeleteDownloadsAfterValues
import app.podiumpodcasts.podium.ui.vm.RoamingWarningDialogState
import app.podiumpodcasts.podium.ui.vm.SettingsViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@kotlinx.parcelize.Parcelize
@Serializable
class SettingsDownloadsAndStorageKey : SettingsPaneKey()

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsDownloadsAndStoragePane(
    navigationIcon: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val db = LocalDatabase.current
    val settingsRepository = LocalSettingsRepository.current

    val vm = viewModel { SettingsViewModel(db, settingsRepository) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = navigationIcon,

                title = {
                    Text(stringResource(R.string.route_settings_downloads_and_storage))
                }
            )
        }
    ) {
        val downloadMetered = vm.repository.behavior.downloadMetered.collectAsState(false)

        LazyColumn(
            Modifier
                .padding(it)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),

            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
        ) {
            settingsGroup(key = "downloads", spaceAbove = 0.dp) {
                row { pos ->
                    SettingsSwitchListItem(
                        checked = downloadMetered.value,
                        onCheckedChange = {
                            scope.launch {
                                vm.repository.behavior.setDownloadMetered(it)
                                vm.requeueDownloads(context, db)
                            }
                        },

                        icon = {
                            Icon(
                                Icons.Rounded.SignalCellularAlt,
                                stringResource(R.string.route_settings_downloads_and_storage_allow_mobile_downloads)
                            )
                        },
                        label = stringResource(R.string.route_settings_downloads_and_storage_allow_mobile_downloads),
                        description = stringResource(R.string.route_settings_downloads_and_storage_allow_mobile_downloads_description),

                        index = pos.index,
                        count = pos.count
                    )
                }
                row { pos ->
                    val downloadInRoaming =
                        vm.repository.behavior.downloadInRoaming.collectAsState(false)

                    SettingsSwitchListItem(
                        enabled = downloadMetered.value,

                        checked = downloadInRoaming.value,
                        onCheckedChange = {
                            if(it) {
                                vm.roamingWarningDialogState.value =
                                    RoamingWarningDialogState.ShowDownload
                            } else {
                                scope.launch {
                                    vm.repository.behavior.setDownloadInRoaming(false)
                                    vm.requeueDownloads(context, db)
                                }
                            }
                        },

                        icon = {
                            Icon(
                                Icons.Rounded.Public,
                                stringResource(R.string.route_settings_downloads_and_storage_allow_while_roaming)
                            )
                        },
                        label = stringResource(R.string.route_settings_downloads_and_storage_allow_while_roaming),
                        description = stringResource(R.string.route_settings_downloads_and_storage_allow_while_roaming_description),
                        index = pos.index,
                        count = pos.count
                    )
                }
                row { pos ->
                    val applySettingsForAutoDownloads =
                        vm.repository.behavior.applySettingsForAutoDownloads.collectAsState(false)

                    SettingsSwitchListItem(
                        enabled = downloadMetered.value,

                        checked = applySettingsForAutoDownloads.value,
                        onCheckedChange = {
                            scope.launch {
                                vm.repository.behavior.setApplySettingsForAutoDownloads(it)
                            }
                        },

                        icon = {
                            Icon(
                                Icons.Rounded.FileDownload,
                                stringResource(R.string.route_settings_downloads_and_storage_include_automatic_downloads)
                            )
                        },
                        label = stringResource(R.string.route_settings_downloads_and_storage_include_automatic_downloads),
                        description = stringResource(R.string.route_settings_downloads_and_storage_include_automatic_downloads_description),

                        index = pos.index,
                        count = pos.count
                    )
                }
                row { pos ->
                    val autoDownloadQueue =
                        vm.repository.behavior.autoDownloadQueue.collectAsState(false)

                    SettingsSwitchListItem(
                        checked = autoDownloadQueue.value,
                        onCheckedChange = {
                            scope.launch {
                                vm.repository.behavior.setAutoDownloadQueue(it)
                            }
                        },

                        icon = {
                            Icon(
                                Icons.Rounded.FileDownload,
                                "Auto-download queued episodes"
                            )
                        },
                        label = "Auto-download queued episodes",
                        description = "Automatically download episodes when added to the playback queue",

                        index = pos.index,
                        count = pos.count
                    )
                }
            }


            settingsGroup(key = "cleanup") {
                row { pos ->
                    val deletePlayedDownloads =
                        vm.repository.behavior.deletePlayedDownloads.collectAsState(false)

                    SettingsSwitchListItem(
                        checked = deletePlayedDownloads.value,
                        onCheckedChange = {
                            scope.launch {
                                vm.repository.behavior.setDeletePlayedDownloads(it)
                            }
                        },

                        icon = {
                            Icon(
                                Icons.Rounded.CleaningServices,
                                stringResource(R.string.route_settings_downloads_and_storage_delete_played_downloads)
                            )
                        },
                        label = stringResource(R.string.route_settings_downloads_and_storage_delete_played_downloads),
                        description = stringResource(R.string.route_settings_downloads_and_storage_delete_played_downloads_description),

                        index = pos.index,
                        count = pos.count
                    )
                }
                row { pos ->
                    SettingsSliderListItem(
                        icon = {
                            Icon(
                                Icons.Rounded.AutoDelete,
                                stringResource(R.string.route_settings_downloads_and_storage_delete_downloads_after)
                            )
                        },
                        label = stringResource(R.string.route_settings_downloads_and_storage_delete_downloads_after),

                        value = vm.deleteDownloadsAfterSliderState.floatValue,
                        onValueChange = { vm.updateDeleteDownloadsAfterSlider(it) },
                        valueRange = 0f..(DeleteDownloadsAfterValues.entries.size - 1).toFloat(),
                        steps = DeleteDownloadsAfterValues.entries.size - 2,

                        onValueChangeFinished = {
                            scope.launch {
                                vm.repository.behavior.setDeleteDownloadsAfterSeconds(
                                    vm.deleteDownloadsAfterTranslatedSliderState.value.seconds
                                )
                            }
                        },

                        supportingContent = {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Absolute.SpaceBetween
                            ) {
                                vm.deleteDownloadsAfterTranslatedSliderState.value.let { state ->
                                    Text(
                                        text = stringResource(state.label)
                                    )
                                }
                            }
                        },

                        index = pos.index,
                        count = pos.count
                    )
                }
                row { pos ->
                    val totalSize = db.podcastEpisodeDownloads().totalSize().collectAsState(0L)
                    val showConfirm = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

                    if(showConfirm.value) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showConfirm.value = false },
                            title = { Text("Clean up all downloads?") },
                            text = {
                                Text("This will delete all downloaded episode audio files (${app.podiumpodcasts.podium.ui.formatFileSize(totalSize.value)}) from your device.")
                            },
                            confirmButton = {
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        showConfirm.value = false
                                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            db.podcastEpisodeDownloads().allRandomSync().forEach { bundle ->
                                                bundle.episode?.let { ep ->
                                                    app.podiumpodcasts.podium.manager.DownloadManager.deleteEpisodeDownload(
                                                        context = context,
                                                        db = db,
                                                        episode = ep
                                                    )
                                                }
                                            }
                                        }
                                    }
                                ) {
                                    Text("Delete all")
                                }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = { showConfirm.value = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }

                    SettingsListItem(
                        icon = {
                            Icon(
                                Icons.Rounded.CleaningServices,
                                "Clean up downloaded episodes"
                            )
                        },
                        label = "Clean up downloaded episodes",
                        description = if(totalSize.value > 0L)
                            "${app.podiumpodcasts.podium.ui.formatFileSize(totalSize.value)} currently used"
                        else
                            "No downloaded episodes",
                        enabled = totalSize.value > 0L,
                        index = pos.index,
                        count = pos.count,
                        onClick = { showConfirm.value = true }
                    )
                }
            }
        }
    }

    RoamingWarningDialog(vm)
}