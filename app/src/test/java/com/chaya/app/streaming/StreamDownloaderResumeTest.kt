package com.chaya.app.streaming

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Regression tests for the process-restart resume race: Media3 content IDs
 * must be derivable from the Room task id so a fresh StreamDownloader
 * resolves downloads persisted by a previous process instead of re-adding
 * them under new ids (orphaning cached segments) or silently no-oping.
 *
 * Seeding goes through the public API only — there is no public writable
 * download index — using a local socket that accepts connections and never
 * responds, so a download stays active deterministically instead of racing
 * to a failure state before it can be paused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StreamDownloaderResumeTest {

    private class FakeListener : StreamDownloader.Listener {
        val paused = mutableListOf<Long>()
        val completed = mutableListOf<Long>()
        val failed = mutableListOf<Long>()
        val progress = mutableListOf<Long>()

        override fun onStreamProgress(taskId: Long, downloadedBytes: Long, totalBytes: Long?) {
            progress += taskId
        }

        override fun onStreamCompleted(taskId: Long) {
            completed += taskId
        }

        override fun onStreamFailed(taskId: Long, reason: Int) {
            failed += taskId
        }

        override fun onStreamPaused(taskId: Long) {
            paused += taskId
        }

        /** Any event at all for the task id — proves the id mapped back from Media3. */
        fun anyEventFor(taskId: Long): Boolean =
            taskId in progress || taskId in completed || taskId in failed || taskId in paused

        /** Every task id that produced any event so far. */
        fun seenTaskIds(): Set<Long> = progress.toSet() + completed + failed + paused
    }

    private lateinit var context: Context
    private lateinit var listener: FakeListener
    private var streamDownloader: StreamDownloader? = null
    private var mediaServer: ServerSocket? = null
    private val heldSockets = CopyOnWriteArrayList<Socket>()

    private val taskId = 42L
    private val contentId = "chaya_task_$taskId"

    @Before
    fun setUp() {
        // StreamDownloader hard-codes Dispatchers.Main for its progress loop;
        // route it to a test dispatcher so the test doesn't need a real
        // Android main-looper bridge and the delay loop stays virtual.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = RuntimeEnvironment.getApplication()
        listener = FakeListener()
        // Defensive wipe so no test depends on another's leftover state.
        File(context.cacheDir, "media3-cache").deleteRecursively()
        context.deleteDatabase(StandaloneDatabaseProvider.DATABASE_NAME)
    }

    @After
    fun tearDown() {
        streamDownloader?.let { sd -> runCatching { sd.release() } }
        streamDownloader = null
        mediaServer?.let { runCatching { it.close() } }
        mediaServer = null
        heldSockets.forEach { runCatching { it.close() } }
        heldSockets.clear()
        Dispatchers.resetMain()
    }

    /** Starts an HTTP server that accepts connections and never responds. */
    private fun startHangingMediaServer(): String {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        mediaServer = server
        Thread {
            try {
                while (!server.isClosed) {
                    heldSockets += server.accept()
                }
            } catch (_: IOException) {
                // server closed — expected during teardown
            }
        }.apply { isDaemon = true }.start()
        return "http://127.0.0.1:${server.localPort}/video.m3u8"
    }

    /** Drains posted main-looper messages until the predicate holds or times out. */
    private fun awaitUntil(timeoutMillis: Long = 10_000, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (predicate()) return true
            Thread.sleep(50)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return predicate()
    }

    private fun newDownloader(): StreamDownloader =
        StreamDownloader(context, listener).also { streamDownloader = it }

    private fun prefixDownloads(sd: StreamDownloader): List<Download> =
        sd.downloadManager.currentDownloads.filter { it.request.id.startsWith("chaya_task_") }

    /**
     * Instance A plays the role of the previous app process: start a real
     * Media3 download (against the never-responding socket, so it stays
     * active rather than failing), pause it — the STOPPED row persists in
     * Media3's on-disk index — then "die" by releasing everything.
     */
    private fun seedPausedDownloadViaPreviousProcess(hungUrl: String) {
        val previous = StreamDownloader(context, FakeListener())
        previous.startStreamDownload(
            taskId, hungUrl, "application/x-mpegurl", userAgent = null, cookies = null
        )
        val started = awaitUntil { prefixDownloads(previous).any { it.request.id == contentId } }
        check(started) { "download never appeared in the previous process" }
        previous.pauseStream(taskId)
        val paused = awaitUntil {
            prefixDownloads(previous).firstOrNull { it.request.id == contentId }
                ?.state == Download.STATE_STOPPED
        }
        check(paused) { "download never reached STOPPED in the previous process" }
        previous.release()
    }

    @Test
    fun contentIdsDeriveFromTaskIdsAndRejectForeignIds() {
        val sd = newDownloader()
        assertEquals(taskId, sd.taskIdFor("chaya_task_$taskId"))
        assertNull(sd.taskIdFor("chaya_task_notanumber"))
        // Old counter-scheme ids and arbitrary ids must not map to any task.
        assertNull(sd.taskIdFor("chaya_12"))
        assertNull(sd.taskIdFor("completely-unrelated-id"))
        assertFalse(sd.taskIdFor("chaya_task_0") == null)
    }

    @Test
    fun freshInstanceSeesPausedDownloadPersistedByPreviousProcess() {
        val hungUrl = startHangingMediaServer()
        seedPausedDownloadViaPreviousProcess(hungUrl)
        val sd = newDownloader()

        val loaded = awaitUntil { prefixDownloads(sd).any { it.request.id == contentId } }
        assertTrue("fresh instance never loaded the persisted download", loaded)

        val download = prefixDownloads(sd).first { it.request.id == contentId }
        assertEquals(Download.STATE_STOPPED, download.state)
        assertEquals(1, download.stopReason)
    }

    @Test
    fun resumeOnFreshInstanceMapsBackToOriginalTaskWithoutReAdding() {
        val hungUrl = startHangingMediaServer()
        seedPausedDownloadViaPreviousProcess(hungUrl)
        val sd = newDownloader()
        assertTrue(awaitUntil { prefixDownloads(sd).any { it.request.id == contentId } })

        sd.resumeStream(taskId, hungUrl, "application/x-mpegurl", userAgent = null, cookies = null)

        // The resumed download must surface as task 42 — not a phantom new
        // task. The hung socket keeps it in QUEUED/DOWNLOADING, so the event
        // set stays deterministic.
        val mapped = awaitUntil { listener.anyEventFor(taskId) }
        assertTrue("resume on fresh instance never mapped an event back to task $taskId", mapped)

        assertEquals(
            "resume produced events for tasks other than $taskId",
            setOf(taskId),
            listener.seenTaskIds()
        )
        assertTrue(listener.paused.isEmpty())
    }

    @Test
    fun startOnAlreadyKnownTaskClearsStopReasonInsteadOfReAdding() {
        val hungUrl = startHangingMediaServer()
        seedPausedDownloadViaPreviousProcess(hungUrl)
        val sd = newDownloader()
        assertTrue(awaitUntil { prefixDownloads(sd).any { it.request.id == contentId } })

        sd.startStreamDownload(
            taskId, hungUrl, "application/x-mpegurl", userAgent = null, cookies = null
        )

        val cleared = awaitUntil { listener.anyEventFor(taskId) }
        assertTrue("start on known task never cleared the stop reason", cleared)

        assertEquals(setOf(taskId), listener.seenTaskIds())
    }

    @Test
    fun unknownTaskIdsAreSafeNoOps() {
        val sd = newDownloader()

        sd.pauseStream(999L)
        sd.stopStream(999L)
        sd.deleteStream(999L)

        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(prefixDownloads(sd).isEmpty())
        assertTrue("unknown ids must not produce listener events", listener.seenTaskIds().isEmpty())
    }

    @Test
    fun deleteStreamRemovesPersistedDownload() {
        val hungUrl = startHangingMediaServer()
        seedPausedDownloadViaPreviousProcess(hungUrl)
        val sd = newDownloader()
        assertTrue(awaitUntil { prefixDownloads(sd).any { it.request.id == contentId } })

        sd.deleteStream(taskId)

        val removed = awaitUntil { prefixDownloads(sd).isEmpty() }
        assertTrue("deleteStream left the persisted download in place", removed)
    }
}
