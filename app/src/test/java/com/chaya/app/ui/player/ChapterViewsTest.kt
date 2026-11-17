package com.chaya.app.ui.player

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chaya.app.subtitles.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The player's chapters: where the seek bar parts, the chapter playing, and the list that plays from one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChapterViewsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val chapters = listOf(
        Chapter(0, 30_000, "Intro"),
        Chapter(30_000, 90_000, "The walk"),
        Chapter(90_000, 120_000, "Home again"),
    )

    @Test
    fun `the seek bar parts where each chapter after the first begins, once the length is known`() {
        assertEquals(listOf(0.25f, 0.75f), chapterMarks(chapters, 120_000))
        assertTrue(chapterMarks(chapters, 0).isEmpty())
        // A chapter listed past what plays marks nothing
        assertEquals(listOf(0.5f), chapterMarks(chapters, 60_000))
    }

    @Test
    fun `the chapter playing is named with where it falls, and tapping it asks for the list`() {
        var asked = 0
        composeRule.setContent { ChapterButton(chapters, current = 1, onClick = { asked++ }) }

        composeRule.onNodeWithText("2 of 3").assertExists()
        composeRule.onNodeWithText("The walk").performClick()

        assertEquals(1, asked)
    }

    @Test
    fun `before the first chapter the button says how many there are`() {
        composeRule.setContent { ChapterButton(chapters, current = null, onClick = {}) }

        composeRule.onNodeWithText("3 chapters").assertExists()
    }

    @Test
    fun `the list marks the chapter playing, and a tap plays from another`() {
        val seeks = mutableListOf<Long>()
        composeRule.setContent { ChapterSheet(chapters, current = 0, onSeek = { seeks += it }, onDismiss = {}) }

        composeRule.onNodeWithText("Intro").assertIsSelected()
        composeRule.onNodeWithText("Home again").performClick()

        assertEquals(listOf(90_000L), seeks)
    }
}
