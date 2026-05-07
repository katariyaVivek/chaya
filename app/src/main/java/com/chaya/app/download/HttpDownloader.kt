package com.chaya.app.download

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads a single file over HTTP with progress reporting, resume support,
 * and cancellation.
 */
class HttpDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    private val activeCalls = mutableMapOf<Long, okhttp3.Call>()

    /**
     * Start downloading [task]. Progress and completion are reported via
     * [onProgress] and [onComplete]. Returns the task id.
     */
    fun start(
        taskId: Long,
        url: String,
        saveFile: File,
        userAgent: String?,
        cookies: String?,
        fromBytes: Long = 0,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onComplete: (Result<File>) -> Unit
    ) {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")

        userAgent?.let { requestBuilder.header("User-Agent", it) }
        cookies?.let { requestBuilder.header("Cookie", it) }

        if (fromBytes > 0) {
            requestBuilder.header("Range", "bytes=$fromBytes-")
        }

        val request = requestBuilder.build()
        val call = client.newCall(request)

        synchronized(activeCalls) {
            activeCalls[taskId]?.cancel()
            activeCalls[taskId] = call
        }

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                synchronized(activeCalls) {
                    if (call.isCanceled()) return
                    activeCalls.remove(taskId)
                }
                onComplete(Result.failure(e))
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                synchronized(activeCalls) {
                    if (!call.isCanceled()) activeCalls.remove(taskId)
                }

                response.use { resp ->
                    if (!resp.isSuccessful && resp.code != 206) {
                        onComplete(Result.failure(
                            java.io.IOException("HTTP ${resp.code}: ${resp.message}")
                        ))
                        return
                    }

                    val body = resp.body ?: run {
                        onComplete(Result.failure(java.io.IOException("Empty response body")))
                        return
                    }

                    // Determine total bytes from Content-Range (resume) or Content-Length
                    val totalBytes = when {
                        resp.code == 206 -> {
                            val range = resp.header("Content-Range") ?: ""
                            val idx = range.lastIndexOf('/')
                            if (idx >= 0) range.substring(idx + 1).toLongOrNull() else null
                        }
                        else -> body.contentLength()
                    }

                    val source = body.source()
                    val output = FileOutputStream(saveFile, fromBytes > 0)
                    val buffer = ByteArray(8192)
                    var downloaded = fromBytes
                    var lastProgressReport = 0L

                    try {
                        output.use { stream ->
                            var bytesRead: Int
                            while (source.read(buffer).also { bytesRead = it } != -1) {
                                if (call.isCanceled()) {
                                    onComplete(Result.failure(
                                        java.io.IOException("Download cancelled")
                                    ))
                                    return
                                }
                                stream.write(buffer, 0, bytesRead)
                                downloaded += bytesRead

                                // Throttle progress reports to ~100ms intervals
                                if (downloaded - lastProgressReport > 65536) {
                                    lastProgressReport = downloaded
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

    fun cancel(taskId: Long) {
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
}
