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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VolumeUp
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.SettingsRepository
import app.podiumpodcasts.podium.ui.theme.Typography
import app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

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
    val settingsRepository = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    val trimSilence by settingsRepository.behavior.trimSilence.collectAsState(initial = false)
    val volumeBoost by settingsRepository.behavior.volumeBoost.collectAsState(initial = false)
    val speedPresets by settingsRepository.behavior.speedPresets.collectAsState(
        initial = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f)
    )

    var speed by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(vm.speed) { speed = vm.speed }

    fun updateSpeed(newSpeed: Float) {
        val clamped = ((newSpeed.coerceIn(0.5f, 3.0f) * 20f).roundToInt() / 20f)
        speed = clamped
        vm.setPlaybackSpeed(clamped)
        scope.launch {
            settingsRepository.behavior.setPlayerPlaybackSpeed(clamped)
        }
    }

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Playback Effects",
                style = Typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(16.dp))

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
                        onClick = { updateSpeed(speed - 0.1f) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Rounded.Remove, contentDescription = "Slower", modifier = Modifier.size(18.dp))
                    }
                    Text(
                        text = String.format(Locale.getDefault(), "%.2fx", speed),
                        color = MaterialTheme.colorScheme.primary,
                        style = Typography.titleLargeEmphasized,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    FilledTonalIconButton(
                        onClick = { updateSpeed(speed + 0.1f) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Faster", modifier = Modifier.size(18.dp))
                    }
                }
            }

            Slider(
                value = speed.coerceIn(0.5f, 3.0f),
                onValueChange = { updateSpeed(it) },
                valueRange = 0.5f..3.0f
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                speedPresets.forEach { preset ->
                    val selected = kotlin.math.abs(speed - preset) < 0.03f
                    FilterChip(
                        selected = selected,
                        onClick = { updateSpeed(preset) },
                        label = {
                            val formatted = if ((preset * 10f) % 10f == 0f) {
                                "${preset.toInt()}x"
                            } else {
                                String.format(Locale.getDefault(), "%.1fx", preset)
                            }
                            Text(formatted)
                        }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                    onCheckedChange = { enabled ->
                        scope.launch { settingsRepository.behavior.setTrimSilence(enabled) }
                    }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                    onCheckedChange = { enabled ->
                        scope.launch { settingsRepository.behavior.setVolumeBoost(enabled) }
                    }
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}