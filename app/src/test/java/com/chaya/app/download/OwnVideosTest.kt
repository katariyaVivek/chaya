package com.chaya.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A video of the person's own, listed as a finished download that Chaya never deletes. */
class OwnVideosTest {

    @Test
    fun `an own file is listed done, named for its file, at the address the picker gave`() {
        val uri = "content://com.android.providers.media.documents/document/video%3A42"
        val task = OwnVideos.task(7, uri, "Holiday 2026.mp4", "video/mp4", 12_345, now = 1_000)

        assertTrue(task.isOwn)
        assertEquals(uri, task.ownUri)
        assertEquals(uri, task.exportedUri)
        assertNull(task.filePath)
        assertEquals(DownloadState.COMPLETED, task.state)
        assertEquals("Holiday 2026", task.title)
        assertEquals("Holiday 2026.mp4", task.fileName)
        assertEquals(12_345L, task.totalBytes)
        // Not a stream, not an archive: it plays as a file does
        assertFalse(task.isStream)
        assertFalse(task.isArchive)
        assertFalse(task.canSaveAsFile)
    }

    @Test
    fun `a download is not one of the person's own`() {
        val download = DownloadTask(id = 1, url = "https://cdn.example/a.mp4", pageUrl = null, fileName = "a.mp4", mimeType = "video/mp4")
        assertFalse(download.isOwn)
        assertNull(download.ownUri)
    }

    @Test
    fun `a title is the file's name without its extension`() {
        assertEquals("Talk", OwnVideos.titleOf("Talk.mp4"))
        assertEquals("my.talk", OwnVideos.titleOf("my.talk.mkv"))
        assertEquals(".hidden", OwnVideos.titleOf(".hidden"))
        assertEquals("Video", OwnVideos.titleOf("  "))
    }

    @Test
    fun `videos and sounds play, other files do not`() {
        assertTrue(OwnVideos.plays("video/mp4"))
        assertTrue(OwnVideos.plays("audio/mpeg"))
        assertFalse(OwnVideos.plays("image/jpeg"))
        assertFalse(OwnVideos.plays(null))
    }
}
