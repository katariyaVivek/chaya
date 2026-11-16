package com.chaya.app.ui.player

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chaya.app.subtitles.SubtitleLine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** The player's transcript: every line with its time, the one being said marked, and a line tapped plays from it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val lines = listOf(
        SubtitleLine(1_000, 2_500, "Hello there."),
        SubtitleLine(65_000, 67_000, "A minute in."),
    )

    @Test
    fun `every line shows with its time, the one being said marked`() {
        composeRule.setContent { Transcript(lines, language = "en", current = 1, onSeek = {}) }

        composeRule.onNodeWithText("TRANSCRIPT · ENGLISH").assertExists()
        composeRule.onNodeWithText("0:01").assertExists()
        composeRule.onNodeWithText("1:05").assertExists()
        composeRule.onNodeWithText("Hello there.").assertIsNotSelected()
        composeRule.onNodeWithText("A minute in.").assertIsSelected()
    }

    @Test
    fun `a line tapped plays from where it is said`() {
        val seeks = mutableListOf<Long>()
        composeRule.setContent { Transcript(lines, language = "en", current = null, onSeek = { seeks += it }) }

        composeRule.onNodeWithText("A minute in.").performClick()

        assertEquals(listOf(65_000L), seeks)
    }

    @Test
    fun `a language is named as the phone names it, or by its tag`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            assertEquals("English", languageName("en"))
            assertEquals("Portuguese (Brazil)", languageName("pt-BR"))
            assertEquals("qqq", languageName("qqq"))
        } finally {
            Locale.setDefault(before)
        }
    }
}
