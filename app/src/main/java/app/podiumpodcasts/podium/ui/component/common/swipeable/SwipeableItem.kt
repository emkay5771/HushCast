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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.derivedStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    val actionSlotWidthDp = 68.dp
    val startSlots = listOfNotNull(startAction, secondaryStartAction)
    val endSlots = listOfNotNull(endAction, secondaryEndAction)
    val startRevealPx = with(density) { (actionSlotWidthDp * startSlots.size).toPx() }
    val endRevealPx = with(density) { (actionSlotWidthDp * endSlots.size).toPx() }
    val halfThresholdPx = with(density) { 56.dp.toPx() }
    val flingVelocityThresholdPx = with(density) { 600.dp.toPx() }
    val minFullSwipePx = with(density) { 180.dp.toPx() }
    // S7: the row width is kept in a plain holder, not snapshot state, so a size change doesn't
    // trigger another recomposition.  It's only read while dragging.
    val itemWidthHolder = remember { FloatArray(1) }
    fun currentFullSwipeThresholdPx(): Float = maxOf(minFullSwipePx, itemWidthHolder[0] * 0.50f)

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
            var settled = false
            try {
                if (!result.isDismissed) {
                    offsetX.animateTo(0f, spatialSpec)
                }
                if (result.message.isNotBlank()) {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val snackBarResult = snackbarHostState.showSnackbar(
                        message = result.message,
                        actionLabel = if (result.showUndo) undo else null,
                        duration = SnackbarDuration.Short
                    )
                    settled = true
                    if (snackBarResult == SnackbarResult.ActionPerformed) {
                        result.onUndo()
                    } else {
                        result.onTimeout?.invoke()
                    }
                } else {
                    settled = true
                    result.onTimeout?.invoke()
                }
            } finally {
                // Item 2: if the screen closes while the snackbar is up, the action stands
                // (the user didn't undo it), so finish it even though this scope is cancelled.
                if (!settled) {
                    withContext(NonCancellable) { result.onTimeout?.invoke() }
                }
            }
        }
    }

    // S7: derived, so a drag only recomposes this row when the tray side or the "full swipe"
    // state changes, not on every pixel.
    val swipeSide by remember {
        derivedStateOf { if (offsetX.value > 0f) 1 else if (offsetX.value < 0f) -1 else 0 }
    }
    val hasStart = startAction != null
    val hasEnd = endAction != null
    val isFullStart by remember(hasStart) {
        derivedStateOf { hasStart && offsetX.value >= currentFullSwipeThresholdPx() }
    }
    val isFullEnd by remember(hasEnd) {
        derivedStateOf { hasEnd && offsetX.value <= -currentFullSwipeThresholdPx() }
    }

    // M11: TalkBack lists each swipe action by its name.
    val startPrimaryLabel = startAction?.label?.invoke()
    val startSecondaryLabel = secondaryStartAction?.label?.invoke()
    val endPrimaryLabel = endAction?.label?.invoke()
    val endSecondaryLabel = secondaryEndAction?.label?.invoke()
    val a11yActions = buildList {
        if (startPrimaryHandler != null) {
            add(CustomAccessibilityAction(startPrimaryLabel.orIfBlank("Swipe right action")) {
                executeAction(startPrimaryHandler)
                true
            })
        }
        if (startSecondaryHandler != null) {
            add(CustomAccessibilityAction(startSecondaryLabel.orIfBlank("Second swipe right action")) {
                executeAction(startSecondaryHandler)
                true
            })
        }
        if (endPrimaryHandler != null) {
            add(CustomAccessibilityAction(endPrimaryLabel.orIfBlank("Swipe left action")) {
                executeAction(endPrimaryHandler)
                true
            })
        }
        if (endSecondaryHandler != null) {
            add(CustomAccessibilityAction(endSecondaryLabel.orIfBlank("Second swipe left action")) {
                executeAction(endSecondaryHandler)
                true
            })
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { itemWidthHolder[0] = it.width.toFloat() }
            .semantics {
                if (a11yActions.isNotEmpty()) {
                    customActions = a11yActions
                }
            }
    ) {
        // Background action tray
        if (swipeSide > 0 && startSlots.isNotEmpty()) {
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
                val startPrimaryShort = startAction.shortLabel()
                Box(
                    modifier = Modifier
                        .width(actionSlotWidthDp)
                        .fillMaxHeight()
                        .clickable(onClickLabel = startPrimaryLabel) { executeAction(startPrimaryHandler) },
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.graphicsLayer {
                            val s = if (isFullStart) 1.2f else 1.0f
                            scaleX = s
                            scaleY = s
                        }
                    ) {
                        Icon(
                            imageVector = primaryStyleResolved.icon,
                            contentDescription = startPrimaryLabel,
                            tint = primaryIconTint,
                            modifier = Modifier.size(20.dp)
                        )
                        if (startPrimaryShort.isNotEmpty()) {
                            androidx.compose.material3.Text(
                                text = startPrimaryShort,
                                style = MaterialTheme.typography.labelSmall,
                                color = primaryIconTint,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
                if (secondaryStartAction != null && !isFullStart) {
                    val secStyle = secondaryStartAction.style()
                    val startSecondaryShort = secondaryStartAction.shortLabel()
                    Box(
                        modifier = Modifier
                            .width(actionSlotWidthDp)
                            .fillMaxHeight()
                            .background(secStyle.backgroundColor.copy(alpha = 0.85f))
                            .clickable(onClickLabel = startSecondaryLabel) { executeAction(startSecondaryHandler) },
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.layout.Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = secStyle.icon,
                                contentDescription = startSecondaryLabel,
                                tint = secStyle.iconTint,
                                modifier = Modifier.size(20.dp)
                            )
                            if (startSecondaryShort.isNotEmpty()) {
                                androidx.compose.material3.Text(
                                    text = startSecondaryShort,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = secStyle.iconTint,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }
        } else if (swipeSide < 0 && endSlots.isNotEmpty()) {
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
                    val endSecondaryShort = secondaryEndAction.shortLabel()
                    Box(
                        modifier = Modifier
                            .width(actionSlotWidthDp)
                            .fillMaxHeight()
                            .background(secStyle.backgroundColor.copy(alpha = 0.85f))
                            .clickable(onClickLabel = endSecondaryLabel) { executeAction(endSecondaryHandler) },
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.layout.Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = secStyle.icon,
                                contentDescription = endSecondaryLabel,
                                tint = secStyle.iconTint,
                                modifier = Modifier.size(20.dp)
                            )
                            if (endSecondaryShort.isNotEmpty()) {
                                androidx.compose.material3.Text(
                                    text = endSecondaryShort,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = secStyle.iconTint,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
                val primaryStyleResolved = endAction.style()
                val endPrimaryShort = endAction.shortLabel()
                val primaryIconTint by animateColorAsState(
                    if (isFullEnd) primaryStyleResolved.activeIconTint else primaryStyleResolved.iconTint,
                    animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                    label = "swipeEndPrimaryTint"
                )
                Box(
                    modifier = Modifier
                        .width(actionSlotWidthDp)
                        .fillMaxHeight()
                        .clickable(onClickLabel = endPrimaryLabel) { executeAction(endPrimaryHandler) },
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.graphicsLayer {
                            val s = if (isFullEnd) 1.2f else 1.0f
                            scaleX = s
                            scaleY = s
                        }
                    ) {
                        Icon(
                            imageVector = primaryStyleResolved.icon,
                            contentDescription = endPrimaryLabel,
                            tint = primaryIconTint,
                            modifier = Modifier.size(20.dp)
                        )
                        if (endPrimaryShort.isNotEmpty()) {
                            androidx.compose.material3.Text(
                                text = endPrimaryShort,
                                style = MaterialTheme.typography.labelSmall,
                                color = primaryIconTint,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }

        // Foreground draggable content
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    enabled = startAction != null || endAction != null,
                    state = rememberDraggableState { rawDelta ->
                        val delta = rawDelta * dirSign
                        val fullSwipeThresholdPx = currentFullSwipeThresholdPx()
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
                        val fullSwipeThresholdPx = currentFullSwipeThresholdPx()
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
private fun String?.orIfBlank(fallback: String): String = if (this.isNullOrBlank()) fallback else this
