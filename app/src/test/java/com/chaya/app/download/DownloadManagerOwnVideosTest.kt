package com.chaya.app.download

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.room.Room
import com.chaya.app.database.ChayaDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** A video of the person's own: listed done, kept across restarts, added once, and never deleted from the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadManagerOwnVideosTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: ChayaDatabase
    private lateinit var manager: DownloadManager

    private val noDownloads = object : MediaDownloader {
        override fun start(
            taskId: Long, url: String, saveFile: File, userAgent: String?, cookies: String?, referer: String?,
            fromBytes: Long, onMeta: ((String?) -> Unit)?, onProgress: (Long, Long?) -> Unit, onComplete: (Result<File>) -> Unit,
        ) = error("an own video is never downloaded")

        override fun cancel(taskId: Long) = Unit
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, ChayaDatabase::class.java).allowMainThreadQueries().build()
        manager = DownloadManager(context, db.downloadDao(), noDownloads)
    }

    @After
    fun tearDown() {
        runBlocking { manager.drainBackgroundWork() }
        db.close()
    }

    @Test
    fun `an own video is listed done, once however often it is added, and comes back after a restart`() = runBlocking {
        manager.restore()
        val file = folder.newFile("Holiday.mp4").apply { writeText("not really a video") }
        val uri = Uri.fromFile(file).toString()

        val id = manager.addOwnVideo(uri, "Holiday.mp4", "video/mp4", file.length())
        assertEquals(id, manager.addOwnVideo(uri, "Holiday.mp4", "video/mp4", file.length()))

        val task = manager.downloads.value.single()
        assertTrue(task.isOwn)
        assertEquals(DownloadState.COMPLETED, task.state)
        assertEquals("Holiday", task.title)

        manager.drainBackgroundWork()
        val again = DownloadManager(context, db.downloadDao(), noDownloads)
        again.restore()
        assertEquals(listOf(uri), again.downloads.value.map { it.ownUri })
    }

    @Test
    fun `deleting an own video takes it out of Chaya and leaves the file where it is`() = runBlocking {
        manager.restore()
        Robolectric.setupContentProvider(PickedFiles::class.java, PickedFiles.AUTHORITY)
        PickedFiles.deleted.clear()
        val uri = "content://${PickedFiles.AUTHORITY}/Holiday.mp4"
        val id = manager.addOwnVideo(uri, "Holiday.mp4", "video/mp4", 18)

        manager.deleteTask(id)
        manager.drainBackgroundWork()

        assertTrue(manager.downloads.value.isEmpty())
        assertTrue(db.downloadDao().getAllOnce().isEmpty())
        assertEquals("the person's own file was not asked to be deleted", emptyList<Uri>(), PickedFiles.deleted)
    }
}

/** Where the phone's picker found the person's file: it notes any request to delete one. */
class PickedFiles : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri) = "video/mp4"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
        deleted += uri
        return 1
    }

    companion object {
        const val AUTHORITY = "com.chaya.test.picked"
        val deleted = mutableListOf<Uri>()
    }
}
