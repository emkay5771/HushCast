package app.podiumpodcasts.podium.ui.component.media

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.podiumpodcasts.podium.ui.component.common.SwitchableDynamicMaterialExpressiveTheme
import app.podiumpodcasts.podium.ui.dialog.ShimmerAsyncImage
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository
import app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel
import kotlinx.coroutines.launch

val LocalFloatingMediaPlayerShown = compositionLocalOf { false }
val LocalFloatingMediaPlayerHeight = compositionLocalOf { 0.dp }

val FloatingMediaPlayerHeight = 72.dp
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
    val scope = rememberCoroutineScope()
    val settingsRepository = LocalSettingsRepository.current
    val vm = viewModel<MediaPlayerViewModel>()
    val context = androidx.compose.ui.platform.LocalContext.current
    val autoMuteDb = remember(context) { app.hushcast.automute.storage.AutoMuteDatabase.getInstance(context) }
    val episodeId = vm.metadataEpisodeId ?: ""
    val muteEntities = remember(episodeId) {
        autoMuteDb.dao().observeMuteSegmentsByEpisode(episodeId)
    }.collectAsState(initial = emptyList())
    val adEntities = remember(episodeId) {
        autoMuteDb.dao().observeAdSegmentsByEpisode(episodeId)
    }.collectAsState(initial = emptyList())

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
            val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
            val motionScheme = MaterialTheme.motionScheme
            AnimatedVisibility(
                visible = showMediaPlayer.value && !hide,
                enter = fadeIn(animationSpec = motionScheme.defaultEffectsSpec()) +
                    scaleIn(initialScale = 0.94f, animationSpec = motionScheme.defaultSpatialSpec()) +
                    slideInVertically(animationSpec = motionScheme.defaultSpatialSpec()) { it },
                exit = fadeOut(animationSpec = motionScheme.fastEffectsSpec()) +
                    scaleOut(targetScale = 0.94f, animationSpec = motionScheme.defaultSpatialSpec()) +
                    slideOutVertically(animationSpec = motionScheme.defaultSpatialSpec()) { it }
            ) {
                app.podiumpodcasts.podium.ui.dialog.bottomsheet.media.PlayerArtworkTheme(
                    enable = enableArtworkColors.value,
                    seedColor = vm.metadataImageSeedColor
                ) {
                    val cardShape = RoundedCornerShape(20.dp)
                    ElevatedCard(
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .widthIn(min = 50.dp, max = FloatingMediaPlayerMaxWidth)
                            .height(FloatingMediaPlayerHeight)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, cardShape)
                            .pointerInput(Unit) {
                                val thresholdPx = 40.dp.toPx()
                                var totalDrag = 0f
                                var triggered = false
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        totalDrag = 0f
                                        triggered = false
                                    },
                                    onVerticalDrag = { _, dragAmount ->
                                        if (triggered) return@detectVerticalDragGestures
                                        totalDrag += dragAmount
                                        if (totalDrag < -thresholdPx) {
                                            triggered = true
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate)
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
                                val artworkScale by animateFloatAsState(
                                    targetValue = if (vm.isPlaying) 1f else 0.93f,
                                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                                    label = "artworkScale"
                                )
                                val artworkCorner by animateDpAsState(
                                    targetValue = if (vm.isPlaying) 12.dp else 9.dp,
                                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                                    label = "artworkCorner"
                                )
                                val isActivelyBuffering =
                                    vm.playbackState == androidx.media3.common.Player.STATE_BUFFERING && vm.playWhenReady
                                var showBufferingOverlay by remember { mutableStateOf(false) }
                                LaunchedEffect(isActivelyBuffering) {
                                    if (isActivelyBuffering) {
                                        kotlinx.coroutines.delay(300L)
                                        showBufferingOverlay = true
                                    } else {
                                        showBufferingOverlay = false
                                    }
                                }
                                Box(
                                    modifier = Modifier.size(48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AnimatedContent(
                                        targetState = vm.mediaMetadata,
                                        transitionSpec = {
                                            (fadeIn(animationSpec = motionScheme.fastEffectsSpec()) +
                                                scaleIn(initialScale = 0.9f, animationSpec = motionScheme.fastSpatialSpec()))
                                                .togetherWith(
                                                    fadeOut(animationSpec = motionScheme.fastEffectsSpec()) +
                                                        scaleOut(targetScale = 0.9f, animationSpec = motionScheme.fastSpatialSpec())
                                                )
                                        },
                                        label = "artworkMetadata"
                                    ) { mediaMetadata ->
                                        ShimmerAsyncImage(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .graphicsLayer {
                                                    scaleX = artworkScale
                                                    scaleY = artworkScale
                                                }
                                                .clip(RoundedCornerShape(artworkCorner)),
                                            model = mediaMetadata?.artworkUri,
                                            contentDescription = mediaMetadata?.title?.toString(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                    if (showBufferingOverlay) {
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .clip(RoundedCornerShape(artworkCorner))
                                                .background(Color.Black.copy(alpha = 0.35f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            ContainedLoadingIndicator(Modifier.size(28.dp))
                                        }
                                    }
                                }

                                if (vm.playerError != null) {
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = vm.playerError.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = { vm.retry() }) {
                                            Text("Retry")
                                        }
                                    }
                                } else {

                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val spinSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
                                    // Skip Back — M3E tonal icon button with built-in shape morph & rotational kick
                                    val backSpin = remember { Animatable(0f) }
                                    FilledTonalIconButton(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .graphicsLayer { rotationZ = backSpin.value },
                                        shapes = IconButtonDefaults.shapes(),
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                        ),
                                        onClick = {
                                            vm.seekBack()
                                            scope.launch {
                                                backSpin.snapTo(-24f)
                                                backSpin.animateTo(0f, animationSpec = spinSpec)
                                            }
                                        }
                                    ) {
                                        Icon(
                                            modifier = Modifier.size(22.dp),
                                            imageVector = when(vm.seekBackIncrement) {
                                                5000L -> Icons.Rounded.Replay5
                                                10000L -> Icons.Rounded.Replay10
                                                30000L -> Icons.Rounded.Replay30
                                                else -> Icons.Rounded.Replay
                                            },
                                            contentDescription = "Skip back"
                                        )
                                    }

                                    // Play/Pause — M3E shape-morphing FilledIconToggleButton
                                    FilledIconToggleButton(
                                        modifier = Modifier.size(width = 52.dp, height = 46.dp),
                                        shapes = IconButtonDefaults.toggleableShapes(),
                                        colors = IconButtonDefaults.filledIconToggleButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary,
                                            checkedContainerColor = MaterialTheme.colorScheme.primary,
                                            checkedContentColor = MaterialTheme.colorScheme.onPrimary
                                        ),
                                        checked = vm.isPlaying,
                                        onCheckedChange = {
                                            if(vm.isPlaying) {
                                                vm.pause()
                                            } else {
                                                vm.play()
                                            }
                                        }
                                    ) {
                                        AnimatedContent(
                                            targetState = vm.isPlaying,
                                            transitionSpec = {
                                                (scaleIn(initialScale = 0.65f, animationSpec = motionScheme.fastSpatialSpec()) +
                                                    fadeIn(animationSpec = motionScheme.fastEffectsSpec()))
                                                    .togetherWith(
                                                        scaleOut(targetScale = 0.65f, animationSpec = motionScheme.fastSpatialSpec()) +
                                                            fadeOut(animationSpec = motionScheme.fastEffectsSpec())
                                                    )
                                            },
                                            label = "playPauseIcon"
                                        ) { playing ->
                                            Icon(
                                                modifier = Modifier.size(26.dp),
                                                imageVector = when(playing) {
                                                    true -> Icons.Rounded.Pause
                                                    false -> Icons.Rounded.PlayArrow
                                                },
                                                contentDescription = if (playing) "Pause" else "Play"
                                            )
                                        }
                                    }

                                    // Skip Forward — M3E tonal icon button with built-in shape morph & rotational kick
                                    val fwdSpin = remember { Animatable(0f) }
                                    FilledTonalIconButton(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .graphicsLayer { rotationZ = fwdSpin.value },
                                        shapes = IconButtonDefaults.shapes(),
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                        ),
                                        onClick = {
                                            vm.seekForward()
                                            scope.launch {
                                                fwdSpin.snapTo(24f)
                                                fwdSpin.animateTo(0f, animationSpec = spinSpec)
                                            }
                                        }
                                    ) {
                                        Icon(
                                            modifier = Modifier.size(22.dp),
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
                                }

                                onOpenQueue?.let { openQueue ->
                                    val queueInteraction = remember { MutableInteractionSource() }
                                    val queuePressed by queueInteraction.collectIsPressedAsState()
                                    val queueScale by animateFloatAsState(
                                        targetValue = if (queuePressed) 0.90f else 1f,
                                        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                                        label = "queueScale"
                                    )
                                    androidx.compose.material3.Surface(
                                        modifier = Modifier.graphicsLayer {
                                            scaleX = queueScale
                                            scaleY = queueScale
                                        },
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        interactionSource = queueInteraction,
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
                                                AnimatedContent(
                                                    targetState = vm.queue.size,
                                                    label = "queueCount"
                                                ) { count ->
                                                    Text(
                                                        text = if (count > 999) "999+" else count.toString(),
                                                        style = MaterialTheme.typography.labelMedium,
                                                        maxLines = 1
                                                    )
                                                }
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
                                    .height(5.dp)
                            ) {
                                LinearWavyProgressIndicator(
                                    progress = { progressState.value },
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    amplitude = { if (vm.isPlaying) 0.65f else 0f },
                                    wavelength = 16.dp
                                )

                                val durMs = vm.currentDuration
                                if (durMs > 0L && (adEntities.value.isNotEmpty() || muteEntities.value.isNotEmpty())) {
                                    val adColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f)
                                    val suggestedAdColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f)
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