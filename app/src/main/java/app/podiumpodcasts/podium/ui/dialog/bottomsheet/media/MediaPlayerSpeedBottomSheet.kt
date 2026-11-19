package app.podiumpodcasts.podium.ui.dialog.bottomsheet.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.SettingsRepository
import app.podiumpodcasts.podium.ui.theme.Typography
import app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** One formatter for the player and Settings ("1.0x", "1.25x"). */
fun formatPlaybackSpeed(speed: Float): String =
    app.podiumpodcasts.podium.ui.component.settings.formatSettingsPlaybackSpeed(speed)

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun MediaPlayerSpeedBottomSheet(
    onDismiss: () -> Unit
) {
    val vm = viewModel<MediaPlayerViewModel>()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val settingsRepository = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    val trimSilence by settingsRepository.behavior.trimSilence.collectAsStateWithLifecycle(initialValue = false)
    val volumeBoost by settingsRepository.behavior.volumeBoost.collectAsStateWithLifecycle(initialValue = false)
    val speedPresets by settingsRepository.behavior.speedPresets.collectAsStateWithLifecycle(
        initialValue = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f)
    )

    val origin = vm.metadataOrigin
    // null until loaded, so the scope toggle doesn't flash "All podcasts" first (I7).
    val savedPodcastSpeed by remember(origin) {
        if (!origin.isNullOrBlank()) settingsRepository.behavior.podcastPlaybackSpeed(origin)
        else kotlinx.coroutines.flow.flowOf(0f)
    }.collectAsStateWithLifecycle(initialValue = null)
    val globalSpeed by settingsRepository.behavior.playerPlaybackSpeed.collectAsStateWithLifecycle(initialValue = null)
    // The user's explicit pick in this sheet; otherwise the scope follows whether the podcast has its own speed.
    var scopeChoice by remember(origin) { mutableStateOf<Boolean?>(null) }
    val perPodcastScope = !origin.isNullOrBlank() &&
        (scopeChoice ?: app.podiumpodcasts.podium.background.PlaybackSpeedResolver.hasOverride(savedPodcastSpeed))

    var speed by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(vm.speed) { speed = vm.speed }

    fun clampSpeed(value: Float): Float =
        ((value.coerceIn(0.5f, 3.0f) * 20f).roundToInt() / 20f)

    /** Applies at once and saves to the chosen scope only (I7). */
    fun applyAndSaveSpeed(newSpeed: Float) {
        val clamped = clampSpeed(newSpeed)
        speed = clamped
        vm.setPlaybackSpeed(clamped)
        scope.launch {
            if (perPodcastScope && !origin.isNullOrBlank()) {
                settingsRepository.behavior.setPodcastPlaybackSpeed(origin, clamped)
            } else {
                settingsRepository.behavior.setPlayerPlaybackSpeed(clamped)
            }
        }
    }

    /** "This podcast" keeps the current speed as its own; "All podcasts" drops it and plays at the global speed. */
    fun chooseScope(podcast: Boolean) {
        if (origin.isNullOrBlank() || podcast == perPodcastScope) return
        scopeChoice = podcast
        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        scope.launch {
            if (podcast) {
                settingsRepository.behavior.setPodcastPlaybackSpeed(origin, clampSpeed(speed))
            } else {
                settingsRepository.behavior.setPodcastPlaybackSpeed(origin, 0f)
                val global = app.podiumpodcasts.podium.background.PlaybackSpeedResolver.resolve(null, globalSpeed)
                speed = global
                vm.setPlaybackSpeed(global)
            }
        }
    }

    // M3: closes with the slide-down animation.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss
    ) {
        PlayerSheetBlur()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Playback effects",
                style = Typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(12.dp))

            if (!origin.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
                ) {
                    ToggleButton(
                        checked = !perPodcastScope,
                        onCheckedChange = { if (it) chooseScope(podcast = false) },
                        shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { role = Role.RadioButton }
                    ) {
                        Text("All podcasts")
                    }
                    ToggleButton(
                        checked = perPodcastScope,
                        onCheckedChange = { if (it) chooseScope(podcast = true) },
                        shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { role = Role.RadioButton }
                    ) {
                        Text("This podcast")
                    }
                }
                Text(
                    text = if (perPodcastScope) "This podcast plays at its own speed."
                    else "Used by every podcast without its own speed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Spacer(Modifier.height(12.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        modifier = Modifier.size(24.dp),
                        imageVector = Icons.Rounded.Speed,
                        contentDescription = stringResource(R.string.common_playback_speed),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.common_playback_speed),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            applyAndSaveSpeed(speed - 0.05f)
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Rounded.Remove, contentDescription = "Slower by 0.05x", modifier = Modifier.size(20.dp))
                    }
                    Text(
                        text = formatPlaybackSpeed(speed),
                        color = MaterialTheme.colorScheme.primary,
                        style = Typography.titleLargeEmphasized.copy(fontFeatureSettings = "tnum"),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    FilledTonalIconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            applyAndSaveSpeed(speed + 0.05f)
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Faster by 0.05x", modifier = Modifier.size(20.dp))
                    }
                }
            }

            Slider(
                value = speed.coerceIn(0.5f, 3.0f),
                onValueChange = {
                    val clamped = clampSpeed(it)
                    if (clamped != speed) {
                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        speed = clamped
                        vm.setPlaybackSpeed(clamped)
                    }
                },
                onValueChangeFinished = {
                    applyAndSaveSpeed(speed)
                },
                valueRange = 0.5f..3.0f
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                speedPresets.forEach { preset ->
                    val selected = abs(speed - preset) < 0.03f
                    FilterChip(
                        selected = selected,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            applyAndSaveSpeed(preset)
                        },
                        label = { Text(formatPlaybackSpeed(preset)) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = trimSilence,
                        role = Role.Switch,
                        onValueChange = { enabled ->
                            haptic.performHapticFeedback(if (enabled) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                            scope.launch { settingsRepository.behavior.setTrimSilence(enabled) }
                        }
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = if (trimSilence) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Trim silence",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Skip silent gaps in speech",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = trimSilence,
                    onCheckedChange = null
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = volumeBoost,
                        role = Role.Switch,
                        onValueChange = { enabled ->
                            haptic.performHapticFeedback(if (enabled) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                            scope.launch { settingsRepository.behavior.setVolumeBoost(enabled) }
                        }
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VolumeUp,
                        contentDescription = null,
                        tint = if (volumeBoost) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Volume boost",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Make quiet voices louder and clearer",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = volumeBoost,
                    onCheckedChange = null
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}