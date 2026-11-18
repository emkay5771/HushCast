package app.podiumpodcasts.podium.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldState
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository

@Composable
fun NavBarScaffold(
    layoutType: NavigationSuiteType =
        NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo()).let {
            if (it == NavigationSuiteType.NavigationBar) NavigationSuiteType.ShortNavigationBarCompact else it
        },
    state: NavigationSuiteScaffoldState,

    currentNavKey: @Composable () -> NavKey,
    onClickItem: (item: NavBarItems) -> Unit,
    content: @Composable () -> Unit
) {
    val currentNavKey = currentNavKey()

    val settingsRepository = LocalSettingsRepository.current
    val bottomBarRaw = settingsRepository.appearance.bottomBarItemsRaw
        .collectAsStateWithLifecycle("HOME,DISCOVER,LIBRARY")
    val enabledItems = NavBarItems.parseList(bottomBarRaw.value)

    NavigationSuiteScaffold(
        layoutType = layoutType,
        state = state,
        containerColor = Color.Transparent,
        navigationSuiteItems = {
            enabledItems.forEach {
                item(
                    icon = {
                        Icon(
                            it.icon,
                            contentDescription = stringResource(it.label)
                        )
                    },
                    label = { Text(stringResource(it.label)) },
                    selected = currentNavKey == it.navKey,
                    onClick = {
                        onClickItem(it)
                    }
                )
            }
        },
        content = content
    )
}