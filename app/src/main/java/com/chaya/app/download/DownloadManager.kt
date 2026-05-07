package com.chaya.app.download

import android.content.Context
import android.content.Intent
import com.chaya.app.database.DownloadDao
import com.chaya.app.database.DownloadEntity
import com.chaya.app.model.DetectedMedia
import com.chaya.app.streaming.StreamDownloader
import androidx.media3.exoplayer.offline.StreamKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates all downloads: creates tasks, delegates to [HttpDownloader]
 * or [StreamDownloader], persists state through [DownloadDao], and exposes
 * state for UI observation.
 */
class DownloadManager(
    private val context: Context,
    private val dao: DownloadDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloader = HttpDownloader()
    private val saveDir = File(context.filesDir, "downloads").also { it.mkdirs() }

    private var streamDownloader: StreamDownloader? = null

    init {
        DownloadNotification.createChannel(context)
    }

    private val idCounter = AtomicLong(0)

    private val _downloads = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloads: StateFlow<List<DownloadTask>> = _downloads.asStateFlow()

    var userAgent: String? = null
    var cookies: String? = null

    suspend fun restore() {
        val entities = dao.getAllOnce()
        _downloads.value = entities.map { it.toTask() }
    }

    fun startDownload(media: DetectedMedia) {
        val isStream = StreamDownloader.isStreamingUrl(media.url) ||
                StreamDownloader.isStreamingMime(media.mimeType)

        if (isStream) {
            startStreamDownload(media, streamKeys = null)
        } else {
            startHttpDownload(media)
        }
    }

    fun startDownload(media: DetectedMedia, streamKeys: List<StreamKey>) {
        startStreamDownload(media, streamKeys)
    }

    private fun startHttpDownload(media: DetectedMedia) {
        val fileName = fileNameForMedia(media)
        val saveFile = resolveFileName(saveDir, fileName)

        val task = DownloadTask(
            id = idCounter.incrementAndGet(),
            url = media.url,
            pageUrl = media.pageUrl,
            fileName = saveFile.name,
            mimeType = media.mimeType,
            filePath = saveFile.absolutePath,
            state = DownloadState.QUEUED
        )

        appendTask(task)
        startForegroundService()
        persistQueued(task)
        transitionTask(task.id, DownloadState.DOWNLOADING)

        downloader.start(
            taskId = task.id,
            url = task.url,
            saveFile = saveFile,
            userAgent = userAgent,
            cookies = cookies,
            onProgress = { downloaded, total ->
                updateProgress(task.id, downloaded, total)
            },
            onComplete = { result ->
                result.fold(
                    onSuccess = {
                        completeTask(task.id)
                    },
                    onFailure = { error ->
                        failTask(task.id, error)
                    }
                )
            }
        )
    }

    private fun startStreamDownload(media: DetectedMedia, streamKeys: List<StreamKey>?) {
        val task = DownloadTask(
            id = idCounter.incrementAndGet(),
            url = media.url,
            pageUrl = media.pageUrl,
            fileName = fileNameForMedia(media),
            mimeType = media.mimeType,
            filePath = null,
            state = DownloadState.QUEUED
        )

        appendTask(task)
        startForegroundService()
        persistQueued(task)
        transitionTask(task.id, DownloadState.DOWNLOADING)

        val sd = streamDownloader ?: run {
            val newSd = StreamDownloader(context, object : StreamDownloader.Listener {
                override fun onStreamProgress(tid: Long, downloadedBytes: Long, totalBytes: Long?) {
                    updateProgress(tid, downloadedBytes, totalBytes)
                }

                override fun onStreamCompleted(tid: Long) {
                    completeTask(tid)
                }

                override fun onStreamFailed(tid: Long, reason: Int) {
                    scope.launch {
                        transitionTask(tid, DownloadState.FAILED)
                        dao.updateState(tid, DownloadState.FAILED)
                    }
                }
            })
            streamDownloader = newSd
            newSd
        }

        sd.startStreamDownload(
            taskId = task.id,
            url = task.url,
            mimeType = task.mimeType,
            userAgent = userAgent,
            cookies = cookies,
            streamKeys = streamKeys
        )
    }

    fun cancelDownload(taskId: Long) {
        streamDownloader?.cancelStreamDownload(taskId)
        downloader.cancel(taskId)
        transitionTask(taskId, DownloadState.CANCELLED)
        scope.launch { dao.updateState(taskId, DownloadState.CANCELLED) }
    }

    fun cancelAll() {
        streamDownloader?.cancelAll()
        downloader.cancelAll()
        _downloads.value = _downloads.value.map { it.copy(state = DownloadState.CANCELLED) }
        scope.launch {
            for (download in _downloads.value) {
                dao.updateState(download.id, DownloadState.CANCELLED)
            }
        }
    }

    fun deleteTask(taskId: Long) {
        streamDownloader?.cancelStreamDownload(taskId)
        downloader.cancel(taskId)
        _downloads.value = _downloads.value.filter { it.id != taskId }
        scope.launch { dao.delete(taskId) }
    }

    private fun startForegroundService() {
        context.startForegroundService(
            Intent(context, DownloadService::class.java)
        )
    }

    private fun persistQueued(task: DownloadTask) {
        scope.launch {
            val entity = DownloadEntity.fromTask(task)
            val dbId = dao.insert(entity)
            if (dbId != task.id) replaceTaskId(task.id, dbId)
            dao.updateState(dbId, DownloadState.DOWNLOADING)
        }
    }

    private fun completeTask(id: Long) {
        transitionTask(id, DownloadState.COMPLETED)
        scope.launch { dao.updateState(id, DownloadState.COMPLETED) }
    }

    private fun failTask(id: Long, error: Throwable) {
        if (error.message == "Download cancelled") {
            transitionTask(id, DownloadState.CANCELLED)
            scope.launch { dao.updateState(id, DownloadState.CANCELLED) }
        } else {
            transitionTask(id, DownloadState.FAILED)
            scope.launch { dao.updateState(id, DownloadState.FAILED) }
        }
    }

    private fun appendTask(task: DownloadTask) {
        _downloads.value = _downloads.value + task
    }

    private fun replaceTaskId(oldId: Long, newId: Long) {
        _downloads.value = _downloads.value.map {
            if (it.id == oldId) it.copy(id = newId) else it
        }
    }

    private fun transitionTask(id: Long, newState: DownloadState) {
        _downloads.value = _downloads.value.map {
            if (it.id == id) it.copy(state = newState, updatedAt = System.currentTimeMillis())
            else it
        }
    }

    private fun updateProgress(id: Long, downloadedBytes: Long, totalBytes: Long?) {
        _downloads.value = _downloads.value.map {
            if (it.id == id) it.copy(
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                updatedAt = System.currentTimeMillis()
            )
            else it
        }
    }

    companion object {
        fun fileNameForMedia(media: DetectedMedia): String {
            val url = media.url
            val path = url.substringBefore("?").substringBefore("#")
            val segments = path.split("/")
            val last = segments.lastOrNull { it.isNotBlank() }

            if (!last.isNullOrBlank() && last.contains('.')) {
                return last
            }

            val ext = when {
                media.mimeType?.startsWith("video/") == true -> ".mp4"
                media.mimeType?.startsWith("audio/") == true -> ".mp3"
                StreamDownloader.isStreamingMime(media.mimeType) -> ".ts"
                else -> ""
            }
            val encoded = URLEncoder.encode(url, "UTF-8").take(40)
            return "${encoded}$ext"
        }

        private fun resolveFileName(dir: File, fileName: String): File {
            val file = File(dir, fileName)
            if (!file.exists()) return file

            val dot = fileName.lastIndexOf('.')
            val base = if (dot >= 0) fileName.substring(0, dot) else fileName
            val ext = if (dot >= 0) fileName.substring(dot) else ""

            var counter = 2
            while (File(dir, "$base ($counter)$ext").exists()) counter++
            return File(dir, "$base ($counter)$ext")
        }
    }
}
