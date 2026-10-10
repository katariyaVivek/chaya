package com.chaya.app.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebSettings
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.StreamKey
import androidx.media3.datasource.DataSource
import com.chaya.app.database.DownloadDao
import com.chaya.app.database.DownloadEntity
import com.chaya.app.diagnostics.ChayaEvent
import com.chaya.app.diagnostics.EventLog
import com.chaya.app.model.DetectedMedia
import com.chaya.app.streaming.Media3StreamExporter
import com.chaya.app.streaming.StreamDownloader
import com.chaya.app.streaming.StreamExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    private val downloader: MediaDownloader = HttpDownloader(),
    /** Null in unit tests that construct the manager directly. */
    private val eventLog: EventLog? = null,
    // Injected for tests: reports usable bytes at a path. Production reads
    // the filesystem; tests return a fixed number. No StatFs mocking needed.
    // Robolectric shadows StatFs with zeroes, so the default must mean
    // "unknown, allow" rather than "no space" — otherwise every
    // pre-existing test would fail fast under unit tests.
    private val freeBytes: (File) -> Long = { path ->
        runCatching { StatFs(path.absolutePath).availableBytes }
            .getOrDefault(Long.MAX_VALUE)
            .takeIf { it > 0 } ?: Long.MAX_VALUE
    },
    /** Joins a picture file and a sound file into one MP4; replaced in tests, where no real media exists. */
    private val merger: MediaMerger = Mp4Merger(),
    /** Lists what goes into a ZIP archive (an account's posts); null where archives are not offered. */
    private val archiveLister: ArchiveLister? = null,
    /** How long an archive waits before asking again for a file that failed on the way, one wait per attempt. */
    private val archiveRetryDelaysMillis: List<Long> = ARCHIVE_RETRY_DELAYS_MILLIS,
    /** Saves finished streams as MP4 files; null builds the Media3 one when first needed. Replaced in tests. */
    private val streamExporter: StreamExporter? = null,
) {
    /** Parent of all fire-and-forget persistence and transfer work; [drainBackgroundWork] joins it. */
    private val backgroundJob = SupervisorJob()
    private val scope = CoroutineScope(backgroundJob + Dispatchers.IO)
    private val saveDir = File(context.filesDir, "downloads").also { it.mkdirs() }

    /** Completed once [restore] has seeded state — startDownload waits on it. */
    private val ready = CompletableDeferred<Unit>()
    private val idCounter = AtomicLong(0)

    /**
     * Lets tests finish in-flight Room writes before closing their in-memory
     * database. Otherwise a write still mid-transaction fails after its test
     * ends and surfaces as an uncaught exception in whichever test runs next.
     * Work that would never finish on its own (a fake transfer left suspended)
     * is cancelled after a short settle period.
     */
    @VisibleForTesting
    internal suspend fun drainBackgroundWork(settleMillis: Long = 500) {
        withTimeoutOrNull(settleMillis) {
            while (true) {
                val active = backgroundJob.children.filter { it.isActive }.toList()
                if (active.isEmpty()) break
                active.joinAll()
            }
        }
        backgroundJob.cancelChildren()
        backgroundJob.children.toList().joinAll()
    }

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
            } else if (!checkStorageForNewDownload(id, media)) {
                return@launch
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

    /**
     * Starts a download the engine worked out: one file, or a picture and a sound that are joined into one
     * MP4 once both have arrived. A joined download briefly needs room for the parts and the result, so the
     * space check allows for both.
     */
    fun startDownload(request: DownloadRequest) {
        ensureServiceRunning()
        scope.launch {
            val id = nextId()
            val saveFile = resolveFileName(saveDir, sanitizeKeepingExtension(request.fileName))
            val task = DownloadTask(
                id = id,
                url = request.url,
                pageUrl = request.pageUrl,
                fileName = saveFile.name,
                mimeType = request.mimeType,
                filePath = saveFile.absolutePath,
                totalBytes = request.expectedBytes,
                state = DownloadState.DOWNLOADING,
                title = request.title,
                thumbnailUrl = request.thumbnailUrl,
                qualityHeight = request.qualityHeight,
                requestHeaders = request.headers,
                audioUrl = request.audioUrl,
                audioRequestHeaders = request.audioHeaders,
            )

            val copies = if (request.audioUrl != null) 2 else 1
            val needed = (request.expectedBytes ?: 0L) * copies + MIN_FREE_BYTES_TO_START
            if (freeBytes(saveDir) < needed) {
                val failed = task.copy(
                    state = DownloadState.FAILED,
                    error = DownloadError.StorageFull,
                    filePath = null,
                )
                append(failed)
                dao.insert(DownloadEntity.fromTask(failed))
                return@launch
            }

            append(task)
            dao.insert(DownloadEntity.fromTask(task))
            transfer(task)
        }
    }

    /**
     * Starts a ZIP archive of many files, such as everything an account has posted: its contents are listed,
     * then fetched one by one, then packed. Like a two-file download, it works out what is left from the files
     * it has, so pause, resume and a killed app all carry on where it stopped.
     */
    fun startArchive(request: ArchiveRequest) {
        ensureServiceRunning()
        scope.launch {
            val id = nextId()
            val saveFile = resolveFileName(saveDir, sanitizeKeepingExtension(request.fileName))
            val task = DownloadTask(
                id = id,
                url = request.source,
                pageUrl = request.pageUrl,
                fileName = saveFile.name,
                mimeType = ARCHIVE_MIME,
                filePath = saveFile.absolutePath,
                state = DownloadState.DOWNLOADING,
                title = request.title,
                thumbnailUrl = request.thumbnailUrl,
                archive = ArchiveProgress(found = 0, saved = 0, listing = true),
            )
            if (freeBytes(saveDir) < MIN_FREE_BYTES_TO_START) {
                val failed = task.copy(state = DownloadState.FAILED, error = DownloadError.StorageFull, archive = null)
                append(failed)
                dao.insert(DownloadEntity.fromTask(failed))
                return@launch
            }
            append(task)
            dao.insert(DownloadEntity.fromTask(task))
            runArchive(id)
        }
    }

    fun pauseDownload(id: Long) {
        val t = find(id) ?: return
        if (t.state != DownloadState.DOWNLOADING) return
        if (stopSavingAsFile(id)) return

        if (t.isArchive) {
            // Paused before the listing is told to stop: a listing that ends sees the pause and does not restart.
            apply(t.copy(state = DownloadState.PAUSED, downloadedBytes = partialBytes(t)))
            stopArchive(t)
            scope.launch { dao.update(DownloadEntity.fromTask(find(id) ?: t)) }
        } else if (isStream(t.url, t.mimeType)) {
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

            if (t.isArchive) {
                if (!checkStorageForResume(t)) return@launch
                archiveFailures.remove(id) // asked again by the person: every file gets its full set of tries
                apply(t.copy(state = DownloadState.DOWNLOADING, error = null))
                runArchive(id)
            } else if (isStream(t.url, t.mimeType)) {
                apply(t.copy(state = DownloadState.DOWNLOADING, error = null))
                obtainStreamDownloader().resumeStream(id, t.url, t.mimeType, ua, ck, t.pageUrl)
            } else if (!checkStorageForResume(t)) {
                return@launch
            } else if (t.hasOwnRequest) {
                resumeEngineTask(t)
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
        if (stopSavingAsFile(id)) return
        if (t.isArchive) {
            // As with a pause: the state first, so a listing that ends sees the cancel and does not restart.
            apply(t.copy(state = DownloadState.CANCELLED))
            stopArchive(t)
            scope.launch { dao.updateState(id, DownloadState.CANCELLED) }
            return
        }
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
        savingAsFile.remove(id)?.cancel()
        downloader.cancel(id)
        streamDownloader?.deleteStream(id)
        if (t != null) {
            t.filePath?.let { p ->
                runCatching { File(p).delete() }
                (trackFiles(p) + File("$p$JOINING_SUFFIX") + File("$p$ZIPPING_SUFFIX")).forEach { runCatching { it.delete() } }
                if (t.isArchive) {
                    stopArchive(t)
                    runCatching { File("$p$ITEMS_SUFFIX").deleteRecursively() }
                }
            }
            t.exportedUri?.let { u ->
                runCatching { context.contentResolver.delete(Uri.parse(u), null, null) }
            }
        }
        _downloads.update { list -> list.filterNot { it.id == id } }
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
        val name = sanitizeKeepingExtension(fileNameForMedia(media))
        val saveFile = resolveFileName(saveDir, name)

        val task = DownloadTask(
            id = id,
            url = media.url,
            pageUrl = media.pageUrl,
            fileName = saveFile.name,
            mimeType = media.mimeType,
            filePath = saveFile.absolutePath,
            downloadedBytes = fromBytes,
            state = DownloadState.DOWNLOADING,
            title = media.title,
            thumbnailUrl = media.thumbnailUrl,
            qualityHeight = media.qualityHeight,
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
            fileName = sanitizeKeepingExtension(fileNameForMedia(media)),
            mimeType = media.mimeType,
            filePath = null,
            state = DownloadState.DOWNLOADING,
            title = media.title,
            thumbnailUrl = media.thumbnailUrl,
            qualityHeight = media.qualityHeight,
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
    // Engine downloads: one file, or a picture and a sound joined at the end
    // ------------------------------------------------------------------ //

    /** Begins, or carries on with, the transfer for a task that brings its own request. */
    private fun transfer(t: DownloadTask) {
        if (t.audioUrl != null) runTracks(t.id) else startSingleFile(t)
    }

    private fun startSingleFile(t: DownloadTask) {
        val file = t.filePath?.let(::File) ?: return
        val headers = headersFor(t.url, t.requestHeaders, t.pageUrl)
        downloader.start(
            taskId = t.id,
            url = t.url,
            saveFile = file,
            userAgent = headers.userAgent,
            cookies = headers.cookies,
            referer = headers.referer,
            fromBytes = partialBytes(t),
            onProgress = { downloaded, total -> progress(t.id, downloaded, total ?: find(t.id)?.totalBytes) },
            onComplete = { result ->
                result.fold(
                    onSuccess = { completeTask(t.id) },
                    onFailure = { failTask(t.id, it) },
                )
            },
        )
    }

    /** Picks an interrupted engine task up where it stopped. */
    private fun resumeEngineTask(t: DownloadTask) {
        val file = ensureFileFor(t)
        val resumed = t.copy(state = DownloadState.DOWNLOADING, filePath = file.absolutePath, error = null)
            .let { it.copy(downloadedBytes = partialBytes(it)) }
        apply(resumed)
        transfer(resumed)
    }

    /**
     * What is left of a picture-plus-sound task: the picture, then the sound, then the join. It works out
     * where it is from which files exist, so it is safe to call again after any interruption.
     */
    private fun runTracks(id: Long) {
        val t = find(id) ?: return
        // Paused, cancelled or removed since the last step: leave it alone.
        if (t.state != DownloadState.DOWNLOADING) return
        val path = t.filePath ?: return
        val picture = File("$path$PICTURE_SUFFIX")
        val sound = File("$path$SOUND_SUFFIX")
        when {
            !picture.exists() -> fetchTrack(t, t.url, t.requestHeaders, picture, bytesBefore = 0L)
            !sound.exists() -> {
                val soundUrl = t.audioUrl ?: return
                fetchTrack(t, soundUrl, t.audioRequestHeaders, sound, bytesBefore = picture.length())
            }
            else -> joinTracks(id, picture, sound)
        }
    }

    /** Downloads one track into `<name>.part`, renames it once whole, then goes back for the next step. */
    private fun fetchTrack(
        t: DownloadTask,
        url: String,
        requestHeaders: Map<String, String>,
        whole: File,
        bytesBefore: Long,
    ) {
        val part = File("${whole.path}$PART_SUFFIX")
        val headers = headersFor(url, requestHeaders, t.pageUrl)
        downloader.start(
            taskId = t.id,
            url = url,
            saveFile = part,
            userAgent = headers.userAgent,
            cookies = headers.cookies,
            referer = headers.referer,
            fromBytes = part.length(),
            onProgress = { downloaded, _ -> progress(t.id, bytesBefore + downloaded, find(t.id)?.totalBytes) },
            onComplete = { result ->
                result.fold(
                    onSuccess = {
                        if (part.renameTo(whole)) runTracks(t.id)
                        else failTask(t.id, IOException("Couldn't keep the downloaded file"))
                    },
                    onFailure = { failTask(t.id, it) },
                )
            },
        )
    }

    /** Joins the two finished tracks into the saved file, tidies up, and completes the task. */
    private fun joinTracks(id: Long, picture: File, sound: File) {
        scope.launch {
            val t = find(id) ?: return@launch
            val output = File(t.filePath ?: return@launch)
            val joining = File("${output.path}$JOINING_SUFFIX")
            try {
                merger.merge(picture, sound, joining)
                output.delete()
                if (!joining.renameTo(output)) throw CombineException("Couldn't keep the combined file")
            } catch (e: CancellationException) {
                joining.delete()
                throw e
            } catch (e: Exception) {
                // The two tracks stay on disk, so a retry only repeats the join.
                joining.delete()
                failTask(id, e)
                return@launch
            }
            picture.delete()
            sound.delete()
            val current = find(id) ?: run {
                output.delete() // removed while it was being joined
                return@launch
            }
            val length = output.length()
            apply(current.copy(downloadedBytes = length, totalBytes = length))
            completeTask(id)
        }
    }

    /** The engine's headers for [url] when it gave any; otherwise this browser's own session, as for any page download. */
    private fun headersFor(url: String, engineHeaders: Map<String, String>, pageUrl: String?): RequestHeaders =
        if (engineHeaders.isNotEmpty()) {
            RequestHeaders.from(engineHeaders, fallbackReferer = pageUrl)
        } else {
            sessionHeaders(url).let { (userAgent, cookies) -> RequestHeaders(userAgent, cookies, pageUrl) }
        }

    /** The files a picture-plus-sound task keeps next to its saved file until it is joined. */
    private fun trackFiles(path: String): List<File> = listOf(
        "$path$PICTURE_SUFFIX",
        "$path$PICTURE_SUFFIX$PART_SUFFIX",
        "$path$SOUND_SUFFIX",
        "$path$SOUND_SUFFIX$PART_SUFFIX",
    ).map(::File)

    // ------------------------------------------------------------------ //
    // ZIP archives: list, fetch each file, pack
    // ------------------------------------------------------------------ //

    /**
     * What is left of an archive: its list, then each file not yet fetched, then the ZIP. It works out where it
     * is from the files in its folder, so it is safe to call again after any interruption.
     */
    private fun runArchive(id: Long) {
        val t = find(id) ?: return
        if (t.state != DownloadState.DOWNLOADING) return
        val folder = archiveFolder(t) ?: return
        val list = File(folder, ARCHIVE_LIST)
        if (!list.exists()) {
            listArchive(t, folder, list)
            return
        }
        val entries = ArchiveEntry.readAll(list)
        if (entries.isEmpty()) {
            failTask(id, ArchiveListingException("There was nothing to save", retryable = false))
            return
        }
        val done = entries.count { File(folder, it.name).exists() || File(folder, it.name + SKIPPED_SUFFIX).exists() }
        val next = entries.firstOrNull { !File(folder, it.name).exists() && !File(folder, it.name + SKIPPED_SUFFIX).exists() }
        updateArchive(id, ArchiveProgress(found = entries.size, saved = done, listing = false))
        if (next == null) packArchive(id, folder, entries) else fetchArchiveEntry(t, folder, next)
    }

    private fun listArchive(t: DownloadTask, folder: File, list: File) {
        val lister = archiveLister ?: run {
            failTask(t.id, ArchiveListingException("Saving a whole account isn't available here", retryable = false))
            return
        }
        val stop = File(folder, ARCHIVE_STOP).apply { delete() }
        // Resumed while a listing it had asked to stop is still winding down: removing the stop file above lets
        // that listing carry on, rather than starting a second one beside it.
        if (!listing.add(t.id)) return
        updateArchive(t.id, ArchiveProgress(found = 0, saved = 0, listing = true))
        scope.launch {
            try {
                lister.list(t.url, list, stop) { found ->
                    updateArchive(t.id, ArchiveProgress(found = found, saved = 0, listing = true))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (find(t.id)?.state == DownloadState.DOWNLOADING) failTask(t.id, e)
                return@launch
            } finally {
                listing.remove(t.id)
            }
            // No list means a pause or cancel stopped the listing. If it was resumed in the meantime, list again.
            if (list.exists() || find(t.id)?.state == DownloadState.DOWNLOADING) runArchive(t.id)
        }
    }

    /** Fetches one file of an archive into `<name>.part`, renames it once whole, then goes back for the next. */
    private fun fetchArchiveEntry(t: DownloadTask, folder: File, entry: ArchiveEntry) {
        val whole = File(folder, entry.name)
        val part = File(folder, entry.name + PART_SUFFIX)
        // Never the browser's cookies: an archive uses the person's sign-in only for listing, and only if chosen.
        val engine = RequestHeaders.from(entry.headers, fallbackReferer = t.pageUrl)
        val headers = engine.copy(userAgent = engine.userAgent ?: sessionHeaders(entry.url).first)
        val bytesBefore = folderBytes(folder) - part.length()
        downloader.start(
            taskId = t.id,
            url = entry.url,
            saveFile = part,
            userAgent = headers.userAgent,
            cookies = headers.cookies,
            referer = headers.referer,
            fromBytes = part.length(),
            onProgress = { downloaded, _ -> progress(t.id, bytesBefore + downloaded, null) },
            onComplete = { result ->
                result.fold(
                    onSuccess = {
                        archiveFailures.remove(t.id)
                        if (part.renameTo(whole)) runArchive(t.id)
                        else failTask(t.id, IOException("Couldn't keep the downloaded file"))
                    },
                    onFailure = { error -> afterArchiveFileFailed(t.id, folder, entry, error) },
                )
            },
        )
    }

    /**
     * One file of an archive failed. A file the site no longer has is left out at once. Anything that may pass
     * (a dropped connection, a busy or overloaded server, an unexplained failure) is asked for again after a
     * wait, a few times. After that, a connection or server that is still failing stops the archive so the
     * person can retry later; a file that keeps failing by itself is left out, so one bad file never holds up
     * the rest. Left-out files are named in the archive's note.
     */
    private fun afterArchiveFileFailed(id: Long, folder: File, entry: ArchiveEntry, error: Throwable) {
        if (find(id)?.state != DownloadState.DOWNLOADING) return // paused, cancelled or removed meanwhile
        val kind = DownloadError.from(error)
        if (kind is DownloadError.StorageFull) {
            archiveFailures.remove(id)
            failTask(id, error)
            return
        }
        if (!isGone(error) && kind.retryable) {
            val failures = (archiveFailures[id]?.takeIf { it.first == entry.name }?.second ?: 0) + 1
            val wait = archiveRetryDelaysMillis.getOrNull(failures - 1)
            if (wait != null) {
                archiveFailures[id] = entry.name to failures
                archiveRetries[id] = scope.launch {
                    delay(wait)
                    archiveRetries.remove(id)
                    runArchive(id)
                }
                return
            }
            val serverBusy = (kind as? DownloadError.HttpStatus)?.code?.let { it == 429 || it >= 500 } == true
            if (kind is DownloadError.Network || serverBusy) {
                archiveFailures.remove(id)
                failTask(id, error)
                return
            }
        }
        archiveFailures.remove(id)
        File(folder, entry.name + PART_SUFFIX).delete()
        File(folder, entry.name + SKIPPED_SUFFIX).createNewFile()
        runArchive(id)
    }

    /** Packs every fetched file into the ZIP, names the ones that could not be fetched, tidies up, and completes. */
    private fun packArchive(id: Long, folder: File, entries: List<ArchiveEntry>) {
        scope.launch {
            val t = find(id) ?: return@launch
            val output = File(t.filePath ?: return@launch)
            val packing = File("${output.path}$ZIPPING_SUFFIX")
            try {
                ZipOutputStream(packing.outputStream().buffered()).use { zip ->
                    // Pictures and videos are compressed already; storing them as they are is faster and no bigger.
                    zip.setLevel(Deflater.NO_COMPRESSION)
                    val missing = mutableListOf<String>()
                    for (entry in entries) {
                        val file = File(folder, entry.name)
                        if (!file.exists()) {
                            missing += entry.name
                            continue
                        }
                        zip.putNextEntry(ZipEntry(entry.name).apply { time = file.lastModified() })
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    if (missing.isNotEmpty()) {
                        zip.putNextEntry(ZipEntry(NOT_SAVED_NOTE))
                        zip.write(
                            ("Chaya couldn't save these: the site no longer had them, or would not send them.\n\n" +
                                missing.joinToString("\n") + "\n").toByteArray()
                        )
                        zip.closeEntry()
                    }
                }
                output.delete()
                if (!packing.renameTo(output)) throw IOException("Couldn't keep the archive")
            } catch (e: CancellationException) {
                packing.delete()
                throw e
            } catch (e: Exception) {
                // The fetched files stay, so a retry only packs them again.
                packing.delete()
                failTask(id, e)
                return@launch
            }
            folder.deleteRecursively()
            val current = find(id) ?: run {
                output.delete() // removed while it was being packed
                return@launch
            }
            val length = output.length()
            apply(current.copy(downloadedBytes = length, totalBytes = length, archive = null))
            completeTask(id)
        }
    }

    /** Stops whatever an archive is doing: the file being fetched, or the listing, which checks for a stop file. */
    private fun stopArchive(t: DownloadTask) {
        archiveRetries.remove(t.id)?.cancel()
        downloader.cancel(t.id)
        archiveFolder(t)?.let { runCatching { File(it, ARCHIVE_STOP).createNewFile() } }
    }

    /** Per archive: the file it is fetching and how many times that file has failed on the way. */
    private val archiveFailures = java.util.concurrent.ConcurrentHashMap<Long, Pair<String, Int>>()

    /** Per archive: a wait before asking for a failed file again, which a pause, cancel or delete stops. */
    private val archiveRetries = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    /** Archives whose contents are being listed right now. */
    private val listing: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun archiveFolder(t: DownloadTask): File? =
        t.filePath?.let { File("$it$ITEMS_SUFFIX").apply { mkdirs() } }

    private fun folderBytes(folder: File): Long =
        folder.listFiles()?.filter { it.isFile && it.name != ARCHIVE_LIST && it.name != ARCHIVE_STOP }
            ?.sumOf { it.length() } ?: 0L

    /** Atomic, so a progress report racing a pause cannot write the old state back. */
    private fun updateArchive(id: Long, progress: ArchiveProgress) {
        _downloads.update { tasks -> tasks.map { if (it.id == id) it.copy(archive = progress) else it } }
    }

    /** The site answered that the file is not there (any more), rather than that something failed on the way. */
    private fun isGone(error: Throwable): Boolean {
        val code = (DownloadError.from(error) as? DownloadError.HttpStatus)?.code ?: return false
        return code in GONE_CODES
    }

    // ------------------------------------------------------------------ //
    // Streams saved as files
    // ------------------------------------------------------------------ //

    /** Exports running now, by task, so a pause, cancel or delete can stop them. */
    private val savingAsFile = ConcurrentHashMap<Long, Job>()

    private val exporter: StreamExporter by lazy {
        streamExporter ?: Media3StreamExporter(context, obtainStreamDownloader())
    }

    /** Media3 has every piece of a stream: save it as a file. */
    @VisibleForTesting
    internal fun streamCompleted(id: Long) = startSavingAsFile(id)

    /**
     * Saves a finished stream that lives only in the cache as an MP4 file: the card's *Save as MP4*, for streams
     * downloaded before Chaya did this itself or whose saving failed.
     */
    fun saveAsFile(id: Long) {
        val t = find(id) ?: return
        if (!t.canSaveAsFile) return
        ensureServiceRunning()
        startSavingAsFile(id)
    }

    private fun startSavingAsFile(id: Long) {
        val job = scope.launch(start = CoroutineStart.LAZY) { saveStreamAsFile(id) }
        if (savingAsFile.putIfAbsent(id, job) != null) {
            job.cancel()
            return
        }
        job.invokeOnCompletion { savingAsFile.remove(id, job) }
        job.start()
    }

    /**
     * Exports the cached stream to `<name>.mp4.saving`, renames it once Media3 is done and the file checks out,
     * removes the cached copy and completes the task with the file, which then goes to Movies like any other
     * download. Whatever goes wrong, the cached copy stays, so the stream still plays and can be saved again.
     */
    private suspend fun saveStreamAsFile(id: Long) {
        val t = find(id) ?: return
        val cached = exporter.cachedBytes(id)
        if (cached == null) {
            keepInCache(id, "Plays in Chaya · not all of it is downloaded, so it can't be saved as MP4")
            return
        }
        // Both copies exist for a while: the cached pieces and the new file.
        if (freeBytes(saveDir) < cached + MIN_FREE_BYTES_TO_START) {
            keepInCache(id, "Plays in Chaya · not enough space to save it as MP4")
            return
        }
        val output = resolveFileName(saveDir, sanitize(t.fileName.substringBeforeLast('.').ifBlank { "video" }) + ".mp4")
        val partial = File("${output.path}$SAVING_SUFFIX")
        apply(t.copy(state = DownloadState.DOWNLOADING, savingAsFile = 0f, saveNote = null, error = null))
        val result = try {
            exporter.export(id, partial) { fraction ->
                _downloads.update { list -> list.map { if (it.id == id && it.savingAsFile != null) it.copy(savingAsFile = fraction) else it } }
            }
        } catch (e: CancellationException) {
            partial.delete()
            throw e
        } catch (e: Exception) {
            partial.delete()
            keepInCache(id, "Plays in Chaya · couldn't save it as MP4")
            return
        }
        // A sound-only stream is an M4A, not a video.
        val target = if (result.hasVideo) output else resolveFileName(saveDir, output.nameWithoutExtension + ".m4a")
        if (!partial.renameTo(target)) {
            partial.delete()
            keepInCache(id, "Plays in Chaya · couldn't keep the MP4")
            return
        }
        val current = find(id) ?: run {
            target.delete() // deleted while it was being saved
            return
        }
        runCatching { exporter.removeCached(id) }
        val length = target.length()
        apply(
            current.copy(
                fileName = target.name,
                filePath = target.absolutePath,
                mimeType = if (result.hasVideo) "video/mp4" else "audio/mp4",
                downloadedBytes = length,
                totalBytes = length,
                savingAsFile = null,
                saveNote = if (result.reencoded) "re-encoded to fit MP4" else null,
            ),
        )
        completeTask(id)
    }

    /** Leaves a finished stream in the cache, where it still plays, saying why it is not a file. */
    private suspend fun keepInCache(id: Long, note: String) {
        val t = find(id) ?: return
        val kept = t.copy(
            state = DownloadState.COMPLETED,
            savingAsFile = null,
            saveNote = note,
            error = null,
            updatedAt = System.currentTimeMillis(),
        )
        apply(kept)
        dao.update(DownloadEntity.fromTask(kept))
    }

    /** Stops a stream being saved as a file, if one is; it stays in the cache, still playable. */
    private fun stopSavingAsFile(id: Long): Boolean {
        val job = savingAsFile.remove(id) ?: return false
        job.cancel()
        scope.launch {
            job.join()
            keepInCache(id, "Plays in Chaya · saving as MP4 was stopped")
        }
        return true
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

    // ------------------------------------------------------------------ //
    // Storage pre-flight (Phase 3.2)
    // ------------------------------------------------------------------ //

    /**
     * Refuses a brand-new download when the device is already too full to
     * plausibly hold it. Fails the task immediately with StorageFull (no
     * retry affordance) instead of starting a transfer doomed to die mid-way.
     */
    private suspend fun checkStorageForNewDownload(id: Long, media: DetectedMedia): Boolean {
        if (freeBytes(saveDir) >= MIN_FREE_BYTES_TO_START) return true
        val task = DownloadTask(
            id = id,
            url = media.url,
            pageUrl = media.pageUrl,
            fileName = sanitizeKeepingExtension(fileNameForMedia(media)),
            mimeType = media.mimeType,
            state = DownloadState.FAILED,
            error = DownloadError.StorageFull,
            title = media.title,
            thumbnailUrl = media.thumbnailUrl,
            qualityHeight = media.qualityHeight,
        )
        append(task)
        dao.insert(DownloadEntity.fromTask(task))
        return false
    }

    /**
     * Same guard on resume/retry: a paused task from better days must not
     * restart into a full disk either.
     */
    private suspend fun checkStorageForResume(t: DownloadTask): Boolean {
        if (freeBytes(saveDir) >= MIN_FREE_BYTES_TO_START) return true
        val failed = t.copy(
            state = DownloadState.FAILED,
            error = DownloadError.StorageFull,
            updatedAt = System.currentTimeMillis(),
        )
        apply(failed)
        dao.update(DownloadEntity.fromTask(failed))
        return false
    }

    // ---- progress ---- //

    private fun progress(id: Long, downloadedBytes: Long, totalBytes: Long?) {
        _downloads.update { list ->
            list.map { if (it.id == id) it.copy(downloadedBytes = downloadedBytes, totalBytes = totalBytes) else it }
        }
    }

    private fun apply(task: DownloadTask) {
        // Single choke point for terminal transitions: every state flip is
        // recorded here, not scattered across complete/fail/pause paths.
        val previous = find(task.id)?.state
        _downloads.update { list -> list.map { if (it.id == task.id) task else it } }
        if (previous != null && previous != task.state) {
            eventLog?.record(
                ChayaEvent.DownloadStateChanged(task.id, previous.name, task.state.name)
            )
            val error = task.error
            if (task.state == DownloadState.FAILED && error != null) {
                eventLog?.record(
                    ChayaEvent.DownloadFailed(
                        task.id,
                        error::class.simpleName ?: "Unknown",
                        error.retryable,
                    )
                )
            }
        }
    }

    // Downloads start and finish on several threads at once, so the list is only ever changed with update {}:
    // reading it and writing it back separately would let one change overwrite another, and a download
    // started alongside others (a post's pictures) could go missing from the list.
    private fun append(task: DownloadTask) {
        _downloads.update { it + task }
        eventLog?.record(
            ChayaEvent.DownloadStateChanged(task.id, "NONE", task.state.name)
        )
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
     * foreground-service guarantee documented in docs/BUILD_AND_TEST.md was pure
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

    /** Bytes already on disk for a partial HTTP download; for a picture-plus-sound task, across both files. */
    private fun partialBytes(t: DownloadTask): Long {
        val path = t.filePath ?: return 0L
        if (t.isArchive) return File("$path$ITEMS_SUFFIX").takeIf { it.isDirectory }?.let(::folderBytes) ?: 0L
        return if (t.audioUrl != null) trackFiles(path).sumOf { it.length() } else File(path).length()
    }

    private fun ensureFileFor(t: DownloadTask): File =
        t.filePath?.let { File(it) } ?: resolveFileName(saveDir, sanitizeKeepingExtension(t.fileName))

    /** Server suggested a better filename via Content-Disposition. */
    private fun onServerFileName(id: Long, suggested: String?) {
        if (suggested.isNullOrBlank()) return
        val current = find(id) ?: return
        val clean = sanitizeKeepingExtension(suggested)
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

            override fun onStreamCompleted(taskId: Long) = streamCompleted(taskId)

            override fun onStreamFailed(taskId: Long, reason: Int, cause: Exception?) {
                // The cause, when there is one, lets a refused or dropped connection be explained as such.
                failTask(taskId, cause ?: IOException("Stream download failed (reason $reason)"))
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
                mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
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
        /** Refuse (re)starts below this much free space; transfers need headroom. */
        const val MIN_FREE_BYTES_TO_START = 50L * 1024 * 1024

        /** Suffixes of the files a picture-plus-sound download keeps next to its saved file until they are joined. */
        private const val PICTURE_SUFFIX = ".video"
        private const val SOUND_SUFFIX = ".audio"
        private const val PART_SUFFIX = ".part"
        private const val JOINING_SUFFIX = ".joining"

        /** A stream being written out as a file, until it is checked and renamed. */
        private const val SAVING_SUFFIX = ".saving"

        /** An archive keeps its list and fetched files in `<saved file>.items/` until they are packed. */
        private const val ITEMS_SUFFIX = ".items"
        private const val ARCHIVE_LIST = "list.jsonl"
        private const val ARCHIVE_STOP = "stop"
        private const val SKIPPED_SUFFIX = ".gone"
        private const val ZIPPING_SUFFIX = ".zipping"
        private const val NOT_SAVED_NOTE = "not-saved.txt"
        const val ARCHIVE_MIME = "application/zip"

        /** Answers that mean a file is no longer there, as opposed to a failure worth retrying. */
        private val GONE_CODES = setOf(403, 404, 410)

        /** An archive asks for a failed file again after 3 s, 15 s and a minute before giving up on it. */
        private val ARCHIVE_RETRY_DELAYS_MILLIS = listOf(3_000L, 15_000L, 60_000L)

        private val ILLEGAL_CHARS = Regex("[\\\\/:*?\"<>|]")
        private val resumableStates = setOf(
            DownloadState.PAUSED, DownloadState.FAILED, DownloadState.CANCELLED
        )

        fun isStreamingUrl(url: String): Boolean = StreamDownloader.isStreamingUrl(url)

        fun isStream(url: String, mimeType: String?): Boolean =
            StreamDownloader.isStreamingUrl(url) || StreamDownloader.isStreamingMime(mimeType)

        private const val MAX_FILE_NAME_LENGTH = 100

        fun sanitize(name: String): String =
            ILLEGAL_CHARS.replace(name.trim(), "_").take(MAX_FILE_NAME_LENGTH)
                .ifEmpty { "media_${System.currentTimeMillis()}" }

        /**
         * Like [sanitize], but a long title is shortened instead of losing its extension, which a plain cut
         * at 100 characters would do to "<long title> (720p).mp4".
         */
        fun sanitizeKeepingExtension(name: String): String {
            val dot = name.lastIndexOf('.')
            val extension = if (dot > 0) name.substring(dot) else ""
            // Only a short run of letters and digits is an extension; ".... and more" is just a long title.
            if (extension.length !in 2..6 || !extension.drop(1).all { it.isLetterOrDigit() }) return sanitize(name)
            return sanitize(name.substring(0, dot)).take(MAX_FILE_NAME_LENGTH - extension.length) + extension
        }

        fun fileNameForMedia(media: DetectedMedia): String {
            val url = media.url
            val path = url.substringBefore("?").substringBefore("#")
            val segments = path.split("/")
            val last = segments.lastOrNull { it.isNotBlank() }

            // A title chosen in the media sheet beats any URL-derived name.
            media.suggestedName?.trim()?.takeIf { it.isNotEmpty() }?.let { base ->
                return "$base.${extensionForTitled(media, last)}"
            }

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

        /** Media container extensions a URL may carry; anything else (php, m3u8, …) is ignored. */
        private val FILE_EXTENSIONS = setOf(
            "mp4", "m4v", "webm", "mov", "mkv", "avi", "wmv", "3gp",
            "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "mka",
        )

        /** Streams save as MP4; files keep their own extension, else one implied by the MIME type. */
        private fun extensionForTitled(media: DetectedMedia, lastSegment: String?): String {
            if (isStream(media.url, media.mimeType)) return "mp4"
            lastSegment?.substringAfterLast('.', "")?.lowercase()
                ?.takeIf { it in FILE_EXTENSIONS }
                ?.let { return it }
            val mime = media.mimeType?.substringBefore(';')?.trim()?.lowercase()
            return when (mime) {
                "video/webm", "audio/webm" -> "webm"
                "audio/mp4", "audio/x-m4a" -> "m4a"
                "audio/aac" -> "aac"
                "audio/ogg" -> "ogg"
                "audio/wav", "audio/x-wav" -> "wav"
                "audio/flac" -> "flac"
                else -> if (mime?.startsWith("audio/") == true) "mp3" else "mp4"
            }
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
