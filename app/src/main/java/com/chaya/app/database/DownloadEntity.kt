package com.chaya.app.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.chaya.app.download.DownloadError
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
    /** Classified failure kind, e.g. NETWORK / HTTP_403 / STORAGE_FULL (v3+). */
    @ColumnInfo(name = "error_kind")
    val errorKind: String? = null,
    /** HTTP status for HTTP_* kinds, else null (v3+). */
    @ColumnInfo(name = "error_code")
    val errorCode: Int? = null,
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
        error = decodeError(errorKind, errorCode, errorMessage),
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        state = state,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        /** Rebuilds the classified error; pre-v3 rows carry only a legacy message. */
        fun decodeError(kind: String?, code: Int?, legacyMessage: String?): DownloadError? {
            if (kind == null) {
                // v2 row that failed before the taxonomy existed: keep the
                // message visible rather than dropping it, retry stays on.
                return legacyMessage?.takeIf { it.isNotBlank() }?.let {
                    DownloadError.Unknown(RuntimeException(it))
                }
            }
            return when (kind) {
                "NETWORK" -> DownloadError.Network(RuntimeException(legacyMessage ?: "network"))
                "HTTP" -> DownloadError.HttpStatus(code ?: 0)
                "STORAGE_FULL" -> DownloadError.StorageFull
                "UNSUPPORTED_FORMAT" -> DownloadError.UnsupportedFormat
                "CANCELLED" -> DownloadError.Cancelled
                else -> DownloadError.Unknown(RuntimeException(legacyMessage ?: "unknown"))
            }
        }

        /** Splits a classified error into persistable kind/code/message columns. */
        fun encodeError(error: DownloadError?): Triple<String?, Int?, String?> = when (error) {
            null -> Triple(null, null, null)
            is DownloadError.Network -> Triple("NETWORK", null, error.userMessage)
            is DownloadError.HttpStatus -> Triple("HTTP", error.code, error.userMessage)
            DownloadError.StorageFull -> Triple("STORAGE_FULL", null, error.userMessage)
            DownloadError.UnsupportedFormat -> Triple("UNSUPPORTED_FORMAT", null, error.userMessage)
            DownloadError.Cancelled -> Triple("CANCELLED", null, error.userMessage)
            is DownloadError.Unknown -> Triple("UNKNOWN", null, error.userMessage)
        }

        fun fromTask(task: DownloadTask): DownloadEntity {
            val (kind, code, message) = encodeError(task.error)
            return DownloadEntity(
            id = task.id,
            url = task.url,
            pageUrl = task.pageUrl,
            fileName = task.fileName,
            mimeType = task.mimeType,
            filePath = task.filePath,
            exportedUri = task.exportedUri,
            errorMessage = message,
            errorKind = kind,
            errorCode = code,
            downloadedBytes = task.downloadedBytes,
            totalBytes = task.totalBytes,
            state = task.state,
            createdAt = task.createdAt,
            updatedAt = task.updatedAt
            )
        }
    }
}
