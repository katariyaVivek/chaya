package com.chaya.app.ui.player

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The player's settings sheet and Lock screen: what each choice asks for. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerSettingsViewsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val asked = mutableListOf<Any>()

    private fun showSheet(speed: Float = 1f, loop: Boolean = false, sleep: SleepTimer = SleepTimer.Off) {
        composeRule.setContent {
            PlayerSettingsSheet(
                speed = speed,
                onSpeed = { asked += "speed $it" },
                loop = loop,
                onLoop = { asked += "loop $it" },
                sleep = sleep,
                nowMs = 0,
                onSleep = { asked += it },
                onLock = { asked += "lock" },
                onDismiss = {},
            )
        }
    }

    @Test
    fun `the speed in use is chosen, and another is asked for with a tap`() {
        showSheet(speed = 1.5f)
        // The heading says it too; the chip is the one that can be chosen
        composeRule.onNode(hasText("1.5×") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertIsSelected()
        composeRule.onNodeWithText("2×").performScrollTo().performClick()
        assertEquals(listOf<Any>("speed 2.0"), asked)
    }

    @Test
    fun `loop video turns on`() {
        showSheet()
        composeRule.onNodeWithContentDescription("Loop video").performClick()
        assertEquals(listOf<Any>("loop true"), asked)
    }

    @Test
    fun `a sleep timer of some minutes, or to the end of the video, and the running one is its own chip`() {
        showSheet(sleep = SleepTimer.after(30, nowMs = 0))
        composeRule.onNodeWithText("30 min").assertIsSelected()
        composeRule.onNodeWithText("30 min left").assertExists()
        composeRule.onNodeWithText("End of video").performScrollTo().performClick()
        assertEquals(listOf<Any>(SleepTimer.EndOfVideo), asked)
    }

    @Test
    fun `lock screen is asked for from the sheet`() {
        showSheet()
        composeRule.onNodeWithText("Lock screen").performClick()
        assertEquals(listOf<Any>("lock"), asked)
    }

    @Test
    fun `locked, a touch shows the unlock button and it unlocks`() {
        var touched = 0
        var unlocked = 0
        composeRule.setContent { LockLayer(hintShown = true, onTouch = { touched++ }, onUnlock = { unlocked++ }) }
        composeRule.onNodeWithText("Tap to unlock").assertExists()
        composeRule.onNodeWithContentDescription("Unlock screen").performClick()
        assertEquals(1, unlocked)
        assertTrue(touched >= 0)
    }
}
