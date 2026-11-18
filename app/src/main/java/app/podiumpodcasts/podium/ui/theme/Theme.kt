package app.podiumpodcasts.podium.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.podiumpodcasts.podium.AppColorPalette
import app.podiumpodcasts.podium.AppDarkThemeStyle
import com.materialkolor.rememberDynamicColorScheme

val LocalDarkTheme = staticCompositionLocalOf { false }
val LocalDarkThemeStyle = staticCompositionLocalOf { AppDarkThemeStyle.DEFAULT }

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

fun ColorScheme.applyDarkStyle(style: AppDarkThemeStyle): ColorScheme = when (style) {
    AppDarkThemeStyle.DEFAULT -> this
    AppDarkThemeStyle.DIM -> copy(
        background = Color(0xFF191D24),
        surface = Color(0xFF191D24),
        surfaceDim = Color(0xFF14181E),
        surfaceBright = Color(0xFF282E38),
        surfaceContainerLowest = Color(0xFF12151B),
        surfaceContainerLow = Color(0xFF1E232B),
        surfaceContainer = Color(0xFF232831),
        surfaceContainerHigh = Color(0xFF2B313C),
        surfaceContainerHighest = Color(0xFF333A46)
    )
    AppDarkThemeStyle.AMOLED -> copy(
        background = Color(0xFF000000),
        surface = Color(0xFF000000),
        surfaceDim = Color(0xFF000000),
        surfaceBright = Color(0xFF181818),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF0A0A0A),
        surfaceContainer = Color(0xFF121212),
        surfaceContainerHigh = Color(0xFF1A1A1A),
        surfaceContainerHighest = Color(0xFF222222)
    )
    AppDarkThemeStyle.MIDNIGHT -> copy(
        background = Color(0xFF0B111E),
        surface = Color(0xFF0B111E),
        surfaceDim = Color(0xFF080D17),
        surfaceBright = Color(0xFF1D2840),
        surfaceContainerLowest = Color(0xFF070B14),
        surfaceContainerLow = Color(0xFF101727),
        surfaceContainer = Color(0xFF141D30),
        surfaceContainerHigh = Color(0xFF1B263D),
        surfaceContainerHighest = Color(0xFF222F4A)
    )
    AppDarkThemeStyle.WARM_DARK -> copy(
        background = Color(0xFF1A1614),
        surface = Color(0xFF1A1614),
        surfaceDim = Color(0xFF14110F),
        surfaceBright = Color(0xFF2D2723),
        surfaceContainerLowest = Color(0xFF110E0C),
        surfaceContainerLow = Color(0xFF201C19),
        surfaceContainer = Color(0xFF25201D),
        surfaceContainerHigh = Color(0xFF2E2824),
        surfaceContainerHighest = Color(0xFF37302B)
    )
    AppDarkThemeStyle.FOREST -> copy(
        background = Color(0xFF0E1813),
        surface = Color(0xFF0E1813),
        surfaceDim = Color(0xFF0A120E),
        surfaceBright = Color(0xFF1E3027),
        surfaceContainerLowest = Color(0xFF080F0B),
        surfaceContainerLow = Color(0xFF131F19),
        surfaceContainer = Color(0xFF17251E),
        surfaceContainerHigh = Color(0xFF1F3027),
        surfaceContainerHighest = Color(0xFF273B30)
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PodiumTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    darkThemeStyle: AppDarkThemeStyle = AppDarkThemeStyle.DEFAULT,
    colorPalette: AppColorPalette = AppColorPalette.DEFAULT,
    content: @Composable () -> Unit
) {
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        colorPalette != AppColorPalette.DEFAULT -> {
            rememberDynamicColorScheme(
                seedColor = Color(colorPalette.seedColorHex),
                isDark = darkTheme
            )
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val colorScheme = if (darkTheme) {
        baseScheme.applyDarkStyle(darkThemeStyle)
    } else {
        baseScheme
    }

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalDarkThemeStyle provides darkThemeStyle,
        androidx.compose.foundation.LocalOverscrollFactory provides null
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}