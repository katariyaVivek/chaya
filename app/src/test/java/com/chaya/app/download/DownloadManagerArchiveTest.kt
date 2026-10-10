package com.chaya.app.download

import android.content.Context
import androidx.room.Room
import com.chaya.app.database.ChayaDatabase
import com.chaya.app.database.DownloadDao
import com.chaya.app.database.DownloadEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/**
 * A ZIP archive of many files (everything an account has posted): listed, fetched one by one, packed. The
 * real manager and a real Room database run against a stand-in lister and file server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadManagerArchiveTest {

    private lateinit var context: Context
    private lateinit var db: ChayaDatabase
    private lateinit var dao: DownloadDao
    private val server = FileServer()
    private val lister = FakeLister()
    private val managers = mutableListOf<DownloadManager>()
    private lateinit var manager: DownloadManager

    private val request = ArchiveRequest(
        source = "${ARCHIVE_SCHEME}instagram:someone",
        fileName = "someone (Instagram).zip",
        title = "@someone · Instagram posts",
        pageUrl = "https://www.instagram.com/someone/",
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "downloads").deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, ChayaDatabase::class.java).allowMainThreadQueries().build()
        dao = db.downloadDao()
        manager = newManager()
    }

    @After
    fun tearDown() {
        runBlocking { managers.forEach { it.drainBackgroundWork() } }
        db.close()
        File(context.filesDir, "downloads").deleteRecursively()
    }

    private fun newManager(archiveLister: ArchiveLister? = lister) =
        DownloadManager(
            context, dao, server, freeBytes = { Long.MAX_VALUE }, archiveLister = archiveLister,
            archiveRetryDelaysMillis = listOf(10L, 10L, 10L),
        )
            .also { managers += it }

    @Test
    fun `every listed file is fetched and packed into one ZIP, and nothing is left behind`() = runBlocking {
        lister.entries = listOf("2024-05-01 A 1.jpg" to "https://cdn/a1", "2024-05-01 A 2.mp4" to "https://cdn/a2")
        manager.restore()

        manager.startArchive(request)
        val done = awaitTask { it.state == DownloadState.COMPLETED }

        assertEquals(listOf(request.source), lister.sources)
        assertEquals(listOf("https://cdn/a1", "https://cdn/a2"), server.asked)
        assertEquals("no browser cookies are sent with an archive's files", listOf(null, null), server.cookies)
        assertEquals("application/zip", done.mimeType)
        assertEquals("someone (Instagram).zip", done.fileName)
        assertNull(done.archive)
        val zip = File(done.filePath!!)
        assertEquals(
            mapOf("2024-05-01 A 1.jpg" to "https://cdn/a1", "2024-05-01 A 2.mp4" to "https://cdn/a2"),
            contents(zip),
        )
        assertEquals(zip.length(), done.totalBytes)
        assertFalse("the working folder is removed", File("${zip.path}.items").exists())
    }

    @Test
    fun `a file the site no longer has is skipped and named in the archive`() = runBlocking {
        lister.entries = listOf("a.jpg" to "https://cdn/a", "gone.jpg" to "https://cdn/gone", "c.jpg" to "https://cdn/c")
        server.answer = { url -> if (url.endsWith("gone")) Result.failure(IOException("HTTP 404: Not Found")) else Result.success(url.toByteArray()) }
        manager.restore()

        manager.startArchive(request)
        val done = awaitTask { it.state == DownloadState.COMPLETED }

        val zip = contents(File(done.filePath!!))
        assertEquals(setOf("a.jpg", "c.jpg", "not-saved.txt"), zip.keys)
        assertTrue(zip.getValue("not-saved.txt").contains("gone.jpg"))
    }

    @Test
    fun `a connection that stays down fails the archive after its tries, and a retry carries on without fetching anything twice`() = runBlocking {
        lister.entries = listOf("a.jpg" to "https://cdn/a", "b.jpg" to "https://cdn/b", "c.jpg" to "https://cdn/c")
        val drops = AtomicInteger(4) // the first ask and its three tries
        server.answer = { url ->
            if (url.endsWith("/b") && drops.getAndDecrement() > 0) Result.failure(SocketTimeoutException("timeout"))
            else Result.success(url.toByteArray())
        }
        manager.restore()

        manager.startArchive(request)
        val failed = awaitTask { it.state == DownloadState.FAILED }
        assertTrue(failed.error is DownloadError.Network)
        assertEquals(List(4) { "https://cdn/b" }, server.asked.filter { it.endsWith("/b") })

        manager.resumeDownload(failed.id)
        awaitTask { it.state == DownloadState.COMPLETED }

        assertEquals(listOf("https://cdn/a") + List(5) { "https://cdn/b" } + "https://cdn/c", server.asked)
        assertEquals("listed once", 1, lister.sources.size)
    }

    @Test
    fun `a file that fails on the way is asked for again, and the archive carries on by itself`() = runBlocking {
        lister.entries = listOf("a.jpg" to "https://cdn/a", "b.jpg" to "https://cdn/b", "c.jpg" to "https://cdn/c")
        val busy = AtomicInteger(1)
        server.answer = { url ->
            if (url.endsWith("/b") && busy.getAndDecrement() > 0) Result.failure(IOException("HTTP 503: Service Unavailable"))
            else Result.success(url.toByteArray())
        }
        manager.restore()

        manager.startArchive(request)
        val done = awaitTask { it.state == DownloadState.COMPLETED || it.state == DownloadState.FAILED }

        assertEquals(DownloadState.COMPLETED, done.state)
        assertEquals(setOf("a.jpg", "b.jpg", "c.jpg"), contents(File(done.filePath!!)).keys)
    }

    @Test
    fun `a file that keeps failing by itself is left out after its tries, and the rest are saved`() = runBlocking {
        lister.entries = listOf("a.jpg" to "https://cdn/a", "b.jpg" to "https://cdn/b", "c.jpg" to "https://cdn/c")
        server.answer = { url ->
            if (url.endsWith("/b")) Result.failure(IOException("unexpected end of stream"))
            else Result.success(url.toByteArray())
        }
        manager.restore()

        manager.startArchive(request)
        val done = awaitTask { it.state == DownloadState.COMPLETED || it.state == DownloadState.FAILED }

        assertEquals(DownloadState.COMPLETED, done.state)
        assertEquals(List(4) { "https://cdn/b" }, server.asked.filter { it.endsWith("/b") })
        val zip = contents(File(done.filePath!!))
        assertEquals(setOf("a.jpg", "c.jpg", "not-saved.txt"), zip.keys)
        assertTrue(zip.getValue("not-saved.txt").contains("b.jpg"))
    }

    @Test
    fun `a site that keeps saying too many requests stops the archive for a later retry`() = runBlocking {
        lister.entries = listOf("a.jpg" to "https://cdn/a", "b.jpg" to "https://cdn/b")
        server.answer = { Result.failure(IOException("HTTP 429: Too Many Requests")) }
        manager.restore()

        manager.startArchive(request)
        val failed = awaitTask { it.state == DownloadState.FAILED }

        assertEquals(DownloadError.HttpStatus(429), failed.error)
        assertTrue(failed.error!!.retryable)
        assertEquals("nothing is left out while the site is only busy", List(4) { "https://cdn/a" }, server.asked)
    }

    @Test
    fun `a pause stops the wait before a file is asked for again, so a resume does not fetch twice`() = runBlocking {
        manager = DownloadManager(
            context, dao, server, freeBytes = { Long.MAX_VALUE }, archiveLister = lister,
            archiveRetryDelaysMillis = listOf(300L),
        ).also { managers += it }
        lister.entries = listOf("a.jpg" to "https://cdn/a", "b.jpg" to "https://cdn/b")
        val dropOnce = AtomicInteger(1)
        server.answer = { url ->
            when {
                url.endsWith("/a") && dropOnce.getAndDecrement() > 0 -> Result.failure(SocketTimeoutException("timeout"))
                url.endsWith("/b") -> {
                    Thread.sleep(800) // still fetching when the stopped wait would have run out
                    Result.success(url.toByteArray())
                }
                else -> Result.success(url.toByteArray())
            }
        }
        manager.restore()

        manager.startArchive(request)
        val waiting = awaitTask { it.state == DownloadState.DOWNLOADING && server.asked.isNotEmpty() }
        delay(100) // the failure has been handled and the wait has begun
        manager.pauseDownload(waiting.id)
        manager.resumeDownload(waiting.id)
        awaitTask { it.state == DownloadState.COMPLETED }

        assertEquals(listOf("https://cdn/a", "https://cdn/a", "https://cdn/b"), server.asked)
    }

    @Test
    fun `a listing the site refuses is explained, and survives a restart`() = runBlocking {
        lister.failWith = ArchiveListingException("This account is private, and you don't follow it", retryable = false)
        manager.restore()

        manager.startArchive(request)
        val failed = awaitTask { it.state == DownloadState.FAILED }

        assertEquals("This account is private, and you don't follow it", failed.error?.userMessage)
        assertFalse(failed.error!!.retryable)
        delay(200)
        val stored = DownloadEntity.fromTask(failed).toTask().error
        assertEquals(failed.error?.userMessage, stored?.userMessage)
        assertEquals(false, stored?.retryable)
    }

    @Test
    fun `pausing while listing stops the listing, and resuming lists again and finishes`() = runBlocking {
        lister.entries = listOf("a.jpg" to "https://cdn/a")
        lister.hold = CompletableDeferred()
        manager.restore()

        manager.startArchive(request)
        val listing = awaitTask { it.archive?.listing == true }
        lister.awaitStarted()
        manager.pauseDownload(listing.id)
        awaitTask { it.state == DownloadState.PAUSED }
        lister.awaitReturned(1)

        lister.hold = null
        manager.resumeDownload(listing.id)
        awaitTask { it.state == DownloadState.COMPLETED }

        assertEquals(2, lister.sources.size)
        assertEquals(listOf("https://cdn/a"), server.asked)
    }

    @Test
    fun `an account with nothing to save says so`() = runBlocking {
        lister.entries = emptyList()
        manager.restore()

        manager.startArchive(request)
        val failed = awaitTask { it.state == DownloadState.FAILED }

        assertEquals("There was nothing to save", failed.error?.userMessage)
    }

    @Test
    fun `without a lister an archive fails with a reason instead of hanging`() = runBlocking {
        val bare = newManager(archiveLister = null)
        bare.restore()

        bare.startArchive(request)
        val failed = awaitTask(bare) { it.state == DownloadState.FAILED }

        assertTrue(failed.error is DownloadError.Listing)
    }

    @Test
    fun `an archive is never mistaken for a single file or a stream`() {
        val task = DownloadTask(id = 1, url = request.source, pageUrl = null, fileName = "x.zip", mimeType = "application/zip")
        assertTrue(task.isArchive)
        assertFalse(task.hasOwnRequest)
        assertFalse(DownloadManager.isStream(task.url, task.mimeType))
    }

    // ---- helpers ---- //

    private fun contents(zip: File): Map<String, String> = ZipFile(zip).use { file ->
        file.entries().asSequence().associate { entry ->
            entry.name to file.getInputStream(entry).bufferedReader().readText()
        }
    }

    private suspend fun awaitTask(
        on: DownloadManager = manager,
        predicate: (DownloadTask) -> Boolean,
    ): DownloadTask {
        repeat(250) {
            on.downloads.value.firstOrNull(predicate)?.let { return it }
            delay(20)
        }
        throw AssertionError("no task matched; tasks were ${on.downloads.value}")
    }
}

/** Answers each address with its own text, unless [answer] says otherwise; finishes at once. */
private class FileServer : MediaDownloader {
    val asked = CopyOnWriteArrayList<String>()
    val cookies = CopyOnWriteArrayList<String?>()
    var answer: (String) -> Result<ByteArray> = { Result.success(it.toByteArray()) }

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
        asked += url
        this.cookies.add(cookies)
        answer(url).fold(
            onSuccess = { bytes ->
                saveFile.parentFile?.mkdirs()
                saveFile.writeBytes(bytes)
                onProgress(saveFile.length(), saveFile.length())
                onComplete(Result.success(saveFile))
            },
            onFailure = { onComplete(Result.failure(it)) },
        )
    }

    override fun cancel(taskId: Long) = Unit
}

/** Writes [entries] as a finished list, or fails, or waits on [hold] until released or asked to stop. */
private class FakeLister : ArchiveLister {
    var entries: List<Pair<String, String>> = emptyList()
    var failWith: Exception? = null
    var hold: CompletableDeferred<Unit>? = null
    val sources = CopyOnWriteArrayList<String>()
    private val returned = CopyOnWriteArrayList<Unit>()

    override suspend fun list(source: String, list: File, stop: File, onFound: (Int) -> Unit) {
        sources += source
        try {
            failWith?.let { throw it }
            hold?.let { gate ->
                while (!gate.isCompleted && !stop.exists()) delay(10)
                if (stop.exists()) return
            }
            list.writeText(entries.joinToString("\n") { (name, url) -> """{"name": "$name", "url": "$url", "http_headers": {}}""" })
            onFound(entries.size)
        } finally {
            returned += Unit
        }
    }

    suspend fun awaitStarted() {
        repeat(250) {
            if (sources.isNotEmpty()) return
            delay(20)
        }
        throw AssertionError("the listing never started")
    }

    suspend fun awaitReturned(times: Int) {
        repeat(250) {
            if (returned.size >= times) return
            delay(20)
        }
        throw AssertionError("the listing did not stop")
    }
}
