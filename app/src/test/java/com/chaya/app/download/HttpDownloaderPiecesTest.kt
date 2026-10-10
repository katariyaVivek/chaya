package com.chaya.app.download

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * YouTube files fetched several pieces at a time: each piece written at its place in the file, a record of the
 * whole ones so a resume asks only for the rest, a refused range putting the rest one at a time, and cancel
 * reaching every piece on the way. Pieces here are 4 KB of a 40,000-byte file (ten pieces) from a local server
 * that answers slowly, so pieces overlap.
 */
class HttpDownloaderPiecesTest {

    private lateinit var server: MockWebServer
    private lateinit var tempDir: File
    private val body = ByteArray(40_000) { (it * 7 % 253).toByte() }
    private val piece = 4096L
    private val ranges = Collections.synchronizedList(mutableListOf<String>())
    private val inFlight = AtomicInteger()
    private val mostAtOnce = AtomicInteger()

    @Before
    fun setUp() {
        server = MockWebServer()
        tempDir = Files.createTempDirectory("chaya-pieces").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    /** The first byte a request asks for, or null without a range. */
    private fun startOf(range: String?) = range?.let { Regex("""bytes=(\d+)-""").find(it) }?.groupValues?.get(1)?.toLong()

    /**
     * Answers ranges after [delayMillis]. [answer] can replace the answer for a piece (by its first byte); [hold]
     * keeps pieces waiting until [release] counts down.
     */
    private fun serve(
        delayMillis: Long = 150,
        hold: (Long) -> Boolean = { false },
        release: CountDownLatch = CountDownLatch(0),
        honoursRanges: Boolean = true,
        answer: (start: Long, ask: Int) -> MockResponse? = { _, _ -> null },
    ) {
        val asked = mutableMapOf<Long, Int>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                ranges += range.orEmpty()
                val now = inFlight.incrementAndGet()
                mostAtOnce.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                try {
                    val match = range?.let { Regex("""bytes=(\d+)-(\d*)""").find(it) }
                    if (!honoursRanges || match == null) {
                        Thread.sleep(delayMillis)
                        return MockResponse().setResponseCode(200).setBody(Buffer().write(body))
                    }
                    val from = match.groupValues[1].toInt()
                    val to = match.groupValues[2].toIntOrNull()?.coerceAtMost(body.size - 1) ?: (body.size - 1)
                    val ask = synchronized(asked) { (asked[from.toLong()] ?: 0).plus(1).also { asked[from.toLong()] = it } }
                    if (hold(from.toLong())) release.await(10, TimeUnit.SECONDS)
                    Thread.sleep(delayMillis)
                    answer(from.toLong(), ask)?.let { return it }
                    return MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes $from-$to/${body.size}")
                        .setBody(Buffer().write(body.copyOfRange(from, to + 1)))
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
        server.start()
    }

    private fun downloader() = HttpDownloader(chunkBytesFor = { piece }, piecesAtOnce = 3)

    private class Run {
        val done = CountDownLatch(1)
        @Volatile var result: Result<File>? = null
        @Volatile var lastProgress: Long = -1
    }

    private fun start(downloader: HttpDownloader, file: File, fromBytes: Long = 0, taskId: Long = 1): Run {
        val run = Run()
        downloader.start(
            taskId = taskId,
            url = server.url("/videoplayback").toString(),
            saveFile = file,
            userAgent = "chaya-test",
            cookies = null,
            fromBytes = fromBytes,
            onProgress = { downloaded, _ -> run.lastProgress = downloaded },
            onComplete = {
                run.result = it
                run.done.countDown()
            },
        )
        return run
    }

    private fun finished(run: Run): Result<File> {
        assertTrue("download did not finish", run.done.await(20, TimeUnit.SECONDS))
        return run.result!!
    }

    /** How many pieces the record beside [file] lists as whole. */
    private fun wholePieces(file: File): Int = runCatching {
        PieceLog.fileFor(file).readLines()[1].split(',').count { it.isNotBlank() }
    }.getOrDefault(0)

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            assertTrue(what, System.currentTimeMillis() < deadline)
            Thread.sleep(20)
        }
    }

    @Test
    fun `pieces are fetched three at a time and the file arrives whole, byte for byte`() {
        serve()
        val file = File(tempDir, "video.mp4.part")

        val run = start(downloader(), file)
        val result = finished(run)

        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        assertArrayEquals(body, file.readBytes())
        assertEquals("every piece asked for once", 10, ranges.size)
        assertEquals(10, ranges.map(::startOf).toSet().size)
        assertTrue("pieces overlapped (at most ${mostAtOnce.get()} at once)", mostAtOnce.get() >= 2)
        assertTrue("never more than three at once", mostAtOnce.get() <= 3)
        assertEquals(body.size.toLong(), run.lastProgress)
        assertFalse("the record goes once the file is whole", PieceLog.fileFor(file).exists())
    }

    @Test
    fun `stopped half way, a resume asks only for the pieces still missing`() {
        val release = CountDownLatch(1)
        // Pieces from the fifth on wait, so exactly four are whole when the download stops.
        serve(hold = { it >= 4 * piece }, release = release)
        val file = File(tempDir, "video.mp4.part")
        val first = downloader()
        val run = start(first, file)
        waitFor("four pieces whole") { wholePieces(file) == 4 }

        first.cancel(1)
        release.countDown()
        assertFalse("a cancelled download does not report", run.done.await(1, TimeUnit.SECONDS))
        assertEquals(4L * piece, PieceLog.bytesOnDisk(file))

        ranges.clear()
        val resumed = start(downloader(), file, fromBytes = file.length())
        val result = finished(resumed)

        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        assertArrayEquals(body, file.readBytes())
        val asked = ranges.mapNotNull(::startOf).toSet()
        assertEquals((4L until 10L).map { it * piece }.toSet(), asked)
    }

    @Test
    fun `cancel stops every piece on the way and nothing is reported`() {
        val release = CountDownLatch(1)
        serve(hold = { it >= piece }, release = release)
        val file = File(tempDir, "video.mp4.part")
        val downloader = downloader()
        val run = start(downloader, file)
        waitFor("three pieces asked for") { ranges.size >= 3 }

        downloader.cancel(1)
        release.countDown()
        Thread.sleep(500)
        val askedAfterCancel = ranges.size

        assertFalse("a cancelled download does not report", run.done.await(1, TimeUnit.SECONDS))
        assertEquals("no piece is asked for after cancel", askedAfterCancel, ranges.size)
        assertTrue("the record stays for a resume", PieceLog.fileFor(file).exists())
    }

    @Test
    fun `a piece answered with the whole file puts the rest one at a time`() {
        // The sixth piece is answered once with the whole file instead of its range.
        serve(answer = { start, ask ->
            if (start == 5 * piece && ask == 1) MockResponse().setResponseCode(200).setBody(Buffer().write(body)) else null
        })
        val file = File(tempDir, "video.mp4.part")

        val result = finished(start(downloader(), file))

        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        assertArrayEquals(body, file.readBytes())
        assertEquals("the refused piece is asked for again", 2, ranges.count { startOf(it) == 5 * piece })
    }

    @Test
    fun `a piece refused twice stops the download, keeping what is whole`() {
        serve(answer = { start, _ -> if (start == 5 * piece) MockResponse().setResponseCode(403) else null })
        val file = File(tempDir, "video.mp4.part")

        val result = finished(start(downloader(), file))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.exceptionOrNull()?.message.orEmpty().contains("403"))
        assertEquals("asked twice: once with the others, once alone", 2, ranges.count { startOf(it) == 5 * piece })
        assertTrue("the record stays for a resume", PieceLog.fileFor(file).exists())
        assertTrue(wholePieces(file) >= 1)
    }

    @Test
    fun `a server that ignores ranges sends the whole file once`() {
        serve(honoursRanges = false)
        val file = File(tempDir, "video.mp4.part")

        val result = finished(start(downloader(), file))

        assertTrue(result.isSuccess)
        assertArrayEquals(body, file.readBytes())
        assertEquals(1, ranges.size)
        assertFalse(PieceLog.fileFor(file).exists())
    }

    @Test
    fun `a file fetched one piece after another before this keeps its whole pieces`() {
        serve()
        // 10,000 bytes from before, with no record: two whole pieces and part of a third.
        val file = File(tempDir, "video.mp4.part").apply { writeBytes(body.copyOfRange(0, 10_000)) }

        val result = finished(start(downloader(), file, fromBytes = 10_000))

        assertTrue(result.isSuccess)
        assertArrayEquals(body, file.readBytes())
        val asked = ranges.mapNotNull(::startOf).toSet()
        assertEquals((2L until 10L).map { it * piece }.toSet(), asked)
    }

    @Test
    fun `a file longer than the one fetched now is cut to its size`() {
        serve()
        val file = File(tempDir, "video.mp4.part").apply { writeBytes(ByteArray(50_000)) }
        PieceLog.load(file, piece, 0).begin(50_000)

        val result = finished(start(downloader(), file, fromBytes = 0))

        assertTrue(result.isSuccess)
        assertArrayEquals(body, file.readBytes())
    }
}
