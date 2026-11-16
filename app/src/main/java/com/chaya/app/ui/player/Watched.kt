package com.chaya.app.ui.player

/**
 * Where a saved video was left (Stage): how far in, how long it is, and when it was stopped (wall-clock milliseconds),
 * as Chaya for Windows keeps it (`video::VideoData`). Kept per download by [PlaybackPositions].
 */
data class Watched(val atMs: Long, val lengthMs: Long, val stoppedAt: Long) {

    /** Where it plays from next: where it was left, unless that was the very start or it was watched to the end. */
    val resumeAtMs: Long
        get() = when {
            atMs < START_MS -> 0
            lengthMs > 0 && atMs > lengthMs - END_MS -> 0
            else -> atMs
        }

    /** Stopped part way, so Home offers to carry on with it. */
    val partWay: Boolean get() = resumeAtMs > 0

    companion object {
        /** Less than this in, it starts again from the start. */
        const val START_MS = 5_000L

        /** Less than this from the end, it counts as watched, and starts again from the start. */
        const val END_MS = 10_000L

        /**
         * The video Home carries on with: of [saved] (the ids of saved videos), the one stopped part way most
         * recently; null with none.
         */
        fun toContinue(saved: Collection<Long>, watched: Map<Long, Watched>): Long? =
            saved.mapNotNull { id -> watched[id]?.takeIf { it.partWay }?.let { id to it } }
                .maxWithOrNull(compareBy({ it.second.stoppedAt }, { it.first }))
                ?.first
    }
}

/** "4:12", or "1:02:05" past an hour. */
fun clockOf(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    fun two(n: Long) = n.toString().padStart(2, '0')
    return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
}
