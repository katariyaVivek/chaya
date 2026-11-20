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

/** One file of a model, as Chaya pins it: its name, size and SHA-256. */
data class ModelFile(val name: String, val size: Long, val sha256: String)

/** Which engine runs a model. */
enum class SpeechEngine {
    /** whisper.cpp (`src/main/cpp`), any of 99 languages. */
    WHISPER,

    /** sherpa-onnx, with NVIDIA's Parakeet TDT: 25 European languages, far more accurate and faster than Whisper Base. */
    PARAKEET,
}

/**
 * A speech model the phone listens with, the smaller and quicker ones only, for a phone's memory and battery: its
 * files, sizes and the SHA-256 Chaya pins for each (as Windows pins its models in `speech/models.json`), where they
 * come from (null: whisper.cpp's own, [SpeechModels.SOURCE]), and for Whisper the attention heads that time its words.
 */
enum class SpeechModel(
    val label: String,
    val note: String,
    val engine: SpeechEngine,
    val files: List<ModelFile>,
    val source: String? = null,
    /** The folder its files are kept in, under the models' own; empty for one file kept on its own. */
    val folder: String = "",
    val dtw: String = "",
) {
    TINY(
        "Tiny", "Fastest", SpeechEngine.WHISPER,
        listOf(ModelFile("ggml-tiny.bin", 77_691_713, "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21")),
        dtw = "tiny",
    ),
    BASE(
        "Base", "More accurate", SpeechEngine.WHISPER,
        listOf(ModelFile("ggml-base.bin", 147_951_465, "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe")),
        dtw = "base",
    ),

    /**
     * NVIDIA's Parakeet TDT 0.6B v3, int8, as sherpa-onnx's author exports it, at a pinned commit: English, Russian,
     * Ukrainian and 22 more European languages, with punctuation and capitals, heard far better than by Whisper Base.
     */
    PARAKEET(
        "Parakeet", "Best on the phone · European languages", SpeechEngine.PARAKEET,
        listOf(
            ModelFile("encoder.int8.onnx", 652_282_294, "1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479"),
            ModelFile("decoder.int8.onnx", 11_845_274, "0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5"),
            ModelFile("joiner.int8.onnx", 6_355_277, "cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98"),
            ModelFile("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
        ),
        source = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/ed5d745623472f97cfc2e6c2916ee248798fc5a8",
        folder = "parakeet-tdt-0.6b-v3",
    ),
    ;

    /** All its files together, in bytes. */
    val size: Long get() = files.sumOf { it.size }

    /** "78 MB". */
    val megabytes: String get() = "${(size + 500_000) / 1_000_000} MB"

    /** A Whisper model's one file. */
    val file: String get() = files.single().name
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
    /** A Whisper model's file, or the folder holding a model of several files. */
    fun path(model: SpeechModel) = if (model.folder.isEmpty()) File(dir, model.file) else File(dir, model.folder)

    private fun fileOf(model: SpeechModel, file: ModelFile) =
        if (model.folder.isEmpty()) file.name else "${model.folder}/${file.name}"

    /** Whether [model] is here, whole. Each file's sum was checked when it arrived, so here only their sizes are. */
    fun isFetched(model: SpeechModel) = model.files.all { File(dir, fileOf(model, it)).length() == it.size }

    /**
     * Fetches whatever of [model] is not here, each file carrying on from its part; [onProgress] hears bytes so far of
     * all its files. Its [path].
     */
    suspend fun fetch(model: SpeechModel, onProgress: (Long, Long) -> Unit): File {
        var before = 0L
        for (file in model.files) {
            val name = fileOf(model, file)
            val from = model.source ?: source
            fetchPinned(Pin(model.label, name, file.size, file.sha256, "$from/${file.name}")) { done, _ ->
                onProgress(before + done, model.size)
            }
            before += file.size
        }
        return path(model)
    }

    /**
     * A file Chaya fetches only as it pins it: its name in words, its file (under the models' folder), its size and
     * SHA-256, and where it comes from (null: [source] and the file's name).
     */
    internal class Pin(val label: String, val file: String, val size: Long, val sha256: String, val url: String? = null)

    internal suspend fun fetchPinned(model: Pin, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val whole = File(dir, model.file)
        if (whole.length() == model.size) return@withContext whole
        whole.parentFile?.mkdirs()
        val part = File(dir, "${model.file}.part")
        if (part.length() > model.size) part.delete()
        if (part.length() < model.size) {
            val from = part.length()
            val request = Request.Builder().url(model.url ?: "$source/${model.file}").apply { if (from > 0) header("Range", "bytes=$from-") }.build()
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
        model.files.forEach { File(dir, fileOf(model, it)).delete(); File(dir, "${fileOf(model, it)}.part").delete() }
        if (model.folder.isNotEmpty()) File(dir, model.folder).delete()
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
