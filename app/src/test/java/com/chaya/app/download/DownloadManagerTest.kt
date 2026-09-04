package com.chaya.app.download

import android.content.Context
import androidx.room.Room
import app.cash.turbine.test
import com.chaya.app.database.ChayaDatabase
import com.chaya.app.database.DownloadDao
import com.chaya.app.database.DownloadEntity
import com.chaya.app.download.DownloadError
import com.chaya.app.download.DownloadError.HttpStatus
import com.chaya.app.model.DetectedMedia
import com.chaya.app.model.DetectionSource
import java.io.File
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Drives [DownloadManager]'s whole HTTP state machine against a real
 * in-memory Room database, with a fake [MediaDownloader] capturing every
 * start/cancel so each transition's downloader invocation (taskId, resume
 * offset, save file) is asserted alongside the state flip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadManagerTest {

    private lateinit var context: Context
    private lateinit var db: ChayaDatabase
    private lateinit var dao: DownloadDao
    private lateinit var downloader: FakeDownloader
    private lateinit var manager: DownloadManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, ChayaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.downloadDao()
        downloader = FakeDownloader()
        manager = DownloadManager(context, dao, downloader)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- startDownload ---- //

    @Test
    fun `startDownload inserts a DOWNLOADING task and starts the downloader with matching identity`() =
        runBlocking {
            manager.restore()
            manager.downloads.test {
                assertEquals(emptyList<DownloadTask>(), awaitItem())
                manager.startDownload(media())
                val task = awaitItem().single()
                assertEquals(DownloadState.DOWNLOADING, task.state)
                assertEquals(media().url, task.url)

                // startHttp appends the task before invoking the downloader,
                // so wait for the capture rather than assuming it already ran.
                assertTrue(awaitUntil { downloader.starts.size == 1 })
                val call = downloader.starts.single()
                assertEquals(task.id, call.taskId)
                assertEquals(task.url, call.url)
                assertEquals(task.filePath, call.saveFile.absolutePath)
                assertEquals(0L, call.fromBytes)
                assertEquals(media().pageUrl, call.referer)
            }
            assertEquals(DownloadState.DOWNLOADING, awaitDbState(1, DownloadState.DOWNLOADING).state)
        }

    // ---- pauseDownload ---- //

    @Test
    fun `pauseDownload on a DOWNLOADING task pauses, cancels the transfer, and keeps partial bytes`() =
        runBlocking {
            manager.restore()
            manager.startDownload(media())
            val started = awaitTaskState(1, DownloadState.DOWNLOADING)
            File(started.filePath!!).writeText("partial-bytes")

            manager.pauseDownload(1)

            val paused = awaitTaskState(1, DownloadState.PAUSED)
            assertEquals(13L, paused.downloadedBytes)
            assertEquals(listOf(1L), downloader.cancelled)
        }

    @Test
    fun `pauseDownload on a task that is not DOWNLOADING is a no-op`() = runBlocking {
        manager.restore()
        manager.startDownload(media())
        awaitTaskState(1, DownloadState.DOWNLOADING)

        manager.pauseDownload(1)
        assertEquals(DownloadState.PAUSED, awaitTaskState(1, DownloadState.PAUSED).state)

        manager.pauseDownload(1)

        assertEquals(DownloadState.PAUSED, awaitTaskState(1, DownloadState.PAUSED).state)
        assertTrue("initial start never recorded", awaitUntil { downloader.starts.size == 1 })
        assertEquals(1, downloader.cancelled.size)
    }

    // ---- storage pre-flight (Phase 3.2) ---- //

    @Test
    fun `startDownload fails fast with StorageFull when disk is full`() = runBlocking {
        val fullManager = DownloadManager(context, dao, downloader, freeBytes = { 0L })
        fullManager.restore()
        fullManager.startDownload(media())

        val task = awaitManagerTaskState(fullManager, 1, DownloadState.FAILED)
        assertEquals(DownloadError.StorageFull, task.error)
        assertTrue("downloader must never start", downloader.starts.isEmpty())
    }

    @Test
    fun `resumeDownload fails fast with StorageFull when disk filled while paused`() = runBlocking {
        manager.restore()
        manager.startDownload(media())
        awaitTaskState(1, DownloadState.DOWNLOADING)
        awaitStart(1)
        manager.pauseDownload(1)
        awaitTaskState(1, DownloadState.PAUSED)
        val startsBefore = downloader.starts.size

        // Disk filled while paused: rebuild the manager with a full disk over
        // the same DAO rows (production: same process, StatFs now reports low).
        val fullManager = DownloadManager(context, dao, downloader, freeBytes = { 0L })
        fullManager.restore()
        fullManager.resumeDownload(1)

        val task = awaitManagerTaskState(fullManager, 1, DownloadState.FAILED)
        assertEquals(DownloadError.StorageFull, task.error)
        assertEquals("resume must not re-invoke the downloader", startsBefore, downloader.starts.size)
    }

    /** Polls another manager instance's flow until [id] reaches [state]. */
    private suspend fun awaitManagerTaskState(
        other: DownloadManager,
        id: Long,
        state: DownloadState,
    ): DownloadTask {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            other.downloads.value.firstOrNull { it.id == id }
                ?.takeIf { it.state == state }
                ?.let { return it }
            delay(20)
        }
        throw AssertionError("task $id never reached $state; downloads=${other.downloads.value}")
    }

    // ---- resumeDownload ---- //

    @Test
    fun `resumeDownload on a PAUSED task re-invokes the downloader from the partial offset`() =
        runBlocking {
            manager.restore()
            manager.startDownload(media())
            val started = awaitTaskState(1, DownloadState.DOWNLOADING)
            // Wait for the fake to record the start before writing the
            // partial: startHttp launches the downloader asynchronously, so
            // pausing immediately can win the race and record fromBytes = 0.
            awaitStart(1)
            File(started.filePath!!).writeText("partial")
            manager.pauseDownload(1)
            awaitTaskState(1, DownloadState.PAUSED)

            manager.resumeDownload(1)

            assertTrue("resume never re-invoked the downloader", awaitUntil { downloader.starts.size == 2 })
            assertEquals(1L, downloader.starts.last().taskId)
            assertEquals(7L, downloader.starts.last().fromBytes)
            assertEquals(
                "resume must reuse the original partial file",
                File(started.filePath!!),
                downloader.starts.last().saveFile
            )
            assertEquals(DownloadState.DOWNLOADING, awaitTaskState(1, DownloadState.DOWNLOADING).state)
        }

    @Test
    fun `resumeDownload on a FAILED task retries with the same partial file`() = runBlocking {
        manager.restore()
        manager.startDownload(media())
        awaitTaskState(1, DownloadState.DOWNLOADING)
        val file = File(manager.downloads.value.single().filePath!!).apply { writeText("partial") }
        awaitStart(1)
        downloader.completeLastWithFailure(1, IOException("HTTP 500: boom"))
        assertEquals(DownloadState.FAILED, awaitTaskState(1, DownloadState.FAILED).state)
        assertEquals(HttpStatus(500), awaitTaskState(1, DownloadState.FAILED).error)
        assertEquals("HTTP", awaitDbState(1, DownloadState.FAILED).errorKind)
        assertEquals(500, awaitDbState(1, DownloadState.FAILED).errorCode)

        manager.resumeDownload(1)

        assertEquals(DownloadState.DOWNLOADING, awaitTaskState(1, DownloadState.DOWNLOADING).state)
        assertTrue("retry never re-invoked the downloader", awaitUntil { downloader.starts.size == 2 })
        assertEquals("retry must keep writing the same partial file", file, downloader.starts.last().saveFile)
    }

    @Test
    fun `resumeDownload on a COMPLETED task is a no-op`() = runBlocking {
        manager.restore()
        manager.startDownload(media())
        awaitTaskState(1, DownloadState.DOWNLOADING)
        awaitStart(1)
        downloader.completeLast(1)
        awaitTaskState(1, DownloadState.COMPLETED)

        manager.resumeDownload(1)
        delay(250)

        assertEquals(DownloadState.COMPLETED, awaitTaskState(1, DownloadState.COMPLETED).state)
        assertEquals("no-op resume must not re-start the downloader", 1, downloader.starts.size)
    }

    // ---- cancelDownload ---- //

    @Test
    fun `cancelDownload on a DOWNLOADING task marks CANCELLED and cancels the transfer`() =
        runBlocking {
            manager.restore()
            manager.startDownload(media())
            awaitTaskState(1, DownloadState.DOWNLOADING)

            manager.cancelDownload(1)

            assertEquals(DownloadState.CANCELLED, awaitTaskState(1, DownloadState.CANCELLED).state)
            assertEquals(listOf(1L), downloader.cancelled)
            assertEquals(DownloadState.CANCELLED, awaitDbState(1, DownloadState.CANCELLED).state)
        }

    // ---- deleteTask ---- //

    @Test
    fun `deleteTask removes the flow entry, the Room row, and the on-disk file`() = runBlocking {
        manager.restore()
        manager.startDownload(media())
        val task = awaitTaskState(1, DownloadState.DOWNLOADING)
        val file = File(task.filePath!!).apply { writeText("partial") }

        manager.deleteTask(1)

        assertTrue(awaitRowGone(1))
        assertTrue(awaitUntil { manager.downloads.value.isEmpty() })
        assertTrue("partial file must be deleted with the task", !file.exists())
    }

    // ---- restore / id continuity ---- //

    @Test
    fun `restore flips persisted DOWNLOADING and QUEUED rows to PAUSED and keeps idCounter past max id`() =
        runBlocking {
            dao.insert(entity(5, DownloadState.DOWNLOADING))
            dao.insert(entity(6, DownloadState.QUEUED))
            dao.insert(entity(7, DownloadState.COMPLETED))

            manager.restore()

            val tasks = manager.downloads.value.associateBy { it.id }
            assertEquals(DownloadState.PAUSED, tasks.getValue(5L).state)
            assertEquals(DownloadState.PAUSED, tasks.getValue(6L).state)
            assertEquals(DownloadState.COMPLETED, tasks.getValue(7L).state)
            assertEquals(DownloadState.PAUSED, dao.getById(5L)!!.state)

            manager.startDownload(media())
            assertEquals(
                "new ids must continue past the restored max id",
                8L,
                awaitTaskState(8, DownloadState.DOWNLOADING).id
            )
        }

    // ---- unknown ids ---- //

    @Test
    fun `operations on unknown task ids are safe no-ops`() = runBlocking {
        manager.restore()

        manager.pauseDownload(99)
        manager.resumeDownload(99)
        manager.cancelDownload(99)
        // deleteTask defensively cancels even ids it cannot find — harmless,
        // but it must not mint a start or leave flow/DB state behind.
        manager.deleteTask(99)
        delay(250)

        assertTrue(manager.downloads.value.isEmpty())
        assertEquals(listOf(99L), downloader.cancelled)
        assertEquals(0, downloader.starts.size)
        assertNull(dao.getById(99))
    }

    // ---- fixtures ---- //

    private fun media(
        url: String = "https://cdn.example.com/video/clip.mp4",
        mimeType: String? = "video/mp4"
    ) = DetectedMedia(
        url = url,
        pageUrl = "https://cdn.example.com/page",
        mimeType = mimeType,
        source = DetectionSource.NETWORK
    )

    private fun entity(id: Long, state: DownloadState) = DownloadEntity(
        id = id,
        url = "https://cdn.example.com/file-$id.mp4",
        pageUrl = null,
        fileName = "file-$id.mp4",
        mimeType = "video/mp4",
        filePath = null,
        exportedUri = null,
        errorMessage = null,
        errorKind = null,
        errorCode = null,
        downloadedBytes = 0,
        totalBytes = null,
        state = state,
        createdAt = 0,
        updatedAt = 0
    )

    /** Polls until [predicate] holds, so scope-launched transitions can settle. */
    private suspend fun awaitUntil(timeoutMs: Long = TIMEOUT_MS, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            delay(20)
        }
        return predicate()
    }

    /** Polls the manager flow until [id] reaches [state], then returns it. */
    private suspend fun awaitTaskState(id: Long, state: DownloadState): DownloadTask {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            manager.downloads.value.firstOrNull { it.id == id }
                ?.takeIf { it.state == state }
                ?.let { return it }
            delay(20)
        }
        throw AssertionError("task $id never reached $state; downloads=${manager.downloads.value}")
    }

    /** Polls the Room row until [id] reaches [state], then returns it. */
    private suspend fun awaitDbState(id: Long, state: DownloadState): DownloadEntity {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        var last: DownloadEntity? = null
        while (System.currentTimeMillis() < deadline) {
            last = dao.getById(id)
            if (last?.state == state) return last
            delay(20)
        }
        throw AssertionError("Room row $id never reached $state; last=$last")
    }

    /** Polls until the Room row for [id] is gone entirely. */
    private suspend fun awaitRowGone(id: Long): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (dao.getById(id) == null) return true
            delay(20)
        }
        return false
    }

    /**
     * Waits for the fake to record the start for [taskId] before driving its
     * callback: startHttp makes the task visible in the flow and persists the
     * row before invoking the downloader, so a callback fired immediately
     * after observing the task would silently miss its target.
     */
    private suspend fun awaitStart(taskId: Long): FakeDownloader.Start {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            downloader.starts.lastOrNull { it.taskId == taskId }?.let { return it }
            delay(20)
        }
        throw AssertionError("downloader never recorded a start for task $taskId; starts=${downloader.starts}")
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}

/**
 * Captures every [MediaDownloader] invocation so tests can drive the
 * manager's callbacks deterministically. Completion is dropped once cancel
 * ran for a task, mirroring the silent-cancel contract the real
 * [HttpDownloader] documents.
 */
private class FakeDownloader : MediaDownloader {

    class Start(
        val taskId: Long,
        val url: String,
        val saveFile: File,
        val referer: String?,
        val fromBytes: Long,
        val onComplete: (Result<File>) -> Unit
    )

    val starts = mutableListOf<Start>()
    val cancelled = mutableListOf<Long>()
    private val cancelledIds = mutableSetOf<Long>()

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
        onComplete: (Result<File>) -> Unit
    ) {
        synchronized(starts) {
            starts += Start(taskId, url, saveFile, referer, fromBytes, onComplete)
        }
    }

    override fun cancel(taskId: Long) {
        synchronized(cancelled) {
            cancelled += taskId
            cancelledIds += taskId
        }
    }

    /** Completes the latest start for [taskId] unless it was cancelled. */
    fun completeLast(taskId: Long) {
        finish(taskId) { it.onComplete(Result.success(it.saveFile)) }
    }

    /** Fails the latest start for [taskId] unless it was cancelled. */
    fun completeLastWithFailure(taskId: Long, error: IOException) {
        finish(taskId) { it.onComplete(Result.failure(error)) }
    }

    private fun finish(taskId: Long, fire: (Start) -> Unit) {
        val call = synchronized(starts) { starts.lastOrNull { it.taskId == taskId } } ?: return
        synchronized(cancelledIds) {
            if (taskId !in cancelledIds) fire(call)
        }
    }
}
