package com.chaya.app.subtitles

import java.io.File

/**
 * What a clip saved as a video of its own is made at, as Chaya for Windows works it out (`core::export`, ported with
 * its tests): a size to fit, the bit rate for a size, the SRT of a clip, and the file's name. Plain Kotlin.
 */
object ClipExport {
    /** The sizes to fit offered, in megabytes; null is "as it is". */
    val SIZES: List<Int?> = listOf(null, 10, 25, 50)

    /** A size to fit worked out for a clip: its picture's size, and the bit rates of picture and sound (bits a second). */
    data class Fit(val width: Int, val height: Int, val video: Int, val audio: Int)

    /**
     * How a clip [length] ms long, its picture [width] by [height], fits in [bytes]: the bit rate that leaves room for
     * the file's own parts, the sound's share (less for a small file), and the picture made smaller (1080, 720, 540,
     * 480, 360, 240 on its narrow side) until each pixel has enough. Null when even the smallest wouldn't look of
     * anything: the clip is too long for that size.
     */
    fun fit(bytes: Long, length: Long, width: Int, height: Int, sound: Boolean): Fit? {
        val seconds = maxOf(length / 1000.0, 0.5)
        // A twentieth for the MP4's own parts and the encoder's slips.
        val total = bytes * 8.0 * 0.95 / seconds
        val audio = when {
            !sound -> 0.0
            total < 1_000_000.0 -> 64_000.0
            total < 2_500_000.0 -> 96_000.0
            else -> 128_000.0
        }
        val video = minOf(total - audio, 24_000_000.0)
        if (video < 150_000.0) return null
        val narrow = minOf(width, height)
        val candidates = listOf(narrow, 1080, 720, 540, 480, 360, 240).filter { it <= narrow }
        var last = width to height
        for ((i, n) in candidates.withIndex()) {
            val scale = n.toDouble() / maxOf(narrow, 1)
            fun even(v: Double) = maxOf(Math.round(v / 2.0).toInt(), 1) * 2
            last = even(width * scale) to even(height * scale)
            // Bits for each pixel of each frame, at 30 a second: under 0.05 H.264 falls apart.
            if (video / (last.first.toDouble() * last.second * 30.0) >= 0.05 || i == candidates.lastIndex) break
        }
        return Fit(last.first, last.second, video.toInt(), audio.toInt())
    }

    /** The bit rate H.264 is asked for at [width] by [height]: 8 Mb/s for 1080p, scaled by pixels, 1.5 to 24. */
    fun bitRate(width: Int, height: Int): Int =
        (width.toLong() * height * 8_000_000 / (1920 * 1080)).coerceIn(1_500_000, 24_000_000).toInt()

    /** The lines that show in a clip from [start] to [end], timed from 0:00 and cut at its edges. */
    fun inside(lines: List<SubtitleLine>, start: Long, end: Long): List<SubtitleLine> =
        lines.filter { it.end > start && it.start < end }
            .map { SubtitleLine(maxOf(it.start, start) - start, minOf(it.end, end) - start, it.text) }

    /** The SRT for a clip: its lines (each with its translation under it, when one is given), timed from 0:00. */
    fun srt(lines: List<SubtitleLine>, start: Long, end: Long, translated: List<String?>? = null): String =
        Subtitles.toSrt(inside(together(lines, translated), start, end))

    /** Each line with its translation under it, one block, so neither covers the other. */
    fun together(lines: List<SubtitleLine>, translated: List<String?>?): List<SubtitleLine> =
        if (translated == null || translated.size != lines.size) lines
        else lines.mapIndexed { i, line -> translated[i]?.let { line.copy(text = "${line.text}\n$it") } ?: line }

    /** "A walk in the park - The best bit.mp4": no character a file name can't take, and "(2)" when it is taken. */
    fun fileFor(folder: File, video: String, clip: String, extension: String): File {
        fun clean(s: String) = s.map { if (it in "<>:\"/\\|?*" || it.isISOControl()) ' ' else it }.joinToString("")
            .trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").trim('.', ' ').take(80)
        val v = clean(video)
        val c = clean(clip)
        val stem = when {
            v.isNotEmpty() && c.isNotEmpty() -> "$v - $c"
            c.isNotEmpty() -> c
            v.isNotEmpty() -> "$v - clip"
            else -> "Clip"
        }
        var file = File(folder, "$stem.$extension")
        var n = 2
        while (file.exists()) file = File(folder, "$stem (${n++}).$extension")
        return file
    }
}
