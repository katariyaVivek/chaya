package com.chaya.app.download

import android.content.Context
import androidx.room.Room
import com.chaya.app.database.ChayaDatabase
import com.chaya.app.database.DownloadDao
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Downloads that come from the engine: a single file with its own headers, or a picture and a separate
 * sound that are fetched one after the other and joined into one MP4. The real manager and a real Room
 * database run against a scripted downloader and merger, so each step's files, headers, progress and
 * state are checked without sockets or real media.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadManagerTracksTest {

    private lateinit var context: Context
    private lateinit var db: ChayaDatabase
    private lateinit var dao: DownloadDao
    private val downloader = ScriptedDownloader()
    private val merger = ScriptedMerger()
    private val managers = mutableListOf<DownloadManager>()
    private lateinit var manager: DownloadManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "downloads").deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, ChayaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.downloadDao()
        manager = newManager()
    }

    @After
    fun tearDown() {
        runBlocking { managers.forEach { it.drainBackgroundWork() } }
        db.close()
        File(context.filesDir, "downloads").deleteRecursively()
    }

    private fun newManager(freeBytes: (File) -> Long = { Long.MAX_VALUE }) =
        DownloadManager(context, dao, downloader, freeBytes = freeBytes, merger = merger).also { managers += it }

    // ---- the whole journey ---- //

    @Test
    fun `a picture and a sound are fetched with their own headers, joined, and saved as one file`() = runBlocking {
        downloader.autoComplete = { call -> if (call.url.endsWith("/video")) VIDEO else AUDIO }
        manager.restore()

        manager.startDownload(request())
        val done = awaitTask { it.state == DownloadState.COMPLETED }

        // Picture first, then sound, each into a ".part" file that is renamed once whole.
        assertEquals(listOf(VIDEO_URL, AUDIO_URL), downloader.calls.map { it.url })
        val picture = downloader.calls[0]
        val sound = downloader.calls[1]
        assertEquals("${done.filePath}.video.part", picture.saveFile.path)
        assertEquals("${done.filePath}.audio.part", sound.saveFile.path)
        assertEquals(Headers("UA/video", "a=b", PAGE), Headers(picture.userAgent, picture.cookies, picture.referer))
        assertEquals(Headers("UA/audio", null, PAGE), Headers(sound.userAgent, sound.cookies, sound.referer))

        // One join, of the two finished files, into a temporary file that then becomes the saved file.
        val join = merger.calls.single()
        assertEquals("${done.filePath}.video", join.picture.path)
        assertEquals("${done.filePath}.audio", join.sound.path)
        assertEquals("${done.filePath}.joining", join.output.path)
        val saved = File(done.filePath!!)
        assertEquals("joined ${VIDEO.size} + ${AUDIO.size}", saved.readText())

        // Nothing is left behind, and the task describes the finished file.
        assertFalse(File("${done.filePath}.video").exists())
        assertFalse(File("${done.filePath}.audio").exists())
        assertFalse(File("${done.filePath}.joining").exists())
        assertEquals(saved.length(), done.downloadedBytes)
        assertEquals(saved.length(), done.totalBytes)
        assertEquals("Me at the zoo (240p).mp4", done.fileName)
        assertEquals("Me at the zoo", done.title)
        assertEquals(240, done.qualityHeight)
    }

    @Test
    fun `progress adds the picture and the sound together against the expected total`() = runBlocking {
        manager.restore()
        manager.startDownload(request(expectedBytes = 600))
        awaitCalls(1)

        downloader.calls[0].onProgress(100, 400)
        assertEquals(100L, awaitTask { it.downloadedBytes == 100L }.downloadedBytes)
        assertEquals(600L, manager.downloads.value.single().totalBytes)

        downloader.finish(downloader.calls[0], VIDEO)
        awaitCalls(2)
        downloader.calls[1].onProgress(50, 200)

        val task = awaitTask { it.downloadedBytes == VIDEO.size + 50L }
        assertEquals(600L, task.totalBytes)
    }

    // ---- interruptions ---- //

    @Test
    fun `pausing while the sound downloads keeps the picture and continues the sound where it stopped`() =
        runBlocking {
            manager.restore()
            manager.startDownload(request())
            awaitCalls(1)
            downloader.finish(downloader.calls[0], VIDEO)
            awaitCalls(2)
            val partialSound = downloader.calls[1].saveFile
            partialSound.writeBytes(ByteArray(30))

            manager.pauseDownload(1)

            val paused = awaitTask { it.state == DownloadState.PAUSED }
            assertEquals(listOf(1L), downloader.cancelled)
            assertEquals(VIDEO.size + 30L, paused.downloadedBytes)

            manager.resumeDownload(1)
            awaitCalls(3)

            // Only the sound is fetched again, as a range request continuing the partial file.
            val resumed = downloader.calls[2]
            assertEquals(AUDIO_URL, resumed.url)
            assertEquals(30L, resumed.fromBytes)
            assertEquals(partialSound.path, resumed.saveFile.path)

            downloader.finish(resumed, ByteArray(70))
            val done = awaitTask { it.state == DownloadState.COMPLETED }
            assertEquals("joined ${VIDEO.size} + 100", File(done.filePath!!).readText())
            assertEquals(3, downloader.calls.size)
        }

    @Test
    fun `a failed join is retried without downloading anything again`() = runBlocking {
        downloader.autoComplete = { call -> if (call.url.endsWith("/video")) VIDEO else AUDIO }
        merger.failWith = CombineException("no picture track")
        manager.restore()
        manager.startDownload(request())

        val failed = awaitTask { it.state == DownloadState.FAILED }

        assertTrue(failed.error is DownloadError.CouldNotCombine)
        assertTrue(File("${failed.filePath}.video").exists())
        assertTrue(File("${failed.filePath}.audio").exists())
        assertFalse(File("${failed.filePath}.joining").exists())

        merger.failWith = null
        manager.resumeDownload(1)

        val done = awaitTask { it.state == DownloadState.COMPLETED }
        assertEquals(2, merger.calls.size)
        assertEquals(2, downloader.calls.size)
        assertEquals("joined ${VIDEO.size} + ${AUDIO.size}", File(done.filePath!!).readText())
    }

    @Test
    fun `a transfer that fails stops the whole download and a retry continues from the files kept`() = runBlocking {
        manager.restore()
        manager.startDownload(request())
        awaitCalls(1)
        downloader.finish(downloader.calls[0], VIDEO)
        awaitCalls(2)

        downloader.calls[1].onComplete(Result.failure(java.io.IOException("HTTP 403: Forbidden")))

        val failed = awaitTask { it.state == DownloadState.FAILED }
        assertTrue(failed.error is DownloadError.HttpStatus)
        assertTrue(File("${failed.filePath}.video").exists())

        manager.resumeDownload(1)
        awaitCalls(3)
        assertEquals(AUDIO_URL, downloader.calls[2].url)
        assertEquals(1, downloader.calls.count { it.url == VIDEO_URL })
    }

    @Test
    fun `deleting a download removes the saved file, the parts and the unfinished join`() = runBlocking {
        manager.restore()
        manager.startDownload(request())
        awaitCalls(1)
        downloader.finish(downloader.calls[0], VIDEO)
        awaitCalls(2)
        downloader.calls[1].saveFile.writeBytes(ByteArray(10))
        // The record of whole pieces a download fetched several pieces at a time keeps beside its part.
        PieceLog.load(downloader.calls[1].saveFile, 4, 0).begin(40)
        val path = manager.downloads.value.single().filePath!!
        File("$path.joining").writeText("unfinished")

        manager.deleteTask(1)

        assertTrue(manager.downloads.value.isEmpty())
        listOf("", ".video", ".video.part", ".audio", ".audio.part", ".audio.part.pieces", ".joining").forEach { suffix ->
            assertFalse("left behind: $suffix", File("$path$suffix").exists())
        }
    }

    @Test
    fun `a picture fetched several pieces at a time counts only its whole pieces when paused`() = runBlocking {
        manager.restore()
        manager.startDownload(request())
        awaitCalls(1)
        val part = downloader.calls[0].saveFile
        // Pieces arrive out of order: the part is 100 bytes long, but only its first 40-byte piece is whole.
        part.writeBytes(ByteArray(100))
        PieceLog.load(part, 40, 0).apply {
            begin(100)
            markDone(0)
        }

        manager.pauseDownload(1)

        val paused = awaitTask { it.state == DownloadState.PAUSED }
        assertEquals(40L, paused.downloadedBytes)
    }

    // ---- one file with the engine's headers ---- //

    @Test
    fun `a single file uses the engine's headers rather than the browser session and is saved directly`() =
        runBlocking {
            manager.restore()
            manager.startDownload(request(withSound = false))
            awaitCalls(1)

            val call = downloader.calls.single()
            val task = manager.downloads.value.single()
            assertEquals(task.filePath, call.saveFile.path)
            assertEquals(VIDEO_URL, call.url)
            assertEquals(Headers("UA/video", "a=b", PAGE), Headers(call.userAgent, call.cookies, call.referer))

            downloader.finish(call, VIDEO)
            val done = awaitTask { it.state == DownloadState.COMPLETED }
            assertEquals(VIDEO.size.toLong(), done.downloadedBytes)
            assertTrue(merger.calls.isEmpty())
        }

    @Test
    fun `without engine headers the browser's own session is used, with the page as referer`() = runBlocking {
        manager.restore()
        manager.startDownload(request(withSound = false, headers = emptyMap()))
        awaitCalls(1)

        val call = downloader.calls.single()
        assertNotNull(call.userAgent)
        assertEquals(PAGE, call.referer)
    }

    @Test
    fun `the engine's request survives in the database`() = runBlocking {
        manager.restore()
        manager.startDownload(request())
        awaitCalls(1)

        val saved = dao.getById(1)!!

        assertEquals(AUDIO_URL, saved.audioUrl)
        assertEquals(request().headers, HeaderCodec.decode(saved.requestHeaders))
        val task = saved.toTask()
        assertEquals(request().headers, task.requestHeaders)
        assertEquals(request().audioHeaders, task.audioRequestHeaders)
        assertTrue(task.hasOwnRequest)
    }

    // ---- room on the phone ---- //

    @Test
    fun `a joined download needs room for both files, and fails at once when there is not enough`() = runBlocking {
        // 500 MB to fetch twice (parts, then the joined file) plus headroom is more than 800 MB.
        val tight = newManager(freeBytes = { 800L * MB })
        tight.restore()

        tight.startDownload(request(expectedBytes = 500L * MB))

        val failed = awaitTask(tight) { it.state == DownloadState.FAILED }
        assertEquals(DownloadError.StorageFull, failed.error)
        assertNull(failed.filePath)
        assertTrue(downloader.calls.isEmpty())
    }

    @Test
    fun `the same 500 MB as a single file fits in 800 MB`() = runBlocking {
        val tight = newManager(freeBytes = { 800L * MB })
        tight.restore()

        tight.startDownload(request(withSound = false, expectedBytes = 500L * MB))

        awaitCalls(1)
        assertEquals(DownloadState.DOWNLOADING, tight.downloads.value.single().state)
    }

    // ---- fixtures ---- //

    private fun request(
        withSound: Boolean = true,
        expectedBytes: Long? = 500L,
        headers: Map<String, String> = mapOf(
            "User-Agent" to "UA/video",
            "Cookie" to "a=b",
            "Referer" to PAGE,
            "Accept" to "*/*",
        ),
    ) = DownloadRequest(
        url = VIDEO_URL,
        headers = headers,
        audioUrl = AUDIO_URL.takeIf { withSound },
        audioHeaders = if (withSound) mapOf("User-Agent" to "UA/audio") else emptyMap(),
        pageUrl = PAGE,
        fileName = "Me at the zoo (240p).mp4",
        mimeType = "video/mp4",
        title = "Me at the zoo",
        thumbnailUrl = "https://i.ytimg.com/vi/x/hq.jpg",
        qualityHeight = 240,
        expectedBytes = expectedBytes,
    )

    private data class Headers(val userAgent: String?, val cookies: String?, val referer: String?)

    private suspend fun awaitTask(
        target: DownloadManager = manager,
        predicate: (DownloadTask) -> Boolean,
    ): DownloadTask {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            target.downloads.value.firstOrNull(predicate)?.let { return it }
            delay(20)
        }
        throw AssertionError("The task never got there. Tasks now: ${target.downloads.value}")
    }

    /** Waits until the downloader has been asked to start at least [count] transfers. */
    private suspend fun awaitCalls(count: Int) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (downloader.calls.size >= count) return
            delay(20)
        }
        throw AssertionError("Expected $count transfers but saw ${downloader.calls.map { it.url }}")
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val MB = 1024L * 1024
        const val PAGE = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
        const val VIDEO_URL = "https://cdn.example/video"
        const val AUDIO_URL = "https://cdn.example/audio"
        val VIDEO = ByteArray(400) { 1 }
        val AUDIO = ByteArray(100) { 2 }
    }
}

/**
 * Records every transfer the manager asks for. By default nothing happens until a test drives the callbacks;
 * [autoComplete] instead finishes each transfer at once with the bytes it returns. Like the real downloader,
 * a start with an offset appends to what is already in the file, and cancelling is silent.
 */
private class ScriptedDownloader : MediaDownloader {

    class Call(
        val taskId: Long,
        val url: String,
        val saveFile: File,
        val userAgent: String?,
        val cookies: String?,
        val referer: String?,
        val fromBytes: Long,
        val onProgress: (Long, Long?) -> Unit,
        val onComplete: (Result<File>) -> Unit,
    )

    val calls = CopyOnWriteArrayList<Call>()
    val cancelled = CopyOnWriteArrayList<Long>()
    var autoComplete: ((Call) -> ByteArray)? = null

    override fun start(
        taskId: Long,
        url: String,
        saveFile: File,
        userAgent: String?,
        cookies: String?,
        referer: String?,
        fromBytes: Long,
        onMeta: ((suggestedName: String?) -> Unit)?,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onComplete: (Result<File>) -> Unit,
    ) {
        val call = Call(taskId, url, saveFile, userAgent, cookies, referer, fromBytes, onProgress, onComplete)
        calls += call
        autoComplete?.let { finish(call, it(call)) }
    }

    override fun cancel(taskId: Long) {
        cancelled += taskId
    }

    /** Writes [bytes] the way a finished transfer would have, reports the final progress, then success. */
    fun finish(call: Call, bytes: ByteArray) {
        call.saveFile.parentFile?.mkdirs()
        if (call.fromBytes > 0) call.saveFile.appendBytes(bytes) else call.saveFile.writeBytes(bytes)
        call.onProgress(call.saveFile.length(), call.saveFile.length())
        call.onComplete(Result.success(call.saveFile))
    }
}

/** Stands in for the real muxer: records what it was asked to join and writes a recognisable result. */
private class ScriptedMerger : MediaMerger {

    class Call(val picture: File, val sound: File, val output: File)

    val calls = CopyOnWriteArrayList<Call>()
    var failWith: Exception? = null

    override fun merge(picture: File, sound: File, output: File) {
        calls += Call(picture, sound, output)
        failWith?.let { throw it }
        output.writeText("joined ${picture.length()} + ${sound.length()}")
    }
}
