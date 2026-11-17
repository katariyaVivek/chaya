package com.chaya.app.subtitles

import kotlin.math.roundToLong

/**
 * A video's chapters, as its maker named them on the site (YouTube's, from the times in the description), which the
 * engine lists with the video (`chaya_engine.extract._chapters`). Ported from Chaya for Windows
 * (`desktop/core/src/chapters.rs`): kept with the saved video ([SubtitleStore]) and marked on the player's seek bar.
 * Plain Kotlin, no Android, so it is tested on the JVM.
 */

/** A chapter: from [start] to [end], in milliseconds, and its title. */
data class Chapter(val start: Long, val end: Long, val title: String)

/** A chapter as the engine lists it, in seconds, before it is put right: any part may be missing. */
data class ListedChapter(val start: Double?, val end: Double?, val title: String?)

object Chapters {
    /** The most chapters kept: more is not a video's chapters but a list of something else. */
    const val MOST = 500

    /** The longest title kept, in characters. */
    const val LONGEST_TITLE = 100

    /**
     * The chapters the engine listed, put right: in order of their start, each ending where the next begins at the
     * latest, none past the video's end ([durationSeconds], when it is known), one without a title named for its place
     * ("Chapter 3"). A video with fewer than two has none: one chapter the length of the video marks nothing.
     */
    fun read(listed: List<ListedChapter>, durationSeconds: Double?): List<Chapter> {
        val length = durationSeconds?.takeIf { it.isFinite() && it > 0 }?.let(::ms)
        val sorted = listed
            .mapNotNull { chapter ->
                val start = chapter.start?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
                val end = chapter.end?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
                // Any run of white space is one space, a no-break space or a line break too, as on Windows
                val title = Subtitles.words(chapter.title.orEmpty()).joinToString(" ")
                Chapter(ms(start), ms(end), title.take(LONGEST_TITLE))
            }
            .sortedBy { it.start }
            // Two that start together are one: the first listed stays
            .distinctBy { it.start }
        val chapters = sorted
            .mapIndexed { index, chapter ->
                var end = chapter.end
                sorted.getOrNull(index + 1)?.let { next -> end = minOf(end, next.start) }
                length?.let { end = minOf(end, it) }
                chapter.copy(end = end)
            }
            .filter { it.end > it.start }
            .take(MOST)
        if (chapters.size < 2) return emptyList()
        return chapters.mapIndexed { index, chapter ->
            if (chapter.title.isEmpty()) chapter.copy(title = "Chapter ${index + 1}") else chapter
        }
    }

    /** The chapter playing at [atMs], by its index; null before the first or after the last. */
    fun at(chapters: List<Chapter>, atMs: Long): Int? {
        val index = chapters.indexOfLast { it.start <= atMs }
        return index.takeIf { it >= 0 && atMs < chapters[it].end }
    }

    /**
     * Chapters as Chaya keeps them in a file: one a line, its start, end and title parted by tabs. A title never holds
     * a tab or a line break ([read] folds white space), so nothing needs escaping.
     */
    fun toText(chapters: List<Chapter>): String =
        chapters.joinToString("") { "${it.start}\t${it.end}\t${Subtitles.words(it.title).joinToString(" ")}\n" }

    /** Chapters read back from [toText]; a line that is not one is left out, and fewer than two are none. */
    fun fromText(text: String): List<Chapter> {
        val chapters = text.lineSequence().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val start = parts[0].toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            val end = parts[1].toLongOrNull()?.takeIf { it > start } ?: return@mapNotNull null
            Chapter(start, end, parts[2])
        }.toList()
        return if (chapters.size < 2) emptyList() else chapters
    }

    private fun ms(seconds: Double): Long = (seconds * 1000).roundToLong()
}
