package com.chaya.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the `Content-Disposition` filename parser: servers vary wildly in
 * how they encode suggested filenames (plain, quoted, RFC 5987 UTF-8), and a
 * regression here silently reverts saved files to the URL-derived fallback
 * name in [DownloadManager.fileNameForMedia].
 */
class HttpDownloaderTest {

    @Test
    fun `null header returns null`() {
        assertNull(HttpDownloader.parseContentDisposition(null))
    }

    @Test
    fun `blank header returns null`() {
        assertNull(HttpDownloader.parseContentDisposition("   "))
    }

    @Test
    fun `plain unquoted filename is extracted`() {
        assertEquals(
            "video.mp4",
            HttpDownloader.parseContentDisposition("attachment; filename=video.mp4")
        )
    }

    @Test
    fun `quoted filename is extracted`() {
        assertEquals(
            "my video.mp4",
            HttpDownloader.parseContentDisposition("attachment; filename=\"my video.mp4\"")
        )
    }

    @Test
    fun `rfc5987 utf-8 filename star takes priority over plain filename`() {
        // Real servers often send both — filename* is the more correct one
        // and must win even when a plain ASCII fallback is also present.
        val header = "attachment; filename=\"fallback.mp4\"; filename*=UTF-8''caf%C3%A9.mp4"
        assertEquals("café.mp4", HttpDownloader.parseContentDisposition(header))
    }

    @Test
    fun `malformed header without filename returns null`() {
        assertNull(HttpDownloader.parseContentDisposition("attachment"))
    }

    @Test
    fun `filename with trailing semicolon params is isolated`() {
        assertEquals(
            "clip.webm",
            HttpDownloader.parseContentDisposition("attachment; filename=clip.webm; size=123")
        )
    }
}
