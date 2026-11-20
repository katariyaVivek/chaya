package com.chaya.app.ui.player

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chaya.app.speech.CloudSpeech
import com.chaya.app.speech.Engine
import com.chaya.app.speech.Making
import com.chaya.app.speech.SpeechModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
        val made = mutableListOf<Engine>()
        composeRule.setContent {
            MakeSubtitlesCard(state = Making.Idle, fetched = { it == SpeechModel.TINY }, onMake = { made += it }, onStop = {})
        }
        composeRule.onNodeWithText("Make subtitles").assertExists()
        composeRule.onNodeWithText("More accurate · 148 MB").performClick()
        composeRule.onNodeWithText("Fetch 148 MB and make subtitles").performClick()
        assertEquals(listOf<Engine>(Engine.OnPhone(SpeechModel.BASE)), made)
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

    @Test
    fun `a service with a key saved is offered, saying the sound is sent and what it costs`() {
        val made = mutableListOf<Engine>()
        composeRule.setContent {
            MakeSubtitlesCard(state = Making.Idle, fetched = { false }, onMake = { made += it }, onStop = {}, clouds = listOf(CloudSpeech.GROQ))
        }
        composeRule.onNodeWithText("Groq").performClick()
        composeRule.onNodeWithText("The video's sound is sent to Groq with your key (whisper-large-v3, about 11¢ an hour of video).").assertExists()
        composeRule.onNodeWithText("Send to Groq and make subtitles").performClick()
        assertEquals(listOf<Engine>(Engine.Cloud(CloudSpeech.GROQ)), made)
    }

    @Test
    fun `without a key, the card says how to have a service listen, and while one does it says the sound is sent`() {
        var state by androidx.compose.runtime.mutableStateOf<Making>(Making.Idle)
        composeRule.setContent { MakeSubtitlesCard(state = state, fetched = { true }, onMake = {}, onStop = {}) }
        composeRule.onNodeWithText("Save a Groq or OpenAI key under Library › ⋮ › AI model to have them listen instead: far more accurate, in seconds, for a few cents.").assertExists()
        state = Making.Listening(40, service = "Groq")
        composeRule.onNodeWithText("Groq is listening · 40%").assertExists()
        composeRule.onNodeWithText("The sound is sent to Groq five minutes at a time. You can leave the player.").assertExists()
    }
}
