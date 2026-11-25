package com.chaya.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** Soft voices lifted, loud ones left, silence never turned into hiss, nothing clipped. */
class LevelerTest {
    private fun tone(seconds: Double, level: Float) = FloatArray((seconds * 16_000).toInt()) { i -> level * sin(2 * PI * 220 * i / 16_000).toFloat() }

    private fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
        var sum = 0.0
        for (i in from until to) sum += samples[i] * samples[i]
        return sqrt(sum / (to - from)).toFloat()
    }

    @Test
    fun `a soft voice is lifted towards the loudness speech is heard at`() {
        // -40 dB: a voice far from the microphone
        val soft = tone(3.0, 0.014f)
        val even = Leveler.even(soft)
        val after = rms(even, 16_000, even.size) // once the gain has settled
        assertTrue("lifted to about -20 dB, was ${rms(soft)}, now $after", after in 0.06f..0.11f)
    }

    @Test
    fun `a loud voice is not made louder, and a very loud one is brought down`() {
        val normal = tone(2.0, 0.14f) // already about -20 dB
        assertEquals(rms(normal, 8_000, normal.size), rms(Leveler.even(normal), 8_000, normal.size), 0.02f)
        val loud = tone(2.0, 0.6f)
        assertTrue(rms(Leveler.even(loud), 8_000, loud.size) < rms(loud, 8_000, loud.size))
    }

    @Test
    fun `silence and hiss keep their level, so nothing is heard that wasn't said`() {
        val hiss = FloatArray(32_000) { i -> 0.001f * sin(i * 1.7f) }
        val even = Leveler.even(hiss)
        assertEquals(rms(hiss), rms(even), 0.0002f)
    }

    @Test
    fun `nothing clips, and a sudden loud sound after a soft one is caught`() {
        val soft = tone(2.0, 0.01f)
        val bang = tone(0.5, 0.9f)
        val even = Leveler.even(soft + bang)
        assertTrue("never past full scale", even.all { abs(it) < 1f })
        assertEquals("the length is kept", soft.size + bang.size, even.size)
        assertTrue(Leveler.even(FloatArray(0)).isEmpty())
    }
}
