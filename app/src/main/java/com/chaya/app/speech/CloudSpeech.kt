package com.chaya.app.speech

import com.chaya.app.ai.AiError
import com.chaya.app.ai.AiResult
import com.chaya.app.ai.Endpoint
import com.chaya.app.ai.failure
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * A service that writes subtitles from the sound, with the person's own key (Settings › AI model): a large Whisper,
 * far more accurate than a phone can run and done in seconds, for cents an hour. The sound is sent to it, so this is
 * only ever chosen by the person, never by default.
 */
enum class CloudSpeech(
    /** The AI model service whose key it uses ([com.chaya.app.ai.AiService]). */
    val service: String,
    val label: String,
    val model: String,
    /** Its price, as the card says it. */
    val price: String,
) {
    GROQ("groq", "Groq", "whisper-large-v3", "about 11¢ an hour of video"),
    // The only OpenAI model that gives each word's time; OpenAI says it ends in February 2027.
    OPENAI("openai", "OpenAI", "whisper-1", "about 36¢ an hour of video"),
}

/**
 * Sends a piece of sound to a [CloudSpeech] service's `/audio/transcriptions` (both speak OpenAI's) and reads back what
 * was said, with each word's time, as a [Transcript] that [SpeechLines] cuts into lines as it does whisper.cpp's.
 */
class CloudListener(private val client: OkHttpClient = defaultClient()) {

    /** What was said in [samples] (16 kHz mono); [language] (ISO 639-1) when it is known, else the service hears it. */
    fun transcribe(endpoint: Endpoint, speech: CloudSpeech, samples: FloatArray, language: String?): AiResult<Transcript> {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "sound.wav", wav(samples).toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", speech.model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "word")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .addFormDataPart("temperature", "0")
            .apply { language?.let { addFormDataPart("language", it) } }
            .build()
        val request = Request.Builder().url(endpoint.at("audio/transcriptions")).post(body)
            .apply { endpoint.key?.let { header("Authorization", "Bearer $it") } }
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return AiResult.Failed(failure(response.code, response.header("Retry-After"), text))
                fromVerbose(text)?.let { AiResult.Done(it) } ?: AiResult.Failed(AiError.Status(response.code))
            }
        } catch (_: IOException) {
            AiResult.Failed(AiError.Unreachable)
        }
    }

    companion object {
        /** Five minutes of sound is 9.6 MB as WAV: an upload takes a while on a phone's connection. */
        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()

        /** [samples] (16 kHz mono, -1 to 1) as a 16-bit WAV file. */
        fun wav(samples: FloatArray): ByteArray {
            val data = samples.size * 2
            val out = ByteArrayOutputStream(44 + data)
            fun int(v: Int) = repeat(4) { out.write((v ushr (8 * it)) and 0xff) }
            fun short(v: Int) = repeat(2) { out.write((v ushr (8 * it)) and 0xff) }
            out.write("RIFF".toByteArray())
            int(36 + data)
            out.write("WAVEfmt ".toByteArray())
            int(16)
            short(1) // PCM
            short(1) // one channel
            int(Sound16k.RATE)
            int(Sound16k.RATE * 2)
            short(2)
            short(16)
            out.write("data".toByteArray())
            int(data)
            for (s in samples) short((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt())
            return out.toByteArray()
        }

        /** "en" for "en", "english" or "English": services name the language either way. */
        fun languageCode(said: String): String {
            val name = said.trim()
            if (name.length in 2..3) return name.lowercase(Locale.ROOT)
            val found = Locale.getAvailableLocales().firstOrNull { it.getDisplayLanguage(Locale.ENGLISH).equals(name, ignoreCase = true) }
            return found?.language ?: ""
        }

        /**
         * The service's `verbose_json`: its segments, each with the words it gave in its time (by their middle); a
         * segment given without words has them spread evenly over it, so lines can still be cut. Null when it is not that.
         */
        fun fromVerbose(json: String): Transcript? {
            val root = try {
                JSONObject(json)
            } catch (_: JSONException) {
                return null
            }
            fun ms(seconds: Double) = (seconds * 1000).roundToLong()
            val words = root.optJSONArray("words")?.let { list ->
                (0 until list.length()).mapNotNull { i ->
                    val w = list.optJSONObject(i) ?: return@mapNotNull null
                    val text = w.optString("word").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    Word(text, ms(w.optDouble("start")), ms(w.optDouble("end")), 1f)
                }
            }.orEmpty()
            val list = root.optJSONArray("segments")
            val segments = if (list == null || list.length() == 0) {
                // Words only: one segment holding them all
                if (words.isEmpty()) emptyList()
                else listOf(Segment(words.first().start, words.last().end, words.joinToString(" ") { it.text }, words))
            } else {
                (0 until list.length()).mapNotNull { i ->
                    val s = list.optJSONObject(i) ?: return@mapNotNull null
                    val start = ms(s.optDouble("start"))
                    val end = ms(s.optDouble("end"))
                    val text = s.optString("text").trim()
                    val inside = words.filter { (it.start + it.end) / 2 in start until maxOf(end, start + 1) }
                    Segment(start, end, text, inside.ifEmpty { spread(text, start, end) })
                }
            }
            if (segments.isEmpty() && !root.has("text")) return null
            return Transcript(languageCode(root.optString("language")), segments)
        }

        /** [text]'s words spread evenly from [start] to [end]. */
        private fun spread(text: String, start: Long, end: Long): List<Word> {
            val words = text.split(' ').filter { it.isNotBlank() }
            if (words.isEmpty()) return emptyList()
            val each = maxOf(1L, (end - start) / words.size)
            return words.mapIndexed { i, w -> Word(w, start + i * each, start + (i + 1) * each, 1f) }
        }
    }
}
