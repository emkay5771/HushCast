package app.podiumpodcasts.podium.ui.route.settings.pane

import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Start
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import app.podiumpodcasts.podium.ui.component.settings.SegmentedButtonLabel
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import app.podiumpodcasts.podium.AppColorPalette
import app.podiumpodcasts.podium.AppContrast
import app.podiumpodcasts.podium.AppDarkThemeStyle
import app.podiumpodcasts.podium.AppPaletteStyle
import app.podiumpodcasts.podium.AppThemeMode
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.ui.component.settings.SettingsGroupPosition
import app.podiumpodcasts.podium.ui.component.settings.SettingsGroupSurface
import app.podiumpodcasts.podium.ui.component.settings.SettingsListItem
import app.podiumpodcasts.podium.ui.component.settings.SettingsSwitchListItem
import app.podiumpodcasts.podium.ui.component.settings.settingsGroup
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository
import app.podiumpodcasts.podium.ui.navigation.NavBarItems
import app.podiumpodcasts.podium.ui.route.settings.SettingsPaneKey
import app.podiumpodcasts.podium.ui.theme.LocalDarkTheme
import app.podiumpodcasts.podium.ui.theme.applyDarkStyle
import app.podiumpodcasts.podium.ui.theme.rememberAppColorScheme
import app.podiumpodcasts.podium.ui.theme.rememberBaseColorScheme
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@kotlinx.parcelize.Parcelize
@Serializable
class SettingsAppearanceKey : SettingsPaneKey()

/** What the color sheet edits: a new custom seed color, or style/contrast of the current palette. */
private enum class ColorSheetMode { CUSTOM, FINE_TUNE }

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsAppearancePane(
    navigationIcon: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()

    val settingsRepository = LocalSettingsRepository.current
    val appearance = settingsRepository.appearance

    val themeMode by appearance.themeMode.collectAsState(AppThemeMode.AUTO)
    val darkThemeStyle by appearance.darkThemeStyle.collectAsState(AppDarkThemeStyle.DEFAULT)
    val colorPalette by appearance.colorPalette.collectAsState(AppColorPalette.DEFAULT)
    val dynamicColors by appearance.enableDynamicColors.collectAsState(true)
    val customSeedColor by appearance.customSeedColor.collectAsState(AppColorPalette.CUSTOM.seedColorHex)
    val paletteStyle by appearance.paletteStyle.collectAsState(AppPaletteStyle.TONAL)
    val contrast by appearance.contrast.collectAsState(AppContrast.STANDARD)
    val artworkColors by appearance.enableArtworkColors.collectAsState(true)
    val blurBackground by appearance.playerBlurBackground.collectAsState(false)
    val blurSheets by appearance.playerBlurSheets.collectAsState(true)
    val blurAvailable = rememberCrossWindowBlurEnabled()
    val bottomBarRaw by appearance.bottomBarItemsRaw.collectAsState("HOME,DISCOVER,LIBRARY")
    val enabledNavItems = NavBarItems.parseList(bottomBarRaw)

    val isDark = LocalDarkTheme.current
    var colorSheet by remember { mutableStateOf<ColorSheetMode?>(null) }

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
            // Preview + mode.  Laid out as in the R20 wireframe: no header above these two.
            settingsGroup(key = "preview", spaceAbove = 0.dp) {
                row { pos ->
                    SettingsGroupSurface(position = pos) {
                        HomeThemePreview(Modifier.fillMaxWidth())
                    }
                }
            }

            item(key = "mode") {
                ThemeModeButtons(
                    selected = themeMode,
                    onSelect = { mode -> scope.launch { appearance.setThemeMode(mode) } },
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            settingsGroup(header = "Colors", key = "colors") {
                row { pos ->
                    SettingsGroupSurface(
                        position = pos,
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        ColorSwatchRow(
                            isDark = isDark,
                            dynamicColors = dynamicColors,
                            colorPalette = colorPalette,
                            customSeedColor = customSeedColor,
                            onWallpaper = { scope.launch { appearance.setEnableDynamicColors(true) } },
                            onPalette = { palette ->
                                scope.launch {
                                    // Palette first, then leave Wallpaper, so the screen recolors once.
                                    appearance.setColorPalette(palette)
                                    appearance.setEnableDynamicColors(false)
                                }
                            },
                            onCustom = { colorSheet = ColorSheetMode.CUSTOM }
                        )
                    }
                }
                row { pos ->
                    SettingsListItem(
                        icon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                        label = "Fine-tune colors",
                        description = if (dynamicColors) {
                            "Not used with Wallpaper colors"
                        } else {
                            "${paletteStyle.label} style · ${contrast.label} contrast"
                        },
                        enabled = !dynamicColors,
                        index = pos.index,
                        count = pos.count,
                        onClick = { colorSheet = ColorSheetMode.FINE_TUNE }
                    )
                }
            }

            if (themeMode != AppThemeMode.LIGHT) {
                settingsGroup(header = "Dark background", key = "dark") {
                    row { pos ->
                        SettingsGroupSurface(position = pos) {
                            DarkBackgroundTiles(
                                selected = darkThemeStyle,
                                dynamicColors = dynamicColors,
                                colorPalette = colorPalette,
                                customSeedColor = customSeedColor,
                                paletteStyle = paletteStyle,
                                contrast = contrast,
                                onSelect = { style -> scope.launch { appearance.setDarkThemeStyle(style) } }
                            )
                        }
                    }
                }
            }

            settingsGroup(header = "Player", key = "player") {
                row { pos ->
                    SettingsSwitchListItem(
                        checked = artworkColors,
                        onCheckedChange = { checked -> scope.launch { appearance.setEnableArtworkColors(checked) } },
                        icon = { Icon(Icons.Rounded.Colorize, contentDescription = null) },
                        label = "Color player from artwork",
                        description = "Use each episode's artwork colors in the player and podcast pages",
                        index = pos.index,
                        count = pos.count
                    )
                }
                row { pos ->
                    SettingsSwitchListItem(
                        checked = blurBackground,
                        onCheckedChange = { checked -> scope.launch { appearance.setPlayerBlurBackground(checked) } },
                        icon = { Icon(Icons.Rounded.BlurOn, contentDescription = null) },
                        label = "Blurred artwork background",
                        description = "Show the artwork, softly blurred, behind the player",
                        index = pos.index,
                        count = pos.count
                    )
                }
                row { pos ->
                    SettingsSwitchListItem(
                        checked = blurSheets,
                        onCheckedChange = { checked -> scope.launch { appearance.setPlayerBlurSheets(checked) } },
                        icon = { Icon(Icons.Rounded.Layers, contentDescription = null) },
                        label = "Blur behind sheets",
                        description = if (blurAvailable) {
                            "Blur the player behind the speed, sleep timer, HushCast and Up Next sheets"
                        } else {
                            "Not available right now"
                        },
                        enabled = blurAvailable,
                        index = pos.index,
                        count = pos.count
                    )
                }
            }

            settingsGroup(header = "Navigation", key = "navigation") {
                row { pos ->
                    StartupScreenRow(position = pos)
                }
            }

            bottomNavigationGroup(
                enabledItems = enabledNavItems,
                onChange = { items ->
                    scope.launch { appearance.setBottomBarItemsRaw(NavBarItems.serializeList(items)) }
                }
            )
        }
    }

    colorSheet?.let { mode ->
        ColorSheet(
            mode = mode,
            isDark = isDark,
            darkThemeStyle = darkThemeStyle,
            colorPalette = colorPalette,
            dynamicColors = dynamicColors,
            customSeedColor = customSeedColor,
            paletteStyle = paletteStyle,
            contrast = contrast,
            onDismiss = { colorSheet = null },
            onSave = { seed, style, level ->
                colorSheet = null
                scope.launch {
                    if (mode == ColorSheetMode.CUSTOM) {
                        appearance.setCustomSeedColor(seed)
                    }
                    appearance.setPaletteStyle(style)
                    appearance.setContrast(level)
                    if (mode == ColorSheetMode.CUSTOM) {
                        appearance.setColorPalette(AppColorPalette.CUSTOM)
                        appearance.setEnableDynamicColors(false)
                    }
                }
            }
        )
    }
}

/* ---------------------------------------------------------------------------------------------
 * Mode
 * ------------------------------------------------------------------------------------------- */

private class ThemeModeOption(
    val mode: AppThemeMode,
    val label: String,
    val shortLabel: String,
    val icon: ImageVector
)

private val ThemeModeOptions = listOf(
    ThemeModeOption(AppThemeMode.AUTO, "Automatic", "Auto", Icons.Rounded.BrightnessAuto),
    ThemeModeOption(AppThemeMode.LIGHT, "Light", "Light", Icons.Rounded.LightMode),
    ThemeModeOption(AppThemeMode.DARK, "Dark", "Dark", Icons.Rounded.DarkMode)
)

/**
 * Automatic / Light / Dark.  On narrow screens or with large fonts the labels would be clipped
 * (a segmented button keeps room for its icon next to the label), so this measures them first:
 * full labels with icons when they fit, then "Auto" with icons, then "Auto" without icons.
 */
@Composable
private fun ThemeModeButtons(
    selected: AppThemeMode,
    onSelect: (AppThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val labelStyle = MaterialTheme.typography.labelLarge
        val density = LocalDensity.current
        val segmentWidth = maxWidth / ThemeModeOptions.size
        val (useShortLabels, showIcons) = remember(segmentWidth, labelStyle, density) {
            fun widest(labels: List<String>): Dp = with(density) {
                labels.maxOf { measurer.measure(it, labelStyle, maxLines = 1).size.width }.toDp()
            }
            // Icon (18 dp) + spacing (8 dp), and at least 8 dp of padding on each side.
            val iconRoom = 26.dp
            val padding = 16.dp
            val full = widest(ThemeModeOptions.map { it.label })
            val short = widest(ThemeModeOptions.map { it.shortLabel })
            when {
                full + iconRoom + padding <= segmentWidth -> false to true
                short + iconRoom + padding <= segmentWidth -> true to true
                else -> true to false
            }
        }
        app.podiumpodcasts.podium.ui.component.common.ConnectedSingleChoiceGroup(
            options = ThemeModeOptions,
            selected = ThemeModeOptions.first { it.mode == selected },
            onSelect = { onSelect(it.mode) }
        ) { option ->
            if (showIcons) {
                Icon(
                    imageVector = option.icon,
                    contentDescription = null,
                    modifier = Modifier.size(androidx.compose.material3.ButtonDefaults.IconSize)
                )
                Spacer(Modifier.size(androidx.compose.material3.ButtonDefaults.IconSpacing))
            }
            SegmentedButtonLabel(
                text = if (useShortLabels) option.shortLabel else option.label,
                hasIcon = showIcons
            )
        }
    }
}

/* ---------------------------------------------------------------------------------------------
 * Preview
 * ------------------------------------------------------------------------------------------- */

/**
 * A small live mock of Home (tiles, mini player, nav bar) drawn from the current
 * [MaterialTheme.colorScheme], so it recolors as soon as a choice is saved, or live inside the
 * color sheet where it is wrapped in the draft scheme.
 */
@Composable
private fun HomeThemePreview(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(cs.background)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp))
            .clearAndSetSemantics { contentDescription = "Preview of the Home screen in these colors" }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Podcasts",
                style = MaterialTheme.typography.titleSmall,
                color = cs.onBackground,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Rounded.Search, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PreviewTile(cs.primaryContainer, cs.primary, Modifier.weight(1f))
            PreviewTile(cs.secondaryContainer, cs.secondary, Modifier.weight(1f))
            PreviewTile(cs.tertiaryContainer, cs.tertiary, Modifier.weight(1f))
        }
        // Mini player
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(cs.surfaceContainerHigh)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(cs.tertiary)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PreviewLine(cs.onSurface, 0.7f)
                PreviewLine(cs.onSurfaceVariant, 0.45f)
            }
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(cs.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(18.dp))
            }
        }
        // Navigation bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(cs.surfaceContainer)
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PreviewNavItem(Icons.Rounded.Home, selected = true)
            PreviewNavItem(Icons.Rounded.Search, selected = false)
            PreviewNavItem(Icons.AutoMirrored.Rounded.ViewList, selected = false)
        }
    }
}

@Composable
private fun PreviewTile(container: Color, accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(container),
        contentAlignment = Alignment.BottomStart
    ) {
        Box(
            Modifier
                .padding(6.dp)
                .size(width = 22.dp, height = 5.dp)
                .clip(CircleShape)
                .background(accent)
        )
    }
}

@Composable
private fun PreviewLine(color: Color, fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth(fraction)
            .height(5.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.75f))
    )
}

@Composable
private fun PreviewNavItem(icon: ImageVector, selected: Boolean) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) cs.secondaryContainer else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

/* ---------------------------------------------------------------------------------------------
 * Colors
 * ------------------------------------------------------------------------------------------- */

private val SwatchSize = 48.dp
private val SwatchSlotWidth = 68.dp

/**
 * Swatch and dark-tile labels: six dark tiles share the row, so on a 360 dp screen each is about
 * 44 dp wide.  "Midnight" and large fonts would be cut off; shrink a little first.
 */
private val TileLabelAutoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 11.sp, stepSize = 0.5.sp)

@Composable
private fun ColorSwatchRow(
    isDark: Boolean,
    dynamicColors: Boolean,
    colorPalette: AppColorPalette,
    customSeedColor: Long,
    onWallpaper: () -> Unit,
    onPalette: (AppColorPalette) -> Unit,
    onCustom: () -> Unit
) {
    val palettes = remember { AppColorPalette.entries.filter { it != AppColorPalette.CUSTOM } }
    val selectedIndex = when {
        dynamicColors -> 0
        colorPalette == AppColorPalette.CUSTOM -> palettes.size + 1
        else -> palettes.indexOf(colorPalette) + 1
    }
    // Keep the current choice in view (settings load after the first frame, so this also
    // scrolls to the saved choice when the screen opens).
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex) {
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
        val fullyVisible = item != null &&
            item.offset >= layout.viewportStartOffset &&
            item.offset + item.size <= layout.viewportEndOffset
        if (!fullyVisible) listState.scrollToItem((selectedIndex - 2).coerceAtLeast(0))
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item(key = "wallpaper") {
            val wallpaper = rememberBaseColorScheme(
                darkTheme = isDark,
                dynamicColor = true,
                colorPalette = AppColorPalette.DEFAULT
            )
            ColorSwatch(
                label = "Wallpaper",
                selected = dynamicColors,
                onClick = onWallpaper
            ) {
                TwoToneCircle(wallpaper.primary, wallpaper.tertiary)
            }
        }
        items(palettes.size, key = { palettes[it].name }) { idx ->
            val palette = palettes[idx]
            val scheme = rememberBaseColorScheme(
                darkTheme = isDark,
                dynamicColor = false,
                colorPalette = palette
            )
            ColorSwatch(
                label = palette.label,
                selected = !dynamicColors && colorPalette == palette,
                onClick = { onPalette(palette) }
            ) {
                TwoToneCircle(scheme.primary, scheme.tertiary)
            }
        }
        item(key = "custom") {
            val hasCustom = colorPalette == AppColorPalette.CUSTOM
            ColorSwatch(
                label = "Custom",
                selected = !dynamicColors && hasCustom,
                onClick = onCustom
            ) {
                if (hasCustom) {
                    val scheme = rememberBaseColorScheme(
                        darkTheme = isDark,
                        dynamicColor = false,
                        colorPalette = AppColorPalette.CUSTOM,
                        customSeedColor = customSeedColor
                    )
                    TwoToneCircle(scheme.primary, scheme.tertiary)
                } else {
                    val outline = MaterialTheme.colorScheme.outline
                    Box(Modifier.size(SwatchSize), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(SwatchSize)) {
                            drawCircle(
                                color = outline,
                                radius = size.minDimension / 2f - 1.dp.toPx(),
                                style = Stroke(
                                    width = 1.5.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                                )
                            )
                        }
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorSwatch(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    swatch: @Composable () -> Unit
) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Column(
        modifier = Modifier
            .width(SwatchSlotWidth)
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier
                .size(SwatchSize + 8.dp)
                .border(BorderStroke(2.dp, ring), CircleShape)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            swatch()
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            autoSize = TileLabelAutoSize
        )
    }
}

/** A round swatch: primary on top, tertiary below. */
@Composable
private fun TwoToneCircle(top: Color, bottom: Color, size: androidx.compose.ui.unit.Dp = SwatchSize) {
    Canvas(Modifier.size(size)) {
        drawArc(color = top, startAngle = 180f, sweepAngle = 180f, useCenter = true)
        drawArc(color = bottom, startAngle = 0f, sweepAngle = 180f, useCenter = true)
    }
}

/* ---------------------------------------------------------------------------------------------
 * Dark background
 * ------------------------------------------------------------------------------------------- */

private fun AppDarkThemeStyle.shortLabel(): String = when (this) {
    AppDarkThemeStyle.DEFAULT -> "Default"
    AppDarkThemeStyle.DIM -> "Dim"
    AppDarkThemeStyle.AMOLED -> "Black"
    AppDarkThemeStyle.MIDNIGHT -> "Midnight"
    AppDarkThemeStyle.WARM_DARK -> "Warm"
    AppDarkThemeStyle.FOREST -> "Forest"
}

@Composable
private fun DarkBackgroundTiles(
    selected: AppDarkThemeStyle,
    dynamicColors: Boolean,
    colorPalette: AppColorPalette,
    customSeedColor: Long,
    paletteStyle: AppPaletteStyle,
    contrast: AppContrast,
    onSelect: (AppDarkThemeStyle) -> Unit
) {
    // The dark scheme of the current colors, so "Default" shows the palette's own dark background.
    val darkBase = rememberBaseColorScheme(
        darkTheme = true,
        dynamicColor = dynamicColors,
        colorPalette = colorPalette,
        customSeedColor = customSeedColor,
        paletteStyle = paletteStyle,
        contrast = contrast
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AppDarkThemeStyle.entries.forEach { style ->
            val scheme = remember(darkBase, style) { darkBase.applyDarkStyle(style) }
            DarkTile(
                label = style.shortLabel(),
                scheme = scheme,
                selected = selected == style,
                onClick = { onSelect(style) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun DarkTile(
    label: String,
    scheme: ColorScheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .border(if (selected) 2.dp else 1.dp, ring, RoundedCornerShape(12.dp))
                .padding(if (selected) 3.dp else 2.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(scheme.background),
            contentAlignment = Alignment.BottomCenter
        ) {
            // A card on the background, with an accent line, like a row in the app.
            Box(
                Modifier
                    .padding(5.dp)
                    .fillMaxWidth()
                    .height(16.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(scheme.surfaceContainerHigh),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    Modifier
                        .padding(start = 4.dp)
                        .size(width = 12.dp, height = 3.dp)
                        .clip(CircleShape)
                        .background(scheme.primary)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            autoSize = TileLabelAutoSize
        )
    }
}

/* ---------------------------------------------------------------------------------------------
 * Navigation
 * ------------------------------------------------------------------------------------------- */

@Composable
private fun StartupScreenRow(position: SettingsGroupPosition) {
    val scope = rememberCoroutineScope()
    val db = LocalDatabase.current
    val appearance = LocalSettingsRepository.current.appearance

    val startupDest by appearance.startupDestination.collectAsState("HOME")
    val libraryVm = viewModel { app.podiumpodcasts.podium.ui.vm.library.LibraryViewModel(db) }
    val userLists = libraryVm.nonSystemLists.collectAsLazyPagingItems()
    val systemLists = libraryVm.systemLists.collectAsLazyPagingItems()
    var showPicker by remember { mutableStateOf(false) }

    val destinations = buildList {
        NavBarItems.entries.forEach { add(it.name to it.displayTitle) }
        for (i in 0 until systemLists.itemCount) {
            systemLists[i]?.let { l -> add("LIST:${l.id}" to "Playlist: ${l.name}") }
        }
        for (i in 0 until userLists.itemCount) {
            userLists[i]?.let { l -> add("LIST:${l.id}" to "Playlist: ${l.name}") }
        }
    }
    val currentLabel = destinations.firstOrNull { it.first == startupDest }?.second ?: "Home"

    SettingsListItem(
        icon = { Icon(Icons.Rounded.Start, contentDescription = null) },
        label = "Startup screen",
        description = currentLabel,
        index = position.index,
        count = position.count,
        onClick = { showPicker = true }
    )

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Startup screen") },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .selectableGroup()
                ) {
                    destinations.forEach { (key, label) ->
                        val selected = startupDest == key
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        showPicker = false
                                        scope.launch { appearance.setStartupDestination(key) }
                                    }
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) { Text("Close") }
            }
        )
    }
}

/** "Bottom navigation bar": every tab as a grouped row with a checkbox and move buttons. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.bottomNavigationGroup(
    enabledItems: List<NavBarItems>,
    onChange: (List<NavBarItems>) -> Unit
) {
    // Shown tabs first (in bar order), then the hidden ones.
    val ordered = enabledItems + NavBarItems.entries.filter { it !in enabledItems }
    settingsGroup(
        header = "Bottom navigation bar",
        description = "Show 2–5 tabs and reorder them",
        key = "bottom_bar"
    ) {
        ordered.forEach { item ->
            row(key = item.name) { pos ->
                val enabledIdx = enabledItems.indexOf(item)
                val isEnabled = enabledIdx >= 0
                val canToggle = if (isEnabled) enabledItems.size > 2 else enabledItems.size < 5

                SettingsListItem(
                    modifier = Modifier.animateItem(
    fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
    fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec()
),
                    checked = isEnabled,
                    leadingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = isEnabled, onCheckedChange = null, enabled = canToggle)
                            Spacer(Modifier.width(12.dp))
                            Icon(item.icon, contentDescription = null)
                        }
                    },
                    label = item.displayTitle,
                    trailingContent = if (isEnabled) {
                        {
                            Row {
                                IconButton(
                                    enabled = enabledIdx > 0,
                                    onClick = {
                                        val mutable = enabledItems.toMutableList()
                                        mutable.add(enabledIdx - 1, mutable.removeAt(enabledIdx))
                                        onChange(mutable)
                                    }
                                ) {
                                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move ${item.displayTitle} up")
                                }
                                IconButton(
                                    enabled = enabledIdx < enabledItems.lastIndex,
                                    onClick = {
                                        val mutable = enabledItems.toMutableList()
                                        mutable.add(enabledIdx + 1, mutable.removeAt(enabledIdx))
                                        onChange(mutable)
                                    }
                                ) {
                                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move ${item.displayTitle} down")
                                }
                            }
                        }
                    } else null,
                    index = pos.index,
                    count = pos.count,
                    onClick = {
                        if (!canToggle) return@SettingsListItem
                        onChange(if (isEnabled) enabledItems - item else (enabledItems + item).take(5))
                    }
                )
            }
        }
    }
}

/* ---------------------------------------------------------------------------------------------
 * Custom color / fine-tune sheet
 * ------------------------------------------------------------------------------------------- */

private val SuggestedColors: List<Pair<String, Long>> = listOf(
    "Red" to 0xFFE53935,
    "Pink" to 0xFFD81B60,
    "Purple" to 0xFF8E24AA,
    "Violet" to 0xFF5E35B1,
    "Indigo" to 0xFF3949AB,
    "Blue" to 0xFF1E88E5,
    "Cyan" to 0xFF00ACC1,
    "Teal" to 0xFF00897B,
    "Green" to 0xFF43A047,
    "Lime" to 0xFF9E9D24,
    "Amber" to 0xFFFFB300,
    "Brown" to 0xFF6D4C41
)

private fun Long.toHex(): String = String.format(java.util.Locale.US, "%06X", this and 0xFFFFFF)

private fun parseHexColor(text: String): Long? {
    val clean = text.trim().removePrefix("#")
    if (clean.length != 6 || !clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return clean.toLongOrNull(16)?.let { it or 0xFF000000L }
}

private fun hueOf(argb: Long): Float {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb.toInt(), hsv)
    return hsv[0]
}

/** Moves [argb] to [hue], keeping enough saturation and brightness to make a usable seed. */
private fun withHue(argb: Long, hue: Float): Long {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb.toInt(), hsv)
    hsv[0] = hue
    hsv[1] = hsv[1].coerceAtLeast(0.45f)
    hsv[2] = hsv[2].coerceIn(0.45f, 0.9f)
    return android.graphics.Color.HSVToColor(hsv).toLong() and 0xFFFFFFFFL
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ColorSheet(
    mode: ColorSheetMode,
    isDark: Boolean,
    darkThemeStyle: AppDarkThemeStyle,
    colorPalette: AppColorPalette,
    dynamicColors: Boolean,
    customSeedColor: Long,
    paletteStyle: AppPaletteStyle,
    contrast: AppContrast,
    onDismiss: () -> Unit,
    onSave: (seed: Long, style: AppPaletteStyle, contrast: AppContrast) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Custom starts from the saved custom color, or from the palette in use.
    val initialSeed = remember {
        when {
            colorPalette == AppColorPalette.CUSTOM || dynamicColors -> customSeedColor
            else -> colorPalette.seedColorHex
        }
    }
    var draftSeed by remember { mutableStateOf(initialSeed) }
    var draftStyle by remember { mutableStateOf(paletteStyle) }
    var draftContrast by remember { mutableStateOf(contrast) }
    var hue by remember { mutableStateOf(hueOf(initialSeed)) }
    var hexText by remember { mutableStateOf(initialSeed.toHex()) }
    var hexError by remember { mutableStateOf(false) }

    fun setSeed(argb: Long, updateHex: Boolean = true) {
        draftSeed = argb or 0xFF000000L
        hue = hueOf(draftSeed)
        if (updateHex) {
            hexText = draftSeed.toHex()
            hexError = false
        }
    }

    val draftScheme = rememberAppColorScheme(
        darkTheme = isDark,
        dynamicColor = false,
        darkThemeStyle = darkThemeStyle,
        colorPalette = if (mode == ColorSheetMode.CUSTOM) AppColorPalette.CUSTOM else colorPalette,
        customSeedColor = if (mode == ColorSheetMode.CUSTOM) draftSeed else customSeedColor,
        paletteStyle = draftStyle,
        contrast = draftContrast
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (mode == ColorSheetMode.CUSTOM) "Custom color" else "Fine-tune colors",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }
            )

            MaterialTheme(colorScheme = draftScheme) {
                HomeThemePreview(Modifier.fillMaxWidth())
            }

            if (mode == ColorSheetMode.CUSTOM) {
                SheetLabel("Hue")
                HueBar()
                Slider(
                    value = hue,
                    onValueChange = { h ->
                        hue = h
                        draftSeed = withHue(draftSeed, h)
                        hexText = draftSeed.toHex()
                        hexError = false
                    },
                    valueRange = 0f..359f,
                    modifier = Modifier.semantics { stateDescription = "${hue.toInt()} degrees" }
                )

                SheetLabel("Suggested")
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 6
                ) {
                    SuggestedColors.forEach { (name, argb) ->
                        val selected = (draftSeed and 0xFFFFFF) == (argb and 0xFFFFFF)
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .selectable(selected = selected, role = Role.RadioButton, onClick = { setSeed(argb) })
                                .semantics { contentDescription = name }
                                .border(
                                    BorderStroke(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
                                    CircleShape
                                )
                                .padding(4.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                        )
                    }
                }

                OutlinedTextField(
                    value = hexText,
                    onValueChange = { text ->
                        val clean = text.removePrefix("#").take(6).uppercase()
                        hexText = clean
                        val parsed = parseHexColor(clean)
                        hexError = clean.length == 6 && parsed == null
                        if (parsed != null) setSeed(parsed, updateHex = false)
                    },
                    label = { Text("Hex color") },
                    prefix = { Text("#") },
                    singleLine = true,
                    isError = hexError,
                    supportingText = if (hexError) {
                        { Text("Use six characters, 0–9 and A–F") }
                    } else null,
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color(draftSeed))
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SheetLabel("Color style")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppPaletteStyle.entries.forEach { style ->
                    FilterChip(
                        selected = draftStyle == style,
                        onClick = { draftStyle = style },
                        label = { Text(style.label) }
                    )
                }
            }

            SheetLabel("Contrast")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppContrast.entries.forEach { level ->
                    FilterChip(
                        selected = draftContrast == level,
                        onClick = { draftContrast = level },
                        label = { Text(level.label) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !hexError,
                    onClick = { onSave(draftSeed, draftStyle, draftContrast) }
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** The hue spectrum drawn above the hue slider. */
@Composable
private fun HueBar() {
    val colors = remember {
        (0..12).map { i -> Color.hsv(hue = (i * 30f).coerceAtMost(359f), saturation = 0.75f, value = 0.85f) }
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .padding(horizontal = 10.dp)
            .clip(CircleShape)
    ) {
        drawRect(brush = Brush.horizontalGradient(colors), topLeft = Offset.Zero, size = Size(size.width, size.height))
    }
}

/* ---------------------------------------------------------------------------------------------
 * Blur availability
 * ------------------------------------------------------------------------------------------- */

/**
 * Whether the system currently allows cross-window blur.  Some devices turn it off, and battery
 * saver does too, so this listens for changes while the screen is shown.
 */
@Composable
private fun rememberCrossWindowBlurEnabled(): Boolean {
    val context = LocalContext.current
    val windowManager = remember(context) { context.getSystemService(WindowManager::class.java) }
    var enabled by remember(windowManager) { mutableStateOf(windowManager?.isCrossWindowBlurEnabled ?: false) }
    DisposableEffect(windowManager) {
        val wm = windowManager ?: return@DisposableEffect onDispose { }
        val listener = java.util.function.Consumer<Boolean> { enabled = it }
        wm.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
        onDispose { wm.removeCrossWindowBlurEnabledListener(listener) }
    }
    return enabled
}
