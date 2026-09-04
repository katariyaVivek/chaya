package com.chaya.app.diagnostics

import androidx.room.Room
import com.chaya.app.database.ChayaDatabase
import com.chaya.app.download.DownloadManager
import com.chaya.app.download.DownloadState
import com.chaya.app.download.MediaDownloader
import com.chaya.app.model.DetectedMedia
import com.chaya.app.model.DetectionSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * Proves the log records a full detect→download→fail cycle: media
 * detection, task creation, the DOWNLOADING flip, and the classified
 * failure — with scrubbed URLs throughout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventLogTest {

    private lateinit var log: EventLog
    private lateinit var db: ChayaDatabase
    private lateinit var manager: DownloadManager

    @Before
    fun setUp() {
        FakeDownloader.lastOnComplete = null
        val context = RuntimeEnvironment.getApplication()
        log = EventLog(context)
        db = Room.inMemoryDatabaseBuilder(context, ChayaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = DownloadManager(context, db.downloadDao(), FakeDownloader(), eventLog = log)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `detect to failed-download produces a chronological scrubbed trail`() = runBlocking {
        manager.restore()

        // Detection side: URL carries a token that must never reach the log.
        log.recordSync(
            ChayaEvent.MediaDetected(
                url = ChayaEvent.scrubbed("https://cdn.example.com/a.mp4?sig=secret")!!,
                source = DetectionSource.NETWORK.name,
                mimeType = "video/mp4",
            )
        )
        manager.startDownload(
            DetectedMedia(
                url = "https://cdn.example.com/a.mp4?sig=secret",
                pageUrl = "https://cdn.example.com/watch?session=xyz",
                mimeType = "video/mp4",
                source = DetectionSource.NETWORK,
            )
        )
        awaitUntil { manager.downloads.value.isNotEmpty() }
        // The fake records its start asynchronously inside startHttp — wait
        // for the captured callback before firing the failure.
        awaitUntil { FakeDownloader.lastOnComplete != null }
        awaitLog { events -> events.any { it is ChayaEvent.DownloadStateChanged } }
        // Fail the in-flight download through the fake's captured callback.
        FakeDownloader.lastOnComplete?.invoke(Result.failure(IOException("HTTP 403: Forbidden")))
        awaitLog { events -> events.any { it is ChayaEvent.DownloadFailed } }

        val events = awaitLogAndSnapshot(
            predicate = { snap -> snap.filterIsInstance<ChayaEvent.DownloadFailed>().size == 1 },
        )
        assertTrue(events.any {
            it is ChayaEvent.MediaDetected && it.url == "https://cdn.example.com/a.mp4"
        })
        assertTrue("no raw token may appear in any event text", events.none {
            logText(it).contains("sig=secret") || logText(it).contains("session=xyz")
        })
        val failed = events.filterIsInstance<ChayaEvent.DownloadFailed>().single()
        assertEquals("HttpStatus", failed.errorKind)
        assertEquals(false, failed.retryable)
        // State flips bracket the failure: NONE -> DOWNLOADING ... -> FAILED.
        val flips = events.filterIsInstance<ChayaEvent.DownloadStateChanged>()
        assertTrue(flips.any { it.from == "NONE" && it.to == "DOWNLOADING" })
        assertTrue(flips.any { it.to == "FAILED" })
        assertEquals(DownloadState.FAILED, manager.downloads.value.single().state)
        assertTrue(failed.taskId == manager.downloads.value.single().id)
    }

    @Test
    fun `buffer caps at 500 and export stays chronological`() = runBlocking {
        repeat(EventLog.MAX_ENTRIES + 50) { i ->
            log.recordSync(ChayaEvent.PageLoaded(url = "https://example.com/$i"))
        }

        val snapshot = logSnapshot()
        assertEquals(EventLog.MAX_ENTRIES, snapshot.size)
        assertEquals("https://example.com/50", (snapshot.first() as ChayaEvent.PageLoaded).url)
        val text = log.exportText()
        assertTrue(text.lines().size >= EventLog.MAX_ENTRIES)
        assertTrue(text.indexOf("https://example.com/50") < text.indexOf("https://example.com/549"))
    }

    private suspend fun logSnapshot() = log.snapshot()

    /** Polls until [predicate] holds on the snapshot, then returns that snapshot. */
    private suspend fun awaitLogAndSnapshot(
        predicate: (List<ChayaEvent>) -> Boolean,
    ): List<ChayaEvent> {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val snapshot = log.snapshot()
            if (predicate(snapshot)) return snapshot
            delay(20)
        }
        return log.snapshot()
    }

    /** Polls the log (suspend snapshot) until [predicate] holds. */
    private suspend fun awaitLog(predicate: (List<ChayaEvent>) -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate(log.snapshot())) return true
            delay(20)
        }
        return predicate(log.snapshot())
    }

    private fun logText(event: ChayaEvent): String = when (event) {
        is ChayaEvent.MediaDetected -> event.url
        is ChayaEvent.DownloadStateChanged -> "${event.from} ${event.to}"
        is ChayaEvent.DownloadFailed -> event.errorKind
        is ChayaEvent.PageLoaded -> event.url
        is ChayaEvent.CaughtException -> event.message ?: ""
    }

    private suspend fun awaitUntil(predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            delay(20)
        }
        return predicate()
    }

    /** Captures the completion callback so the test drives failures deterministically. */
    private class FakeDownloader : MediaDownloader {
        override fun start(
            taskId: Long,
            url: String,
            saveFile: File,
            userAgent: String?,
            cookies: String?,
            referer: String?,
            fromBytes: Long,
            onMeta: ((String?) -> Unit)?,
            onProgress: (Long, Long?) -> Unit,
            onComplete: (Result<File>) -> Unit,
        ) {
            lastOnComplete = onComplete
        }

        override fun cancel(taskId: Long) = Unit

        companion object {
            var lastOnComplete: ((Result<File>) -> Unit)? = null
        }
    }
}
