package com.chaya.app.ui.player

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import com.chaya.app.subtitles.SubtitleLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Subtitles put right in the player: the line's sheet, the transcript's ways to it, and Undo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FixViewsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val line = SubtitleLine(12_000, 14_000, "Helo there.")
    private val events = mutableListOf<String>()

    private fun sheet(synced: Long? = null, translation: Pair<String, String>? = null) = composeRule.setContent {
        FixSheet(
            line = line,
            atMs = 15_000,
            synced = synced,
            translation = translation,
            onKeep = { text, translated -> events += if (translated == null) "keep $text" else "keep $text / $translated" },
            onSaidNow = { events += "said now" },
            onNudge = { events += "nudge $it" },
            onDismiss = { events += "dismiss" },
        )
    }

    @Test
    fun `a line's words are corrected, kept only once they change`() {
        sheet()

        composeRule.onNodeWithText("LINE AT 0:12").assertExists()
        composeRule.onNodeWithText("Keep").assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextClearance()
        composeRule.onNode(hasSetTextAction()).performTextInput("Hello there.")
        composeRule.onNodeWithText("Keep").performClick()

        assertEquals(listOf("keep Hello there."), events)
    }

    @Test
    fun `a line left empty is taken out`() {
        sheet()

        composeRule.onNode(hasSetTextAction()).performTextClearance()
        composeRule.onNodeWithText("Take out").performClick()

        assertEquals(listOf("keep "), events)
    }

    @Test
    fun `said now moves every line, or stretches since the line put right before`() {
        sheet()
        composeRule.onNodeWithText("This line is said at 0:15").assertExists()
        composeRule.onNodeWithText("Moves every line so this one starts where the video is").performClick()
        composeRule.onNodeWithContentDescription("Every line 0.5 seconds earlier").performClick()
        composeRule.onNodeWithText("+0.1 s").performClick()

        assertEquals(listOf("said now", "nudge -500", "nudge 100"), events)
    }

    @Test
    fun `with a line put right far enough before, said now stretches between the two`() {
        sheet(synced = 2_000)

        composeRule.onNodeWithText("Stretches the lines between 0:02 and here, for subtitles that drift").assertExists()
    }

    @Test
    fun `said now stretches only from a line far enough away`() {
        assertFalse(saidNowStretches(null, 60_000))
        assertFalse(saidNowStretches(58_000, 60_000))
        assertTrue(saidNowStretches(20_000, 60_000))
        assertEquals(listOf("−0.5 s", "−0.1 s", "+0.1 s", "+0.5 s"), NUDGES.map(::nudgeLabel))
    }

    @Test
    fun `a long press on a line, or the pencil beside it, puts it right`() {
        val lines = listOf(SubtitleLine(1_000, 2_000, "One."), SubtitleLine(5_000, 6_000, "Two."))
        composeRule.setContent {
            Transcript(lines, language = "en", current = 1, onSeek = { events += "seek $it" }, onFix = { events += "fix $it" })
        }

        composeRule.onNodeWithText("One.").performTouchInput { longClick() }
        composeRule.onNodeWithText("One.").performClick()
        composeRule.onNodeWithContentDescription("Put the line at 0:05 right").performClick()

        assertEquals(listOf("fix 0", "seek 1000", "fix 1"), events)
    }

    @Test
    fun `the translation's line under it is put right in the same sheet`() {
        sheet(translation = "Hola." to "Spanish")

        composeRule.onNodeWithText("SPANISH").assertExists()
        composeRule.onNodeWithText("Keep").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("The translation's words").performTextReplacement("Hola, buenas.")
        composeRule.onNodeWithText("Keep").performClick()

        assertEquals(listOf("keep Helo there. / Hola, buenas."), events)
    }

    @Test
    fun `what a change did is said, with undo while there is something to undo`() {
        composeRule.setContent { FixedBar(said = "Lines moved 0.5 s later.", canUndo = true, onUndo = { events += "undo" }) }

        composeRule.onNodeWithText("Lines moved 0.5 s later.").assertExists()
        composeRule.onNodeWithText("Undo").performClick()

        assertEquals(listOf("undo"), events)
    }
}
