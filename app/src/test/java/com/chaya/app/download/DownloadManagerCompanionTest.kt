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
}
