package com.chaya.app.download

data class DownloadTask(
    val id: Long,
    val url: String,
    val pageUrl: String?,
    val fileName: String,
    val mimeType: String?,
    val filePath: String? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val state: DownloadState = DownloadState.QUEUED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val progressFraction: Float
        get() = if (totalBytes != null && totalBytes > 0) {
            downloadedBytes.toFloat() / totalBytes
        } else 0f
}
