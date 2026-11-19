package app.podiumpodcasts.podium.ui.route.settings.pane

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Publish
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.ui.component.settings.SettingsListItem
import app.podiumpodcasts.podium.ui.component.settings.settingsGroup
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository
import app.podiumpodcasts.podium.ui.route.settings.SettingsPaneKey
import app.podiumpodcasts.podium.ui.vm.ExportDatabaseState
import app.podiumpodcasts.podium.ui.vm.SettingsViewModel
import kotlinx.serialization.Serializable

@kotlinx.parcelize.Parcelize
@Serializable
class SettingsDatabaseKey : SettingsPaneKey()

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsDatabasePane(
    navigationIcon: @Composable () -> Unit,

    onRestore: () -> Unit,
    onOpmlImport: () -> Unit
) {
    val context = LocalContext.current

    val db = LocalDatabase.current
    val settingsRepository = LocalSettingsRepository.current

    val vm = viewModel { SettingsViewModel(db, settingsRepository) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = navigationIcon,

                title = {
                    Text(stringResource(R.string.route_settings_database_and_backup))
                }
            )
        }
    ) {
        val importExportHeader = stringResource(R.string.route_settings_import_export)

        LazyColumn(
            Modifier
                .padding(it)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),

            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
        ) {
            settingsGroup(key = "database", spaceAbove = 0.dp) {
                row { pos ->
                    val size = remember { vm.calculateDatabaseSize(context) ?: "" }

                    SettingsListItem(
                        icon = {
                            Icon(
                                Icons.Rounded.DataUsage,
                                stringResource(R.string.route_settings_database_storage_used)
                            )
                        },
                        label = stringResource(R.string.route_settings_database_storage_used),
                        description = size,
                        index = pos.index,
                        count = pos.count
                    ) {

                    }
                }
                row { pos ->
                    val state = vm.exportDatabaseState.value

                    val exportShareTitle =
                        stringResource(R.string.route_settings_database_export_share_title)

                    SettingsListItem(
                        icon = {
                            Icon(
                                Icons.Rounded.Save,
                                stringResource(R.string.route_settings_database_export)
                            )
                        },
                        label = stringResource(R.string.route_settings_database_export),
                        description = when(state) {
                            is ExportDatabaseState.Writing -> "${state.fileName} ..."
                            else -> stringResource(R.string.route_settings_database_export_description)
                        },

                        index = pos.index,
                        count = pos.count,

                        onClick = {
                            vm.exportAndShareDatabase(
                                context = context,
                                title = exportShareTitle
                            )
                        }
                    )
                }
                row { pos ->
                    SettingsListItem(
                        icon = {
                            Icon(
                                Icons.Rounded.Restore,
                                stringResource(R.string.route_settings_database_restore)
                            )
                        },
                        label = stringResource(R.string.route_settings_database_restore),
                        description = stringResource(
                            R.string.route_settings_database_restore_description,
                            stringResource(R.string.app_name)
                        ),

                        index = pos.index,
                        count = pos.count,

                        onClick = {
                            onRestore()
                        }
                    )
                }
            }



            settingsGroup(header = importExportHeader, key = "opml") {
                row { pos ->
                    val exportShareTitle =
                        stringResource(R.string.route_settings_import_export_export_as_opml_share_title)

                    SettingsListItem(
                        icon = {
                            Icon(
                                Icons.Rounded.Publish,
                                stringResource(R.string.route_settings_import_export_export_as_opml)
                            )
                        },
                        label = stringResource(R.string.route_settings_import_export_export_as_opml),
                        description = stringResource(R.string.route_settings_import_export_export_as_opml_description),

                        index = pos.index,
                        count = pos.count,

                        onClick = {
                            vm.exportAndShareOpml(
                                context = context,
                                title = exportShareTitle
                            )
                        }
                    )
                }
                row { pos ->
                    SettingsListItem(
                        icon = {
                            Icon(
                                Icons.Rounded.Download,
                                stringResource(R.string.route_settings_import_export_import_from_opml)
                            )
                        },
                        label = stringResource(R.string.route_settings_import_export_import_from_opml),
                        description = stringResource(R.string.route_settings_import_export_import_from_opml_description),

                        index = pos.index,
                        count = pos.count,

                        onClick = {
                            onOpmlImport()
                        }
                    )
                }
            }
        }
    }
}