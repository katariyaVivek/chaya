package com.chaya.app.library

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import com.chaya.app.download.DownloadTask
import com.chaya.app.download.ownUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.graphics.scale
import java.io.File

/** The files the library shows that are not downloads themselves: video frames and files taken out of ZIPs. */
interface LibraryFiles {
    /** A frame of a finished video, for its tile; null when there is none to take. */
    suspend fun frameFor(task: DownloadTask): File?

    /**
     * A frame big enough to fill the width of the screen and Home's picture across its top (500dp tall): a tile's
     * frame, stretched that far, was a blur.
     */
    suspend fun largeFrameFor(task: DownloadTask): File? = frameFor(task)

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
    private val appContext = context.applicationContext
    private val root = File(context.cacheDir, LIBRARY_DIR)
    private val frames = File(root, "frames")

    /** Named by the download and its file, so a later download that reuses the number never shows this frame. */
    private fun frameFile(task: DownloadTask, large: Boolean = false) =
        File(frames, "${task.id}-${(task.filePath ?: task.ownUri).orEmpty().hashCode().toUInt().toString(16)}${if (large) "-large" else ""}.jpg")

    private fun zipDir(task: DownloadTask) = File(root, "zip/${task.id}")

    override suspend fun frameFor(task: DownloadTask): File? = frame(task, large = false)

    override suspend fun largeFrameFor(task: DownloadTask): File? = frame(task, large = true)

    private suspend fun frame(task: DownloadTask, large: Boolean): File? = withContext(Dispatchers.IO) {
        // A download's file, or one of the person's own videos where it is on the phone
        val source = task.filePath?.let(::File)?.takeIf { it.isFile }
        val own = task.ownUri?.toUri()
        val open: (MediaMetadataRetriever) -> Unit = when {
            source != null -> { retriever -> retriever.setDataSource(source.absolutePath) }
            own != null -> { retriever -> retriever.setDataSource(appContext, own) }
            else -> return@withContext null
        }
        val target = frameFile(task, large)
        if (target.isFile) return@withContext target
        val frame = runCatching { grabFrame(open, if (large) LARGE_FRAME_WIDTH else FRAME_WIDTH) }.getOrNull() ?: return@withContext null
        frames.mkdirs()
        val partial = File(frames, "${target.name}.part")
        runCatching {
            partial.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, if (large) 88 else 80, it) }
            partial.renameTo(target)
        }
        frame.recycle()
        target.takeIf { it.isFile }
    }

    /** A frame a second in (the first is often black), at most [width] wide. */
    private fun grabFrame(open: (MediaMetadataRetriever) -> Unit, width: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            open(retriever)
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime()
                ?: return null
            if (frame.width <= width) return frame
            val scaled = frame.scale(width, frame.height * width / frame.width)
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
        frameFile(task, large = true).delete()
        zipDir(task).deleteRecursively()
    }

    companion object {
        /** Under the app's cache; shared through the file provider's "library" path. */
        const val LIBRARY_DIR = "library"
        private const val FRAME_WIDTH = 480

        /** Home's picture is the screen's width and 500dp tall, cropped from a 16:9 frame: about 1920 wide is sharp. */
        private const val LARGE_FRAME_WIDTH = 1920
    }
}
