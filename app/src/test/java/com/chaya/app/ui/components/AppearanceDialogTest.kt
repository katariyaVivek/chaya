package com.chaya.app.ui.components

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chaya.app.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppearanceDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the current choice is marked and tapping another reports it`() {
        val chosen = mutableListOf<ThemeMode>()
        composeRule.setContent {
            AppearanceDialog(current = ThemeMode.SYSTEM, onSelect = { chosen += it }, onDismiss = {})
        }

        composeRule.onNodeWithText("Same as phone").assertIsSelected()
        composeRule.onNodeWithText("Light").performClick()

        assertEquals(listOf(ThemeMode.LIGHT), chosen)
    }
}
