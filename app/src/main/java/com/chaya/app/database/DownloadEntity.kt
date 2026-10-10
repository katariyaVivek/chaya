package com.chaya.app.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.chaya.app.download.DownloadError
import com.chaya.app.download.DownloadState
import com.chaya.app.download.DownloadTask
import com.chaya.app.download.HeaderCodec

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
    val updatedAt: Long = System.currentTimeMillis(),
    /** Display title without quality or extension (v4+). */
    val title: String? = null,
    @ColumnInfo(name = "thumbnail_url")
    val thumbnailUrl: String? = null,
    @ColumnInfo(name = "quality_height")
    val qualityHeight: Int? = null,
    /** Headers the engine says [url] needs, as "Name: value" lines (v5+). */
    @ColumnInfo(name = "request_headers")
    val requestHeaders: String? = null,
    /** Separate sound file joined to the picture at [url] when both have arrived (v5+). */
    @ColumnInfo(name = "audio_url")
    val audioUrl: String? = null,
    @ColumnInfo(name = "audio_request_headers")
    val audioRequestHeaders: String? = null,
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
        updatedAt = updatedAt,
        title = title,
        thumbnailUrl = thumbnailUrl,
        qualityHeight = qualityHeight,
        requestHeaders = HeaderCodec.decode(requestHeaders),
        audioUrl = audioUrl,
        audioRequestHeaders = HeaderCodec.decode(audioRequestHeaders),
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
                "COMBINE" -> DownloadError.CouldNotCombine(RuntimeException(legacyMessage ?: "combine"))
                "CANCELLED" -> DownloadError.Cancelled
                "LISTING" -> DownloadError.Listing(legacyMessage ?: "Couldn't list what to save", canRetry = code != 0)
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
            is DownloadError.CouldNotCombine -> Triple("COMBINE", null, error.userMessage)
            DownloadError.Cancelled -> Triple("CANCELLED", null, error.userMessage)
            // The code column, otherwise unused here, keeps whether retrying could help.
            is DownloadError.Listing -> Triple("LISTING", if (error.retryable) 1 else 0, error.userMessage)
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
            updatedAt = task.updatedAt,
            title = task.title,
            thumbnailUrl = task.thumbnailUrl,
            qualityHeight = task.qualityHeight,
            requestHeaders = HeaderCodec.encode(task.requestHeaders),
            audioUrl = task.audioUrl,
            audioRequestHeaders = HeaderCodec.encode(task.audioRequestHeaders),
            )
        }
    }
}
