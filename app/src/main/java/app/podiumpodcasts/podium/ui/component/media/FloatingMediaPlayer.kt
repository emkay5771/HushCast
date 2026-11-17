package app.podiumpodcasts.podium.ui.component.media

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Forward
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.ui.component.common.SwitchableDynamicMaterialExpressiveTheme
import app.podiumpodcasts.podium.ui.dialog.ShimmerAsyncImage
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository
import app.podiumpodcasts.podium.ui.theme.Typography
import app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel

val LocalFloatingMediaPlayerShown = compositionLocalOf { false }
val LocalFloatingMediaPlayerHeight = compositionLocalOf { 0.dp }

val FloatingMediaPlayerHeight = 70.dp
val FloatingMediaPlayerMaxWidth = 500.dp
val FloatingMediaPlayerBreakpoint = FloatingMediaPlayerMaxWidth + 200.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FloatingMediaPlayer(
    hide: Boolean = false,
    showMediaPlayerBottomSheet: Boolean = false,
    onMediaPlayerShownChange: (shown: Boolean) -> Unit,
    onOpenQueue: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val settingsRepository = LocalSettingsRepository.current
    val vm = viewModel<MediaPlayerViewModel>()
    val context = androidx.compose.ui.platform.LocalContext.current
    val autoMuteDb = remember(context) { app.hushcast.automute.storage.AutoMuteDatabase.getInstance(context) }
    val episodeId = vm.metadataEpisodeId ?: ""
    val muteEntities = autoMuteDb.dao().observeMuteSegmentsByEpisode(episodeId).collectAsState(initial = emptyList())
    val adEntities = autoMuteDb.dao().observeAdSegmentsByEpisode(episodeId).collectAsState(initial = emptyList())

    val enableArtworkColors = settingsRepository.appearance.enableArtworkColors.collectAsState(true)

    val showMediaPlayer = remember { mutableStateOf(false) }
    LaunchedEffect(vm.isPlayerVisible, showMediaPlayerBottomSheet) {
        showMediaPlayer.value = vm.isPlayerVisible && !showMediaPlayerBottomSheet
        onMediaPlayerShownChange(showMediaPlayer.value)
    }

    val progressState = vm.getProgressState()

    BoxWithConstraints {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = when(maxWidth > FloatingMediaPlayerBreakpoint) {
                true -> Alignment.BottomStart
                false -> Alignment.BottomEnd
            }
        ) {
            AnimatedVisibility(
                visible = showMediaPlayer.value && !hide,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it }
            ) {
                SwitchableDynamicMaterialExpressiveTheme(
                    enable = enableArtworkColors.value,
                    seedColor = Color(vm.metadataImageSeedColor ?: 1)
                ) {
                    val cardShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                    ElevatedCard(
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .widthIn(min = 50.dp, max = FloatingMediaPlayerMaxWidth)
                            .height(FloatingMediaPlayerHeight)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape)
                            .pointerInput(Unit) {
                                val thresholdPx = 40.dp.toPx()
                                var totalDrag = 0f
                                detectVerticalDragGestures(
                                    onDragStart = { totalDrag = 0f },
                                    onVerticalDrag = { _, dragAmount ->
                                        totalDrag += dragAmount
                                        if(totalDrag < -thresholdPx) {
                                            totalDrag = 0f
                                            onClick()
                                        }
                                    }
                                )
                            },
                        shape = cardShape,
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        onClick = onClick
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AnimatedContent(vm.isLoading) { isLoading ->
                                    when(isLoading) {
                                        true -> {
                                            ContainedLoadingIndicator(
                                                Modifier.size(48.dp)
                                            )
                                        }

                                        false -> {
                                            AnimatedContent(vm.mediaMetadata) { mediaMetadata ->
                                                ShimmerAsyncImage(
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),

                                                    model = mediaMetadata?.artworkUri,
                                                    contentDescription = mediaMetadata?.title?.toString(),

                                                    contentScale = ContentScale.Crop
                                                )
                                            }
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        modifier = Modifier.size(40.dp),
                                        onClick = { vm.seekBack() }
                                    ) {
                                        Icon(
                                            modifier = Modifier.size(26.dp),
                                            imageVector = when(vm.seekBackIncrement) {
                                                5000L -> Icons.Rounded.Replay5
                                                10000L -> Icons.Rounded.Replay10
                                                30000L -> Icons.Rounded.Replay30
                                                else -> Icons.Rounded.Replay
                                            },
                                            contentDescription = "Skip back"
                                        )
                                    }

                                    FilledTonalIconToggleButton(
                                        modifier = Modifier.size(46.dp),
                                        checked = vm.isPlaying,
                                        onCheckedChange = {
                                            if(vm.isPlaying) {
                                                vm.pause()
                                            } else {
                                                vm.play()
                                            }
                                        }
                                    ) {
                                        AnimatedContent(vm.isPlaying) { playing ->
                                            Icon(
                                                modifier = Modifier.size(28.dp),
                                                imageVector = when(playing) {
                                                    true -> Icons.Rounded.Pause
                                                    false -> Icons.Rounded.PlayArrow
                                                },
                                                contentDescription = if (playing) "Pause" else "Play"
                                            )
                                        }
                                    }

                                    IconButton(
                                        modifier = Modifier.size(40.dp),
                                        onClick = { vm.seekForward() }
                                    ) {
                                        Icon(
                                            modifier = Modifier.size(26.dp),
                                            imageVector = when(vm.seekForwardIncrement) {
                                                5000L -> Icons.Rounded.Forward5
                                                10000L -> Icons.Rounded.Forward10
                                                30000L -> Icons.Rounded.Forward30
                                                else -> Icons.Rounded.Forward
                                            },
                                            contentDescription = "Skip forward"
                                        )
                                    }
                                }

                                onOpenQueue?.let { openQueue ->
                                    androidx.compose.material3.Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        onClick = openQueue
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(
                                                horizontal = if (vm.queue.isNotEmpty()) 10.dp else 8.dp,
                                                vertical = 6.dp
                                            ),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            if (vm.queue.isNotEmpty()) {
                                                Text(
                                                    text = if (vm.queue.size > 999) "999+" else vm.queue.size.toString(),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    maxLines = 1
                                                )
                                            }
                                            Icon(
                                                modifier = Modifier.size(18.dp),
                                                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                                contentDescription = "Up Next"
                                            )
                                        }
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.5.dp)
                            ) {
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { progressState.value },
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                )

                                val durMs = vm.currentDuration
                                if (durMs > 0L && (adEntities.value.isNotEmpty() || muteEntities.value.isNotEmpty())) {
                                    val adColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.75f)
                                    val suggestedAdColor = Color(0xFFFFB300).copy(alpha = 0.8f)
                                    val muteTickColor = MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                                    androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
                                        val w = size.width
                                        val h = size.height
                                        val dur = durMs.toFloat().coerceAtLeast(1f)
                                        for (ad in adEntities.value) {
                                            val x0 = (ad.startMs / dur).coerceIn(0f, 1f) * w
                                            val x1 = (ad.endMs / dur).coerceIn(0f, 1f) * w
                                            drawRect(
                                                color = if (ad.confidence >= 0.7f) adColor else suggestedAdColor,
                                                topLeft = androidx.compose.ui.geometry.Offset(x0, 0f),
                                                size = androidx.compose.ui.geometry.Size((x1 - x0).coerceAtLeast(3f), h)
                                            )
                                        }
                                        for (mute in muteEntities.value) {
                                            if (!mute.enabled) continue
                                            val x = (mute.startMs / dur).coerceIn(0f, 1f) * w
                                            drawRect(
                                                color = muteTickColor,
                                                topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
                                                size = androidx.compose.ui.geometry.Size(3f, h)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FloatingMediaPlayerSpacer(
    additionalHeight: Dp = 0.dp
) {
    Spacer(
        Modifier.height(LocalFloatingMediaPlayerHeight.current + additionalHeight)
    )
}