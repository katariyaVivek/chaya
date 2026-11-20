package com.chaya.app.speech

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Evens out a video's sound before it is listened to, so a soft voice is heard as well as a loud one: whisper.cpp,
 * and any model, misses words said quietly under music or far from the microphone. Each 50 ms is brought towards one
 * loudness, lifted by at most [MOST_GAIN] (+20 dB) and lowered by at most half, changing smoothly (quick to come down
 * for a sudden loud sound, slow to come back up) so it never pumps. What is near silence is left as it is, so hiss
 * is never lifted into words that weren't said. Nothing clips: peaks are rounded off below full scale. Plain Kotlin.
 */
object Leveler {
    /** 50 ms at 16 kHz. */
    const val WINDOW = 800

    /** The loudness aimed for: about -20 dB of full scale, where speech in a well-mixed video sits. */
    const val TARGET = 0.1f

    /** Lifted at most this many times (+20 dB), lowered to at most half. */
    const val MOST_GAIN = 10f
    const val LEAST_GAIN = 0.5f

    /** Quieter than this (-54 dB) is silence or hiss, and keeps its level. */
    const val GATE = 0.002f

    /** How far the gain moves towards its aim each window: down at once, up over about half a second. */
    private const val DOWN = 0.7f
    private const val UP = 0.1f

    /** [samples] (16 kHz mono, -1 to 1) evened out; a new array. */
    fun even(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        val windows = (samples.size + WINDOW - 1) / WINDOW
        val gains = FloatArray(windows)
        var gain = 1f
        for (w in 0 until windows) {
            val from = w * WINDOW
            val to = minOf(samples.size, from + WINDOW)
            var sum = 0.0
            for (i in from until to) sum += samples[i] * samples[i]
            val rms = sqrt(sum / (to - from)).toFloat()
            val aim = if (rms < GATE) 1f else (TARGET / rms).coerceIn(LEAST_GAIN, MOST_GAIN)
            gain += (aim - gain) * if (aim < gain) DOWN else UP
            gains[w] = gain
        }
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            // The gain between the middles of this window and the next, so it never steps
            val at = (i - WINDOW / 2f) / WINDOW
            val w = at.toInt().coerceIn(0, windows - 1)
            val next = minOf(w + 1, windows - 1)
            val t = (at - w).coerceIn(0f, 1f)
            out[i] = soften(samples[i] * (gains[w] + (gains[next] - gains[w]) * t))
        }
        return out
    }

    /** Peaks rounded off from 0.8 up, so a lifted word never clips. */
    private fun soften(x: Float): Float {
        val knee = 0.8f
        val size = abs(x)
        if (size <= knee) return x
        val over = (size - knee) / (1f - knee)
        return (knee + (1f - knee) * 0.99f * tanh(over)) * if (x < 0) -1f else 1f
    }
}
