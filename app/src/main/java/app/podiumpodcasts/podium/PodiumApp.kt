package app.podiumpodcasts.podium

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podiumpodcasts.podium.ui.DeepLink
import app.podiumpodcasts.podium.ui.Main
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository
import app.podiumpodcasts.podium.ui.theme.PodiumTheme

@Composable
fun PodiumApp(
    deepLink: DeepLink?
) {
    val view = LocalView.current
    val settingsRepository = LocalSettingsRepository.current
    val themeMode by settingsRepository.appearance.themeMode.collectAsStateWithLifecycle(AppThemeMode.AUTO)
    val darkThemeStyle by settingsRepository.appearance.darkThemeStyle.collectAsStateWithLifecycle(AppDarkThemeStyle.DEFAULT)
    val colorPalette by settingsRepository.appearance.colorPalette.collectAsStateWithLifecycle(AppColorPalette.DEFAULT)
    val dynamicColors by settingsRepository.appearance.enableDynamicColors.collectAsStateWithLifecycle(true)
    val customSeedColor by settingsRepository.appearance.customSeedColor.collectAsStateWithLifecycle(AppColorPalette.CUSTOM.seedColorHex)
    val paletteStyle by settingsRepository.appearance.paletteStyle.collectAsStateWithLifecycle(AppPaletteStyle.TONAL)
    val contrast by settingsRepository.appearance.contrast.collectAsStateWithLifecycle(AppContrast.STANDARD)

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
        dynamicColor = dynamicColors,
        darkThemeStyle = darkThemeStyle,
        colorPalette = colorPalette,
        customSeedColor = customSeedColor,
        paletteStyle = paletteStyle,
        contrast = contrast
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