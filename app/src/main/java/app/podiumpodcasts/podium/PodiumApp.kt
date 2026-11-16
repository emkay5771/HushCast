package app.podiumpodcasts.podium

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.podiumpodcasts.podium.ui.DeepLink
import app.podiumpodcasts.podium.ui.Main
import app.podiumpodcasts.podium.ui.theme.PodiumTheme

@Composable
fun PodiumApp(
    deepLink: DeepLink?
) {
    val context = LocalContext.current
    val view = LocalView.current
    val settingsRepository = remember(context) { SettingsRepository(context) }
    val themeMode by settingsRepository.appearance.themeMode.collectAsState(AppThemeMode.AUTO)
    val dynamicColors by settingsRepository.appearance.enableDynamicColors.collectAsState(true)

    val darkTheme = when (themeMode) {
        AppThemeMode.AUTO -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    PodiumTheme(
        darkTheme = darkTheme,
        dynamicColor = dynamicColors
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Main(deepLink)
        }
    }
}