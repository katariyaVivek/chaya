package com.chaya.app.speech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * A speech model whisper.cpp listens with: the smaller ones only, for a phone's memory and battery. Its file, size and
 * the SHA-256 Chaya pins for it (as Windows pins its models in `speech/models.json`), and the attention heads that time
 * its words.
 */
enum class SpeechModel(val label: String, val file: String, val size: Long, val sha256: String, val dtw: String, val note: String) {
    TINY("Tiny", "ggml-tiny.bin", 77_691_713, "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21", "tiny", "Fastest"),
    BASE("Base", "ggml-base.bin", 147_951_465, "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe", "base", "More accurate"),
    ;

    /** "78 MB". */
    val megabytes: String get() = "${(size + 500_000) / 1_000_000} MB"
}

/** Why a model could not be had, in words. */
class ModelException(message: String) : IOException(message)

/**
 * The models, fetched once, when first needed, from Hugging Face's copy of whisper.cpp's own (as Windows fetches them),
 * checked against the pinned SHA-256, and kept in the app's files. A model being fetched is "ggml-tiny.bin.part" until
 * it is whole and its sum is right, so a fetch stopped part-way carries on from what arrived, and a file whose sum is
 * wrong is never used.
 */
class SpeechModels(
    private val dir: File,
    private val client: OkHttpClient = defaultClient(),
    private val source: String = SOURCE,
) {
    fun path(model: SpeechModel) = File(dir, model.file)

    /** Whether [model] is here, whole. Its sum was checked when it arrived, so here only its size is. */
    fun isFetched(model: SpeechModel) = path(model).length() == model.size

    /** Fetches [model] unless it is here, carrying on from a part; [onProgress] hears bytes so far of all. */
    suspend fun fetch(model: SpeechModel, onProgress: (Long, Long) -> Unit): File =
        fetchPinned(Pin(model.label, model.file, model.size, model.sha256), onProgress)

    /** A file Chaya fetches only as it pins it: its name in words, its file, its size and its SHA-256. */
    internal class Pin(val label: String, val file: String, val size: Long, val sha256: String)

    internal suspend fun fetchPinned(model: Pin, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val whole = File(dir, model.file)
        if (whole.length() == model.size) return@withContext whole
        dir.mkdirs()
        val part = File(dir, "${model.file}.part")
        if (part.length() > model.size) part.delete()
        if (part.length() < model.size) {
            val from = part.length()
            val request = Request.Builder().url("$source/${model.file}").apply { if (from > 0) header("Range", "bytes=$from-") }.build()
            val call = client.newCall(request)
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw ModelException("Chaya couldn't fetch the ${model.label} model (${response.code}).")
                    // A server that ignores the range sends it all again: start the part over.
                    val append = from > 0 && response.code == 206
                    val body = response.body ?: throw ModelException("Chaya couldn't fetch the ${model.label} model.")
                    FileOutputStream(part, append).use { out ->
                        var done = if (append) from else 0L
                        val buffer = ByteArray(64 * 1024)
                        body.byteStream().use { input ->
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                done += read
                                onProgress(done, model.size)
                            }
                        }
                    }
                }
            } catch (e: ModelException) {
                throw e
            } catch (e: IOException) {
                throw ModelException("The ${model.label} model stopped arriving. Try again: it carries on from where it stopped.")
            }
        }
        if (part.length() != model.size || sha256(part) != model.sha256) {
            part.delete()
            throw ModelException("The ${model.label} model that arrived isn't the one Chaya expects, so it wasn't kept. Try again.")
        }
        if (!part.renameTo(whole)) throw ModelException("Chaya couldn't keep the ${model.label} model.")
        whole
    }

    /** Deletes [model], to free its room. */
    fun delete(model: SpeechModel) {
        path(model).delete()
        File(dir, "${model.file}.part").delete()
    }

    companion object {
        /** Where the models come from: Hugging Face's copy of whisper.cpp's own. */
        const val SOURCE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
