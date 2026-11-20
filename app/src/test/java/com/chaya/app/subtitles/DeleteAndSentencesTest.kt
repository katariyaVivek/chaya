package com.chaya.app.subtitles

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.chaya.app.speech.SpeechModel
import com.chaya.app.ui.player.SpeechModelsDialog
import com.chaya.app.ui.player.TranslateSheet
import com.chaya.app.ui.player.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Subtitles, translations and speech models deleted; a sentence over two lines translated as one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeleteAndSentencesTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `deleting subtitles takes what was made from them, and leaves chapters and clips`() {
        val dir = folder.newFolder()
        listOf("12.en.srt", "12.translations.json", "12.highlights.json", "12.chapters", "12.clips.json", "13.en.srt")
            .forEach { File(dir, it).writeText("x") }
        assertTrue(SubtitleStore(dir).forgetSubtitles(12))
        assertEquals(setOf("12.chapters", "12.clips.json", "13.en.srt"), dir.list()!!.toSet())
        assertFalse("nothing left to delete", SubtitleStore(dir).forgetSubtitles(12))
    }

    @Test
    fun `a translation version is deleted and the one shown stays shown`() {
        val store = TranslationStore(folder.newFolder())
        val hi = Translation("en", "hi", "Groq · llama", 1, listOf("नमस्ते"))
        val es = Translation("en", "es", "Groq · llama", 2, listOf("Hola"))
        store.keep(12, hi)
        store.keep(12, es) // shown: es, index 1
        val left = store.remove(12, 0)
        assertEquals(listOf("es"), left.versions.map { it.to })
        assertEquals("still shown, now first", 0, left.shown)
        assertEquals(VideoTranslations(), store.remove(12, 0))
        assertEquals("the file goes with the last version", VideoTranslations(), store.load(12))
    }

    @Test
    fun `lines that make one sentence are found, and the request says so`() {
        val lines = listOf("This is the most beautiful", "pair of hands I have ever seen.", "Thank you.", "And then", "we", "left!")
        assertEquals(listOf(1..2, 4..6), Translator.sentences(lines, lines.indices.toList()))
        val question = Translator.question(Translator.Job("Hands", lines, "Spanish", "m"), lines.indices.toList())
        assertTrue(question, "These lines are one sentence each, translated as a whole and shared out over the same lines: 1–2, 4–6." in question)
        assertEquals("a line on its own is no run", emptyList<IntRange>(), Translator.sentences(lines, listOf(2)))
        assertTrue(Translator.endsSentence("Really?\\u201d".replace("\\u201d", "”")))
        assertFalse(Translator.endsSentence("and so"))
    }

    @Test
    fun `a full batch ends where a sentence does, so a sentence is never split over two requests`() {
        // 39 one-line sentences, then a sentence over lines 40 to 42
        val lines = List(39) { "Line ${it + 1}." } + listOf("This sentence", "runs over", "three lines.") + List(5) { "More ${it + 1}." }
        val batches = Translator.batches(lines, List(lines.size) { null })
        assertEquals("the first batch stops before the sentence", 39, batches[0].size)
        assertEquals(listOf(39, 40, 41), batches[1].take(3))
        assertEquals((0 until lines.size).toList(), batches.flatten())
    }

    @Test
    fun `the transcript's bin asks to delete the subtitles`() {
        var deleted = 0
        composeRule.setContent {
            androidx.compose.foundation.layout.Column {
                Transcript(listOf(SubtitleLine(0, 1_000, "Hello.")), language = "en", current = null, onSeek = {}, onDelete = { deleted++ })
            }
        }
        composeRule.onNodeWithContentDescription("Delete these subtitles").performClick()
        assertEquals(1, deleted)
    }

    @Test
    fun `each translation version has its own delete`() {
        val removed = mutableListOf<Int>()
        composeRule.setContent {
            TranslateSheet(
                from = "en",
                translations = VideoTranslations(listOf(Translation("en", "hi", "Groq · llama", 1, listOf("x"))), shown = 0),
                state = Translating.Idle,
                onTranslate = {}, onStop = {}, onShow = {}, onSetUp = {}, onDismiss = {},
                onDelete = { removed += it },
            )
        }
        composeRule.onNodeWithContentDescription("Delete the Hindi translation").performScrollTo().performClick()
        assertEquals(listOf(0), removed)
    }

    @Test
    fun `the speech models dialog lists what is on the phone and deletes one`() {
        val models = mutableListOf<SpeechModel>()
        composeRule.setContent { SpeechModelsDialog(listOf(SpeechModel.PARAKEET), onDelete = { models += it }, onDismiss = {}) }
        composeRule.onNodeWithText("Best on the phone · European languages · 671 MB").assertExists()
        composeRule.onNodeWithText("Delete").performClick()
        assertEquals(listOf(SpeechModel.PARAKEET), models)
    }
}
