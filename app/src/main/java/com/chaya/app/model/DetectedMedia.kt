package com.chaya.app.model

enum class DetectionSource {
    NETWORK,
    DOM,
    XHR_FETCH,
    MANIFEST
}

/** What a detected URL is to the user: a whole file, a stream, or a piece of a stream. */
enum class MediaKind {
    VIDEO,
    AUDIO,
    STREAM,
    /** A chunk of an adaptive stream; counted but never offered as a download. */
    SEGMENT
}

data class DetectedMedia(
    val url: String,
    val pageUrl: String?,
    val mimeType: String?,
    val contentLength: Long? = null,
    val source: DetectionSource,
    val detectedAt: Long = System.currentTimeMillis(),
    /** Human name chosen in the media sheet; names the saved file instead of the URL's last segment. */
    val suggestedName: String? = null,
) {
    /** Normalized URL for deduplication — strip fragment, sort query params. */
    val normalizedUrl: String
        get() = url.substringBefore("#")
}
