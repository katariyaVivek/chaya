package com.chaya.app.download

import android.app.Notification
import androidx.core.app.NotificationCompat
import com.chaya.app.MainActivity
import com.chaya.app.OpenedDownloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Download complete": named by the video's title, and opening it when tapped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadNotificationTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun task(title: String?) = DownloadTask(
        id = 42,
        url = "https://cdn.example/6cf38ed091e3ffa8f52a0d36084ad92.mp4",
        pageUrl = null,
        fileName = "6cf38ed091e3ffa8f52a0d36084ad92.mp4",
        mimeType = "video/mp4",
        state = DownloadState.COMPLETED,
        title = title,
    )

    @Test
    fun `it is named by the download's title, else its file`() {
        assertEquals("Episode 4", DownloadNotification.titleOf(task("Episode 4")))
        assertEquals("6cf38ed091e3ffa8f52a0d36084ad92.mp4", DownloadNotification.titleOf(task(" ")))
        val built = DownloadNotification.buildCompleteNotification(context, task("Episode 4"))
        assertEquals("Episode 4", built.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
    }

    @Test
    fun `a tap opens the app on that download`() {
        // It had no intent: tapping it did nothing.
        val built: Notification = DownloadNotification.buildCompleteNotification(context, task("Episode 4"))
        val tap = built.contentIntent
        assertNotNull(tap)
        val opened = shadowOf(tap).savedIntent
        assertEquals(MainActivity::class.java.name, opened.component?.className)
        assertEquals(42L, opened.getLongExtra(OpenedDownloads.EXTRA_DOWNLOAD_ID, -1))
    }
}
