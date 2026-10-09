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
    /** Title shown in the downloads list, without quality or extension, e.g. "Big Buck Bunny". */
    val title: String? = null,
    /** Poster or page image for the downloads list; loaded anonymously when displayed. */
    val thumbnailUrl: String? = null,
    /** Chosen video height in pixels (e.g. 720), shown as "720p" in the downloads list. */
    val qualityHeight: Int? = null,
) {
    /** Normalized URL for deduplication — strip fragment, sort query params. */
    val normalizedUrl: String
        get() = url.substringBefore("#")
}
