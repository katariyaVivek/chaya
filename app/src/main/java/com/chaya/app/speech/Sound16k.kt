package com.chaya.app.speech

/**
 * A video's sound as whisper.cpp hears it: one channel at 16 kHz, as floats from -1 to 1. Fed 16-bit samples at the
 * rate and with the channels they were decoded at; each frame's channels are averaged, and the rate is changed by
 * straight lines between neighbouring samples, which is plenty for speech. Gives the sound in [chunkSamples] pieces,
 * so a long video is never all in memory at once. Plain Kotlin.
 */
class Sound16k(
    private val inRate: Int,
    private val channels: Int,
    private val chunkSamples: Int = CHUNK_SECONDS * RATE,
    private val onChunk: (FloatArray, Long) -> Unit,
) {
    private val step = inRate.toDouble() / RATE
    private var chunk = FloatArray(chunkSamples)
    private var filled = 0
    private var sent = 0L
    private var frameSum = 0f
    private var channel = 0
    /** The previous mono sample and its index, and where the next output sample falls, in input samples. */
    private var previous = 0f
    private var index = -1L
    private var next = 0.0

    fun add(sample: Short) {
        frameSum += sample / 32_768f
        if (++channel < channels) return
        val mono = frameSum / channels
        frameSum = 0f
        channel = 0
        index++
        while (next <= index) {
            val t = (next - (index - 1)).toFloat()
            emit(if (index == 0L) mono else previous + (mono - previous) * t)
            next += step
        }
        previous = mono
    }

    private fun emit(value: Float) {
        chunk[filled++] = value
        if (filled == chunkSamples) flushChunk()
    }

    private fun flushChunk() {
        if (filled == 0) return
        onChunk(if (filled == chunk.size) chunk else chunk.copyOf(filled), sent * 1000 / RATE)
        sent += filled
        chunk = FloatArray(chunkSamples)
        filled = 0
    }

    /** The sound has ended: the last piece. */
    fun finish() = flushChunk()

    companion object {
        const val RATE = 16_000

        /** Five minutes a piece: about 19 MB of floats, and whisper.cpp's own windows fit in it 10 times over. */
        const val CHUNK_SECONDS = 300
    }
}
