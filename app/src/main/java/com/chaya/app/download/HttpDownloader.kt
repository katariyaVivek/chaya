package com.chaya.app.download

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Downloads a single file over HTTP with progress reporting, resume support
 * (`Range` header) and cancellation.
 *
 * Cancellation is *silent*: [cancel] stops the transfer without invoking any
 * callback, so [DownloadManager] stays the single owner of state transitions.
 */
class HttpDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
    /** How many bytes to ask for per request, or null for the whole file in one request. */
    private val chunkBytesFor: (url: String) -> Long? = ::youTubeChunkBytes,
) : MediaDownloader {
    private val activeCalls = mutableMapOf<Long, okhttp3.Call>()

    /**
     * Start downloading [url] into [saveFile].
     *
     * @param fromBytes resume offset; a partial file must already exist.
     * @param onMeta invoked once per response with the server-suggested
     *   filename from `Content-Disposition` (null if absent), BEFORE any byte
     *   is written — safe to rename [saveFile] inside this callback.
     * @param onProgress throttled progress callback.
     * @param onComplete terminal result. Never called after [cancel].
     */
    override fun start(
        taskId: Long,
        url: String,
        saveFile: File,
        userAgent: String?,
        cookies: String?,
        referer: String?,
        fromBytes: Long,
        onMeta: ((suggestedName: String?) -> Unit)?,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onComplete: (Result<File>) -> Unit
    ) {
        saveFile.parentFile?.mkdirs()

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")

        userAgent?.let { requestBuilder.header("User-Agent", it) }
        cookies?.let { requestBuilder.header("Cookie", it) }
        if (!referer.isNullOrBlank()) {
            requestBuilder.header("Referer", referer)
        }
        val chunkBytes = chunkBytesFor(url)
        when {
            chunkBytes != null -> requestBuilder.header("Range", rangeFrom(fromBytes, chunkBytes, total = null))
            fromBytes > 0 -> requestBuilder.header("Range", "bytes=$fromBytes-")
        }

        val call = client.newCall(requestBuilder.build())
        // The call fetching right now: a download fetched in pieces moves on to a new call for each one.
        var current = call

        synchronized(activeCalls) {
            activeCalls[taskId]?.cancel()
            activeCalls[taskId] = call
        }

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                release(taskId, call)
                // Silent when cancelled — manager owns the state machine.
                if (call.isCanceled()) return
                onComplete(Result.failure(e))
            }

            // The call stays registered until its body is fully read: it used to be dropped as soon as
            // the headers arrived, so a pause, cancel or delete during the transfer found nothing to
            // cancel and the body kept streaming into the file, then reported success.
            override fun onResponse(call: okhttp3.Call, response: Response) = try {
                transfer(call, response)
            } finally {
                release(taskId, current)
            }

            private fun transfer(call: okhttp3.Call, response: Response) {
                response.use { resp ->
                    if (call.isCanceled()) return

                    if (!resp.isSuccessful) {
                        onComplete(Result.failure(IOException("HTTP ${resp.code}: ${resp.message}")))
                        return
                    }

                    // Server ignored our Range header and returned the whole body —
                    // restart from zero instead of corrupting the partial file.
                    val resumed = resp.code == 206 && fromBytes > 0
                    // Fetching in pieces only makes sense once the server has shown it honours ranges.
                    val pieceBytes = chunkBytes.takeIf { resp.code == 206 }

                    val body = resp.body ?: run {
                        onComplete(Result.failure(IOException("Empty response body")))
                        return
                    }

                    val totalBytes: Long? = when {
                        resp.code == 206 -> {
                            val range = resp.header("Content-Range") ?: ""
                            val idx = range.lastIndexOf('/')
                            if (idx >= 0) range.substring(idx + 1).toLongOrNull()?.takeIf { it > 0 }
                            else null
                        }
                        else -> body.contentLength().takeIf { it > 0 }
                    }

                    // Report server-suggested name before first write so callers can rename.
                    onMeta?.invoke(parseContentDisposition(resp.header("Content-Disposition")))

                    val append = resumed
                    var downloaded = if (resumed) fromBytes else 0L

                    val buffer = ByteArray(64 * 1024)
                    var lastReportBytes = downloaded
                    var lastReportTime = System.currentTimeMillis()
                    var piece = resp
                    var source = body.source()

                    try {
                        FileOutputStream(saveFile, append).use { stream ->
                            while (true) {
                                if (current.isCanceled()) return  // silent cancel
                                val read = source.read(buffer)
                                if (read == -1) {
                                    val total = totalBytes
                                    if (pieceBytes == null || total == null || downloaded >= total) break
                                    // This piece is done; ask for the next one on a call cancel can reach.
                                    if (piece !== resp) piece.close()
                                    val next = client.newCall(
                                        requestBuilder.header("Range", rangeFrom(downloaded, pieceBytes, total)).build()
                                    )
                                    if (!replace(taskId, current, next)) return  // cancelled between pieces
                                    current = next
                                    piece = next.execute()
                                    if (current.isCanceled()) return
                                    if (piece.code != 206) {
                                        throw IOException("HTTP ${piece.code}: ${piece.message} for bytes $downloaded-")
                                    }
                                    source = (piece.body ?: throw IOException("Empty response body")).source()
                                    continue
                                }
                                stream.write(buffer, 0, read)
                                downloaded += read

                                val now = System.currentTimeMillis()
                                if (downloaded - lastReportBytes >= 64 * 1024 ||
                                    now - lastReportTime >= 300
                                ) {
                                    lastReportBytes = downloaded
                                    lastReportTime = now
                                    onProgress(downloaded, totalBytes)
                                }
                            }
                        }
                        onProgress(downloaded, totalBytes)
                        onComplete(Result.success(saveFile))
                    } catch (e: Exception) {
                        if (!current.isCanceled()) {
                            onComplete(Result.failure(e))
                        }
                    } finally {
                        if (piece !== resp) piece.close()
                    }
                }
            }
        })
    }

    /** Silently stop the transfer for [taskId]. Partial file is preserved. */
    override fun cancel(taskId: Long) {
        synchronized(activeCalls) {
            activeCalls.remove(taskId)?.cancel()
        }
    }

    fun cancelAll() {
        synchronized(activeCalls) {
            activeCalls.values.forEach { it.cancel() }
            activeCalls.clear()
        }
    }

    /**
     * Registers [next] in place of [previous] for [taskId], so cancel stops the piece being fetched now.
     * False when [previous] is no longer registered: the download was cancelled, or a resume replaced it.
     */
    private fun replace(taskId: Long, previous: okhttp3.Call, next: okhttp3.Call): Boolean =
        synchronized(activeCalls) {
            if (activeCalls[taskId] !== previous || previous.isCanceled()) return false
            activeCalls[taskId] = next
            true
        }

    /** Forgets [call] once it has ended, unless a resume has already registered a newer call for [taskId]. */
    private fun release(taskId: Long, call: okhttp3.Call) {
        synchronized(activeCalls) {
            if (activeCalls[taskId] === call) activeCalls.remove(taskId)
        }
    }

    companion object {
        /** yt-dlp's piece size for YouTube, which serves one long request at about the speed of playback. */
        const val YOUTUBE_CHUNK_BYTES = 10L * 1024 * 1024

        /**
         * YouTube's files ([url] on googlevideo.com) are fetched in pieces, as yt-dlp does; anything else in
         * one request. Decided from the address, so a download resumed after the app was closed keeps it.
         */
        fun youTubeChunkBytes(url: String): Long? {
            val host = url.toHttpUrlOrNull()?.host ?: return null
            return YOUTUBE_CHUNK_BYTES.takeIf { host == "googlevideo.com" || host.endsWith(".googlevideo.com") }
        }

        /** The Range header for the piece starting at [from]: [size] bytes, or what is left of [total]. */
        internal fun rangeFrom(from: Long, size: Long, total: Long?): String {
            val end = from + size - 1
            return "bytes=$from-${if (total != null) minOf(end, total - 1) else end}"
        }

        /** Extract filename from `Content-Disposition`; supports RFC 5987 `filename*`. */
        fun parseContentDisposition(header: String?): String? {
            if (header.isNullOrBlank()) return null
            Regex("filename\\*=(?:UTF-8|utf-8)''([^;]+)").find(header)
                ?.groupValues?.get(1)?.let { encoded ->
                    return runCatching { URLDecoder.decode(encoded.trim(), "UTF-8") }
                        .getOrNull()?.takeIf { it.isNotEmpty() }
                }
            return Regex("filename\\s*=\\s*\"?([^\";]+)\"?").find(header)
                ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}
