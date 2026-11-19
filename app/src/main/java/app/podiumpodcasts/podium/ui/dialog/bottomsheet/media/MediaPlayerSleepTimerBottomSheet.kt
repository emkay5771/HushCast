package app.podiumpodcasts.podium.ui.dialog.bottomsheet.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hushcast.automute.dsp.SkipAwareSeekCalculator
import app.hushcast.automute.storage.AutoMuteDatabase
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.chapters.EpisodeChapterHelper
import app.podiumpodcasts.podium.api.db.AppDatabase
import app.podiumpodcasts.podium.background.HushCastEngineBridge
import app.podiumpodcasts.podium.ui.theme.Typography
import app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun formatTimerDuration(wallMs: Long): String {
    val totalMin = ((wallMs + 30_000L) / 60_000L).coerceAtLeast(1L)
    val h = totalMin / 60L
    val m = totalMin % 60L
    return when {
        h > 0L && m > 0L -> "${h}h ${m}m"
        h > 0L -> "${h}h"
        else -> "${m}m"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaPlayerSleepTimerBottomSheet(
    onDismiss: () -> Unit
) {
    val vm = viewModel<MediaPlayerViewModel>()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val appDb = remember(context) { app.podiumpodcasts.podium.manager.DatabaseManager.build(context) }
    val autoMuteDb = remember(context) { AutoMuteDatabase.getInstance(context) }
    val episodeId = vm.metadataEpisodeId ?: ""
    val durationMs = vm.currentDuration.coerceAtLeast(0L)

    val chapters by remember(episodeId, durationMs) {
        if (episodeId.isNotBlank()) {
            EpisodeChapterHelper.observeResolvedChapters(appDb, autoMuteDb, episodeId, durationMs)
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val sleepTimerMinutes = vm.getSleepTimerState().value
    val sleepTimerTriggerMs = vm.sleepTimerTrigger
    val isSleepTimerEnabled = sleepTimerMinutes != null

    val livePos = vm.rememberLivePositionMs()
    val skipRanges = HushCastEngineBridge.getActiveSkipRanges()
    val speed = vm.speed.coerceAtLeast(0.5f)

    val currentOrNextChapterEndMs = remember(chapters, livePos, durationMs) {
        if (chapters.isEmpty()) null
        else {
            val active = chapters.firstOrNull { livePos in it.startMs until it.endMs }
                ?: chapters.firstOrNull { it.startMs > livePos }
            active?.endMs ?: durationMs.takeIf { it > livePos }
        }
    }

    val endOfChapterWallMs = remember(currentOrNextChapterEndMs, livePos, speed, skipRanges) {
        currentOrNextChapterEndMs?.let { target ->
            SkipAwareSeekCalculator.computeWallRemainingMs(livePos, target, speed, skipRanges)
                .coerceAtLeast(1000L)
        }
    }

    val endOfEpisodeWallMs = remember(durationMs, livePos, speed, skipRanges) {
        if (durationMs > livePos + 1000L) {
            SkipAwareSeekCalculator.computeWallRemainingMs(livePos, durationMs, speed, skipRanges)
                .coerceAtLeast(1000L)
        } else null
    }

    var showCustom by remember { mutableStateOf(false) }
    var customMinutes by remember { mutableFloatStateOf(20f) }

    // M3: picking a timer closes the sheet with its slide-down animation.
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    val heldByPause = vm.isSleepTimerHeldByPause

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss
    ) {
        PlayerSheetBlur()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        modifier = Modifier.size(28.dp),
                        imageVector = Icons.Rounded.Bedtime,
                        contentDescription = stringResource(R.string.common_sleep_timer),
                        tint = if (isSleepTimerEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (isSleepTimerEnabled) {
                                stringResource(R.string.template_min, sleepTimerMinutes ?: 1)
                            } else {
                                stringResource(R.string.common_sleep_timer_off)
                            },
                            color = if (isSleepTimerEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            style = Typography.headlineSmallEmphasized.copy(fontFeatureSettings = "tnum")
                        )
                        // Item 6: end of chapter/episode waits while paused instead of running down.
                        if (isSleepTimerEnabled && heldByPause) {
                            Text(
                                text = "Paused · counts down while playing",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (isSleepTimerEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                val base = maxOf(System.currentTimeMillis(), sleepTimerTriggerMs ?: System.currentTimeMillis())
                                vm.setSleepTimer(base + 5L * 60_000L)
                            }
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("5 min")
                        }
                        OutlinedButton(
                            onClick = {
                                vm.setSleepTimer(null)
                                close()
                            }
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Turn off sleep timer", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()

            listOf(
                15 to "15 minutes",
                30 to "30 minutes",
                60 to "1 hour"
            ).forEach { (mins, label) ->
                val key = "${mins}m"
                val isSelected = isSleepTimerEnabled && vm.sleepTimerPresetKey == key
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(label) },
                    trailingContent = if (isSelected) {
                        {
                            Icon(
                                androidx.compose.material.icons.Icons.Rounded.Check,
                                contentDescription = "Active",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null,
                    modifier = Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        vm.setSleepTimer(System.currentTimeMillis() + mins * 60_000L, presetKey = key)
                        close()
                    }
                )
            }

            if (currentOrNextChapterEndMs != null && endOfChapterWallMs != null) {
                val isSelected = isSleepTimerEnabled && vm.sleepTimerPresetKey == "END_OF_CHAPTER"
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text("End of chapter · ${formatTimerDuration(endOfChapterWallMs)}")
                    },
                    trailingContent = if (isSelected) {
                        {
                            Icon(
                                androidx.compose.material.icons.Icons.Rounded.Check,
                                contentDescription = "Active",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null,
                    modifier = Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        vm.setSleepTimerEndOfChapter(currentOrNextChapterEndMs, endOfChapterWallMs)
                        close()
                    }
                )
            }

            if (endOfEpisodeWallMs != null) {
                val isSelected = isSleepTimerEnabled && vm.sleepTimerPresetKey == "END_OF_EPISODE"
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text("End of episode · ${formatTimerDuration(endOfEpisodeWallMs)}")
                    },
                    trailingContent = if (isSelected) {
                        {
                            Icon(
                                androidx.compose.material.icons.Icons.Rounded.Check,
                                contentDescription = "Active",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null,
                    modifier = Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        vm.setSleepTimerEndOfEpisode(endOfEpisodeWallMs)
                        close()
                    }
                )
            }

            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Custom") },
                trailingContent = {
                    if (showCustom) {
                        Text(
                            text = "${customMinutes.roundToInt()} min",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                },
                modifier = Modifier.clickable { showCustom = !showCustom }
            )

            AnimatedVisibility(visible = showCustom) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Slider(
                        value = customMinutes,
                        onValueChange = { customMinutes = it.roundToInt().toFloat() },
                        valueRange = 5f..120f,
                        steps = 22
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                vm.setSleepTimer(System.currentTimeMillis() + customMinutes.roundToInt() * 60_000L)
                                close()
                            }
                        ) {
                            Text("Start timer")
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}