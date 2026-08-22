package com.chaya.app.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Motion tokens — "butter smooth": springs are critically damped (no bounce),
 * tweens ease out hard. See DESIGN.md.
 */
object ChayaMotion {
    /** Ease-out-expo — the workhorse for tweens. */
    val EasingStandard = CubicBezierEasing(0.16f, 1f, 0.30f, 1f)

    /** Symmetric ease for exits and small shifts. */
    val EasingExit = CubicBezierEasing(0.40f, 0f, 0.60f, 1f)

    const val DurationShort = 180
    const val DurationMedium = 300
    const val DurationLong = 450

    /** Critically-damped spring: settles fast, zero overshoot. */
    fun <T> springSmooth(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    fun <T> tweenStandard(duration: Int = DurationMedium) =
        tween<T>(duration, easing = EasingStandard)

    fun <T> tweenShort() = tween<T>(DurationShort, easing = EasingStandard)
}

/**
 * Press feedback: subtle scale-down while touched. Works alongside any
 * clickable / Card(onClick) because it observes raw pointer events on its own
 * node instead of an interaction source.
 */
fun Modifier.pressScale(pressedScale: Float = 0.96f): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = ChayaMotion.springSmooth(),
        label = "pressScale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(pressedScale) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                waitForUpOrCancellation()
                pressed = false
            }
        }
}

/**
 * Fade-rise entrance with a per-index delay. Use for static sections on first
 * composition (start screen, empty states), not for rebinding list items.
 */
@Composable
fun StaggeredAppear(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val alpha = remember { Animatable(0f) }
    val offsetY = remember { Animatable(20f) }
    LaunchedEffect(Unit) {
        delay(index * 70L)
        launch {
            alpha.animateTo(1f, ChayaMotion.tweenStandard())
        }
        offsetY.animateTo(0f, ChayaMotion.tweenStandard())
    }
    Box(modifier.graphicsLayer {
        this.alpha = alpha.value
        translationY = offsetY.value
    }) {
        content()
    }
}
