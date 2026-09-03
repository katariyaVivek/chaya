package com.chaya.app.download

import java.io.File

/**
 * The single-file transfer seam [DownloadManager] drives for HTTP downloads.
 *
 * [HttpDownloader] is a concrete OkHttp implementation with real network
 * semantics; depending on this minimal interface instead of the class lets
 * DownloadManager tests drive the full state machine (start, pause, resume,
 * complete, fail) deterministically without sockets, while production keeps
 * the real implementation through the default constructor parameter.
 */
interface MediaDownloader {
    /**
     * Start downloading [url] into [saveFile], resuming from [fromBytes]
     * when a partial file already exists. Default values live here (not in
     * overrides) so every implementation inherits them.
     */
    fun start(
        taskId: Long,
        url: String,
        saveFile: File,
        userAgent: String?,
        cookies: String?,
        referer: String? = null,
        fromBytes: Long = 0,
        onMeta: ((suggestedName: String?) -> Unit)? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onComplete: (Result<File>) -> Unit
    )

    /**
     * Stop the transfer for [taskId] silently: no onComplete afterwards,
     * and the partial file is kept for a later resume.
     */
    fun cancel(taskId: Long)
}
