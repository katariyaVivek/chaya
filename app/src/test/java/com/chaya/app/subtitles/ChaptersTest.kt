package com.chaya.app.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A video's chapters read, found by time and kept as text, as Chaya for Windows reads them (`chapters.rs`'s tests). */
class ChaptersTest {

    private fun times(chapters: List<Chapter>) = chapters.map { Triple(it.start, it.end, it.title) }

    @Test
    fun `chapters are read in milliseconds and in order`() {
        val chapters = Chapters.read(
            listOf(
                ListedChapter(42.0, 300.5, "The   walk"),
                ListedChapter(0.0, 42.0, "Intro"),
                ListedChapter(300.5, 420.0, ""),
            ),
            420.0,
        )
        assertEquals(
            listOf(Triple(0L, 42_000L, "Intro"), Triple(42_000L, 300_500L, "The walk"), Triple(300_500L, 420_000L, "Chapter 3")),
            times(chapters),
        )
    }

    @Test
    fun `a chapter ends where the next begins and none runs past the video`() {
        val chapters = Chapters.read(
            listOf(
                ListedChapter(0.0, 60.0, "One"),
                ListedChapter(50.0, 120.0, "Two"),
                ListedChapter(50.0, 70.0, "Two again"),
                ListedChapter(110.0, 200.0, "Three"),
                ListedChapter(130.0, 140.0, "After the end"),
            ),
            120.0,
        )
        assertEquals(
            listOf(Triple(0L, 50_000L, "One"), Triple(50_000L, 110_000L, "Two"), Triple(110_000L, 120_000L, "Three")),
            times(chapters),
        )
    }

    @Test
    fun `one chapter or nonsense is no chapters`() {
        assertTrue(Chapters.read(listOf(ListedChapter(0.0, 420.0, "The video")), 420.0).isEmpty())
        assertTrue(
            Chapters.read(
                listOf(ListedChapter(null, null, "a"), ListedChapter(null, 5.0, null), ListedChapter(-1.0, 3.0, "x")),
                null,
            ).isEmpty()
        )
        assertTrue(Chapters.read(emptyList(), null).isEmpty())
        val long = Chapters.read(listOf(ListedChapter(0.0, 1.0, "x".repeat(300)), ListedChapter(1.0, 2.0, null)), null)
        assertEquals(Chapters.LONGEST_TITLE, long[0].title.length)
    }

    @Test
    fun `the chapter playing is found by time, and none outside them`() {
        val chapters = listOf(Chapter(5_000, 10_000, "One"), Chapter(10_000, 20_000, "Two"))
        assertNull(Chapters.at(chapters, 4_999))
        assertEquals(0, Chapters.at(chapters, 5_000))
        assertEquals(0, Chapters.at(chapters, 9_999))
        assertEquals(1, Chapters.at(chapters, 10_000))
        assertNull(Chapters.at(chapters, 20_000))
        assertNull(Chapters.at(emptyList(), 0))
    }

    @Test
    fun `chapters kept as text come back as they were, and a damaged file gives none`() {
        val chapters = listOf(Chapter(0, 42_000, "Intro"), Chapter(42_000, 300_500, "The walk: part 2"))
        assertEquals(chapters, Chapters.fromText(Chapters.toText(chapters)))
        assertTrue(Chapters.fromText("").isEmpty())
        assertTrue(Chapters.fromText("0\t42000\tIntro\nnot a chapter\n").isEmpty())
        assertTrue(Chapters.fromText("0\t42000\tIntro\n50\t10\tEnds before it starts\n").isEmpty())
    }
}
