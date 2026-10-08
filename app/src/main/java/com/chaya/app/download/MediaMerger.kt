package com.chaya.app.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Joins a file that has only a picture and a file that has only sound into one MP4. */
interface MediaMerger {
    /**
     * Writes [picture] and [sound] into [output] as one MP4 without re-encoding.
     * @throws CombineException when they cannot be joined; nothing is left at [output] in that case.
     */
    fun merge(picture: File, sound: File, output: File)
}

/** The picture and the sound could not be joined into one file. */
class CombineException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Joins tracks with Android's [MediaMuxer]. It copies the already-compressed samples from one file to the
 * other, so it takes seconds and loses no quality. The picture and the sound are interleaved by timestamp so
 * the result plays and seeks smoothly instead of holding all the picture first.
 */
class Mp4Merger : MediaMerger {

    override fun merge(picture: File, sound: File, output: File) {
        val pictureSource = MediaExtractor()
        val soundSource = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        var finished = false
        try {
            pictureSource.setDataSource(picture.absolutePath)
            soundSource.setDataSource(sound.absolutePath)
            val pictureTrack = firstTrack(pictureSource, "video/")
                ?: throw CombineException("There is no picture in ${picture.name}")
            val soundTrack = firstTrack(soundSource, "audio/")
                ?: throw CombineException("There is no sound in ${sound.name}")
            pictureSource.selectTrack(pictureTrack)
            soundSource.selectTrack(soundTrack)
            val pictureFormat = pictureSource.getTrackFormat(pictureTrack)
            val soundFormat = soundSource.getTrackFormat(soundTrack)

            output.delete()
            val writer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            rotationOf(picture)?.let { writer.setOrientationHint(it) }
            val pictureOut = writer.addTrack(pictureFormat)
            val soundOut = writer.addTrack(soundFormat)
            writer.start()
            started = true

            copyInterleaved(
                first = pictureSource, firstOut = pictureOut,
                second = soundSource, secondOut = soundOut,
                muxer = writer,
                bufferSize = bufferSizeFor(pictureFormat, soundFormat),
            )
            writer.stop()
            finished = true
        } catch (e: CombineException) {
            output.delete()
            throw e
        } catch (e: Exception) {
            output.delete()
            throw CombineException("Couldn't combine ${picture.name} and ${sound.name}", e)
        } finally {
            if (started && !finished) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { pictureSource.release() }
            runCatching { soundSource.release() }
        }
    }

    private fun firstTrack(extractor: MediaExtractor, mimePrefix: String): Int? =
        (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true
        }

    /** Writes whichever source has the earlier next sample, so the two stay in step. */
    private fun copyInterleaved(
        first: MediaExtractor,
        firstOut: Int,
        second: MediaExtractor,
        secondOut: Int,
        muxer: MediaMuxer,
        bufferSize: Int,
    ) {
        val buffer = ByteBuffer.allocateDirect(bufferSize)
        val info = MediaCodec.BufferInfo()
        var firstTime = first.sampleTime
        var secondTime = second.sampleTime
        while (firstTime >= 0 || secondTime >= 0) {
            val useFirst = secondTime < 0 || (firstTime in 0L..secondTime)
            val source = if (useFirst) first else second
            buffer.clear()
            val size = source.readSampleData(buffer, 0)
            if (size < 0) {
                if (useFirst) firstTime = -1 else secondTime = -1
                continue
            }
            val keyFrame = (source.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
            info.set(0, size, source.sampleTime, if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(if (useFirst) firstOut else secondOut, buffer, info)
            source.advance()
            if (useFirst) firstTime = source.sampleTime else secondTime = source.sampleTime
        }
    }

    /** Large enough for the biggest sample either track says it has, within sane limits. */
    private fun bufferSizeFor(vararg formats: MediaFormat): Int {
        val advertised = formats.maxOf { format ->
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
        }
        return advertised.coerceIn(MIN_SAMPLE_BUFFER_BYTES, MAX_SAMPLE_BUFFER_BYTES)
    }

    /** A portrait video recorded sideways says so in its metadata; keep that, or it would play rotated. */
    private fun rotationOf(file: File): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?.takeIf { it == 90 || it == 180 || it == 270 }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        private const val MIN_SAMPLE_BUFFER_BYTES = 2 * 1024 * 1024
        private const val MAX_SAMPLE_BUFFER_BYTES = 16 * 1024 * 1024

        /**
         * Whether the muxer can put a picture with this codec into an MP4 on every phone chaya runs on. Only H.264
         * for now: it covers YouTube up to 1080p. HEVC and AV1 depend on the Android version and are not verified.
         */
        fun canJoinPicture(videoCodec: String?): Boolean {
            val codec = videoCodec?.lowercase() ?: return false
            return codec.startsWith("avc1") || codec.startsWith("avc3")
        }
    }
}
