package app.podiumpodcasts.podium.ui.component.common.swipeable

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.podiumpodcasts.podium.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SwipeableItem(
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    startAction: SwipeableItemAction? = null,
    secondaryStartAction: SwipeableItemAction? = null,
    endAction: SwipeableItemAction? = null,
    secondaryEndAction: SwipeableItemAction? = null,
    content: @Composable RowScope.() -> Unit
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val dirSign = if (isRtl) -1f else 1f
    val undo = stringResource(R.string.common_action_undo)
    val spatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()

    val actionSlotWidthDp = 64.dp
    val startSlots = listOfNotNull(startAction, secondaryStartAction)
    val endSlots = listOfNotNull(endAction, secondaryEndAction)
    val startRevealPx = with(density) { (actionSlotWidthDp * startSlots.size).toPx() }
    val endRevealPx = with(density) { (actionSlotWidthDp * endSlots.size).toPx() }
    val halfThresholdPx = with(density) { 56.dp.toPx() }
    val flingVelocityThresholdPx = with(density) { 600.dp.toPx() }
    var itemWidthPx by remember { mutableFloatStateOf(0f) }

    val offsetX = remember { Animatable(0f) }
    var wasOverFullThreshold by remember { mutableStateOf(false) }

    val startPrimaryHandler = startAction?.onActionHandler()
    val startSecondaryHandler = secondaryStartAction?.onActionHandler()
    val endPrimaryHandler = endAction?.onActionHandler()
    val endSecondaryHandler = secondaryEndAction?.onActionHandler()

    fun executeAction(handler: (suspend () -> SwipeableItemActionResult)?) {
        if (handler == null) return
        scope.launch {
            val result = handler()
            if (!result.isDismissed) {
                offsetX.animateTo(0f, spatialSpec)
            }
            snackbarHostState.currentSnackbarData?.dismiss()
            val snackBarResult = snackbarHostState.showSnackbar(
                message = result.message,
                actionLabel = undo,
                duration = SnackbarDuration.Short
            )
            if (snackBarResult == SnackbarResult.ActionPerformed) {
                result.onUndo()
            }
        }
    }

    val fullSwipeThresholdPx = maxOf(
        with(density) { 180.dp.toPx() },
        itemWidthPx * 0.50f
    )
    val isFullStart = offsetX.value >= fullSwipeThresholdPx && startAction != null
    val isFullEnd = offsetX.value <= -fullSwipeThresholdPx && endAction != null

    val a11yActions = buildList {
        if (startPrimaryHandler != null) {
            add(CustomAccessibilityAction("Primary start action") {
                executeAction(startPrimaryHandler)
                true
            })
        }
        if (startSecondaryHandler != null) {
            add(CustomAccessibilityAction("Secondary start action") {
                executeAction(startSecondaryHandler)
                true
            })
        }
        if (endPrimaryHandler != null) {
            add(CustomAccessibilityAction("Primary end action") {
                executeAction(endPrimaryHandler)
                true
            })
        }
        if (endSecondaryHandler != null) {
            add(CustomAccessibilityAction("Secondary end action") {
                executeAction(endSecondaryHandler)
                true
            })
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { itemWidthPx = it.width.toFloat() }
            .semantics {
                if (a11yActions.isNotEmpty()) {
                    customActions = a11yActions
                }
            }
    ) {
        // Background action tray
        if (offsetX.value > 0f && startSlots.isNotEmpty()) {
            val primaryStyle = startAction!!.style()
            val bgColor by animateColorAsState(
                if (isFullStart) primaryStyle.activeBackgroundColor else primaryStyle.backgroundColor,
                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "swipeStartBg"
            )
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(bgColor),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val primaryStyleResolved = startAction.style()
                val primaryIconTint by animateColorAsState(
                    if (isFullStart) primaryStyleResolved.activeIconTint else primaryStyleResolved.iconTint,
                    animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                    label = "swipeStartPrimaryTint"
                )
                Box(
                    modifier = Modifier
                        .width(actionSlotWidthDp)
                        .fillMaxHeight()
                        .clickable { executeAction(startPrimaryHandler) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = primaryStyleResolved.icon,
                        contentDescription = null,
                        tint = primaryIconTint,
                        modifier = Modifier.graphicsLayer {
                            val s = if (isFullStart) 1.25f else 1.0f
                            scaleX = s
                            scaleY = s
                        }
                    )
                }
                if (secondaryStartAction != null && !isFullStart) {
                    val secStyle = secondaryStartAction.style()
                    Box(
                        modifier = Modifier
                            .width(actionSlotWidthDp)
                            .fillMaxHeight()
                            .background(secStyle.backgroundColor.copy(alpha = 0.85f))
                            .clickable { executeAction(startSecondaryHandler) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = secStyle.icon,
                            contentDescription = null,
                            tint = secStyle.iconTint
                        )
                    }
                }
            }
        } else if (offsetX.value < 0f && endSlots.isNotEmpty()) {
            val primaryStyle = endAction!!.style()
            val bgColor by animateColorAsState(
                if (isFullEnd) primaryStyle.activeBackgroundColor else primaryStyle.backgroundColor,
                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "swipeEndBg"
            )
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(bgColor),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (secondaryEndAction != null && !isFullEnd) {
                    val secStyle = secondaryEndAction.style()
                    Box(
                        modifier = Modifier
                            .width(actionSlotWidthDp)
                            .fillMaxHeight()
                            .background(secStyle.backgroundColor.copy(alpha = 0.85f))
                            .clickable { executeAction(endSecondaryHandler) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = secStyle.icon,
                            contentDescription = null,
                            tint = secStyle.iconTint
                        )
                    }
                }
                val primaryStyleResolved = endAction.style()
                val primaryIconTint by animateColorAsState(
                    if (isFullEnd) primaryStyleResolved.activeIconTint else primaryStyleResolved.iconTint,
                    animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                    label = "swipeEndPrimaryTint"
                )
                Box(
                    modifier = Modifier
                        .width(actionSlotWidthDp)
                        .fillMaxHeight()
                        .clickable { executeAction(endPrimaryHandler) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = primaryStyleResolved.icon,
                        contentDescription = null,
                        tint = primaryIconTint,
                        modifier = Modifier.graphicsLayer {
                            val s = if (isFullEnd) 1.25f else 1.0f
                            scaleX = s
                            scaleY = s
                        }
                    )
                }
            }
        }

        // Foreground draggable content
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset((offsetX.value * dirSign).roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    enabled = startAction != null || endAction != null,
                    state = rememberDraggableState { rawDelta ->
                        val delta = rawDelta * dirSign
                        val minBound = if (endAction != null) -(fullSwipeThresholdPx * 1.15f) else 0f
                        val maxBound = if (startAction != null) (fullSwipeThresholdPx * 1.15f) else 0f
                        val next = (offsetX.value + delta).coerceIn(minBound, maxBound)
                        val overThreshold = (next >= fullSwipeThresholdPx && startAction != null) ||
                            (next <= -fullSwipeThresholdPx && endAction != null)
                        if (overThreshold && !wasOverFullThreshold) {
                            haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        }
                        wasOverFullThreshold = overThreshold
                        scope.launch { offsetX.snapTo(next) }
                    },
                    onDragStopped = { rawVelocity ->
                        val cur = offsetX.value
                        val velocity = rawVelocity * dirSign
                        wasOverFullThreshold = false
                        when {
                            (cur >= fullSwipeThresholdPx || (cur > halfThresholdPx && velocity >= flingVelocityThresholdPx)) && startAction != null -> {
                                haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                executeAction(startPrimaryHandler)
                            }
                            (cur <= -fullSwipeThresholdPx || (cur < -halfThresholdPx && velocity <= -flingVelocityThresholdPx)) && endAction != null -> {
                                haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                executeAction(endPrimaryHandler)
                            }
                            cur >= halfThresholdPx && startAction != null -> {
                                scope.launch { offsetX.animateTo(startRevealPx, spatialSpec) }
                            }
                            cur <= -halfThresholdPx && endAction != null -> {
                                scope.launch { offsetX.animateTo(-endRevealPx, spatialSpec) }
                            }
                            else -> {
                                scope.launch { offsetX.animateTo(0f, spatialSpec) }
                            }
                        }
                    }
                ),
            content = content
        )
    }
}