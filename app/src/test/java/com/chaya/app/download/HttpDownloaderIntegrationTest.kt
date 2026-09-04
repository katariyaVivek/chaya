package com.chaya.app.download

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Proves [HttpDownloader] against a real embedded HTTP server instead of a
 * mocked OkHttp layer: byte-exact transfer, Range resume semantics, the
 * server-ignores-Range fallback, Content-Disposition timing, HTTP error
 * surfacing and the documented silent-cancel contract. These are the
 * contracts [DownloadManager] and Phase 0.4/3.1 hardening build on.
 */
class HttpDownloaderIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var downloader: HttpDownloader
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = HttpDownloader()
        tempDir = Files.createTempDirectory("chaya-dl-test").toFile()
    }

    @After
    fun tearDown() {
        downloader.cancelAll()
        server.shutdown()
        tempDir.deleteRecursively()
    }

    /** Deterministic pseudo-random payload; byte i is always the same value. */
    private fun fixtureBytes(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    private fun saveFile(name: String) = File(tempDir, name)

    private fun startDownload(
        saveFile: File,
        fromBytes: Long = 0,
        referer: String? = "https://videosite.example/watch",
        onMeta: ((String?) -> Unit)? = null,
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
        taskId: Long = 1L,
        onComplete: (Result<File>) -> Unit,
    ) {
        downloader.start(
            taskId = taskId,
            url = server.url("/media").toString(),
            saveFile = saveFile,
            userAgent = "chaya-test/1.0",
            cookies = "session=abc",
            referer = referer,
            fromBytes = fromBytes,
            onMeta = onMeta,
            onProgress = onProgress,
            onComplete = onComplete,
        )
    }

    private fun awaitCompletion(latch: CountDownLatch): Result<File> {
        val holder = arrayOfNulls<Result<File>>(1)
        startDownload(saveFile("out.bin"), onComplete = {
            holder[0] = it
            latch.countDown()
        })
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return holder[0]!!
    }

    @Test
    fun `full download matches server bytes exactly`() {
        val payload = fixtureBytes(8192)
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))

        val result = awaitCompletion(CountDownLatch(1))

        assertTrue("expected success, got $result", result.isSuccess)
        assertEquals(payload.toList(), result.getOrThrow().readBytes().toList())
    }

    @Test
    fun `resume sends correct Range header and reassembles file without gaps`() {
        val payload = fixtureBytes(8192)
        val partial = 3000L

        // Simulate the state left by an interrupted first attempt: the
        // partial file holds the first `partial` bytes on disk.
        val file = saveFile("out.bin")
        file.writeBytes(payload.copyOfRange(0, partial.toInt()))

        val remaining = payload.copyOfRange(partial.toInt(), payload.size)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $partial-${payload.size - 1}/${payload.size}")
                .setBody(Buffer().write(remaining))
        )

        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<Result<File>>(1)
        startDownload(file, fromBytes = partial, onComplete = {
            holder[0] = it
            latch.countDown()
        })
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        val result = holder[0]!!

        assertTrue("expected success, got $result", result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("bytes=$partial-", recorded.getHeader("Range"))
        assertEquals(
            "resumed file must be byte-for-byte complete",
            payload.toList(),
            file.readBytes().toList(),
        )
    }

    @Test
    fun `server ignoring Range returns 200 and downloader restarts from zero`() {
        val payload = fixtureBytes(4096)
        val file = saveFile("out.bin")
        // Stale partial content that would corrupt the file if appended to.
        file.writeBytes(fixtureBytes(1000))

        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))

        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<Result<File>>(1)
        startDownload(file, fromBytes = 1000, onComplete = {
            holder[0] = it
            latch.countDown()
        })
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        val result = holder[0]!!

        assertTrue("expected success, got $result", result.isSuccess)
        assertEquals(
            "200 despite Range must truncate and rewrite, not append",
            payload.toList(),
            file.readBytes().toList(),
        )
    }

    @Test
    fun `content-disposition name reaches onMeta before any bytes are written`() {
        val payload = fixtureBytes(2048)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Disposition", "attachment; filename=\"real-name.mp4\"")
                .setBody(Buffer().write(payload))
        )

        val events = mutableListOf<String>()
        val metaNames = mutableListOf<String?>()
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<Result<File>>(1)
        startDownload(
            saveFile("out.bin"),
            onMeta = {
                metaNames.add(it)
                events.add("meta")
            },
            onProgress = { _, _ -> events.add("bytes") },
            onComplete = {
                holder[0] = it
                latch.countDown()
            },
        )
        assertTrue(latch.await(10, TimeUnit.SECONDS))

        assertTrue(holder[0]!!.isSuccess)
        assertEquals("real-name.mp4", metaNames.first())
        assertEquals("onMeta must fire before any bytes are written", "meta", events.first())
    }

    @Test
    fun `http error status surfaces as failure with status code in message`() {
        for (code in intArrayOf(403, 404, 500)) {
            server.enqueue(MockResponse().setResponseCode(code))

            val result = awaitCompletion(CountDownLatch(1))

            assertTrue("HTTP $code must fail, got $result", result.isFailure)
            val message = result.exceptionOrNull()?.message
            assertNotNull("failure must carry a message", message)
            assertTrue(
                "message should contain the status code, was: $message",
                message!!.contains(code.toString()),
            )
        }
    }

    @Test
    fun `empty response body completes with an empty file`() {
        // A 200 with zero bytes is a valid (if odd) download: success with a
        // 0-byte file, not an error.
        server.enqueue(MockResponse().setResponseCode(200))

        val result = awaitCompletion(CountDownLatch(1))

        assertTrue(result.isSuccess)
        assertEquals(0L, result.getOrThrow().length())
    }

    @Test
    fun `silent cancel never invokes callbacks and preserves partial file`() {
        // Large body throttled to ~10KB/s guarantees the transfer is still
        // streaming while the test cancels.
        val payload = fixtureBytes(1024 * 1024)
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(payload))
                .throttleBody(1024, 100, TimeUnit.MILLISECONDS)
        )

        val file = saveFile("out.bin")
        val completion = CountDownLatch(1)
        val completed = arrayOfNulls<Result<File>>(1)
        startDownload(
            file,
            onProgress = { _, _ -> },
            onComplete = {
                completed[0] = it
                completion.countDown()
            },
        )

        // Wait until real bytes are on disk, then cancel mid-transfer.
        val deadline = System.currentTimeMillis() + 10_000
        while ((file.length() == 0L) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue("transfer never wrote any bytes; cannot test mid-stream cancel", file.length() > 0)
        // OkHttp may already hold buffered bytes it flushes before the cancel
        // lands, so preserve the exact prefix seen at cancel time rather than
        // asserting on the raw size.
        val prefix = file.readBytes()
        downloader.cancel(taskId = 1L)

        assertFalse(
            "onComplete must never fire after cancel",
            completion.await(2, TimeUnit.SECONDS),
        )
        assertNull(completed[0])
        assertTrue("partial file must be deleted", file.exists())
        assertTrue(
            "cancel must stop well before the full ${payload.size}-byte body",
            file.length() < payload.size.toLong(),
        )
        assertEquals(
            "bytes written before cancel must be preserved, not truncated or duplicated",
            prefix.toList(),
            file.readBytes().copyOfRange(0, prefix.size).toList(),
        )
        assertTrue("file must not exceed the body size", file.length() <= payload.size.toLong())
    }

    @Test
    fun `connection dropped mid-body fails instead of reporting success`() {
        val payload = fixtureBytes(64 * 1024)
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(payload))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )

        val result = awaitCompletion(CountDownLatch(1))

        assertTrue("truncated transfer must not succeed, got $result", result.isFailure)
    }

    @Test
    fun `cancel while response is delayed never invokes callbacks`() {
        val payload = fixtureBytes(4096)
        // Long body delay makes the cancel deterministically win the race —
        // without it, a fast response could legitimately complete first.
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(payload))
                .setBodyDelay(2, TimeUnit.SECONDS)
        )

        val completion = CountDownLatch(1)
        val completed = arrayOfNulls<Result<File>>(1)
        startDownload(saveFile("out.bin"), onComplete = {
            completed[0] = it
            completion.countDown()
        })
        downloader.cancel(taskId = 1L)

        assertFalse(
            "onComplete must never fire after cancel",
            completion.await(3, TimeUnit.SECONDS),
        )
        assertNull(completed[0])
    }

    @Test
    fun `response body reads are consumed as raw bytes without auto-decompression surprises`() {
        // Accept-Encoding: identity forbids gzip so byte counts line up with
        // what the server actually sent.
        val payload = fixtureBytes(4096)
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))

        val result = awaitCompletion(CountDownLatch(1))

        assertTrue(result.isSuccess)
        assertEquals(
            payload.toList(),
            result.getOrThrow().readBytes().toList(),
        )
    }

    @Test
    fun `identity accept-encoding is sent so servers do not gzip the body`() {
        val payload = fixtureBytes(1024)
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))

        awaitCompletion(CountDownLatch(1))

        val recorded = server.takeRequest()
        assertEquals("identity", recorded.getHeader("Accept-Encoding"))
    }

    @Test
    fun `auth headers from the original request reach the server`() {
        val payload = fixtureBytes(1024)
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))

        awaitCompletion(CountDownLatch(1))

        val recorded = server.takeRequest()
        assertEquals("chaya-test/1.0", recorded.getHeader("User-Agent"))
        assertEquals("session=abc", recorded.getHeader("Cookie"))
        assertEquals("https://videosite.example/watch", recorded.getHeader("Referer"))
    }

    @Test
    fun `blank referer is omitted while other headers still sent`() {
        val payload = fixtureBytes(1024)
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))

        val latch = CountDownLatch(1)
        startDownload(saveFile("out.bin"), referer = "  ", onComplete = { latch.countDown() })
        // Referer is blank → no header; the transfer itself still completes.
        assertTrue(latch.await(10, TimeUnit.SECONDS))

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Referer"))
        assertEquals("session=abc", recorded.getHeader("Cookie"))
    }

    @Test
    fun `resumed transfer reports totals from content-range`() {
        val payload = fixtureBytes(8192)
        val partial = 3000L
        val file = saveFile("out.bin")
        file.writeBytes(payload.copyOfRange(0, partial.toInt()))

        val remaining = payload.copyOfRange(partial.toInt(), payload.size)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $partial-${payload.size - 1}/${payload.size}")
                .setBody(Buffer().write(remaining))
        )

        val totals = mutableListOf<Long?>()
        val latch = CountDownLatch(1)
        startDownload(
            file,
            fromBytes = partial,
            onProgress = { downloaded, total ->
                if (total != null) totals.add(total)
            },
            onComplete = { latch.countDown() },
        )
        assertTrue(latch.await(10, TimeUnit.SECONDS))

        assertEquals("total must come from Content-Range, not body length", listOf(payload.size.toLong()), totals)
    }

    @Test
    fun `successful 206 resume path where server ignores nothing still writes only the remainder`() {
        val payload = fixtureBytes(8192)
        val partial = 4096L
        val file = saveFile("out.bin")
        file.writeBytes(payload.copyOfRange(0, partial.toInt()))

        val remaining = payload.copyOfRange(partial.toInt(), payload.size)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $partial-${payload.size - 1}/${payload.size}")
                .setBody(Buffer().write(remaining))
        )

        val latch = CountDownLatch(1)
        startDownload(file, fromBytes = partial, onComplete = { latch.countDown() })
        assertTrue(latch.await(10, TimeUnit.SECONDS))

        assertEquals(
            "206 must append the remainder exactly once",
            payload.toList(),
            file.readBytes().toList(),
        )
    }
}
