package com.chaya.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each download that finishes is announced once, and downloads finished in the past never again. */
class CompletionNoticesTest {

    private val appStartedAt = 1_000_000L

    private fun task(id: Long, state: DownloadState, updatedAt: Long = appStartedAt + 10) = DownloadTask(
        id = id, url = "https://cdn.example/$id.mp4", pageUrl = null, fileName = "$id.mp4", mimeType = "video/mp4",
        filePath = null, state = state, createdAt = updatedAt, updatedAt = updatedAt,
    )

    @Test
    fun `downloads finished before the app started are never announced`() {
        val notices = CompletionNotices(appStartedAt)
        val old = listOf(task(1, DownloadState.COMPLETED, updatedAt = 5), task(2, DownloadState.COMPLETED, updatedAt = 6))

        assertTrue(notices.toAnnounce(old).isEmpty())
        // A new download starts and finishes: only it is announced.
        assertTrue(notices.toAnnounce(old + task(3, DownloadState.DOWNLOADING)).isEmpty())
        assertEquals(listOf(3L), notices.toAnnounce(old + task(3, DownloadState.COMPLETED)).map { it.id })
    }

    @Test
    fun `a finished download is announced once, however often the list changes or the service restarts`() {
        val notices = CompletionNotices(appStartedAt)
        notices.toAnnounce(listOf(task(1, DownloadState.DOWNLOADING)))

        assertEquals(listOf(1L), notices.toAnnounce(listOf(task(1, DownloadState.COMPLETED))).map { it.id })
        assertTrue(notices.toAnnounce(listOf(task(1, DownloadState.COMPLETED))).isEmpty())
        // The next download brings the service back: the first one stays announced.
        assertTrue(notices.toAnnounce(listOf(task(1, DownloadState.COMPLETED), task(2, DownloadState.QUEUED))).isEmpty())
    }

    @Test
    fun `each download is announced as it finishes, while others still run`() {
        val notices = CompletionNotices(appStartedAt)
        notices.toAnnounce(listOf(task(1, DownloadState.DOWNLOADING), task(2, DownloadState.DOWNLOADING)))

        assertEquals(
            listOf(1L),
            notices.toAnnounce(listOf(task(1, DownloadState.COMPLETED), task(2, DownloadState.DOWNLOADING))).map { it.id },
        )
        assertEquals(
            listOf(2L),
            notices.toAnnounce(listOf(task(1, DownloadState.COMPLETED), task(2, DownloadState.COMPLETED))).map { it.id },
        )
    }

    @Test
    fun `a download that finishes too fast to be seen running is still announced`() {
        val notices = CompletionNotices(appStartedAt)
        assertEquals(listOf(7L), notices.toAnnounce(listOf(task(7, DownloadState.COMPLETED))).map { it.id })
    }

    @Test
    fun `failed and cancelled downloads are not announced as finished`() {
        val notices = CompletionNotices(appStartedAt)
        notices.toAnnounce(listOf(task(1, DownloadState.DOWNLOADING), task(2, DownloadState.DOWNLOADING)))
        assertTrue(notices.toAnnounce(listOf(task(1, DownloadState.FAILED), task(2, DownloadState.CANCELLED))).isEmpty())
    }

    @Test
    fun `a download that runs again is announced again when it finishes`() {
        val notices = CompletionNotices(appStartedAt)
        notices.toAnnounce(listOf(task(1, DownloadState.DOWNLOADING)))
        notices.toAnnounce(listOf(task(1, DownloadState.COMPLETED)))

        notices.toAnnounce(listOf(task(1, DownloadState.DOWNLOADING)))
        assertEquals(listOf(1L), notices.toAnnounce(listOf(task(1, DownloadState.COMPLETED))).map { it.id })
    }
}
