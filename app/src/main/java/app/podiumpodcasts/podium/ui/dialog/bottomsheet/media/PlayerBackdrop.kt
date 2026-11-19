package app.podiumpodcasts.podium.ui.dialog.bottomsheet.media

import android.content.Context
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 * The player's blurred-artwork background (R19).  The artwork is loaded at 40 px, box-blurred
 * once, and drawn stretched behind the page, which costs about one frame per episode instead of a
 * live blur on every frame.  Returns null while loading or when [artworkUri] is null.
 */
@Composable
fun rememberBlurredArtwork(artworkUri: String?, enabled: Boolean): ImageBitmap? {
    val context = LocalContext.current
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(artworkUri, enabled) {
        if (artworkUri.isNullOrBlank()) {
            image = null
        } else if (enabled) {
            image = loadBlurred(context, artworkUri)
        }
    }
    return image
}

/**
 * Draws [image] stretched behind the content, under a scrim in [scrimColor] that is solid at the
 * top (so it meets the sheet's drag-handle band without a seam) and lighter lower down.
 */
fun Modifier.playerBackdrop(image: ImageBitmap?, scrimColor: Color, alpha: Float = 1f): Modifier {
    val clamped = alpha.coerceIn(0f, 1f)
    if (image == null || clamped <= 0f) return this
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

private suspend fun loadBlurred(context: Context, uri: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val request = ImageRequest.Builder(context)
            .data(uri)
            .size(Size(40, 40))
            .allowHardware(false)
            .build()
        val result = ImageLoader(context).execute(request) as? SuccessResult ?: return@runCatching null
        val small = Bitmap.createScaledBitmap(result.image.toBitmap(), 40, 40, true)
        boxBlur(small, radius = 4, passes = 3).asImageBitmap()
    }.getOrNull()
}

/** A few passes of a separable box blur; at 40 px this is effectively free. */
private fun boxBlur(src: Bitmap, radius: Int, passes: Int): Bitmap {
    val w = src.width
    val h = src.height
    var px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    var tmp = IntArray(w * h)
    repeat(passes) {
        blurLine(px, tmp, w, h, radius, horizontal = true)
        blurLine(tmp, px, w, h, radius, horizontal = false)
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

private fun blurLine(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    for (line in 0 until lines) {
        for (i in 0 until len) {
            var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
            for (k in -r..r) {
                val j = (i + k).coerceIn(0, len - 1)
                val c = if (horizontal) src[line * w + j] else src[j * w + line]
                a += (c ushr 24) and 0xFF; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
            }
            val v = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
            if (horizontal) dst[line * w + i] = v else dst[i * w + line] = v
        }
    }
}

/**
 * Blurs whatever is behind this sheet's window (R19, "Blur behind sheets"), where the device has
 * cross-window blur on.  Call it at the top of a player sub-sheet's content.  When the device or
 * battery saver turns blur off, the normal dim scrim shows instead.
 */
@Composable
fun BlurBehindSheetWindow(enabled: Boolean, radiusPx: Int = 48) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null && enabled) {
            val wm = view.context.getSystemService(WindowManager::class.java)
            if (wm?.isCrossWindowBlurEnabled == true) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply { blurBehindRadius = radiusPx }
            }
        }
        onDispose { }
    }
}

/** Applies the "Blur behind sheets" setting to the player sheet this is called from (R19). */
@Composable
fun PlayerSheetBlur() {
    val repo = app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository.current
    val enabled by repo.appearance.playerBlurSheets.collectAsState(initial = true)
    BlurBehindSheetWindow(enabled = enabled)
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
