package com.chaya.app.detection

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.chaya.app.model.DetectedMedia
import com.chaya.app.model.DetectionSource
import java.util.Locale

/**
 * Intercepts WebView requests and detects media URLs flowing through.
 *
 * Observer-only: returns null so the WebView loads normally.
 *
 * Detection is extension-based, plus an exact-match check for streaming
 * manifest MIME types that some players put in the Accept header.
 */
class MediaInterceptor(
    private val onMediaDetected: (DetectedMedia) -> Unit
) {
    /** Known media file extensions (lowercase, no dot). */
    private val mediaExtensions = setOf(
        "mp4", "m4v", "webm", "mov", "mkv", "avi", "wmv", "3gp",
        "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "mka",
        "m3u8", "mpd", "ts"
    )

    /** Exact MIME types for streaming manifests. */
    private val manifestMimeTypes = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "application/dash+xml"
    )

    /** Cache of normalized URLs already reported to avoid duplicates. */
    private val reportedUrls = mutableSetOf<String>()

    /**
     * Called from [android.webkit.WebViewClient.shouldInterceptRequest].
     * Returns null to let the WebView load normally — this is observer-only.
     */
    fun shouldInterceptRequest(
        request: WebResourceRequest,
        pageUrl: String?
    ): WebResourceResponse? {
        val url = request.url.toString()

        // Skip non-http schemes (data:, blob:, javascript:, etc.)
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null

        val normalized = url.substringBefore("#")
        if (normalized in reportedUrls) return null

        detect(request, pageUrl)?.let { detected ->
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
                    source = DetectionSource.NETWORK
                )
            }
        }

        // Some players request manifests with a specific Accept header.
        val acceptHeader = request.requestHeaders["Accept"] ?: ""
        if (acceptHeader.lowercase(Locale.ROOT) in manifestMimeTypes.map { it.lowercase(Locale.ROOT) }) {
            return DetectedMedia(
                url = url,
                pageUrl = pageUrl,
                mimeType = acceptHeader,
                source = DetectionSource.MANIFEST
            )
        }

        return null
    }

    private fun mimeTypeForExtension(ext: String): String = when (ext) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "wmv" -> "video/x-ms-wmv"
        "3gp" -> "video/3gpp"
        "ts" -> "video/mp2t"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "mka" -> "audio/x-matroska"
        "m3u8" -> "application/vnd.apple.mpegurl"
        "mpd" -> "application/dash+xml"
        else -> "application/octet-stream"
    }
}
