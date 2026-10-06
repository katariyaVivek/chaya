package com.chaya.app.detection

import com.chaya.app.model.MediaKind
import java.net.URLDecoder
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Turns detected media into names a person recognizes: the page's own title
 * for the main video, element captions or readable filenames for the rest,
 * and never a hash like `193039199_mp4_h264_aac_hd_7`.
 */
object MediaNamer {

    /** Separators between a content title and a site name, e.g. "Title - YouTube". */
    private val titleSeparator = Regex("\\s+[|\\-–—·•:]\\s+")

    /** Splits filename words on separators and camelCase boundaries. */
    private val wordBoundary = Regex("[\\s_.+\\-]+|(?<=[a-z])(?=[A-Z])")

    /** Filename tokens that describe encoding or packaging rather than content. */
    private val technicalTokens = setOf(
        "mp4", "m4v", "webm", "mov", "mkv", "m3u8", "mpd", "ts", "m4a", "mp3", "aac", "opus",
        "ogg", "flac", "wav", "h264", "h265", "hevc", "avc", "avc1", "vp9", "av1", "hd", "sd",
        "fhd", "uhd", "hls", "dash", "cmaf", "index", "master", "playlist", "manifest",
        "chunklist", "video", "audio", "stream", "media", "file", "default", "main", "output",
        "source", "src", "prog", "progressive", "high", "low", "med", "medium", "mobile",
        "desktop", "url", "dl", "download", "clip",
    )

    private const val MAX_FILE_BASE_CHARS = 80

    /** Title for the page's main item: declared metadata first, then captions, then the page title. */
    fun primaryTitle(
        url: String,
        player: PlayerMeta?,
        pageMeta: PageMeta?,
        matchesPageVideo: Boolean,
        fallbackPageTitle: String?,
        pageUrl: String?,
    ): String {
        val site = pageMeta?.siteName
        val host = pageUrl?.let(MediaUrlClassifier::hostOf)
        // A bare media document (the page *is* the file) has only the filename as its title.
        val isMediaDocument = pageUrl != null &&
            MediaUrlClassifier.withoutRangeParams(pageUrl) == MediaUrlClassifier.withoutRangeParams(url)
        return listOfNotNull(
            if (isMediaDocument) humanFileName(url) else null,
            if (matchesPageVideo) pageMeta?.ldName else null,
            if (matchesPageVideo) pageMeta?.ogTitle?.let { cleanPageTitle(it, site, host) } else null,
            player?.title,
            pageMeta?.ogTitle?.let { cleanPageTitle(it, site, host) },
            (pageMeta?.title ?: fallbackPageTitle)?.let { cleanPageTitle(it, site, host) },
            humanFileName(url),
        ).firstOrNull { it.isNotBlank() }
            ?: host?.let { "Video from ${it.removePrefix("www.")}" }
            ?: "Video"
    }

    /** Title for a secondary item: its caption, a readable filename, or "Video 2". */
    fun otherTitle(url: String, player: PlayerMeta?, kind: MediaKind, ordinal: Int): String =
        player?.title?.takeIf { it.isNotBlank() }
            ?: humanFileName(url)
            ?: "${kindNoun(kind)} $ordinal"

    /** Facts a person cares about, e.g. "1:42 · 720p · MP4" or "Ad · 0:15 · doubleclick.net". */
    fun subtitle(
        url: String,
        kind: MediaKind,
        isAd: Boolean,
        durationSeconds: Double?,
        videoHeight: Int?,
        mimeType: String?,
    ): String {
        val parts = buildList {
            if (isAd) add("Ad")
            durationSeconds?.let { add(formatDuration(it)) }
            if (kind != MediaKind.AUDIO && videoHeight != null && videoHeight >= 144) add("${videoHeight}p")
            add(formatLabel(url, kind, mimeType))
            if (isAd) MediaUrlClassifier.hostOf(url)?.let { add(it.removePrefix("www.")) }
        }
        return parts.joinToString(" · ")
    }

    /**
     * Strips a trailing or leading site name from a page title, e.g.
     * "Big Buck Bunny - YouTube" -> "Big Buck Bunny". Returns null when
     * nothing but the site name remains.
     */
    fun cleanPageTitle(title: String, siteName: String?, pageHost: String?): String? {
        val collapsed = title.replace(Regex("\\s+"), " ").trim()
        if (collapsed.isEmpty()) return null
        val siteKeys = listOfNotNull(siteName?.let(::comparable), pageHost?.let(::brandOf))
            .filter { it.length >= 3 }
        val parts = collapsed.split(titleSeparator).filter { it.isNotBlank() }
        if (siteKeys.isEmpty()) return collapsed
        if (parts.size == 1) return collapsed.takeUnless { isSiteName(it, siteKeys) }
        val kept = parts.filterNot { isSiteName(it, siteKeys) }
        return kept.joinToString(" - ").ifEmpty { null }
    }

    /**
     * A filename worth showing ("Big Buck Bunny", "SoundHelix Song 1"), or null
     * when the name is a hash, an id, or packaging jargon.
     */
    fun humanFileName(url: String): String? {
        val raw = MediaUrlClassifier.fileNameOf(url)
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        val base = decoded.substringBeforeLast('.').trim()
        if (base.isEmpty()) return null

        val tokens = base.split(wordBoundary).filter { it.isNotEmpty() }
        if (tokens.any { it.length >= 8 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } && it.any(Char::isDigit) }) {
            return null
        }
        val letters = base.count(Char::isLetter)
        val digits = base.count(Char::isDigit)
        if (letters == 0 || digits.toDouble() / (letters + digits) >= 0.35) return null
        val humanWords = tokens.count { token ->
            token.length >= 3 &&
                token.all(Char::isLetter) &&
                token.any { it.lowercaseChar() in "aeiouy" } &&
                token.lowercase(Locale.ROOT) !in technicalTokens
        }
        if (humanWords == 0) return null
        return tokens.joinToString(" ")
    }

    /** Base filename (no extension) for a saved download, e.g. "Big Buck Bunny (720p)". */
    fun fileBaseName(title: String, videoHeight: Int?): String {
        val clean = title.replace(Regex("[\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim()
        val quality = videoHeight?.takeIf { it >= 144 }?.let { " (${it}p)" }.orEmpty()
        return clean.take(MAX_FILE_BASE_CHARS - quality.length).trimEnd() + quality
    }

    /** `0:15`, `1:42`, `1:02:03`. */
    fun formatDuration(seconds: Double): String {
        val total = seconds.roundToLong().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
    }

    private fun formatLabel(url: String, kind: MediaKind, mimeType: String?): String {
        if (kind == MediaKind.STREAM) return "Stream"
        val ext = MediaUrlClassifier.extensionOf(url)
        return when {
            ext == "webm" -> "WebM"
            ext in setOf("mp4", "m4v", "mov", "mkv", "avi", "3gp", "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus") ->
                ext.uppercase(Locale.ROOT)
            mimeType?.startsWith("audio/") == true -> "Audio"
            else -> "Video"
        }
    }

    private fun kindNoun(kind: MediaKind): String = when (kind) {
        MediaKind.AUDIO -> "Audio"
        MediaKind.STREAM -> "Stream"
        else -> "Video"
    }

    /**
     * "NDTV News" is the site for key "ndtv"; a short content word such as
     * "News" is not, even though the site name contains it.
     */
    private fun isSiteName(part: String, siteKeys: List<String>): Boolean {
        val key = comparable(part)
        if (key.isEmpty()) return false
        return siteKeys.any { site ->
            key == site || key.contains(site) || (site.contains(key) && key.length >= site.length * 0.6)
        }
    }

    /** Letters and digits only, lowercase: "YouTube" and "you-tube" compare equal. */
    private fun comparable(text: String): String =
        text.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    /** "www.youtube.com" -> "youtube", "m.dailymotion.com" -> "dailymotion". */
    private fun brandOf(host: String): String {
        val labels = host.lowercase(Locale.ROOT).split('.').filter { it.isNotEmpty() }
        if (labels.size < 2) return comparable(host)
        val secondLevel = labels[labels.size - 2]
        val brand = if (secondLevel.length <= 3 && labels.size >= 3) labels[labels.size - 3] else secondLevel
        return comparable(brand)
    }
}
