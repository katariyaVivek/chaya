package com.chaya.app.subtitles

/**
 * Subtitles, as Chaya for Windows reads them (`desktop/core/src/subtitles.rs`, ported line for line): SRT and WebVTT
 * read into lines, the line shown at a time, which of a site's tracks to save with a video, and lines written as SRT.
 *
 * Chaya draws subtitles itself rather than through the player, so the line on the picture and the transcript under
 * it are the same lines. Plain Kotlin, no Android, so it is tested on the JVM.
 */

/** One subtitle line: shown from [start] until [end] (milliseconds), its text without styling, lines joined by "\n". */
data class SubtitleLine(val start: Long, val end: Long, val text: String)

/** A stretch of time and the line shown in it, by its index in the lines. */
data class SubtitleSpan(val start: Long, val end: Long, val line: Int)

/** One of a site's subtitle tracks, as the engine lists it: its language tag, address, and whether a machine made it. */
data class SubtitleTrack(val language: String, val url: String, val automatic: Boolean)

/** A site's subtitles to fetch for a download: the language its file is named by ("en"), and the headers to send. */
data class SubtitleSource(val language: String, val url: String, val headers: Map<String, String> = emptyMap())

object Subtitles {

    /**
     * Reads SRT or WebVTT (told apart by nothing: both are blocks of a timing line and text) into lines, in the order
     * they start. Blocks without a timing line (VTT's header, NOTE, STYLE and REGION, an SRT's stray numbers) are left
     * out, as are lines with no text once their styling is taken off.
     */
    fun parse(input: String): List<SubtitleLine> {
        val text = input.removePrefix("\uFEFF")
        // YouTube's machine captions roll: each line comes again at the top of the next, with each word's time inside it.
        val rolling = "><c>" in text || hasInlineTimes(text)
        val lines = mutableListOf<SubtitleLine>()
        var cue: Cue? = null
        // A text line right after a line of spaces, kept apart: when a timing line follows it, it was the next block's
        // number, in a file whose blocks are parted by spaces instead of empty lines.
        var afterSpaces = false
        var maybeNumber = false
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            val timing = timing(line)
            if (timing != null) {
                if (maybeNumber) cue?.text?.removeLastOrNull()
                close(cue, lines)
                cue = Cue(timing.first, timing.second, mutableListOf())
                afterSpaces = false
                maybeNumber = false
            } else if (line.isEmpty()) {
                close(cue, lines)
                cue = null
                afterSpaces = false
                maybeNumber = false
            } else if (cue != null) {
                maybeNumber = afterSpaces
                afterSpaces = line.isBlank()
                cue.text.add(line)
            }
        }
        close(cue, lines)
        // Stable, as Rust's: lines starting together keep the file's order
        val sorted = lines.sortedBy { it.start }
        return if (rolling) settleRolling(sorted) else sorted
    }

    private class Cue(val start: Long, val end: Long, val text: MutableList<String>)

    /** Ends the cue being read, keeping it when it has text and lasts. */
    private fun close(cue: Cue?, lines: MutableList<SubtitleLine>) {
        cue ?: return
        val text = cue.text.map(::clean).filter { it.isNotEmpty() }.joinToString("\n")
        if (text.isNotEmpty() && cue.end > cue.start) lines.add(SubtitleLine(cue.start, cue.end, text))
    }

    /**
     * YouTube's machine captions as one line after another: the line carried over from the cue before is taken off
     * each cue, and the moments a cue only holds what was already shown are left out.
     */
    private fun settleRolling(lines: List<SubtitleLine>): List<SubtitleLine> {
        var shown: List<String> = emptyList()
        val kept = mutableListOf<SubtitleLine>()
        for (line in lines) {
            val rows = line.text.split('\n').toMutableList()
            while (rows.isNotEmpty() && rows.first() in shown) rows.removeAt(0)
            shown = line.text.split('\n')
            val text = rows.joinToString("\n")
            if (text.isNotEmpty()) kept.add(line.copy(text = text))
        }
        return kept
    }

    private fun hasInlineTimes(text: String): Boolean {
        var i = text.indexOf('<')
        while (i >= 0) {
            val close = text.indexOf('>', i + 1)
            if (close >= 0 && time(text.substring(i + 1, close)) != null) return true
            i = text.indexOf('<', i + 1)
        }
        return false
    }

    /** "00:01:02,500 --> 00:01:04,000" (SRT) or "01:02.500 --> 01:04.000 align:start" (VTT, with its cue settings). */
    internal fun timing(line: String): Pair<Long, Long>? {
        val arrow = line.indexOf("-->")
        if (arrow < 0) return null
        val start = time(line.substring(0, arrow).trim()) ?: return null
        val end = words(line.substring(arrow + 3)).firstOrNull()?.let(::time) ?: return null
        return start to end
    }

    /**
     * "hh:mm:ss,ttt", "hh:mm:ss.ttt" or "mm:ss.ttt", in milliseconds; a fraction of fewer than three digits is read
     * as the decimal it is.
     */
    internal fun time(text: String): Long? {
        val split = text.indexOfFirst { it == ',' || it == '.' }
        val clock = if (split >= 0) text.substring(0, split) else text
        val fraction = if (split >= 0) text.substring(split + 1) else ""
        val parts = clock.split(':')
        if (parts.size !in 2..3 || fraction.length > 3) return null
        var seconds = 0L
        for ((i, part) in parts.withIndex()) {
            val n = digits(part) ?: return null
            if (i > 0 && n >= 60) return null
            seconds = seconds * 60 + n
        }
        val millis = if (fraction.isEmpty()) 0 else (digits(fraction) ?: return null) * POWERS[3 - fraction.length]
        return seconds * 1000 + millis
    }

    /** A number written in ASCII digits only: not "+5", not "٥". */
    private fun digits(s: String): Long? =
        if (s.isNotEmpty() && s.length <= 18 && s.all { it in '0'..'9' }) s.toLong() else null

    private val POWERS = longArrayOf(1, 10, 100, 1000)
    /**
     * Unicode's white space (its White_Space property), as Rust's split_whitespace has it, a no-break space
     * included. Listed, not a regex: `(?U)\s`, which says the same on a desktop's Java, is a syntax error to
     * Android's regex engine (ICU), and threw as this object was first used, closing the app.
     */
    internal fun isSpace(c: Char): Boolean =
        c in '\u0009'..'\u000D' || c == ' ' || c == '\u0085' || c == '\u00A0' || c == '\u1680' ||
            c in '\u2000'..'\u200A' || c == '\u2028' || c == '\u2029' || c == '\u202F' || c == '\u205F' ||
            c == '\u3000'

    /** The words of [s]: what lies between its runs of white space. */
    internal fun words(s: String): List<String> {
        val words = mutableListOf<String>()
        var start = -1
        for (i in s.indices) {
            if (isSpace(s[i])) {
                if (start >= 0) words += s.substring(start, i)
                start = -1
            } else if (start < 0) {
                start = i
            }
        }
        if (start >= 0) words += s.substring(start)
        return words
    }

    /**
     * A text line without its styling: HTML-like tags (`<i>`, `<font>`, VTT's `<c.yellow>`, `<v Name>` and word
     * times), SSA overrides (`{\an8}`), and entities read as the characters they stand for.
     */
    internal fun clean(line: String): String {
        val out = StringBuilder(line.length)
        val hasClose = '>' in line
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '<' && hasClose -> {
                    i++
                    while (i < line.length && line[i] != '>') i++
                }
                c == '{' && i + 1 < line.length && line[i + 1] == '\\' -> {
                    i++
                    while (i < line.length && line[i] != '}') i++
                }
                else -> out.append(c)
            }
            i++
        }
        val read = out.toString()
            .replace("&nbsp;", " ")
            .replace("&lrm;", "")
            .replace("&rlm;", "")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
        return words(read).joinToString(" ")
    }

    /** Lines as an SRT file, numbered from 1. */
    fun toSrt(lines: List<SubtitleLine>): String = buildString {
        fun two(n: Long) = n.toString().padStart(2, '0')
        fun stamp(ms: Long) = "${two(ms / 3_600_000)}:${two(ms / 60_000 % 60)}:${two(ms / 1000 % 60)},${(ms % 1000).toString().padStart(3, '0')}"
        lines.forEachIndexed { i, line ->
            append(i + 1).append('\n')
            append(stamp(line.start)).append(" --> ").append(stamp(line.end)).append('\n')
            append(line.text).append("\n\n")
        }
    }

    /**
     * Which line shows when: stretches that do not overlap, in order, with nothing between lines. Where two lines
     * overlap, the one that started later shows; when it ends, an earlier one still running shows again.
     */
    fun timeline(lines: List<SubtitleLine>): List<SubtitleSpan> {
        val edges = lines.flatMap { listOf(it.start, it.end) }.distinct().sorted()
        val spans = mutableListOf<SubtitleSpan>()
        for (k in 0 until edges.size - 1) {
            val start = edges[k]
            val end = edges[k + 1]
            // `lines` are in the order they start, so the last one running is the latest started.
            val line = lines.indexOfLast { it.start <= start && it.end >= end }
            if (line < 0) continue
            val last = spans.lastOrNull()
            if (last != null && last.line == line && last.end == start) {
                spans[spans.lastIndex] = last.copy(end = end)
            } else {
                spans.add(SubtitleSpan(start, end, line))
            }
        }
        return spans
    }

    /** The line shown at [at] milliseconds, by its index, from [timeline]'s spans; null between lines. */
    fun lineAt(spans: List<SubtitleSpan>, at: Long): Int? {
        // The last span starting at or before [at], found by halving
        var low = 0
        var high = spans.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (spans[mid].start <= at) low = mid + 1 else high = mid
        }
        val span = spans.getOrNull(low - 1) ?: return null
        return span.line.takeIf { at < span.end }
    }

    /**
     * Which of a site's tracks is saved with a video in [language] (the video's own, when the site says): its own
     * language's subtitles, then a machine caption in that language (yt-dlp marks the original one "-orig"); a video
     * whose language is not known gets the site's first subtitles. Machine captions in other languages are machine
     * translations, never taken.
     */
    fun pick(tracks: List<SubtitleTrack>, language: String?): SubtitleTrack? {
        fun base(tag: String) = tag.split('-', '_').first().lowercase()
        val own = language?.let(::base)?.takeIf { it.isNotEmpty() }
        val made = tracks.filter { !it.automatic }
        val machine = tracks.filter { it.automatic }
        return if (own != null) {
            made.firstOrNull { base(it.language) == own }
                ?: machine.firstOrNull { it.language.endsWith("-orig") && base(it.language) == own }
                ?: machine.firstOrNull { base(it.language) == own }
        } else {
            made.firstOrNull() ?: machine.firstOrNull { it.language.endsWith("-orig") }
        }
    }

    /**
     * The language as it is written into a file name: "en", "pt-BR", "zh-Hans"; YouTube's "-orig" mark and a named
     * track's own suffix ("en-nP7-2PuUl7o") are left off. Null when nothing in it names a language, so a site's tag
     * can never put anything else into a file's name.
     */
    fun fileLanguage(tag: String): String? {
        val parts = tag.removeSuffix("-orig").split('-', '_')
        val first = parts.first()
        if (first.length !in 2..3 || !first.all(::isAsciiLetter)) return null
        val out = StringBuilder(first.lowercase())
        for (part in parts.drop(1)) {
            val script = part.length == 4 && part.all(::isAsciiLetter)
            val region = (part.length == 2 && part.all(::isAsciiLetter)) || (part.length == 3 && part.all { it in '0'..'9' })
            if (!script && !region) break
            out.append('-').append(part)
        }
        return out.toString()
    }

    private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    /** The subtitles to save with a video from a site that lists [tracks] for it, in [language], fetched with [headers]. */
    fun toSave(tracks: List<SubtitleTrack>, language: String?, headers: Map<String, String>): SubtitleSource? {
        val track = pick(tracks, language) ?: return null
        return SubtitleSource(fileLanguage(track.language) ?: return null, track.url, headers)
    }
}
