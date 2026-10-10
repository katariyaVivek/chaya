package com.chaya.app.library

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.chaya.app.download.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The files the library shows that are not downloads themselves: video frames and files taken out of ZIPs. */
interface LibraryFiles {
    /** A frame of a finished video, for its tile; null when there is none to take. */
    suspend fun frameFor(task: DownloadTask): File?

    /** What a finished ZIP holds. */
    suspend fun zipItems(task: DownloadTask): List<ZipItem>

    /** One file of a ZIP, taken out to view, share or save. */
    suspend fun zipFile(task: DownloadTask, item: ZipItem): File?

    /** Forgets what was made for [task]: its frame and the files taken out of it. */
    fun forget(task: DownloadTask)
}

/**
 * Frames and ZIP files live in the app's cache (`cache/library/`), named by the download, so they go when the
 * download is deleted or Android needs the room.
 */
class CachedLibraryFiles(context: Context) : LibraryFiles {
    private val root = File(context.cacheDir, LIBRARY_DIR)
    private val frames = File(root, "frames")

    /** Named by the download and its file, so a later download that reuses the number never shows this frame. */
    private fun frameFile(task: DownloadTask) =
        File(frames, "${task.id}-${task.filePath.orEmpty().hashCode().toUInt().toString(16)}.jpg")

    private fun zipDir(task: DownloadTask) = File(root, "zip/${task.id}")

    override suspend fun frameFor(task: DownloadTask): File? = withContext(Dispatchers.IO) {
        val source = task.filePath?.let(::File)?.takeIf { it.isFile } ?: return@withContext null
        val target = frameFile(task)
        if (target.isFile) return@withContext target
        val frame = runCatching { grabFrame(source) }.getOrNull() ?: return@withContext null
        frames.mkdirs()
        val partial = File(frames, "${target.name}.part")
        runCatching {
            partial.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            partial.renameTo(target)
        }
        frame.recycle()
        target.takeIf { it.isFile }
    }

    /** A frame a second in (the first is often black), at most [FRAME_WIDTH] wide. */
    private fun grabFrame(video: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime()
                ?: return null
            if (frame.width <= FRAME_WIDTH) return frame
            val scaled = Bitmap.createScaledBitmap(frame, FRAME_WIDTH, frame.height * FRAME_WIDTH / frame.width, true)
            if (scaled !== frame) frame.recycle()
            return scaled
        } finally {
            runCatching { retriever.release() }
        }
    }

    override suspend fun zipItems(task: DownloadTask): List<ZipItem> = withContext(Dispatchers.IO) {
        val zip = task.filePath?.let(::File)?.takeIf { it.isFile } ?: return@withContext emptyList()
        runCatching { ZipContents.list(zip) }.getOrDefault(emptyList())
    }

    override suspend fun zipFile(task: DownloadTask, item: ZipItem): File? = withContext(Dispatchers.IO) {
        val zip = task.filePath?.let(::File)?.takeIf { it.isFile } ?: return@withContext null
        runCatching { ZipContents.extract(zip, item, zipDir(task)) }.getOrNull()
    }

    override fun forget(task: DownloadTask) {
        frameFile(task).delete()
        zipDir(task).deleteRecursively()
    }

    companion object {
        /** Under the app's cache; shared through the file provider's "library" path. */
        const val LIBRARY_DIR = "library"
        private const val FRAME_WIDTH = 480
    }
}
