package com.chaya.app.speech

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaya.app.ui.player.ClipMedia
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * whisper.cpp on a real Android, as Windows tests it: the Tiny model fetched from Hugging Face and checked against its
 * pinned sum, the JFK sample decoded by the phone to 16 kHz, listened to, and cut into lines. How long it took goes to
 * logcat under ChayaDevice.
 */
@RunWith(AndroidJUnit4::class)
class WhisperOnDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    @Test
    fun theJfkSampleIsHeardAndCutIntoLines() = runBlocking {
        assertTrue("whisper.cpp is in this build", Whisper.available)
        val models = SpeechModels(File(context.filesDir, "speech"))
        val model = models.fetch(SpeechModel.TINY) { _, _ -> }
        assertEquals(SpeechModel.TINY.size, model.length())

        val sample = File(context.cacheDir, "jfk.m4a")
        assets.open("jfk.m4a").use { input -> sample.outputStream().use { input.copyTo(it) } }
        val pieces = mutableListOf<FloatArray>()
        var sound: Sound16k? = null
        val decoded = ClipMedia.decodePcm(context, Uri.fromFile(sample), onFormat = { rate, channels ->
            sound = Sound16k(rate, channels) { samples, _ -> pieces += samples }
        }) { sound?.add(it) }
        sound?.finish()
        assertTrue(decoded)
        val samples = pieces.single()
        assertTrue("about 11 s at 16 kHz: ${samples.size}", samples.size in 160_000..190_000)

        val started = System.nanoTime()
        var heard = 0
        val json = Whisper.transcribe(model, SpeechModel.TINY.dtw, samples, null) { heard = it }
        Log.i("ChayaDevice", "whisper.cpp Tiny heard the JFK sample in ${(System.nanoTime() - started) / 1_000_000} ms")
        assertNotNull(json)
        val transcript = Transcript.parse(json!!)
        assertNotNull(transcript)
        assertEquals("en", transcript!!.language)
        val said = transcript.segments.joinToString(" ") { it.text }.lowercase()
        assertTrue(said, "ask not what your country can do for you" in said)
        val lines = transcript.lines()
        assertTrue("${lines.map { it.text }}", lines.size >= 2 && lines.all { it.end > it.start })
        assertTrue("progress was reported", heard > 0)
    }
}
