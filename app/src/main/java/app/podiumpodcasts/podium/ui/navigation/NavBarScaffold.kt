package app.podiumpodcasts.podium.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.Icon
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldValue
import androidx.compose.ui.Modifier
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
    layoutType: NavigationSuiteType = appNavigationSuiteType(),
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
                val selected = currentNavKey == it.navKey
                item(
                    icon = {
                        // D7: filled for the selected tab, outlined for the others.  The label
                        // already names the tab, so the icon itself is decorative.
                        Icon(
                            if (selected) it.icon else it.unselectedIcon,
                            contentDescription = null
                        )
                    },
                    label = { Text(stringResource(it.label)) },
                    selected = selected,
                    onClick = {
                        onClickItem(it)
                    }
                )
            }
        },
        content = {
            // R14: the bottom bar already handles the system navigation bar inset.  Consume it for
            // the screens inside, or their own Scaffolds add it again and leave a dark strip above
            // the bar (lists then can't scroll under the mini player).
            val consumeNavBarInset = layoutType.isBottomBar() &&
                state.currentValue == NavigationSuiteScaffoldValue.Visible
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (consumeNavBarInset) Modifier.consumeWindowInsets(WindowInsets.navigationBars)
                        else Modifier
                    ),
                propagateMinConstraints = true
            ) {
                content()
            }
        }
    )
}

/**
 * R15: M3 Expressive's layout choice: the short navigation bar on phones (wide, icon beside label,
 * in landscape) and the wide navigation rail on tablets and foldables.  One place, used by both
 * [NavBarScaffold] and Main.
 */
@Composable
fun appNavigationSuiteType(): NavigationSuiteType =
    NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfo())

fun NavigationSuiteType.isBottomBar(): Boolean =
    this == NavigationSuiteType.ShortNavigationBarCompact ||
        this == NavigationSuiteType.ShortNavigationBarMedium ||
        this == NavigationSuiteType.NavigationBar