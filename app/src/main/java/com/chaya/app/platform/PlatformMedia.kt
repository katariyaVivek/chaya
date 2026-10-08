package com.chaya.app.platform

import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/** One file or stream yt-dlp found for a video: a quality, audio only, or a ready-made combination. */
data class PlatformFormat(
    val id: String,
    val url: String,
    val ext: String?,
    val protocol: String?,
    val width: Int?,
    val height: Int?,
    val fps: Double?,
    val videoCodec: String?,
    val audioCodec: String?,
    /** Average bitrate in kilobits per second, when yt-dlp knows it. */
    val bitrateKbps: Double?,
    val audioBitrateKbps: Double?,
    val sizeBytes: Long?,
    val note: String?,
    val language: String?,
    /** Headers the file's server expects (referer, user agent...), applied when downloading. */
    val headers: Map<String, String>,
    /** yt-dlp's rank for an audio track's language: 10 original, 5 the site's default, -1 other dubs, -10 descriptive. */
    val languagePreference: Int? = null,
    /** "SDR", "HDR10", "HLG" and so on; null when unknown. */
    val dynamicRange: String? = null,
) {
    // yt-dlp writes "none" for a missing track. Anything else, including unknown, counts as present:
    // simple extractors often leave the codecs out of a file that has both.
    val hasVideo: Boolean get() = videoCodec != "none"
    val hasAudio: Boolean get() = audioCodec != "none"

    val isAudioOnly: Boolean
        get() = !audioCodec.isNullOrEmpty() && audioCodec != "none" && (videoCodec == null || videoCodec == "none")

    /** A plain file at one address, as opposed to an HLS or DASH manifest of many pieces. */
    val isDirectFile: Boolean
        get() {
            val proto = protocol?.lowercase(Locale.ROOT).orEmpty()
            val address = url.lowercase(Locale.ROOT)
            if ("m3u8" in proto || "dash" in proto || "m3u" in proto) return false
            return ".m3u8" !in address && ".mpd" !in address
        }

    /** A single file with both picture and sound. */
    val isCompleteFile: Boolean get() = isDirectFile && hasVideo && hasAudio

    /** YouTube's "DRC" tracks are the same sound with its loudness range squeezed; the plain track is preferred. */
    val isLoudnessCompressed: Boolean
        get() = note?.contains("DRC", ignoreCase = true) == true || id.endsWith("-drc", ignoreCase = true)

    /** HDR pictures look washed out on screens and players that cannot show HDR, so SDR is preferred. */
    val isStandardRange: Boolean get() = dynamicRange == null || dynamicRange.equals("SDR", ignoreCase = true)

    /**
     * The side of the picture people name a quality by: 1920x1080 and 1080x1920 are both "1080p".
     * Falls back to the height when the width is unknown.
     */
    val quality: Int?
        get() = when {
            width != null && height != null && width > 0 && height > 0 -> minOf(width, height)
            height != null && height > 0 -> height
            else -> null
        }
}

/** Everything the app needs to know about a link: what it is and what it can be saved as. */
data class PlatformMedia(
    val id: String?,
    val title: String,
    val author: String?,
    val durationSeconds: Double?,
    val thumbnailUrl: String?,
    val pageUrl: String?,
    val extractor: String?,
    val isLive: Boolean,
    val formats: List<PlatformFormat>,
) {
    companion object {
        /**
         * Reads the JSON `chaya_engine.extract` returns.
         * @throws PlatformException when the engine reported an error or the text is not what it promised.
         */
        fun parse(json: String): PlatformMedia {
            val root = try {
                JSONObject(json)
            } catch (e: JSONException) {
                throw PlatformException(PlatformException.Kind.ENGINE, "Unreadable engine output: ${e.message}")
            }

            root.optJSONObject("error")?.let { error ->
                throw PlatformException(
                    PlatformException.Kind.fromEngine(error.str("kind")),
                    error.str("detail"),
                )
            }

            val media = root.optJSONObject("media")
                ?: throw PlatformException(PlatformException.Kind.ENGINE, "The engine returned neither media nor an error.")

            val formats = media.optJSONArray("formats")?.let { array ->
                (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { parseFormat(it) } }
            }.orEmpty()

            return PlatformMedia(
                id = media.str("id"),
                title = media.str("title") ?: "Untitled",
                author = media.str("uploader") ?: media.str("channel"),
                durationSeconds = media.dbl("duration")?.takeIf { it > 0 },
                thumbnailUrl = media.str("thumbnail"),
                pageUrl = media.str("webpage_url"),
                extractor = media.str("extractor_key"),
                isLive = media.optBoolean("is_live", false),
                formats = formats,
            )
        }

        private fun parseFormat(f: JSONObject): PlatformFormat? {
            val url = f.str("url") ?: return null
            val headers = f.optJSONObject("http_headers")?.let { h ->
                h.keys().asSequence().mapNotNull { key -> h.str(key)?.let { key to it } }.toMap()
            }.orEmpty()
            return PlatformFormat(
                id = f.str("format_id") ?: url.hashCode().toString(),
                url = url,
                ext = f.str("ext"),
                protocol = f.str("protocol"),
                width = f.int("width"),
                height = f.int("height"),
                fps = f.dbl("fps"),
                videoCodec = f.str("vcodec"),
                audioCodec = f.str("acodec"),
                bitrateKbps = f.dbl("tbr"),
                audioBitrateKbps = f.dbl("abr"),
                sizeBytes = f.lng("filesize") ?: f.lng("filesize_approx"),
                note = f.str("format_note"),
                language = f.str("language"),
                headers = headers,
                languagePreference = f.int("language_preference"),
                dynamicRange = f.str("dynamic_range"),
            )
        }

        // org.json turns a JSON null into the text "null", so every read checks isNull first.
        private fun JSONObject.str(key: String): String? =
            if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

        private fun JSONObject.dbl(key: String): Double? =
            if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

        private fun JSONObject.int(key: String): Int? = dbl(key)?.toInt()

        private fun JSONObject.lng(key: String): Long? = dbl(key)?.toLong()
    }
}

/** Why a link could not be turned into something to download. [message] is fit to show to a person. */
class PlatformException(val kind: Kind, val detail: String? = null) : Exception(kind.message) {

    enum class Kind(val message: String) {
        BOT_CHECK("YouTube wants a quick sign-in check first. Open the video in the browser here, then try again."),
        PRIVATE("This video is private."),
        NEEDS_LOGIN("This needs a signed-in account. Sign in to the site in the browser here, then try again."),
        GEO("This video isn't available in your country."),
        UNSUPPORTED("This link isn't supported."),
        UNAVAILABLE("This video is unavailable or has been removed."),
        NETWORK("Couldn't reach the site. Check your connection and try again."),
        ENGINE("The video finder couldn't start. Try again in a moment."),
        UNKNOWN("Couldn't find a video to download at this link."),
        ;

        companion object {
            /** Maps the engine's lowercase kind names; anything unrecognized is [UNKNOWN]. */
            fun fromEngine(name: String?): Kind =
                entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: UNKNOWN
        }
    }
}
