package com.chaya.app.subtitles

import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * A search across what is said in every saved video: the lines of the subtitles kept with each one ([SubtitleStore],
 * "12.en.srt"), found by words as the Library's search box is typed into. Ported from Chaya for Windows
 * (`desktop/core/src/search.rs`) with its tests.
 *
 * Words are matched ignoring case and accents ("ecole" finds "École", "strasse" finds "Straße"), each anywhere in the
 * line and in any order; a line is found when it holds all of them. What matched is marked, as ranges of the line's
 * own text (a Kotlin string's indices).
 *
 * The lines are kept in memory, folded once when read, so a search reads no file: before a search the index looks
 * again at which subtitle files each video has, re-reading only a file whose size or time changed ([keepUp]), with the
 * folder listed once. Plain Kotlin, no Android, so it is tested on the JVM. Safe to use from any thread.
 */
class LineSearch(private val dir: File) {

    /** A saved video to search the lines of: its download id and the title the Library shows. */
    data class Video(val id: Long, val title: String)

    /**
     * A line found: the video's id and title, the language of its subtitles ("en"), when it shows (milliseconds), its
     * text as the subtitles have it, and the ranges of that text that matched (start inclusive, end exclusive).
     */
    data class Hit(
        val id: Long,
        val title: String,
        val language: String,
        val start: Long,
        val end: Long,
        val text: String,
        val marks: List<IntRange>,
    )

    /** What a search found: at most so many lines, by video in the order given and then by time; how many matched in all. */
    data class Results(val hits: List<Hit> = emptyList(), val total: Int = 0) {
        /** How many videos the lines found are in. */
        val videos: Int get() = hits.distinctBy { it.id }.size
    }

    /** One subtitles file as it was when read: its name, size and when it was last written. */
    private data class Stamp(val name: String, val size: Long, val modified: Long)

    private class Track(val language: String, val lines: List<SubtitleLine>, val folded: String, val starts: IntArray)

    private class Indexed(var video: Video, val files: List<Stamp>, val tracks: List<Track>)

    private var videos: List<Indexed> = emptyList()
    private var checkedAt: Long? = null

    /**
     * Looks at the subtitle files of [videos] again when the videos are not those indexed, or the files were last looked
     * at more than [RECHECK_MS] before [nowMs]. Whether it looked.
     */
    @Synchronized
    fun keepUp(videos: List<Video>, nowMs: Long): Boolean {
        val same = videos.size == this.videos.size && videos.zip(this.videos).all { (v, i) -> v == i.video }
        val fresh = checkedAt?.let { nowMs - it in 0 until RECHECK_MS } == true
        if (same && fresh) return false
        refresh(videos)
        checkedAt = nowMs
        return true
    }

    /**
     * Indexes [videos], in their order: a video no longer among them is let go, and of the rest only the subtitle files
     * that are new, or whose size or time changed, are read. The folder is listed once. How many files were read.
     */
    @Synchronized
    fun refresh(videos: List<Video>): Int {
        val listed = list()
        val old = this.videos.associateBy { it.video.id }
        var read = 0
        this.videos = videos.map { video ->
            val files = listed[video.id].orEmpty()
            val kept = old[video.id]?.takeIf { it.files == files }
            if (kept != null) {
                kept.video = video
                kept
            } else {
                read += files.size
                Indexed(video, files, files.mapNotNull { readTrack(video.id, it) })
            }
        }
        return read
    }

    /** The lines that hold every word of [query], in the videos [among] (all, when null), at most [most] of them. */
    @Synchronized
    fun search(query: String, among: Set<Long>? = null, most: Int = MOST): Results {
        val words = wordsOf(query)
        val longest = words.firstOrNull() ?: return Results()
        val hits = mutableListOf<Hit>()
        var total = 0
        for (indexed in videos) {
            if (among != null && indexed.video.id !in among) continue
            val found = mutableListOf<Pair<Track, SubtitleLine>>()
            for (track in indexed.tracks) {
                var last = -1
                var from = 0
                while (true) {
                    val at = track.folded.indexOf(longest, from)
                    if (at < 0) break
                    from = at + longest.length
                    val line = lineAt(track.starts, at)
                    if (line == last) continue
                    last = line
                    val end = if (line + 1 < track.starts.size) track.starts[line + 1] - 1 else track.folded.length
                    val text = track.folded.substring(track.starts[line], end)
                    if (words.drop(1).all { it in text }) found += track to track.lines[line]
                }
            }
            total += found.size
            found.sortWith(compareBy({ it.second.start }, { it.first.language }))
            for ((track, line) in found.take((most - hits.size).coerceAtLeast(0))) {
                hits += Hit(indexed.video.id, indexed.video.title, track.language, line.start, line.end, line.text, marks(line.text, words))
            }
        }
        return Results(hits, total)
    }

    /** The subtitle files kept, by download id ("12.en.srt"), each with its size and time, in a fixed order. */
    private fun list(): Map<Long, List<Stamp>> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".srt") }
            .mapNotNull { file ->
                val id = file.name.substringBefore('.').toLongOrNull() ?: return@mapNotNull null
                id to Stamp(file.name, file.length(), file.lastModified())
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, files) -> files.sortedBy { it.name } }

    private fun readTrack(id: Long, stamp: Stamp): Track? {
        val language = stamp.name.removePrefix("$id.").removeSuffix(".srt")
        val lines = runCatching { Subtitles.parse(File(dir, stamp.name).readText()) }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val folded = StringBuilder()
        val starts = IntArray(lines.size)
        lines.forEachIndexed { index, line ->
            if (index > 0) folded.append('\n')
            starts[index] = folded.length
            foldInto(line.text, folded) {}
        }
        return Track(language, lines, folded.toString(), starts)
    }

    companion object {
        /** The most lines a search gives back; the rest are counted. */
        const val MOST = 200

        /** How long the subtitle files seen are trusted before they are looked at again. */
        const val RECHECK_MS = 1_000L

        /** The line a place in a track's folded text is in: the last whose start is at or before it. */
        private fun lineAt(starts: IntArray, at: Int): Int {
            var low = 0
            var high = starts.size - 1
            while (low < high) {
                val middle = (low + high + 1) / 2
                if (starts[middle] <= at) low = middle else high = middle - 1
            }
            return low
        }

        /**
         * [text] as it is searched: lower case, without accents, with letters some languages write as two ("ß", "æ")
         * as two, and any space or line break as one space.
         */
        fun fold(text: String): String = StringBuilder(text.length).also { foldInto(text, it) {} }.toString()

        /** Folds [text] onto [out], calling [each] with how many characters each of its code points became. */
        private fun foldInto(text: String, out: StringBuilder, each: (Int) -> Unit) {
            var i = 0
            while (i < text.length) {
                val point = text.codePointAt(i)
                val before = out.length
                when {
                    point < 0x80 && point != ' '.code && !Subtitles.isSpace(point.toChar()) ->
                        out.append(point.toChar().lowercaseChar())
                    point <= 0xFFFF && Subtitles.isSpace(point.toChar()) -> out.append(' ')
                    else -> foldLetter(String(Character.toChars(point)), out)
                }
                each(out.length - before)
                i += Character.charCount(point)
            }
        }

        private fun foldLetter(letter: String, out: StringBuilder) {
            val lower = letter.lowercase(Locale.ROOT)
            var i = 0
            while (i < lower.length) {
                val point = lower.codePointAt(i)
                when (point) {
                    'ß'.code -> out.append("ss")
                    'æ'.code -> out.append("ae")
                    'œ'.code -> out.append("oe")
                    'ø'.code -> out.append('o')
                    'đ'.code, 'ð'.code -> out.append('d')
                    'ł'.code -> out.append('l')
                    'ı'.code -> out.append('i')
                    'þ'.code -> out.append("th")
                    0x2018, 0x2019, 0x02BC -> out.append('\'')
                    else -> {
                        val parts = Normalizer.normalize(String(Character.toChars(point)), Normalizer.Form.NFD)
                        var j = 0
                        while (j < parts.length) {
                            val part = parts.codePointAt(j)
                            if (!isAccent(part)) out.appendCodePoint(part)
                            j += Character.charCount(part)
                        }
                    }
                }
                i += Character.charCount(point)
            }
        }

        /**
         * An accent, as taking a letter apart leaves it (the "´" of "é"): the combining marks Latin, Greek and Cyrillic
         * letters are written with. Other scripts' marks (Devanagari's vowel signs) are part of the word, and stay.
         */
        private fun isAccent(point: Int): Boolean =
            point in 0x300..0x36F || point in 0x1AB0..0x1AFF || point in 0x1DC0..0x1DFF ||
                point in 0x20D0..0x20FF || point in 0xFE20..0xFE2F

        private fun isWordPart(point: Int) = Character.isAlphabetic(point) || Character.isDigit(point)

        /** The words of a query, folded, without the punctuation at their ends ("walk," is "walk"), each once, longest first. */
        internal fun wordsOf(query: String): List<String> {
            val words = mutableListOf<String>()
            for (raw in fold(query).split(' ')) {
                var start = 0
                var end = raw.length
                while (start < end && !isWordPart(raw.codePointAt(start))) start += Character.charCount(raw.codePointAt(start))
                while (end > start && !isWordPart(raw.codePointBefore(end))) end -= Character.charCount(raw.codePointBefore(end))
                val word = raw.substring(start, end)
                if (word.isNotEmpty() && word !in words) words += word
            }
            return words.sortedByDescending { it.length }
        }

        /** Where in [text] each of [words] is (every time), in order and joined where they touch or overlap. */
        internal fun marks(text: String, words: List<String>): List<IntRange> {
            // Each character of the folded text, and the range of [text] of the code point it came from.
            val fromStart = mutableListOf<Int>()
            val fromEnd = mutableListOf<Int>()
            val folded = StringBuilder(text.length)
            var at = 0
            var i = 0
            foldInto(text, folded) { added ->
                val width = Character.charCount(text.codePointAt(i))
                repeat(added) {
                    fromStart += at
                    fromEnd += at + width
                }
                at += width
                i += width
            }
            val found = folded.toString()
            val ranges = words.flatMap { word ->
                buildList {
                    var from = 0
                    while (true) {
                        val index = found.indexOf(word, from)
                        if (index < 0) break
                        add(fromStart[index] to fromEnd[index + word.length - 1])
                        from = index + word.length
                    }
                }
            }.sortedWith(compareBy({ it.first }, { it.second }))
            val joined = mutableListOf<Pair<Int, Int>>()
            for (range in ranges) {
                val last = joined.lastOrNull()
                if (last != null && range.first <= last.second) {
                    joined[joined.size - 1] = last.first to maxOf(last.second, range.second)
                } else {
                    joined += range
                }
            }
            return joined.map { it.first until it.second }
        }
    }
}
