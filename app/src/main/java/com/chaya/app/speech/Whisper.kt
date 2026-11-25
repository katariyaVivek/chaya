package com.chaya.app.speech

import java.io.File

/** whisper.cpp, built into the app (`src/main/cpp`), listening on the phone's processor. One at a time. */
object Whisper {
    /** Hears how far whisper.cpp has listened, in percent. */
    fun interface Listener {
        fun heard(percent: Int)
    }

    /** The build for this phone's cores: the fast one where they do half-precision and dot products, else the baseline. */
    private val loaded: Boolean by lazy {
        val cpu = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
        val fast = CpuChoice.fastBuild(cpu, android.os.Build.SUPPORTED_ABIS.firstOrNull())
        (fast && runCatching { System.loadLibrary("chaya_whisper_fp16") }.isSuccess) ||
            runCatching { System.loadLibrary("chaya_whisper") }.isSuccess
    }

    /** Whether whisper.cpp is in this build for this phone. */
    val available: Boolean get() = loaded

    /** Threads for this phone's cores ([CpuChoice.threads]): not one for every slow core, which held the rest up. */
    fun threads(): Int {
        val speeds = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { core ->
            runCatching { File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }
        return CpuChoice.threads(speeds, Runtime.getRuntime().availableProcessors())
    }

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
