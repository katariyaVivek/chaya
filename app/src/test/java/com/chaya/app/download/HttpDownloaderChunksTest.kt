package com.chaya.app.download

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Downloads fetched in pieces, as YouTube needs: it serves one long request at about the speed of playback,
 * so yt-dlp (and Chaya) ask for 10 MB ranges one after another. Here a piece is 4 KB against a local server.
 */
class HttpDownloaderChunksTest {

    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private val body = ByteArray(10_000) { (it % 251).toByte() }
    private val ranges = Collections.synchronizedList(mutableListOf<String?>())

    @Before
    fun setUp() {
        server = MockWebServer()
        tempDir = Files.createTempDirectory("chaya-chunks").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    /** Counts down when the request for the second piece reaches the server. */
    private val secondPieceAsked = CountDownLatch(1)

    /**
     * A server that honours "bytes=a-b" ranges, or ignores them when [honoursRanges] is false.
     * [holdLaterPieces] keeps every piece after the first waiting, so a test can cancel in between.
     */
    private fun serve(honoursRanges: Boolean = true, holdLaterPieces: Boolean = false) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                ranges += range
                val match = range?.let { Regex("""bytes=(\d+)-(\d*)""").find(it) }
                if (!honoursRanges || match == null) {
                    return MockResponse().setResponseCode(200).setBody(Buffer().write(body))
                }
                val from = match.groupValues[1].toInt()
                val to = match.groupValues[2].toIntOrNull()?.coerceAtMost(body.size - 1) ?: (body.size - 1)
                if (from > 0) secondPieceAsked.countDown()
                return MockResponse()
                    .apply { if (holdLaterPieces && from > 0) setBodyDelay(3, TimeUnit.SECONDS) }
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $from-$to/${body.size}")
                    .setBody(Buffer().write(body.copyOfRange(from, to + 1)))
            }
        }
        server.start()
    }

    private fun download(downloader: HttpDownloader, file: File, fromBytes: Long = 0): Pair<Result<File>?, Long?> {
        val done = CountDownLatch(1)
        var result: Result<File>? = null
        var lastTotal: Long? = null
        downloader.start(
            taskId = 1L,
            url = server.url("/videoplayback").toString(),
            saveFile = file,
            userAgent = "chaya-test",
            cookies = null,
            fromBytes = fromBytes,
            onProgress = { _, total -> lastTotal = total },
            onComplete = { result = it; done.countDown() },
        )
        assertTrue("download did not finish", done.await(10, TimeUnit.SECONDS))
        return result to lastTotal
    }

    @Test
    fun `a file fetched in pieces arrives whole, one range after another`() {
        serve()
        val file = File(tempDir, "video.mp4")

        val (result, total) = download(HttpDownloader(chunkBytesFor = { 4096L }), file)

        assertTrue(result!!.isSuccess)
        assertArrayEquals(body, file.readBytes())
        assertEquals(listOf("bytes=0-4095", "bytes=4096-8191", "bytes=8192-9999"), ranges.toList())
        assertEquals(10_000L, total)
    }

    @Test
    fun `a resumed download asks for its pieces from where it stopped`() {
        serve()
        val file = File(tempDir, "video.mp4").apply { writeBytes(body.copyOfRange(0, 5000)) }

        val (result, _) = download(HttpDownloader(chunkBytesFor = { 4096L }), file, fromBytes = 5000)

        assertTrue(result!!.isSuccess)
        assertArrayEquals(body, file.readBytes())
        assertEquals(listOf("bytes=5000-9095", "bytes=9096-9999"), ranges.toList())
    }

    @Test
    fun `a server that ignores ranges sends the whole file once and that is enough`() {
        serve(honoursRanges = false)
        val file = File(tempDir, "video.mp4")

        val (result, _) = download(HttpDownloader(chunkBytesFor = { 4096L }), file)

        assertTrue(result!!.isSuccess)
        assertArrayEquals(body, file.readBytes())
        assertEquals(1, ranges.size)
    }

    @Test
    fun `cancelling between pieces stops the download without a result`() {
        serve(holdLaterPieces = true)
        val file = File(tempDir, "video.mp4")
        val downloader = HttpDownloader(chunkBytesFor = { 4096L })
        val finished = CountDownLatch(1)
        downloader.start(
            taskId = 7L,
            url = server.url("/videoplayback").toString(),
            saveFile = file,
            userAgent = null,
            cookies = null,
            onProgress = { _, _ -> },
            onComplete = { finished.countDown() },
        )
        assertTrue("the second piece was never asked for", secondPieceAsked.await(5, TimeUnit.SECONDS))

        downloader.cancel(7L)

        assertTrue("a cancelled download must not report", !finished.await(4, TimeUnit.SECONDS))
        assertEquals("only the first piece was written", 4096L, file.length())
    }

    @Test
    fun `only YouTube's file host is fetched in pieces`() {
        val tenMb = HttpDownloader.YOUTUBE_CHUNK_BYTES
        assertEquals(tenMb, HttpDownloader.youTubeChunkBytes("https://rr3---sn-abc.googlevideo.com/videoplayback?id=1"))
        assertNull(HttpDownloader.youTubeChunkBytes("https://scontent.cdninstagram.com/v/clip.mp4"))
        assertNull(HttpDownloader.youTubeChunkBytes("https://evil-googlevideo.com/videoplayback"))
        assertNull(HttpDownloader.youTubeChunkBytes("not a url"))
    }

    @Test
    fun `the last piece's range stops at the end of the file`() {
        assertEquals("bytes=0-4095", HttpDownloader.rangeFrom(0, 4096, total = null))
        assertEquals("bytes=8192-9999", HttpDownloader.rangeFrom(8192, 4096, total = 10_000))
    }
}
