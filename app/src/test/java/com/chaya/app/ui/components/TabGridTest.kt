package com.chaya.app.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import com.chaya.app.browser.TabSummary
import com.chaya.app.home.BarAction
import com.chaya.app.home.BarMiddle
import com.chaya.app.home.ChayaBar
import com.chaya.app.home.Place
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The tab grid: open, close with Undo, search, the tab shown, a new tab, close all. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TabGridTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val tabs = listOf(
        TabSummary(1, "Launch Event 2026", "https://news.example/launch"),
        TabSummary(2, "Weather today", "https://weather.example/"),
        TabSummary(3, "", ""),
    )

    private val events = mutableListOf<String>()

    private fun show(activeId: Long = 2) {
        composeRule.setContent {
            TabGrid(
                tabs = tabs,
                activeId = activeId,
                onSelect = { events += "select $it" },
                onClose = { events += "close $it" },
                onNewTab = { events += "new" },
                onCloseAll = { events += "close all" },
                onDismiss = { events += "dismiss" },
                bottomBar = { newTab ->
                    ChayaBar(
                        place = Place.BROWSE,
                        middle = BarMiddle.Page(canGoBack = true, canGoForward = false, tabCount = 3, tabsShown = true),
                        action = BarAction.NewTab,
                        progress = null,
                        onPlace = { events += "place $it" },
                        onAction = newTab,
                        onTabs = { events += "tabs" },
                    )
                },
            )
        }
    }

    @Test
    fun `every tab is a card, the start page named as such, and tapping one shows it`() {
        show()

        composeRule.onNodeWithText("Launch Event 2026").assertExists()
        composeRule.onAllNodesWithText("New tab").assertCountEquals(1)
        composeRule.onNodeWithText("Launch Event 2026").performClick()

        assertEquals(listOf("select 1"), events)
    }

    @Test
    fun `the tab shown is marked`() {
        show(activeId = 2)

        composeRule.onNodeWithText("Weather today", useUnmergedTree = false).assertIsSelected()
        composeRule.onNodeWithText("Launch Event 2026").assertIsNotSelected()
    }

    @Test
    fun `search keeps the cards whose title or address match`() {
        show()

        composeRule.onNode(hasSetTextAction()).performTextInput("news.example")

        composeRule.onNodeWithText("Launch Event 2026").assertExists()
        composeRule.onAllNodesWithText("Weather today").assertCountEquals(0)
        composeRule.onAllNodesWithText("New tab").assertCountEquals(0)
    }

    @Test
    fun `a closed tab goes at once, comes back with Undo, and is closed for real when Undo runs out`() {
        show()

        composeRule.onNodeWithContentDescription("Close Weather today").performClick()
        composeRule.onAllNodesWithText("Weather today").assertCountEquals(0)
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.onNodeWithText("Weather today").assertExists()
        assertEquals(emptyList<String>(), events)

        composeRule.onNodeWithContentDescription("Close Weather today").performClick()
        composeRule.mainClock.advanceTimeBy(10_000)
        composeRule.waitForIdle()

        assertEquals(listOf("close 2"), events)
    }

    @Test
    fun `opening a tab while an Undo shows closes the closed one first`() {
        show()

        composeRule.onNodeWithContentDescription("Close Weather today").performClick()
        composeRule.onNodeWithText("Launch Event 2026").performClick()

        assertEquals(listOf("close 2", "select 1"), events)
    }

    @Test
    fun `the bar's ink circle opens a new tab, closing first a tab waiting out its Undo`() {
        show()
        composeRule.onNodeWithContentDescription("Close Weather today").performClick()
        composeRule.onNodeWithContentDescription("New tab").performClick()
        assertEquals(listOf("close 2", "new"), events)
    }

    @Test
    fun `the bar's Tabs closes the grid, its Back and Forward wait for the page`() {
        show()
        composeRule.onNodeWithContentDescription("Close tabs").performClick()
        assertEquals(listOf("tabs"), events)
        composeRule.onNodeWithContentDescription("Back").assertIsNotEnabled()
    }

    @Test
    fun `the header says how many tabs are open, and counts one closed at once`() {
        show()
        composeRule.onNodeWithText("3 tabs").assertExists()
        composeRule.onNodeWithContentDescription("Close Weather today").performClick()
        composeRule.onNodeWithText("2 tabs").assertExists()
    }

    @Test
    fun `close all tabs asks first`() {
        show()

        composeRule.onNodeWithContentDescription("More tab actions").performClick()
        composeRule.onNodeWithText("Close all tabs").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<String>(), events)

        composeRule.onNodeWithContentDescription("More tab actions").performClick()
        composeRule.onNodeWithText("Close all tabs").performClick()
        composeRule.onNodeWithText("Close all").performClick()
        assertEquals(listOf("close all"), events)
    }
}
