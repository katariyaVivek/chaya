package com.chaya.app.ai

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/*
 * The person's own AI model, for translation and highlights (steps 5 to 7 of docs/PLAN.md): a service they have an
 * account with and its key, or a model running on a PC in their home through Ollama or LM Studio. Two protocols cover
 * them all: OpenAI's chat completions, which most services and both PC programs speak, and Anthropic's Messages.
 * This is `core::ai` of Chaya for Windows, ported; the requests themselves are in [AiClient].
 *
 * The key is never kept here: [AiKeys] keeps it encrypted, and it is handed in each time and sent only to the service
 * it belongs to. A plain `http://` address is accepted only on the phone or the local network, so a key never
 * crosses the internet unencrypted.
 */

/** How a service is spoken to. */
enum class Protocol {
    /** OpenAI's chat completions (`/chat/completions`, `/models`, a bearer key). */
    OPENAI,

    /** Anthropic's Messages (`/v1/messages`, `/v1/models`, `x-api-key`). */
    ANTHROPIC,
}

/** A service Chaya knows. */
data class AiService(
    val id: String,
    val name: String,
    val protocol: Protocol,
    /** Its address, ending before `/chat/completions`; for a program on a PC, an example the person changes. */
    val address: String,
    /** Whether it needs a key (a program on a PC does not). */
    val key: Boolean = true,
    /** A program on a PC in the home, reached at the address the person gives. */
    val local: Boolean = false,
    /** Where its keys are made, for the settings to point at. */
    val keysAt: String = "",
) {
    /** How a sentence names it: "The service" for one the person names. */
    val spoken: String get() = if (id == CUSTOM) "The service" else name

    companion object {
        const val CUSTOM = "custom"

        /** Every service offered, as the settings list them. */
        val ALL: List<AiService> = listOf(
            AiService("openai", "OpenAI", Protocol.OPENAI, "https://api.openai.com/v1", keysAt = "https://platform.openai.com/api-keys"),
            AiService("anthropic", "Anthropic", Protocol.ANTHROPIC, "https://api.anthropic.com/v1", keysAt = "https://console.anthropic.com/settings/keys"),
            AiService("gemini", "Gemini", Protocol.OPENAI, "https://generativelanguage.googleapis.com/v1beta/openai", keysAt = "https://aistudio.google.com/apikey"),
            AiService("deepseek", "DeepSeek", Protocol.OPENAI, "https://api.deepseek.com", keysAt = "https://platform.deepseek.com/api_keys"),
            AiService("openrouter", "OpenRouter", Protocol.OPENAI, "https://openrouter.ai/api/v1", keysAt = "https://openrouter.ai/keys"),
            AiService("mistral", "Mistral", Protocol.OPENAI, "https://api.mistral.ai/v1", keysAt = "https://console.mistral.ai/api-keys"),
            AiService("groq", "Groq", Protocol.OPENAI, "https://api.groq.com/openai/v1", keysAt = "https://console.groq.com/keys"),
            AiService("xai", "xAI", Protocol.OPENAI, "https://api.x.ai/v1", keysAt = "https://console.x.ai"),
            // On the phone, a program on a PC is never at 127.0.0.1: the person gives the PC's address on their network.
            AiService("ollama", "Ollama on a PC", Protocol.OPENAI, "http://192.168.1.10:11434/v1", key = false, local = true),
            AiService("lmstudio", "LM Studio on a PC", Protocol.OPENAI, "http://192.168.1.10:1234/v1", key = false, local = true),
            AiService(CUSTOM, "Another service (OpenAI-compatible)", Protocol.OPENAI, ""),
        )

        fun find(id: String?): AiService? = ALL.firstOrNull { it.id == id }
    }
}

/** What the person chose in Settings › AI model (never the key). */
data class AiSettings(
    val service: String? = null,
    /** The address, for a service the person names or a program on a PC. */
    val address: String? = null,
    val model: String? = null,
) {
    fun service(): AiService? = AiService.find(service)

    /**
     * Where the chosen service is: the address the person gave, or the service's own. A program on a PC and a service
     * the person names have none of their own (the PC program's is only an example).
     */
    fun address(): String? {
        val service = service() ?: return null
        val given = address?.trim()?.takeIf { it.isNotEmpty() }
        val address = (given ?: service.address.takeUnless { service.local }.orEmpty()).trimEnd('/')
        return address.takeIf { it.isNotEmpty() }
    }

    /** Ready to be asked something: a service and a model. */
    val chosen: Boolean get() = service() != null && !model.isNullOrBlank()

    fun toJson(): String = JSONObject().apply {
        service?.let { put("service", it) }
        address?.let { put("address", it) }
        model?.let { put("model", it) }
    }.toString()

    companion object {
        fun fromJson(text: String?): AiSettings {
            val json = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return AiSettings()
            fun field(name: String) = json.optString(name).takeIf { json.has(name) && it.isNotEmpty() }
            return AiSettings(field("service"), field("address"), field("model"))
        }
    }
}

/** Why the model could not be reached or used, in words a person can act on. */
sealed class AiError {
    /** No service is chosen, or no model. */
    data object NotSetUp : AiError()

    /** The service needs a key and none is saved. */
    data object NoKey : AiError()

    /** An address that is not one (or plain `http://` beyond the local network). */
    data object BadAddress : AiError()

    /** The service did not answer: offline, or a program on a PC that is not running. */
    data object Unreachable : AiError()

    /** The service refused the key. */
    data object RefusedKey : AiError()

    /** Too many requests: try again, after this many seconds if the service said. */
    data class RateLimited(val seconds: Long?) : AiError()

    /** The service has no model of that name. */
    data object NoSuchModel : AiError()

    /** The model answered, but not with the JSON asked for. */
    data object NotJson : AiError()

    /** The service answered with an error it described (its words, shortened). */
    data class Said(val words: String) : AiError()

    /** The service answered with an error and no words for it. */
    data class Status(val code: Int) : AiError()

    fun message(service: String): String = when (this) {
        NotSetUp -> "Choose a service and a model first."
        NoKey -> "Paste your $service key first."
        BadAddress -> "That address can't be used. It has to start with https:// (or http:// on your home network)."
        Unreachable -> "$service didn't answer. Check that it's running, or your connection."
        RefusedKey -> "$service refused the key. Check it, or make a new one."
        is RateLimited -> if (seconds != null) "$service is asking for fewer requests. Try again in $seconds s."
        else "$service is asking for fewer requests. Try again in a minute."
        NoSuchModel -> "$service has no model of that name. Pick one from its list."
        NotJson -> "The model answered, but not in the form Chaya asked for. Try another model."
        is Said -> "$service said: $words"
        is Status -> "$service answered with an error ($code)."
    }
}

/** A result that is either what was asked for or why it could not be had. */
sealed class AiResult<out T> {
    data class Done<T>(val value: T) : AiResult<T>()
    data class Failed(val error: AiError) : AiResult<Nothing>()

    fun getOrNull(): T? = (this as? Done)?.value
}

/** A service as it is reached: how, where (ending in `/`), and with which key. */
class Endpoint private constructor(val protocol: Protocol, val address: String, val key: String?) {
    /** `path` under the service's address. */
    fun at(path: String): String = address + path

    companion object {
        /** The chosen service at `settings`' address, with `key` (read from [AiKeys] by the caller). */
        fun of(settings: AiSettings, key: String?): AiResult<Endpoint> {
            val service = settings.service() ?: return AiResult.Failed(AiError.NotSetUp)
            val address = settings.address() ?: return AiResult.Failed(AiError.BadAddress)
            // OkHttp reads only http and https; anything else ("ftp://x") is no address at all.
            val url = address.toHttpUrlOrNull() ?: return AiResult.Failed(AiError.BadAddress)
            val local = onThisNetwork(url.host)
            if (!(url.scheme == "https" || (url.scheme == "http" && local))) return AiResult.Failed(AiError.BadAddress)
            val usable = key?.takeIf { it.isNotBlank() }
            if (service.key && service.id != AiService.CUSTOM && usable == null) return AiResult.Failed(AiError.NoKey)
            return AiResult.Done(Endpoint(service.protocol, "$address/", usable))
        }

        /** `host` is this phone or the local network: "localhost", a name ending in .local, or a private address. */
        fun onThisNetwork(host: String): Boolean {
            val bare = host.trim('[', ']').lowercase()
            ipv4(bare)?.let { (a, b) ->
                return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
            }
            if (':' in bare) {
                if (bare == "::1") return true
                val first = bare.substringBefore(':').ifEmpty { "0" }.toIntOrNull(16) ?: return false
                return (first and 0xfe00) == 0xfc00 || (first and 0xffc0) == 0xfe80
            }
            return bare == "localhost" || bare.endsWith(".local") || bare.endsWith(".localhost")
        }

        /** The first two numbers of a dotted IPv4 address, or null when `host` is not one. */
        private fun ipv4(host: String): Pair<Int, Int>? {
            val parts = host.split('.')
            if (parts.size != 4) return null
            val numbers = parts.map { part -> part.toIntOrNull()?.takeIf { it in 0..255 && part.length in 1..3 } ?: return null }
            return numbers[0] to numbers[1]
        }
    }
}

/** A question for the model: what it is for (`system`), what is asked (`user`), and how long its answer may run. */
data class Ask(val model: String, val system: String, val user: String, val maxTokens: Int)

/** Words in the names of models made for something other than text (OpenAI lists them all together). */
internal val NOT_FOR_CHAT = listOf(
    "embed", "tts", "whisper", "dall-e", "moderation", "image", "audio", "realtime", "transcribe", "rerank", "live",
)

/** The JSON object in a model's answer: all of it, or a ```json block, or from its first `{` to its last `}`. */
fun jsonIn(text: String): JSONObject? {
    val trimmed = text.trim()
    val fenced = trimmed.split("```").getOrNull(1)?.trim()?.removePrefix("json")?.trim()
    val open = trimmed.indexOf('{')
    val close = trimmed.lastIndexOf('}')
    val braced = if (open in 0 until close) trimmed.substring(open, close + 1) else null
    return listOfNotNull(trimmed, fenced, braced).firstNotNullOfOrNull(::wholeObject)
}

/** `text` read as one JSON object and nothing after it. */
private fun wholeObject(text: String): JSONObject? = try {
    val tokener = JSONTokener(text)
    val value = tokener.nextValue()
    if (value is JSONObject && !tokener.more()) value else null
} catch (_: JSONException) {
    null
}

/** What a failed answer means: its status, its `Retry-After`, and its body. */
internal fun failure(status: Int, retryAfter: String?, body: String): AiError {
    val json: Any? = try {
        JSONTokener(body).nextValue()
    } catch (_: JSONException) {
        null
    }
    // OpenAI's {"error": {"message"}}, Anthropic's {"error": {"type", "message"}}, or a bare {"error": "..."}.
    val said = (json as? JSONObject)?.let { obj ->
        (obj.opt("error") as? JSONObject)?.optString("message")?.takeIf { it.isNotEmpty() }
            ?: (obj.opt("error") as? String)
            ?: obj.optString("message")
    }.orEmpty()
    val lower = said.lowercase()
    return when {
        status == 401 || status == 403 -> AiError.RefusedKey
        // Gemini says a key is wrong with 400.
        status == 400 && "api key" in lower -> AiError.RefusedKey
        status == 429 -> AiError.RateLimited(retryAfter?.trim()?.toLongOrNull())
        status == 404 -> AiError.NoSuchModel
        "model" in lower && ("not found" in lower || "does not exist" in lower) -> AiError.NoSuchModel
        said.isNotBlank() -> AiError.Said(said.take(200).trim())
        else -> AiError.Status(status)
    }
}

/** The text in an OpenAI-style answer: a string, or (some services) a list of parts. */
internal fun openAiText(answer: JSONObject): String? {
    val message = answer.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return null
    val content: Any? = message.opt("content")
    if (content is String) return content
    if (content is JSONArray) return (0 until content.length()).mapNotNull { content.optJSONObject(it)?.optString("text") }.joinToString("")
    return null
}

/** The text parts of an Anthropic answer, joined. */
internal fun anthropicText(answer: JSONObject): String {
    val parts = answer.optJSONArray("content") ?: return ""
    return (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }
        .filter { it.optString("type") == "text" }
        .joinToString("") { it.optString("text") }
}

/** The models in a `/models` answer that are for text, without Gemini's `models/` prefix, sorted. */
internal fun chatModels(listed: JSONObject): List<String> {
    val data = listed.optJSONArray("data") ?: return emptyList()
    return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf(String::isNotEmpty) }
        .map { it.removePrefix("models/") }
        .filter { id -> NOT_FOR_CHAT.none { it in id } }
        .distinct()
        .sorted()
}
