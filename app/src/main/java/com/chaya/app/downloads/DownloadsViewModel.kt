package com.chaya.app.downloads

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.chaya.app.ChayaApplication
import com.chaya.app.download.DownloadManager
import com.chaya.app.download.DownloadTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {
    private val manager: DownloadManager
        get() = (getApplication<ChayaApplication>()).downloadManager

    val downloads: StateFlow<List<DownloadTask>> = manager.downloads

    /** Swiped-away downloads that stay recoverable until their Undo snackbar finishes. */
    private val _pendingDeletes = MutableStateFlow<Set<Long>>(emptySet())
    val pendingDeletes: StateFlow<Set<Long>> = _pendingDeletes.asStateFlow()

    fun pause(id: Long) = manager.pauseDownload(id)
    fun resume(id: Long) = manager.resumeDownload(id)
    fun cancel(id: Long) = manager.cancelDownload(id)
    fun delete(id: Long) = manager.deleteTask(id)
    fun saveAsFile(id: Long) = manager.saveAsFile(id)

    /** Hides a download right away; it is only deleted once [commitDelete] runs. */
    fun hideForDelete(id: Long) {
        _pendingDeletes.update { it + id }
    }

    /** Brings a hidden download back (the user tapped Undo). */
    fun undoDelete(id: Long) {
        _pendingDeletes.update { it - id }
    }

    /** Deletes a download that was hidden and not restored. */
    fun commitDelete(id: Long) {
        if (id !in _pendingDeletes.value) return
        _pendingDeletes.update { it - id }
        manager.deleteTask(id)
    }

    /** Leaving the screen ends the Undo window, so anything still hidden is deleted for real. */
    override fun onCleared() {
        _pendingDeletes.value.forEach { manager.deleteTask(it) }
        super.onCleared()
    }

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
