package com.chaya.app.subtitles

/**
 * Subtitles put right from the player ([SubtitleFixes]), written to the file Chaya keeps with the video, so the player,
 * the transcript and the Library's search all read them. Each change keeps the file as it was before it, so Undo puts
 * it back exactly: the last [UNDOS] changes of each video, while the app runs, as on Windows. Plain Kotlin, no Android.
 */
class SubtitleEdits(private val store: SubtitleStore) {

    /** What the person asked to change: its new lines from the old, or null when it cannot be done. */
    fun interface Change {
        fun of(lines: List<SubtitleLine>): List<SubtitleLine>?
    }

    /** Each video's files as they were before each change, the latest last. */
    private val undos = HashMap<Long, ArrayDeque<Pair<String, String>>>()

    /**
     * Changes download [id]'s subtitles by [change], keeping them as they were for Undo. Their lines after; null when
     * the video has none, the change cannot be made, or the file could not be written (nothing changed then).
     */
    @Synchronized
    fun change(id: Long, change: Change): List<SubtitleLine>? {
        val (name, before) = store.raw(id) ?: return null
        val lines = Subtitles.parse(before)
        val changed = change.of(lines) ?: return null
        if (changed == lines) return lines
        if (!store.write(id, name, Subtitles.toSrt(changed))) return null
        val kept = undos.getOrPut(id) { ArrayDeque() }
        kept.addLast(name to before)
        if (kept.size > UNDOS) kept.removeFirst()
        return changed
    }

    /** Puts download [id]'s subtitles back as they were before its last change. Their lines after; null with nothing to undo. */
    @Synchronized
    fun undo(id: Long): List<SubtitleLine>? {
        val kept = undos[id] ?: return null
        val (name, before) = kept.removeLastOrNull() ?: return null
        if (kept.isEmpty()) undos.remove(id)
        if (!store.write(id, name, before)) {
            kept.addLast(name to before)
            undos[id] = kept
            return null
        }
        return Subtitles.parse(before)
    }

    /** How many of download [id]'s changes can be undone. */
    @Synchronized
    fun undoable(id: Long): Int = undos[id]?.size ?: 0

    /** A download deleted: its changes can no longer be undone. */
    @Synchronized
    fun forget(id: Long) {
        undos.remove(id)
    }

    companion object {
        /** The most changes of one video that can be undone. */
        const val UNDOS = 20
    }
}
