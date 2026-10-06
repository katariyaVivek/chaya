package com.chaya.app.detection

import com.chaya.app.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the naming rules that replace raw URL segments with titles people recognize. */
class MediaNamerTest {

    @Test
    fun `site suffix is stripped from page titles`() {
        assertEquals("Big Buck Bunny", MediaNamer.cleanPageTitle("Big Buck Bunny - YouTube", null, "www.youtube.com"))
        assertEquals("Watch live", MediaNamer.cleanPageTitle("Watch live | NDTV News", "NDTV News", "www.ndtv.com"))
    }

    @Test
    fun `a short content word inside the site name is kept`() {
        assertEquals("News", MediaNamer.cleanPageTitle("News - NDTV News", "NDTV News", "www.ndtv.com"))
    }

    @Test
    fun `a title that is only the site name yields nothing`() {
        assertNull(MediaNamer.cleanPageTitle("YouTube", null, "www.youtube.com"))
    }

    @Test
    fun `titles without separators are kept whole`() {
        assertEquals("hls.js demo", MediaNamer.cleanPageTitle("hls.js demo", null, "hlsjs.video-dev.org"))
    }

    @Test
    fun `readable filenames are split into words`() {
        assertEquals("Big Buck Bunny", MediaNamer.humanFileName("https://cdn.example/BigBuckBunny.mp4"))
        assertEquals("Sound Helix Song 1", MediaNamer.humanFileName("https://cdn.example/SoundHelix-Song-1.mp3"))
    }

    @Test
    fun `hashes ids and packaging jargon are never shown as names`() {
        assertNull(MediaNamer.humanFileName("https://cdn.example/193039199_mp4_h264_aac_hd_7.ts"))
        assertNull(MediaNamer.humanFileName("https://cdn.example/x36xhzz/x36xhzz.m3u8"))
        assertNull(MediaNamer.humanFileName("https://cdn.example/hls/master.m3u8"))
        assertNull(MediaNamer.humanFileName("https://cdn.example/v/a8f3b2c9d0e1.mp4"))
        assertNull(MediaNamer.humanFileName("https://cdn.example/v/"))
    }

    @Test
    fun `durations read like a player clock`() {
        assertEquals("0:15", MediaNamer.formatDuration(15.0))
        assertEquals("1:42", MediaNamer.formatDuration(102.4))
        assertEquals("1:02:03", MediaNamer.formatDuration(3723.0))
    }

    @Test
    fun `file base names carry the quality and stay within the length cap`() {
        assertEquals("Big Buck Bunny (720p)", MediaNamer.fileBaseName("Big  Buck\nBunny", 720))
        assertEquals("Song", MediaNamer.fileBaseName("Song", null))
        val long = MediaNamer.fileBaseName("x".repeat(200), 1080)
        assertTrue(long.length <= 80)
        assertTrue(long.endsWith("(1080p)"))
    }

    @Test
    fun `ad subtitles lead with Ad and end with the serving host`() {
        val subtitle = MediaNamer.subtitle(
            url = "https://s0.2mdn.net/videoplayback/creative.mp4",
            kind = MediaKind.VIDEO,
            isAd = true,
            durationSeconds = 15.0,
            videoHeight = 360,
            mimeType = "video/mp4",
        )
        assertEquals("Ad · 0:15 · 360p · MP4 · s0.2mdn.net", subtitle)
    }

    @Test
    fun `a bare media document is named from its file`() {
        val title = MediaNamer.primaryTitle(
            url = "https://cdn.example/sample/BigBuckBunny.mp4",
            player = null,
            pageMeta = null,
            matchesPageVideo = false,
            fallbackPageTitle = "BigBuckBunny.mp4",
            pageUrl = "https://cdn.example/sample/BigBuckBunny.mp4",
        )
        assertEquals("Big Buck Bunny", title)
    }
}
