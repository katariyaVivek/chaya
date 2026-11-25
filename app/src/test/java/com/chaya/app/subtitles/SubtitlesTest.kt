package com.chaya.app.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Subtitles read, timed, picked and written as Chaya for Windows does (`desktop/core/src/subtitles.rs`'s tests). */
class SubtitlesTest {

    private fun texts(lines: List<SubtitleLine>) = lines.map { it.text }

    @Test
    fun `an SRT is read with its times and lines`() {
        val srt = "\uFEFF1\r\n00:00:01,000 --> 00:00:02,500\r\nHello there.\r\n\r\n2\r\n00:01:02,050 --> 01:00:04,000\r\n" +
            "Two lines,\r\nof one subtitle.\r\n\r\n"
        assertEquals(
            listOf(
                SubtitleLine(1000, 2500, "Hello there."),
                SubtitleLine(62_050, 3_604_000, "Two lines,\nof one subtitle."),
            ),
            Subtitles.parse(srt),
        )
    }

    @Test
    fun `a VTT is read without its header, notes, styles or cue settings`() {
        val vtt = "WEBVTT - made by hand\nKind: captions\nLanguage: en\n\nSTYLE\n::cue { color: yellow }\n\n" +
            "NOTE a note,\nover two lines\n\nintro\n00:01.000 --> 00:04.000 align:start position:10%\n" +
            "<v Roger>Hi, <i>Bob</i>.</v>\n\n00:00:04.500 --> 00:00:06.000\n<c.yellow>Hello</c> &amp; goodbye\n"
        val lines = Subtitles.parse(vtt)
        assertEquals(listOf("Hi, Bob.", "Hello & goodbye"), texts(lines))
        assertEquals(1000L to 4000L, lines[0].start to lines[0].end)
        assertEquals(4500L to 6000L, lines[1].start to lines[1].end)
    }

    @Test
    fun `styling tags and overrides come off and entities are read`() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n{\\an8}<font color=\"#ffff00\">Up <b>here</b></font>&nbsp;&lt;3\n\n" +
            "2\n00:00:03,000 --> 00:00:04,000\n<i></i>\n\n"
        // A line with nothing left is left out
        assertEquals(listOf("Up here <3"), texts(Subtitles.parse(srt)))
    }

    @Test
    fun `words part at Unicode's white space, a no-break space included, and nowhere else`() {
        assertEquals(listOf("a", "b", "c", "d", "e"), Subtitles.words(" a\u00A0b\u3000c\u2009\u0085d\t\r\ne "))
        // A zero-width space and a joiner are not white space: they stay inside the word
        assertEquals(listOf("a\u200Bb", "c\u2060d"), Subtitles.words("a\u200Bb c\u2060d"))
        assertEquals(emptyList<String>(), Subtitles.words(" \u00A0 "))
        assertEquals("Up here", Subtitles.clean("Up\u00A0\u00A0here"))
    }

    @Test
    fun `times are read in every spelling the files use`() {
        assertEquals(3_723_456L, Subtitles.time("01:02:03,456"))
        assertEquals(3_723_456L, Subtitles.time("01:02:03.456"))
        assertEquals(123_400L, Subtitles.time("02:03.4"))
        assertEquals(5000L, Subtitles.time("00:00:05"))
        assertEquals(62_000L, Subtitles.time("1:2"))
        assertNull(Subtitles.time("00:61.000"))
        assertNull(Subtitles.time("aa:00.000"))
        assertNull(Subtitles.time("00:00:01.0000"))
        assertNull(Subtitles.time("+1:00.000"))
        assertEquals(1000L to 2000L, Subtitles.timing("00:01.000 --> 00:02.000 line:0"))
        assertNull(Subtitles.timing("not --> a time"))
    }

    @Test
    fun `broken blocks are skipped and the rest kept`() {
        val srt = "1\n00:00:01,000 -> 00:00:02,000\nNo arrow.\n\n2\n00:00:05,000 --> 00:00:03,000\nBackwards.\n\n" +
            "3\n00:00:06,000 --> 00:00:07,000\nKept.\n"
        assertEquals(listOf("Kept."), texts(Subtitles.parse(srt)))
    }

    @Test
    fun `blocks parted by spaces rather than empty lines still part`() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nFirst.\n \n2\n00:00:03,000 --> 00:00:04,000\nSecond.\n"
        assertEquals(listOf("First.", "Second."), texts(Subtitles.parse(srt)))
    }

    @Test
    fun `lines come in the order they start`() {
        val srt = "2\n00:00:05,000 --> 00:00:06,000\nLater.\n\n1\n00:00:01,000 --> 00:00:02,000\nSooner.\n"
        assertEquals(listOf("Sooner.", "Later."), texts(Subtitles.parse(srt)))
    }

    /** YouTube's machine captions, as yt-dlp's VTT has them: each cue repeats the line before, with word times. */
    private val rolling = "WEBVTT\nKind: captions\nLanguage: en\n\n" +
        "00:00:00.000 --> 00:00:02.490 align:start position:0%\n \nall<00:00:00.400><c> right</c><00:00:00.800><c> so</c>\n\n" +
        "00:00:02.490 --> 00:00:02.500 align:start position:0%\nall right so\n \n\n" +
        "00:00:02.500 --> 00:00:05.000 align:start position:0%\nall right so\nhere<00:00:03.000><c> we</c><00:00:03.500><c> are</c>\n\n" +
        "00:00:05.000 --> 00:00:05.010 align:start position:0%\nhere we are\n \n\n" +
        "00:00:05.010 --> 00:00:07.000 align:start position:0%\nhere we are\nin<00:00:05.500><c> front</c>\n"

    @Test
    fun `YouTube's rolling captions become one line after another`() {
        val lines = Subtitles.parse(rolling)
        assertEquals(listOf("all right so", "here we are", "in front"), texts(lines))
        assertEquals(2500L to 5000L, lines[1].start to lines[1].end)
    }

    @Test
    fun `a line said twice in a film stays twice`() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nNo!\n\n2\n00:00:02,000 --> 00:00:03,000\nNo!\n"
        assertEquals(listOf("No!", "No!"), texts(Subtitles.parse(srt)))
    }

    @Test
    fun `written as SRT and read again, the lines are the same`() {
        val lines = Subtitles.parse(rolling)
        val srt = Subtitles.toSrt(lines)
        assertTrue(srt, srt.startsWith("1\n00:00:00,000 --> 00:00:02,490\nall right so\n\n2\n"))
        assertEquals(lines, Subtitles.parse(srt))
        assertEquals(
            "01:02:03,456 --> 01:02:04,000",
            Subtitles.toSrt(listOf(SubtitleLine(3_723_456, 3_724_000, "a"))).split('\n')[1],
        )
    }

    private fun line(start: Long, end: Long) = SubtitleLine(start, end, "$start")

    @Test
    fun `the line at a time is the one running then, and none between lines`() {
        val spans = Subtitles.timeline(listOf(line(1000, 2000), line(3000, 4000)))
        assertNull(Subtitles.lineAt(spans, 0))
        assertEquals(0, Subtitles.lineAt(spans, 1000))
        assertEquals(0, Subtitles.lineAt(spans, 1999))
        // A line ends at its end
        assertNull(Subtitles.lineAt(spans, 2000))
        assertEquals(1, Subtitles.lineAt(spans, 3500))
        assertNull(Subtitles.lineAt(spans, 9000))
        assertNull(Subtitles.lineAt(emptyList(), 1000))
    }

    @Test
    fun `where lines overlap the later shows, and the earlier comes back after it`() {
        // A long line, a short one said over it, then one that starts as the long one ends.
        val spans = Subtitles.timeline(listOf(line(0, 5000), line(1000, 2000), line(5000, 6000)))
        assertEquals(
            listOf(
                SubtitleSpan(0, 1000, 0),
                SubtitleSpan(1000, 2000, 1),
                SubtitleSpan(2000, 5000, 0),
                SubtitleSpan(5000, 6000, 2),
            ),
            spans,
        )
        assertEquals(1, Subtitles.lineAt(spans, 1500))
        assertEquals(0, Subtitles.lineAt(spans, 2500))
        // Two lines starting together: the one listed later, as the file has them.
        assertEquals(1, Subtitles.lineAt(Subtitles.timeline(listOf(line(0, 3000), line(0, 1000))), 500))
    }

    private fun track(language: String, automatic: Boolean) =
        SubtitleTrack(language, "https://subs.example/$language", automatic)

    @Test
    fun `the video's own language is saved, made subtitles before machine captions`() {
        val tracks = listOf(track("es", false), track("en-US", false), track("en-orig", true), track("en", true))
        assertEquals("en-US", Subtitles.pick(tracks, "en")?.language)
        val onlyMachine = listOf(track("de", true), track("en", true), track("en-orig", true))
        assertEquals("en-orig", Subtitles.pick(onlyMachine, "en")?.language)
        assertEquals("en", Subtitles.pick(listOf(track("en", true)), "en-GB")?.language)
        // A Spanish video with only English subtitles and Spanish captions: the captions, in the words spoken.
        val spanish = listOf(track("en", false), track("es-orig", true), track("en", true))
        assertEquals("es-orig", Subtitles.pick(spanish, "es")?.language)
    }

    @Test
    fun `without the video's language, the first subtitles or the original captions are saved`() {
        assertEquals("fr", Subtitles.pick(listOf(track("fr", false), track("en", false)), null)?.language)
        assertEquals("ja-orig", Subtitles.pick(listOf(track("fr", true), track("ja-orig", true)), null)?.language)
        // A machine translation never
        assertNull(Subtitles.pick(listOf(track("fr", true)), null))
        // Nothing in the video's language
        assertNull(Subtitles.pick(listOf(track("fr", false)), "de"))
        assertNull(Subtitles.pick(emptyList(), "en"))
    }

    @Test
    fun `languages are written into names as players read them`() {
        assertEquals("en", Subtitles.fileLanguage("en"))
        assertEquals("en", Subtitles.fileLanguage("en-orig"))
        assertEquals("pt-BR", Subtitles.fileLanguage("pt-BR"))
        assertEquals("zh-Hans", Subtitles.fileLanguage("zh-Hans"))
        assertEquals("es-419", Subtitles.fileLanguage("es-419"))
        assertEquals("en", Subtitles.fileLanguage("en-nP7-2PuUl7o"))
        assertEquals("en", Subtitles.fileLanguage("EN"))
        assertNull(Subtitles.fileLanguage("live_chat"))
        assertNull(Subtitles.fileLanguage("../x"))
        assertNull(Subtitles.fileLanguage("é1"))
        assertNull(Subtitles.fileLanguage(""))
    }

    @Test
    fun `what is saved with a video names its file's language and carries the video's headers`() {
        val headers = mapOf("Referer" to "https://www.youtube.com/")
        val saved = Subtitles.toSave(listOf(track("en-orig", true)), "en", headers)
        assertEquals(SubtitleSource("en", "https://subs.example/en-orig", headers), saved)
        assertNull(Subtitles.toSave(listOf(track("fr", true)), "en", headers))
    }
}
