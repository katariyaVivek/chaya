package com.chaya.app.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chaya.app.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Chaya's settings in one place: each row says what it is for and opens it; Back leaves. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `every setting is a row that opens it, and the look says which is chosen`() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            SettingsContent(
                themeMode = ThemeMode.DARK,
                snackbarHostState = SnackbarHostState(),
                onNavigateBack = { events += "back" },
                onAppearance = { events += "appearance" },
                onAiModel = { events += "ai model" },
                onSpeechModels = { events += "speech models" },
                onDiagnostics = { events += "diagnostics" },
            )
        }

        composeRule.onNodeWithText("Dark").assertExists()
        composeRule.onNodeWithText("Appearance").performClick()
        composeRule.onNodeWithText("AI model").performClick()
        composeRule.onNodeWithText("Speech models").performClick()
        composeRule.onNodeWithText("Diagnostics").performClick()
        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(listOf("appearance", "ai model", "speech models", "diagnostics", "back"), events)
    }
}
