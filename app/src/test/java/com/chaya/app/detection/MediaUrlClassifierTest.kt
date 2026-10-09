package com.chaya.app.detection

import com.chaya.app.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins which URLs count as stream pieces, ads, or whole files before they reach the sheet. */
class MediaUrlClassifierTest {

    @Test
    fun `stream pieces are recognized by extension and by name`() {
        listOf(
            "https://cdn.example/x/url_3/193039199_mp4_h264_aac_hd_7.ts",
            "https://cdn.example/dash/chunk-stream0-00001.m4s",
            "https://cdn.example/v/seg-12-v1-a1.mp4",
            "https://cdn.example/v/segment_0003.mp4",
            "https://cdn.example/v/frag12.mp4",
            "https://cdn.example/v/init.mp4",
            "https://cdn.example/v/video_init.mp4?token=1",
        ).forEach { assertTrue(it, MediaUrlClassifier.isSegment(it)) }
    }

    @Test
    fun `whole files are not pieces even when their names look similar`() {
        listOf(
            "https://cdn.example/BigBuckBunny.mp4",
            "https://cdn.example/segmentation-tutorial.mp4",
            "https://cdn.example/movie-part2.mp4",
            "https://cdn.example/hls/master.m3u8",
            "https://cdn.example/",
        ).forEach { assertFalse(it, MediaUrlClassifier.isSegment(it)) }
    }

    @Test
    fun `byte-range parameters are dropped and other parameters kept`() {
        assertEquals(
            "https://video.fbcdn.example/clip.mp4?_nc_cat=1",
            MediaUrlClassifier.withoutRangeParams("https://video.fbcdn.example/clip.mp4?_nc_cat=1&bytestart=0&byteend=999"),
        )
        assertEquals(
            "https://cdn.example/clip.mp4",
            MediaUrlClassifier.withoutRangeParams("https://cdn.example/clip.mp4?range=0-1023#t=10"),
        )
        assertEquals(
            "https://cdn.example/clip.mp4?range=full",
            MediaUrlClassifier.withoutRangeParams("https://cdn.example/clip.mp4?range=full"),
        )
    }

    @Test
    fun `kinds come from the path first and the MIME type second`() {
        assertEquals(MediaKind.STREAM, MediaUrlClassifier.kindOf("https://cdn.example/master.m3u8?token=1", null))
        assertEquals(MediaKind.STREAM, MediaUrlClassifier.kindOf("https://cdn.example/manifest.mpd", null))
        assertEquals(MediaKind.STREAM, MediaUrlClassifier.kindOf("https://cdn.example/live", "application/x-mpegURL"))
        assertEquals(MediaKind.AUDIO, MediaUrlClassifier.kindOf("https://cdn.example/song.mp3", null))
        assertEquals(MediaKind.AUDIO, MediaUrlClassifier.kindOf("https://cdn.example/track", "audio/mpeg"))
        assertEquals(MediaKind.VIDEO, MediaUrlClassifier.kindOf("https://cdn.example/clip.mp4", "video/mp4"))
        assertEquals(MediaKind.SEGMENT, MediaUrlClassifier.kindOf("https://cdn.example/1.ts", "video/mp2t"))
    }

    @Test
    fun `host and path ignore credentials ports queries and fragments`() {
        assertEquals("cdn.example", MediaUrlClassifier.hostOf("https://user@CDN.example:8443/a/b.mp4?x=1#y"))
        assertEquals("/a/b.mp4", MediaUrlClassifier.pathOf("https://cdn.example/a/b.mp4?x=1#y"))
        assertEquals("/", MediaUrlClassifier.pathOf("https://cdn.example"))
    }

    @Test
    fun `ad networks and ad paths are recognized without catching ordinary sites`() {
        assertTrue(AdHosts.isAdUrl("https://s0.2mdn.net/videoplayback/creative.mp4"))
        assertTrue(AdHosts.isAdUrl("https://pubads.g.doubleclick.net/gampad/ads?iu=1"))
        assertTrue(AdHosts.isAdUrl("https://video.site.example/vast/preroll-15s.mp4"))
        assertFalse(AdHosts.isAdUrl("https://cdn.site.example/videos/launch-event.mp4"))
        assertFalse(AdHosts.isAdUrl("https://www.adobe.com/media/tutorial.mp4"))
        assertFalse(AdHosts.isAdUrl("https://site.example/downloads/headlines.mp4"))
    }
}
