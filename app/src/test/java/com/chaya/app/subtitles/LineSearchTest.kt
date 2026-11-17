package com.chaya.app.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Every line said, searched as Chaya for Windows searches it (`desktop/core/src/search.rs`'s tests). */
class LineSearchTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun srt(vararg lines: Pair<Long, String>): String {
        fun time(ms: Long) = "00:%02d:%02d,%03d".format(ms / 60_000, ms / 1000 % 60, ms % 1000)
        return lines.withIndex().joinToString("") { (i, line) ->
            "${i + 1}\n${time(line.first)} --> ${time(line.first + 2000)}\n${line.second}\n\n"
        }
    }

    private fun video(id: Long) = LineSearch.Video(id, "Video $id")

    private fun texts(results: LineSearch.Results) = results.hits.map { Triple(it.id, it.start, it.text) }

    /** The text the marks cover, as the screen marks it. */
    private fun marked(hit: LineSearch.Hit) = hit.marks.map { hit.text.substring(it) }

    private fun oneVideo(vararg lines: Pair<Long, String>): LineSearch {
        val dir = folder.newFolder()
        File(dir, "1.en.srt").writeText(srt(*lines))
        return LineSearch(dir).apply { refresh(listOf(video(1))) }
    }

    @Test
    fun `case and accents are folded away`() {
        assertEquals("ecole maison", LineSearch.fold("École MAISON"))
        assertEquals("strasse, aesir, oeuvre, ore", LineSearch.fold("Straße, Æsir, Œuvre, Øre"))
        assertEquals("nino cafe  creme", LineSearch.fold("niño\ncafé  crème"))
        assertEquals("tieng viet", LineSearch.fold("Tiếng Việt"))
        assertEquals("don't", LineSearch.fold("don’t"))
        // Already taken apart: the accent is a character of its own, and goes
        assertEquals("ete", LineSearch.fold("été"))
        // Scripts without case or accents are left as they are
        assertEquals("नमस्ते 你好", LineSearch.fold("नमस्ते 你好"))
    }

    @Test
    fun `a query is its words each once longest first`() {
        assertEquals(listOf("walk", "park", "the"), LineSearch.wordsOf("  the Walk, the PARK!  "))
        assertEquals(listOf("que"), LineSearch.wordsOf("¿Qué?"))
        assertEquals(listOf("नमस्ते"), LineSearch.wordsOf("नमस्ते!"))
        assertTrue(LineSearch.wordsOf(" ... ").isEmpty())
    }

    @Test
    fun `a line is found when it holds every word in any order`() {
        val index = oneVideo(
            1000L to "This is a walk in the park.",
            5000L to "The park is quiet.",
            9000L to "We walk home,\nthrough the PARK.",
            13000L to "Nothing here.",
        )

        assertEquals(
            listOf(Triple(1L, 1000L, "This is a walk in the park."), Triple(1L, 9000L, "We walk home,\nthrough the PARK.")),
            texts(index.search("park walk")),
        )
        assertEquals(3, index.search("PARK").total)
        assertEquals(0, index.search("walking").total)
        assertEquals(LineSearch.Results(), index.search(""))
        // Words are not found across two lines
        assertEquals(0, index.search("quiet we").total)
    }

    @Test
    fun `what matched is marked in the line's own text`() {
        val index = oneVideo(1000L to "Le café de l'École, CAFÉ crème", 3000L to "Straße und Strasse", 5000L to "😀 café")

        val found = index.search("cafe ecole")
        assertEquals(listOf(3 until 7, 13 until 18, 20 until 24), found.hits[0].marks)
        assertEquals(listOf("café", "École", "CAFÉ"), marked(found.hits[0]))

        assertEquals(listOf("Straße", "Strasse"), marked(index.search("strasse").hits[0]))

        // The face before it is two of a string's characters
        val cafes = index.search("café")
        assertEquals(listOf(listOf(3 until 7, 20 until 24), listOf(3 until 7)), cafes.hits.map { it.marks })
        assertEquals(listOf("café"), marked(cafes.hits[1]))

        // Words that overlap are one mark
        assertEquals(listOf(2 until 10), oneVideo(1000L to "a parkland").search("park land").hits[0].marks)
    }

    @Test
    fun `lines come by video in the order given, then by time across languages`() {
        val dir = folder.newFolder()
        File(dir, "1.en.srt").writeText(srt(1000L to "one walk", 9000L to "two walk"))
        File(dir, "1.fr.srt").writeText(srt(5000L to "une walk"))
        File(dir, "2.en.srt").writeText(srt(2000L to "walk on"))
        // Not subtitles: a video's chapters, and a file being written
        File(dir, "2.chapters").writeText("0\t1000\twalk\n1000\t2000\twalk\n")
        File(dir, "2.de.srt.part").writeText(srt(3000L to "walk"))
        val index = LineSearch(dir).apply { refresh(listOf(video(2), video(1))) }

        val found = index.search("walk")
        assertEquals(
            listOf(Triple(2L, 2000L, "walk on"), Triple(1L, 1000L, "one walk"), Triple(1L, 5000L, "une walk"), Triple(1L, 9000L, "two walk")),
            texts(found),
        )
        assertEquals(listOf("en", "en", "fr", "en"), found.hits.map { it.language })
        assertEquals("Video 1", found.hits[1].title)
        assertEquals(3000L, found.hits[1].end)
        assertEquals(2, found.videos)

        // Only the videos asked about
        assertEquals(listOf(Triple(2L, 2000L, "walk on")), texts(index.search("walk", among = setOf(2L))))
    }

    @Test
    fun `at most so many lines come back and the rest are counted`() {
        val index = oneVideo(*(0 until 300).map { it * 3000L to "Line $it of the walk" }.toTypedArray())

        val found = index.search("walk")
        assertEquals(LineSearch.MOST to 300, found.hits.size to found.total)
        assertEquals("Line 0 of the walk", found.hits[0].text)
        assertEquals(5, index.search("walk", most = 5).hits.size)
    }

    @Test
    fun `only subtitles that changed are read again`() {
        val dir = folder.newFolder()
        File(dir, "1.en.srt").writeText(srt(1000L to "an old line"))
        File(dir, "2.en.srt").writeText(srt(1000L to "another line"))
        // Another download's subtitles, which are not 1's
        File(dir, "11.en.srt").writeText(srt(1000L to "not this line"))
        val index = LineSearch(dir)
        val videos = listOf(video(1), video(2))

        assertEquals(2, index.refresh(videos))
        assertEquals("nothing changed", 0, index.refresh(videos))
        assertEquals(2, index.search("line").total)

        // 1's subtitles made again, and a translation beside them
        File(dir, "1.en.srt").writeText(srt(1000L to "a new line, longer"))
        File(dir, "1.fr.srt").writeText(srt(1000L to "une ligne"))
        assertEquals("1's two files, not 2's", 2, index.refresh(videos))
        assertEquals(listOf(Triple(1L, 1000L, "a new line, longer")), texts(index.search("new")))
        assertEquals(0, index.search("old").total)
        assertEquals(1, index.search("ligne").total)

        // 1 taken off the list: its lines go. A video added: its are read
        File(dir, "3.en.srt").writeText("WEBVTT\n\n00:01.000 --> 00:02.000\nthe third line\n")
        assertEquals(1, index.refresh(listOf(video(2), video(3))))
        assertEquals(
            listOf(Triple(2L, 1000L, "another line"), Triple(3L, 1000L, "the third line")),
            texts(index.search("line")),
        )

        // Subtitles deleted: the video stays, with nothing to find
        File(dir, "3.en.srt").delete()
        index.refresh(listOf(video(2), video(3)))
        assertEquals(0, index.search("third").total)
    }

    @Test
    fun `the files are looked at again only after a while, or when the videos change`() {
        val dir = folder.newFolder()
        File(dir, "1.en.srt").writeText(srt(1000L to "hello"))
        val index = LineSearch(dir)
        val videos = listOf(video(1))

        assertTrue(index.keepUp(videos, 10_000))
        assertFalse(index.keepUp(videos, 10_300))
        assertTrue(index.keepUp(videos, 10_000 + LineSearch.RECHECK_MS + 1))
        assertTrue(index.keepUp(listOf(LineSearch.Video(1, "Renamed")), 10_000 + LineSearch.RECHECK_MS + 2))
        assertEquals("Renamed", index.search("hello").hits[0].title)
    }

    @Test
    fun `a large index is searched quickly`() {
        // 100 videos of 1,000 lines each
        val dir = folder.newFolder()
        val videos = (0 until 100L).map { id ->
            File(dir, "$id.en.srt").writeText(
                srt(*(0 until 1000).map { it * 2000L to "Line $it of video $id, where we talk about things" }.toTypedArray())
            )
            video(id)
        }
        val index = LineSearch(dir).apply { refresh(videos) }

        val began = System.nanoTime()
        val found = index.search("talk 999")
        assertEquals(0, index.refresh(videos))
        val tookMs = (System.nanoTime() - began) / 1_000_000
        assertEquals(100, found.total)
        // Generous, for a busy machine
        assertTrue("took $tookMs ms", tookMs < 2_000)
    }
}
