package com.chaya.app.model

enum class DetectionSource {
    NETWORK,
    DOM,
    XHR_FETCH,
    MANIFEST
}

data class DetectedMedia(
    val url: String,
    val pageUrl: String?,
    val mimeType: String?,
    val contentLength: Long? = null,
    val source: DetectionSource,
    val detectedAt: Long = System.currentTimeMillis()
) {
    /** Normalized URL for deduplication — strip fragment, sort query params. */
    val normalizedUrl: String
        get() = url.substringBefore("#")
}
