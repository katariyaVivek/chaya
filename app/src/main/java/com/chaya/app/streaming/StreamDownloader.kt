package com.chaya.app.streaming

import android.content.Context
import android.net.Uri
import androidx.media3.common.StreamKey
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Handles HLS/DASH stream downloads using Media3's [DownloadManager].
 *
 * Media3 content IDs are derived from the Room task id (`chaya_task_<taskId>`)
 * rather than a process-local counter, so a fresh instance resolves the
 * persisted download index by computing the same ID after process death.
 * Earlier the IDs came from an in-memory counter plus two lookup maps that
 * died with the process: after a restart, pause/delete silently no-oped and
 * resume re-added the download from scratch under a brand-new ID, orphaning
 * the cached segments of the original.
 *
 * Pause = [DownloadManager.setStopReason] (state STOPPED, cache kept),
 * resume = setStopReason back to [Download.STOP_REASON_NONE], delete =
 * [DownloadManager.removeDownload].
 */
class StreamDownloader(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onStreamProgress(taskId: Long, downloadedBytes: Long, totalBytes: Long?)
        fun onStreamCompleted(taskId: Long)
        fun onStreamFailed(taskId: Long, reason: Int)
        fun onStreamPaused(taskId: Long)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val cacheDir = File(context.cacheDir, "media3-cache").also { it.mkdirs() }
    private val databaseProvider = StandaloneDatabaseProvider(context)
    private val cache = SimpleCache(cacheDir, NoOpCacheEvictor(), databaseProvider)

    /** Headers applied to the next created upstream data source. */
    private val currentHeaders =
        AtomicReference<Map<String, String>>(emptyMap())

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(30_000)
        .setReadTimeoutMs(60_000)

    /** Wraps the HTTP factory so each data source picks up the latest headers. */
    private val upstreamFactory = DataSource.Factory {
        currentHeaders.get().takeIf { it.isNotEmpty() }?.let { headers ->
            httpFactory.setDefaultRequestProperties(headers)
        }
        httpFactory.createDataSource()
    }

    private val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setFragmentSize(2 * 1024 * 1024))

    val downloadManager: DownloadManager = DownloadManager(
        context,
        databaseProvider,
        cache,
        cacheDataSourceFactory,
        Runnable::run
    ).apply {
        maxParallelDownloads = 3
        requirements = Requirements(0)

        addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?
            ) {
                val taskId = taskIdFor(download.request.id)
                when (download.state) {
                    Download.STATE_COMPLETED -> {
                        if (taskId != null) listener.onStreamCompleted(taskId)
                    }
                    Download.STATE_FAILED -> {
                        taskId?.let { listener.onStreamFailed(it, download.failureReason) }
                    }
                    Download.STATE_STOPPED -> {
                        taskId?.let { listener.onStreamPaused(it) }
                    }
                    Download.STATE_DOWNLOADING, Download.STATE_QUEUED -> {
                        taskId?.let {
                            val total = download.contentLength.takeIf { len -> len > 0 }
                            listener.onStreamProgress(it, download.bytesDownloaded, total)
                        }
                    }
                }
            }
        })
    }

    init {
        // Smooth progress reporting — onDownloadChanged alone is too coarse.
        scope.launch {
            while (isActive) {
                delay(500)
                tickProgress()
            }
        }
    }

    fun startStreamDownload(
        taskId: Long,
        url: String,
        mimeType: String?,
        userAgent: String?,
        cookies: String?,
        referer: String? = null,
        streamKeys: List<StreamKey>? = null
    ) {
        applyHeaders(userAgent, cookies, referer)
        val contentId = contentIdFor(taskId)
        if (downloadManager.currentDownloads.any { it.request.id == contentId }) {
            // Already known to Media3 (e.g. resume after process death) —
            // clear the stop reason instead of re-adding, so cached segments
            // and download progress survive.
            downloadManager.setStopReason(contentId, Download.STOP_REASON_NONE)
            return
        }
        val builder = DownloadRequest.Builder(contentId, Uri.parse(url))
        mimeType?.let { builder.setMimeType(it) }
        if (!streamKeys.isNullOrEmpty()) builder.setStreamKeys(streamKeys)
        downloadManager.addDownload(builder.build())
    }

    fun pauseStream(taskId: Long) {
        val contentId = contentIdFor(taskId)
        // DownloadManager call sites also receive HTTP task ids, which are
        // never stream downloads — skip ids absent from Media3's index rather
        // than forwarding them.
        if (downloadManager.currentDownloads.none { it.request.id == contentId }) return
        // setStopReason targets a single content id (verified against the
        // media3-exoplayer 1.5.1 API) — earlier this called the app-wide
        // pauseDownloads(), which silently paused every other active stream
        // download too whenever the user paused just one of them.
        downloadManager.setStopReason(contentId, STOP_REASON_PAUSED)
    }

    fun resumeStream(
        taskId: Long,
        url: String,
        mimeType: String?,
        userAgent: String?,
        cookies: String?,
        referer: String? = null
    ) {
        applyHeaders(userAgent, cookies, referer)
        val contentId = contentIdFor(taskId)
        val stopped = downloadManager.currentDownloads.any {
            it.request.id == contentId && it.state == Download.STATE_STOPPED
        }
        if (stopped) {
            downloadManager.setStopReason(contentId, Download.STOP_REASON_NONE)
        } else {
            // Either the download is gone (completed/failed/removed) or was
            // never a stream download — (re)start is correct in both cases
            // and reuses the same derived content id either way.
            startStreamDownload(taskId, url, mimeType, userAgent, cookies, referer)
        }
    }

    /** Cancel but keep cached segments so a later resume is cheap. */
    fun stopStream(taskId: Long) = pauseStream(taskId)

    /** Cache-aware data source factory for in-app playback of finished streams. */
    fun playbackDataSourceFactory(
        userAgent: String?,
        cookies: String?,
        referer: String?
    ): DataSource.Factory {
        applyHeaders(userAgent, cookies, referer)
        return cacheDataSourceFactory
    }

    fun deleteStream(taskId: Long) {
        // Safe for any taskId: removing an id absent from Media3's index is a
        // no-op on the index, so HTTP task ids pass through harmlessly.
        downloadManager.removeDownload(contentIdFor(taskId))
    }

    fun cancelAll() {
        downloadManager.removeAllDownloads()
    }

    fun release() {
        downloadManager.release()
        cache.release()
    }

    // ------------------------------------------------------------------ //

    /** Stable Media3 content id for a task id — computable after process death. */
    private fun contentIdFor(taskId: Long): String = "$CONTENT_ID_PREFIX$taskId"

    /** Inverse of [contentIdFor]; null for ids Chaya did not create. */
    internal fun taskIdFor(contentId: String): Long? =
        contentId.removePrefix(CONTENT_ID_PREFIX)
            .takeIf { it != contentId }
            ?.toLongOrNull()

    private fun applyHeaders(ua: String?, cookies: String?, referer: String?) {
        val map = buildMap {
            ua?.let { put("User-Agent", it) }
            cookies?.let { put("Cookie", it) }
            referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        }
        currentHeaders.set(map)
    }

    private fun tickProgress() {
        for (d in downloadManager.currentDownloads) {
            if (d.state != Download.STATE_DOWNLOADING) continue
            val taskId = taskIdFor(d.request.id) ?: continue
            val total = d.contentLength.takeIf { it > 0 }
            listener.onStreamProgress(taskId, d.bytesDownloaded, total)
        }
    }

    companion object {
        fun isStreamingUrl(url: String): Boolean {
            val lower = url.lowercase()
            return lower.endsWith(".m3u8") || lower.endsWith(".mpd")
        }

        fun isStreamingMime(mimeType: String?): Boolean {
            if (mimeType == null) return false
            return mimeType.contains("mpegurl") || mimeType.contains("dash+xml")
        }

        /** Any non-zero value marks a download STOPPED without touching sibling downloads. */
        private const val STOP_REASON_PAUSED = 1

        private const val CONTENT_ID_PREFIX = "chaya_task_"
    }
}
