package com.chaya.app.detection

import com.chaya.app.model.MediaKind
import java.util.Locale

/**
 * Classifies media URLs before they reach the sheet so stream pieces never
 * appear as downloadable files and byte-range requests collapse into one item.
 *
 * Mirrors `isSegment` in `chaya_media_detector.js`; this side is authoritative.
 */
object MediaUrlClassifier {

    /** Extensions that are always pieces of an adaptive stream, never whole files. */
    private val segmentExtensions = setOf("ts", "m4s", "cmfv", "cmfa", "m4f")

    /** Named stream pieces such as `seg-12`, `segment_3`, `chunk-0001`, `frag12`. */
    private val segmentNamePattern = Regex(
        "(^|[-_.])(seg|segment|chunk|frag|fragment)[-_]?\\d+",
        RegexOption.IGNORE_CASE,
    )

    /** Initialization pieces such as `init.mp4`, `init-stream0.m4s`, `video_init.mp4`. */
    private val initSegmentPattern = Regex("(^|[-_.])init([-_.]|$)", RegexOption.IGNORE_CASE)

    /** Query parameters players use to fetch byte ranges of one file. */
    private val rangeParams = setOf("bytestart", "byteend", "range", "bytes")

    /** Values of [rangeParams] that are byte offsets or `start-end` spans. */
    private val rangeValue = Regex("\\d+(-\\d*)?")

    private val streamExtensions = setOf("m3u8", "mpd")
    private val audioExtensions = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "mka")
    private val manifestMimeTypes = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "application/dash+xml",
    )

    /** Decides how the sheet treats a URL, preferring the extension and falling back to the MIME hint. */
    fun kindOf(url: String, mimeType: String?): MediaKind {
        if (isSegment(url)) return MediaKind.SEGMENT
        val ext = extensionOf(url)
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        return when {
            ext in streamExtensions || mime in manifestMimeTypes -> MediaKind.STREAM
            ext in audioExtensions || mime?.startsWith("audio/") == true -> MediaKind.AUDIO
            else -> MediaKind.VIDEO
        }
    }

    /** True for chunks of an HLS/DASH stream, which are useless on their own. */
    fun isSegment(url: String): Boolean {
        val name = fileNameOf(url)
        if (name.isEmpty()) return false
        if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) in segmentExtensions) return true
        return segmentNamePattern.containsMatchIn(name) || initSegmentPattern.containsMatchIn(name)
    }

    /**
     * Drops the fragment and byte-range query parameters so every range request
     * of one file maps to the whole file, which is what the user downloads.
     */
    fun withoutRangeParams(url: String): String {
        val base = url.substringBefore('#')
        val queryStart = base.indexOf('?')
        if (queryStart < 0) return base
        val kept = base.substring(queryStart + 1)
            .split('&')
            .filter { param ->
                if (param.isEmpty()) return@filter false
                val name = param.substringBefore('=').lowercase(Locale.ROOT)
                val value = param.substringAfter('=', "")
                !(name in rangeParams && rangeValue.matches(value))
            }
        val path = base.substring(0, queryStart)
        return if (kept.isEmpty()) path else "$path?${kept.joinToString("&")}"
    }

    /** Lowercase extension of the URL path, ignoring query and fragment. */
    fun extensionOf(url: String): String =
        fileNameOf(url).substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** Last path segment of the URL, ignoring query and fragment. */
    fun fileNameOf(url: String): String =
        pathOf(url).substringAfterLast('/')

    /** Lowercase host of an http(s) URL, or null when there is none. */
    fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase(Locale.ROOT)
        return host.ifEmpty { null }
    }

    /** Path of the URL without scheme, host, query, or fragment; `/` when empty. */
    fun pathOf(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val pathStart = afterScheme.indexOf('/')
        if (pathStart < 0) return "/"
        return afterScheme.substring(pathStart).substringBefore('?').substringBefore('#')
    }
}
