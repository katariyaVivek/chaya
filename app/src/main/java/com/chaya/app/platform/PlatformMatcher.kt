package com.chaya.app.platform

import java.net.URI
import java.util.Locale

/** The sites chaya can fetch a video from when given a link to it. */
enum class Platform(val displayName: String) {
    YOUTUBE("YouTube"),
    INSTAGRAM("Instagram"),
    TIKTOK("TikTok"),
    TWITTER("X"),
    PINTEREST("Pinterest"),
}

/** [url] is the link as given; [id] identifies the video within [platform]. */
data class PlatformMatch(val platform: Platform, val id: String, val url: String) {
    /**
     * What the lookup is finding, for "Finding the video…": an Instagram post or a tweet may hold only pictures,
     * so for those it is the post, and a Pinterest pin is a pin; a reel, a YouTube or a TikTok link is a video.
     */
    val lookingFor: String
        get() = when {
            platform == Platform.TWITTER -> "post"
            platform == Platform.PINTEREST -> "pin"
            platform == Platform.INSTAGRAM && id.substringBefore('/') == "p" -> "post"
            else -> "video"
        }
}

/**
 * Recognizes links to a single video on a supported site. Ported from sancika's matcher, so the two
 * apps agree on which links count. Profile pages, search pages and other sites return null.
 */
object PlatformMatcher {
    private val youtubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
    private val tiktokHosts = setOf("tiktok.com", "www.tiktok.com", "m.tiktok.com", "vm.tiktok.com", "vt.tiktok.com")
    private val instagramHosts = setOf("instagram.com", "www.instagram.com", "m.instagram.com")
    private val twitterHosts = setOf("twitter.com", "www.twitter.com", "mobile.twitter.com", "x.com", "www.x.com", "t.co")

    /** pinterest.com, its country sites (pinterest.co.uk, de.pinterest.com...), and pin.it short links. */
    private val pinterestHost = Regex("""(?:[a-z0-9-]+\.)*pinterest\.(?:com|[a-z]{2}|co\.[a-z]{2}|com\.[a-z]{2})|pin\.it""")

    fun match(input: String): PlatformMatch? {
        val url = input.trim()
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null

        return when {
            host in youtubeHosts || host.endsWith(".youtube.com") || host == "youtu.be" -> matchYouTube(uri, host, url)
            host in tiktokHosts -> matchTikTok(uri, host, url)
            host in instagramHosts -> matchInstagram(uri, url)
            host in twitterHosts -> matchTwitter(uri, host, url)
            pinterestHost.matches(host) -> matchPinterest(uri, host, url)
            else -> null
        }
    }

    private fun matchYouTube(uri: URI, host: String, url: String): PlatformMatch? {
        if (host == "youtu.be") {
            return segments(uri).firstOrNull()?.let { PlatformMatch(Platform.YOUTUBE, it, url) }
        }
        queryParam(uri, "v")?.let { return PlatformMatch(Platform.YOUTUBE, it, url) }
        val parts = segments(uri)
        if (parts.size >= 2 && parts[0] in setOf("shorts", "embed", "live")) {
            return PlatformMatch(Platform.YOUTUBE, parts[1], url)
        }
        return null
    }

    private fun matchTikTok(uri: URI, host: String, url: String): PlatformMatch? {
        val parts = segments(uri)
        val videoIndex = parts.indexOf("video")
        if (videoIndex >= 0 && parts.getOrNull(videoIndex + 1) != null) {
            return PlatformMatch(Platform.TIKTOK, parts[videoIndex + 1], url)
        }
        if (parts.size >= 2 && parts[0] == "t") {
            return PlatformMatch(Platform.TIKTOK, parts[1], url)
        }
        if (host == "vm.tiktok.com" || host == "vt.tiktok.com") {
            return parts.firstOrNull()?.let { PlatformMatch(Platform.TIKTOK, it, url) }
        }
        return null
    }

    private fun matchInstagram(uri: URI, url: String): PlatformMatch? {
        val parts = segments(uri)
        if (parts.size >= 2 && parts[0] in setOf("p", "reel", "reels", "tv", "stories")) {
            return PlatformMatch(Platform.INSTAGRAM, parts.take(3).joinToString("/"), url)
        }
        return null
    }

    private fun matchTwitter(uri: URI, host: String, url: String): PlatformMatch? {
        if (host == "t.co") {
            return segments(uri).firstOrNull()?.let { PlatformMatch(Platform.TWITTER, it, url) }
        }
        val parts = segments(uri)
        val statusIndex = parts.indexOf("status")
        if (statusIndex >= 0 && parts.getOrNull(statusIndex + 1) != null) {
            return PlatformMatch(Platform.TWITTER, parts[statusIndex + 1], url)
        }
        return null
    }

    private fun matchPinterest(uri: URI, host: String, url: String): PlatformMatch? {
        val parts = segments(uri)
        if (host == "pin.it") {
            return parts.firstOrNull()?.let { PlatformMatch(Platform.PINTEREST, it, url) }
        }
        // "/pin/123/", or "/pin/some-title--123/" as newer links have it.
        if (parts.size >= 2 && parts[0] == "pin") {
            return PlatformMatch(Platform.PINTEREST, parts[1].substringAfterLast("--"), url)
        }
        return null
    }

    private fun segments(uri: URI): List<String> =
        (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }

    private fun queryParam(uri: URI, name: String): String? =
        uri.rawQuery?.split('&')
            ?.map { it.substringBefore('=') to it.substringAfter('=', "") }
            ?.firstOrNull { (key, value) -> key == name && value.isNotEmpty() }
            ?.second
}
