package com.chaya.app.home

import com.chaya.app.download.DownloadState
import com.chaya.app.download.DownloadTask
import com.chaya.app.ui.player.Watched
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Home shows: the newest saved video, the newest saved things after it, and the downloads still going. */
class HomeShelfTest {

    private fun task(
        id: Long,
        title: String,
        fileName: String = "$title.mp4",
        mimeType: String? = "video/mp4",
        state: DownloadState = DownloadState.COMPLETED,
        finishedAt: Long = id * 1000,
        groupKey: String? = null,
        done: Long = 0,
        total: Long? = null,
    ) = DownloadTask(
        id = id, url = "https://cdn.example/$id", pageUrl = "https://www.youtube.com/watch?v=$id", fileName = fileName,
        mimeType = mimeType, state = state, updatedAt = finishedAt, title = title, groupKey = groupKey,
        downloadedBytes = done, totalBytes = total,
    )

    private val post = "https://www.instagram.com/p/abc/\nSunset"

    @Test
    fun `the newest saved video leads, and the rest follow newest first, without it`() {
        val shelf = HomeShelf.of(
            listOf(
                task(1, "Me at the zoo"),
                task(2, "Last light on the lake"),
                task(3, "Cat", "Cat.webp", "image/webp"),
                task(4, "A song", "A song.m4a", "audio/mp4"),
            ),
        )

        assertEquals(2L, shelf.hero?.id)
        assertEquals(listOf("task:4", "task:3", "task:1"), shelf.recent.map { it.key })
    }

    @Test
    fun `the video left part-way leads instead of the newest, and one watched to the end does not`() {
        val tasks = listOf(task(1, "Me at the zoo"), task(2, "Last light on the lake"), task(3, "Harbour at dawn"))
        val watched = mapOf(
            1L to Watched(atMs = 252_000, lengthMs = 760_000, stoppedAt = 50),
            // Watched later, but to its end: nothing to continue
            3L to Watched(atMs = 595_000, lengthMs = 600_000, stoppedAt = 90),
        )

        val shelf = HomeShelf.of(tasks, watched = watched)

        assertEquals(1L, shelf.hero?.id)
        assertEquals(252_000L, shelf.heroWatched?.resumeAtMs)
        assertEquals(listOf("task:3", "task:2"), shelf.recent.map { it.key })
        // Nothing left part-way: the newest leads, and Home says so
        val newest = HomeShelf.of(tasks, watched = mapOf(3L to watched.getValue(3L)))
        assertEquals(3L, newest.hero?.id)
        assertNull(newest.heroWatched)
    }

    @Test
    fun `a post's files are one item, and a video inside a post does not lead`() {
        val shelf = HomeShelf.of(
            listOf(
                task(1, "Me at the zoo"),
                task(2, "Sunset (1 of 2)", "Sunset (1 of 2).jpg", "image/jpeg", groupKey = post),
                task(3, "Sunset (2 of 2)", "Sunset (2 of 2).mp4", groupKey = post),
            ),
        )

        assertEquals(1L, shelf.hero?.id)
        assertEquals(listOf("post:$post"), shelf.recent.map { it.key })
    }

    @Test
    fun `only what is saved is shown, and what is waiting out its Undo is not`() {
        val shelf = HomeShelf.of(
            listOf(task(1, "Saved"), task(2, "Gone", finishedAt = 9000), task(3, "Going", state = DownloadState.DOWNLOADING)),
            hidden = setOf(2L),
        )

        assertEquals(1L, shelf.hero?.id)
        assertTrue(shelf.recent.isEmpty())
    }

    @Test
    fun `with nothing saved there is no picture and no row`() {
        val shelf = HomeShelf.of(listOf(task(1, "Going", state = DownloadState.PAUSED)))

        assertNull(shelf.hero)
        assertTrue(shelf.isEmpty)
    }

    @Test
    fun `the row holds the newest few, and the Library has the rest`() {
        val shelf = HomeShelf.of((1L..20L).map { task(it, "Picture $it", "Picture $it.jpg", "image/jpeg") })

        assertNull(shelf.hero)
        assertEquals(HomeShelf.RECENT, shelf.recent.size)
        assertEquals("task:20", shelf.recent.first().key)
    }

    @Test
    fun `downloads still going are counted, and how far they are is weighted by size`() {
        val progress = HomeShelf.progressOf(
            listOf(
                task(1, "Big", state = DownloadState.DOWNLOADING, done = 50, total = 100),
                task(2, "Small", state = DownloadState.PAUSED, done = 0, total = 300),
                task(3, "Done", done = 900, total = 900),
            ),
        )

        assertEquals(2, progress?.running)
        assertEquals(50f / 400f, progress?.fraction ?: -1f, 0.0001f)
    }

    @Test
    fun `a download that does not know its size yet is counted but leaves how far to those that do`() {
        val stream = task(1, "Stream", state = DownloadState.DOWNLOADING, done = 12_000, total = null)
        assertEquals(HomeShelf.Progress(1, null), HomeShelf.progressOf(listOf(stream)))

        val both = HomeShelf.progressOf(listOf(stream, task(2, "File", state = DownloadState.QUEUED, done = 25, total = 100)))
        assertEquals(2, both?.running)
        assertEquals(0.25f, both?.fraction ?: -1f, 0.0001f)
    }

    @Test
    fun `nothing going, no progress`() {
        assertNull(HomeShelf.progressOf(listOf(task(1, "Saved"), task(2, "Failed", state = DownloadState.FAILED))))
    }
}
