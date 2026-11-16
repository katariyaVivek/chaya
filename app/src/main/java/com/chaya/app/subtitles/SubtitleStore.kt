package com.chaya.app.subtitles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** A video's subtitles as Chaya kept them: their language ("en") and lines, with which line shows when. */
class SavedSubtitles(val language: String, val lines: List<SubtitleLine>) {
    val spans: List<SubtitleSpan> = Subtitles.timeline(lines)

    /** The line shown at [atMs], by its index in [lines]; null between lines. */
    fun lineAt(atMs: Long): Int? = Subtitles.lineAt(spans, atMs)
}

/**
 * The subtitles saved with videos: fetched from the site when the download starts (its addresses expire), kept as SRT
 * in the app's own files ("12.en.srt", by download), read by the player, and deleted with the download. Nothing is
 * kept when the site's answer is not subtitles, and a fetch that fails never touches the download itself.
 */
class SubtitleStore(
    private val dir: File,
    private val client: OkHttpClient = defaultClient(),
) {
    /** Fetches [source] for download [id] and keeps it; whether anything was kept. Never throws. */
    suspend fun save(id: Long, source: SubtitleSource): Boolean = withContext(Dispatchers.IO) {
        runCatching { fetch(source) }.getOrNull()?.let { text ->
            val lines = Subtitles.parse(text)
            if (lines.isEmpty()) return@withContext false
            // The language was made safe for a file's name by Subtitles.fileLanguage; checked again all the same
            val language = Subtitles.fileLanguage(source.language)?.takeIf { it == source.language }
                ?: return@withContext false
            runCatching {
                dir.mkdirs()
                val file = File(dir, "$id.$language.srt")
                val partial = File(dir, "${file.name}.part")
                partial.writeText(Subtitles.toSrt(lines))
                if (!partial.renameTo(file)) {
                    partial.delete()
                    return@withContext false
                }
                true
            }.getOrDefault(false)
        } ?: false
    }

    private fun fetch(source: SubtitleSource): String? {
        val url = source.url.toHttpUrlOrNull() ?: return null
        val request = Request.Builder().url(url).apply {
            source.headers.forEach { (name, value) -> runCatching { header(name, value) } }
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            // A subtitles file is small; anything far bigger is not one
            if (body.contentLength() > MAX_BYTES) return null
            val source = body.source()
            // Reads until there is more than the most allowed, or the answer ends
            if (source.request(MAX_BYTES + 1)) return null
            return source.buffer.readUtf8()
        }
    }

    /** The subtitles kept for download [id], read; null when there are none. Reads a file, so not on the main thread. */
    fun load(id: Long): SavedSubtitles? {
        val file = fileFor(id) ?: return null
        val language = file.name.removePrefix("$id.").removeSuffix(".srt")
        val lines = runCatching { Subtitles.parse(file.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return SavedSubtitles(language, lines)
    }

    /** Whether download [id] has subtitles kept. */
    fun has(id: Long): Boolean = fileFor(id) != null

    /** A download deleted: its subtitles go with it. */
    fun forget(id: Long) {
        dir.listFiles { file -> file.name.startsWith("$id.") }?.forEach { it.delete() }
    }

    private fun fileFor(id: Long): File? =
        dir.listFiles { file -> file.name.startsWith("$id.") && file.name.endsWith(".srt") }?.firstOrNull()

    companion object {
        /** Far above any film's subtitles (a feature film's SRT is about 100 KB). */
        const val MAX_BYTES = 5L * 1024 * 1024

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()

        /**
         * The headers to send for subtitles at [subtitlesUrl], from those of the video's file at [fileUrl]: all of
         * them, but a cookie only to the same site, so a sign-in never goes to another host.
         */
        fun headersFor(subtitlesUrl: String, fileUrl: String, headers: Map<String, String>): Map<String, String> {
            val sameHost = subtitlesUrl.toHttpUrlOrNull()?.host?.let { it == fileUrl.toHttpUrlOrNull()?.host } == true
            return if (sameHost) headers else headers.filterKeys { !it.equals("Cookie", ignoreCase = true) }
        }
    }
}
