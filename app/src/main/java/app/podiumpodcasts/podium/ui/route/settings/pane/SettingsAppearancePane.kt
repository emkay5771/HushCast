package app.podiumpodcasts.podium.ui.route.settings.pane

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import app.podiumpodcasts.podium.AppThemeMode
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.ui.component.settings.SettingsSwitchListItem
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository
import app.podiumpodcasts.podium.ui.route.settings.SettingsPaneKey
import app.podiumpodcasts.podium.ui.vm.SettingsViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@kotlinx.parcelize.Parcelize
@Serializable
class SettingsAppearanceKey : SettingsPaneKey()

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsAppearancePane(
    navigationIcon: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()

    val db = LocalDatabase.current
    val settingsRepository = LocalSettingsRepository.current

    val vm = viewModel { SettingsViewModel(db, settingsRepository) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = navigationIcon,

                title = {
                    Text(stringResource(R.string.route_settings_appearance))
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
                val themeMode = vm.repository.appearance.themeMode.collectAsState(
                    AppThemeMode.AUTO
                )
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text(
                        text = "Theme",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val options = listOf(
                            AppThemeMode.AUTO to "Automatic",
                            AppThemeMode.LIGHT to "Light",
                            AppThemeMode.DARK to "Dark"
                        )
                        options.forEachIndexed { idx, (mode, label) ->
                            SegmentedButton(
                                selected = themeMode.value == mode,
                                onClick = {
                                    scope.launch {
                                        vm.repository.appearance.setThemeMode(mode)
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = idx,
                                    count = options.size
                                )
                            ) {
                                Text(label)
                            }
                        }
                    }
                }
            }

            item {
                val enableDynamicColors =
                    vm.repository.appearance.enableDynamicColors.collectAsState(true)

                SettingsSwitchListItem(
                    checked = enableDynamicColors.value,
                    onCheckedChange = {
                        scope.launch {
                            vm.repository.appearance.setEnableDynamicColors(it)
                        }
                    },

                    icon = {
                        Icon(
                            Icons.Rounded.Colorize,
                            "Dynamic color"
                        )
                    },
                    label = "Dynamic color (Material You)",
                    description = "Adapt app colors to your device wallpaper on Android 12+",

                    index = 0,
                    count = 2
                )
            }

            item {
                val enableArtworkColors =
                    vm.repository.appearance.enableArtworkColors.collectAsState(true)

                SettingsSwitchListItem(
                    checked = enableArtworkColors.value,
                    onCheckedChange = {
                        scope.launch {
                            vm.repository.appearance.setEnableArtworkColors(it)
                        }
                    },

                    icon = {
                        Icon(
                            Icons.Rounded.Colorize,
                            stringResource(R.string.route_settings_appearance_enable_artwork_colors)
                        )
                    },
                    label = stringResource(R.string.route_settings_appearance_enable_artwork_colors),
                    description = stringResource(R.string.route_settings_appearance_enable_artwork_colors_description),

                    index = 1,
                    count = 2
                )
            }

            item {
                val startupDest = vm.repository.appearance.startupDestination.collectAsState("HOME")
                val libraryVm = viewModel { app.podiumpodcasts.podium.ui.vm.library.LibraryViewModel(db) }
                val userLists = libraryVm.nonSystemLists.collectAsLazyPagingItems()
                val systemLists = libraryVm.systemLists.collectAsLazyPagingItems()

                val destinations = buildList {
                    app.podiumpodcasts.podium.ui.navigation.NavBarItems.entries.forEach {
                        add(it.name to it.displayTitle)
                    }
                    for (i in 0 until systemLists.itemCount) {
                        systemLists[i]?.let { l -> add("LIST:${l.id}" to "Playlist: ${l.name}") }
                    }
                    for (i in 0 until userLists.itemCount) {
                        userLists[i]?.let { l -> add("LIST:${l.id}" to "Playlist: ${l.name}") }
                    }
                }

                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Startup screen",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Choose which tab or playlist opens when launching the app",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    destinations.forEach { (key, label) ->
                        val selected = startupDest.value == key
                        androidx.compose.material3.Surface(
                            onClick = {
                                scope.launch {
                                    vm.repository.appearance.setStartupDestination(key)
                                }
                            },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            color = if (selected) {
                                androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer
                            } else {
                                androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
                            }
                        ) {
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = selected,
                                    onClick = {
                                        scope.launch {
                                            vm.repository.appearance.setStartupDestination(key)
                                        }
                                    }
                                )
                                Text(
                                    text = label,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }

            item {
                val bottomBarRaw = vm.repository.appearance.bottomBarItemsRaw
                    .collectAsState("HOME,DISCOVER,LIBRARY")
                val enabledItems = app.podiumpodcasts.podium.ui.navigation.NavBarItems.parseList(bottomBarRaw.value)
                val disabledItems = app.podiumpodcasts.podium.ui.navigation.NavBarItems.entries
                    .filter { it !in enabledItems }

                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Bottom navigation bar",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Show 2–5 tabs and reorder them with the arrows",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    (enabledItems + disabledItems).forEach { navItem ->
                        val enabledIdx = enabledItems.indexOf(navItem)
                        val isEnabled = enabledIdx >= 0
                        androidx.compose.material3.Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.Checkbox(
                                    checked = isEnabled,
                                    enabled = if (isEnabled) enabledItems.size > 2 else enabledItems.size < 5,
                                    onCheckedChange = { checked ->
                                        val next = if (checked) {
                                            (enabledItems + navItem).take(5)
                                        } else {
                                            if (enabledItems.size > 2) enabledItems - navItem else enabledItems
                                        }
                                        scope.launch {
                                            vm.repository.appearance.setBottomBarItemsRaw(
                                                app.podiumpodcasts.podium.ui.navigation.NavBarItems.serializeList(next)
                                            )
                                        }
                                    }
                                )
                                Icon(
                                    imageVector = navItem.icon,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 10.dp)
                                )
                                Text(
                                    text = navItem.displayTitle,
                                    modifier = Modifier.weight(1f),
                                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                                )
                                if (isEnabled) {
                                    androidx.compose.material3.IconButton(
                                        enabled = enabledIdx > 0,
                                        onClick = {
                                            val mutable = enabledItems.toMutableList()
                                            val moved = mutable.removeAt(enabledIdx)
                                            mutable.add(enabledIdx - 1, moved)
                                            scope.launch {
                                                vm.repository.appearance.setBottomBarItemsRaw(
                                                    app.podiumpodcasts.podium.ui.navigation.NavBarItems.serializeList(mutable)
                                                )
                                            }
                                        }
                                    ) {
                                        Text("▲")
                                    }
                                    androidx.compose.material3.IconButton(
                                        enabled = enabledIdx < enabledItems.lastIndex,
                                        onClick = {
                                            val mutable = enabledItems.toMutableList()
                                            val moved = mutable.removeAt(enabledIdx)
                                            mutable.add(enabledIdx + 1, moved)
                                            scope.launch {
                                                vm.repository.appearance.setBottomBarItemsRaw(
                                                    app.podiumpodcasts.podium.ui.navigation.NavBarItems.serializeList(mutable)
                                                )
                                            }
                                        }
                                    ) {
                                        Text("▼")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}