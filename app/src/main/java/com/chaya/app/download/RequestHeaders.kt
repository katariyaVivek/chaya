package com.chaya.app.download

/**
 * Stores request headers as plain text, one "Name: value" per line, as they appear on the wire. HTTP
 * forbids line breaks inside a header, so the format needs no escaping, and it stays readable in the database.
 */
object HeaderCodec {

    /** Null when there is nothing worth storing. Headers that could not survive the format are dropped. */
    fun encode(headers: Map<String, String>): String? =
        headers.entries
            .filter { (name, value) ->
                name.isNotBlank() && ':' !in name && !name.hasLineBreak() && !value.hasLineBreak()
            }
            .joinToString("\n") { (name, value) -> "$name: $value" }
            .ifEmpty { null }

    fun decode(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        return text.lineSequence()
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) return@mapNotNull null
                val name = line.substring(0, colon).trim()
                if (name.isEmpty()) null else name to line.substring(colon + 1).trim()
            }
            .toMap()
    }

    private fun String.hasLineBreak() = '\n' in this || '\r' in this
}

/**
 * The three headers a media server most often checks, picked (case-insensitively) from whatever the engine
 * reported. Other headers the engine sends, such as Accept or Sec-Fetch-Mode, do not change what a CDN serves.
 */
data class RequestHeaders(
    val userAgent: String?,
    val cookies: String?,
    val referer: String?,
) {
    companion object {
        fun from(headers: Map<String, String>, fallbackReferer: String? = null): RequestHeaders {
            fun get(name: String) = headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.takeIf { it.isNotBlank() }
            return RequestHeaders(
                userAgent = get("User-Agent"),
                cookies = get("Cookie"),
                referer = get("Referer") ?: fallbackReferer,
            )
        }
    }
}
