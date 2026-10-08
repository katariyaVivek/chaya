package com.chaya.app

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Makes tiny but real media on the device, with its own encoders, and reads back what a media file holds.
 * Tests get genuine H.264 and AAC without shipping fixtures or needing ffmpeg.
 */
object TestMedia {
    const val VIDEO_FRAMES = 30
    const val VIDEO_FPS = 15
    const val AUDIO_SECONDS = 2

    private const val TIMEOUT_US = 10_000L
    private const val GIVE_UP_AFTER_NANOS = 30_000_000_000L

    /** An MP4 with only a picture: [frames] frames of a moving gradient, H.264. */
    fun pictureOnly(
        file: File,
        frames: Int = VIDEO_FRAMES,
        fps: Int = VIDEO_FPS,
        width: Int = 320,
        height: Int = 180,
        rotation: Int = 0,
    ) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (rotation != 0) muxer.setOrientationHint(rotation)
        var codecStarted = false
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codecStarted = true

            val info = MediaCodec.BufferInfo()
            val frameBytes = width * height * 3 / 2
            val deadline = System.nanoTime() + GIVE_UP_AFTER_NANOS
            var track = -1
            var submitted = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                check(System.nanoTime() < deadline) { "The video encoder took too long" }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val timeUs = submitted * 1_000_000L / fps
                        if (submitted == frames) {
                            codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val image = requireNotNull(codec.getInputImage(index)) { "The encoder gave no input image" }
                            paint(image, submitted)
                            codec.queueInputBuffer(index, 0, frameBytes, timeUs, 0)
                            submitted++
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    out >= 0 -> {
                        val data = requireNotNull(codec.getOutputBuffer(out))
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            data.position(info.offset)
                            data.limit(info.offset + info.size)
                            muxer.writeSampleData(track, data, info)
                        }
                        outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(out, false)
                    }
                }
            }
        } finally {
            runCatching { if (codecStarted) codec.stop() }
            runCatching { codec.release() }
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    /** An M4A with only sound: a 440 Hz tone, AAC-LC. */
    fun soundOnly(file: File, seconds: Int = AUDIO_SECONDS, sampleRate: Int = 44_100) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var codecStarted = false
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codecStarted = true

            val info = MediaCodec.BufferInfo()
            val totalSamples = sampleRate * seconds
            val deadline = System.nanoTime() + GIVE_UP_AFTER_NANOS
            var track = -1
            var submitted = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                check(System.nanoTime() < deadline) { "The audio encoder took too long" }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index))
                        buffer.clear()
                        buffer.order(ByteOrder.LITTLE_ENDIAN)
                        val timeUs = submitted * 1_000_000L / sampleRate
                        val count = minOf(buffer.capacity() / 2, 1024, totalSamples - submitted)
                        if (count <= 0) {
                            codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            repeat(count) { i ->
                                val phase = 2.0 * PI * 440.0 * (submitted + i) / sampleRate
                                buffer.putShort((sin(phase) * 0.3 * Short.MAX_VALUE).toInt().toShort())
                            }
                            codec.queueInputBuffer(index, 0, count * 2, timeUs, 0)
                            submitted += count
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    out >= 0 -> {
                        val data = requireNotNull(codec.getOutputBuffer(out))
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                        if (info.size > 0 && muxerStarted) {
                            data.position(info.offset)
                            data.limit(info.offset + info.size)
                            muxer.writeSampleData(track, data, info)
                        }
                        outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(out, false)
                    }
                }
            }
        } finally {
            runCatching { if (codecStarted) codec.stop() }
            runCatching { codec.release() }
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    /** Fills one raw frame: a diagonal gradient that shifts with [frame], on neutral colour. */
    private fun paint(image: Image, frame: Int) {
        image.planes.forEachIndexed { index, plane ->
            val width = if (index == 0) image.width else image.width / 2
            val height = if (index == 0) image.height else image.height / 2
            val buffer = plane.buffer
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val value = if (index == 0) (x + y + frame * 6) and 0xFF else 128
                    buffer.put(y * plane.rowStride + x * plane.pixelStride, value.toByte())
                }
            }
        }
    }

    /** What a media file holds, as a media framework reads it back. */
    class Inspection(
        val mimeTypes: List<String>,
        /** Sample times in microseconds, per track. */
        val sampleTimesUs: List<List<Long>>,
        val durationMs: Long?,
        val rotation: Int?,
    ) {
        fun trackOf(mimePrefix: String): Int = mimeTypes.indexOfFirst { it.startsWith(mimePrefix) }

        fun times(mimePrefix: String): List<Long> = sampleTimesUs.getOrElse(trackOf(mimePrefix)) { emptyList() }
    }

    fun inspect(file: File): Inspection {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val mimeTypes = (0 until extractor.trackCount).map {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
            }
            repeat(extractor.trackCount) { extractor.selectTrack(it) }
            val times = List(extractor.trackCount) { mutableListOf<Long>() }
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                times[track] += extractor.sampleTime
                if (!extractor.advance()) break
            }
            val (durationMs, rotation) = metadata(file)
            return Inspection(mimeTypes, times, durationMs, rotation)
        } finally {
            extractor.release()
        }
    }

    /** The file's duration in milliseconds and its picture rotation, when it says. */
    private fun metadata(file: File): Pair<Long?, Int?> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() to
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        } finally {
            retriever.release()
        }
    }
}
