package com.chaya.app.download

/**
 * Where a download is in its life. [DownloadManager] makes these moves:
 *
 * ```
 *  QUEUED ──► DOWNLOADING ◄──► PAUSED
 *    │           │   ▲            │
 *    │           │   └─ resume ───┤ (also from FAILED and CANCELLED: Retry, Resume)
 *    │           ▼                ▼
 *    └──► CANCELLED   FAILED   COMPLETED ──► DOWNLOADING (Save as MP4, for a stream kept in the cache)
 * ```
 *
 * New tasks start at DOWNLOADING; QUEUED is the default of a task and a stored row, never set on its way.
 * A task the app was killed during comes back PAUSED ([DownloadManager.restore]). What a DOWNLOADING
 * task is doing inside (a picture, its sound, the join, an archive's list, saving as MP4) is worked out
 * from its files, and shown on its card as [DownloadTask.step], never stored here.
 *
 * Ask the properties below rather than listing states again, so every screen and the manager agree.
 */
enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED;

    /** Running or about to: what keeps the foreground service up, and what Cancel stops. */
    val isActive: Boolean get() = this == QUEUED || this == DOWNLOADING

    /** Neither done nor given up on: the list's "In progress", and what the grid counts as unfinished. */
    val isUnfinished: Boolean get() = isActive || this == PAUSED

    /** Stopped part way, so Resume or Retry carries on from what was saved. */
    val canResume: Boolean get() = this == PAUSED || this == FAILED || this == CANCELLED
}
