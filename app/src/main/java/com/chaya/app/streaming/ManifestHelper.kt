package com.chaya.app.streaming

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.source.DefaultRenderersFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume

object ManifestHelper {

    /**
     * Downloads the manifest at [url] and extracts available video/audio tracks.
     *
     * @return success with a list of [StreamTrack]s, or failure with an [IOException].
     */
    suspend fun parse(
        context: Context,
        url: String,
        mimeType: String?,
        userAgent: String?
    ): Result<List<StreamTrack>> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            try {
                val dataSourceFactory = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(30_000)
                if (userAgent != null) {
                    dataSourceFactory.setUserAgent(userAgent)
                }

                val mediaItem = if (mimeType != null) {
                    MediaItem.Builder().setUri(url).setMimeType(mimeType).build()
                } else {
                    MediaItem.fromUri(url)
                }

                val helper = DownloadHelper.forMediaItem(
                    context,
                    mediaItem,
                    DefaultRenderersFactory(context),
                    dataSourceFactory
                )

                continuation.invokeOnCancellation { helper.release() }

                helper.prepare(object : DownloadHelper.Callback {
                    override fun onPrepared(helper: DownloadHelper) {
                        val tracks = extractTracks(helper)
                        helper.release()
                        if (continuation.isActive) {
                            continuation.resume(Result.success(tracks))
                        }
                    }

                    override fun onPrepareError(helper: DownloadHelper, e: IOException) {
                        helper.release()
                        if (continuation.isActive) {
                            continuation.resume(Result.failure(e))
                        }
                    }
                })
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resume(Result.failure(e))
                }
            }
        }
    }

    private fun extractTracks(helper: DownloadHelper): List<StreamTrack> {
        val tracks = mutableListOf<StreamTrack>()

        for (period in 0 until helper.periodCount) {
            val trackInfo = helper.getMappedTrackInfo(period)
            for (renderer in 0 until trackInfo.rendererCount) {
                val rendererType = trackInfo.getRendererType(renderer)
                if (rendererType != C.TRACK_TYPE_VIDEO && rendererType != C.TRACK_TYPE_AUDIO) continue

                val trackGroups = trackInfo.getTrackGroups(renderer)
                for (group in 0 until trackGroups.length) {
                    val trackGroup = trackGroups[group]
                    // Use the first format in the group to build a label
                    val format = trackGroup[0]
                    val label = buildLabel(format, rendererType)

                    // Collect all stream keys for this group
                    val indices = (0 until trackGroup.length).toList()
                    val streamKeys = helper.getStreamKeys(renderer, group, indices)

                    tracks.add(StreamTrack(rendererType, label, streamKeys))
                }
            }
        }

        return tracks
    }

    private fun buildLabel(format: Format, rendererType: Int): String = when (rendererType) {
        C.TRACK_TYPE_VIDEO -> {
            val bits = mutableListOf<String>()
            if (format.height > 0) bits.add("${format.height}p")
            if (format.width > 0 && format.height > 0) bits.add("${format.width}x${format.height}")
            if (format.bitrate > 0) bits.add("${format.bitrate / 1000} kbps")
            if (format.codecs != null) bits.add(format.codecs)
            bits.joinToString(" · ").ifEmpty { "Video" }
        }
        C.TRACK_TYPE_AUDIO -> {
            val bits = mutableListOf<String>()
            bits.add(format.language?.uppercase() ?: "Audio")
            if (format.bitrate > 0) bits.add("${format.bitrate / 1000} kbps")
            if (format.channelCount > 0) bits.add("${format.channelCount}ch")
            bits.joinToString(" · ").ifEmpty { "Audio" }
        }
        else -> "Track"
    }
}
