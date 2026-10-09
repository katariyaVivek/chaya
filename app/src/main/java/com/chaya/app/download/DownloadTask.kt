package com.chaya.app.download

data class DownloadTask(
    val id: Long,
    val url: String,
    val pageUrl: String?,
    val fileName: String,
    val mimeType: String?,
    val filePath: String? = null,
    /** MediaStore URI of the copy exported to public storage (API 29+). */
    val exportedUri: String? = null,
    /** Classified failure shown on the downloads screen; persisted as the encoded form. */
    val error: DownloadError? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val state: DownloadState = DownloadState.QUEUED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Display title (no quality or extension); null for downloads made before v4. */
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val qualityHeight: Int? = null,
    /** Headers the engine says [url] needs; empty for downloads started from the browser. */
    val requestHeaders: Map<String, String> = emptyMap(),
    /** A separate sound file joined to the picture at [url] when both have arrived; null for ordinary downloads. */
    val audioUrl: String? = null,
    val audioRequestHeaders: Map<String, String> = emptyMap(),
) {
    val progressFraction: Float
        get() = if (totalBytes != null && totalBytes > 0) {
            downloadedBytes.toFloat() / totalBytes
        } else 0f

    /** Started from engine results, so it brings its own headers instead of this browser's session. */
    val hasOwnRequest: Boolean
        get() = audioUrl != null || requestHeaders.isNotEmpty()
}
