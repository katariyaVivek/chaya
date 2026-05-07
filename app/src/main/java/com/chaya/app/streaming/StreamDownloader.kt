package com.chaya.app.streaming

import android.content.Context
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.StreamKey
import androidx.media3.database.StandaloneDatabaseProvider
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Handles HLS/DASH stream downloads using Media3's [DownloadManager].
 */
class StreamDownloader(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onStreamProgress(taskId: Long, downloadedBytes: Long, totalBytes: Long?)
        fun onStreamCompleted(taskId: Long)
        fun onStreamFailed(taskId: Long, reason: Int)
    }

    private val cacheDir = File(context.cacheDir, "media3-cache").also { it.mkdirs() }
    private val databaseProvider = StandaloneDatabaseProvider(context)
    private val cache = SimpleCache(cacheDir, NoOpCacheEvictor(), databaseProvider)

    private val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(30_000)
        .setReadTimeoutMs(60_000)

    private val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(dataSourceFactory)
        .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setFragmentSize(2 * 1024 * 1024))

    private val contentIdCounter = AtomicLong(0)

    internal val taskToContentId = mutableMapOf<Long, String>()
    private val contentIdToTask = mutableMapOf<String, Long>()

    val downloadManager: DownloadManager = DownloadManager(
        context,
        databaseProvider,
        cache,
        cacheDataSourceFactory
    ).apply {
        maxParallelDownloads = 3
        requirements = androidx.media3.common.util.Requirements(0)

        addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?
            ) {
                val taskId = contentIdToTask[download.request.id] ?: return
                when (download.state) {
                    Download.STATE_COMPLETED -> {
                        taskToContentId.remove(taskId)
                        contentIdToTask.remove(download.request.id)
                        listener.onStreamCompleted(taskId)
                    }
                    Download.STATE_FAILED -> {
                        taskToContentId.remove(taskId)
                        contentIdToTask.remove(download.request.id)
                        listener.onStreamFailed(taskId, download.failureReason)
                    }
                    Download.STATE_DOWNLOADING -> {
                        val total = if (download.contentLength > 0) download.contentLength else null
                        listener.onStreamProgress(taskId, download.bytesDownloaded, total)
                    }
                }
            }

            override fun onDownloadRemoved(
                downloadManager: DownloadManager,
                download: Download
            ) {
                val taskId = contentIdToTask.remove(download.request.id) ?: return
                taskToContentId.remove(taskId)
            }
        })
    }

    fun startStreamDownload(
        taskId: Long,
        url: String,
        mimeType: String?,
        userAgent: String?,
        cookies: String?,
        streamKeys: List<StreamKey>? = null
    ) {
        val contentId = "chaya_${contentIdCounter.incrementAndGet()}"
        taskToContentId[taskId] = contentId
        contentIdToTask[contentId] = taskId

        val requestBuilder = DownloadRequest.Builder(contentId, url)

        if (mimeType != null) {
            requestBuilder.setMimeType(mimeType)
        }
        if (userAgent != null) {
            requestBuilder.setCustomCacheKey("ua:$userAgent")
        }
        if (!streamKeys.isNullOrEmpty()) {
            requestBuilder.setStreamKeys(streamKeys)
        }

        downloadManager.addDownload(requestBuilder.build())
    }

    fun cancelStreamDownload(taskId: Long) {
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

    companion object {
        fun isStreamingUrl(url: String): Boolean {
            val lower = url.lowercase()
            return lower.endsWith(".m3u8") || lower.endsWith(".mpd")
        }

        fun isStreamingMime(mimeType: String?): Boolean {
            if (mimeType == null) return false
            return mimeType.contains("mpegurl") ||
                    mimeType.contains("dash+xml") ||
                    mimeType.startsWith("application/vnd.apple.mpegurl")
        }
    }
}
