package com.chaya.app.platform

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.URL

/**
 * The engine on a real Android: Python starting, yt-dlp importing, the QuickJS engine running yt-dlp's
 * solver scripts, and a lookup going through yt-dlp over a real socket. Numbers worth knowing (how long the
 * solver takes, how much memory it touches) go to logcat under the tag ChayaDevice.
 */
@RunWith(AndroidJUnit4::class)
class EngineOnDeviceTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun python(): Python {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        return Python.getInstance()
    }

    private fun selftest(function: String): JSONObject =
        JSONObject(python().getModule("chaya_engine.selftest").callAttr(function).toString())

    private fun log(message: String) = Log.i(TAG, message)

    /** Peak resident memory of this process so far, in kilobytes; the engine's share shows as the change. */
    private fun peakMemoryKb(): Long =
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmHWM:") }
            ?.filter { it.isDigit() }
            ?.toLongOrNull() ?: -1

    // ---- Python and yt-dlp ---- //

    @Test
    fun pythonStartsAndYtDlpAndItsSolverProviderAreInPlace() {
        val started = System.nanoTime()
        val status = selftest("status")
        log("engine status: $status (first Python call took ${(System.nanoTime() - started) / 1_000_000} ms)")

        assertTrue(status.getString("yt_dlp").startsWith("20"))
        assertTrue(status.getString("yt_dlp_ejs").isNotEmpty())
        assertTrue("the solver provider is not registered", status.getBoolean("provider_registered"))
    }

    @Test
    fun aDirectVideoLinkIsFoundThroughYtDlpOverARealSocket() {
        runBlocking {
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        MockResponse()
                            .setHeader("Content-Type", "video/mp4")
                            .setHeader("Content-Length", "2048")
                            .setBody(Buffer().write(ByteArray(2048)))
                }
                server.start()
                val url = server.url("/clips/zoo.mp4").toString()

                val media = PlatformEngine(context).extract(url)

                val format = media.formats.single()
                assertEquals(url, format.url)
                assertEquals("mp4", format.ext)
                assertTrue("a direct link is a complete file", format.isCompleteFile)
            }
        }
    }

    @Test
    fun aLinkYtDlpCannotUseComesBackAsAnExplainedFailure() {
        runBlocking {
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        MockResponse().setResponseCode(404).setBody("gone")
                }
                server.start()

                val failure = runCatching { PlatformEngine(context).extract(server.url("/missing").toString()) }
                    .exceptionOrNull()

                assertTrue("expected a PlatformException, got $failure", failure is PlatformException)
                log("a dead link comes back as: ${(failure as PlatformException).kind} / ${failure.detail}")
            }
        }
    }

    // ---- the embedded JavaScript engine ---- //

    @Test
    fun theEmbeddedJavaScriptEngineRunsScriptsAndCapturesConsoleLog() {
        assertEquals("hello\na b\n", JsSolver.run("console.log('hello'); console.log('a', 'b');"))
    }

    @Test
    fun modernJavaScriptWorksInTheEmbeddedEngine() {
        val printed = JsSolver.run(
            """
            class Box { #v = 41; get() { return this.#v + 1; } }
            const o = { a: { b: [1, 2, 3] } };
            console.log(JSON.stringify({
              n: new Box().get(),
              last: o?.a?.b.at(-1),
              big: (2n ** 64n).toString(),
              joined: [...'ab'].join('-'),
            }));
            """.trimIndent(),
        )

        assertEquals("""{"n":42,"last":3,"big":"18446744073709551616","joined":"a-b"}""" + "\n", printed)
    }

    @Test
    fun aScriptThatThrowsSurfacesAsAnException() {
        assertThrows(Exception::class.java) { JsSolver.run("throw new Error('boom')") }
    }

    @Test
    fun aScriptCanBeRunAgainAndAgain() {
        repeat(5) { assertEquals("$it\n", JsSolver.run("console.log('$it')")) }
    }

    // ---- yt-dlp's solver, in that engine ---- //

    @Test
    fun ytDlpsRealSolverScriptsRunInTheEmbeddedEngine() {
        val result = selftest("solver_check")
        log("solver bundle check: $result")

        assertTrue("answer: $result", result.getString("type") in setOf("result", "error"))
        assertTrue(result.getLong("millis") < 60_000)
    }

    @Test
    fun theSolverPreprocessesTheCurrentYouTubePlayerInReasonableTime() {
        // Needs the network; skip rather than fail when there is none.
        try {
            URL("https://www.youtube.com/iframe_api").openConnection().apply {
                connectTimeout = 15_000
                readTimeout = 15_000
            }.getInputStream().use { it.readBytes() }
        } catch (e: IOException) {
            assumeNoException("YouTube is not reachable from here", e)
        }

        val memoryBefore = peakMemoryKb()
        val result = selftest("player_benchmark")
        log("player benchmark: $result; peak memory $memoryBefore kB -> ${peakMemoryKb()} kB")

        assertTrue("answer: $result", result.getString("type") in setOf("result", "error"))
        // Generous: tells a hung engine from a slow one. The number itself is what we want to see.
        assertTrue("took ${result.getLong("millis")} ms", result.getLong("millis") < 180_000)
    }

    private companion object {
        const val TAG = "ChayaDevice"
    }
}
