package com.chaya.app.subtitles

import com.chaya.app.ai.AiClient
import com.chaya.app.ai.AiError
import com.chaya.app.ai.AiResult
import com.chaya.app.ai.AiSettings
import com.chaya.app.ai.Endpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
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
import java.util.Collections

/** `core::translate`'s cases, ported, against a stand-in model; and the versions kept beside the subtitles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranslateTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @After
    fun tearDown() = server.shutdown()

    private fun endpoint(): Endpoint =
        (Endpoint.of(AiSettings(service = "openai", address = server.url("/v1").toString(), model = "m"), "k") as AiResult.Done).value

    /** The numbers a request asks for, from its "one item for each of the numbers …" line. */
    private fun asked(user: String): List<Int> =
        user.substringAfter("each of the numbers ").substringBefore(" and no others").split(", ").map { it.toInt() }

    private fun userOf(request: RecordedRequest): String =
        JSONObject(request.body.readUtf8()).getJSONArray("messages").getJSONObject(1).getString("content")

    private fun reply(items: List<Pair<Int, String>>): MockResponse {
        val lines = JSONArray().apply { items.forEach { (n, text) -> put(JSONObject().put("n", n).put("text", text)) } }
        val content = JSONObject().put("lines", lines).toString()
        return MockResponse().setBody(
            JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString(),
        )
    }

    /** A model answering each line asked for with "ES n", except as [leave] (n, asked alone) says. */
    private fun model(leave: (Int, Boolean) -> Boolean = { _, _ -> false }, answer: ((String) -> MockResponse)? = null) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val user = userOf(request)
                sent += user
                answer?.let { return it(user) }
                val asked = asked(user)
                val alone = asked.size == 1
                return reply(asked.filter { !leave(it, alone) }.map { it to "ES $it" })
            }
        }
    }

    private fun lines(count: Int) = (1..count).map { "Line number $it of the talk." }

    private suspend fun run(lines: List<String>, done: MutableList<String?>): TranslateError? =
        Translator(AiClient()).translate(endpoint(), Translator.Job("A walk in the park", lines, "Spanish", "small"), done)

    @Test
    fun `every line goes out numbered in batches and comes back in step`() = runTest {
        model()
        val done = mutableListOf<String?>()
        assertNull(run(lines(100), done))
        assertEquals((1..100).map { "ES $it" }, done)
        assertEquals("100 lines in batches of 40", 3, sent.size)
        assertEquals((1..40).toList(), asked(sent[0]))
        assertEquals((81..100).toList(), asked(sent[2]))
        // Each batch carries the title and the whole transcript.
        assertTrue(sent[2].contains("Video title: A walk in the park"))
        assertTrue(sent[2].contains("1: Line number 1 of the talk.") && sent[2].contains("100: Line number 100 of the talk."))
        assertTrue(sent[2].contains("into Spanish"))
    }

    @Test
    fun `a line the reply leaves out is asked for on its own`() = runTest {
        model(leave = { n, alone -> n == 7 && !alone })
        val done = mutableListOf<String?>()
        assertNull(run(lines(10), done))
        assertEquals("ES 7", done[6])
        assertEquals(2, sent.size)
        assertEquals(listOf(7), asked(sent[1]))
    }

    @Test
    fun `two lines merged into one leave the second to be asked again`() = runTest {
        model(answer = { user ->
            val asked = asked(user)
            if (asked.size == 1) reply(listOf(asked[0] to "ES ${asked[0]}"))
            else reply(asked.filter { it != 5 }.map { it to if (it == 4) "ES 4 y ES 5" else "ES $it" })
        })
        val done = mutableListOf<String?>()
        assertNull(run(lines(8), done))
        assertEquals("every line has its own, in step", "ES 5", done[4])
        assertEquals(8, done.count { it != null })
    }

    @Test
    fun `a line never given leaves the rest kept and says how many, and only it is asked for again`() = runTest {
        model(leave = { n, _ -> n == 3 })
        val done = mutableListOf<String?>()
        assertEquals(TranslateError.Incomplete(1), run(lines(5), done))
        assertEquals(4, done.count { it != null })
        assertEquals("the batch, then the line alone twice", 1 + Translator.ALONE, sent.size)
        sent.clear()
        model()
        assertNull(run(lines(5), done))
        assertEquals("nothing is paid for twice", listOf(listOf(3)), sent.map(::asked))
    }

    @Test
    fun `a rate limit is waited out and a refused key stops it`() = runBlocking {
        var tries = 0
        model(answer = { user ->
            tries++
            if (tries == 1) MockResponse().setResponseCode(429).setHeader("Retry-After", "0").setBody("{}")
            else reply(asked(user).map { it to "ES $it" })
        })
        val done = mutableListOf<String?>()
        assertNull(run(lines(3), done))
        assertEquals(2, tries)
        model(answer = { MockResponse().setResponseCode(401).setBody("""{"error": {"message": "bad key"}}""") })
        assertEquals(TranslateError.Ai(AiError.RefusedKey), run(lines(3), mutableListOf()))
    }

    @Test
    fun `a long transcript goes as the lines around the batch`() {
        val source = (1..3000).map { "A rather long line of the talk, number $it." }
        val question = Translator.question(Translator.Job("Long", source, "Hindi", "m"), (1500 until 1540).toList())
        assertTrue("${question.length}", question.length < Translator.AROUND + 8_000)
        assertTrue(question.contains("1490: ") && question.contains("1550: "))
        assertFalse("not the start of a long video", question.contains("\n1: "))
    }

    @Test
    fun `batches are runs of lines not yet translated`() {
        val done = MutableList<String?>(10) { null }.also { it[2] = "x"; it[3] = "x" }
        assertEquals(listOf(listOf(0, 1), listOf(4, 5, 6, 7, 8, 9)), Translator.batches(lines(10), done))
        val long = List(5) { "x".repeat(1_400) }
        assertEquals("by characters", listOf(listOf(0, 1), listOf(2, 3), listOf(4)), Translator.batches(long, List(5) { null }))
    }

    @Test
    fun `a reply's lines are read by number whatever the number's type`() {
        val reply = JSONObject(
            """{"lines": [{"n": 1, "text": " Hola "}, {"n": "2", "text": "Adiós"}, {"n": 9, "text": "x"}, {"n": 3, "text": ""}]}""",
        )
        assertEquals(listOf(0 to "Hola", 1 to "Adiós"), Translator.read(reply, 3))
    }

    @Test
    fun `a version is kept for each language and model, shown, and goes with the download`() {
        val dir = folder.newFolder()
        val store = TranslationStore(dir)
        assertEquals(VideoTranslations(), store.load(12))
        val groq = Translation("en", "es", "Groq · llama", 1, listOf("Hola", null))
        store.keep(12, groq)
        store.keep(12, Translation("en", "es", "OpenAI · gpt", 2, listOf("Hola", "Adiós")))
        val carried = store.keep(12, groq.copy(lines = listOf("Hola", "Chao")))
        assertEquals(2, carried.versions.size)
        assertEquals("carrying on shows that version", 0, carried.shown)
        assertEquals(listOf("Hola", "Chao"), store.load(12).shown(2)?.lines)
        assertNull("a version that no longer fits the lines is not shown", store.load(12).shown(3))
        assertEquals(null, store.show(12, null).shown)
        assertEquals(null, store.load(12).shown)
        File(dir, "12.en.srt").writeText("1\n00:00:01,000 --> 00:00:02,000\nHello\n")
        SubtitleStore(dir).forget(12)
        assertEquals(VideoTranslations(), store.load(12))
    }

    @Test
    fun `a translation runs in the app's scope, keeps every batch, and says what stopped it`() = runBlocking {
        model(leave = { n, _ -> n == 2 })
        val dir = folder.newFolder()
        val store = TranslationStore(dir)
        val subtitles = SavedSubtitles("en", (1..3).map { SubtitleLine(it * 1000L, it * 1000L + 500, "Line $it") })
        val jobs = TranslationJobs(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            store = store,
            subtitles = { subtitles },
            translator = Translator(AiClient()),
            settings = { AiSettings(service = "groq", model = "llama") },
            endpoint = { AiResult.Done(endpoint()) },
        )
        jobs.start(12, "Talk", "es")
        val failed = withTimeout(10_000) { jobs.states.first { it[12] is Translating.Failed }[12] }
        assertEquals(Translating.Failed("The model left out a line. Try again: what's translated is kept."), failed)
        val kept = store.load(12).versions.single()
        assertEquals("Groq · llama", kept.model)
        assertEquals(listOf("ES 1", null, "ES 3"), kept.lines)
        assertTrue("into Spanish, named in English for the model", sent.first().contains("into Spanish"))

        val notSetUp = TranslationJobs(CoroutineScope(Dispatchers.IO), store, { subtitles }, Translator(AiClient()), { AiSettings() }, { AiResult.Failed(AiError.NotSetUp) })
        notSetUp.start(12, "Talk", "es")
        val said = withTimeout(10_000) { notSetUp.states.first { it[12] is Translating.Failed }[12] }
        assertTrue((said as Translating.Failed).message.contains("AI model"))
    }
}
