package com.chaya.app.home

import com.chaya.app.download.DownloadTask
import com.chaya.app.library.FileKind
import com.chaya.app.library.LibraryItem
import com.chaya.app.library.kindOf
import com.chaya.app.library.libraryItems
import com.chaya.app.ui.player.Watched

/**
 * What Home shows (Stage, as Chaya for Windows has it): the video left part-way filling the top, else the newest one, the newest saved
 * things after it, and how the downloads still going are getting on. Worked out from the downloads, never stored.
 *
 * [hidden] are downloads swiped away in the Library and still waiting out their Undo: not shown here either.
 */
data class HomeShelf(
    /** The video watched last and left part-way, else the newest saved one: Home's picture; null with none saved. */
    val hero: DownloadTask?,
    /** The newest saved things after it, as the Library's tiles. */
    val recent: List<LibraryItem>,
    /** The downloads still going, together; null with none. */
    val progress: Progress?,
    /** Where [hero] was left, when it was left part-way: Home says "Continue" instead of "Newest". */
    val heroWatched: Watched? = null,
) {
    /**
     * [running] downloads still going (running, waiting or paused). [fraction] is how far they are together, each
     * weighted by its size, from those whose size is known; null when none of them knows it yet (a stream, early on).
     */
    data class Progress(val running: Int, val fraction: Float?)

    val isEmpty: Boolean get() = hero == null && recent.isEmpty()

    companion object {
        /** As many as the row under the picture is worth scrolling: the Library has the rest. */
        const val RECENT = 8

        fun of(
            tasks: List<DownloadTask>,
            hidden: Set<Long> = emptySet(),
            watched: Map<Long, Watched> = emptyMap(),
        ): HomeShelf {
            val shown = tasks.filter { it.id !in hidden }
            val items = libraryItems(shown)
            val videos = items.mapNotNull { item ->
                (item as? LibraryItem.Single)?.task?.takeIf { kindOf(it) == FileKind.VIDEO }
            }
            val continued = Watched.toContinue(videos.map { it.id }, watched)
            val hero = videos.firstOrNull { it.id == continued } ?: videos.firstOrNull()
            val recent = items.filterNot { it is LibraryItem.Single && it.task.id == hero?.id }.take(RECENT)
            return HomeShelf(hero, recent, progressOf(shown), hero?.let { watched[it.id] }?.takeIf { it.partWay })
        }

        fun progressOf(tasks: List<DownloadTask>): Progress? {
            val going = tasks.filter { it.state.isUnfinished }
            if (going.isEmpty()) return null
            val sized = going.mapNotNull { task -> task.totalBytes?.takeIf { it > 0 }?.let { task to it } }
            val total = sized.sumOf { (_, size) -> size }
            val done = sized.sumOf { (task, size) -> task.downloadedBytes.coerceIn(0, size) }
            return Progress(going.size, if (total > 0) done.toFloat() / total else null)
        }
    }
}
