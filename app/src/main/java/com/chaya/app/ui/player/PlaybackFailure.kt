package com.chaya.app.ui.player

import androidx.media3.common.PlaybackException

/**
 * Why a saved video would not play, in a sentence for the player and a line for Diagnostics. The player said only
 * "Chaya can't play this file", which told no one whether the phone lacked a decoder for the picture or the file was
 * damaged.
 */
internal object PlaybackFailure {

    /** The picture or sound that failed, as the player knew it when it failed; all null when it got no further. */
    data class Track(val mimeType: String?, val width: Int?, val height: Int?)

    /** The sentence the player shows. */
    fun sentence(errorCode: Int, track: Track): String = when (kind(errorCode)) {
        Kind.DECODER -> {
            val what = listOfNotNull(codecName(track.mimeType), sizeOf(track)).joinToString(", ")
            if (what.isEmpty()) "This phone can't decode this video." else "This phone can't decode this video ($what)."
        }
        Kind.DAMAGED -> "This file looks damaged or incomplete."
        Kind.MISSING -> "The file is no longer where Chaya saved it."
        Kind.OTHER -> "Chaya can't play this file."
    }

    /** The line for Diagnostics: the player's own name for the error, and the track. No address or file name. */
    fun diagnostic(errorCodeName: String, track: Track): String =
        listOfNotNull(errorCodeName, track.mimeType, sizeOf(track)).joinToString(" · ")

    private enum class Kind { DECODER, DAMAGED, MISSING, OTHER }

    private fun kind(errorCode: Int): Kind = when (errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> Kind.DECODER
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        -> Kind.DAMAGED
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        -> Kind.MISSING
        else -> Kind.OTHER
    }

    /** The picture's or sound's codec as people know it; null when unknown. */
    fun codecName(mimeType: String?): String? = when (mimeType?.lowercase()) {
        null -> null
        "video/avc" -> "H.264"
        "video/hevc" -> "H.265"
        "video/dolby-vision" -> "Dolby Vision"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/av01" -> "AV1"
        "audio/mp4a-latm" -> "AAC"
        "audio/opus" -> "Opus"
        "audio/ac3" -> "Dolby Digital"
        "audio/eac3" -> "Dolby Digital Plus"
        else -> mimeType.substringAfter('/')
    }

    private fun sizeOf(track: Track): String? {
        val width = track.width?.takeIf { it > 0 } ?: return null
        val height = track.height?.takeIf { it > 0 } ?: return null
        return "$width×$height"
    }
}
