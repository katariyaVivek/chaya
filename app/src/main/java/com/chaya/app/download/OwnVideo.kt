package com.chaya.app.download

/**
 * A video (or sound) of the person's own, added to the Library from the phone's files rather than downloaded, as
 * Chaya for Windows lists a video dropped on its window (`desktop/core/src/own.rs`). It stays where it is: Chaya keeps
 * the document's address and the right to read it that the phone's picker gave, plays it, searches its lines and
 * makes clips of it as of a download, and Delete only takes it out of Chaya. Plain Kotlin, no Android.
 */
const val OWN_SCHEME = "chaya-own:"

/** One of the person's own files, added from the phone, not a download: Chaya never deletes it. */
val DownloadTask.isOwn: Boolean get() = url.startsWith(OWN_SCHEME)

/** The phone's address of an own file (a `content://` document), from the address Chaya keeps for it. */
val DownloadTask.ownUri: String? get() = if (isOwn) url.removePrefix(OWN_SCHEME) else null

object OwnVideos {
    /** Whether the picker's [mimeType] is something Chaya plays: a video or a sound. */
    fun plays(mimeType: String?): Boolean =
        mimeType != null && (mimeType.startsWith("video/") || mimeType.startsWith("audio/"))

    /** The title the Library shows for a file named [name]: its name without the extension ("Talk.mp4" is "Talk"). */
    fun titleOf(name: String): String {
        val trimmed = name.trim()
        val dot = trimmed.lastIndexOf('.')
        val title = if (dot > 0) trimmed.substring(0, dot) else trimmed
        return title.ifBlank { "Video" }
    }

    /**
     * The finished entry for the file at [uri] (a document the picker gave), named [name], of [mimeType] and
     * [sizeBytes] when the phone says: done, with nothing to fetch, its address the document's.
     */
    fun task(id: Long, uri: String, name: String, mimeType: String?, sizeBytes: Long?, now: Long): DownloadTask =
        DownloadTask(
            id = id,
            url = OWN_SCHEME + uri,
            pageUrl = null,
            fileName = name.trim().ifBlank { "Video" },
            mimeType = mimeType,
            exportedUri = uri,
            downloadedBytes = sizeBytes ?: 0,
            totalBytes = sizeBytes,
            state = DownloadState.COMPLETED,
            createdAt = now,
            updatedAt = now,
            title = titleOf(name),
        )
}
