package com.chaya.app.streaming

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A stream download from start to finish through the real Media3 pipeline: a tiny HLS stream (a playlist of
 * playlists, a playlist of two segments, and the segments) is served from a local server and must end up in
 * the cache. Two bugs once made every stream download fail or never begin, and no test noticed, because the
 * others only check that a download shows up in the list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StreamDownloaderDownloadTest {

    private class RecordingListener : StreamDownloader.Listener {
        val completed = CopyOnWriteArrayList<Long>()
        val failed = CopyOnWriteArrayList<Pair<Long, Exception?>>()

        override fun onStreamProgress(taskId: Long, downloadedBytes: Long, totalBytes: Long?) = Unit
        override fun onStreamCompleted(taskId: Long) {
            completed += taskId
        }

        override fun onStreamFailed(taskId: Long, reason: Int, cause: Exception?) {
            failed += taskId to cause
        }

        override fun onStreamPaused(taskId: Long) = Unit
    }

    private lateinit var context: Context
    private lateinit var listener: RecordingListener
    private lateinit var server: MockWebServer
    private var streamDownloader: StreamDownloader? = null
    private val requested = CopyOnWriteArrayList<String>()

    private val taskId = 7L
    private val contentId = "chaya_task_$taskId"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = RuntimeEnvironment.getApplication()
        listener = RecordingListener()
        File(context.cacheDir, "media3-cache").deleteRecursively()
        context.deleteDatabase(StandaloneDatabaseProvider.DATABASE_NAME)
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        streamDownloader?.let { runCatching { it.release() } }
        runCatching { server.shutdown() }
        Dispatchers.resetMain()
    }

    private fun serve(handler: (path: String) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requested += request.path.orEmpty()
                return handler(request.path.orEmpty())
            }
        }
        server.start()
    }

    private fun newDownloader(): StreamDownloader = StreamDownloader(context, listener).also { streamDownloader = it }

    private fun awaitUntil(timeoutMillis: Long = 30_000, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (predicate()) return true
            Thread.sleep(50)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return predicate()
    }

    private fun hlsStream(path: String): MockResponse = when (path) {
        "/master.m3u8" -> playlist(
            "#EXTM3U\n#EXT-X-VERSION:3\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=150000,RESOLUTION=320x184\nlow/index.m3u8\n",
        )
        "/low/index.m3u8" -> playlist(
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n" +
                "#EXTINF:2.0,\nseg0.ts\n#EXTINF:2.0,\nseg1.ts\n#EXT-X-ENDLIST\n",
        )
        "/low/seg0.ts", "/low/seg1.ts" ->
            MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(ByteArray(SEGMENT_BYTES) { 0x47 }))
        else -> MockResponse().setResponseCode(404)
    }

    private fun playlist(text: String) =
        MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody(text)

    @Test
    fun aSmallHlsStreamIsDownloadedIntoTheCache() {
        serve { hlsStream(it) }
        val sd = newDownloader()

        sd.startStreamDownload(
            taskId, server.url("/master.m3u8").toString(), "application/x-mpegURL", userAgent = "chaya-test", cookies = null,
        )

        assertTrue("the download never completed; failures: ${listener.failed}", awaitUntil { taskId in listener.completed })
        // currentDownloads leaves out finished downloads, so a completed one is only in the index.
        val download = checkNotNull(sd.downloadManager.downloadIndex.getDownload(contentId)) {
            "the completed download is not in Media3's index"
        }
        assertEquals(Download.STATE_COMPLETED, download.state)
        assertTrue("only ${download.bytesDownloaded} bytes arrived", download.bytesDownloaded >= 2L * SEGMENT_BYTES)
        assertEquals(
            setOf("/master.m3u8", "/low/index.m3u8", "/low/seg0.ts", "/low/seg1.ts"),
            requested.toSet(),
        )
    }

    @Test
    fun theHeadersWeSetReachTheServer() {
        val seen = CopyOnWriteArrayList<Pair<String?, String?>>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request.getHeader("User-Agent") to request.getHeader("Referer")
                return hlsStream(request.path.orEmpty())
            }
        }
        server.start()
        val sd = newDownloader()

        sd.startStreamDownload(
            taskId, server.url("/master.m3u8").toString(), "application/x-mpegURL",
            userAgent = "chaya-test/1", cookies = null, referer = "https://example.com/watch",
        )

        assertTrue(awaitUntil { taskId in listener.completed })
        assertTrue(seen.isNotEmpty())
        assertTrue("headers: $seen", seen.all { it == ("chaya-test/1" to "https://example.com/watch") })
    }

    @Test
    fun theCacheCanBeOpenedForPlaybackOfWhatWasSaved() {
        serve { hlsStream(it) }
        val sd = newDownloader()

        // Opening a data source on the cache used to throw a NullPointerException every time.
        val source = sd.playbackDataSourceFactory(userAgent = null, cookies = null, referer = null).createDataSource()

        assertNotNull(source)
    }

    @Test
    fun aServerThatRefusesIsReportedWithItsReason() {
        serve { MockResponse().setResponseCode(403) }
        val sd = newDownloader()
        // Media3 retries a failing transfer several times, slowly, before giving up; the test need not wait for that.
        sd.downloadManager.minRetryCount = 0

        sd.startStreamDownload(
            taskId, server.url("/master.m3u8").toString(), "application/x-mpegURL", userAgent = null, cookies = null,
        )

        assertTrue("never reported a failure", awaitUntil { listener.failed.isNotEmpty() })
        val (failedId, cause) = listener.failed.first()
        assertEquals(taskId, failedId)
        assertNotNull("the cause was dropped", cause)
        assertTrue("cause: $cause", cause.toString().contains("403"))
    }

    private companion object {
        const val SEGMENT_BYTES = 4096
    }
}
