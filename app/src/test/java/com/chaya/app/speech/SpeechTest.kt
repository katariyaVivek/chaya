package com.chaya.app.speech

import com.chaya.app.subtitles.SubtitleLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * `speech::whisper::parse` and `speech::lines`, ported with Windows' tests and its recorded whisper.cpp output (read
 * from `desktop/core/tests/fixtures`, one copy for both apps); the models fetched as pinned; 16 kHz sound; a job.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeechTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    private fun fixture(name: String) = File("../desktop/core/tests/fixtures/$name").readText()

    /** Words said one after another from [start], each over [each] ms, with [gap] between them. */
    private fun said(text: String, start: Long, each: Long, gap: Long): List<Word> {
        var at = start
        return text.split(' ').map { w -> Word(w, at, at + each, 0.9f).also { at += each + gap } }
    }

    private fun transcript(language: String, segments: List<List<Word>>) = Transcript(
        language,
        segments.map { words -> Segment(words.first().start, words.last().end, words.joinToString(" ") { it.text.trim() }, words) },
    )

    private fun texts(lines: List<SubtitleLine>) = lines.map { it.text }

    private fun gapAfter(text: String, word: String): Float {
        val line = said(text, 0, 300, 50)
        val at = line.indexOfFirst { it.text == word } + 1
        return SpeechLines.score(line, at, SpeechLines.Words.of("en"))
    }

    // ── whisper.cpp's JSON ──

    @Test
    fun `whisper cpp's JSON is read into segments and words`() {
        val transcript = Transcript.parse(fixture("whisper-jfk.json"))!!
        assertEquals("en", transcript.language)
        assertEquals(2, transcript.segments.size)
        val first = transcript.segments[0]
        assertEquals(0L to 7_600L, first.start to first.end)
        assertEquals("And so my fellow Americans,", first.text)
        assertEquals("pieces of a word put back together", listOf("And", "so", "my", "fellow", "Americans,"), first.words.map { it.text })
        assertEquals(3_200L to 4_400L, first.words[4].start to first.words[4].end)
        assertEquals("the least sure piece", 0.71f, first.words[4].p, 0.001f)
        assertEquals("ask", transcript.segments[1].words.first().text)
    }

    @Test
    fun `a word starts at its DTW time and ends at its punctuation or soon after`() {
        val words = Transcript.parse(fixture("whisper-columbia.json"))!!.segments.flatMap { it.words }
        fun find(text: String) = words.first { it.text == text }
        assertEquals(1_820L to 2_880L, find("Americans,").let { it.start to it.end })
        assertEquals(3_580L, find("this").start)
        val news = find("news")
        assertEquals(4_920L, news.start)
        assertTrue("$news", news.end in 5_101..5_999)
        words.zipWithNext().forEach { (a, b) -> assertTrue("$a $b", a.start <= a.end && a.end <= b.start) }
    }

    @Test
    fun `a recorded speech comes out in short lines that follow it`() {
        val transcript = Transcript.parse(fixture("whisper-columbia.json"))!!
        val lines = transcript.lines()
        assertEquals(39, transcript.segments.size)
        assertTrue("${lines.size} lines", lines.size >= 70)
        val said = transcript.segments.flatMap { it.text.split(' ').filter(String::isNotBlank) }
        val shown = lines.flatMap { it.text.split(' ').filter(String::isNotBlank) }
        assertEquals("every word, once, in order", said, shown)
        lines.zipWithNext().forEach { (a, b) -> assertTrue("$a $b", a.start < a.end && a.end <= b.start) }
        for (line in lines) {
            assertTrue("$line", !line.text.contains(' ') || line.text.codePointCount(0, line.text.length) <= 44)
            assertTrue("$line", line.end - line.start <= 5_500)
            for (word in listOf("the", "a", "of", "to", "our")) assertFalse("$line", line.text.endsWith(" $word"))
        }
        assertTrue("a pause after a comma", texts(lines).contains("My fellow Americans,"))
        assertTrue("not where whisper.cpp's segment ended", texts(lines).none { it.endsWith("overlook the") })
    }

    @Test
    fun `without DTW times lines are cut by whisper cpp's own`() {
        val parsed = Transcript.parse(fixture("whisper-jfk.json"))!!
        val transcript = parsed.copy(segments = parsed.segments + Segment(11_000, 12_000, "[BLANK_AUDIO]"))
        val lines = transcript.lines()
        assertEquals(
            listOf("And so my fellow Americans,", "ask not what your country can do for you,", "ask what you can do for your country."),
            texts(lines),
        )
        assertEquals(0L, lines[0].start)
        assertEquals(11_000L + 300, lines[2].end)
    }

    @Test
    fun `a line said over and over is kept once`() {
        val said = listOf(
            "We walked to the river.", "And then we sat down.", "And then we sat down", "and then we sat down.",
            "The water was cold.", "And then we sat down.",
        )
        val segments = said.mapIndexed { i, text -> """{"offsets": {"from": ${i * 2000}, "to": ${i * 2000 + 2000}}, "text": "$text", "tokens": []}""" }
        val json = """{"result": {"language": "en"}, "transcription": [${segments.joinToString(",")}]}"""
        assertEquals(
            "a repeat right after is left out; the same line later is kept",
            listOf("We walked to the river.", "And then we sat down.", "The water was cold.", "And then we sat down."),
            Transcript.parse(json)!!.segments.map { it.text },
        )
    }

    @Test
    fun `what JSON has no word for is still read`() {
        val json = "{\"result\": {\"language\": \"de\"}, \"transcription\": [{\"offsets\": {\"from\": 0, \"to\": 1000}," +
            "\"text\": \" Hallo\u0007\", \"tokens\": [{\"text\": \" Hallo\", \"offsets\": {\"from\": 0, \"to\": 900}," +
            "\"p\": -nan, \"t_dtw\": -1}]}]}"
        val transcript = Transcript.parse(json)!!
        assertEquals("de", transcript.language)
        assertEquals("Hallo", transcript.segments[0].words[0].text)
        assertEquals(0f, transcript.segments[0].words[0].p)
        assertNull(Transcript.parse("not json"))
        assertNull(Transcript.parse("{\"result\": {}}"))
    }

    // ── Lines ──

    @Test
    fun `a line that fits is left whole`() {
        assertEquals(
            listOf("Ask not what your country can do for you."),
            texts(transcript("en", listOf(said("Ask not what your country can do for you.", 0, 300, 50))).lines()),
        )
    }

    @Test
    fun `sentences end lines but Mr and initials do not`() {
        val t = transcript("en", listOf(said("It rained all day. Then Mr. Smith and John F. Kennedy came.", 0, 250, 20)))
        assertEquals(listOf("It rained all day.", "Then Mr. Smith and John F. Kennedy came."), texts(t.lines()))
    }

    @Test
    fun `a clear pause ends a line, and after of or the only a long one`() {
        val words = said("we waited for the bus", 0, 250, 20) + said("and nobody came at all", 3_000, 250, 20)
        assertEquals(listOf("we waited for the bus", "and nobody came at all"), texts(transcript("en", listOf(words)).lines()))
        val short = said("we are all thinking of", 0, 250, 20) + said("the families", 2_080, 400, 20)
        assertEquals(listOf("we are all thinking of the families"), texts(transcript("en", listOf(short)).lines()))
        val long = said("we are all thinking of", 0, 250, 20) + said("the families", 3_000, 400, 20)
        assertEquals(listOf("we are all thinking of", "the families"), texts(transcript("en", listOf(long)).lines()))
    }

    @Test
    fun `gaps score as Windows scores them`() {
        val shuttle = "Mission Control lost contact with our space shuttle Columbia."
        assertTrue(gapAfter(shuttle, "contact") > gapAfter(shuttle, "space"))
        assertTrue(gapAfter(shuttle, "contact") > gapAfter(shuttle, "lost"))
        val comma = "the people who came yesterday, saw everything we built for them"
        assertTrue(gapAfter(comma, "yesterday,") > gapAfter(comma, "came"))
        assertTrue(gapAfter(comma, "yesterday,") > gapAfter(comma, "saw"))
        val but = "she wanted to stay much longer but the train was leaving soon"
        assertTrue(gapAfter(but, "longer") > gapAfter(but, "much"))
        assertTrue(gapAfter(but, "longer") > gapAfter(but, "but"))
        val the = "we drove all the way down to the river every single summer"
        assertTrue(gapAfter(the, "the") < gapAfter(the, "all"))
        assertTrue(gapAfter(the, "to") < gapAfter(the, "down"))
        val house = "they all came to see the beautiful house we built last summer"
        assertTrue(gapAfter(house, "beautiful") < gapAfter(house, "house"))
        val three = "they built three houses near the lake"
        assertTrue(gapAfter(three, "three") < gapAfter(three, "houses"))
        val alpha = "alpha bravo charlie delta echo foxtrot golf hotel india juliet"
        assertTrue(gapAfter(alpha, "echo") > gapAfter(alpha, "charlie"))
        assertTrue(gapAfter(alpha, "charlie") > gapAfter(alpha, "alpha"))
        assertTrue(gapAfter(alpha, "india") < gapAfter(alpha, "golf"))
    }

    @Test
    fun `a longer pause scores higher`() {
        val en = SpeechLines.Words.of("en")
        fun gap(pause: Long) = SpeechLines.score(said("one two three", 0, 250, 20) + said("four five six", 790 + pause, 250, 20), 3, en)
        assertTrue(gap(300) > gap(20))
        assertTrue(gap(600) > gap(300))
    }

    @Test
    fun `a long sentence is cut until every line fits`() {
        val t = transcript(
            "en",
            listOf(
                said(
                    "and so my fellow Americans the time has come for all of us to ask what we can do for our " +
                        "country and what our country can do for the world we share with everyone else",
                    0, 280, 30,
                ),
            ),
        )
        val lines = t.lines()
        assertTrue("${texts(lines)}", lines.size >= 4)
        for (line in lines) {
            assertTrue(line.text, line.text.length <= SpeechLines.MOST_CHARS)
            assertTrue("$line", line.end - line.start <= SpeechLines.LONGEST + SpeechLines.EARLY + SpeechLines.LATE)
            for (word in listOf("the", "of", "to", "our")) assertFalse(line.text, line.text.endsWith(" $word"))
        }
        assertEquals(t.segments[0].text, texts(lines).joinToString(" "))
    }

    @Test
    fun `a tiny piece joins its closer neighbour`() {
        val first = said("Yes.", 0, 300, 0) + said("I think we should go there", 350, 250, 20)
        val t = transcript("en", listOf(first, said("Right now, before it rains.", 4_000, 250, 20)))
        assertEquals(listOf("Yes. I think we should go there", "Right now, before it rains."), texts(t.lines()))
    }

    @Test
    fun `lines show early and stay late without overlapping`() {
        val lines = transcript("en", listOf(said("The first line here.", 1_000, 250, 20), said("And the second one.", 2_200, 250, 20))).lines()
        assertEquals("0.2 s early", 800L, lines[0].start)
        assertEquals(2_060L, lines[0].end)
        assertEquals(2_060L, lines[1].start)
        assertEquals("0.3 s late", 2_200L + 4 * 270 - 20 + 300, lines[1].end)
        val lone = transcript("en", listOf(said("Hi there.", 5_000, 150, 0))).lines()
        assertEquals("at least a second where there is room", 4_800L to 5_800L, lone[0].start to lone[0].end)
    }

    @Test
    fun `Hindi does not start a line with a postposition`() {
        val words = SpeechLines.Words.of("hi")
        val line = said("हम सब ने मिलकर इस देश को आगे बढ़ाया है और हम आगे भी", 0, 300, 50)
        val beforeKo = line.indexOfFirst { it.text == "को" }
        assertTrue(SpeechLines.score(line, beforeKo, words) < SpeechLines.score(line, beforeKo + 1, words))
        val beforeAur = line.indexOfFirst { it.text == "और" }
        assertTrue(SpeechLines.score(line, beforeAur, words) > SpeechLines.score(line, beforeAur - 1, words))
        assertTrue(words.endsSentence("है।"))
        assertEquals("a word's marks are part of it", "है", SpeechLines.bare("है,"))
    }

    @Test
    fun `languages written without spaces keep whisper cpp's segments, and silences are left out`() {
        val ja = transcript("ja", listOf(listOf(Word("今日はいい天気ですね", 0, 2_000, 0.9f)))).lines()
        assertEquals(listOf("今日はいい天気ですね"), texts(ja))
        assertEquals(0L to 2_300L, ja[0].start to ja[0].end)
        val t = transcript("en", listOf(said("Hello there everyone.", 0, 300, 20)))
        val withSilence = t.copy(segments = t.segments + Segment(2_000, 9_000, "[BLANK_AUDIO]"))
        assertEquals(listOf("Hello there everyone."), texts(withSilence.lines()))
    }

    // ── Sound, models, a job ──

    @Test
    fun `sound becomes one channel at 16 kHz, in pieces`() {
        val pieces = mutableListOf<Pair<Int, Long>>()
        val sound = Sound16k(inRate = 48_000, channels = 2, chunkSamples = 16_000) { samples, at -> pieces += samples.size to at }
        // Two and a half seconds of a steady stereo sound at 48 kHz
        repeat(120_000) {
            sound.add(16_384)
            sound.add(0)
        }
        sound.finish()
        assertEquals(listOf(16_000 to 0L, 16_000 to 1_000L, 8_000 to 2_000L), pieces)
        var last = 0f
        Sound16k(32_000, 1, 10) { samples, _ -> last = samples.last() }.apply {
            repeat(20) { add(32_767) }
            finish()
        }
        assertEquals("channels averaged, scaled to -1..1", 1f, last, 0.001f)
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `a model is fetched once, carrying on from a part, and kept only when its sum is right`() = runTest {
        val model = ByteArray(10_000) { (it % 251).toByte() }
        val pin = SpeechModels.Pin("Test", "ggml-test.bin", model.size.toLong(), sha(model))
        val dir = folder.newFolder()
        File(dir, "ggml-test.bin.part").writeBytes(model.copyOf(4_000))
        server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(model.copyOfRange(4_000, model.size))))
        val models = SpeechModels(dir, source = server.url("/m").toString().trimEnd('/'))
        val file = models.fetchPinned(pin) { _, _ -> }
        assertTrue(file.readBytes().contentEquals(model))
        assertEquals("bytes=4000-", server.takeRequest().getHeader("Range"))
        // Here: not fetched again.
        models.fetchPinned(pin) { _, _ -> }
        assertEquals(1, server.requestCount)

        val wrong = SpeechModels.Pin("Wrong", "ggml-wrong.bin", model.size.toLong(), "0".repeat(64))
        server.enqueue(MockResponse().setBody(Buffer().write(model)))
        val refused = runCatching { models.fetchPinned(wrong) { _, _ -> } }.exceptionOrNull()
        assertTrue("$refused", refused is ModelException)
        assertFalse("never kept", File(dir, "ggml-wrong.bin").exists())
        assertFalse(File(dir, "ggml-wrong.bin.part").exists())
    }

    @Test
    fun `making subtitles listens to each piece, shifts its times, and keeps the lines in the language heard`() = runBlocking {
        val dir = folder.newFolder()
        // The model, as if fetched before: only its size is looked at.
        RandomAccessFile(File(dir, SpeechModel.TINY.file), "rw").use { it.setLength(SpeechModel.TINY.size) }
        val kept = mutableListOf<Triple<Long, String, List<SubtitleLine>>>()
        val languagesAsked = mutableListOf<String?>()
        val jfk = fixture("whisper-jfk.json")
        val jobs = SpeechJobs(
            scope = CoroutineScope(Dispatchers.IO),
            models = SpeechModels(dir),
            decode = { _, onPiece ->
                onPiece(FloatArray(10), 0)
                onPiece(FloatArray(10), 300_000)
                true
            },
            listen = { _, _, _, language, listener ->
                languagesAsked += language
                listener.heard(50)
                jfk
            },
            keep = { id, language, lines -> kept += Triple(id, language, lines); true },
        )
        jobs.start(12, SpeechModel.TINY, 600_000)
        withTimeout(10_000) { jobs.states.first { it[12] == Making.Idle && kept.isNotEmpty() } }
        val (id, language, lines) = kept.single()
        assertEquals(12L to "en", id to language)
        assertEquals(6, lines.size)
        assertEquals("the second piece's lines start 5 minutes in (0.2 s early)", 300_000L - SpeechLines.EARLY, lines[3].start)
        assertEquals("the language heard first is kept for the rest", listOf(null, "en"), languagesAsked)
    }
}
