package com.chaya.app.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.chaya.app.subtitles.Clip
import com.chaya.app.subtitles.SubtitleLine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipViewsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a long press starts choosing lines, and a tap then chooses up to that line instead of playing`() {
        val events = mutableListOf<String>()
        val lines = listOf(SubtitleLine(1_000, 2_000, "One."), SubtitleLine(5_000, 6_000, "Two."), SubtitleLine(8_000, 9_000, "Three."))
        var chosen by mutableStateOf<IntRange?>(null)
        composeRule.setContent {
            Transcript(
                lines, language = "en", current = null,
                onSeek = { events += "seek $it" },
                onFix = { events += "fix $it" },
                chosen = chosen,
                onChoose = { events += "choose $it"; chosen = (chosen?.first ?: it)..it },
            )
        }
        composeRule.onNodeWithText("Two.").performClick()
        composeRule.onNodeWithText("One.").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Three.").performClick()
        assertEquals(listOf("seek 5000", "choose 0", "choose 2"), events)
    }

    @Test
    fun `the chosen lines' bar says how many and their span, and makes a clip`() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            ChosenBar(count = 3, start = 12_000, end = 31_000, onClip = { events += "clip" }, onFix = null, onCancel = { events += "cancel" })
        }
        composeRule.onNodeWithText("3 lines").assertExists()
        composeRule.onNodeWithText("0:12–0:31 · 19 s").assertExists()
        composeRule.onNodeWithText("Clip").performClick()
        assertEquals(listOf("clip"), events)
    }

    @Test
    fun `the clips are listed with their spans, and one opens`() {
        val opened = mutableListOf<Clip>()
        val clip = Clip(1, "The turn", 60_000, 64_500)
        composeRule.setContent { ClipsSheet(listOf(clip), onOpen = { opened += it }, onDismiss = {}) }
        composeRule.onNodeWithText("1:00–1:04 · 4.5 s").assertExists()
        composeRule.onNodeWithText("The turn").performClick()
        assertEquals(listOf(clip), opened)
    }
}
