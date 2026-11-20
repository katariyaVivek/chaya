package com.chaya.app.speech

import com.chaya.app.ai.AiError
import com.chaya.app.ai.AiResult
import com.chaya.app.ai.AiSettings
import com.chaya.app.ai.Endpoint
import com.chaya.app.subtitles.SubtitleLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Subtitles made by Groq or OpenAI with the person's key: what is sent, what is read back, and what stops it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudSpeechTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    private fun endpoint(): Endpoint =
        (Endpoint.of(AiSettings(service = "groq", address = server.url("/openai/v1").toString()), "gsk_test") as AiResult.Done).value

    /** What Groq answers with verbose_json and both granularities, for "Ask not what your country can do for you." */
    private val groq = JSONObject()
        .put("task", "transcribe").put("language", "English").put("duration", 4.2)
        .put("text", " Ask not what your country can do for you.")
        .put("segments", JSONArray().put(JSONObject().put("id", 0).put("start", 0.0).put("end", 4.2).put("text", " Ask not what your country can do for you.")))
        .put(
            "words",
            JSONArray().apply {
                "Ask not what your country can do for you.".split(' ').forEachIndexed { i, w ->
                    put(JSONObject().put("word", w).put("start", i * 0.4).put("end", i * 0.4 + 0.35))
                }
            },
        ).toString()

    @Test
    fun `the sound goes as a WAV with the model and word timings asked for, and the key only as a bearer`() {
        server.enqueue(MockResponse().setBody(groq))
        val heard = CloudListener().transcribe(endpoint(), CloudSpeech.GROQ, FloatArray(16_000) { 0.1f }, "en")
        val transcript = (heard as AiResult.Done).value
        assertEquals("en", transcript.language)
        assertEquals("Ask not what your country can do for you.", transcript.lines().single().text)
        assertEquals(1_200L to 1_550L, transcript.segments[0].words[3].let { it.start to it.end })

        val sent = server.takeRequest()
        assertEquals("/openai/v1/audio/transcriptions", sent.path)
        assertEquals("Bearer gsk_test", sent.getHeader("Authorization"))
        val body = sent.body.readByteArray()
        val text = String(body, Charsets.ISO_8859_1)
        for (part in listOf("name=\"model\"", "whisper-large-v3", "name=\"response_format\"", "verbose_json", "name=\"language\"")) {
            assertTrue(part, part in text)
        }
        assertEquals("both timings", 2, Regex("timestamp_granularities\\[]").findAll(text).count())
        assertTrue("a WAV file", "filename=\"sound.wav\"" in text && "RIFF" in text && "WAVEfmt " in text)
    }

    @Test
    fun `a WAV is 16 kHz mono 16-bit, its samples as they were`() {
        val wav = CloudListener.wav(floatArrayOf(0f, 0.5f, -1f, 2f))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + 8, wav.size)
        assertEquals(16_000, b.getInt(24))
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(8, b.getInt(40))
        assertEquals(listOf(0, 16_384, -32_767, 32_767), (0 until 4).map { b.getShort(44 + it * 2).toInt() })
    }

    @Test
    fun `segments without words have them spread over their time, and the language is named either way`() {
        val json = """{"language": "russian", "text": "Привет мир", "segments": [{"start": 1.0, "end": 3.0, "text": " Привет мир"}]}"""
        val transcript = CloudListener.fromVerbose(json)!!
        assertEquals("ru", transcript.language)
        assertEquals(listOf(Word("Привет", 1_000, 2_000, 1f), Word("мир", 2_000, 3_000, 1f)), transcript.segments[0].words)
        assertEquals("fr", CloudListener.languageCode("fr"))
        assertEquals("", CloudListener.languageCode("Klingon"))
        assertNull(CloudListener.fromVerbose("not json"))
    }

    @Test
    fun `a refused key or a service not there is said in words`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error": {"message": "Invalid API Key"}}"""))
        assertEquals(AiResult.Failed(AiError.RefusedKey), CloudListener().transcribe(endpoint(), CloudSpeech.GROQ, FloatArray(10), null))
        val gone = (Endpoint.of(AiSettings(service = "custom", address = "http://127.0.0.1:9/v1"), "k") as AiResult.Done).value
        assertEquals(AiResult.Failed(AiError.Unreachable), CloudListener().transcribe(gone, CloudSpeech.GROQ, FloatArray(10), null))
    }

    @Test
    fun `a cloud job sends each piece evened out, keeps its times in place, and stops in words when refused`() = runBlocking {
        val sent = mutableListOf<Pair<Float, String?>>()
        val kept = mutableListOf<Pair<String, List<SubtitleLine>>>()
        var refuse = false
        val jobs = SpeechJobs(
            scope = CoroutineScope(Dispatchers.IO),
            models = SpeechModels(folder.newFolder()),
            decode = { _, onPiece ->
                onPiece(FloatArray(16_000) { 0.01f * kotlin.math.sin(it * 0.1f) }, 0)
                onPiece(FloatArray(16_000) { 0.01f * kotlin.math.sin(it * 0.1f) }, 300_000)
                true
            },
            listen = { _, _, _, _, _ -> error("not on the phone") },
            listenCloud = { _, samples, language ->
                sent += (samples.maxOf { kotlin.math.abs(it) }) to language
                if (refuse) CloudHeard.Failed("Groq refused the key. Check it, or make a new one.")
                else CloudHeard.Said(CloudListener.fromVerbose(groq)!!)
            },
            keep = { _, language, lines -> kept += language to lines; true },
        )
        jobs.start(7, Engine.Cloud(CloudSpeech.GROQ), 600_000)
        withTimeout(10_000) { jobs.states.first { it[7] == Making.Idle && kept.isNotEmpty() } }
        assertTrue("soft sound lifted before it is sent: ${sent[0].first}", sent[0].first > 0.05f)
        assertEquals("the language heard first is asked for after", listOf(null, "en"), sent.map { it.second })
        val (language, lines) = kept.single()
        assertEquals("en", language)
        assertEquals(300_000L - SpeechLines.EARLY, lines[1].start)

        refuse = true
        jobs.start(8, Engine.Cloud(CloudSpeech.GROQ), 600_000)
        val failed = withTimeout(10_000) { jobs.states.first { it[8] is Making.Failed }[8] }
        assertEquals(Making.Failed("Groq refused the key. Check it, or make a new one."), failed)
    }
}
