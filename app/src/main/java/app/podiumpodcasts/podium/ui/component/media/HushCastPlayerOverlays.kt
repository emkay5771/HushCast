package app.podiumpodcasts.podium.ui.component.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.VerticalAlignCenter
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hushcast.automute.dsp.AdSkipEvent
import app.hushcast.automute.dsp.AdWindow
import app.hushcast.automute.dsp.MuteWindow
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.engine.Segment
import app.hushcast.automute.engine.Word

@Composable
fun EpisodeReadinessBadge(
    badge: ReadinessBadge,
    progressPercent: Int = 0,
    onAnalyzeNowClick: (() -> Unit)? = null
) {
    val isActionableNotProcessed = badge == ReadinessBadge.NOT_PROCESSED && onAnalyzeNowClick != null
    val (label, containerColor, contentColor, icon) = when {
        isActionableNotProcessed -> Quad(
            "Analyze w/ HushCast AI",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Rounded.AutoAwesome
        )
        badge == ReadinessBadge.READY -> Quad(
            "HushCast Ready",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Rounded.CheckCircle
        )
        badge == ReadinessBadge.WORD_MUTE_READY -> Quad(
            "Word-Mute Ready",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.AutoMirrored.Rounded.VolumeOff
        )
        badge == ReadinessBadge.ANALYZING -> Quad(
            "Analyzing $progressPercent%",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.Rounded.HourglassTop
        )
        else -> Quad(
            "Not Processed",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Rounded.Info
        )
    }

    val clickableModifier = if (onAnalyzeNowClick != null && badge != ReadinessBadge.ANALYZING) {
        Modifier.clickable(onClick = onAnalyzeNowClick)
    } else {
        Modifier
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .then(clickableModifier)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/**
 * Annotated scrubber bar rendered above/behind the player slider showing:
 * - Red/primary tick marks for muted words
 * - Shaded violet blocks for high-confidence sponsor ads
 * - Amber markers for borderline "Suggested Ad" spans
 */
@Composable
fun AnnotatedScrubberOverlay(
    durationMs: Long,
    muteWindows: List<MuteWindow>,
    adWindows: List<AdWindow>,
    modifier: Modifier = Modifier
) {
    if (durationMs <= 0L || (muteWindows.isEmpty() && adWindows.isEmpty())) return

    val adColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)
    val suggestedAdColor = Color(0xFFFFB300).copy(alpha = 0.65f)
    val muteTickColor = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        val w = size.width
        val h = size.height
        val dur = durationMs.toFloat().coerceAtLeast(1f)

        for (ad in adWindows) {
            val x0 = (ad.startMs / dur).coerceIn(0f, 1f) * w
            val x1 = (ad.endMs / dur).coerceIn(0f, 1f) * w
            drawRect(
                color = if (ad.isHighConfidence) adColor else suggestedAdColor,
                topLeft = Offset(x0, 0f),
                size = Size((x1 - x0).coerceAtLeast(3f), h)
            )
        }

        for (mute in muteWindows) {
            if (!mute.enabled) continue
            val x = (mute.startMs / dur).coerceIn(0f, 1f) * w
            drawRect(
                color = muteTickColor,
                topLeft = Offset(x, 0f),
                size = Size(3f, h)
            )
        }
    }
}

/**
 * Single unified playback scrubber that renders played progress, ad-skip windows,
 * and word-mute ticks directly on the slider track, with vertical-drag fine scrubbing,
 * haptic sponsor-boundary ticks, and a zoomed ±90s mini-timeline callout.
 */
@Composable
fun AnnotatedPlaybackSlider(
    value: Float,
    durationMs: Long,
    muteWindows: List<MuteWindow>,
    adWindows: List<AdWindow>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    pendingUndo: AdSkipEvent? = null,
    onSkipAd: ((AdWindow) -> Unit)? = null,
    onUndoSkip: ((AdSkipEvent) -> Unit)? = null,
    onDismissUndo: (() -> Unit)? = null
) {
    val activeTrackColor = MaterialTheme.colorScheme.primary
    val inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
    val adColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
    val activeAdColor = MaterialTheme.colorScheme.tertiary
    val suggestedAdColor = Color(0xFFFFB300).copy(alpha = 0.85f)
    val muteTickColor = MaterialTheme.colorScheme.error
    val thumbDefaultColor = MaterialTheme.colorScheme.primary
    val thumbAdColor = MaterialTheme.colorScheme.tertiary
    val thumbBorderColor = MaterialTheme.colorScheme.surface

    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var isDragging by remember { mutableStateOf(false) }
    var scrubSpeed by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }

    val clampedVal = value.coerceIn(0f, 1f)
    val currentPosMs = if (durationMs > 0L) (clampedVal * durationMs).toLong() else 0L
    val activeAd = remember(currentPosMs, adWindows) {
        if (durationMs <= 0L) null
        else adWindows.firstOrNull { currentPosMs in it.startMs..it.endMs }
    }
    val activeMute = remember(currentPosMs, muteWindows) {
        if (durationMs <= 0L) null
        else muteWindows.firstOrNull { it.enabled && kotlin.math.abs(currentPosMs - it.startMs) <= 1500L }
    }

    LaunchedEffect(isDragging, activeAd?.startMs) {
        if (isDragging) {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        }
    }

    val trackHeight by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (isDragging) 14.dp else 8.dp,
        animationSpec = androidx.compose.animation.core.spring(
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        ),
        label = "trackHeight"
    )

    val latestOnValueChange by androidx.compose.runtime.rememberUpdatedState(onValueChange)
    val latestOnValueChangeFinished by androidx.compose.runtime.rememberUpdatedState(onValueChangeFinished)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = isDragging || activeAd != null || pendingUndo != null,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
        ) {
            val showUndo = !isDragging && pendingUndo != null
            val pillBg = when {
                showUndo -> MaterialTheme.colorScheme.primaryContainer
                activeAd != null -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHighest
            }
            val pillFg = when {
                showUndo -> MaterialTheme.colorScheme.onPrimaryContainer
                activeAd != null -> MaterialTheme.colorScheme.onTertiaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                shape = RoundedCornerShape(14.dp),
                color = pillBg,
                contentColor = pillFg
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (showUndo && pendingUndo != null) {
                            Text(
                                text = "Skipped ${pendingUndo.adWindow.label}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                if (onUndoSkip != null) {
                                    TextButton(
                                        onClick = { onUndoSkip(pendingUndo) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text("Undo", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                if (onDismissUndo != null) {
                                    IconButton(
                                        onClick = onDismissUndo,
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Dismiss",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = formatScrubTimeMs(currentPosMs),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                if (activeAd != null) {
                                    val customLabel = activeAd.label.trim().takeIf {
                                        it.isNotEmpty() &&
                                            it != "Sponsor / Ad" &&
                                            it != "Suggested Ad" &&
                                            it != "House Promo" &&
                                            it != "Membership Pitch" &&
                                            it != "Intro / Outro"
                                    }
                                    val sectionLabel = customLabel ?: when (activeAd.adClass) {
                                        "house_promo" -> "Show Promo"
                                        "membership_pitch" -> "Membership"
                                        "boilerplate" -> "Intro / Outro"
                                        else -> if (activeAd.isHighConfidence) "Sponsor" else "Suggested Ad"
                                    }
                                    Text(
                                        text = "• $sectionLabel (${formatScrubTimeMs(activeAd.startMs)}–${formatScrubTimeMs(activeAd.endMs)})",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                } else if (activeMute != null) {
                                    Text(
                                        text = "• Muted: ${activeMute.word}",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }

                            if (isDragging) {
                                val speedLabel = when {
                                    scrubSpeed <= 0.15f -> "Fine 1/10x"
                                    scrubSpeed <= 0.25f -> "Fine 1/5x"
                                    scrubSpeed <= 0.55f -> "Fine ½x"
                                    else -> "Drag ↓ for fine"
                                }
                                Text(
                                    text = speedLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = pillFg.copy(alpha = 0.78f)
                                )
                            } else if (activeAd != null && onSkipAd != null) {
                                TextButton(
                                    onClick = { onSkipAd(activeAd) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("Skip", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // Zoomed ±90s mini-timeline around the current scrub position
                    if (isDragging && durationMs > 0L) {
                        val windowHalfMs = 90_000L
                        val winStart = currentPosMs - windowHalfMs
                        val winSpan = (windowHalfMs * 2L).toFloat()
                        val zoomTrackBg = pillFg.copy(alpha = 0.15f)
                        val zoomCursorColor = pillFg
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        ) {
                            val w = size.width
                            val h = size.height
                            drawRect(color = zoomTrackBg, size = Size(w, h))

                            for (ad in adWindows) {
                                if (ad.endMs < winStart || ad.startMs > winStart + windowHalfMs * 2L) continue
                                val x0 = ((ad.startMs - winStart) / winSpan).coerceIn(0f, 1f) * w
                                val x1 = ((ad.endMs - winStart) / winSpan).coerceIn(0f, 1f) * w
                                drawRect(
                                    color = if (ad.isHighConfidence) activeAdColor else suggestedAdColor,
                                    topLeft = Offset(x0, 0f),
                                    size = Size((x1 - x0).coerceAtLeast(3f), h)
                                )
                            }
                            for (mute in muteWindows) {
                                if (!mute.enabled) continue
                                if (mute.startMs < winStart || mute.startMs > winStart + windowHalfMs * 2L) continue
                                val x = ((mute.startMs - winStart) / winSpan).coerceIn(0f, 1f) * w
                                drawRect(
                                    color = muteTickColor,
                                    topLeft = Offset(x - 1.5f, 0f),
                                    size = Size(3f, h)
                                )
                            }
                            // Center cursor needle
                            drawRect(
                                color = zoomCursorColor,
                                topLeft = Offset(w * 0.5f - 1.5f, 0f),
                                size = Size(3f, h)
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .pointerInput(durationMs) {
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val threshold1 = 36.dp.toPx()
                    val threshold2 = 76.dp.toPx()
                    val threshold3 = 120.dp.toPx()
                    val edgePad = 10.dp.toPx()
                    val trackW = (width - edgePad * 2f).coerceAtLeast(1f)

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        isDragging = true
                        scrubSpeed = 1f
                        var currentFrac = ((down.position.x - edgePad) / trackW).coerceIn(0f, 1f)
                        latestOnValueChange(currentFrac)

                        var lastX = down.position.x
                        val startY = down.position.y

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                                ?: break

                            if (!change.pressed) {
                                change.consume()
                                break
                            }

                            val dy = kotlin.math.abs(change.position.y - startY)
                            val speed = when {
                                dy >= threshold3 -> 0.1f
                                dy >= threshold2 -> 0.2f
                                dy >= threshold1 -> 0.5f
                                else -> 1.0f
                            }
                            scrubSpeed = speed

                            val dx = change.position.x - lastX
                            lastX = change.position.x
                            if (dx != 0f) {
                                currentFrac = (currentFrac + (dx / trackW) * speed).coerceIn(0f, 1f)
                                latestOnValueChange(currentFrac)
                            }
                            change.consume()
                        }

                        isDragging = false
                        scrubSpeed = 1f
                        latestOnValueChangeFinished()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(trackHeight)
                    .clip(RoundedCornerShape(7.dp))
            ) {
                val w = size.width
                val h = size.height

                drawRect(color = inactiveTrackColor, size = Size(w, h))
                if (clampedVal > 0f) {
                    drawRect(color = activeTrackColor, size = Size(w * clampedVal, h))
                }

                if (durationMs > 0L) {
                    val dur = durationMs.toFloat().coerceAtLeast(1f)
                    for (ad in adWindows) {
                        val x0 = (ad.startMs / dur).coerceIn(0f, 1f) * w
                        val x1 = (ad.endMs / dur).coerceIn(0f, 1f) * w
                        val isActive = activeAd != null && activeAd.startMs == ad.startMs
                        val color = when {
                            isActive -> activeAdColor
                            ad.isHighConfidence -> adColor
                            else -> suggestedAdColor
                        }
                        drawRect(
                            color = color,
                            topLeft = Offset(x0, 0f),
                            size = Size((x1 - x0).coerceAtLeast(5f), h)
                        )
                        if (isActive) {
                            drawRect(
                                color = thumbBorderColor,
                                topLeft = Offset(x0, 0f),
                                size = Size((x1 - x0).coerceAtLeast(5f), 2.dp.toPx())
                            )
                        }
                    }
                    for (mute in muteWindows) {
                        if (!mute.enabled) continue
                        val x = (mute.startMs / dur).coerceIn(0f, 1f) * w
                        drawRect(
                            color = muteTickColor,
                            topLeft = Offset(x, 0f),
                            size = Size(3f, h)
                        )
                    }
                }
            }

            // Crisp pill thumb needle so the exact position & sponsor overlap remain visible
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(28.dp)
            ) {
                val thumbX = clampedVal * size.width
                val thumbW = if (isDragging) 6.dp.toPx() else 4.dp.toPx()
                val thumbH = if (isDragging) 26.dp.toPx() else 20.dp.toPx()
                val topY = (size.height - thumbH) / 2f
                val corner = androidx.compose.ui.geometry.CornerRadius(thumbW / 2f, thumbW / 2f)
                drawRoundRect(
                    color = thumbBorderColor,
                    topLeft = Offset(thumbX - thumbW / 2f - 1.5.dp.toPx(), topY - 1.5.dp.toPx()),
                    size = Size(thumbW + 3.dp.toPx(), thumbH + 3.dp.toPx()),
                    cornerRadius = corner
                )
                drawRoundRect(
                    color = if (activeAd != null) thumbAdColor else thumbDefaultColor,
                    topLeft = Offset(thumbX - thumbW / 2f, topY),
                    size = Size(thumbW, thumbH),
                    cornerRadius = corner
                )
            }
        }
    }
}

private fun formatScrubTimeMs(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L)) / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
fun OneTapUndoAdSkipBanner(
    event: AdSkipEvent,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.SkipNext, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Skipped ${event.adWindow.label} (${((event.jumpedToMs - event.jumpedFromMs) / 1000L)}s)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            TextButton(onClick = onUndo) {
                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Undo", color = MaterialTheme.colorScheme.inversePrimary)
            }
        }
    }
}

@Composable
fun ActiveAdSkipToastBanner(
    adWindow: AdWindow,
    currentPositionMs: Long,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val remainingSec = ((adWindow.endMs - currentPositionMs).coerceAtLeast(0L) / 1000L)
    val isSuggested = !adWindow.isHighConfidence
    val containerColor = if (isSuggested) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (isSuggested) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.SkipNext, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = if (isSuggested) "Suggested Ad" else "Sponsor / Ad",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${remainingSec}s remaining",
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.8f)
                    )
                }
            }
            FilledTonalButton(
                onClick = onSkip,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Skip Ad")
            }
            androidx.compose.material3.IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Dismiss",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun CastingBypassNoticeBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Cast, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Casting active: on-device word muting is bypassed on external receivers.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * Synced Interactive Transcript View:
 * - Searchable lines with timestamps (tap any line or word to jump)
 * - Long-press any word to mute/unmute it across the episode
 * - Collapses sponsor reads into "Sponsor Read — Tap to hear" cards with working Play
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SyncedTranscriptCard(
    segments: List<Segment>,
    words: List<Word>,
    muteWindows: List<MuteWindow>,
    adWindows: List<AdWindow>,
    currentPositionMs: Long,
    onSeekToMs: (Long) -> Unit,
    onToggleWordMute: (Word, Boolean) -> Unit,
    onPlayAdSegment: ((AdWindow, Long) -> Unit)? = null,
    onCopyReport: (() -> Unit)? = null,
    summary: String? = null,
    chapters: List<app.hushcast.automute.desktop.DesktopChapterDto> = emptyList(),
    modifier: Modifier = Modifier
) {
    if (segments.isEmpty()) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Subtitles, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    "No transcript yet. Download & analyze this episode to enable interactive word muting and ad skipping.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        return
    }

    var searchQuery by remember { mutableStateOf("") }
    var showInsights by remember { mutableStateOf(false) }
    val filteredSegments = remember(segments, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) segments
        else segments.filter { it.text.contains(q, ignoreCase = true) }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!summary.isNullOrBlank() || chapters.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showInsights = !showInsights }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = buildString {
                                append("AI Summary")
                                if (chapters.isNotEmpty()) {
                                    val isPodcast = chapters.any { it.source == "podcast" }
                                    append(if (isPodcast) " • ${chapters.size} Podcast Chapters" else " • ${chapters.size} Auto-Created Chapters")
                                }
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = if (showInsights) "Hide" else "Show",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (showInsights) {
                        if (!summary.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        if (chapters.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                chapters.forEach { ch ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onSeekToMs(ch.startMs) }
                                            .padding(vertical = 4.dp, horizontal = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = formatTranscriptTimestamp(ch.startMs),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = ch.title,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Search transcript…") },
                leadingIcon = {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Rounded.Search,
                        contentDescription = null
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        androidx.compose.material3.IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear search"
                            )
                        }
                    }
                }
            )

            onCopyReport?.let { copyReport ->
                androidx.compose.material3.FilledTonalIconButton(onClick = copyReport) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Rounded.ContentCopy,
                        contentDescription = "Copy transcript & ad report"
                    )
                }
            }
        }

        Text(
            text = if (searchQuery.isBlank()) {
                "Tap to jump • Long-press word to mute"
            } else {
                "${filteredSegments.size} matching ${if (filteredSegments.size == 1) "line" else "lines"} • Tap to jump"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (filteredSegments.isEmpty()) {
            Text(
                text = "No lines matching \"$searchQuery\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            val displayRows = remember(filteredSegments, adWindows) {
                buildTranscriptDisplayRows(filteredSegments, adWindows)
            }
            val wordsBySegment = remember(filteredSegments, words) {
                groupWordsBySegment(filteredSegments, words)
            }
            val activeMuteWindows = remember(muteWindows) {
                muteWindows.filter { it.enabled }.sortedBy { it.startMs }
            }
            val activeRowIndex = remember(displayRows, currentPositionMs) {
                if (displayRows.isEmpty()) return@remember -1
                var lo = 0
                var hi = displayRows.lastIndex
                var best = -1
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    val (s, e) = when (val row = displayRows[mid]) {
                        is TranscriptDisplayRow.ParagraphGroup -> row.startMs to row.endMs
                        is TranscriptDisplayRow.SponsorGroup -> row.startMs to row.endMs
                    }
                    if (currentPositionMs in s..e) {
                        best = mid
                        break
                    } else if (currentPositionMs < s) {
                        hi = mid - 1
                    } else {
                        best = mid
                        lo = mid + 1
                    }
                }
                best
            }

            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = activeRowIndex.coerceAtLeast(0)
            )
            val scope = rememberCoroutineScope()
            var followLive by remember { mutableStateOf(true) }

            val nestedScrollConnection = remember {
                object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                    override fun onPreScroll(
                        available: Offset,
                        source: androidx.compose.ui.input.nestedscroll.NestedScrollSource
                    ): Offset {
                        if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput &&
                            kotlin.math.abs(available.y) > 2f
                        ) {
                            followLive = false
                        }
                        return Offset.Zero
                    }
                }
            }

            LaunchedEffect(activeRowIndex, followLive, searchQuery) {
                if (followLive && searchQuery.isBlank() && activeRowIndex >= 0) {
                    listState.animateScrollToItem(activeRowIndex)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .nestedScroll(nestedScrollConnection)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(
                        items = displayRows,
                        key = { idx, row ->
                            when (row) {
                                is TranscriptDisplayRow.SponsorGroup -> "ad_${row.startMs}_${idx}"
                                is TranscriptDisplayRow.ParagraphGroup -> "para_${row.startMs}_${idx}"
                            }
                        }
                    ) { _, row ->
                        when (row) {
                            is TranscriptDisplayRow.SponsorGroup -> {
                                val durSec = ((row.endMs - row.startMs) / 1000L).coerceAtLeast(1L)
                                val durFormatted = if (durSec >= 60L) {
                                    "%d:%02d".format(durSec / 60L, durSec % 60L)
                                } else {
                                    "${durSec}s"
                                }
                                AssistChip(
                                    onClick = {
                                        if (onPlayAdSegment != null) {
                                            onPlayAdSegment(row.ad, row.startMs)
                                        } else {
                                            onSeekToMs(row.startMs)
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = "${row.displayLabel} · $durFormatted",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.SkipNext,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                )
                            }

                            is TranscriptDisplayRow.ParagraphGroup -> {
                                val isActive = currentPositionMs in row.startMs..row.endMs
                                val paragraphWords = remember(row, wordsBySegment) {
                                    row.segments.flatMap { seg -> wordsBySegment[seg].orEmpty() }
                                }

                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (isActive) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                    } else {
                                        Color.Transparent
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSeekToMs(row.startMs) }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(
                                            horizontal = 10.dp,
                                            vertical = 6.dp
                                        )
                                    ) {
                                        if (row.showSpeakerLabel && row.speaker != null) {
                                            val spkColor = transcriptSpeakerColors[row.speaker.coerceAtLeast(0) % transcriptSpeakerColors.size]
                                            Text(
                                                text = "Speaker ${row.speaker + 1}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = spkColor,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(bottom = 4.dp)
                                            )
                                        }
                                        if (paragraphWords.isNotEmpty()) {
                                            val mutedTextColor = MaterialTheme.colorScheme.onErrorContainer
                                            val mutedBgColor = MaterialTheme.colorScheme.errorContainer
                                            val spokenTextColor = MaterialTheme.colorScheme.onPrimary
                                            val spokenBgColor = MaterialTheme.colorScheme.primary
                                            val normalColor = if (isActive) {
                                                MaterialTheme.colorScheme.onSurface
                                            } else {
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                                            }

                                            val wordRanges = remember(paragraphWords) {
                                                ArrayList<Triple<Int, Int, Word>>(paragraphWords.size)
                                            }
                                            val annotated = remember(
                                                paragraphWords,
                                                activeMuteWindows,
                                                if (isActive) currentPositionMs / 100L else -1L,
                                                normalColor,
                                                mutedTextColor,
                                                mutedBgColor,
                                                spokenTextColor,
                                                spokenBgColor
                                            ) {
                                                wordRanges.clear()
                                                androidx.compose.ui.text.buildAnnotatedString {
                                                    paragraphWords.forEachIndexed { idx, w ->
                                                        val ws = (w.start * 1000.0).toLong()
                                                        val we = (w.end * 1000.0).toLong()
                                                        val isMuted = isWordInMuteWindows(ws, we, activeMuteWindows)
                                                        val isSpokenNow = isActive && currentPositionMs in ws..we
                                                        val startIdx = length
                                                        val token = w.text.trim()
                                                        if (isMuted || isSpokenNow) {
                                                            pushStyle(
                                                                androidx.compose.ui.text.SpanStyle(
                                                                    color = if (isMuted) mutedTextColor else spokenTextColor,
                                                                    background = if (isMuted) mutedBgColor else spokenBgColor,
                                                                    fontWeight = if (isSpokenNow) FontWeight.Bold else FontWeight.Normal,
                                                                    textDecoration = if (isMuted) TextDecoration.LineThrough else TextDecoration.None
                                                                )
                                                            )
                                                            append(token)
                                                            pop()
                                                        } else {
                                                            pushStyle(androidx.compose.ui.text.SpanStyle(color = normalColor))
                                                            append(token)
                                                            pop()
                                                        }
                                                        val endIdx = length
                                                        wordRanges.add(Triple(startIdx, endIdx, w))
                                                        if (idx < paragraphWords.lastIndex) append(" ")
                                                    }
                                                }
                                            }

                                            var textLayoutResult by remember {
                                                mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null)
                                            }

                                            Text(
                                                text = annotated,
                                                style = MaterialTheme.typography.bodyLarge,
                                                onTextLayout = { textLayoutResult = it },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .pointerInput(paragraphWords, activeMuteWindows) {
                                                        detectTapGestures(
                                                            onTap = { pos ->
                                                                val layout = textLayoutResult ?: return@detectTapGestures
                                                                val offset = layout.getOffsetForPosition(pos)
                                                                val hit = wordRanges.firstOrNull { (s, e, _) -> offset in s..e }
                                                                if (hit != null) {
                                                                    onSeekToMs((hit.third.start * 1000.0).toLong())
                                                                } else {
                                                                    onSeekToMs(row.startMs)
                                                                }
                                                            },
                                                            onLongPress = { pos ->
                                                                val layout = textLayoutResult ?: return@detectTapGestures
                                                                val offset = layout.getOffsetForPosition(pos)
                                                                val hit = wordRanges.firstOrNull { (s, e, _) -> offset in s..e }
                                                                if (hit != null) {
                                                                    val w = hit.third
                                                                    val ws = (w.start * 1000.0).toLong()
                                                                    val we = (w.end * 1000.0).toLong()
                                                                    val isMuted = isWordInMuteWindows(ws, we, activeMuteWindows)
                                                                    onToggleWordMute(w, !isMuted)
                                                                }
                                                            }
                                                        )
                                                    }
                                            )
                                        } else {
                                            Text(
                                                text = row.segments.joinToString(" ") { it.text.trim() },
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = if (isActive) {
                                                    MaterialTheme.colorScheme.onSurface
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (!followLive && searchQuery.isBlank() && activeRowIndex >= 0) {
                    FilledTonalButton(
                        onClick = {
                            followLive = true
                            scope.launch {
                                listState.animateScrollToItem(activeRowIndex)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VerticalAlignCenter,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Follow playback", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

private sealed interface TranscriptDisplayRow {
    data class ParagraphGroup(
        val segments: List<Segment>,
        val speaker: Int?,
        val showSpeakerLabel: Boolean,
        val startMs: Long,
        val endMs: Long
    ) : TranscriptDisplayRow

    data class SponsorGroup(
        val ad: AdWindow,
        val segments: List<Segment>,
        val displayLabel: String,
        val startMs: Long,
        val endMs: Long
    ) : TranscriptDisplayRow
}

private val sponsorPatterns = listOf(
    Regex(
        """(?:support for (?:the|this) show comes from|sponsored by|brought to you by|thanks to|this episode is brought to you by)\s+([A-Z][A-Za-z0-9&'.-]+(?:\s+[A-Z][A-Za-z0-9&'.-]+){0,2})""",
        RegexOption.IGNORE_CASE
    ),
    Regex(
        """(?:learn more at|visit|head to|go to|check out)\s+([A-Za-z0-9-]+)\.(?:com|io|co|org|net|ai)\b""",
        RegexOption.IGNORE_CASE
    )
)

private fun extractSponsorName(text: String): String? {
    for (pat in sponsorPatterns) {
        val m = pat.find(text) ?: continue
        val raw = m.groupValues.getOrNull(1)?.trim(' ', '.', ',', ':', ';', '!', '?', '"', '\'')
        if (!raw.isNullOrBlank() && raw.length in 2..28) {
            return if (raw.all { it.isLowerCase() }) {
                raw.replaceFirstChar { it.uppercase() }
            } else {
                raw
            }
        }
    }
    return null
}

private fun endsWithSentenceBoundary(text: String): Boolean {
    val t = text.trimEnd()
    if (t.isEmpty()) return false
    return t.endsWith(".") || t.endsWith("!") || t.endsWith("?") ||
        t.endsWith(".\"") || t.endsWith("!\"") || t.endsWith("?\"") ||
        t.endsWith(".'") || t.endsWith("!'") || t.endsWith("?'")
}

private fun buildTranscriptDisplayRows(
    segments: List<Segment>,
    adWindows: List<AdWindow>
): List<TranscriptDisplayRow> {
    val rows = ArrayList<TranscriptDisplayRow>()
    var pendingAd: AdWindow? = null
    val pendingAdSegs = ArrayList<Segment>()
    val pendingParaSegs = ArrayList<Segment>()
    var pendingParaChars = 0
    var lastEmittedSpeaker: Int? = null
    var lastWasSponsor = false

    fun flushParagraph() {
        if (pendingParaSegs.isEmpty()) return
        val spk = pendingParaSegs.first().speaker
        val showSpeaker = spk != null && (lastWasSponsor || spk != lastEmittedSpeaker)
        val startMs = (pendingParaSegs.first().start * 1000.0).toLong()
        val endMs = (pendingParaSegs.last().end * 1000.0).toLong().coerceAtLeast(startMs + 200L)
        rows.add(
            TranscriptDisplayRow.ParagraphGroup(
                segments = pendingParaSegs.toList(),
                speaker = spk,
                showSpeakerLabel = showSpeaker,
                startMs = startMs,
                endMs = endMs
            )
        )
        lastEmittedSpeaker = spk
        lastWasSponsor = false
        pendingParaSegs.clear()
        pendingParaChars = 0
    }

    fun flushPendingAd() {
        val ad = pendingAd ?: return
        if (pendingAdSegs.isEmpty()) return
        val startMs = (pendingAdSegs.first().start * 1000.0).toLong()
        val endMs = (pendingAdSegs.last().end * 1000.0).toLong().coerceAtLeast(startMs + 1000L)
        val combined = pendingAdSegs.joinToString(" ") { it.text }
        val sponsor = extractSponsorName(combined)
        val label = when {
            sponsor != null && ad.isHighConfidence -> "Sponsor: $sponsor"
            sponsor != null -> "Suggested Ad: $sponsor"
            else -> ad.label
        }
        rows.add(
            TranscriptDisplayRow.SponsorGroup(
                ad = ad,
                segments = pendingAdSegs.toList(),
                displayLabel = label,
                startMs = startMs,
                endMs = endMs
            )
        )
        pendingAd = null
        pendingAdSegs.clear()
        lastWasSponsor = true
    }

    for (seg in segments) {
        val sMs = (seg.start * 1000.0).toLong()
        val eMs = (seg.end * 1000.0).toLong()
        val overlappingAd = if (adWindows.isEmpty()) {
            null
        } else {
            adWindows.firstOrNull { ad -> sMs < ad.endMs && eMs > ad.startMs }
        }
        if (overlappingAd != null) {
            flushParagraph()
            val curAd = pendingAd
            val lastEndMs = pendingAdSegs.lastOrNull()?.let { (it.end * 1000.0).toLong() } ?: sMs
            if (curAd != null && (sMs - lastEndMs) <= 8000L && curAd.isHighConfidence == overlappingAd.isHighConfidence) {
                pendingAdSegs.add(seg)
            } else {
                flushPendingAd()
                pendingAd = overlappingAd
                pendingAdSegs.add(seg)
            }
        } else {
            flushPendingAd()
            if (pendingParaSegs.isNotEmpty()) {
                val prevSeg = pendingParaSegs.last()
                val speakerChanged = seg.speaker != prevSeg.speaker
                val longPause = (seg.start - prevSeg.end) >= 1.5
                val paraDurationSec = prevSeg.end - pendingParaSegs.first().start
                val sentenceReady = endsWithSentenceBoundary(prevSeg.text) &&
                    (pendingParaChars >= 260 || pendingParaSegs.size >= 3 || paraDurationSec >= 12.0)
                val hardCap = pendingParaChars >= 500
                if (speakerChanged || longPause || sentenceReady || hardCap) {
                    flushParagraph()
                }
            }
            pendingParaSegs.add(seg)
            pendingParaChars += seg.text.trim().length + 1
        }
    }
    flushPendingAd()
    flushParagraph()
    return rows
}

private val transcriptSpeakerColors = listOf(
    Color(0xFF1E88E5),
    Color(0xFF8E24AA),
    Color(0xFF00897B),
    Color(0xFFF4511E),
    Color(0xFF3949AB),
    Color(0xFF6D4C41)
)

private fun groupWordsBySegment(
    segments: List<Segment>,
    words: List<Word>
): Map<Segment, List<Word>> {
    val map = java.util.IdentityHashMap<Segment, List<Word>>(segments.size)
    if (segments.isEmpty() || words.isEmpty()) return map

    var wordIdx = 0
    val wordCount = words.size
    for (seg in segments) {
        val segStartMs = (seg.start * 1000.0).toLong()
        val segEndMs = (seg.end * 1000.0).toLong()
        while (wordIdx < wordCount && (words[wordIdx].start * 1000.0).toLong() < segStartMs) {
            wordIdx++
        }
        var probe = wordIdx
        val bucket = ArrayList<Word>()
        while (probe < wordCount) {
            val ws = (words[probe].start * 1000.0).toLong()
            if (ws > segEndMs) break
            bucket.add(words[probe])
            probe++
        }
        if (bucket.isNotEmpty()) {
            map[seg] = bucket
        }
    }
    return map
}

private fun isWordInMuteWindows(
    wordStartMs: Long,
    wordEndMs: Long,
    sortedActiveMutes: List<MuteWindow>
): Boolean {
    if (sortedActiveMutes.isEmpty()) return false
    val wordMidMs = (wordStartMs + wordEndMs) / 2L
    val wordDurMs = (wordEndMs - wordStartMs).coerceAtLeast(1L)
    var lo = 0
    var hi = sortedActiveMutes.lastIndex
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val m = sortedActiveMutes[mid]
        when {
            wordEndMs <= m.startMs -> hi = mid - 1
            wordStartMs >= m.endMs -> lo = mid + 1
            else -> {
                if (wordMidMs in m.startMs..m.endMs) return true
                val overlapMs = (minOf(wordEndMs, m.endMs) - maxOf(wordStartMs, m.startMs)).coerceAtLeast(0L)
                return overlapMs * 2L >= wordDurMs
            }
        }
    }
    return false
}

private fun formatTranscriptTimestamp(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Unified HushCast AI Analysis / Download dialog following the consistent option tree:
 * 1. Desktop / Cloud Worker connected -> offer Desktop/Cloud analyze (+ on-device fallback if models ready)
 * 2. Only Mobile ready -> offer On-Device Download & Analyze (+ Audio Only)
 * 3. No models & no Desktop -> explain setup needed + offer Audio Only
 */
@Composable
fun HushCastAnalyzeDialog(
    episodeTitle: String,
    isDownloaded: Boolean,
    onDismiss: () -> Unit,
    onAnalyzeDesktop: () -> Unit,
    onAnalyzeOnDevice: () -> Unit,
    onDownloadAudioOnly: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val desktopConnected = remember(context) {
        app.hushcast.automute.engine.HushCastPrefs.isDesktopWorkerEnabled(context) &&
            app.hushcast.automute.engine.HushCastPrefs.getDesktopWorkerUrl(context).isNotBlank()
    }
    val localModelsReady = remember(context) {
        app.hushcast.automute.engine.ModelManager(context).areAllModelsReady()
    }

    val title: String
    val body: String
    val primaryLabel: String
    val primaryAction: () -> Unit
    val secondaryLabel: String?
    val secondaryAction: (() -> Unit)?

    when {
        desktopConnected -> {
            title = if (isDownloaded) "Refresh on Desktop / Cloud" else "Analyze with Desktop / Cloud"
            body = if (isDownloaded) {
                "Re-download and analyze \"$episodeTitle\" on your connected Desktop/Cloud Worker for fresh transcript, speakers, chapters, and ad markers."
            } else {
                "Download and analyze \"$episodeTitle\" on your connected Desktop/Cloud Worker, then stream the exact audio + AI bundle to your phone."
            }
            primaryLabel = if (isDownloaded) "Refresh on Desktop" else "Download & Analyze (Worker)"
            primaryAction = onAnalyzeDesktop
            if (localModelsReady) {
                secondaryLabel = if (isDownloaded) "Analyze On-Device" else "On-Device Instead"
                secondaryAction = onAnalyzeOnDevice
            } else {
                secondaryLabel = null
                secondaryAction = null
            }
        }
        localModelsReady -> {
            title = "Analyze On-Device with HushCast AI"
            body = if (isDownloaded) {
                "Run on-device HushCast AI on \"$episodeTitle\" to transcribe, auto-mute words, and detect sponsor reads."
            } else {
                "\"$episodeTitle\" isn't downloaded yet. Download it now to run on-device HushCast AI."
            }
            primaryLabel = if (isDownloaded) "Analyze On-Device" else "Download & Analyze"
            primaryAction = onAnalyzeOnDevice
            if (!isDownloaded) {
                secondaryLabel = "Audio Only"
                secondaryAction = onDownloadAudioOnly
            } else {
                secondaryLabel = null
                secondaryAction = null
            }
        }
        else -> {
            title = "HushCast AI Not Set Up"
            body = "No on-device AI models (~455 MB) are downloaded and no Desktop/Cloud Worker is connected. Download on-device models over Wi-Fi now, or pair a worker in Settings → HushCast."
            primaryLabel = "Set up (Wi-Fi)"
            primaryAction = {
                app.hushcast.automute.worker.ModelDownloadWorker.enqueue(
                    context = context,
                    includeAdModels = true,
                    allowMetered = false
                )
            }
            if (!isDownloaded) {
                secondaryLabel = "Audio Only"
                secondaryAction = onDownloadAudioOnly
            } else {
                secondaryLabel = null
                secondaryAction = null
            }
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    onDismiss()
                    primaryAction()
                }
            ) {
                Text(primaryLabel)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (secondaryLabel != null && secondaryAction != null) {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            onDismiss()
                            secondaryAction()
                        }
                    ) {
                        Text(secondaryLabel)
                    }
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
fun ChaptersTabContent(
    chapters: List<app.hushcast.automute.desktop.DesktopChapterDto>,
    currentPositionMs: Long,
    episodeDurationMs: Long,
    skippedChapterStarts: Set<Long>,
    onSeekToMs: (Long) -> Unit,
    onToggleSkipChapter: (app.hushcast.automute.desktop.DesktopChapterDto, Boolean) -> Unit,
    onAnalyzeNowClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (chapters.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "No chapters available yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Analyze this episode with HushCast AI to auto-create topic chapters, or play an episode with embedded podcast chapters.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Button(onClick = onAnalyzeNowClick) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Analyze with HushCast AI")
            }
        }
        return
    }

    val isPodcastProvided = remember(chapters) {
        chapters.any { it.source == "podcast" }
    }
    val activeIndex = remember(chapters, currentPositionMs) {
        chapters.indexOfLast { it.startMs <= currentPositionMs }.coerceAtLeast(0)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isPodcastProvided) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (isPodcastProvided) {
                            Icons.Rounded.Info
                        } else {
                            Icons.Rounded.AutoAwesome
                        },
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (isPodcastProvided) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                    Text(
                        text = if (isPodcastProvided) {
                            "Podcast Chapters"
                        } else {
                            "Auto-Created Chapters (HushCast AI)"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isPodcastProvided) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                }
            }

            val skippedCount = chapters.count { it.startMs in skippedChapterStarts }
            Text(
                text = if (skippedCount > 0) {
                    "${chapters.size} chapters • $skippedCount skipped"
                } else {
                    "${chapters.size} chapters"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                modifier = Modifier.weight(1f),
                enabled = activeIndex > 0,
                onClick = {
                    val prev = chapters.getOrNull(activeIndex - 1) ?: return@FilledTonalButton
                    if (prev.startMs in skippedChapterStarts) {
                        onToggleSkipChapter(prev, false)
                    }
                    onSeekToMs(prev.startMs)
                }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Undo,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Previous Chapter", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            FilledTonalButton(
                modifier = Modifier.weight(1f),
                enabled = activeIndex < chapters.lastIndex,
                onClick = {
                    val next = chapters.getOrNull(activeIndex + 1) ?: return@FilledTonalButton
                    if (next.startMs in skippedChapterStarts) {
                        onToggleSkipChapter(next, false)
                    }
                    onSeekToMs(next.startMs)
                }
            ) {
                Text("Next Chapter", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                items = chapters,
                key = { idx, ch -> "${ch.startMs}_${idx}" }
            ) { idx, ch ->
                val isActive = idx == activeIndex
                val isSkipped = ch.startMs in skippedChapterStarts
                val nextStartMs = chapters.getOrNull(idx + 1)?.startMs ?: episodeDurationMs
                val chDurationMs = (nextStartMs - ch.startMs).coerceAtLeast(0L)
                val progress = if (isActive && chDurationMs > 0L) {
                    ((currentPositionMs - ch.startMs).toFloat() / chDurationMs.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = when {
                        isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        isSkipped -> MaterialTheme.colorScheme.surfaceContainerLow
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (isSkipped) {
                                onToggleSkipChapter(ch, false)
                            }
                            onSeekToMs(ch.startMs)
                        }
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "${idx + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ch.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                                    textDecoration = if (isSkipped) TextDecoration.LineThrough else TextDecoration.None,
                                    color = when {
                                        isSkipped -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        isActive -> MaterialTheme.colorScheme.onPrimaryContainer
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = buildString {
                                        append(formatTranscriptTimestamp(ch.startMs))
                                        if (chDurationMs > 0L) {
                                            append(" • ")
                                            append(formatChapterDuration(chDurationMs))
                                        }
                                        if (isSkipped) {
                                            append(" • Skipped")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            androidx.compose.material3.Checkbox(
                                checked = !isSkipped,
                                onCheckedChange = { includeChapter ->
                                    onToggleSkipChapter(ch, !includeChapter)
                                }
                            )
                        }

                        if (isActive && chDurationMs > 0L) {
                            Spacer(Modifier.height(8.dp))
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatChapterDuration(durationMs: Long): String {
    val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

