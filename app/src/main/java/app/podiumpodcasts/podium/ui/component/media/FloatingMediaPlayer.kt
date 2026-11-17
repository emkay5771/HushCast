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
                                                    contentDescription = null,

                                                    contentScale = ContentScale.Crop
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.width(12.dp))

                                Column(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    AnimatedContent(vm.mediaMetadata) { mediaMetadata ->
                                        Column {
                                            Text(
                                                text = mediaMetadata?.title?.toString().orEmpty(),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = Typography.titleMediumEmphasized
                                            )

                                            Text(
                                                text = mediaMetadata?.subtitle?.toString().orEmpty(),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = Typography.bodySmall
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.width(6.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    FilledTonalIconToggleButton(
                                        modifier = Modifier.size(40.dp),
                                        checked = vm.isPlaying,
                                        onCheckedChange = {
                                            if(vm.isPlaying) {
                                                vm.pause()
                                            } else {
                                                vm.play()
                                            }
                                        }
                                    ) {
                                        AnimatedContent(vm.isPlaying) {
                                            Icon(
                                                imageVector = when(it) {
                                                    true -> Icons.Rounded.Pause
                                                    false -> Icons.Rounded.PlayArrow
                                                },
                                                contentDescription = null
                                            )
                                        }
                                    }

                                    onOpenQueue?.let { openQueue ->
                                        IconButton(
                                            modifier = Modifier.size(36.dp),
                                            onClick = openQueue
                                        ) {
                                            Icon(
                                                modifier = Modifier.size(20.dp),
                                                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                                contentDescription = "Up Next"
                                            )
                                        }
                                    }
                                }
                            }

                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { progressState.value },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.5.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
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