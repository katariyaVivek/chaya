package com.chaya.app.platform

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Updating the engine on a real Android: wheels fetched from a PyPI served on the phone, compiled by the
 * phone's Python, checked, and imported in place of the app's copy; and a broken set falling back to it.
 */
@RunWith(AndroidJUnit4::class)
class EngineUpdateOnDeviceTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    private fun python(): Python {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        return Python.getInstance()
    }

    private fun status(): JSONObject = JSONObject(python().getModule("chaya_engine.selftest").callAttr("status").toString())

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "engine-update-test").apply { deleteRecursively() }
    }

    @After
    fun tearDown() {
        // Back to the app's copy for the other tests in this process.
        python().getModule("chaya_engine.paths").callAttr("drop")
        dir.deleteRecursively()
    }

    @Test
    fun aNewerSetFromALocalPyPiIsCompiledCheckedAndImportedOnTheNextStart() {
        // The app's own pins, fetched from the real PyPI and served here as "newer": the app is told it carries
        // older versions. Needs the network once; skip rather than fail without it.
        val pins = mapOf("yt-dlp" to "2026.8.19", "yt-dlp-ejs" to "0.8.0", "gallery-dl" to "1.32.16")
        val releases = HashMap<String, JSONObject>()
        val wheels = HashMap<String, ByteArray>()
        val client = OkHttpClient()
        try {
            for ((name, version) in pins) {
                val release = client.newCall(Request.Builder().url("https://pypi.org/pypi/$name/$version/json").build())
                    .execute().use { JSONObject(it.body!!.string()) }
                val urls = release.getJSONArray("urls")
                for (i in 0 until urls.length()) {
                    val file = urls.getJSONObject(i)
                    if (!file.getString("filename").endsWith(".whl")) continue
                    wheels[file.getString("filename")] = client.newCall(Request.Builder().url(file.getString("url")).build())
                        .execute().use { it.body!!.bytes() }
                }
                releases[name] = release
            }
        } catch (e: IOException) {
            assumeNoException("PyPI is not reachable from here", e)
        }

        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path!!
                    Regex("^/pypi/([^/]+)/(?:[^/]+/)?json$").find(path)?.let { found ->
                        val release = JSONObject(releases[found.groupValues[1]]!!.toString())
                        val urls = release.getJSONArray("urls")
                        for (i in 0 until urls.length()) {
                            val file = urls.getJSONObject(i)
                            file.put("url", server.url("/files/${file.getString("filename")}").toString())
                        }
                        return MockResponse().setBody(release.toString())
                    }
                    val wheel = wheels[path.removePrefix("/files/")] ?: return MockResponse().setResponseCode(404)
                    return MockResponse().setBody(Buffer().write(wheel))
                }
            }
            server.start()

            val older = mapOf("yt-dlp" to "2000.1.1", "yt-dlp-ejs" to "0.0.1", "gallery-dl" to "1.0.0")
            val engine = PlatformEngine(context)
            val updater = EngineUpdater(
                EngineSets(dir, older),
                compile = engine::compileWheel,
                installedVersion = engine::installedVersion,
                pypi = server.url("/"),
                wheelHosts = setOf(server.hostName),
            )

            val started = System.nanoTime()
            val outcome = runBlocking { updater.check() }
            Log.i(TAG, "fetching and compiling the engine took ${(System.nanoTime() - started) / 1_000_000} ms")
            assertEquals(EngineSets(dir, older).status.value.note, EngineUpdater.Outcome.DOWNLOADED, outcome)
        }

        // The next start: a fresh EngineSets reads what was fetched, checks it and puts it on sys.path.
        val sets = EngineSets(dir, mapOf("yt-dlp" to "2000.1.1", "yt-dlp-ejs" to "0.0.1", "gallery-dl" to "1.0.0"))
        val active = sets.activate(PlatformEngine.ChaquopyRuntime(python()))

        assertEquals(sets.status.value.note, EngineSets.Status.TRUSTED, active?.status)
        val status = status()
        val files = status.getJSONArray("files")
        assertEquals(3, files.length())
        assertTrue(files.getString(0).startsWith(dir.absolutePath))
        val imported = python().getModule("yt_dlp").get("__file__").toString()
        assertTrue("yt_dlp came from $imported", imported.startsWith(dir.absolutePath))
        assertTrue(status.getBoolean("provider_registered"))
        Log.i(TAG, "engine status with the updated copy: $status")
    }

    @Test
    fun aSetThatFailsItsCheckFallsBackToTheCopyInTheApp() {
        val bundled = JSONObject(status().toString())
        val broken = File(dir.apply { mkdirs() }, "yt-dlp-2099.1.1.zip")
        ZipOutputStream(broken.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("yt_dlp/__init__.py"))
            zip.write("raise ImportError('broken on purpose')\n".toByteArray())
        }
        val app = mapOf(
            "yt-dlp" to bundled.getString("yt_dlp"),
            "yt-dlp-ejs" to bundled.getString("yt_dlp_ejs"),
            "gallery-dl" to bundled.getString("gallery_dl"),
        )
        val sets = EngineSets(dir, app)
        val set = EngineSets.EngineSet(app + ("yt-dlp" to "2099.1.1"), mapOf("yt-dlp" to broken.name), 1, EngineSets.Status.NEW)
        sets.add(set)

        assertNull(sets.activate(PlatformEngine.ChaquopyRuntime(python())))

        assertTrue(sets.isSkipped(set.versions))
        assertTrue(sets.status.value.note!!, sets.status.value.note!!.contains("broken on purpose"))
        val after = status()
        assertEquals(bundled.getString("yt_dlp"), after.getString("yt_dlp"))
        assertEquals(0, after.getJSONArray("files").length())
        assertTrue(after.getBoolean("provider_registered"))
    }

    private companion object {
        const val TAG = "ChayaDevice"
    }
}
