package com.chaya.app.diagnostics

import com.chaya.app.download.DownloadError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the privacy boundary: no raw query string or fragment may reach the
 * log through [ChayaEvent.scrubbed], and every event formats to a readable
 * single line for the export.
 */
class ChayaEventTest {

    @Test
    fun `scrubbed strips query and fragment but keeps path`() {
        assertEquals(
            "https://cdn.example.com/media/abc123",
            ChayaEvent.scrubbed("https://cdn.example.com/media/abc123?sig=secret&token=abc#frag"),
        )
        assertEquals(
            "https://media.example.com/watch/v",
            ChayaEvent.scrubbed("https://media.example.com/watch/v?session=xyz"),
        )
    }

    @Test
    fun `scrubbed passes clean urls through and tolerates blanks`() {
        assertEquals(
            "https://cdn.example.com/clip.mp4",
            ChayaEvent.scrubbed("https://cdn.example.com/clip.mp4"),
        )
        assertNull(ChayaEvent.scrubbed(null))
        assertNull(ChayaEvent.scrubbed("  "))
    }

    @Test
    fun `failed event carries kind and retryable, not raw text`() {
        val error = DownloadError.HttpStatus(403)

        val event = ChayaEvent.DownloadFailed(
            taskId = 7,
            errorKind = error::class.simpleName ?: "?",
            retryable = error.retryable,
        )

        assertEquals(7L, event.taskId)
        assertEquals("HttpStatus", event.errorKind)
        assertTrue(!event.retryable)
    }

    @Test
    fun `media event records source and mime for coverage counts`() {
        val event = ChayaEvent.MediaDetected(
            url = ChayaEvent.scrubbed("https://cdn.example.com/a.mp4?sig=s")!!,
            source = "NETWORK",
            mimeType = "video/mp4",
        )

        assertEquals("https://cdn.example.com/a.mp4", event.url)
        assertTrue(!event.url.contains("sig="))
    }
}
