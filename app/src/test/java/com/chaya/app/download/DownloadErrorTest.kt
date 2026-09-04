package com.chaya.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Pins the classifier in [DownloadError.from]: every raw failure shape the
 * app can produce maps to exactly one taxonomy entry with the right
 * user message and retry affordance.
 */
class DownloadErrorTest {

    @Test
    fun `timeout and dns failures classify as retryable network errors`() {
        for (cause in listOf(
            SocketTimeoutException("timeout"),
            UnknownHostException("cdn.example.com"),
            ConnectException("connection refused"),
            InterruptedIOException("interrupted"),
        )) {
            val error = DownloadError.from(cause)
            assertTrue("expected Network for $cause", error is DownloadError.Network)
            assertEquals("Connection lost — check your network", error.userMessage)
            assertTrue(error.retryable)
        }
    }

    @Test
    fun `network cause wrapped in generic IOException still classifies as network`() {
        val wrapped = IOException("unexpected end of stream", UnknownHostException("cdn.example.com"))

        val error = DownloadError.from(wrapped)

        assertTrue(error is DownloadError.Network)
        assertTrue(error.retryable)
    }

    @Test
    fun `expired links and missing files are not retryable`() {
        for (code in intArrayOf(401, 403, 404)) {
            val error = DownloadError.from(IOException("HTTP $code: Forbidden"))

            assertEquals(DownloadError.HttpStatus(code), error)
            assertFalse("HTTP $code must not offer retry", error.retryable)
        }
        assertEquals(
            "Access denied — the link may have expired",
            DownloadError.from(IOException("HTTP 403: Forbidden")).userMessage,
        )
        assertEquals(
            "File not found on the server",
            DownloadError.from(IOException("HTTP 404: Not Found")).userMessage,
        )
    }

    @Test
    fun `server errors and rate limits stay retryable`() {
        for (code in intArrayOf(429, 500, 503)) {
            val error = DownloadError.from(IOException("HTTP $code: boom"))

            assertEquals(DownloadError.HttpStatus(code), error)
            assertTrue("HTTP $code should offer retry", error.retryable)
        }
    }

    @Test
    fun `full disk classifies as non-retryable storage error`() {
        val error = DownloadError.from(IOException("write failed: ENOSPC (No space left on device)"))

        assertEquals(DownloadError.StorageFull, error)
        assertEquals("Not enough storage space", error.userMessage)
        assertFalse(error.retryable)
    }

    @Test
    fun `unrecognized failures stay retryable unknowns`() {
        val error = DownloadError.from(RuntimeException("weird"))

        assertTrue(error is DownloadError.Unknown)
        assertEquals("Something went wrong", error.userMessage)
        assertTrue(error.retryable)
    }

    @Test
    fun `cancellation is never shown as a failure`() {
        val error = DownloadError.from(java.util.concurrent.CancellationException("cancelled"))

        assertEquals(DownloadError.Cancelled, error)
        assertFalse(error.retryable)
    }
}
