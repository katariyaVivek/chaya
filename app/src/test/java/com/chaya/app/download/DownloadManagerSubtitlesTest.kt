package com.chaya.app.download

import android.content.Context
import androidx.room.Room
import com.chaya.app.database.ChayaDatabase
import com.chaya.app.subtitles.SubtitleSource
import com.chaya.app.subtitles.SubtitleStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** A video saved from a link keeps the site's subtitles, fetched as it starts, and loses them when it is deleted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadManagerSubtitlesTest {

    private lateinit var context: Context
    private lateinit var db: ChayaDatabase
    private lateinit var server: MockWebServer
    private lateinit var store: SubtitleStore
    private lateinit var downloader: StartsOnly
    private lateinit var manager: DownloadManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, ChayaDatabase::class.java).allowMainThreadQueries().build()
        server = MockWebServer().apply { start() }
        store = SubtitleStore(File(context.filesDir, "subtitles-test").apply { deleteRecursively() })
        downloader = StartsOnly()
        manager = DownloadManager(context, db.downloadDao(), downloader, subtitles = store)
    }

    @After
    fun tearDown() {
        runBlocking { manager.drainBackgroundWork() }
        server.shutdown()
        db.close()
    }

    private fun request(subtitles: SubtitleSource?) = DownloadRequest(
        url = "https://cdn.example/talk.mp4", fileName = "Talk (720p).mp4", mimeType = "video/mp4", title = "Talk",
        subtitles = subtitles,
    )

    private suspend fun waitFor(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!check() && System.currentTimeMillis() < deadline) delay(20)
        assertTrue(what, check())
    }

    @Test
    fun `the site's subtitles are kept with the video, and deleted with it`() = runBlocking {
        manager.restore()
        server.enqueue(MockResponse().setBody("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello.\n"))

        manager.startDownload(request(SubtitleSource("en", server.url("/timedtext").toString())))

        waitFor("the download started") { manager.downloads.value.isNotEmpty() }
        val id = manager.downloads.value.single().id
        waitFor("the subtitles were kept") { store.has(id) }
        assertEquals(listOf("Hello."), store.load(id)?.lines?.map { it.text })

        manager.deleteTask(id)

        assertFalse(store.has(id))
    }

    @Test
    fun `subtitles that cannot be fetched leave the video downloading without them`() = runBlocking {
        manager.restore()
        server.enqueue(MockResponse().setResponseCode(500))

        manager.startDownload(request(SubtitleSource("en", server.url("/timedtext").toString())))

        waitFor("the video reached the downloader") { synchronized(downloader.starts) { downloader.starts.isNotEmpty() } }
        waitFor("the site was asked") { server.requestCount == 1 }
        manager.drainBackgroundWork()
        val task = manager.downloads.value.single()
        assertEquals(DownloadState.DOWNLOADING, task.state)
        assertFalse(store.has(task.id))
    }
}

/** A downloader that only notes which downloads reached it, and never finishes one. */
private class StartsOnly : MediaDownloader {
    val starts = mutableListOf<Long>()

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
        synchronized(starts) { starts += taskId }
    }

    override fun cancel(taskId: Long) = Unit
}
