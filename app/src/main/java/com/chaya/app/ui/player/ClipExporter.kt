package com.chaya.app.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMuxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.chaya.app.subtitles.ClipExport
import com.chaya.app.subtitles.SubtitleLine
import com.chaya.app.subtitles.Subtitles
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

/** A clip could not be saved as a video, in words. */
class ClipExportException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A clip saved as a video of its own with Media3 Transformer, on the phone's own hardware encoder: cut from the saved
 * video, its subtitles (and their translation, under them) drawn on as an overlay, and, with a size to fit, at the bit
 * rate and picture size [ClipExport.fit] works out. No ffmpeg.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object ClipExporter {
    /** What a clip is made from: the video, the span, the lines to draw (none for a clean cut), a size in MB. */
    class Job(
        val video: Uri,
        val start: Long,
        val end: Long,
        val lines: List<SubtitleLine>,
        val sizeMb: Int?,
        val out: File,
    )

    /** The picture's size and whether it has sound, as the phone reads them. */
    private class Probe(val width: Int, val height: Int, val sound: Boolean)

    private fun probe(context: Context, video: Uri): Probe {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, video)
            fun read(key: Int) = retriever.extractMetadata(key)?.toIntOrNull()
            val rotated = read(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.let { it % 180 != 0 } == true
            val w = read(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: throw ClipExportException("This video has no picture to cut.")
            val h = read(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: throw ClipExportException("This video has no picture to cut.")
            val sound = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            return if (rotated) Probe(h, w, sound) else Probe(w, h, sound)
        } catch (e: RuntimeException) {
            throw ClipExportException("Chaya couldn't read this video.", e)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Saves [job], reporting how far from 0 to 1; cancelling stops it. */
    suspend fun export(context: Context, job: Job, onProgress: (Float) -> Unit) {
        val app = context.applicationContext
        val probe = withContext(Dispatchers.IO) { probe(app, job.video) }
        val length = (job.end - job.start).coerceAtLeast(1)
        val fit = job.sizeMb?.let { mb ->
            ClipExport.fit(mb * 1_000_000L, length, probe.width, probe.height, probe.sound)
                ?: throw ClipExportException("This clip is too long to fit in $mb MB. Trim it, or choose a larger size.")
        }
        val videoEffects = buildList {
            if (fit != null && (fit.width != probe.width || fit.height != probe.height)) {
                add(Presentation.createForWidthAndHeight(fit.width, fit.height, Presentation.LAYOUT_SCALE_TO_FIT))
            }
            if (job.lines.isNotEmpty()) add(OverlayEffect(listOf(SubtitleBurn(ClipExport.inside(job.lines, job.start, job.end)))))
        }
        val item = EditedMediaItem.Builder(
            MediaItem.Builder()
                .setUri(job.video)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(job.start).setEndPositionMs(job.end).build(),
                )
                .build(),
        ).setEffects(Effects(emptyList(), videoEffects)).build()
        val width = fit?.width ?: probe.width
        val height = fit?.height ?: probe.height
        val encoders = DefaultEncoderFactory.Builder(app)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(fit?.video ?: ClipExport.bitRate(width, height)).build())
            .apply { fit?.takeIf { it.audio > 0 }?.let { setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(it.audio).build()) } }
            .build()
        job.out.parentFile?.mkdirs()
        job.out.delete()
        withContext(Dispatchers.Main) {
            coroutineScope {
                val transformer = Transformer.Builder(app)
                    .setEncoderFactory(encoders)
                    .setMuxerFactory(InAppMuxer.Factory.Builder().build())
                    .build()
                val progress = launch {
                    val holder = ProgressHolder()
                    while (isActive) {
                        delay(250)
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                    }
                }
                try {
                    run(transformer, item, job.out)
                } finally {
                    progress.cancel()
                }
            }
        }
        if (!job.out.isFile || job.out.length() == 0L) throw ClipExportException("The saved clip is empty.")
        onProgress(1f)
    }

    private suspend fun run(transformer: Transformer, item: EditedMediaItem, out: File): ExportResult =
        suspendCancellableCoroutine { continuation ->
            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (continuation.isActive) continuation.resume(exportResult)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            ClipExportException("Chaya couldn't make the clip (${exportException.errorCodeName}).", exportException),
                        )
                    }
                }
            })
            continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
            transformer.start(item, out.absolutePath)
        }

    /** Writes the clip's subtitles as an SRT timed from 0:00. */
    fun writeSrt(lines: List<SubtitleLine>, start: Long, end: Long, out: File) {
        out.parentFile?.mkdirs()
        out.writeText(Subtitles.toSrt(ClipExport.inside(lines, start, end)))
    }
}

/**
 * The clip's subtitles drawn on its frames, as the player draws them: white on a dark band near the foot, a line and
 * its translation as one block. A bitmap the frame's size is made once for each line, and reused while it shows.
 * [lines] are timed from the clip's start, as its frames are.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class SubtitleBurn(private val lines: List<SubtitleLine>) : BitmapOverlay() {
    private var frame = Size(1280, 720)
    private var drawn: Pair<Int, Bitmap>? = null
    private val empty by lazy { createBitmap(frame.width, frame.height) }

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        frame = videoSize
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val ms = presentationTimeUs / 1000
        val index = lines.indexOfLast { ms >= it.start && ms < it.end }
        if (index < 0) return empty
        drawn?.takeIf { it.first == index }?.let { return it.second }
        val bitmap = draw(lines[index].text)
        drawn?.second?.recycle()
        drawn = index to bitmap
        return bitmap
    }

    private fun draw(text: String): Bitmap {
        val bitmap = createBitmap(frame.width, frame.height)
        val canvas = Canvas(bitmap)
        val size = minOf(frame.width, frame.height) * 0.052f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val widest = (frame.width * 0.86f).toInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, widest).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        val pad = size * 0.35f
        val bottom = frame.height - frame.height * 0.06f
        val top = bottom - layout.height
        var left = Float.MAX_VALUE
        var right = 0f
        for (line in 0 until layout.lineCount) {
            left = minOf(left, layout.getLineLeft(line))
            right = maxOf(right, layout.getLineRight(line))
        }
        val x = (frame.width - widest) / 2f
        val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(158, 0, 0, 0) }
        canvas.drawRoundRect(RectF(x + left - pad, top - pad / 2, x + right + pad, bottom + pad / 2), pad, pad, band)
        canvas.withTranslation(x, top) { layout.draw(this) }
        return bitmap
    }
}
