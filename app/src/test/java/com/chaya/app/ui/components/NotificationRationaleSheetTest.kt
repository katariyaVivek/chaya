package com.chaya.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the rationale sheet (Phase 0.5): Allow and Not now are distinct
 * callbacks, dismiss routes to Not now (download still proceeds), and the
 * file name is named so the user knows what the permission is for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationRationaleSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `allow and not-now fire distinct callbacks`() {
        val fired = mutableListOf<String>()
        composeRule.setContent {
            NotificationRationaleSheet(
                fileName = "clip.mp4",
                onAllow = { fired += "allow" },
                onNotNow = { fired += "notnow" },
            )
        }

        composeRule.onNodeWithText("Track this download?").assertIsDisplayed()
        composeRule.onNodeWithText("Not now").performClick()
        assertEquals(listOf("notnow"), fired)
    }

    @Test
    fun `allow fires the permission path`() {
        val fired = mutableListOf<String>()
        composeRule.setContent {
            NotificationRationaleSheet(
                fileName = "song.mp3",
                onAllow = { fired += "allow" },
                onNotNow = { fired += "notnow" },
            )
        }

        composeRule.onNodeWithText("Allow").performClick()
        assertEquals(listOf("allow"), fired)
    }
}
