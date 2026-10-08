package com.chaya.app.download

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaya.app.TestMedia
import com.chaya.app.database.ChayaDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The whole save pipeline for a picture and a sound, with the real downloader over real sockets, the real
 * join, Room, and the public Movies folder: no UI, no fakes.
 */
@RunWith(AndroidJUnit4::class)
class TwoFileDownloadOnDeviceTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var dir: File
    private lateinit var server: MockWebServer
    private lateinit var db: ChayaDatabase
    private lateinit var manager: DownloadManager
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "download-test").apply {
            deleteRecursively()
            mkdirs()
        }
        server = MockWebServer()
        db = Room.inMemoryDatabaseBuilder(context, ChayaDatabase::class.java).build()
        manager = DownloadManager(context, db.downloadDao())
    }

    @After
    fun tearDown() {
        runBlocking { manager.drainBackgroundWork() }
        db.close()
        server.shutdown()
        dir.deleteRecursively()
    }

    @Test
    fun aPictureAndASoundAreDownloadedJoinedSavedAndShownInMovies() {
        runBlocking {
            val picture = File(dir, "picture.mp4").also { TestMedia.pictureOnly(it) }
            val sound = File(dir, "sound.m4a").also { TestMedia.soundOnly(it) }
            serve("/video" to picture, "/sound" to sound)
            manager.restore()

            manager.startDownload(
                DownloadRequest(
                    url = server.url("/video").toString(),
                    headers = mapOf("User-Agent" to "chaya-device-test/1", "Referer" to "https://example.com/watch?v=1"),
                    audioUrl = server.url("/sound").toString(),
                    audioHeaders = mapOf("User-Agent" to "chaya-device-test/1"),
                    pageUrl = "https://example.com/watch?v=1",
                    fileName = "Device test (180p).mp4",
                    mimeType = "video/mp4",
                    title = "Device test",
                    qualityHeight = 180,
                    expectedBytes = picture.length() + sound.length(),
                ),
            )
            val done = awaitFinished()

            assertEquals("ended as ${done.state}: ${done.error}", DownloadState.COMPLETED, done.state)

            // One real MP4 with both tracks, and nothing left over.
            val saved = File(requireNotNull(done.filePath))
            val result = TestMedia.inspect(saved)
            assertTrue("tracks: ${result.mimeTypes}", result.mimeTypes.contains("video/avc"))
            assertTrue(result.mimeTypes.any { it.startsWith("audio/") })
            assertEquals(TestMedia.inspect(picture).times("video/").size, result.times("video/").size)
            assertFalse(File("${saved.path}.video").exists())
            assertFalse(File("${saved.path}.audio").exists())
            assertFalse(File("${saved.path}.joining").exists())
            assertEquals(saved.length(), done.totalBytes)

            // The engine's headers reached the server, for both files.
            assertEquals(listOf("/video", "/sound"), requests.map { it.path })
            assertTrue(requests.all { it.getHeader("User-Agent") == "chaya-device-test/1" })
            assertEquals("https://example.com/watch?v=1", requests.first().getHeader("Referer"))

            // A copy sits in the public Movies folder, where the gallery and other apps can see it.
            val exported = Uri.parse(requireNotNull(done.exportedUri) { "not exported to MediaStore" })
            context.contentResolver.query(
                exported,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
                null, null, null,
            ).use { cursor ->
                assertTrue("the exported copy is missing", cursor != null && cursor.moveToFirst())
                // MediaStore adds a number when a file of that name is already in Movies.
                val name = cursor!!.getString(0)
                assertTrue("exported as $name", name.startsWith("Device test (180p)") && name.endsWith(".mp4"))
                assertEquals(saved.length(), cursor.getLong(1))
            }

            // Deleting the download removes the public copy too.
            manager.deleteTask(done.id)
            assertTrue(awaitUntil { exportedRowCount(exported) == 0 })
            Log.i(TAG, "saved ${saved.length()} bytes from ${picture.length()} + ${sound.length()}")
        }
    }

    @Test
    fun aSingleFileWithTheEnginesHeadersIsSavedWhole() {
        runBlocking {
            val picture = File(dir, "clip.mp4").also { TestMedia.pictureOnly(it) }
            serve("/clip" to picture)
            manager.restore()

            manager.startDownload(
                DownloadRequest(
                    url = server.url("/clip").toString(),
                    headers = mapOf("User-Agent" to "chaya-device-test/2"),
                    fileName = "Single clip (180p).mp4",
                    mimeType = "video/mp4",
                    title = "Single clip",
                    qualityHeight = 180,
                    expectedBytes = picture.length(),
                ),
            )
            val done = awaitFinished()

            assertEquals("ended as ${done.state}: ${done.error}", DownloadState.COMPLETED, done.state)
            assertEquals(picture.readBytes().toList(), File(requireNotNull(done.filePath)).readBytes().toList())
            assertEquals("chaya-device-test/2", requests.single().getHeader("User-Agent"))
            manager.deleteTask(done.id)
        }
    }

    @Test
    fun aDownloadTheServerRefusesFailsWithAnExplanationAndKeepsNothing() {
        runBlocking {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(403)
            }
            server.start()
            manager.restore()

            manager.startDownload(
                DownloadRequest(
                    url = server.url("/video").toString(),
                    headers = mapOf("User-Agent" to "chaya-device-test/3"),
                    audioUrl = server.url("/sound").toString(),
                    fileName = "Refused (180p).mp4",
                    mimeType = "video/mp4",
                    title = "Refused",
                ),
            )
            val done = awaitFinished()

            assertEquals(DownloadState.FAILED, done.state)
            assertTrue("error: ${done.error}", done.error is DownloadError.HttpStatus)
            assertFalse(File(requireNotNull(done.filePath)).exists())
            manager.deleteTask(done.id)
        }
    }

    // ---- helpers ---- //

    /** Serves each file at its path, with the right content type. */
    private fun serve(vararg files: Pair<String, File>) {
        val byPath = files.toMap()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val file = byPath[request.path] ?: return MockResponse().setResponseCode(404)
                val type = if (file.extension == "m4a") "audio/mp4" else "video/mp4"
                return MockResponse().setHeader("Content-Type", type).setBody(Buffer().write(file.readBytes()))
            }
        }
        server.start()
    }

    private suspend fun awaitFinished(): DownloadTask {
        val finished = setOf(DownloadState.COMPLETED, DownloadState.FAILED)
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            manager.downloads.value.firstOrNull { it.state in finished }?.let { return it }
            delay(100)
        }
        error("The download never finished. Tasks: ${manager.downloads.value}")
    }

    private suspend fun awaitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(100)
        }
        return condition()
    }

    private fun exportedRowCount(uri: Uri): Int =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.count } ?: 0

    private companion object {
        const val TAG = "ChayaDevice"
    }
}
