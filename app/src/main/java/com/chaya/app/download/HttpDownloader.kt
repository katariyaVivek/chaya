package com.chaya.app.download

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
        .build()
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
        if (fromBytes > 0) {
            requestBuilder.header("Range", "bytes=$fromBytes-")
        }

        val call = client.newCall(requestBuilder.build())

        synchronized(activeCalls) {
            activeCalls[taskId]?.cancel()
            activeCalls[taskId] = call
        }

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                synchronized(activeCalls) { activeCalls.remove(taskId) }
                // Silent when cancelled — manager owns the state machine.
                if (call.isCanceled()) return
                onComplete(Result.failure(e))
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                synchronized(activeCalls) {
                    if (!call.isCanceled()) activeCalls.remove(taskId)
                }

                response.use { resp ->
                    if (call.isCanceled()) return

                    if (!resp.isSuccessful) {
                        onComplete(Result.failure(IOException("HTTP ${resp.code}: ${resp.message}")))
                        return
                    }

                    // Server ignored our Range header and returned the whole body —
                    // restart from zero instead of corrupting the partial file.
                    val resumed = resp.code == 206 && fromBytes > 0

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

                    val source = body.source()
                    val buffer = ByteArray(64 * 1024)
                    var lastReportBytes = downloaded
                    var lastReportTime = System.currentTimeMillis()

                    try {
                        FileOutputStream(saveFile, append).use { stream ->
                            while (true) {
                                if (call.isCanceled()) return  // silent cancel
                                val read = source.read(buffer)
                                if (read == -1) break
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
                        if (!call.isCanceled()) {
                            onComplete(Result.failure(e))
                        }
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

    companion object {
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
