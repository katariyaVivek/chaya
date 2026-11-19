package com.chaya.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Asks the person's AI model: the models a service offers, a question, and Settings' *Test*. Streaming is off; Chaya
 * asks for JSON and checks the reply is what it asked for. Nothing is sent unless the person asked for something
 * that needs the model.
 */
class AiClient(private val client: OkHttpClient = defaultClient()) {

    /** The models the service offers to chat with, by name, sorted; none when it lists none (the person types one). */
    suspend fun models(endpoint: Endpoint): AiResult<List<String>> =
        when (val listed = send(endpoint, Request.Builder().url(endpoint.at("models")).get())) {
            is AiResult.Done -> AiResult.Done(chatModels(listed.value))
            is AiResult.Failed -> listed
        }

    /** What the model answered, as text. */
    suspend fun ask(endpoint: Endpoint, ask: Ask): AiResult<String> = when (endpoint.protocol) {
        Protocol.OPENAI -> {
            // Nothing but the model and the messages: a temperature or a token limit is refused by some models.
            val body = JSONObject()
                .put("model", ask.model)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", ask.system))
                        .put(JSONObject().put("role", "user").put("content", ask.user)),
                )
                .put("stream", false)
            when (val answer = send(endpoint, post(endpoint.at("chat/completions"), body))) {
                is AiResult.Done -> openAiText(answer.value)?.let { AiResult.Done(it) } ?: AiResult.Failed(AiError.NotJson)
                is AiResult.Failed -> answer
            }
        }
        Protocol.ANTHROPIC -> {
            val body = JSONObject()
                .put("model", ask.model)
                .put("max_tokens", ask.maxTokens)
                .put("system", ask.system)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", ask.user)))
            when (val answer = send(endpoint, post(endpoint.at("messages"), body))) {
                is AiResult.Done -> AiResult.Done(anthropicText(answer.value))
                is AiResult.Failed -> answer
            }
        }
    }

    /**
     * What the model answered, as the JSON object asked for: read out of a fenced block or words around it, and
     * accepted only when [wanted] says it is what was asked for.
     */
    suspend fun askJson(endpoint: Endpoint, ask: Ask, wanted: (JSONObject) -> Boolean): AiResult<JSONObject> =
        when (val text = ask(endpoint, ask)) {
            is AiResult.Done -> jsonIn(text.value)?.takeIf(wanted)?.let { AiResult.Done(it) } ?: AiResult.Failed(AiError.NotJson)
            is AiResult.Failed -> text
        }

    /** Settings › AI model's *Test*: a small question the model must answer in JSON; how long it took, in ms. */
    suspend fun test(endpoint: Endpoint, model: String): AiResult<Long> {
        val started = System.nanoTime()
        val question = Ask(
            model = model,
            system = "You answer with JSON only: one object, and nothing before or after it.",
            user = """Reply with exactly this JSON object: {"ok": true, "word": "Chaya"}""",
            maxTokens = 64,
        )
        return when (val answer = askJson(endpoint, question) { it.optBoolean("ok", false) }) {
            is AiResult.Done -> AiResult.Done((System.nanoTime() - started) / 1_000_000)
            is AiResult.Failed -> answer
        }
    }

    private fun post(url: String, body: JSONObject) =
        Request.Builder().url(url).post(body.toString().toRequestBody(JSON))

    /** The key goes only where the service reads it: a bearer for OpenAI's protocol, `x-api-key` for Anthropic's. */
    private fun signed(endpoint: Endpoint, request: Request.Builder): Request {
        when (endpoint.protocol) {
            Protocol.OPENAI -> endpoint.key?.let { request.header("Authorization", "Bearer $it") }
            Protocol.ANTHROPIC -> {
                endpoint.key?.let { request.header("x-api-key", it) }
                request.header("anthropic-version", "2023-06-01")
            }
        }
        return request.build()
    }

    private suspend fun send(endpoint: Endpoint, request: Request.Builder): AiResult<JSONObject> = withContext(Dispatchers.IO) {
        val call = client.newCall(signed(endpoint, request))
        val response = try {
            call.await()
        } catch (_: IOException) {
            return@withContext AiResult.Failed(AiError.Unreachable)
        }
        response.use {
            val body = try {
                it.body?.string().orEmpty()
            } catch (_: IOException) {
                return@withContext AiResult.Failed(AiError.Unreachable)
            }
            if (!it.isSuccessful) return@withContext AiResult.Failed(failure(it.code, it.header("Retry-After"), body))
            try {
                AiResult.Done(JSONObject(body))
            } catch (_: JSONException) {
                AiResult.Failed(AiError.Status(it.code))
            }
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** A model on a slow PC can take minutes over a long question. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS)
            .build()
    }
}

/** The call's response, cancelling the call when the coroutine asking is cancelled (the person stopped it). */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = continuation.resume(response) { _, _, _ -> response.close() }
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }
    })
}
