package com.chaya.app.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Swiping sideways along the bottom bar moves through the tabs; tapping its buttons still works. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TabSwipeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val steps = mutableListOf<Int>()
    private var taps = 0

    private fun showBar() {
        composeRule.setContent {
            Box(Modifier.size(360.dp, 56.dp).testTag("bar").swipeBetweenTabs { steps += it }) {
                TextButton(onClick = { taps++ }) { Text("Back") }
            }
        }
    }

    @Test
    fun `finger to the left shows the next tab, to the right the one before`() {
        showBar()
        composeRule.onNodeWithTag("bar").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("bar").performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(listOf(1, -1), steps)
    }

    @Test
    fun `a short slide does not switch, and a tap still reaches the button`() {
        showBar()
        composeRule.onNodeWithTag("bar").performTouchInput { swipeLeft(startX = centerX, endX = centerX - 30f) }
        composeRule.onNodeWithText("Back").performClick()
        composeRule.waitForIdle()

        assertEquals(emptyList<Int>(), steps)
        assertEquals(1, taps)
    }

    @Test
    fun `the step follows the direction and the distance`() {
        assertEquals(1, TabSwipe.stepFor(-120f, 100f))
        assertEquals(-1, TabSwipe.stepFor(120f, 100f))
        assertEquals(0, TabSwipe.stepFor(-60f, 100f))
        assertEquals(0, TabSwipe.stepFor(0f, 100f))
    }
}
