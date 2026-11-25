package com.chaya.app.ai

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `core::ai`'s cases, ported: both protocols against a stand-in service, and what each failure means. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiClientTest {
    private val server = MockWebServer()
    private val client = AiClient()

    @After
    fun tearDown() = server.shutdown()

    private fun at(service: String, key: String?): Endpoint {
        val settings = AiSettings(service = service, address = server.url("/v1").toString(), model = "m")
        return (Endpoint.of(settings, key) as AiResult.Done).value
    }

    private val question = Ask(model = "small-model", system = "Answer in JSON.", user = "Say ok.", maxTokens = 100)

    private fun openAiAnswer(text: String) = MockResponse().setBody(
        JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", text)))).toString(),
    )

    private fun status(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)

    @Test
    fun `OpenAI's protocol asks with the key and reads the reply`() = runTest {
        server.enqueue(openAiAnswer("""{"ok": true}"""))
        val reply = client.askJson(at("openai", "sk-test"), question) { it.optBoolean("ok") }
        assertEquals("""{"ok":true}""", (reply as AiResult.Done).value.toString())
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/v1/chat/completions", sent.path)
        assertEquals("Bearer sk-test", sent.getHeader("Authorization"))
        val body = JSONObject(sent.body.readUtf8())
        assertEquals("small-model", body.getString("model"))
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("Say ok.", body.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(false, body.getBoolean("stream"))
    }

    @Test
    fun `Anthropic's protocol asks with its headers and reads the text parts`() = runTest {
        val parts = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "{\"ok\": "))
            .put(JSONObject().put("type", "text").put("text", "true}"))
        server.enqueue(MockResponse().setBody(JSONObject().put("content", parts).toString()))
        val reply = client.askJson(at("anthropic", "sk-ant-test"), question) { it.optBoolean("ok") }
        assertTrue(reply is AiResult.Done)
        val sent = server.takeRequest()
        assertEquals("/v1/messages", sent.path)
        assertEquals("sk-ant-test", sent.getHeader("x-api-key"))
        assertEquals("2023-06-01", sent.getHeader("anthropic-version"))
        assertNull("the key goes only where Anthropic reads it", sent.getHeader("Authorization"))
        val body = JSONObject(sent.body.readUtf8())
        assertEquals("Answer in JSON.", body.getString("system"))
        assertEquals(100, body.getInt("max_tokens"))
        assertEquals("user", body.getJSONArray("messages").getJSONObject(0).getString("role"))
    }

    @Test
    fun `a refused key and a rate limit are told apart on both protocols`() = runTest {
        for (service in listOf("openai", "anthropic")) {
            server.enqueue(status(401, """{"error": {"type": "authentication_error", "message": "invalid x-api-key"}}"""))
            assertEquals(service, AiResult.Failed(AiError.RefusedKey), client.ask(at(service, "k"), question))
            server.enqueue(status(429, """{"error": {"message": "Rate limit reached"}}""").setHeader("Retry-After", "20"))
            assertEquals(service, AiResult.Failed(AiError.RateLimited(20)), client.ask(at(service, "k"), question))
        }
        // Gemini says a wrong key with 400.
        server.enqueue(status(400, """[{"error": {"code": 400}}]"""))
        assertEquals(AiResult.Failed(AiError.Status(400)), client.ask(at("gemini", "k"), question))
        server.enqueue(status(400, """{"error": {"message": "API key not valid. Please pass a valid API key."}}"""))
        assertEquals(AiResult.Failed(AiError.RefusedKey), client.ask(at("gemini", "k"), question))
        server.enqueue(status(500, """{"error": {"message": "Quota exceeded."}}"""))
        assertEquals(AiResult.Failed(AiError.Said("Quota exceeded.")), client.ask(at("openai", "k"), question))
    }

    @Test
    fun `a model that answers in prose is not taken for JSON`() = runTest {
        server.enqueue(openAiAnswer("Sure! Here is a summary of the video."))
        assertEquals(AiResult.Failed(AiError.NotJson), client.askJson(at("openai", "k"), question) { true })
        // JSON, but not what was asked for.
        server.enqueue(openAiAnswer("""{"answer": "ok"}"""))
        assertEquals(AiResult.Failed(AiError.NotJson), client.askJson(at("openai", "k"), question) { it.optBoolean("ok") })
    }

    @Test
    fun `JSON is found in a fenced block or among words`() {
        assertEquals("""{"ok":true}""", jsonIn("""{"ok": true}""").toString())
        assertEquals("""{"ok":true}""", jsonIn("```json\n{\"ok\": true}\n```").toString())
        assertEquals("""{"ok":true}""", jsonIn("Here you go: {\"ok\": true} Hope that helps!").toString())
        assertNull("an object, not a list", jsonIn("[1, 2]"))
        assertNull(jsonIn("no json here"))
    }

    @Test
    fun `the models listed are those for text without Gemini's prefix`() = runTest {
        val ids = listOf(
            "gpt-4.1-mini", "text-embedding-3-small", "whisper-1", "models/gemini-2.5-flash", "dall-e-3", "gpt-4.1",
            "models/gemini-3-flash-live", "gemini-live-2.5-flash-preview",
        )
        val data = JSONArray().apply { ids.forEach { put(JSONObject().put("id", it)) } }
        server.enqueue(MockResponse().setBody(JSONObject().put("data", data).toString()))
        val names = client.models(at("openai", "k"))
        assertEquals(AiResult.Done(listOf("gemini-2.5-flash", "gpt-4.1", "gpt-4.1-mini")), names)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `Test asks a small question, with no key for a model on a PC`() = runTest {
        server.enqueue(openAiAnswer("```json\n{\"ok\": true, \"word\": \"Chaya\"}\n```"))
        assertTrue(client.test(at("ollama", null), "llama3.2") is AiResult.Done)
        assertNull("no key for a program on a PC", server.takeRequest().getHeader("Authorization"))
        server.enqueue(status(404, """{"error": {"message": "model 'x' not found"}}"""))
        assertEquals(AiResult.Failed(AiError.NoSuchModel), client.test(at("ollama", null), "x"))
    }

    @Test
    fun `a service that is not there is unreachable`() = runTest {
        val settings = AiSettings(service = "lmstudio", address = "http://127.0.0.1:9/v1", model = "m")
        val endpoint = (Endpoint.of(settings, null) as AiResult.Done).value
        assertEquals(AiResult.Failed(AiError.Unreachable), client.ask(endpoint, question))
    }

    @Test
    fun `a key goes only over https or within the home network`() {
        fun with(service: String, address: String?, key: String? = "k") =
            Endpoint.of(AiSettings(service = service, address = address, model = "m"), key).let {
                (it as? AiResult.Done)?.value?.address ?: (it as AiResult.Failed).error
            }
        assertEquals("https://llm.example.com/v1/", with("custom", "https://llm.example.com/v1/"))
        assertEquals("http://192.168.1.20:1234/v1/", with("custom", "http://192.168.1.20:1234/v1"))
        assertEquals("http://10.0.0.5:11434/v1/", with("ollama", "http://10.0.0.5:11434/v1", key = null))
        assertEquals("http://my-pc.local:1234/v1/", with("lmstudio", "http://my-pc.local:1234/v1", key = null))
        assertEquals("http://localhost:8080/v1/", with("custom", "http://localhost:8080/v1"))
        assertEquals("plain http, far away", AiError.BadAddress, with("custom", "http://llm.example.com/v1"))
        assertEquals(AiError.BadAddress, with("custom", "http://172.32.0.1/v1"))
        assertEquals(AiError.BadAddress, with("custom", "ftp://x/"))
        assertEquals("a service the person names needs an address", AiError.BadAddress, with("custom", ""))
        assertEquals("a PC's program has no address of its own on the phone", AiError.BadAddress, with("ollama", null, key = null))
        assertEquals(AiError.NoKey, with("openai", null, key = null))
        assertEquals("https://api.openai.com/v1/", with("openai", null))
        assertEquals(AiResult.Failed(AiError.NotSetUp), Endpoint.of(AiSettings(), null))
    }

    @Test
    fun `every failure has a sentence naming the service`() {
        listOf(
            AiError.NotSetUp, AiError.NoKey, AiError.BadAddress, AiError.Unreachable, AiError.RefusedKey,
            AiError.RateLimited(5), AiError.RateLimited(null), AiError.NoSuchModel, AiError.NotJson,
            AiError.Said("Quota exceeded."), AiError.Status(502),
        ).forEach { error ->
            val message = error.message("Groq")
            assertTrue("$error: $message", message.endsWith('.') && message.length > 20)
        }
    }
}
