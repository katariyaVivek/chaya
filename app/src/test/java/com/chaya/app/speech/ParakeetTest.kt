package com.chaya.app.speech

import com.chaya.app.subtitles.SubtitleLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Parakeet through sherpa-onnx: windows cut at quiet moments, tokens made words, the language guessed, its files. */
class ParakeetTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `long sound is cut at its quietest moment between 20 and 30 seconds, every sample in one window`() {
        val rate = Sound16k.RATE
        // 70 s of speech-loud sound, quiet from 23.0 to 23.4 s and from 47.0 to 47.4 s
        val samples = FloatArray(70 * rate) { i ->
            val s = i.toDouble() / rate
            if ((s in 23.0..23.4) || (s in 47.0..47.4)) 0f else 0.3f * kotlin.math.sin(i * 0.05f)
        }
        val cuts = Parakeet.windows(samples)
        assertEquals(0, cuts.first().first)
        assertEquals(samples.size - 1, cuts.last().last)
        assertTrue("every sample in one window", cuts.zipWithNext().all { (a, b) -> a.last + 1 == b.first })
        val ends = cuts.dropLast(1).map { (it.last + 1).toDouble() / rate }
        assertTrue("cut in the quiet: $ends", ends.size == 2 && ends[0] in 23.0..23.4 && ends[1] in 47.0..47.4)
        assertTrue(cuts.all { it.last - it.first + 1 <= Parakeet.LONGEST })
        assertEquals("short sound is one window", listOf(0 until 16_000), Parakeet.windows(FloatArray(16_000)))
    }

    @Test
    fun `tokens become words at their first token's time, punctuation and word pieces joined`() {
        val tokens = listOf("▁Ask", "▁not", "▁wh", "at", "▁your", "▁country", ",", "<|endoftext|>", "▁ask")
        val times = listOf(0.0f, 0.4f, 0.8f, 0.96f, 1.2f, 1.5f, 2.0f, 2.1f, 9.0f)
        val words = Parakeet.words(tokens, times, offset = 10_000, end = 12_500)
        assertEquals(listOf("Ask", "not", "what", "your", "country,", "ask"), words.map { it.text })
        assertEquals(listOf(10_000L, 10_400L, 10_800L, 11_200L, 11_500L, 19_000L), words.map { it.start })
        assertEquals("ends where the next word starts", 10_400L, words[0].end)
        assertEquals("the last ends at the window's end, never before it starts", 19_000L, words.last().end)
        assertEquals("a space marks a word as the ▁ does", listOf("Hi", "there"), Parakeet.words(listOf(" Hi", " there"), listOf(0f, 0.5f), 0, 1_000).map { it.text })
    }

    @Test
    fun `a long pause doesn't stretch a word over it`() {
        val words = Parakeet.words(listOf("▁Yes.", "▁Then"), listOf(0f, 8f), 0, 10_000)
        assertEquals(1_500L, words[0].end)
    }

    @Test
    fun `the language is guessed from letters and little words`() {
        assertEquals("en", LanguageGuess.of("And so my fellow Americans, ask not what your country can do for you."))
        assertEquals("ru", LanguageGuess.of("Я не знаю, что это было, но мы так и не поняли."))
        assertEquals("uk", LanguageGuess.of("Я не знаю, що це було, але ми так і не зрозуміли."))
        assertEquals("de", LanguageGuess.of("Ich weiß nicht, was das ist, und wir sind auf dem Weg."))
        assertEquals("fr", LanguageGuess.of("Je ne sais pas ce que vous voulez, mais nous allons le faire."))
        assertEquals("es", LanguageGuess.of("No sé por qué la gente dice que es un problema para los niños."))
        assertEquals("und", LanguageGuess.of("1234 ..."))
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `Parakeet's four files are pinned, a model of several files is fetched into its own folder`() = runTest {
        assertEquals(listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"), SpeechModel.PARAKEET.files.map { it.name })
        assertTrue(SpeechModel.PARAKEET.files.all { it.sha256.length == 64 })
        assertEquals("671 MB", SpeechModel.PARAKEET.megabytes)
        assertTrue("pinned to a commit, not a branch", SpeechModel.PARAKEET.source!!.contains("/resolve/ed5d745623472f97cfc2e6c2916ee248798fc5a8"))

        val a = ByteArray(3_000) { 1 }
        val b = ByteArray(2_000) { 2 }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/m/a.bin" -> MockResponse().setBody(Buffer().write(a))
                "/m/b.bin" -> MockResponse().setBody(Buffer().write(b))
                else -> MockResponse().setResponseCode(404)
            }
        }
        val dir = folder.newFolder()
        val models = SpeechModels(dir)
        val progress = mutableListOf<Long>()
        val pins = listOf(
            SpeechModels.Pin("Test", "two/a.bin", a.size.toLong(), sha(a), server.url("/m/a.bin").toString()),
            SpeechModels.Pin("Test", "two/b.bin", b.size.toLong(), sha(b), server.url("/m/b.bin").toString()),
        )
        pins.forEach { pin -> models.fetchPinned(pin) { done, _ -> progress += done } }
        assertTrue(File(dir, "two/a.bin").readBytes().contentEquals(a))
        assertTrue(File(dir, "two/b.bin").readBytes().contentEquals(b))
        assertFalse(models.isFetched(SpeechModel.PARAKEET))
    }

    @Test
    fun `a Parakeet job hears each piece with the model's folder and keeps its lines in the language guessed`() = runBlocking {
        val dir = folder.newFolder()
        // Parakeet's files, as if fetched: only their sizes are looked at
        SpeechModel.PARAKEET.files.forEach { f ->
            File(dir, "${SpeechModel.PARAKEET.folder}/${f.name}").apply { parentFile!!.mkdirs() }
                .let { RandomAccessFile(it, "rw").use { raf -> raf.setLength(f.size) } }
        }
        val models = SpeechModels(dir)
        assertTrue(models.isFetched(SpeechModel.PARAKEET))
        val folders = mutableListOf<File>()
        val kept = mutableListOf<Pair<String, List<SubtitleLine>>>()
        // The model is let go after the job says it is done, so the test waits for that rather than looking at once
        val released = kotlinx.coroutines.CompletableDeferred<Unit>()
        val jobs = SpeechJobs(
            scope = CoroutineScope(Dispatchers.IO),
            models = models,
            decode = { _, onPiece -> onPiece(FloatArray(16_000), 0); true },
            listen = { _, _, _, _, _ -> error("not whisper.cpp") },
            listenParakeet = { modelFolder, _, heard ->
                folders += modelFolder
                heard(100)
                val words = Parakeet.words(listOf("▁Я", "▁не", "▁знаю,", "▁что", "▁это", "▁было."), listOf(0f, 0.3f, 0.6f, 1.1f, 1.4f, 1.7f), 0, 2_200)
                Transcript(LanguageGuess.of(words.joinToString(" ") { it.text }), listOf(Segment(0, 2_200, "Я не знаю, что это было.", words)))
            },
            keep = { _, language, lines -> kept += language to lines; true },
            done = { released.complete(Unit) },
        )
        jobs.start(3, Engine.OnPhone(SpeechModel.PARAKEET), 60_000)
        withTimeout(10_000) { jobs.states.first { it[3] == Making.Idle && kept.isNotEmpty() } }
        assertEquals(listOf(File(dir, "parakeet-tdt-0.6b-v3")), folders)
        assertEquals("ru", kept.single().first)
        assertEquals("Я не знаю, что это было.", kept.single().second.single().text)
        withTimeout(10_000) { released.await() }
    }

    @Test
    fun `another video can start the moment one is done, while its model is still being let go`() = runBlocking {
        val letGo = kotlinx.coroutines.CompletableDeferred<Unit>()
        val kept = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val jobs = SpeechJobs(
            scope = CoroutineScope(Dispatchers.IO),
            models = SpeechModels(folder.newFolder()),
            decode = { _, onPiece -> onPiece(FloatArray(10), 0); true },
            listen = { _, _, _, _, _ -> null },
            listenCloud = { _, _, _ -> CloudHeard.Said(Transcript("en", listOf(Segment(0, 1_000, "Hello there.", Parakeet.words(listOf("▁Hello", "▁there."), listOf(0f, 0.5f), 0, 1_000))))) },
            keep = { id, _, _ -> kept += id; true },
            // The first job lingers here after saying it is done, as releasing a 670 MB model can
            done = { if (kept.size == 1) runBlocking { letGo.await() } },
        )
        jobs.start(1, Engine.Cloud(CloudSpeech.GROQ), 1_000)
        withTimeout(10_000) { jobs.states.first { it[1] == Making.Idle && kept.isNotEmpty() } }
        jobs.start(2, Engine.Cloud(CloudSpeech.GROQ), 1_000)
        letGo.complete(Unit)
        val second = withTimeout(10_000) { jobs.states.first { it[2] == Making.Idle || it[2] is Making.Failed }[2] }
        assertEquals(Making.Idle, second)
        assertEquals(listOf(1L, 2L), kept.toList())
    }
}
