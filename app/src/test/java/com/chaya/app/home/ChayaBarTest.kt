package com.chaya.app.home

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Chaya's one bar: the same places at the same ends everywhere, the middle by place, one ink circle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChayaBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()

    private fun show(
        place: Place,
        middle: BarMiddle = BarMiddle.Browse,
        action: BarAction = BarAction.Go,
        progress: HomeShelf.Progress? = null,
    ) {
        composeRule.setContent {
            ChayaBar(
                place = place,
                middle = middle,
                action = action,
                progress = progress,
                onPlace = { events += "place $it" },
                onAction = { events += "action" },
                onBack = { events += "back" },
                onForward = { events += "forward" },
                onTabs = { events += "tabs" },
            )
        }
    }

    @Test
    fun `on Home, Home is lit and the others go to their places`() {
        show(Place.HOME)

        composeRule.onNodeWithContentDescription("Home").assertIsSelected()
        composeRule.onNodeWithContentDescription("Library").assertIsNotSelected()
        composeRule.onNodeWithContentDescription("Home").performClick()
        composeRule.onNodeWithContentDescription("Browse").performClick()
        composeRule.onNodeWithContentDescription("Library").performClick()

        assertEquals(listOf("place BROWSE", "place LIBRARY"), events)
    }

    @Test
    fun `the ink circle opens a link or search from Home and the Library`() {
        show(Place.LIBRARY)

        composeRule.onNodeWithContentDescription("Library").assertIsSelected()
        composeRule.onNodeWithContentDescription("Open a link or search").performClick()

        assertEquals(listOf("action"), events)
    }

    @Test
    fun `while browsing, the middle is the page's own controls, Home and Library still at the ends`() {
        show(Place.BROWSE, BarMiddle.Page(canGoBack = true, canGoForward = false, tabCount = 3), BarAction.NewTab)

        composeRule.onNodeWithContentDescription("Back").assertIsEnabled().performClick()
        composeRule.onNodeWithContentDescription("Forward").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Tabs (3)").performClick()
        composeRule.onNodeWithContentDescription("New tab").performClick()
        composeRule.onNodeWithContentDescription("Home").assertIsNotSelected().performClick()

        assertEquals(listOf("back", "tabs", "action", "place HOME"), events)
    }

    @Test
    fun `a page with something to save turns the circle into Save, named for it`() {
        show(Place.BROWSE, BarMiddle.Page(false, false, 1), BarAction.Save("Save Big Buck Bunny"))

        composeRule.onNodeWithContentDescription("Save Big Buck Bunny").performClick()

        assertEquals(listOf("action"), events)
    }

    @Test
    fun `the Library slot says how far the downloads are, on every screen`() {
        show(Place.HOME, progress = HomeShelf.Progress(running = 2, fraction = 0.42f))
        composeRule.onNodeWithContentDescription("42% saved", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a download whose size is not known yet is counted instead`() {
        show(Place.BROWSE, BarMiddle.Page(false, false, 1), BarAction.NewTab, HomeShelf.Progress(running = 1, fraction = null))
        composeRule.onNodeWithContentDescription("1 saving", useUnmergedTree = true).assertExists()
    }
}
