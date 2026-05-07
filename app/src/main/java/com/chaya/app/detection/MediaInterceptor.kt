package com.chaya.app.detection

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.chaya.app.model.DetectedMedia
import com.chaya.app.model.DetectionSource
import java.util.Locale

/**
 * Intercepts WebView requests and detects media URLs flowing through.
 *
 * Uses [WebResourceRequest] inspection (URL extension + MIME type from headers)
 * to identify downloadable media before the page loads them.
 */
class MediaInterceptor(
    private val onMediaDetected: (DetectedMedia) -> Unit
) {
    /** Known media file extensions (lowercase, no dot). */
    private val mediaExtensions = setOf(
        "mp4", "webm", "mp3", "m4a", "aac",
        "m3u8", "mpd", "ts", "ogg", "wav", "flac", "wmv", "avi", "mkv"
    )

    /** MIME type prefixes that indicate media content. */
    private val mediaMimePrefixes = listOf("video/", "audio/")

    /** Exact MIME types for streaming manifests. */
    private val manifestMimeTypes = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegURL",
        "application/dash+xml"
    )

    /** Cache of normalized URLs already reported to avoid duplicates. */
    private val reportedUrls = mutableSetOf<String>()

    /**
     * Called from [WebViewClient.shouldInterceptRequest].
     * Returns null to let the WebView load normally — this is observer-only.
     */
    fun shouldInterceptRequest(
        request: WebResourceRequest,
        pageUrl: String?
    ): WebResourceResponse? {
        val url = request.url.toString()

        // Skip non-http schemes (data:, blob:, javascript:, etc.)
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null

        // Skip already-reported URLs
        val normalized = url.substringBefore("#")
        if (normalized in reportedUrls) return null

        val mimeType = request.requestHeaders["Accept"] ?: ""
        val detected = detect(request, pageUrl)

        if (detected != null) {
            reportedUrls.add(normalized)
            onMediaDetected(detected)
        }

        return null
    }

    fun clearReportedUrls() {
        reportedUrls.clear()
    }

    private fun detect(
        request: WebResourceRequest,
        pageUrl: String?
    ): DetectedMedia? {
        val url = request.url.toString()
        val path = request.url.path ?: return null
        val lowerPath = path.lowercase(Locale.ROOT)

        // Check by file extension
        val dotIndex = lowerPath.lastIndexOf('.')
        if (dotIndex >= 0) {
            val ext = lowerPath.substring(dotIndex + 1)
            if (ext in mediaExtensions) {
                return DetectedMedia(
                    url = url,
                    pageUrl = pageUrl,
                    mimeType = mimeTypeForExtension(ext),
                    contentLength = null,
                    source = DetectionSource.NETWORK
                )
            }
        }

        // Check by manifest MIME type in Accept header
        val acceptHeader = request.requestHeaders["Accept"] ?: ""
        if (acceptHeader in manifestMimeTypes) {
            return DetectedMedia(
                url = url,
                pageUrl = pageUrl,
                mimeType = acceptHeader,
                contentLength = null,
                source = DetectionSource.MANIFEST
            )
        }

        // Check by MIME prefix in Accept header
        if (mediaMimePrefixes.any { acceptHeader.startsWith(it) }) {
            return DetectedMedia(
                url = url,
                pageUrl = pageUrl,
                mimeType = acceptHeader,
                contentLength = null,
                source = DetectionSource.NETWORK
            )
        }

        return null
    }

    private fun mimeTypeForExtension(ext: String): String = when (ext) {
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "wmv" -> "video/x-ms-wmv"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "m3u8" -> "application/vnd.apple.mpegurl"
        "mpd" -> "application/dash+xml"
        "ts" -> "video/mp2t"
        else -> "application/octet-stream"
    }
}
