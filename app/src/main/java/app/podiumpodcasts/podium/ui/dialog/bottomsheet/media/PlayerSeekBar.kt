package app.podiumpodcasts.podium.ui.dialog.bottomsheet.media

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hushcast.automute.dsp.AdWindow
import app.hushcast.automute.dsp.MuteWindow
import app.hushcast.automute.dsp.SkipAwareSeekCalculator
import app.podiumpodcasts.podium.background.HushCastEngineBridge
import kotlin.math.abs

@Composable
fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    adjustRemainingForSpeed: Boolean,
    muteWindows: List<MuteWindow>,
    adWindows: List<AdWindow>,
    onSeekToMs: (Long) -> Unit,
    onSkipAd: ((AdWindow) -> Unit)? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val haptic = LocalHapticFeedback.current
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var scrubSpeed by remember { mutableFloatStateOf(1f) }

    val safeDuration = durationMs.coerceAtLeast(1L)
    val liveFraction = (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val displayFraction = if (isDragging) dragFraction else liveFraction
    val displayPosMs = if (isDragging) (displayFraction * safeDuration).toLong() else positionMs

    val activeAd = remember(displayPosMs, adWindows) {
        if (durationMs <= 0L) null
        else adWindows.firstOrNull { displayPosMs in it.startMs..it.endMs }
    }
    val nearMute = remember(displayPosMs, muteWindows) {
        if (durationMs <= 0L) null
        else muteWindows.firstOrNull { it.enabled && abs(displayPosMs - it.startMs) <= 1500L }
    }

    LaunchedEffect(isDragging, activeAd?.startMs, nearMute?.startMs) {
        if (isDragging && (activeAd != null || nearMute != null)) {
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }

    val trackHeight by animateDpAsState(
        targetValue = if (isDragging) 14.dp else if (compact) 6.dp else 9.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "seekTrackHeight"
    )

    val adColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.78f)
    val suggestedAdColor = Color(0xFFFFB300).copy(alpha = 0.8f)
    val muteTickColor = MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
    val activeTrackColor = MaterialTheme.colorScheme.primary
    val inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
    val thumbColor = if (activeAd != null) MaterialTheme.colorScheme.tertiary else activeTrackColor
    val thumbBorderColor = MaterialTheme.colorScheme.surface

    val posText = DateUtils.formatElapsedTime(displayPosMs / 1000L)
    val remainingAudioMs = (durationMs - displayPosMs).coerceAtLeast(0L)
    val remainingDisplayMs = if (adjustRemainingForSpeed && durationMs > 0L) {
        SkipAwareSeekCalculator.computeWallRemainingMs(
            fromMs = displayPosMs,
            targetEndMs = durationMs,
            speed = playbackSpeed,
            skipRanges = HushCastEngineBridge.getActiveSkipRanges()
        )
    } else {
        remainingAudioMs
    }
    val remText = if (durationMs > 0L) "-${DateUtils.formatElapsedTime(remainingDisplayMs / 1000L)}" else "--:--"

    Column(modifier = modifier.fillMaxWidth()) {
        // Precise scrub floating pill with zoomed-in ±90s mini timeline
        AnimatedVisibility(
            visible = isDragging && durationMs > 0L,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            val pillBg = if (activeAd != null) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            }
            val pillFg = if (activeAd != null) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            val speedLabel = when {
                scrubSpeed <= 0.15f -> "Fine 1/10x"
                scrubSpeed <= 0.25f -> "Fine 1/5x"
                scrubSpeed <= 0.55f -> "Fine ½x"
                else -> "Drag ↓ for fine"
            }

            Surface(
                color = pillBg,
                contentColor = pillFg,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = posText,
                                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                                fontWeight = FontWeight.Bold
                            )
                            if (activeAd != null) {
                                Text(
                                    text = "• ${formatAdDisplayLabel(activeAd)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else if (nearMute != null) {
                                Text(
                                    text = "• Muted: ${nearMute.word}",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Text(
                            text = speedLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = pillFg.copy(alpha = 0.78f)
                        )
                    }

                    val windowHalfMs = 90_000L
                    val winStart = displayPosMs - windowHalfMs
                    val winSpan = (windowHalfMs * 2L).toFloat()
                    val zoomTrackBg = pillFg.copy(alpha = 0.15f)
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
                                color = if (ad.isHighConfidence) adColor else suggestedAdColor,
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
                        drawRect(
                            color = pillFg,
                            topLeft = Offset(w * 0.5f - 1.5f, 0f),
                            size = Size(3f, h)
                        )
                    }
                }
            }
        }

        val a11yActions = buildList {
            if (activeAd != null && onSkipAd != null) {
                add(
                    CustomAccessibilityAction("Skip ${formatAdDisplayLabel(activeAd)}") {
                        onSkipAd(activeAd)
                        true
                    }
                )
            }
        }
        val stateDesc = buildString {
            append(posText)
            if (durationMs > 0L) {
                append(", ")
                append(DateUtils.formatElapsedTime(remainingDisplayMs / 1000L))
                append(" remaining")
            }
            if (activeAd != null) {
                append(", in ${formatAdDisplayLabel(activeAd)}")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .semantics {
                    stateDescription = stateDesc
                    if (a11yActions.isNotEmpty()) {
                        customActions = a11yActions
                    }
                }
                .pointerInput(durationMs) {
                    if (durationMs <= 0L) return@pointerInput
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
                        dragFraction = currentFrac

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

                            val dy = abs(change.position.y - startY)
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
                                dragFraction = currentFrac
                            }
                            change.consume()
                        }

                        val targetMs = (currentFrac * safeDuration).toLong().coerceIn(0L, safeDuration)
                        isDragging = false
                        scrubSpeed = 1f
                        onSeekToMs(targetMs)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(trackHeight)
            ) {
                val w = size.width
                val h = size.height
                val cr = CornerRadius(h / 2f, h / 2f)

                drawRoundRect(
                    color = inactiveTrackColor,
                    topLeft = Offset.Zero,
                    size = Size(w, h),
                    cornerRadius = cr
                )

                val fillW = (displayFraction * w).coerceIn(0f, w)
                if (fillW > 0f) {
                    drawRoundRect(
                        color = activeTrackColor,
                        topLeft = Offset.Zero,
                        size = Size(fillW, h),
                        cornerRadius = cr
                    )
                }

                if (durationMs > 0L) {
                    val dur = durationMs.toFloat()
                    for (ad in adWindows) {
                        val x0 = (ad.startMs / dur).coerceIn(0f, 1f) * w
                        val x1 = (ad.endMs / dur).coerceIn(0f, 1f) * w
                        drawRoundRect(
                            color = if (ad.isHighConfidence) adColor else suggestedAdColor,
                            topLeft = Offset(x0, 0f),
                            size = Size((x1 - x0).coerceAtLeast(3.dp.toPx()), h),
                            cornerRadius = cr
                        )
                    }
                    for (mute in muteWindows) {
                        if (!mute.enabled) continue
                        val x = (mute.startMs / dur).coerceIn(0f, 1f) * w
                        drawRect(
                            color = muteTickColor,
                            topLeft = Offset(x, 0f),
                            size = Size(2.5.dp.toPx(), h)
                        )
                    }
                }

                val thumbX = (displayFraction * w).coerceIn(0f, w)
                val thumbW = if (isDragging) 6.dp.toPx() else 4.dp.toPx()
                val thumbH = if (isDragging) 28.dp.toPx() else 22.dp.toPx()
                val thumbTop = (h - thumbH) / 2f
                drawRoundRect(
                    color = thumbBorderColor,
                    topLeft = Offset(thumbX - thumbW / 2f - 2.dp.toPx(), thumbTop - 2.dp.toPx()),
                    size = Size(thumbW + 4.dp.toPx(), thumbH + 4.dp.toPx()),
                    cornerRadius = CornerRadius(thumbW, thumbW)
                )
                drawRoundRect(
                    color = thumbColor,
                    topLeft = Offset(thumbX - thumbW / 2f, thumbTop),
                    size = Size(thumbW, thumbH),
                    cornerRadius = CornerRadius(thumbW / 2f, thumbW / 2f)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = posText,
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = remText,
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun formatAdDisplayLabel(ad: AdWindow): String {
    val customLabel = ad.label.trim().takeIf {
        it.isNotEmpty() &&
            it != "Ad" &&
            it != "Sponsor / Ad" &&
            it != "Suggested Ad" &&
            it != "House Promo" &&
            it != "Membership Pitch" &&
            it != "Intro / Outro"
    }
    return customLabel ?: when (ad.adClass) {
        "house_promo" -> "Show Promo"
        "membership_pitch" -> "Membership"
        "boilerplate" -> "Intro / Outro"
        else -> if (ad.isHighConfidence) "Sponsor" else "Suggested Ad"
    }
}
