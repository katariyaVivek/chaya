package com.chaya.app.ui.player

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.chaya.app.subtitles.Translating
import com.chaya.app.subtitles.Translation
import com.chaya.app.subtitles.VideoTranslations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranslateViewsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the phone's language comes first, never the one the subtitles are in`() {
        val targets = translateTargets(Locale.forLanguageTag("hi-IN"), from = "en")
        assertEquals("hi", targets.first())
        assertFalse("en" in targets)
        assertEquals(targets.distinct(), targets)
    }

    @Test
    fun `Translate asks for the language chosen, and a version is chosen to show`() {
        val asked = mutableListOf<String>()
        val shown = mutableListOf<Int?>()
        val versions = VideoTranslations(
            listOf(Translation("en", "es", "Groq · llama", 1, listOf("Hola", null))),
            shown = null,
        )
        composeRule.setContent {
            TranslateSheet(
                from = "en", translations = versions, state = Translating.Idle,
                onTranslate = { asked += it }, onStop = {}, onShow = { shown += it }, onSetUp = {}, onDismiss = {},
            )
        }
        // The chip, before the version of the same language listed under it
        composeRule.onAllNodesWithText("Spanish").onFirst().performClick()
        composeRule.onNodeWithText("Translate").performClick()
        assertEquals(listOf("es"), asked)
        composeRule.onNodeWithText("Original only").performScrollTo().assertIsSelected()
        composeRule.onNodeWithText("Groq · llama · 1 of 2 lines").performScrollTo().performClick()
        assertEquals(listOf<Int?>(0), shown)
    }

    @Test
    fun `while it runs, it says how far it is and offers Stop`() {
        var stopped = 0
        composeRule.setContent {
            TranslateSheet(
                from = "en", translations = VideoTranslations(), state = Translating.Running("es", "Groq · llama", 40, 120),
                onTranslate = {}, onStop = { stopped++ }, onShow = {}, onSetUp = {}, onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Translating into Spanish · 40 of 120 lines").assertExists()
        composeRule.onNodeWithText("Stop").performClick()
        assertEquals(1, stopped)
    }
}
