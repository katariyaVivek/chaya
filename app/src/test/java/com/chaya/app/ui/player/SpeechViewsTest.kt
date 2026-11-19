package com.chaya.app.ui.player

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chaya.app.speech.Making
import com.chaya.app.speech.SpeechModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeechViewsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the model's size is said before it is fetched, and the one chosen is made with`() {
        val made = mutableListOf<SpeechModel>()
        composeRule.setContent {
            MakeSubtitlesCard(state = Making.Idle, fetched = { it == SpeechModel.TINY }, onMake = { made += it }, onStop = {})
        }
        composeRule.onNodeWithText("Make subtitles").assertExists()
        composeRule.onNodeWithText("More accurate · 148 MB").performClick()
        composeRule.onNodeWithText("Fetch 148 MB and make subtitles").performClick()
        assertEquals(listOf(SpeechModel.BASE), made)
    }

    @Test
    fun `while it listens it says how far, with Stop`() {
        var stopped = 0
        composeRule.setContent {
            MakeSubtitlesCard(state = Making.Listening(42), fetched = { true }, onMake = {}, onStop = { stopped++ })
        }
        composeRule.onNodeWithText("Listening · 42%").assertExists()
        composeRule.onNodeWithText("Stop").performClick()
        assertEquals(1, stopped)
    }
}
