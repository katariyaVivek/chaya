package com.chaya.app.streaming

import java.net.URI
import java.util.Locale

/**
 * The headers a stream's requests carry, as the page's own player sends them. A player in a frame fetches its
 * stream with the frame as Referer and the frame's origin as Origin (every cross-origin fetch carries one); a
 * server that checks Origin and finds none can answer with a short decoy or an error stream instead.
 */
object StreamHeaders {

    fun of(userAgent: String?, cookies: String?, referer: String?): Map<String, String> = buildMap {
        userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        cookies?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        referer?.takeIf { it.isNotBlank() }?.let { page ->
            put("Referer", page)
            originOf(page)?.let { put("Origin", it) }
        }
    }

    /** `https://player.example` for `https://player.example/e/1?x`; null for anything that is not http(s). */
    fun originOf(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val port = uri.port.takeIf { it != -1 && it != (if (scheme == "https") 443 else 80) }
        return if (port == null) "$scheme://$host" else "$scheme://$host:$port"
    }
}
