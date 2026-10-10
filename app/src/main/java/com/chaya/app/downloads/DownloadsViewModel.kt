package com.chaya.app.downloads

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chaya.app.ChayaApplication
import com.chaya.app.download.DownloadManager
import com.chaya.app.download.DownloadTask
import com.chaya.app.library.LibraryFiles
import com.chaya.app.library.LibraryIntents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {
    private val app: ChayaApplication get() = getApplication()

    private val manager: DownloadManager
        get() = app.downloadManager

    /** Video frames and files taken out of ZIPs, for the grid. */
    val files: LibraryFiles get() = app.libraryFiles

    val downloads: StateFlow<List<DownloadTask>> = manager.downloads

    private val prefs = application.getSharedPreferences("chaya_prefs", Context.MODE_PRIVATE)

    private val _grid = MutableStateFlow(prefs.getBoolean(KEY_GRID, false))
    /** Whether finished downloads show as a grid rather than the list; remembered. */
    val grid: StateFlow<Boolean> = _grid.asStateFlow()

    fun setGrid(on: Boolean) {
        prefs.edit().putBoolean(KEY_GRID, on).apply()
        _grid.value = on
    }

    /** Swiped-away downloads that stay recoverable until their Undo snackbar finishes. */
    private val _pendingDeletes = MutableStateFlow<Set<Long>>(emptySet())
    val pendingDeletes: StateFlow<Set<Long>> = _pendingDeletes.asStateFlow()

    fun pause(id: Long) = manager.pauseDownload(id)
    fun resume(id: Long) = manager.resumeDownload(id)
    fun cancel(id: Long) = manager.cancelDownload(id)
    fun delete(id: Long) = deleteNow(id)
    fun saveAsFile(id: Long) = manager.saveAsFile(id)

    /** Deletes a download with what the library made for it: its frame, and files taken out of its ZIP. */
    private fun deleteNow(id: Long) {
        downloads.value.firstOrNull { it.id == id }?.let(files::forget)
        manager.deleteTask(id)
    }

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
        deleteNow(id)
    }

    /** Leaving the screen ends the Undo window, so anything still hidden is deleted for real. */
    override fun onCleared() {
        _pendingDeletes.value.forEach(::deleteNow)
        super.onCleared()
    }

    /** Opens a completed download; reports an error message on failure. */
    fun open(task: DownloadTask, onError: (String) -> Unit) {
        val intent = manager.openIntentFor(task)
        if (intent == null) {
            onError("File not found")
            return
        }
        runCatching { app.startActivity(intent) }
            .onFailure { onError("No app can open this file") }
    }

    /** The share sheet for finished downloads, one or several. */
    fun share(tasks: List<DownloadTask>, onError: (String) -> Unit) {
        val shareable = tasks.filter(LibraryIntents::canShare)
        val uris = shareable.mapNotNull { runCatching { LibraryIntents.uriFor(app, it) }.getOrNull() }
        val intent = LibraryIntents.shareIntent(uris, shareable.map { LibraryIntents.mimeOf(it.fileName, it.mimeType) })
        if (intent == null) {
            onError(if (tasks.isEmpty()) "Nothing chosen" else "Nothing here can be shared")
            return
        }
        runCatching { app.startActivity(intent) }.onFailure { onError("No app can take this") }
    }

    fun openFile(file: File, mimeType: String, onError: (String) -> Unit) {
        runCatching { app.startActivity(LibraryIntents.viewIntent(LibraryIntents.uriForFile(app, file), mimeType)) }
            .onFailure { onError("No app can open this file") }
    }

    fun shareFile(file: File, mimeType: String, onError: (String) -> Unit) {
        runCatching {
            val intent = LibraryIntents.shareIntent(listOf(LibraryIntents.uriForFile(app, file)), listOf(mimeType))
            if (intent != null) app.startActivity(intent)
        }.onFailure { onError("No app can take this") }
    }

    /** Saves a file taken out of a ZIP to the phone's own folders, and says how that went. */
    fun saveFile(file: File, name: String, mimeType: String, onMessage: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onMessage("Saving to the phone's folders needs Android 10 or newer")
            return
        }
        viewModelScope.launch {
            val saved = LibraryIntents.saveToPhone(app, file, name, mimeType)
            onMessage(if (saved) "Saved $name to your phone" else "Couldn't save $name")
        }
    }

    private companion object {
        const val KEY_GRID = "downloads_grid"
    }
}
