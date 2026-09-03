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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Handles HLS/DASH stream downloads using Media3's [DownloadManager].
 *
 * Pause = [DownloadManager.stopDownload] (state STOPPED, cache kept),
 * resume = [DownloadManager.startDownload], delete = removeDownload.
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

    private val contentIdCounter = AtomicLong(0)
    internal val taskToContentId = mutableMapOf<Long, String>()
    internal val contentIdToTask = mutableMapOf<String, Long>()

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
                val taskId = contentIdToTask[download.request.id]
                when (download.state) {
                    Download.STATE_COMPLETED -> {
                        if (taskId != null) listener.onStreamCompleted(taskId)
                        untrack(download.request.id)
                    }
                    Download.STATE_FAILED -> {
                        taskId?.let { listener.onStreamFailed(it, download.failureReason) }
                        untrack(download.request.id)
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

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                untrack(download.request.id)
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
        val contentId = track(taskId)
        val builder = DownloadRequest.Builder(contentId, Uri.parse(url))
        mimeType?.let { builder.setMimeType(it) }
        if (!streamKeys.isNullOrEmpty()) builder.setStreamKeys(streamKeys)
        downloadManager.addDownload(builder.build())
    }

    fun pauseStream(taskId: Long) {
        // setStopReason targets a single content id (verified against the
        // media3-exoplayer 1.5.1 API) — earlier this called the app-wide
        // pauseDownloads(), which silently paused every other active stream
        // download too whenever the user paused just one of them.
        val contentId = taskToContentId[taskId] ?: return
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
        val contentId = taskToContentId[taskId]
        if (contentId != null &&
            downloadManager.currentDownloads.any { it.request.id == contentId && it.state == Download.STATE_STOPPED }
        ) {
            downloadManager.setStopReason(contentId, Download.STOP_REASON_NONE)
        } else {
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

    /** Remove the download entirely and drop its cached data. */
    fun deleteStream(taskId: Long) {
        val contentId = taskToContentId.remove(taskId) ?: return
        contentIdToTask.remove(contentId)
        downloadManager.removeDownload(contentId)
    }

    fun cancelAll() {
        taskToContentId.clear()
        contentIdToTask.clear()
        downloadManager.removeAllDownloads()
    }

    fun release() {
        downloadManager.release()
        cache.release()
    }

    // ------------------------------------------------------------------ //

    private fun track(taskId: Long): String =
        taskToContentId[taskId]
            ?: "chaya_${contentIdCounter.incrementAndGet()}".also {
                taskToContentId[taskId] = it
                contentIdToTask[it] = taskId
            }

    private fun untrack(contentId: String) {
        contentIdToTask.remove(contentId)?.let { taskToContentId.remove(it) }
    }

    private fun applyHeaders(ua: String?, cookies: String?, referer: String?) {
        val map = buildMap {
            ua?.let { put("User-Agent", it) }
            cookies?.let { put("Cookie", it) }
            referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        }
        currentHeaders.set(map)
    }

    private fun tickProgress() {
        if (contentIdToTask.isEmpty()) return
        val tracked = contentIdToTask.toMap()
        val current = downloadManager.currentDownloads.associateBy { it.request.id }
        for ((contentId, taskId) in tracked) {
            val d = current[contentId] ?: continue
            if (d.state == Download.STATE_DOWNLOADING) {
                val total = d.contentLength.takeIf { it > 0 }
                listener.onStreamProgress(taskId, d.bytesDownloaded, total)
            }
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
    }
}
