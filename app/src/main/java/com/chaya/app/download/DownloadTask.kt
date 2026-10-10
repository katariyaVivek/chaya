package com.chaya.app.download

import com.chaya.app.streaming.StreamDownloader

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
    /** Shared by the files saved from one post, so the library shows them as one tile; null otherwise. */
    val groupKey: String? = null,
    /** For a ZIP archive of many files, how far it has got; not stored. */
    val archive: ArchiveProgress? = null,
    /** While a finished stream is being saved as an MP4 file, how far that has got (0 to 1); not stored. */
    val savingAsFile: Float? = null,
    /** What became of saving a stream as a file, when there is something to say (re-encoded, or why not); not stored. */
    val saveNote: String? = null,
) {
    val progressFraction: Float
        get() = if (totalBytes != null && totalBytes > 0) {
            downloadedBytes.toFloat() / totalBytes
        } else 0f

    /** A ZIP archive of many files (everything an account has posted), not one file. */
    val isArchive: Boolean get() = url.startsWith(ARCHIVE_SCHEME)

    /** An HLS or DASH stream, downloaded into Media3's cache rather than to a file. */
    val isStream: Boolean
        get() = StreamDownloader.isStreamingUrl(url) || StreamDownloader.isStreamingMime(mimeType)

    /** A finished stream that still lives only in the cache: it plays in the app and can be saved as an MP4 file. */
    val canSaveAsFile: Boolean
        get() = state == DownloadState.COMPLETED && isStream && filePath == null && exportedUri == null && savingAsFile == null

    /** Started from engine results, so it brings its own headers instead of this browser's session. */
    val hasOwnRequest: Boolean
        get() = audioUrl != null || requestHeaders.isNotEmpty()
}
