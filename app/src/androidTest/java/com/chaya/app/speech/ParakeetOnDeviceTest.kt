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
 * Parakeet through sherpa-onnx on a real Android: its four files fetched from Hugging Face and checked against their
 * pinned sums, the JFK sample decoded by the phone, evened out, heard, and cut into lines. How long it took goes to
 * logcat under ChayaDevice.
 */
@RunWith(AndroidJUnit4::class)
class ParakeetOnDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    @Test
    fun theJfkSampleIsHeardWithPunctuationAndCutIntoLines() = runBlocking {
        assertTrue("sherpa-onnx is in this build", Parakeet.available)
        val models = SpeechModels(File(context.filesDir, "speech"))
        val folder = models.fetch(SpeechModel.PARAKEET) { _, _ -> }
        assertTrue(models.isFetched(SpeechModel.PARAKEET))

        val sample = File(context.cacheDir, "jfk.m4a")
        assets.open("jfk.m4a").use { input -> sample.outputStream().use { input.copyTo(it) } }
        val pieces = mutableListOf<FloatArray>()
        var sound: Sound16k? = null
        ClipMedia.decodePcm(context, Uri.fromFile(sample), onFormat = { rate, channels ->
            sound = Sound16k(rate, channels) { samples, _ -> pieces += samples }
        }) { sound?.add(it) }
        sound?.finish()

        val started = System.nanoTime()
        var heard = 0
        val transcript = Parakeet.transcribe(folder, Leveler.even(pieces.single()), Whisper.threads(), heard = { heard = it })
        Log.i("ChayaDevice", "Parakeet heard the JFK sample in ${(System.nanoTime() - started) / 1_000_000} ms")
        Parakeet.release()
        assertNotNull(transcript)
        assertEquals("en", transcript!!.language)
        val said = transcript.segments.joinToString(" ") { it.text }
        assertTrue(said, "ask not what your country can do for you" in said.lowercase())
        assertTrue("with its punctuation: $said", said.contains(','))
        val lines = transcript.lines()
        assertTrue("${lines.map { it.text }}", lines.size >= 2 && lines.zipWithNext().all { (a, b) -> a.end <= b.start })
        assertEquals(100, heard)
    }
}
