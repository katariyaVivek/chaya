package com.chaya.app.ui

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaya.app.TestMedia
import com.chaya.app.subtitles.SubtitleLine
import com.chaya.app.ui.player.ClipExporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** A clip saved by Media3 Transformer on the phone's own encoder, its subtitles drawn on, and its length read back. */
@RunWith(AndroidJUnit4::class)
class ClipExporterTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aShortClipIsSavedWithItsSubtitlesDrawnOnAndLastsAsLongAsTheClip() = runBlocking {
        val video = File(context.cacheDir, "clip-source.mp4").also { TestMedia.pictureOnly(it, frames = 45) }
        val out = File(context.cacheDir, "clips/clip-out.mp4")
        val lines = listOf(SubtitleLine(0, 1_200, "Hello there."), SubtitleLine(1_200, 3_000, "A second line,\nand its translation."))
        val progress = mutableListOf<Float>()
        ClipExporter.export(context, ClipExporter.Job(Uri.fromFile(video), 500, 2_000, lines, sizeMb = null, out = out)) { progress += it }
        val saved = TestMedia.inspect(out)
        assertTrue("a picture: ${saved.mimeTypes}", saved.trackOf("video/") >= 0)
        val length = saved.durationMs ?: 0
        assertTrue("1.5 s asked, $length ms saved", abs(length - 1_500) <= 250)
        assertTrue(progress.last() == 1f)
    }

    @Test
    fun aSizeToFitMakesTheFileNoBiggerThanAsked() = runBlocking {
        val video = File(context.cacheDir, "clip-source-fit.mp4").also { TestMedia.pictureOnly(it, frames = 45) }
        val out = File(context.cacheDir, "clips/clip-fit.mp4")
        ClipExporter.export(context, ClipExporter.Job(Uri.fromFile(video), 0, 2_500, emptyList(), sizeMb = 10, out = out)) {}
        assertTrue("${out.length()} bytes", out.length() in 1..10_000_000)
    }
}
