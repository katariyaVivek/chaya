package com.chaya.app.streaming

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.Util
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every spelling of a stream's type reaches Media3 as the one it recognises. On 7movies the type detection gave,
 * `application/vnd.apple.mpegurl`, made Media3 save the 729-byte playlist as if it were the video.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StreamMimeTest {

    private val playlist = Uri.parse("https://img.example/tvshows/abc/index.m3u8")

    @Test
    fun `Media3 takes the apple spelling for a plain file, which is the bug`() {
        assertEquals(C.CONTENT_TYPE_OTHER, Util.inferContentTypeForUriAndMimeType(playlist, "application/vnd.apple.mpegurl"))
    }

    @Test
    fun `every HLS spelling becomes the one Media3 reads as HLS`() {
        for (type in listOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "Application/X-MpegURL; charset=utf-8")) {
            assertEquals(type, C.CONTENT_TYPE_HLS, Util.inferContentTypeForUriAndMimeType(playlist, StreamMime.forMedia3(type)))
        }
    }

    @Test
    fun `DASH is kept, other types pass through, and no type stays none`() {
        assertEquals(C.CONTENT_TYPE_DASH, Util.inferContentTypeForUriAndMimeType(playlist, StreamMime.forMedia3("application/dash+xml")))
        assertEquals("video/mp4", StreamMime.forMedia3("video/mp4"))
        assertNull(StreamMime.forMedia3(null))
    }
}
