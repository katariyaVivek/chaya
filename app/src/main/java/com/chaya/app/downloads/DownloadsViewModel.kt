package com.chaya.app.downloads

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.chaya.app.ChayaApplication
import com.chaya.app.download.DownloadManager
import com.chaya.app.download.DownloadTask
import kotlinx.coroutines.flow.StateFlow

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {
    private val manager: DownloadManager
        get() = (getApplication<ChayaApplication>()).downloadManager

    val downloads: StateFlow<List<DownloadTask>> = manager.downloads

    fun pause(id: Long) = manager.pauseDownload(id)
    fun resume(id: Long) = manager.resumeDownload(id)
    fun cancel(id: Long) = manager.cancelDownload(id)
    fun delete(id: Long) = manager.deleteTask(id)

    /** Opens a completed download; reports an error message on failure. */
    fun open(task: DownloadTask, onError: (String) -> Unit) {
        val intent = manager.openIntentFor(task)
        if (intent == null) {
            onError("File not found")
            return
        }
        runCatching { getApplication<Application>().startActivity(intent) }
            .onFailure { onError("No app can open this file") }
    }
}
