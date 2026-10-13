package com.chaya.app.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Which way a sideways swipe along the bottom bar moves through the tabs. */
internal object TabSwipe {
    /** How far the finger has to travel sideways before letting go switches the tab. */
    val DISTANCE = 48.dp

    /** How much of the finger's travel the bar follows, so it moves enough to show the swipe is felt. */
    const val FOLLOW = 0.3f

    /**
     * The step through the tabs for a swipe that moved [dragX] pixels: finger to the left shows the next tab
     * (1), to the right the one before (-1), as Chrome's toolbar does; 0 for a swipe shorter than [distance].
     */
    fun stepFor(dragX: Float, distance: Float): Int = when {
        dragX <= -distance -> 1
        dragX >= distance -> -1
        else -> 0
    }
}

/**
 * Swiping sideways on this element shows the tab beside the one shown; [onSwipe] gets the step (see
 * [TabSwipe.stepFor]). Taps still reach the buttons inside: the swipe takes the touch only once it has moved
 * sideways past the touch slop.
 */
internal fun Modifier.swipeBetweenTabs(onSwipe: (step: Int) -> Unit): Modifier = composed {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val distance = with(LocalDensity.current) { TabSwipe.DISTANCE.toPx() }
    // The touch is read outside the layer that moves, so the bar following the finger does not change the drag.
    pointerInput(Unit) {
        var dragged = 0f
        detectHorizontalDragGestures(
            onDragStart = { dragged = 0f },
            onDragEnd = {
                val step = TabSwipe.stepFor(dragged, distance)
                if (step != 0) currentOnSwipe(step)
                scope.launch { offset.animateTo(0f) }
            },
            onDragCancel = { scope.launch { offset.animateTo(0f) } },
        ) { change, amount ->
            change.consume()
            dragged += amount
            scope.launch { offset.snapTo(dragged * TabSwipe.FOLLOW) }
        }
    }.graphicsLayer { translationX = offset.value }
}
