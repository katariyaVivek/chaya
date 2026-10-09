package com.chaya.app.download

import com.chaya.app.model.DetectedMedia
import com.chaya.app.model.DetectionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The naming/classification helpers on [DownloadManager]'s companion decide
 * where every file lands and whether it goes through the HTTP path or the
 * Media3 stream path — a mistake here silently corrupts saved filenames or
 * routes a plain MP4 into the streaming downloader (and vice versa).
 */
class DownloadManagerCompanionTest {

    // ---- sanitize ---- //

    @Test
    fun `sanitize strips filesystem-illegal characters`() {
        assertEquals("my_video_name", DownloadManager.sanitize("my/video:name"))
    }

    @Test
    fun `sanitize trims whitespace and truncates to 100 chars`() {
        val long = "a".repeat(150)
        assertEquals(100, DownloadManager.sanitize(long).length)
    }

    @Test
    fun `sanitize falls back to a generated name when input is empty`() {
        assertTrue(DownloadManager.sanitize("   ").startsWith("media_"))
    }

    // ---- sanitizeKeepingExtension ---- //

    @Test
    fun `a long title is shortened instead of losing its extension`() {
        val name = DownloadManager.sanitizeKeepingExtension("T".repeat(150) + " (720p).mp4")

        assertEquals(100, name.length)
        assertTrue(name.endsWith(".mp4"))
    }

    @Test
    fun `a name that already fits is left alone apart from illegal characters`() {
        assertEquals("Me at the zoo (240p).mp4", DownloadManager.sanitizeKeepingExtension("Me at the zoo (240p).mp4"))
        assertEquals("a_b_c.mp4", DownloadManager.sanitizeKeepingExtension("a/b:c.mp4"))
    }

    @Test
    fun `dots in the title are not mistaken for the extension`() {
        val name = DownloadManager.sanitizeKeepingExtension("Wow... what a day (720p).mp4")

        assertEquals("Wow... what a day (720p).mp4", name)
    }

    @Test
    fun `a name with no extension is simply sanitized and cut`() {
        assertEquals(100, DownloadManager.sanitizeKeepingExtension("a".repeat(150)).length)
        assertEquals("Version 2.0 is out", DownloadManager.sanitizeKeepingExtension("Version 2.0 is out"))
        assertEquals("trailing dot.", DownloadManager.sanitizeKeepingExtension("trailing dot."))
    }

    @Test
    fun `an empty title still gets a generated name that keeps the extension`() {
        val name = DownloadManager.sanitizeKeepingExtension("   .mp4")

        assertTrue(name.endsWith(".mp4"))
        assertTrue(name.startsWith("media_"))
    }

    // ---- fileNameForMedia ---- //

    private fun media(url: String, mimeType: String? = null) = DetectedMedia(
        url = url,
        pageUrl = null,
        mimeType = mimeType,
        source = DetectionSource.NETWORK
    )

    @Test
    fun `fileNameForMedia uses the last path segment when it has an extension`() {
        val name = DownloadManager.fileNameForMedia(media("https://cdn.example.com/videos/clip.mp4"))
        assertEquals("clip.mp4", name)
    }

    @Test
    fun `fileNameForMedia ignores query string and fragment when reading the last segment`() {
        val name = DownloadManager.fileNameForMedia(
            media("https://cdn.example.com/clip.mp4?token=abc#t=10")
        )
        assertEquals("clip.mp4", name)
    }

    @Test
    fun `fileNameForMedia synthesizes a video extension when the url has none`() {
        val name = DownloadManager.fileNameForMedia(
            media("https://cdn.example.com/stream?id=42", mimeType = "video/mp4")
        )
        assertTrue("expected .mp4 fallback, got $name", name.endsWith(".mp4"))
    }

    @Test
    fun `fileNameForMedia synthesizes an audio extension when the url has none`() {
        val name = DownloadManager.fileNameForMedia(
            media("https://cdn.example.com/track?id=1", mimeType = "audio/mpeg")
        )
        assertTrue("expected .mp3 fallback, got $name", name.endsWith(".mp3"))
    }

    @Test
    fun `fileNameForMedia synthesizes a ts extension for manifest mime without url extension`() {
        val name = DownloadManager.fileNameForMedia(
            media("https://cdn.example.com/manifest?id=1", mimeType = "application/vnd.apple.mpegurl")
        )
        assertTrue("expected .ts fallback, got $name", name.endsWith(".ts"))
    }

    // ---- isStream / isStreamingUrl ---- //

    @Test
    fun `isStream true for m3u8 extension regardless of mime`() {
        assertTrue(DownloadManager.isStream("https://cdn.example.com/index.m3u8", null))
    }

    @Test
    fun `isStream true for mpd extension`() {
        assertTrue(DownloadManager.isStream("https://cdn.example.com/manifest.mpd", null))
    }

    @Test
    fun `isStream true for streaming mime without extension`() {
        assertTrue(DownloadManager.isStream("https://cdn.example.com/live", "application/dash+xml"))
    }

    @Test
    fun `isStream false for a plain mp4 url and mime`() {
        assertFalse(DownloadManager.isStream("https://cdn.example.com/clip.mp4", "video/mp4"))
    }

    @Test
    fun `isStream false when both url and mime are unrelated`() {
        assertFalse(DownloadManager.isStream("https://cdn.example.com/page.html", "text/html"))
    }

    @Test
    fun `isStream true for signed manifests and uppercase mpegURL mime`() {
        assertTrue(DownloadManager.isStream("https://cdn.example.com/master.m3u8?token=abc", null))
        assertTrue(DownloadManager.isStream("https://cdn.example.com/live", "application/x-mpegURL"))
    }

    // ---- titled downloads (media sheet names) ---- //

    @Test
    fun `fileNameForMedia uses the sheet title and saves streams as mp4`() {
        val name = DownloadManager.fileNameForMedia(
            media("https://cdn.example.com/x36xhzz/x36xhzz.m3u8").copy(suggestedName = "Big Buck Bunny (720p)")
        )
        assertEquals("Big Buck Bunny (720p).mp4", name)
    }

    @Test
    fun `fileNameForMedia keeps a titled file's own extension`() {
        val name = DownloadManager.fileNameForMedia(
            media("https://cdn.example.com/a8f3b2c9.webm?sig=1", "video/webm").copy(suggestedName = "Interview")
        )
        assertEquals("Interview.webm", name)
    }

    @Test
    fun `fileNameForMedia derives a titled extension from the mime type when the url has none`() {
        val name = DownloadManager.fileNameForMedia(
            media("https://cdn.example.com/track?id=1", "audio/mp4").copy(suggestedName = "Episode 12")
        )
        assertEquals("Episode 12.m4a", name)
    }
}
