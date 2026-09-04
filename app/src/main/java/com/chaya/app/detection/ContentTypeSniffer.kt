package com.chaya.app.detection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Keeps native verification bounded and redirect-free so page-scoped session
 * headers cannot be replayed to a different origin during media discovery.
 */
private val contentTypeClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

/**
 * Verifies that [url] advertises a directly downloadable media MIME type
 * without downloading its body or modifying the WebView's own request flow.
 */
suspend fun sniffContentType(
    url: String,
    headers: Map<String, String>,
    client: OkHttpClient = contentTypeClient,
): String? {
    val request = try {
        Request.Builder()
            .url(url)
            .head()
            .apply {
                headers.forEach { (name, value) ->
                    if (name.isNotBlank() && value.isNotBlank()) {
                        header(name, value)
                    }
                }
            }
            .build()
    } catch (_: IllegalArgumentException) {
        return null
    }

    return try {
        client.newCall(request).awaitMediaContentType()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: IOException) {
        null
    }
}

/** Bridges OkHttp's asynchronous response callback into a cancellable media-MIME lookup. */
private suspend fun Call.awaitMediaContentType(): String? = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) {
                continuation.resumeWithException(e)
            }
        }

        override fun onResponse(call: Call, response: Response) {
            val mediaContentType = response.use {
                if (it.isSuccessful) recognizedMediaContentType(it.header("Content-Type")) else null
            }
            if (continuation.isActive) {
                continuation.resume(mediaContentType)
            }
        }
    })
}

/**
 * Excludes generic API and binary responses while retaining direct audio,
 * video, and manifest types that the download pipeline knows how to route.
 */
private fun recognizedMediaContentType(contentType: String?): String? {
    val normalized = contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotEmpty() }
        ?: return null

    return normalized.takeIf { type ->
        type.startsWith("video/") ||
            type.startsWith("audio/") ||
            type in streamingManifestMimeTypes ||
            type == "application/ogg" ||
            type == "application/x-ogg"
    }
}

/**
 * Covers manifest server variants that cannot be identified by an audio or
 * video top-level MIME prefix alone.
 */
private val streamingManifestMimeTypes = setOf(
    "application/vnd.apple.mpegurl",
    "application/x-mpegurl",
    "application/dash+xml",
)
