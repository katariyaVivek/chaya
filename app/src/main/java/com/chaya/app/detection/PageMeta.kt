package com.chaya.app.detection

import org.json.JSONArray
import org.json.JSONObject

/** One `<video>`/`<audio>` element as the injected detector saw it. */
data class PlayerMeta(
    /** The element's http(s) source, or null for blob:/MediaSource players. */
    val src: String?,
    /** True when the element plays a blob: URL, i.e. a JavaScript-fed stream. */
    val isBlob: Boolean,
    val isAudio: Boolean,
    /** Rendered size in CSS pixels. */
    val width: Int,
    val height: Int,
    val visible: Boolean,
    val durationSeconds: Double?,
    /** Intrinsic video size, known once metadata loads. */
    val videoWidth: Int?,
    val videoHeight: Int?,
    val playing: Boolean,
    val played: Boolean,
    val muted: Boolean,
    val autoplay: Boolean,
    val loop: Boolean,
    val controls: Boolean,
    val poster: String?,
    /** Title from the element's `title`, `aria-label`, or enclosing figure caption. */
    val title: String?,
    /** True when an ancestor's id or class marks it as an ad slot. */
    val inAdContainer: Boolean,
) {
    val area: Int get() = width * height
}

/** Page-level hints the sheet uses to rank and name detected media. */
data class PageMeta(
    val title: String?,
    val ogTitle: String?,
    val siteName: String?,
    val ogImage: String?,
    /** Declared main-video URLs: og:video*, twitter:player:stream, JSON-LD contentUrl. */
    val videoUrls: List<String>,
    val ldName: String?,
    val ldThumbnail: String?,
    val ldDurationSeconds: Double?,
    val players: List<PlayerMeta>,
)

/**
 * Parses the detector's page report defensively: the JSON is page-controlled,
 * so sizes are capped, strings truncated, numbers clamped, and only http(s)
 * URLs survive.
 */
object PageMetaParser {
    const val MAX_JSON_CHARS = 32_768
    private const val MAX_TEXT_CHARS = 300
    private const val MAX_URL_CHARS = 4_096
    private const val MAX_PLAYERS = 8
    private const val MAX_VIDEO_URLS = 8
    private const val MAX_PIXELS = 100_000
    private const val MAX_DURATION_SECONDS = 7.0 * 24 * 60 * 60

    private val isoDuration = Regex(
        "^P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?$",
        RegexOption.IGNORE_CASE,
    )

    /** Returns null for oversized, malformed, or non-object input. */
    fun parse(json: String?): PageMeta? {
        if (json.isNullOrEmpty() || json.length > MAX_JSON_CHARS) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return PageMeta(
            title = root.text("title"),
            ogTitle = root.text("ogTitle"),
            siteName = root.text("siteName"),
            ogImage = root.url("ogImage"),
            videoUrls = root.optJSONArray("videoUrls").urls(MAX_VIDEO_URLS),
            ldName = root.text("ldName"),
            ldThumbnail = root.url("ldThumbnail"),
            ldDurationSeconds = parseIsoDuration(root.text("ldDuration")),
            players = root.optJSONArray("players").players(),
        )
    }

    /** Converts schema.org durations such as `PT1M42S` to seconds. */
    fun parseIsoDuration(value: String?): Double? {
        val match = value?.trim()?.let(isoDuration::matchEntire) ?: return null
        val (days, hours, minutes, seconds) = match.destructured
        if (days.isEmpty() && hours.isEmpty() && minutes.isEmpty() && seconds.isEmpty()) return null
        val total = (days.toDoubleOrNull() ?: 0.0) * 86_400 +
            (hours.toDoubleOrNull() ?: 0.0) * 3_600 +
            (minutes.toDoubleOrNull() ?: 0.0) * 60 +
            (seconds.toDoubleOrNull() ?: 0.0)
        return total.takeIf { it > 0 && it <= MAX_DURATION_SECONDS }
    }

    private fun JSONArray?.players(): List<PlayerMeta> {
        if (this == null) return emptyList()
        return (0 until minOf(length(), MAX_PLAYERS)).mapNotNull { index ->
            val p = optJSONObject(index) ?: return@mapNotNull null
            PlayerMeta(
                src = p.url("src"),
                isBlob = p.optBoolean("blob"),
                isAudio = p.optBoolean("audio"),
                width = p.pixels("w") ?: 0,
                height = p.pixels("h") ?: 0,
                visible = p.optBoolean("visible"),
                durationSeconds = p.seconds("duration"),
                videoWidth = p.pixels("vw"),
                videoHeight = p.pixels("vh"),
                playing = p.optBoolean("playing"),
                played = p.optBoolean("played"),
                muted = p.optBoolean("muted"),
                autoplay = p.optBoolean("autoplay"),
                loop = p.optBoolean("loop"),
                controls = p.optBoolean("controls"),
                poster = p.url("poster"),
                title = p.text("title"),
                inAdContainer = p.optBoolean("ad"),
            )
        }
    }

    private fun JSONArray?.urls(limit: Int): List<String> {
        if (this == null) return emptyList()
        return (0 until minOf(length(), limit)).mapNotNull { index ->
            (opt(index) as? String)?.let(::httpUrlOrNull)
        }
    }

    private fun JSONObject.text(key: String): String? {
        val raw = opt(key) as? String ?: return null
        return raw.replace(Regex("\\s+"), " ").trim().take(MAX_TEXT_CHARS).ifEmpty { null }
    }

    private fun JSONObject.url(key: String): String? = (opt(key) as? String)?.let(::httpUrlOrNull)

    private fun JSONObject.pixels(key: String): Int? {
        val value = (opt(key) as? Number)?.toDouble() ?: return null
        if (value.isNaN() || value <= 0) return null
        return value.coerceAtMost(MAX_PIXELS.toDouble()).toInt()
    }

    private fun JSONObject.seconds(key: String): Double? {
        val value = (opt(key) as? Number)?.toDouble() ?: return null
        return value.takeIf { !it.isNaN() && it > 0 && it <= MAX_DURATION_SECONDS }
    }

    private fun httpUrlOrNull(value: String): String? {
        val url = value.trim()
        if (url.length > MAX_URL_CHARS) return null
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        return url.takeIf { MediaUrlClassifier.hostOf(it) != null }
    }
}
