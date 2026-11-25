package com.chaya.app.subtitles

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Subtitles put right by the person, as Chaya for Windows does it (`desktop/core/src/fix.rs`, ported with its tests): a
 * line's words corrected (or the line taken out), and the timing of the whole track moved: shifted, or stretched
 * between two lines when it drifts (a file made for another cut of the video, or for another frame rate). Plain
 * Kotlin, no Android, so it is tested on the JVM.
 */

/** What the person asked of a track's timing. */
sealed interface Retiming {
    /** Every line [by] milliseconds later (earlier when negative). */
    data class Shift(val by: Long) : Retiming

    /** The line that starts at [from] starts at [to], while the line already put right at [fixed] stays where it is. */
    data class Through(val fixed: Long, val from: Long, val to: Long) : Retiming
}

/** A change of a track's timing: every time t becomes t × [scale] + [offset] (milliseconds). */
data class Retime(val scale: Double, val offset: Double) {

    private fun at(ms: Long): Double = ms * scale + offset

    /**
     * [lines] with their times changed, kept in order: a line that would end before the video starts is left out, and
     * one that would start before it starts at 0.
     */
    fun apply(lines: List<SubtitleLine>): List<SubtitleLine> = lines.mapNotNull { line ->
        val end = at(line.end)
        if (end <= 0.0) return@mapNotNull null
        val start = at(line.start).coerceAtLeast(0.0).roundToLong()
        SubtitleLine(start, end.roundToLong().coerceAtLeast(start + 1), line.text)
    }

    companion object {
        /**
         * How far a stretch may go: a track at most this much faster or slower than the video (25 against 23.976
         * frames a second is 4%; a file for another cut can be further off, but not twice as fast).
         */
        const val STRETCH = 0.25

        /** Two lines closer than this are too close to stretch between: a small slip in either would stretch it wildly. */
        const val NEAREST_ANCHORS = 5_000L

        fun shift(by: Long) = Retime(1.0, by.toDouble())

        /**
         * The change [asked] means: a stretch keeps `fixed` where it is and takes `from` to `to`; when the two lines are
         * too close, or the stretch would be more than [STRETCH], it is a plain shift of `from` to `to`.
         */
        fun of(asked: Retiming): Retime = when (asked) {
            is Retiming.Shift -> shift(asked.by)
            is Retiming.Through -> {
                val shift = shift(asked.to - asked.from)
                val scale = (asked.to - asked.fixed).toDouble() / (asked.from - asked.fixed).toDouble()
                when {
                    abs(asked.from - asked.fixed) < NEAREST_ANCHORS || abs(asked.to - asked.fixed) < NEAREST_ANCHORS -> shift
                    scale !in (1.0 - STRETCH)..(1.0 + STRETCH) -> shift
                    else -> Retime(scale, asked.fixed - asked.fixed * scale)
                }
            }
        }
    }
}

object SubtitleFixes {
    /** The longest line kept, in characters. */
    const val LONGEST_TEXT = 500

    /**
     * [lines] with line [index] saying [text] instead: its breaks kept, spaces at the ends of each taken off; empty, the
     * line is taken out. Null when there is no such line.
     */
    fun withText(lines: List<SubtitleLine>, index: Int, text: String): List<SubtitleLine>? {
        if (index !in lines.indices) return null
        val cleaned = text.replace("\r\n", "\n")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .take(LONGEST_TEXT)
        return lines.toMutableList().apply {
            if (cleaned.isEmpty()) removeAt(index) else this[index] = this[index].copy(text = cleaned)
        }
    }

    /** "Lines moved 1.5 s later", "Lines moved 0.1 s earlier": what a shift of [by] milliseconds did. */
    fun moved(by: Long): String {
        if (by == 0L) return "Lines left where they were."
        val seconds = abs(by) / 1000.0
        val shown = if (by % 1000 == 0L) "%.0f".format(java.util.Locale.ROOT, seconds) else "%.1f".format(java.util.Locale.ROOT, seconds)
        return "Lines moved $shown s ${if (by > 0) "later" else "earlier"}."
    }
}
