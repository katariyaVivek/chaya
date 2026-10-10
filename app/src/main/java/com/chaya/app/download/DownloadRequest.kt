package com.chaya.app.download

/**
 * A download whose address and headers the engine (yt-dlp) worked out, rather than ones seen in the browser.
 * Either one complete file, or a picture plus a separate sound that are joined into one MP4 when both have arrived.
 */
data class DownloadRequest(
    val url: String,
    /** Headers [url] needs, including any cookie for it. Empty means "use this browser's session". */
    val headers: Map<String, String> = emptyMap(),
    /** A separate sound file to join to the picture at [url]; null when [url] is already complete. */
    val audioUrl: String? = null,
    val audioHeaders: Map<String, String> = emptyMap(),
    val pageUrl: String? = null,
    /** The saved file's name including its extension, e.g. "Me at the zoo (240p).mp4". */
    val fileName: String,
    val mimeType: String?,
    /** Title for the downloads list, without quality or extension. */
    val title: String,
    val thumbnailUrl: String? = null,
    /** Picture height in pixels, shown as "240p". */
    val qualityHeight: Int? = null,
    /** Total size of everything to fetch, when known, so the progress bar can be honest. */
    val expectedBytes: Long? = null,
    /** Shared by the files saved from one post: the library shows them as one tile. */
    val groupKey: String? = null,
)
