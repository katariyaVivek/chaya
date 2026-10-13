package com.chaya.app.streaming

import java.util.Locale

/**
 * A stream's type as Media3 spells it. Media3 recognises HLS only by the exact string `application/x-mpegURL`
 * (MimeTypes.APPLICATION_M3U8); handed `application/vnd.apple.mpegurl`, the type detection gives for every `.m3u8`
 * address, it treats the address as one plain file: a "download" that saves the playlist itself, a quality list
 * with nothing in it, and a copy that plays as 0:00.
 */
object StreamMime {
    /** MimeTypes.APPLICATION_M3U8. */
    const val HLS = "application/x-mpegURL"

    /** MimeTypes.APPLICATION_MPD. */
    const val DASH = "application/dash+xml"

    /** [mimeType] in Media3's spelling when it names HLS or DASH; anything else as it is. */
    fun forMedia3(mimeType: String?): String? {
        val type = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return null
        return when {
            type.endsWith("mpegurl") -> HLS
            type == DASH -> DASH
            else -> mimeType
        }
    }
}
