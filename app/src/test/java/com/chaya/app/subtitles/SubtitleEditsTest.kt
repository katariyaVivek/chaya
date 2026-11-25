package com.chaya.app.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Subtitles put right are written to the file the player reads, and each change can be undone exactly. */
class SubtitleEditsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val original = "1\r\n00:00:01,000 --> 00:00:02,000\r\nHelo there.\r\n\r\n2\r\n00:00:05,000 --> 00:00:06,000\r\nGoodbye.\r\n"

    private fun setUp(): Triple<File, SubtitleStore, SubtitleEdits> {
        val dir = folder.newFolder()
        File(dir, "12.en.srt").writeText(original)
        val store = SubtitleStore(dir)
        return Triple(dir, store, SubtitleEdits(store))
    }

    @Test
    fun `a change is written to the file the player reads, and undone back to the very bytes`() {
        val (dir, store, edits) = setUp()

        val corrected = edits.change(12) { SubtitleFixes.withText(it, 0, "Hello there.") }
        assertEquals(listOf("Hello there.", "Goodbye."), corrected?.map { it.text })
        assertEquals(listOf("Hello there.", "Goodbye."), store.load(12)?.lines?.map { it.text })
        assertEquals("en", store.load(12)?.language)

        val moved = edits.change(12) { Retime.shift(500).apply(it) }
        assertEquals(listOf(1_500L, 5_500L), moved?.map { it.start })
        assertEquals(2, edits.undoable(12))

        assertEquals(listOf(1_000L, 5_000L), edits.undo(12)?.map { it.start })
        assertEquals(listOf("Helo there.", "Goodbye."), edits.undo(12)?.map { it.text })
        assertEquals("the file as it was, exactly", original, File(dir, "12.en.srt").readText())
        assertNull("nothing more to undo", edits.undo(12))
        assertFalse(File(dir, "12.en.srt.part").exists())
    }

    @Test
    fun `a change that changes nothing, or cannot be made, keeps nothing to undo`() {
        val (_, _, edits) = setUp()

        edits.change(12) { it }
        assertNull(edits.change(12) { SubtitleFixes.withText(it, 9, "No such line") })
        assertNull("a video without subtitles", edits.change(13) { it })

        assertEquals(0, edits.undoable(12))
    }

    @Test
    fun `only the last changes are kept for undo`() {
        val (_, _, edits) = setUp()

        repeat(SubtitleEdits.UNDOS + 5) { edits.change(12) { lines -> Retime.shift(100).apply(lines) } }

        assertEquals(SubtitleEdits.UNDOS, edits.undoable(12))
        repeat(SubtitleEdits.UNDOS) { assertTrue(edits.undo(12) != null) }
        // Back as far as kept: the first five shifts stay
        assertNull(edits.undo(12))
    }

    @Test
    fun `the store writes only its own subtitle files`() {
        val (dir, store, _) = setUp()
        assertFalse(store.write(12, "13.en.srt", "x"))
        assertFalse(store.write(12, "12.chapters", "x"))
        assertFalse(store.write(12, "12.en/../../x.srt", "x"))
        assertEquals(listOf("12.en.srt"), dir.list()!!.toList())
    }
}
