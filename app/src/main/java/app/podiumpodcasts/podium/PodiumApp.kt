package app.podiumpodcasts.podium

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.podiumpodcasts.podium.ui.DeepLink
import app.podiumpodcasts.podium.ui.Main
import app.podiumpodcasts.podium.ui.theme.PodiumTheme

@Composable
fun PodiumApp(
    deepLink: DeepLink?
) {
    val context = LocalContext.current
    val settingsRepository = remember(context) { SettingsRepository(context) }
    val themeMode by settingsRepository.appearance.themeMode.collectAsState(AppThemeMode.AUTO)
    val dynamicColors by settingsRepository.appearance.enableDynamicColors.collectAsState(true)

    val darkTheme = when (themeMode) {
        AppThemeMode.AUTO -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
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