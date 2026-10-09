package com.chaya.app.platform

import com.chaya.app.download.DownloadRequest

/**
 * What the download manager needs to save this choice. A streaming manifest (HLS, DASH) is not a request:
 * those go through the stream downloader instead.
 */
fun PlatformChoice.toDownloadRequest(media: PlatformMedia, pageUrl: String? = null): DownloadRequest {
    require(file.isDirectFile) { "A streaming manifest is not a plain file" }
    val title = media.title.trim().ifEmpty { "Video" }
    val extension = savedExtension()
    // "Title (720p).mp4", "Title (audio).m4a"; a video with no picture size is just "Title.mp4".
    val tag = when {
        isAudioOnly -> "audio"
        quality == null -> null
        else -> label
    }
    return DownloadRequest(
        url = file.url,
        headers = file.headers,
        audioUrl = audioToMerge?.url,
        audioHeaders = audioToMerge?.headers.orEmpty(),
        pageUrl = pageUrl ?: media.pageUrl,
        fileName = if (tag == null) "$title.$extension" else "$title ($tag).$extension",
        mimeType = mimeTypeFor(extension, isAudioOnly),
        title = title,
        thumbnailUrl = media.thumbnailUrl,
        qualityHeight = quality,
        expectedBytes = sizeBytes,
    )
}

/** Joined tracks always become MP4; a ready-made file keeps the container it came in. */
internal fun PlatformChoice.savedExtension(): String {
    if (needsMerge) return "mp4"
    return file.ext?.lowercase()?.takeIf { it.isNotEmpty() } ?: if (isAudioOnly) "m4a" else "mp4"
}

internal fun mimeTypeFor(extension: String, audioOnly: Boolean): String = when (extension) {
    "m4a" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    "ogg", "opus" -> "audio/ogg"
    "webm" -> if (audioOnly) "audio/webm" else "video/webm"
    "mov" -> "video/quicktime"
    else -> if (audioOnly) "audio/mp4" else "video/mp4"
}
