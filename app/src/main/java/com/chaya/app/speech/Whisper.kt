package com.chaya.app.speech

import java.io.File

/** whisper.cpp, built into the app (`src/main/cpp`), listening on the phone's processor. One at a time. */
object Whisper {
    /** Hears how far whisper.cpp has listened, in percent. */
    fun interface Listener {
        fun heard(percent: Int)
    }

    private val loaded: Boolean by lazy { runCatching { System.loadLibrary("chaya_whisper") }.isSuccess }

    /** Whether whisper.cpp is in this build for this phone. */
    val available: Boolean get() = loaded

    /** About a thread a core, leaving one for the rest of the phone, between 2 and 8. */
    fun threads(): Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 8)

    /**
     * What was said in [samples] (16 kHz mono), as the JSON whisper-cli writes with -ojf, read by [Transcript.parse];
     * null when the model would not load, or listening failed or was stopped.
     */
    @Synchronized
    fun transcribe(model: File, dtw: String, samples: FloatArray, language: String?, listener: Listener?): String? {
        if (!loaded) return null
        val json = transcribeNative(model.absolutePath, dtw, samples, language, threads(), listener) ?: return null
        return String(json, Charsets.UTF_8)
    }

    /** Ends the listening under way; it answers null. */
    fun stop() {
        if (loaded) stopNative()
    }

    @JvmStatic
    private external fun transcribeNative(
        model: String,
        dtw: String,
        samples: FloatArray,
        language: String?,
        threads: Int,
        listener: Listener?,
    ): ByteArray?

    @JvmStatic
    private external fun stopNative()
}
