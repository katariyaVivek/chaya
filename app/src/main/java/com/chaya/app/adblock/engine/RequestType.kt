package com.chaya.app.adblock.engine

/**
 * What a request is for, as far as can be told. Ad lists name the kinds of request a rule applies to
 * (`$script`, `$image`...). A request whose kind cannot be told is [UNKNOWN] and is matched only by rules that
 * apply to every kind, so a guess never blocks something a rule did not mean.
 */
object RequestType {
    const val SCRIPT = 1
    const val IMAGE = 1 shl 1
    const val STYLESHEET = 1 shl 2
    const val XHR = 1 shl 3
    const val SUBDOCUMENT = 1 shl 4
    const val MEDIA = 1 shl 5
    const val FONT = 1 shl 6
    const val WEBSOCKET = 1 shl 7
    const val PING = 1 shl 8
    const val OBJECT = 1 shl 9
    const val OTHER = 1 shl 10
    const val ALL = (1 shl 11) - 1
    const val UNKNOWN = 0

    /** The kind a list option names, or null when the option is not a kind of request. */
    internal fun ofOption(name: String): Int? = when (name) {
        "script" -> SCRIPT
        "image" -> IMAGE
        "stylesheet", "css" -> STYLESHEET
        "xmlhttprequest", "xhr" -> XHR
        "subdocument", "frame" -> SUBDOCUMENT
        "media" -> MEDIA
        "font" -> FONT
        "websocket" -> WEBSOCKET
        "ping", "beacon" -> PING
        "object", "object-subrequest" -> OBJECT
        "other" -> OTHER
        else -> null
    }
}
