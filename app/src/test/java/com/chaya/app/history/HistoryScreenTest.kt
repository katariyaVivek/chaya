package com.chaya.app.history

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.chaya.app.database.BookmarkEntity
import com.chaya.app.database.HistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

/** The History and Bookmarks screens: pages by day, search, delete one, clear a span, the switch, bookmarks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val utc = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-10T12:00:00Z").toEpochMilli()
    private fun at(time: String) = Instant.parse(time).toEpochMilli()

    private val rows = listOf(
        HistoryEntity("https://news.example/launch", "Launch story", at("2026-10-10T09:00:00Z"), 1),
        HistoryEntity("https://weather.example/", "Weather today", at("2026-10-10T11:00:00Z"), 3),
        HistoryEntity("https://www.recipes.example/soup", "", at("2026-10-09T20:00:00Z"), 1),
    )

    private val events = mutableListOf<String>()

    private fun showHistory(recording: Boolean = true) {
        composeRule.setContent {
            HistoryContent(
                rows = rows,
                recording = recording,
                onRecordingChange = { events += "recording $it" },
                onOpen = { events += "open $it" },
                onDelete = { events += "delete $it" },
                onClear = { events += "clear $it" },
                onNavigateBack = { events += "back" },
                now = now,
                zone = utc,
            )
        }
    }

    @Test
    fun `pages are grouped under today and yesterday, and tapping one opens it`() {
        showHistory()

        composeRule.onNodeWithText("Today").assertExists()
        composeRule.onNodeWithText("Yesterday").assertExists()
        composeRule.onNodeWithText("Weather today").performClick()
        // A page without a title goes by its site; the test window is small, so scroll down to it.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("recipes.example"))
        composeRule.onNodeWithText("recipes.example").assertExists()

        assertEquals(listOf("open https://weather.example/"), events)
    }

    @Test
    fun `search keeps the pages whose title or address match`() {
        showHistory()

        composeRule.onNode(hasSetTextAction()).performTextInput("weather")
        composeRule.onNodeWithText("Weather today").assertExists()
        composeRule.onAllNodesWithText("Launch story").assertCountEquals(0)
        composeRule.onAllNodesWithText("Yesterday").assertCountEquals(0)

        composeRule.onNode(hasSetTextAction()).performTextInput("zzz")
        composeRule.onNodeWithText("Nothing in history matches “weatherzzz”").assertExists()
    }

    @Test
    fun `the cross deletes one page`() {
        showHistory()

        composeRule.onNodeWithContentDescription("Delete Launch story from history").performClick()

        assertEquals(listOf("delete https://news.example/launch"), events)
    }

    @Test
    fun `clear history asks how far back, and cancel clears nothing`() {
        showHistory()

        composeRule.onNodeWithContentDescription("More").performClick()
        composeRule.onNodeWithText("Clear history").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<String>(), events)

        composeRule.onNodeWithContentDescription("More").performClick()
        composeRule.onNodeWithText("Clear history").performClick()
        composeRule.onNodeWithText("Last day").performClick()
        composeRule.onNodeWithText("Clear").performClick()
        assertEquals(listOf("clear LAST_DAY"), events)
    }

    @Test
    fun `the switch turns saving history off`() {
        showHistory(recording = true)

        composeRule.onNodeWithText("Save history").performClick()

        assertEquals(listOf("recording false"), events)
    }

    @Test
    fun `with saving off the switch says so`() {
        showHistory(recording = false)

        composeRule.onNodeWithText("Off: pages you open are not kept").assertExists()
        composeRule.onNodeWithText("Save history").assertIsOff()
    }

    @Test
    fun `days further back are named by date, with the year when it is not this one`() {
        val days = historyByDay(
            rows + listOf(
                HistoryEntity("https://a.example/", "A", at("2026-10-01T08:00:00Z"), 1),
                HistoryEntity("https://b.example/", "B", at("2025-12-31T08:00:00Z"), 1),
            ),
            now,
            utc,
        )

        assertEquals(
            listOf("Today", "Yesterday", "Thursday, 1 October", "Wednesday, 31 December 2025"),
            days.map { it.first },
        )
        // Newest first within a day.
        assertEquals(listOf("Weather today", "Launch story"), days[0].second.map { it.title })
    }

    // ---- bookmarks ---- //

    private fun showBookmarks(bookmarks: List<BookmarkEntity>) {
        composeRule.setContent {
            BookmarksContent(
                rows = bookmarks,
                onOpen = { events += "open $it" },
                onRemove = { events += "remove $it" },
                onNavigateBack = { events += "back" },
            )
        }
    }

    @Test
    fun `with no bookmarks the screen says how to add one`() {
        showBookmarks(emptyList())

        composeRule.onNodeWithText("Tap the star beside a page's address to keep it here.").assertExists()
    }

    @Test
    fun `a bookmark opens when tapped and goes with its cross`() {
        showBookmarks(
            listOf(
                BookmarkEntity("https://news.example/", "Daily News", 2),
                BookmarkEntity("https://weather.example/", "Weather", 1),
            ),
        )

        composeRule.onNodeWithText("Daily News").performClick()
        composeRule.onNodeWithContentDescription("Remove Weather from bookmarks").performClick()

        assertEquals(listOf("open https://news.example/", "remove https://weather.example/"), events)
    }

    @Test
    fun `search keeps the bookmarks that match`() {
        showBookmarks(
            listOf(
                BookmarkEntity("https://news.example/", "Daily News", 2),
                BookmarkEntity("https://weather.example/", "Weather", 1),
            ),
        )

        composeRule.onNode(hasSetTextAction()).performTextInput("news")

        composeRule.onNodeWithText("Daily News").assertExists()
        composeRule.onAllNodesWithText("Weather").assertCountEquals(0)
    }
}
