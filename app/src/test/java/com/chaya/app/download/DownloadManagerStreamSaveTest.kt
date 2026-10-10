package com.chaya.app.download

import android.content.Context
import androidx.room.Room
import com.chaya.app.database.ChayaDatabase
import com.chaya.app.database.DownloadDao
import com.chaya.app.database.DownloadEntity
import com.chaya.app.streaming.StreamExport
import com.chaya.app.streaming.StreamExportException
import com.chaya.app.streaming.StreamExporter
import kotlinx.coroutines.CompletableDeferred
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
 * A finished stream is saved as an MP4 file: completed → saving → saved, with the cached copy kept whenever that
 * does not work, so the stream still plays and can be saved again. The export itself needs a device
 * (StreamExportOnDeviceTest); here it is a stand-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadManagerStreamSaveTest {

    private lateinit var context: Context
    private lateinit var db: ChayaDatabase
    private lateinit var dao: DownloadDao
    private lateinit var exporter: FakeExporter
    private var freeSpace = Long.MAX_VALUE
    private lateinit var manager: DownloadManager

    /** Writes a few bytes as "the MP4", or fails, or waits, as a test asks. */
    private class FakeExporter : StreamExporter {
        var cached: Long? = 5_000_000
        var failWith: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        var result = StreamExport(hasVideo = true, reencoded = false)
        val exports = CopyOnWriteArrayList<File>()
        val removed = CopyOnWriteArrayList<Long>()

        override fun cachedBytes(taskId: Long): Long? = cached

        override suspend fun export(taskId: Long, output: File, onProgress: (Float) -> Unit): StreamExport {
            exports += output
            output.writeBytes(ByteArray(1234))
            onProgress(0.5f)
            gate?.await()
            failWith?.let { throw it }
            onProgress(1f)
            return result
        }

        override fun removeCached(taskId: Long) {
            removed += taskId
        }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, ChayaDatabase::class.java).allowMainThreadQueries().build()
        dao = db.downloadDao()
        exporter = FakeExporter()
        manager = DownloadManager(context, dao, freeBytes = { freeSpace }, streamExporter = exporter)
        File(context.filesDir, "downloads").listFiles()?.forEach { it.deleteRecursively() }
    }

    @After
    fun tearDown() {
        runBlocking { manager.drainBackgroundWork() }
        db.close()
    }

    /** A stream download as Room keeps it: no file, the stream's address. */
    private suspend fun stream(id: Long = 1, state: DownloadState = DownloadState.DOWNLOADING): DownloadTask {
        val task = DownloadTask(
            id = id,
            url = "https://cdn.example/show/master.m3u8",
            pageUrl = "https://cdn.example/show",
            fileName = "Show (720p).mp4",
            mimeType = "application/x-mpegURL",
            state = state,
            downloadedBytes = 5_000_000,
            title = "Show",
        )
        dao.insert(DownloadEntity.fromTask(task))
        manager.restore()
        return task
    }

    private fun task(id: Long = 1) = manager.downloads.value.single { it.id == id }

    private suspend fun awaitUntil(predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (!predicate() && System.currentTimeMillis() < deadline) delay(10)
        return predicate()
    }

    @Test
    fun `a finished stream is saved as an MP4 file and its cached copy removed`() = runBlocking {
        stream()
        exporter.gate = CompletableDeferred()

        manager.streamCompleted(1)

        assertTrue("shows it is saving", awaitUntil { task().savingAsFile == 0.5f })
        assertEquals(DownloadState.DOWNLOADING, task().state)
        exporter.gate!!.complete(Unit)
        assertTrue(awaitUntil { task().state == DownloadState.COMPLETED && task().filePath != null })

        val saved = task()
        assertEquals("Show (720p).mp4", saved.fileName)
        assertTrue(File(saved.filePath!!).isFile)
        assertEquals("video/mp4", saved.mimeType)
        assertEquals(1234L, saved.downloadedBytes)
        assertNull(saved.savingAsFile)
        assertFalse(saved.canSaveAsFile)
        assertEquals(listOf(1L), exporter.removed.toList())
        assertTrue("written beside the file, then renamed", exporter.exports.single().name.endsWith(".mp4.saving"))
        assertFalse(exporter.exports.single().exists())
        assertTrue(awaitUntil { runBlocking { dao.getAllOnce() }.single().filePath == saved.filePath })
    }

    @Test
    fun `a failed export keeps the cached copy, which still plays, and Save as MP4 tries again`() = runBlocking {
        stream()
        exporter.failWith = StreamExportException("muxer said no")

        manager.streamCompleted(1)

        assertTrue(awaitUntil { task().state == DownloadState.COMPLETED })
        val kept = task()
        assertNull(kept.filePath)
        assertTrue(kept.canSaveAsFile)
        assertTrue(kept.saveNote!!.contains("couldn't save it as MP4"))
        assertEquals(emptyList<Long>(), exporter.removed.toList())
        assertFalse("the half-written file is gone", exporter.exports.single().exists())
        assertTrue(awaitUntil { runBlocking { dao.getAllOnce() }.single().state == DownloadState.COMPLETED })

        exporter.failWith = null
        manager.saveAsFile(1)

        // The file comes first, then the completion that also copies it to Movies.
        assertTrue(awaitUntil { task().filePath != null && task().state == DownloadState.COMPLETED })
        assertNull(task().saveNote)
        assertEquals(listOf(1L), exporter.removed.toList())
    }

    @Test
    fun `a stream downloaded before this can be saved as MP4 from its card`() = runBlocking {
        stream(state = DownloadState.COMPLETED)
        assertTrue(task().canSaveAsFile)

        manager.saveAsFile(1)

        // The file comes first, then the completion that also copies it to Movies.
        assertTrue(awaitUntil { task().filePath != null && task().state == DownloadState.COMPLETED })
    }

    @Test
    fun `without room for both copies the stream stays in the cache and says why`() = runBlocking {
        stream()
        freeSpace = 5_000_000 + DownloadManager.MIN_FREE_BYTES_TO_START - 1

        manager.streamCompleted(1)

        assertTrue(awaitUntil { task().state == DownloadState.COMPLETED })
        assertTrue(task().saveNote!!.contains("not enough space"))
        assertEquals(emptyList<File>(), exporter.exports.toList())
    }

    @Test
    fun `a stream not all in the cache is not exported`() = runBlocking {
        stream()
        exporter.cached = null

        manager.streamCompleted(1)

        assertTrue(awaitUntil { task().state == DownloadState.COMPLETED })
        assertNull(task().filePath)
        assertEquals(emptyList<File>(), exporter.exports.toList())
    }

    @Test
    fun `pausing while it saves stops the export and keeps the cached copy`() = runBlocking {
        stream()
        exporter.gate = CompletableDeferred()
        manager.streamCompleted(1)
        assertTrue(awaitUntil { task().savingAsFile != null })

        manager.pauseDownload(1)

        assertTrue(awaitUntil { task().state == DownloadState.COMPLETED })
        assertNull(task().filePath)
        assertTrue(task().canSaveAsFile)
        assertFalse(exporter.exports.single().exists())
        assertEquals(emptyList<Long>(), exporter.removed.toList())
    }

    @Test
    fun `deleting while it saves leaves nothing behind`() = runBlocking {
        stream()
        exporter.gate = CompletableDeferred()
        manager.streamCompleted(1)
        assertTrue(awaitUntil { task().savingAsFile != null })

        manager.deleteTask(1)

        assertTrue(awaitUntil { !exporter.exports.single().exists() })
        assertTrue(manager.downloads.value.isEmpty())
        assertEquals(emptyList<String>(), File(context.filesDir, "downloads").list()!!.toList())
    }

    @Test
    fun `a stream that had to be re-encoded says so`() = runBlocking {
        stream()
        exporter.result = StreamExport(hasVideo = true, reencoded = true)

        manager.streamCompleted(1)

        assertTrue(awaitUntil { task().filePath != null && task().state == DownloadState.COMPLETED })
        assertNotNull(task().saveNote)
        assertTrue(task().saveNote!!.contains("re-encoded"))
    }

    @Test
    fun `a stream of sound only is saved as M4A`() = runBlocking {
        stream()
        exporter.result = StreamExport(hasVideo = false, reencoded = false)

        manager.streamCompleted(1)

        assertTrue(awaitUntil { task().filePath != null && task().state == DownloadState.COMPLETED })
        assertEquals("Show (720p).m4a", task().fileName)
        assertEquals("audio/mp4", task().mimeType)
    }
}
