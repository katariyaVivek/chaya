package com.chaya.app.ui

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaya.app.TestMedia
import com.chaya.app.subtitles.PeakMeter
import com.chaya.app.ui.player.ClipMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** The clip timeline's sound and frames, read by the phone's own decoders from real H.264 and AAC. */
@RunWith(AndroidJUnit4::class)
class ClipMediaTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theSoundsLoudnessIsMeasuredFiftyTimesASecond() {
        val file = File(context.cacheDir, "clip-sound.m4a").also { TestMedia.soundOnly(it) }
        val peaks = ClipMedia.decodePeaks(context, Uri.fromFile(file))
        assertNotNull(peaks)
        val expected = TestMedia.AUDIO_SECONDS * PeakMeter.PER_SECOND
        assertTrue("${peaks!!.size} peaks for ${TestMedia.AUDIO_SECONDS} s", abs(peaks.size - expected) <= PeakMeter.PER_SECOND / 5)
        assertTrue("a tone is not silence", peaks.count { (it.toInt() and 0xff) > 20 } > peaks.size / 2)
        assertNotNull(ClipMedia.soundPeaks(context, 999_001, Uri.fromFile(file)))
        assertTrue("read once, then kept", File(File(context.cacheDir, "peaks"), "999001.peaks").isFile)
    }

    @Test
    fun aFrameIsReadAtTheHeightAsked() {
        val file = File(context.cacheDir, "clip-picture.mp4").also { TestMedia.pictureOnly(it) }
        val frame = ClipMedia.frameAt(context, Uri.fromFile(file), 1_000, 44)
        assertNotNull(frame)
        assertEquals(44, frame!!.height)
        assertNull("a video without sound has no peaks", ClipMedia.decodePeaks(context, Uri.fromFile(file)))
    }
}
