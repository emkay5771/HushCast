package app.podiumpodcasts.podium.ui.dialog.bottomsheet.media

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.hushcast.automute.dsp.AdWindow
import app.hushcast.automute.dsp.MuteWindow
import app.hushcast.automute.dsp.SkipAwareSeekCalculator
import app.podiumpodcasts.podium.background.HushCastEngineBridge
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
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
        targetValue = if (isDragging) 12.dp else if (compact) 6.dp else 8.dp,
        label = "seekTrackHeight"
    )

    val adColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.72f)
    val suggestedAdColor = Color(0xFFFFB300).copy(alpha = 0.75f)
    val muteTickColor = MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
    val activeTrackColor = MaterialTheme.colorScheme.primary
    val inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant

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
        val a11yActions = buildList {
            if (activeAd != null && onSkipAd != null) {
                add(
                    CustomAccessibilityAction("Skip sponsor segment") {
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
                append(", in sponsor segment")
            }
        }

        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Slider(
            value = displayFraction,
            onValueChange = { next ->
                if (!isDragging) isDragging = true
                dragFraction = next.coerceIn(0f, 1f)
            },
            onValueChangeFinished = {
                val targetMs = (dragFraction * safeDuration).toLong().coerceIn(0L, safeDuration)
                isDragging = false
                onSeekToMs(targetMs)
            },
            enabled = durationMs > 0L,
            interactionSource = interactionSource,
            colors = SliderDefaults.colors(
                thumbColor = if (activeAd != null) MaterialTheme.colorScheme.tertiary else activeTrackColor,
                activeTrackColor = activeTrackColor,
                inactiveTrackColor = inactiveTrackColor
            ),
            track = { sliderState ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(trackHeight),
                    contentAlignment = Alignment.Center
                ) {
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(trackHeight)
                            .drawWithContent {
                                drawContent()
                                if (durationMs > 0L) {
                                    val w = size.width
                                    val h = size.height
                                    val dur = durationMs.toFloat()
                                    for (ad in adWindows) {
                                        val x0 = (ad.startMs / dur).coerceIn(0f, 1f) * w
                                        val x1 = (ad.endMs / dur).coerceIn(0f, 1f) * w
                                        drawRoundRect(
                                            color = if (ad.isHighConfidence) adColor else suggestedAdColor,
                                            topLeft = Offset(x0, 0f),
                                            size = Size((x1 - x0).coerceAtLeast(3.dp.toPx()), h),
                                            cornerRadius = CornerRadius(h / 2f, h / 2f)
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
                            },
                        drawStopIndicator = null,
                        thumbTrackGapSize = 4.dp
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    stateDescription = stateDesc
                    if (a11yActions.isNotEmpty()) {
                        customActions = a11yActions
                    }
                }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = posText,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = remText,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
