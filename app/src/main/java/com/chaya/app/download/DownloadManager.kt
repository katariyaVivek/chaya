package com.chaya.app.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebSettings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.StreamKey
import androidx.media3.datasource.DataSource
import com.chaya.app.database.DownloadDao
import com.chaya.app.database.DownloadEntity
import com.chaya.app.model.DetectedMedia
import com.chaya.app.streaming.StreamDownloader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates all downloads: creates tasks, delegates HTTP transfers to the
 * injected [MediaDownloader] (production: [HttpDownloader]) and streams to
 * [StreamDownloader], persists state through [DownloadDao], and exposes
 * state for UI observation.
 *
 * Task IDs are generated here (seeded from the DB) and used as explicit
 * primary keys, so in-memory tasks, DB rows and downloader callbacks always
 * agree on identity.
 */
class DownloadManager(
    private val context: Context,
    private val dao: DownloadDao,
    private val downloader: MediaDownloader = HttpDownloader()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveDir = File(context.filesDir, "downloads").also { it.mkdirs() }

    /** Completed once [restore] has seeded state — startDownload waits on it. */
    private val ready = CompletableDeferred<Unit>()
    private val idCounter = AtomicLong(0)

    private val _downloads = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloads: StateFlow<List<DownloadTask>> = _downloads.asStateFlow()

    private var streamDownloader: StreamDownloader? = null

    init {
        DownloadNotification.createChannel(context)
    }

    // ------------------------------------------------------------------ //
    // Restore / lifecycle
    // ------------------------------------------------------------------ //

    /**
     * Loads persisted downloads. Rows stuck in DOWNLOADING/QUEUED (app was
     * killed mid-download) are moved to PAUSED — partial files are kept, so
     * the user can resume them.
     */
    suspend fun restore() {
        if (ready.isCompleted) return
        val entities = dao.getAllOnce()
        val tasks = entities.map { e ->
            if (e.state == DownloadState.DOWNLOADING || e.state == DownloadState.QUEUED) {
                dao.updateState(e.id, DownloadState.PAUSED)
                e.copy(state = DownloadState.PAUSED).toTask()
            } else {
                e.toTask()
            }
        }
        _downloads.value = tasks
        idCounter.set(dao.getMaxId())
        ready.complete(Unit)
    }

    // ------------------------------------------------------------------ //
    // Public API
    // ------------------------------------------------------------------ //

    fun startDownload(media: DetectedMedia) {
        ensureServiceRunning()
        scope.launch {
            val id = nextId()
            val (ua, ck) = sessionHeaders(media.url)
            if (isStream(media.url, media.mimeType)) {
                startStream(id, media, ua, ck, null)
            } else {
                startHttp(id, media, ua, ck, fromBytes = 0)
            }
        }
    }

    fun startDownload(media: DetectedMedia, streamKeys: List<StreamKey>) {
        ensureServiceRunning()
        scope.launch {
            val id = nextId()
            val (ua, ck) = sessionHeaders(media.url)
            startStream(id, media, ua, ck, streamKeys)
        }
    }

    fun pauseDownload(id: Long) {
        val t = find(id) ?: return
        if (t.state != DownloadState.DOWNLOADING) return

        if (isStream(t.url, t.mimeType)) {
            // Media3 will report back via onStreamPaused.
            streamDownloader?.pauseStream(id)
        } else {
            downloader.cancel(id)
            apply(t.copy(state = DownloadState.PAUSED, downloadedBytes = partialBytes(t)))
            scope.launch { dao.update(DownloadEntity.fromTask(find(id) ?: t)) }
        }
    }

    /** Resume a paused download, or retry a failed/cancelled one (keeps partial data). */
    fun resumeDownload(id: Long) {
        val t0 = find(id) ?: return
        if (t0.state !in resumableStates) return
        ensureServiceRunning()

        scope.launch {
            val t = find(id) ?: return@launch
            val (ua, ck) = sessionHeaders(t.url)

            if (isStream(t.url, t.mimeType)) {
                apply(t.copy(state = DownloadState.DOWNLOADING, error = null))
                obtainStreamDownloader().resumeStream(id, t.url, t.mimeType, ua, ck, t.pageUrl)
            } else {
                val file = ensureFileFor(t)
                val from = partialBytes(t)
                apply(
                    t.copy(
                        state = DownloadState.DOWNLOADING,
                        filePath = file.absolutePath,
                        downloadedBytes = from,
                        error = null
                    )
                )
                downloader.start(
                    taskId = id,
                    url = t.url,
                    saveFile = file,
                    userAgent = ua,
                    cookies = ck,
                    referer = t.pageUrl,
                    fromBytes = from,
                    onMeta = { suggested -> onServerFileName(id, suggested) },
                    onProgress = { d, total -> progress(id, d, total) },
                    onComplete = { result ->
                        result.fold(
                            onSuccess = { completeTask(id) },
                            onFailure = { failTask(id, it) }
                        )
                    }
                )
            }
        }
    }

    fun cancelDownload(id: Long) {
        val t = find(id) ?: return
        if (t.state != DownloadState.DOWNLOADING && t.state != DownloadState.QUEUED) return
        downloader.cancel(id)
        if (isStream(t.url, t.mimeType)) streamDownloader?.stopStream(id)

        apply(t.copy(state = DownloadState.CANCELLED))
        scope.launch { dao.updateState(id, DownloadState.CANCELLED) }
    }

    fun cancelAll() {
        _downloads.value
            .filter { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED }
            .forEach { cancelDownload(it.id) }
    }

    /** Remove a task and delete its files (private copy + public MediaStore copy). */
    fun deleteTask(id: Long) {
        val t = find(id)
        downloader.cancel(id)
        streamDownloader?.deleteStream(id)
        if (t != null) {
            t.filePath?.let { p -> runCatching { File(p).delete() } }
            t.exportedUri?.let { u ->
                runCatching { context.contentResolver.delete(Uri.parse(u), null, null) }
            }
        }
        _downloads.value = _downloads.value.filterNot { it.id == id }
        scope.launch { dao.delete(id) }
    }

    /** Build an ACTION_VIEW intent for a completed download, or null if impossible. */
    fun openIntentFor(task: DownloadTask): Intent? {
        if (task.state != DownloadState.COMPLETED) return null
        val mime = task.mimeType
            ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(task.fileName.substringAfterLast('.', ""))
            ?: "*/*"
        val uri: Uri = task.exportedUri?.let(Uri::parse)
            ?: task.filePath?.let {
                if (!File(it).exists()) return@let null
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(it))
            }
            ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Playback info for a completed stream download (cache-backed). */
    data class StreamPlayback(
        val factory: DataSource.Factory,
        val uri: Uri,
        val mimeType: String?
    )

    /** Returns cache-aware playback for a stream task, or null for plain files. */
    fun streamPlaybackFor(taskId: Long): StreamPlayback? {
        val t = find(taskId) ?: return null
        if (!isStream(t.url, t.mimeType)) return null
        val sd = obtainStreamDownloader()
        val (ua, ck) = sessionHeaders(t.url)
        return StreamPlayback(
            factory = sd.playbackDataSourceFactory(ua, ck, t.pageUrl),
            uri = Uri.parse(t.url),
            mimeType = t.mimeType
        )
    }

    // ------------------------------------------------------------------ //
    // Starters
    // ------------------------------------------------------------------ //

    private suspend fun startHttp(
        id: Long,
        media: DetectedMedia,
        ua: String?,
        ck: String?,
        fromBytes: Long
    ) {
        val name = sanitize(fileNameForMedia(media))
        val saveFile = resolveFileName(saveDir, name)

        val task = DownloadTask(
            id = id,
            url = media.url,
            pageUrl = media.pageUrl,
            fileName = saveFile.name,
            mimeType = media.mimeType,
            filePath = saveFile.absolutePath,
            downloadedBytes = fromBytes,
            state = DownloadState.DOWNLOADING
        )
        append(task)
        dao.insert(DownloadEntity.fromTask(task))

        downloader.start(
            taskId = id,
            url = task.url,
            saveFile = saveFile,
            userAgent = ua,
            cookies = ck,
            referer = media.pageUrl,
            fromBytes = fromBytes,
            onMeta = { suggested -> onServerFileName(id, suggested) },
            onProgress = { d, total -> progress(id, d, total) },
            onComplete = { result ->
                result.fold(
                    onSuccess = { completeTask(id) },
                    onFailure = { failTask(id, it) }
                )
            }
        )
    }

    private suspend fun startStream(
        id: Long,
        media: DetectedMedia,
        ua: String?,
        ck: String?,
        streamKeys: List<StreamKey>?
    ) {
        val task = DownloadTask(
            id = id,
            url = media.url,
            pageUrl = media.pageUrl,
            fileName = sanitize(fileNameForMedia(media)),
            mimeType = media.mimeType,
            filePath = null,
            state = DownloadState.DOWNLOADING
        )
        append(task)
        dao.insert(DownloadEntity.fromTask(task))

        obtainStreamDownloader().startStreamDownload(
            taskId = id,
            url = task.url,
            mimeType = task.mimeType,
            userAgent = ua,
            cookies = ck,
            referer = task.pageUrl,
            streamKeys = streamKeys
        )
    }

    // ------------------------------------------------------------------ //
    // State transitions
    // ------------------------------------------------------------------ //

    private fun completeTask(id: Long) {
        scope.launch {
            val t = find(id) ?: return@launch
            val exported = runCatching { exportToPublicStorage(t) }.getOrNull()
            val finished = t.copy(
                state = DownloadState.COMPLETED,
                totalBytes = t.totalBytes?.takeIf { it > 0 } ?: t.downloadedBytes,
                exportedUri = exported,
                error = null,
                updatedAt = System.currentTimeMillis()
            )
            apply(finished)
            dao.update(DownloadEntity.fromTask(finished))
        }
    }

    private fun failTask(id: Long, error: Throwable) {
        scope.launch {
            val t = find(id) ?: return@launch
            val failed = t.copy(
                state = DownloadState.FAILED,
                error = DownloadError.from(error),
                updatedAt = System.currentTimeMillis()
            )
            apply(failed)
            dao.update(DownloadEntity.fromTask(failed))
        }
    }

    private fun progress(id: Long, downloadedBytes: Long, totalBytes: Long?) {
        _downloads.value = _downloads.value.map {
            if (it.id == id) {
                it.copy(downloadedBytes = downloadedBytes, totalBytes = totalBytes)
            } else it
        }
    }

    private fun apply(task: DownloadTask) {
        _downloads.value = _downloads.value.map {
            if (it.id == task.id) task else it
        }
    }

    private fun append(task: DownloadTask) {
        _downloads.value = _downloads.value + task
    }

    private fun find(id: Long): DownloadTask? =
        _downloads.value.firstOrNull { it.id == id }

    private suspend fun nextId(): Long {
        ready.await()
        return idCounter.incrementAndGet()
    }

    // ------------------------------------------------------------------ //
    // Helpers
    // ------------------------------------------------------------------ //

    /**
     * Promotes [DownloadService] to the foreground before any transfer begins.
     *
     * Without this call the service class exists but is never instantiated,
     * so Android is free to kill the download the instant the app backgrounds
     * and the "Pause"/"Cancel" notification actions never appear — the
     * foreground-service guarantee documented in BUILD_AND_TEST.md was pure
     * fiction until a caller actually started the service.
     */
    private fun ensureServiceRunning() {
        val intent = Intent(context, DownloadService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    /** Fresh UA + WebView session cookies for the given URL. */
    private fun sessionHeaders(url: String): Pair<String?, String?> {
        val ua = WebSettings.getDefaultUserAgent(context)
        val ck = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        return ua to ck
    }

    /** Bytes already on disk for a partial HTTP download. */
    private fun partialBytes(t: DownloadTask): Long =
        t.filePath?.let { f -> File(f).length().takeIf { it > 0 } } ?: 0L

    private fun ensureFileFor(t: DownloadTask): File =
        t.filePath?.let { File(it) } ?: resolveFileName(saveDir, sanitize(t.fileName))

    /** Server suggested a better filename via Content-Disposition. */
    private fun onServerFileName(id: Long, suggested: String?) {
        if (suggested.isNullOrBlank()) return
        val current = find(id) ?: return
        val clean = sanitize(suggested)
        if (clean.equals(current.fileName, ignoreCase = true)) return

        // Keep an extension if the suggestion lacks one.
        val finalName = if ('.' in clean) clean else {
            val ext = current.fileName.substringAfterLast('.', "")
            if (ext.isEmpty()) clean else "$clean.$ext"
        }
        val old = current.filePath?.let(::File)?.takeIf { it.exists() }
        val newFile = resolveFileName(saveDir, finalName)
        if (old != null && !old.renameTo(newFile)) return

        val updated = current.copy(fileName = newFile.name, filePath = newFile.absolutePath)
        apply(updated)
        scope.launch { dao.updateNameAndPath(id, newFile.name, newFile.absolutePath) }
    }

    private fun obtainStreamDownloader(): StreamDownloader =
        streamDownloader ?: StreamDownloader(context, object : StreamDownloader.Listener {
            override fun onStreamProgress(taskId: Long, downloadedBytes: Long, totalBytes: Long?) {
                progress(taskId, downloadedBytes, totalBytes)
            }

            override fun onStreamCompleted(taskId: Long) = completeTask(taskId)

            override fun onStreamFailed(taskId: Long, reason: Int) {
                failTask(taskId, IOException("Stream download failed (reason $reason)"))
            }

            override fun onStreamPaused(taskId: Long) {
                val t = find(taskId) ?: return
                apply(t.copy(state = DownloadState.PAUSED))
                scope.launch { dao.updateState(taskId, DownloadState.PAUSED) }
            }
        }).also { streamDownloader = it }

    /**
     * Copy a completed file into public storage (MediaStore) so users can see
     * it in their Files/Gallery apps. Returns the content URI, or null.
     */
    private suspend fun exportToPublicStorage(task: DownloadTask): String? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
            val src = task.filePath?.let(::File)?.takeIf { it.exists() }
                ?: return@withContext null
            val mime = task.mimeType ?: "application/octet-stream"

            val (collection, relPath) = when {
                mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
                mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MUSIC
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
            }

            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, task.fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return@withContext null
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } ?: throw IOException("Could not open output stream")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri.toString()
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                null
            }
        }

    companion object {
        private val ILLEGAL_CHARS = Regex("[\\\\/:*?\"<>|]")
        private val resumableStates = setOf(
            DownloadState.PAUSED, DownloadState.FAILED, DownloadState.CANCELLED
        )

        fun isStreamingUrl(url: String): Boolean = StreamDownloader.isStreamingUrl(url)

        fun isStream(url: String, mimeType: String?): Boolean =
            StreamDownloader.isStreamingUrl(url) || StreamDownloader.isStreamingMime(mimeType)

        fun sanitize(name: String): String =
            ILLEGAL_CHARS.replace(name.trim(), "_").take(100)
                .ifEmpty { "media_${System.currentTimeMillis()}" }

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
