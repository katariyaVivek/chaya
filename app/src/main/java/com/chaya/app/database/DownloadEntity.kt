package com.chaya.app.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.chaya.app.download.DownloadState
import com.chaya.app.download.DownloadTask

@Entity(tableName = "downloads")
data class DownloadEntity(
    /**
     * Explicit primary key assigned by DownloadManager (NOT auto-generated).
     * This guarantees the in-memory task id, the DB row id, and the id used
     * by downloader callbacks are always the same value.
     */
    @PrimaryKey
    val id: Long,
    val url: String,
    val pageUrl: String?,
    val fileName: String,
    val mimeType: String?,
    @ColumnInfo(name = "file_path")
    val filePath: String?,
    @ColumnInfo(name = "exported_uri")
    val exportedUri: String? = null,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "downloaded_bytes")
    val downloadedBytes: Long = 0,
    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long?,
    val state: DownloadState = DownloadState.QUEUED,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    val progressFraction: Float
        get() = if (totalBytes != null && totalBytes > 0) {
            downloadedBytes.toFloat() / totalBytes
        } else 0f

    fun toTask(): DownloadTask = DownloadTask(
        id = id,
        url = url,
        pageUrl = pageUrl,
        fileName = fileName,
        mimeType = mimeType,
        filePath = filePath,
        exportedUri = exportedUri,
        errorMessage = errorMessage,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        state = state,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromTask(task: DownloadTask): DownloadEntity = DownloadEntity(
            id = task.id,
            url = task.url,
            pageUrl = task.pageUrl,
            fileName = task.fileName,
            mimeType = task.mimeType,
            filePath = task.filePath,
            exportedUri = task.exportedUri,
            errorMessage = task.errorMessage,
            downloadedBytes = task.downloadedBytes,
            totalBytes = task.totalBytes,
            state = task.state,
            createdAt = task.createdAt,
            updatedAt = task.updatedAt
        )
    }
}
