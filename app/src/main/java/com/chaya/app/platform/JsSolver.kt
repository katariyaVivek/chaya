package com.chaya.app.platform

import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Runs the small JavaScript program yt-dlp builds to unscramble YouTube's playback links.
 *
 * yt-dlp normally hands that program to Deno, Node or similar, none of which exist on a phone, so the
 * app runs it in the QuickJS engine it carries instead. The Python side (`chaya_engine/jsc_provider.py`)
 * calls [run] by name, which is why this class is kept by the shrinker rule in proguard-rules.pro.
 */
object JsSolver {
    /** The program normally finishes in seconds; this only stops a runaway one from hanging an extraction. */
    private const val TIMEOUT_MILLIS = 60_000L

    /**
     * Runs [script] and returns everything it printed with `console.log`, which is where yt-dlp reads
     * the answer from. Blocks the calling thread, so call it from a background thread (extraction does).
     * Throws if the script fails; the Python side turns that into a normal yt-dlp error.
     */
    @JvmStatic
    fun run(script: String): String = runBlocking {
        val printed = Printed()
        quickJs(Dispatchers.Default) {
            evaluationTimeoutMillis = TIMEOUT_MILLIS
            define("console") {
                function("log") { args -> printed.addLine(args) }
                // Present so a script that reports a problem there cannot fail on a missing function.
                function("info") { _ -> }
                function("warn") { _ -> }
                function("error") { _ -> }
                function("debug") { _ -> }
            }
            evaluate<Any?>(script)
        }
        printed.text.toString()
    }

    /** What the script has printed so far, one line per `console.log` call. */
    private class Printed {
        val text = StringBuilder()

        fun addLine(args: Array<Any?>) {
            text.append(args.joinToString(" ")).append('\n')
        }
    }
}
