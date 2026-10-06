package com.chaya.app.streaming

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.StreamKey
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.DownloadHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
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
        userAgent: String?,
        cookies: String? = null
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
                if (!cookies.isNullOrBlank()) {
                    dataSourceFactory.setDefaultRequestProperties(mapOf("Cookie" to cookies))
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

    /**
     * One entry per rendition. An HLS master or DASH adaptation set puts every
     * rendition in a single adaptive group; listing the group as one entry
     * labeled by its first format hid the choice and downloaded all of them.
     */
    private fun extractTracks(helper: DownloadHelper): List<StreamTrack> {
        val tracks = mutableListOf<StreamTrack>()

        for (period in 0 until helper.periodCount) {
            val trackInfo = helper.getMappedTrackInfo(period)
            for (renderer in 0 until trackInfo.rendererCount) {
                val rendererType = trackInfo.getRendererType(renderer)
                if (rendererType != C.TRACK_TYPE_VIDEO && rendererType != C.TRACK_TYPE_AUDIO) continue

                val trackGroups = trackInfo.getTrackGroups(renderer)
                for (group in 0 until trackGroups.length) {
                    val trackGroup = trackGroups.get(group)
                    for (trackIndex in 0 until trackGroup.length) {
                        tracks += trackFor(
                            format = trackGroup.getFormat(trackIndex),
                            rendererType = rendererType,
                            streamKey = StreamKey(period, group, trackIndex),
                        )
                    }
                }
            }
        }

        return orderedForPicker(tracks)
    }

    /** Labels a rendition the way a person chooses: "1080p", then the facts behind it. */
    internal fun trackFor(format: Format, rendererType: Int, streamKey: StreamKey): StreamTrack {
        val bitrate = maxOf(format.bitrate, format.peakBitrate, format.averageBitrate, 0)
        return when (rendererType) {
            C.TRACK_TYPE_VIDEO -> StreamTrack(
                rendererType = rendererType,
                label = if (format.height > 0) "${format.height}p" else "Video",
                streamKeys = listOf(streamKey),
                height = maxOf(format.height, 0),
                bitrate = bitrate,
                detail = listOfNotNull(
                    if (format.width > 0 && format.height > 0) "${format.width}×${format.height}" else null,
                    formatBitrate(bitrate),
                ).joinToString(" · "),
            )
            else -> StreamTrack(
                rendererType = rendererType,
                label = format.language
                    ?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
                    ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifBlank { it } }
                    ?: format.label
                    ?: "Audio",
                streamKeys = listOf(streamKey),
                bitrate = bitrate,
                detail = listOfNotNull(
                    formatBitrate(bitrate),
                    if (format.channelCount > 0) "${format.channelCount}ch" else null,
                ).joinToString(" · "),
            )
        }
    }

    /** Video first, best quality on top; audio after, in manifest order. */
    internal fun orderedForPicker(tracks: List<StreamTrack>): List<StreamTrack> {
        val (video, audio) = tracks.partition { it.rendererType == C.TRACK_TYPE_VIDEO }
        return video.sortedWith(compareByDescending<StreamTrack> { it.height }.thenByDescending { it.bitrate }) + audio
    }

    private fun formatBitrate(bitsPerSecond: Int): String? = when {
        bitsPerSecond <= 0 -> null
        bitsPerSecond >= 1_000_000 -> "%.1f Mbps".format(Locale.ROOT, bitsPerSecond / 1_000_000.0)
        else -> "${bitsPerSecond / 1000} kbps"
    }
}
