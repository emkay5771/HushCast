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
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.layout
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hushcast.automute.dsp.AdWindow
import app.hushcast.automute.dsp.MuteWindow
import app.hushcast.automute.dsp.SkipAwareSeekCalculator
import app.podiumpodcasts.podium.background.HushCastEngineBridge
import kotlin.math.abs

/**
 * S4: [positionMsState] is read only in derived state (second-level text, ad/mute hits) and in the
 * draw phase (track and thumb), so a position tick redraws the bar instead of recomposing the
 * page around it.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerSeekBar(
    positionMsState: androidx.compose.runtime.State<Long>,
    durationMs: Long,
    playbackSpeed: Float,
    remainingTimeDisplay: app.podiumpodcasts.podium.RemainingTimeDisplayMode = app.podiumpodcasts.podium.RemainingTimeDisplayMode.BOTH,
    muteWindows: List<MuteWindow>,
    adWindows: List<AdWindow>,
    onSeekToMs: (Long) -> Unit,
    onSkipAd: ((AdWindow) -> Unit)? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** Chapters, so the scrub card can name the chapter you'd land in (R8). */
    chapters: List<app.podiumpodcasts.podium.api.chapters.ResolvedChapter> = emptyList()
) {
    val haptic = LocalHapticFeedback.current
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var scrubSpeed by remember { mutableFloatStateOf(1f) }

    val safeDuration = durationMs.coerceAtLeast(1L)
    // Exact position, for hit tests: changes every tick, but only derived states read it.
    val exactPosState = remember(positionMsState, safeDuration) {
        derivedStateOf {
            if (isDragging) (dragFraction * safeDuration).toLong() else positionMsState.value
        }
    }
    // Whole seconds, for the text: recomposes at most once a second.
    val displayPosSec by remember(exactPosState) { derivedStateOf { exactPosState.value / 1000L } }
    // While dragging the card's zoomed timeline follows the finger exactly (it recomposes anyway).
    val displayPosMs = if (isDragging) exactPosState.value else displayPosSec * 1000L
    val displayFraction = if (isDragging) dragFraction else (displayPosMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)

    val activeAdState = remember(adWindows, durationMs, exactPosState) {
        derivedStateOf {
            val pos = exactPosState.value
            if (durationMs <= 0L) null else adWindows.firstOrNull { pos in it.startMs..it.endMs }
        }
    }
    val nearMuteState = remember(muteWindows, durationMs, exactPosState) {
        derivedStateOf {
            val pos = exactPosState.value
            if (durationMs <= 0L) null
            else muteWindows.firstOrNull { it.enabled && abs(pos - it.startMs) <= 1500L }
        }
    }
    val activeAd = activeAdState.value
    val nearMute = nearMuteState.value

    LaunchedEffect(isDragging, activeAd?.startMs, nearMute?.startMs) {
        if (isDragging && (activeAd != null || nearMute != null)) {
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }

    // R8: which chapter the scrub would land in, with a tick each time it changes.
    val scrubChapterIndex = if (isDragging && chapters.isNotEmpty()) {
        chapters.indexOfLast { displayPosMs >= it.startMs }
    } else {
        -1
    }
    LaunchedEffect(isDragging, scrubChapterIndex) {
        if (isDragging && scrubChapterIndex >= 0) {
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }

    val trackHeight by animateDpAsState(
        targetValue = if (isDragging) 16.dp else if (compact) 6.dp else 12.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "seekTrackHeight"
    )

    val adColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.78f)
    val suggestedAdColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.85f)
    val muteTickColor = MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
    val activeTrackColor = MaterialTheme.colorScheme.primary
    val inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
    val thumbColor = if (activeAd != null) MaterialTheme.colorScheme.tertiary else activeTrackColor

    val posText = DateUtils.formatElapsedTime(displayPosMs / 1000L)
    val remainingAudioMs = (durationMs - displayPosMs).coerceAtLeast(0L)
    val remainingAdjustedMs = if (durationMs > 0L) {
        SkipAwareSeekCalculator.computeWallRemainingMs(
            fromMs = displayPosMs,
            targetEndMs = durationMs,
            speed = playbackSpeed,
            skipRanges = HushCastEngineBridge.getActiveSkipRanges()
        )
    } else {
        0L
    }
    val remainingDisplayMs = if (remainingTimeDisplay == app.podiumpodcasts.podium.RemainingTimeDisplayMode.RAW) {
        remainingAudioMs
    } else {
        remainingAdjustedMs
    }
    val remText = if (durationMs > 0L) {
        val rawText = "-${DateUtils.formatElapsedTime(remainingAudioMs / 1000L)}"
        val adjText = "-${DateUtils.formatElapsedTime(remainingAdjustedMs / 1000L)}"
        when (remainingTimeDisplay) {
            app.podiumpodcasts.podium.RemainingTimeDisplayMode.RAW -> rawText
            app.podiumpodcasts.podium.RemainingTimeDisplayMode.ADJUSTED -> adjText
            app.podiumpodcasts.podium.RemainingTimeDisplayMode.BOTH ->
                if (rawText != adjText) "$rawText ($adjText)" else rawText
        }
    } else {
        "--:--"
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Precise scrub floating pill with zoomed-in ±90s mini timeline
        AnimatedVisibility(
            visible = isDragging && durationMs > 0L,
            enter = fadeIn(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
            exit = fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
            // R8: the card floats above the bar with no layout height, so the page above doesn't
            // jump and the chapter row isn't clipped while scrubbing.
            modifier = Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, 0) { placeable.place(0, -placeable.height) }
            }
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

                    val scrubChapter = chapters.getOrNull(scrubChapterIndex)
                    if (scrubChapter != null) {
                        Text(
                            text = "${scrubChapterIndex + 1} of ${chapters.size} · ${scrubChapter.title}",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = displayFraction,
                        range = 0f..1f
                    )
                    setProgress { target ->
                        if (durationMs > 0L) {
                            onSeekToMs((target.coerceIn(0f, 1f) * durationMs).toLong())
                            true
                        } else {
                            false
                        }
                    }
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
                    val touchSlop = viewConfiguration.touchSlop

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x
                        val startY = down.position.y
                        var startedHorizontal = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                                ?: break

                            if (!change.pressed) {
                                // Quick tap without dragging past touchSlop -> seek to tapped position.
                                if (!change.isConsumed) {
                                    change.consume()
                                    val tapFrac = ((change.position.x - edgePad) / trackW).coerceIn(0f, 1f)
                                    val targetMs = (tapFrac * safeDuration).toLong().coerceIn(0L, safeDuration)
                                    onSeekToMs(targetMs)
                                }
                                return@awaitEachGesture
                            }

                            val dxTotal = change.position.x - startX
                            val dyTotal = change.position.y - startY
                            if (abs(dyTotal) > touchSlop && abs(dyTotal) > abs(dxTotal)) {
                                // Vertical swipe (e.g. closing the player sheet or opening Up Next):
                                // leave the gesture unconsumed and do not scrub.
                                return@awaitEachGesture
                            }
                            if (abs(dxTotal) > touchSlop && abs(dxTotal) >= abs(dyTotal)) {
                                startedHorizontal = true
                                change.consume()
                                break
                            }
                        }

                        if (!startedHorizontal) return@awaitEachGesture

                        isDragging = true
                        scrubSpeed = 1f
                        var currentFrac = ((startX - edgePad) / trackW).coerceIn(0f, 1f)
                        dragFraction = currentFrac
                        var lastX = startX

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
                // Read the live position here (draw phase) so ticks only redraw.
                val drawFraction = if (isDragging) dragFraction
                else (positionMsState.value.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
                val outer = CornerRadius(h / 2f, h / 2f)
                val inner = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                // R6: M3E-style track: two rounded segments with a gap at the handle; every mark
                // is clipped to them, so marks at the ends can't square off the round caps.
                val handleX = (drawFraction * w).coerceIn(0f, w)
                val gap = 4.dp.toPx() + (if (isDragging) 3.dp.toPx() else 2.dp.toPx())
                val leftEnd = handleX - gap
                val rightStart = handleX + gap
                val trackPath = Path().apply {
                    if (leftEnd > 0f) {
                        addRoundRect(
                            RoundRect(
                                left = 0f, top = 0f, right = leftEnd, bottom = h,
                                topLeftCornerRadius = outer, topRightCornerRadius = inner,
                                bottomRightCornerRadius = inner, bottomLeftCornerRadius = outer
                            )
                        )
                    }
                    if (rightStart < w) {
                        addRoundRect(
                            RoundRect(
                                left = rightStart, top = 0f, right = w, bottom = h,
                                topLeftCornerRadius = inner, topRightCornerRadius = outer,
                                bottomRightCornerRadius = outer, bottomLeftCornerRadius = inner
                            )
                        )
                    }
                }
                clipPath(trackPath) {
                    drawRect(color = inactiveTrackColor, topLeft = Offset.Zero, size = Size(w, h))
                    if (handleX > 0f) {
                        drawRect(color = activeTrackColor, topLeft = Offset.Zero, size = Size(handleX, h))
                    }
                    if (durationMs > 0L) {
                        val dur = durationMs.toFloat()
                        for (ad in adWindows) {
                            val x0 = (ad.startMs / dur).coerceIn(0f, 1f) * w
                            val x1 = (ad.endMs / dur).coerceIn(0f, 1f) * w
                            drawRect(
                                color = if (ad.isHighConfidence) adColor else suggestedAdColor,
                                topLeft = Offset(x0, 0f),
                                size = Size((x1 - x0).coerceAtLeast(3.dp.toPx()), h)
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
                }

                val thumbX = (drawFraction * w).coerceIn(0f, w)
                val thumbW = if (isDragging) 6.dp.toPx() else 4.dp.toPx()
                val thumbH = if (isDragging) 28.dp.toPx() else 22.dp.toPx()
                val thumbTop = (h - thumbH) / 2f
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
