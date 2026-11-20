package com.chaya.app.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.graphics.scale
import com.chaya.app.subtitles.PeakMeter
import java.io.File
import java.nio.ByteOrder

/**
 * What the clip timeline is drawn from, read on the phone with its own decoders: how loud the sound is ([soundPeaks],
 * kept in the cache so it is read once) and frames of the picture ([frameAt]). Neither leaves the phone.
 */
object ClipMedia {
    private const val TIMEOUT_US = 10_000L

    /** The peaks of [uri]'s sound ([PeakMeter]), read once and kept as "12.peaks" in the cache; null without sound. */
    fun soundPeaks(context: Context, id: Long, uri: Uri): ByteArray? {
        val kept = File(File(context.cacheDir, "peaks"), "$id.peaks")
        if (kept.isFile) return runCatching { kept.readBytes() }.getOrNull()
        val peaks = runCatching { decodePeaks(context, uri) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching {
            kept.parentFile?.mkdirs()
            kept.writeBytes(peaks)
        }
        return peaks
    }

    /** Decodes the first sound track of [uri] to PCM and measures it as it comes; nothing but the peaks is kept. */
    fun decodePeaks(context: Context, uri: Uri): ByteArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var meter: PeakMeter? = null
            var float = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val input = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (input >= 0) {
                        val buffer = decoder.getInputBuffer(input)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = decoder.outputFormat
                    float = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    meter = PeakMeter(out.getInteger(MediaFormat.KEY_SAMPLE_RATE), out.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                } else if (output >= 0) {
                    val buffer = decoder.getOutputBuffer(output)
                    val measuring = meter ?: PeakMeter(
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    ).also { meter = it }
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val ordered = buffer.order(ByteOrder.nativeOrder())
                        if (float) {
                            val samples = ordered.asFloatBuffer()
                            while (samples.hasRemaining()) {
                                measuring.add((samples.get().coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
                            }
                        } else {
                            val samples = ordered.asShortBuffer()
                            while (samples.hasRemaining()) measuring.add(samples.get())
                        }
                    }
                    decoder.releaseOutputBuffer(output, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return meter?.peaks()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** A frame of [uri] near [ms], [height] pixels high; null when there is none. */
    fun frameAt(context: Context, uri: Uri, ms: Long, height: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val frame = retriever.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val width = maxOf(1, frame.width * height / maxOf(1, frame.height))
            frame.scale(width, height).also { if (it !== frame) frame.recycle() }
        } catch (_: RuntimeException) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
