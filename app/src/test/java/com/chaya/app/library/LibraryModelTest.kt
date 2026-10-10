package com.chaya.app.library

import com.chaya.app.download.DownloadState
import com.chaya.app.download.DownloadTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the library shows: finished things only, a post's files on one tile, kinds, sites and search. */
class LibraryModelTest {

    private fun task(
        id: Long,
        title: String,
        fileName: String = "$title.mp4",
        mimeType: String? = "video/mp4",
        page: String = "https://www.youtube.com/watch?v=$id",
        state: DownloadState = DownloadState.COMPLETED,
        finishedAt: Long = id * 1000,
        groupKey: String? = null,
        url: String = "https://cdn.example/$id",
    ) = DownloadTask(
        id = id, url = url, pageUrl = page, fileName = fileName, mimeType = mimeType, state = state,
        updatedAt = finishedAt, title = title, groupKey = groupKey,
    )

    private val post = "https://www.instagram.com/p/abc/\nSunset"
    private val tasks = listOf(
        task(1, "Me at the zoo"),
        task(2, "Sunset (1 of 3)", "Sunset (1 of 3).jpg", "image/jpeg", "https://www.instagram.com/p/abc/", groupKey = post),
        task(3, "Sunset (2 of 3)", "Sunset (2 of 3).jpg", "image/jpeg", "https://www.instagram.com/p/abc/", groupKey = post),
        task(4, "Sunset (3 of 3)", "Sunset (3 of 3).mp4", "video/mp4", "https://www.instagram.com/p/abc/", groupKey = post),
        task(5, "A song", "A song.m4a", "audio/mp4", "https://www.tiktok.com/@a/video/5"),
        task(6, "Still going", state = DownloadState.DOWNLOADING),
        task(7, "Everything @someone posted", "someone.zip", "application/zip", "https://x.com/someone",
            url = "chaya-archive://x/someone"),
        task(8, "Cat", "Cat.webp", "image/webp", "https://x.com/someone/status/8"),
    )

    @Test
    fun `finished downloads only, newest first, a post's files on one tile in the order they were saved`() {
        val items = libraryItems(tasks)

        assertEquals(listOf("task:8", "task:7", "task:5", "post:$post", "task:1"), items.map { it.key })
        val tile = items.single { it is LibraryItem.Post }
        assertEquals(listOf(2L, 3L, 4L), tile.tasks.map { it.id })
        assertEquals("Sunset", tile.title)
        assertEquals(4000L, tile.finishedAt)
    }

    @Test
    fun `a post with one file left after filtering is an ordinary tile`() {
        val items = libraryItems(tasks, LibraryFilter(kind = FileKind.VIDEO))

        assertEquals(listOf("task:4", "task:1"), items.map { it.key })
    }

    @Test
    fun `kinds come from the type, then the name`() {
        assertEquals(FileKind.PICTURE, kindOf("image/jpeg", "x.bin"))
        assertEquals(FileKind.AUDIO, kindOf(null, "song.opus"))
        assertEquals(FileKind.VIDEO, kindOf("application/octet-stream", "clip.webm"))
        assertEquals(FileKind.ZIP, kindOf(null, "all.zip"))
        assertNull(kindOf("application/pdf", "doc.pdf"))
        assertEquals(FileKind.ZIP, kindOf(tasks[6]))
        // A stream is a video whatever its name.
        assertEquals(FileKind.VIDEO, kindOf(task(9, "Live", "Live", null, url = "https://cdn.example/master.m3u8")))
    }

    @Test
    fun `sites come from the page the download was found on`() {
        assertEquals(listOf(SourceSite.YOUTUBE, SourceSite.INSTAGRAM, SourceSite.TIKTOK, SourceSite.X, SourceSite.OTHER),
            listOf(tasks[0], tasks[1], tasks[4], tasks[7], task(10, "Elsewhere", page = "https://news.example/a")).map(::siteOf))
        assertEquals(SourceSite.YOUTUBE, siteOf(task(11, "Short", page = "https://m.youtube.com/shorts/x")))
        assertEquals(SourceSite.X, siteOf(task(12, "Old", page = "https://twitter.com/a/status/1")))
        assertEquals(SourceSite.OTHER, siteOf(task(13, "Lookalike", page = "https://notyoutube.com/x")))
    }

    @Test
    fun `kind, site and search narrow the library together`() {
        assertEquals(listOf("task:8", "post:$post"),
            libraryItems(tasks, LibraryFilter(kind = FileKind.PICTURE)).map { it.key })
        assertEquals(listOf("task:8", "task:7"), libraryItems(tasks, LibraryFilter(site = SourceSite.X)).map { it.key })
        assertEquals(listOf("task:8"),
            libraryItems(tasks, LibraryFilter(kind = FileKind.PICTURE, site = SourceSite.X)).map { it.key })
        assertEquals(listOf("task:1"), libraryItems(tasks, LibraryFilter(query = "  ZOO ")).map { it.key })
        assertTrue(libraryItems(tasks, LibraryFilter(query = "nothing like this")).isEmpty())
        assertTrue(LibraryFilter().isEmpty)
    }

    @Test
    fun `a post is named without its place, and unfinished work is counted for the grid`() {
        assertEquals("Sunset", postTitle(tasks[1]))
        assertEquals("Me at the zoo", postTitle(tasks[0]))
        assertEquals(1, unfinishedCount(tasks))
    }
}
