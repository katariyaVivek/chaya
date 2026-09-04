package com.chaya.app.detection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Proves unknown media endpoints are verified with a real HEAD exchange
 * before the browser presents them as downloadable media.
 */
class ContentTypeSnifferTest {

    /** Owns the ephemeral server whose request log establishes each HTTP contract. */
    private lateinit var server: MockWebServer

    /** Starts an isolated HTTP endpoint for each content-type contract. */
    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    /** Releases the embedded endpoint so test runs cannot retain sockets. */
    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Ensures verification uses HEAD, normalizes media MIME parameters, and preserves session headers. */
    @Test
    fun `HEAD media response returns normalized type and forwards supplied headers`() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "Video/MP4; codecs=avc1.64001f")
        )

        val mimeType = sniffContentType(
            url = server.url("/media/abc123").toString(),
            headers = mapOf(
                "User-Agent" to "chaya-test/1.0",
                "Cookie" to "session=abc",
                "Referer" to "https://media.example/watch",
            ),
        )

        assertEquals("video/mp4", mimeType)
        val request = server.takeRequest()
        assertEquals("HEAD", request.method)
        assertEquals("chaya-test/1.0", request.getHeader("User-Agent"))
        assertEquals("session=abc", request.getHeader("Cookie"))
        assertEquals("https://media.example/watch", request.getHeader("Referer"))
    }

    /** Ensures HLS manifests remain eligible when their endpoint has no file extension. */
    @Test
    fun `HEAD manifest response returns recognized streaming MIME type`() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8")
        )

        val mimeType = sniffContentType(
            url = server.url("/playback?id=42").toString(),
            headers = emptyMap(),
        )

        assertEquals("application/vnd.apple.mpegurl", mimeType)
    }

    /** Ensures generic endpoints and rejected HEAD requests never become false media detections. */
    @Test
    fun `non-media and unsuccessful HEAD responses are ignored`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json"))
        server.enqueue(
            MockResponse()
                .setResponseCode(405)
                .setHeader("Content-Type", "video/mp4")
        )

        assertNull(sniffContentType(server.url("/api").toString(), emptyMap()))
        assertNull(sniffContentType(server.url("/head-not-supported").toString(), emptyMap()))
    }

    /** Ensures session headers for an unknown endpoint are never replayed to a redirect target. */
    @Test
    fun `redirected HEAD response is ignored without contacting the second origin`() = runBlocking {
        val redirectedServer = MockWebServer()
        redirectedServer.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", redirectedServer.url("/final"))
            )
            redirectedServer.enqueue(MockResponse().setHeader("Content-Type", "video/mp4"))

            val mimeType = sniffContentType(
                url = server.url("/media/abc123").toString(),
                headers = mapOf("Cookie" to "origin-session=abc"),
            )

            assertNull(mimeType)
            assertEquals("HEAD", server.takeRequest().method)
            assertNull(redirectedServer.takeRequest(200, TimeUnit.MILLISECONDS))
        } finally {
            redirectedServer.shutdown()
        }
    }

    /** Ensures navigation cancellation reaches OkHttp rather than leaving authenticated HEAD calls in flight. */
    @Test
    fun `cancelling an in-flight sniff cancels its OkHttp call`() = runBlocking {
        val cancellationObserved = CompletableDeferred<Boolean>()
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) {
                    cancellationObserved.complete(call.isCanceled())
                }
            })
            .build()

        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                sniffContentType(server.url("/media/slow").toString(), emptyMap(), client)
            }

            assertNotNull("the HEAD request never reached the server", server.takeRequest(5, TimeUnit.SECONDS))
            job.cancelAndJoin()

            assertTrue(
                "cancelling the coroutine must cancel the in-flight OkHttp call",
                withTimeout(5_000) { cancellationObserved.await() },
            )
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
