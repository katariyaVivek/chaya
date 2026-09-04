package com.chaya.app.diagnostics

import android.net.Uri

/**
 * Privacy-scrubbed diagnostics events for the on-device log. Raw URLs never
 * reach the log: [scrubbed] strips query parameters and fragments (where
 * session tokens live) before recording.
 */
sealed class ChayaEvent {
    abstract val timestamp: Long

    /** Media surfaced to the user, by detection layer and MIME type. */
    data class MediaDetected(
        val url: String,
        val source: String,
        val mimeType: String?,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ChayaEvent()

    /** Every DownloadManager state flip, from the single choke points. */
    data class DownloadStateChanged(
        val taskId: Long,
        val from: String,
        val to: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ChayaEvent()

    /** Classified failure (kind + retryable), never the raw exception text. */
    data class DownloadFailed(
        val taskId: Long,
        val errorKind: String,
        val retryable: Boolean,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ChayaEvent()

    /** Top-level page load completed. */
    data class PageLoaded(
        val url: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ChayaEvent()

    /** Caught (non-fatal) exception with its tag. */
    data class CaughtException(
        val tag: String,
        val message: String?,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : ChayaEvent()

    companion object {
        /** Strips query + fragment; keeps scheme/host/path for debugging. */
        fun scrubbed(rawUrl: String?): String? {
            if (rawUrl.isNullOrBlank()) return null
            return try {
                val uri = Uri.parse(rawUrl)
                uri.buildUpon().clearQuery().fragment(null).build().toString()
            } catch (_: Exception) {
                rawUrl.substringBefore('?').substringBefore('#')
            }
        }
    }
}
