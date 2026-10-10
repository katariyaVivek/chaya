package com.chaya.app.platform

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The daily check against a PyPI served locally: what is fetched, what is verified, what is refused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineUpdaterTest {

    private val server = MockWebServer()
    private lateinit var dir: File
    private var now = 1_000_000_000_000L
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val bundled = mapOf("yt-dlp" to "2026.8.19", "yt-dlp-ejs" to "0.8.0", "gallery-dl" to "1.32.16")
    private val installed = mutableMapOf("requests" to "2.32.5", "urllib3" to "2.5.0", "certifi" to "2025.8.3")
    private val compiled = mutableListOf<String>()

    /** What the local PyPI knows: package → its releases, the latest last. */
    private val releases = mutableMapOf<String, MutableList<Release>>()
    private val requests = mutableListOf<RecordedRequest>()
    private var pypiDown = false

    private data class Release(
        val name: String,
        val version: String,
        val requires: List<String> = emptyList(),
        val requiresPython: String = ">=3.9",
        val wheel: ByteArray,
        val sha256: String = sha256(wheel),
        val wheelUrl: String? = null,
    )

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("engine-updater").toFile()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (pypiDown) return MockResponse().setResponseCode(503)
                val path = request.path!!
                Regex("^/pypi/([^/]+)/json$").find(path)?.let { found ->
                    val latest = releases[found.groupValues[1]]?.last() ?: return MockResponse().setResponseCode(404)
                    val etag = "\"${latest.name}-${latest.version}\""
                    if (request.getHeader("If-None-Match") == etag) return MockResponse().setResponseCode(304)
                    return MockResponse().setHeader("ETag", etag).setBody(json(latest).toString())
                }
                Regex("^/pypi/([^/]+)/([^/]+)/json$").find(path)?.let { found ->
                    val release = releases[found.groupValues[1]]?.firstOrNull { it.version == found.groupValues[2] }
                        ?: return MockResponse().setResponseCode(404)
                    return MockResponse().setBody(json(release).toString())
                }
                Regex("^/files/(.+)$").find(path)?.let { found ->
                    val release = releases.values.flatten().firstOrNull { fileName(it) == found.groupValues[1] }
                        ?: return MockResponse().setResponseCode(404)
                    return MockResponse().setBody(Buffer().write(release.wheel))
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
        // What the app carries is on PyPI too.
        publish("yt-dlp", "2026.8.19", requires = listOf("yt-dlp-ejs==0.8.0; extra == \"default\""))
        publish("yt-dlp-ejs", "0.8.0")
        publish("gallery-dl", "1.32.16", requires = listOf("requests>=2.11.0"))
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun publish(
        name: String,
        version: String,
        requires: List<String> = emptyList(),
        requiresPython: String = ">=3.9",
        wheel: ByteArray = wheel(name, version),
        sha256: String = sha256(wheel),
        wheelUrl: String? = null,
    ) {
        releases.getOrPut(name) { mutableListOf() } += Release(name, version, requires, requiresPython, wheel, sha256, wheelUrl)
    }

    private fun fileName(release: Release) = "${release.name.replace('-', '_')}-${release.version}-py3-none-any.whl"

    private fun json(release: Release) = JSONObject()
        .put("info", JSONObject()
            .put("name", release.name)
            .put("version", release.version)
            .put("requires_python", release.requiresPython)
            .put("requires_dist", JSONArray(release.requires)))
        .put("urls", JSONArray().put(JSONObject()
            .put("filename", fileName(release))
            .put("packagetype", "bdist_wheel")
            .put("url", release.wheelUrl ?: server.url("/files/${fileName(release)}").toString())
            .put("size", release.wheel.size)
            .put("yanked", false)
            .put("digests", JSONObject().put("sha256", release.sha256))))

    /** A wheel as PyPI serves it: the package and its metadata. */
    private fun wheel(name: String, version: String, module: String = name.replace('-', '_')): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("$module/__init__.py"))
            zip.write("__version__ = '$version'\n".toByteArray())
            zip.putNextEntry(ZipEntry("$module-$version.dist-info/METADATA"))
            zip.write("Metadata-Version: 2.1\nName: $name\nVersion: $version\n\nAbout it.\n".toByteArray())
        }
        return bytes.toByteArray()
    }

    private val sets by lazy { EngineSets(dir, bundled) }

    private fun updater() = EngineUpdater(
        sets = sets,
        compile = { wheel, out ->
            compiled += wheel.name
            wheel.copyTo(out, overwrite = true)
        },
        installedVersion = { installed[it] },
        pypi = server.url("/"),
        wheelHosts = setOf(server.hostName),
        clock = { now },
    )

    private fun paths() = requests.map { it.path!! }

    private fun keptFiles() = dir.listFiles()!!.map { it.name }.filter { it != "state.json" }.sorted()

    @Test
    fun `a check is due once a day, and hours after one that failed`() = runBlocking {
        val updater = updater()
        assertTrue(updater.isUpdateDue())
        assertEquals(EngineUpdater.Outcome.UP_TO_DATE, updater.check())
        assertFalse(updater.isUpdateDue())
        now += day - hour
        assertEquals(EngineUpdater.Outcome.NOT_DUE, updater.updateIfDue())
        now += hour
        assertTrue(updater.isUpdateDue())

        pypiDown = true
        assertEquals(EngineUpdater.Outcome.FAILED, updater.updateIfDue())
        now += 5 * hour
        assertFalse(updater.isUpdateDue())
        now += hour
        assertTrue(updater.isUpdateDue())
    }

    @Test
    fun `with updates turned off nothing is due`() {
        sets.setUpdatesOn(false)
        assertFalse(updater().isUpdateDue())
    }

    @Test
    fun `when PyPI has nothing newer, nothing is fetched`() = runBlocking {
        assertEquals(EngineUpdater.Outcome.UP_TO_DATE, updater().check())

        assertEquals(listOf("/pypi/yt-dlp/json", "/pypi/gallery-dl/json"), paths())
        assertNull(sets.newest())
        assertEquals(emptyList<String>(), keptFiles())
    }

    @Test
    fun `a newer yt-dlp comes with the yt-dlp-ejs it names, verified and compiled, for the next start`() = runBlocking {
        publish("yt-dlp", "2026.9.1", requires = listOf("yt-dlp-ejs==0.8.1; extra == \"default\"", "yt-dlp-ejs==0.8.1; extra == \"pin\""))
        publish("yt-dlp-ejs", "0.8.1")
        // A newer ejs exists, but this yt-dlp was not built with it.
        publish("yt-dlp-ejs", "0.9.0")

        assertEquals(EngineUpdater.Outcome.DOWNLOADED, updater().check())

        val set = sets.newest()!!
        assertEquals(mapOf("yt-dlp" to "2026.9.1", "yt-dlp-ejs" to "0.8.1", "gallery-dl" to "1.32.16"), set.versions)
        assertEquals(EngineSets.Status.NEW, set.status)
        assertEquals(listOf("yt-dlp" to "yt-dlp-2026.9.1.zip", "yt-dlp-ejs" to "yt-dlp-ejs-0.8.1.zip"), set.files.toList())
        assertEquals(listOf("yt-dlp-2026.9.1.zip", "yt-dlp-ejs-0.8.1.zip"), keptFiles())
        assertEquals(listOf("yt-dlp-2026.9.1.whl.download", "yt-dlp-ejs-0.8.1.whl.download"), compiled)
        assertTrue("/pypi/yt-dlp-ejs/0.8.1/json" in paths())
        assertFalse("/pypi/yt-dlp-ejs/json" in paths())
        assertEquals("2026.9.1", sets.status.value.waiting!!["yt-dlp"])
        assertTrue(sets.status.value.note!!.contains("used from the next start"))
    }

    @Test
    fun `a wheel that does not match PyPI's SHA-256 is refused and nothing is kept`() = runBlocking {
        publish("gallery-dl", "1.33.0", requires = listOf("requests>=2.11.0"), sha256 = "0".repeat(64))

        assertEquals(EngineUpdater.Outcome.FAILED, updater().check())

        assertNull(sets.newest())
        assertEquals(emptyList<String>(), keptFiles())
        assertEquals(emptyList<String>(), compiled)
        assertTrue(sets.status.value.note!!.contains("SHA-256"))
    }

    @Test
    fun `a corrupt wheel is refused even when its hash matches`() = runBlocking {
        publish("gallery-dl", "1.33.0", wheel = "this is not a zip".toByteArray())

        assertEquals(EngineUpdater.Outcome.FAILED, updater().check())

        assertNull(sets.newest())
        assertEquals(emptyList<String>(), keptFiles())
    }

    @Test
    fun `a wheel holding another package or another version is refused`() = runBlocking {
        publish("gallery-dl", "1.33.0", wheel = wheel("gallery-dl", "1.33.0", module = "something_else"))
        assertEquals(EngineUpdater.Outcome.FAILED, updater().check())

        publish("gallery-dl", "1.33.1", wheel = wheel("gallery-dl", "1.32.0"))
        assertEquals(EngineUpdater.Outcome.FAILED, updater().check())
        assertNull(sets.newest())
    }

    @Test
    fun `a release needing a newer library than the app has is refused, and not fetched again`() = runBlocking {
        publish("gallery-dl", "1.33.0", requires = listOf("requests>=2.40.0", "yt-dlp; extra == \"video\""))

        assertEquals(EngineUpdater.Outcome.REFUSED, updater().check())
        assertTrue(sets.status.value.note!!.contains("requests >=2.40.0; the app has 2.32.5"))
        now += day
        assertEquals(EngineUpdater.Outcome.REFUSED, updater().check())

        assertFalse(paths().any { it.startsWith("/files/") })
        assertNull(sets.newest())
    }

    @Test
    fun `an optional library yt-dlp can use must be new enough if the app has it, and may be missing`() = runBlocking {
        publish(
            "yt-dlp", "2026.9.1",
            requires = listOf(
                "yt-dlp-ejs==0.8.0; extra == \"default\"",
                "mutagen; extra == \"default\"",
                "urllib3<3,>=2.6.0; extra == \"default\"",
            ),
        )

        assertEquals(EngineUpdater.Outcome.REFUSED, updater().check())
        assertTrue(sets.status.value.note!!.contains("urllib3"))

        installed["urllib3"] = "2.6.1"
        publish("yt-dlp", "2026.9.2", requires = listOf("yt-dlp-ejs==0.8.0; extra == \"default\"", "mutagen; extra == \"default\""))
        now += day
        assertEquals(EngineUpdater.Outcome.DOWNLOADED, updater().check())
        // The same ejs as the app's: only yt-dlp is fetched.
        assertEquals(mapOf("yt-dlp" to "yt-dlp-2026.9.2.zip"), sets.newest()!!.files)
    }

    @Test
    fun `a release for another Python, or with no ejs named, is refused`() = runBlocking {
        publish("gallery-dl", "1.33.0", requiresPython = ">=3.14")
        assertEquals(EngineUpdater.Outcome.REFUSED, updater().check())

        publish("gallery-dl", "1.33.1")
        publish("yt-dlp", "2026.9.1", requires = emptyList())
        now += day
        assertEquals(EngineUpdater.Outcome.REFUSED, updater().check())
        assertTrue(sets.status.value.note!!.contains("does not name its yt-dlp-ejs"))
    }

    @Test
    fun `a wheel offered from another host is refused`() = runBlocking {
        publish("gallery-dl", "1.33.0", wheelUrl = "https://elsewhere.example/gallery_dl-1.33.0-py3-none-any.whl")

        assertEquals(EngineUpdater.Outcome.REFUSED, updater().check())
        assertNull(sets.newest())
    }

    @Test
    fun `a failed check keeps the set already fetched`() = runBlocking {
        publish("gallery-dl", "1.33.0")
        assertEquals(EngineUpdater.Outcome.DOWNLOADED, updater().check())
        val before = sets.newest()!!

        pypiDown = true
        now += day
        assertEquals(EngineUpdater.Outcome.FAILED, updater().check())

        assertEquals(before, sets.newest())
        assertEquals(listOf("gallery-dl-1.33.0.zip"), keptFiles())
    }

    @Test
    fun `an unchanged answer is asked about with If-None-Match and nothing more is fetched`() = runBlocking {
        publish("gallery-dl", "1.33.0")
        assertEquals(EngineUpdater.Outcome.DOWNLOADED, updater().check())
        requests.clear()

        now += day
        assertEquals(EngineUpdater.Outcome.UP_TO_DATE, updater().check())

        assertEquals(listOf("/pypi/yt-dlp/json", "/pypi/gallery-dl/json"), paths())
        assertEquals("\"gallery-dl-1.33.0\"", requests.last().getHeader("If-None-Match"))
    }

    @Test
    fun `a package already kept is not fetched again for a newer set`() = runBlocking {
        publish("yt-dlp", "2026.9.1", requires = listOf("yt-dlp-ejs==0.8.0; extra == \"default\""))
        assertEquals(EngineUpdater.Outcome.DOWNLOADED, updater().check())
        requests.clear()
        compiled.clear()

        publish("gallery-dl", "1.33.0")
        now += day
        assertEquals(EngineUpdater.Outcome.DOWNLOADED, updater().check())

        assertEquals(mapOf("yt-dlp" to "yt-dlp-2026.9.1.zip", "gallery-dl" to "gallery-dl-1.33.0.zip"), sets.newest()!!.files)
        assertEquals(listOf("gallery-dl-1.33.0.whl.download"), compiled)
        assertFalse(paths().any { it.contains("yt_dlp-2026.9.1") })
    }

    @Test
    fun `a pre-release named as the latest is not taken`() = runBlocking {
        publish("yt-dlp", "2026.9.1.dev0", requires = listOf("yt-dlp-ejs==0.8.0; extra == \"default\""))

        assertEquals(EngineUpdater.Outcome.FAILED, updater().check())
        assertNull(sets.newest())
    }

    private companion object {
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
