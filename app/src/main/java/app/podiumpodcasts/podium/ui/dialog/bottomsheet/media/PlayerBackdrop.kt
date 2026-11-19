package app.podiumpodcasts.podium.ui.dialog.bottomsheet.media

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import app.podiumpodcasts.podium.ui.theme.applyDarkStyle
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders the player's blurred-artwork background (R19).
 *
 * Animates a GPU [Modifier.blur] radius via [animateDpAsState] alongside an alpha crossfade
 * using M3E `defaultEffectsSpec()`.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerBackdropLayer(
    artworkUri: String?,
    enabled: Boolean,
    scrimColor: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var artwork by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(artworkUri, enabled) {
        if (artworkUri.isNullOrBlank()) {
            artwork = null
        } else if (enabled) {
            artwork = loadArtworkBitmap(context, artworkUri, targetPx = 240)
        }
    }
    val blurRadius by animateDpAsState(
        targetValue = if (enabled && artwork != null) 48.dp else 0.dp,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "playerBackdropBlur"
    )
    val alpha by animateFloatAsState(
        targetValue = if (enabled && artwork != null) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "playerBackdropAlpha"
    )
    val bmp = artwork
    if (bmp != null && alpha > 0f) {
        Box(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(radius = blurRadius, edgeTreatment = BlurredEdgeTreatment.Rectangle)
                    .playerBackdropImage(bmp, alpha)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .playerBackdropScrim(scrimColor, alpha)
            )
        }
    }
}

private fun Modifier.playerBackdropImage(image: ImageBitmap, alpha: Float): Modifier {
    val clamped = alpha.coerceIn(0f, 1f)
    if (clamped <= 0f) return this
    return drawBehind {
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            alpha = 0.85f * clamped,
            filterQuality = FilterQuality.High
        )
    }
}

private fun Modifier.playerBackdropScrim(scrimColor: Color, alpha: Float): Modifier {
    val clamped = alpha.coerceIn(0f, 1f)
    if (clamped <= 0f) return this
    return drawBehind {
        drawRect(
            brush = Brush.verticalGradient(
                0f to scrimColor.copy(alpha = scrimColor.alpha * clamped),
                0.18f to scrimColor.copy(alpha = 0.55f * clamped),
                1f to scrimColor.copy(alpha = 0.70f * clamped),
                startY = 0f,
                endY = size.height
            ),
            topLeft = Offset.Zero,
            size = size
        )
    }
}

private suspend fun loadArtworkBitmap(context: Context, uri: String, targetPx: Int): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(uri)
                .size(Size(targetPx, targetPx))
                .allowHardware(false)
                .build()
            val result = ImageLoader(context).execute(request) as? SuccessResult ?: return@runCatching null
            Bitmap.createScaledBitmap(result.image.toBitmap(), targetPx, targetPx, true).asImageBitmap()
        }.getOrNull()
    }

/**
 * Blurs whatever is behind this sheet's window (R19, "Blur behind sheets"), where the device has
 * cross-window blur on.  Clears the flag when disabled, on dispose, or when cross-window blur is
 * turned off (e.g. Battery Saver).
 */
@Composable
fun BlurBehindSheetWindow(enabled: Boolean, radiusPx: Int = 48) {
    val view = LocalView.current
    val wm = remember(view) { view.context.getSystemService(WindowManager::class.java) }
    var crossWindowBlurEnabled by remember(wm) {
        mutableStateOf(wm?.isCrossWindowBlurEnabled == true)
    }

    DisposableEffect(wm) {
        if (wm == null) return@DisposableEffect onDispose { }
        val listener = java.util.function.Consumer<Boolean> { active ->
            crossWindowBlurEnabled = active
        }
        wm.addCrossWindowBlurEnabledListener(listener)
        onDispose {
            wm.removeCrossWindowBlurEnabledListener(listener)
        }
    }

    DisposableEffect(view, enabled, crossWindowBlurEnabled, radiusPx) {
        val window = (view.parent as? DialogWindowProvider)?.window
        fun applyBlur(active: Boolean) {
            if (window == null) return
            if (active) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply { blurBehindRadius = radiusPx }
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply { blurBehindRadius = 0 }
            }
        }
        applyBlur(enabled && crossWindowBlurEnabled)
        onDispose {
            applyBlur(false)
        }
    }
}

/** Applies the "Blur behind sheets" setting to the player sheet this is called from (R19). */
@Composable
fun PlayerSheetBlur() {
    val repo = app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository.current
    val enabled by repo.appearance.playerBlurSheets.collectAsState(initial = null)
    val flag = enabled ?: return
    BlurBehindSheetWindow(enabled = flag)
}

/**
 * Artwork color schemes, computed once per (seed, light/dark, dark style) and shared by the mini
 * player, the player sheet and Up Next, which each used to build the same palette (M6).
 */
internal object PlayerPalette {
    private data class Key(val seed: Int, val isDark: Boolean, val darkStyle: app.podiumpodcasts.podium.AppDarkThemeStyle)

    private val cache = object : LinkedHashMap<Key, androidx.compose.material3.ColorScheme>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, androidx.compose.material3.ColorScheme>?): Boolean =
            size > 8
    }

    /** Same palettes SwitchableDynamicMaterialExpressiveTheme produced, without recomputing them. */
    fun scheme(seed: Int, isDark: Boolean, darkStyle: app.podiumpodcasts.podium.AppDarkThemeStyle): androidx.compose.material3.ColorScheme {
        val key = Key(seed, isDark, darkStyle)
        synchronized(cache) { cache[key]?.let { return it } }
        val computed = if (isDark && darkStyle != app.podiumpodcasts.podium.AppDarkThemeStyle.DEFAULT) {
            com.materialkolor.dynamicColorScheme(seedColor = Color(seed), isDark = true)
                .applyDarkStyle(darkStyle)
        } else {
            com.materialkolor.dynamicColorScheme(
                seedColor = Color(seed),
                isDark = isDark,
                style = com.materialkolor.PaletteStyle.Expressive,
                specVersion = com.materialkolor.dynamiccolor.ColorSpec.SpecVersion.SPEC_2025
            )
        }
        synchronized(cache) { cache[key] = computed }
        return computed
    }
}

/**
 * The player's artwork theme (M6): the palette comes from [PlayerPalette] (computed once and
 * shared), and a new episode's colors cross-fade in instead of snapping.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerArtworkTheme(
    enable: Boolean,
    seedColor: Int?,
    content: @Composable () -> Unit
) {
    if (!enable) {
        content()
        return
    }
    val isDark = app.podiumpodcasts.podium.ui.theme.LocalDarkTheme.current
    val darkStyle = app.podiumpodcasts.podium.ui.theme.LocalDarkThemeStyle.current
    val seed = seedColor ?: 1
    val target = remember(seed, isDark, darkStyle) { PlayerPalette.scheme(seed, isDark, darkStyle) }
    val animated = com.materialkolor.ktx.animateColorScheme(target)
    androidx.compose.material3.MaterialExpressiveTheme(
        colorScheme = animated,
        content = content
    )
}
