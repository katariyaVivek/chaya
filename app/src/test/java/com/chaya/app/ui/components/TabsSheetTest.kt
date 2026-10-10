package com.chaya.app.ui.components

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chaya.app.browser.TabSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TabsSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val tabs = listOf(
        TabSummary(1, "A story", "https://www.news.example/a"),
        TabSummary(2, "", ""),
    )

    @Test
    fun `tabs are listed by title and site, and can be shown, closed or added`() {
        val shown = mutableListOf<Long>()
        val closed = mutableListOf<Long>()
        var added = 0
        composeRule.setContent {
            TabsSheet(
                tabs = tabs, activeId = 1, canOpenMore = true,
                onSelect = { shown += it }, onClose = { closed += it }, onNewTab = { added++ }, onDismiss = {},
            )
        }

        composeRule.onNodeWithText("2 tabs").assertExists()
        composeRule.onNodeWithText("news.example").assertExists()
        composeRule.onNodeWithText("Start page").performClick()
        composeRule.onNodeWithContentDescription("Close A story").performClick()
        composeRule.onNodeWithText("New tab").performClick()

        assertEquals(listOf(2L), shown)
        assertEquals(listOf(1L), closed)
        assertEquals(1, added)
    }

    @Test
    fun `at the limit no new tab can be opened`() {
        composeRule.setContent {
            TabsSheet(
                tabs = tabs, activeId = 1, canOpenMore = false,
                onSelect = {}, onClose = {}, onNewTab = {}, onDismiss = {},
            )
        }
        composeRule.onNodeWithText("New tab").assertIsNotEnabled()
    }
}
