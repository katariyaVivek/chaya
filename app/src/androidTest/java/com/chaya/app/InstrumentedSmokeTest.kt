package com.chaya.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chaya.app.ui.components.DetectedMediaSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator smoke test (Phase 1.3/1.4): proves the instrumented pipeline
 * itself works on a real device. Runs ONLY via connectedDebugAndroidTest
 * on the nightly/manual emulator job — never in testDebugUnitTest. Real
 * page loads stay out by design; fixtures over live internet.
 */
@RunWith(AndroidJUnit4::class)
class InstrumentedSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyMediaSheetRendersTeachingLine() {
        composeRule.setContent {
            DetectedMediaSheet(mediaList = emptyList(), onDismiss = {}, onDownload = {})
        }

        composeRule.onNodeWithText("Nothing found yet. Play or scroll the page.").assertExists()
    }
}
