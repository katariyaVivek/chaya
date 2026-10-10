package com.chaya.app.download

/**
 * Which finished downloads to announce with a "Download complete" notification, each once.
 *
 * The downloads list holds every download ever finished, and the download service starts afresh for each new
 * download. A service that announced whatever it found finished, remembering only what it had announced
 * itself, announced old downloads again with every new one. One of these lives as long as the app's process,
 * across services, and announces only a download seen running or one that finished after the app started.
 */
class CompletionNotices(private val appStartedAt: Long) {
    private val running = HashSet<Long>()
    private val announced = HashSet<Long>()

    /** The downloads in [tasks] that have just finished and have not been announced yet. */
    @Synchronized
    fun toAnnounce(tasks: List<DownloadTask>): List<DownloadTask> {
        val finished = ArrayList<DownloadTask>()
        for (task in tasks) {
            when (task.state) {
                DownloadState.DOWNLOADING, DownloadState.QUEUED -> {
                    running += task.id
                    // Running again (or a new download that reuses a removed one's number): announce it when done.
                    announced -= task.id
                }
                DownloadState.COMPLETED -> {
                    val new = task.id in running || task.updatedAt >= appStartedAt
                    if (new && announced.add(task.id)) finished += task
                    running -= task.id
                }
                else -> running -= task.id
            }
        }
        return finished
    }
}
