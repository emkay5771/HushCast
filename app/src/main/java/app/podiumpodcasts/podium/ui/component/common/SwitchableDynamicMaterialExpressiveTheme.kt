package app.podiumpodcasts.podium.ui.component.common

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.podiumpodcasts.podium.AppDarkThemeStyle
import app.podiumpodcasts.podium.ui.theme.LocalDarkTheme
import app.podiumpodcasts.podium.ui.theme.LocalDarkThemeStyle
import app.podiumpodcasts.podium.ui.theme.applyDarkStyle
import com.materialkolor.DynamicMaterialExpressiveTheme
import com.materialkolor.rememberDynamicColorScheme

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SwitchableDynamicMaterialExpressiveTheme(
    enable: Boolean,
    seedColor: Color,
    content: @Composable () -> Unit
) {
    val isDark = LocalDarkTheme.current
    val darkStyle = LocalDarkThemeStyle.current
    when(enable) {
        true -> {
            if (isDark && darkStyle != AppDarkThemeStyle.DEFAULT) {
                val scheme = rememberDynamicColorScheme(
                    seedColor = seedColor,
                    isDark = true
                ).applyDarkStyle(darkStyle)
                MaterialExpressiveTheme(
                    colorScheme = scheme,
                    content = content
                )
            } else {
                DynamicMaterialExpressiveTheme(
                    seedColor = seedColor,
                    isDark = isDark,
                    content = content
                )
            }
        }

        false -> content()
    }
}