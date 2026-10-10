package com.chaya.app.streaming

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/** How a stream was written out. */
data class StreamExport(
    /** False for a stream of sound only, saved as M4A. */
    val hasVideo: Boolean,
    /** The samples had to be decoded and encoded again, because the MP4 container could not take them as they were. */
    val reencoded: Boolean,
)

/** A stream could not be saved as a file; its cached copy is untouched. */
class StreamExportException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Writes a finished stream download (HLS or DASH, kept in Media3's cache as pieces) out as one MP4 file, so it
 * can be shared, seen in the gallery and opened by other apps.
 */
interface StreamExporter {
    /** What the stream takes in the cache, or null when it is not all there: a partly cached stream is never exported. */
    fun cachedBytes(taskId: Long): Long?

    /**
     * Writes the stream of [taskId] into [output], reporting progress from 0 to 1. Cancelling stops it.
     * @throws StreamExportException when it cannot be written; [output] is then left for the caller to delete.
     */
    suspend fun export(taskId: Long, output: File, onProgress: (Float) -> Unit): StreamExport

    /** Removes the cached copy, once the file has been checked and kept. */
    fun removeCached(taskId: Long)
}

/**
 * [StreamExporter] with Media3 Transformer. It reads the cache only, through the stream's own download request
 * (so the qualities that were downloaded are the ones read), and never the network. The samples are copied as
 * they are when the MP4 container can take them (H.264 or HEVC with AAC, nearly every HLS stream), which takes
 * seconds and loses nothing; otherwise Transformer re-encodes them, and the result says so.
 */
class Media3StreamExporter(
    context: Context,
    private val streams: StreamDownloader,
) : StreamExporter {
    private val appContext = context.applicationContext

    override fun cachedBytes(taskId: Long): Long? = streams.completedDownload(taskId)?.bytesDownloaded

    override suspend fun export(taskId: Long, output: File, onProgress: (Float) -> Unit): StreamExport {
        val download = withContext(Dispatchers.IO) { streams.completedDownload(taskId) }
            ?: throw StreamExportException("The stream is not all downloaded")
        output.delete()
        val result = withContext(Dispatchers.Main) {
            coroutineScope {
                val transformer = Transformer.Builder(appContext)
                    .setAssetLoaderFactory(
                        DefaultAssetLoaderFactory(
                            appContext,
                            DefaultDecoderFactory.Builder(appContext).build(),
                            Clock.DEFAULT,
                            DefaultMediaSourceFactory(streams.cacheOnlyDataSourceFactory()),
                            DataSourceBitmapLoader(appContext),
                        ),
                    )
                    .build()
                val progress = launch {
                    val holder = ProgressHolder()
                    while (isActive) {
                        delay(PROGRESS_EVERY_MILLIS)
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(holder.progress / 100f)
                        }
                    }
                }
                try {
                    run(transformer, download.request.toMediaItem(), output)
                } finally {
                    progress.cancel()
                }
            }
        }
        withContext(Dispatchers.IO) { verify(output, result) }
        onProgress(1f)
        return StreamExport(
            hasVideo = result.videoMimeType != null,
            reencoded = result.videoConversionProcess.isTranscoded() || result.audioConversionProcess.isTranscoded(),
        )
    }

    override fun removeCached(taskId: Long) = streams.deleteStream(taskId)

    /** Starts [transformer] and waits for its answer; cancelling the wait cancels the export. */
    private suspend fun run(transformer: Transformer, item: androidx.media3.common.MediaItem, output: File): ExportResult =
        suspendCancellableCoroutine { continuation ->
            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (continuation.isActive) continuation.resume(exportResult)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            StreamExportException("Couldn't save the stream as a file (${exportException.errorCodeName})", exportException),
                        )
                    }
                }
            })
            // The transformer belongs to the main thread; a cancel can arrive from any other.
            continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
            transformer.start(item, output.absolutePath)
        }

    /** The file plays as the export said: the tracks it reported are there, and it lasts as long. */
    private fun verify(output: File, result: ExportResult) {
        if (!output.isFile || output.length() == 0L) throw StreamExportException("The saved file is empty")
        val mimeTypes = MediaExtractor().run {
            try {
                setDataSource(output.absolutePath)
                (0 until trackCount).mapNotNull { getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
            } catch (e: Exception) {
                throw StreamExportException("The saved file cannot be read", e)
            } finally {
                release()
            }
        }
        if (result.videoMimeType != null && mimeTypes.none { it.startsWith("video/") }) {
            throw StreamExportException("The saved file has no picture")
        }
        if (result.audioMimeType != null && mimeTypes.none { it.startsWith("audio/") }) {
            throw StreamExportException("The saved file has no sound")
        }
        if (mimeTypes.isEmpty()) throw StreamExportException("The saved file holds nothing")
        val durationMs = MediaMetadataRetriever().run {
            try {
                setDataSource(output.absolutePath)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } catch (e: Exception) {
                null
            } finally {
                runCatching { release() }
            }
        } ?: throw StreamExportException("The saved file has no duration")
        if (durationMs <= 0) throw StreamExportException("The saved file has no duration")
        if (result.durationMs > 0) {
            val allowed = maxOf(DURATION_SLACK_MILLIS, result.durationMs / 20)
            if (abs(durationMs - result.durationMs) > allowed) {
                throw StreamExportException("The saved file lasts ${durationMs} ms instead of ${result.durationMs} ms")
            }
        }
    }

    private fun Int.isTranscoded() =
        this == ExportResult.CONVERSION_PROCESS_TRANSCODED || this == ExportResult.CONVERSION_PROCESS_TRANSMUXED_AND_TRANSCODED

    private companion object {
        const val PROGRESS_EVERY_MILLIS = 250L
        const val DURATION_SLACK_MILLIS = 1_000L
    }
}
