package com.chaya.app.platform

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
import java.io.File
import java.nio.file.Files

/** Which copy of the engine is used at a start, and when a copy from PyPI stops being trusted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineSetsTest {

    private lateinit var dir: File
    private val bundled = mapOf("yt-dlp" to "2026.8.19", "yt-dlp-ejs" to "0.8.0", "gallery-dl" to "1.32.16")

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("engine-sets").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun sets() = EngineSets(dir, bundled)

    /**
     * Python as EngineSets sees it: which files are on sys.path, and a self-test that reports the versions in the
     * names of those files (`yt-dlp-2026.9.1.zip`), or the app's own where none is used.
     */
    private class FakePython(val bundled: Map<String, String>) : EngineRuntime {
        val calls = mutableListOf<String>()
        var inUse: List<File> = emptyList()
        var broken: (List<File>) -> Boolean = { false }
        var duringCheck: () -> Unit = {}

        override fun use(files: List<File>) {
            calls += "use " + files.joinToString(",") { it.name }
            inUse = files
        }

        override fun drop() {
            calls += "drop"
            inUse = emptyList()
        }

        override fun status(): String {
            calls += "status"
            duringCheck()
            if (broken(inUse)) throw RuntimeException("ImportError: broken on purpose\nmore lines")
            val versions = bundled.toMutableMap()
            inUse.forEach { versions[it.name.substringBeforeLast('-')] = it.name.substringAfterLast('-').removeSuffix(".zip") }
            return JSONObject().apply {
                ENGINE_PACKAGES.forEach { put(it.module, versions[it.name]) }
                put("provider_registered", true)
                put("post_links_known", true)
            }.toString()
        }
    }

    /** A set fetched from PyPI: its compiled packages written into the engine folder. */
    private fun fetched(sets: EngineSets, addedAt: Long, vararg versions: Pair<String, String>): EngineSets.EngineSet {
        val files = versions.associate { (name, version) -> name to "$name-$version.zip" }
        files.values.forEach { File(dir, it).writeText("compiled") }
        val set = EngineSets.EngineSet(bundled + versions, files, addedAt, EngineSets.Status.NEW)
        sets.add(set)
        return set
    }

    @Test
    fun `with nothing fetched, the copy in the app is used`() {
        val python = FakePython(bundled)

        assertNull(sets().activate(python))
        assertEquals(emptyList<String>(), python.calls)
    }

    @Test
    fun `a new set is checked at the first start and trusted once its versions are reported`() {
        val sets = sets()
        val set = fetched(sets, 1, "yt-dlp" to "2026.9.1", "yt-dlp-ejs" to "0.8.1")
        val python = FakePython(bundled)

        val active = sets.activate(python)!!

        assertEquals(set.id, active.id)
        assertEquals(EngineSets.Status.TRUSTED, active.status)
        assertEquals(listOf("use yt-dlp-2026.9.1.zip,yt-dlp-ejs-0.8.1.zip", "status"), python.calls)
        // The next start uses it without checking again.
        val next = FakePython(bundled)
        assertEquals(set.id, sets().activate(next)!!.id)
        assertEquals(listOf("use yt-dlp-2026.9.1.zip,yt-dlp-ejs-0.8.1.zip"), next.calls)
    }

    @Test
    fun `a new set that fails its check is dropped, skipped, and the last good set is used`() {
        fetched(sets(), 1, "yt-dlp" to "2026.9.1").also { sets().activate(FakePython(bundled)) }
        val sets = sets()
        val bad = fetched(sets, 2, "yt-dlp" to "2026.9.20")
        val python = FakePython(bundled).apply { broken = { files -> files.any { "2026.9.20" in it.name } } }

        val active = sets.activate(python)!!

        assertEquals("2026.9.1", active.versions["yt-dlp"])
        assertEquals(listOf("use yt-dlp-2026.9.20.zip", "status", "drop", "use yt-dlp-2026.9.1.zip"), python.calls)
        assertTrue(sets.isSkipped(bad.versions))
        assertFalse(File(dir, "yt-dlp-2026.9.20.zip").exists())
        assertTrue(sets.status.value.note!!.contains("failed its check"))
        assertTrue(sets.status.value.note!!.contains("broken on purpose"))
    }

    @Test
    fun `a set that reports other versions than its own is not trusted`() {
        val sets = sets()
        fetched(sets, 1, "gallery-dl" to "1.33.0")
        val python = object : EngineRuntime {
            override fun use(files: List<File>) {}
            override fun drop() {}
            // The files were not what got imported: the app's copy answered.
            override fun status() = FakePython(bundled).status()
        }

        assertNull(sets.activate(python))
        assertTrue(sets.status.value.note!!.contains("gallery-dl reported 1.32.16 instead of 1.33.0"))
    }

    @Test
    fun `a set whose check never finished, because the app died, is not used again`() {
        val sets = sets()
        val set = fetched(sets, 1, "yt-dlp" to "2026.9.1")
        val state = File(dir, "state.json")
        var onDiskDuringCheck = ""
        val python = FakePython(bundled).apply { duringCheck = { onDiskDuringCheck = state.readText() } }
        sets.activate(python)
        // The app dies during the check: the next start finds what was on disk then.
        state.writeText(onDiskDuringCheck)

        val next = FakePython(bundled)
        val restarted = sets()

        assertNull(restarted.activate(next))
        assertTrue(restarted.isSkipped(set.versions))
        assertEquals(emptyList<String>(), next.calls)
    }

    @Test
    fun `two engine failures in a row skip a set from the next start, and a success in between resets the count`() {
        fetched(sets(), 1, "yt-dlp" to "2026.9.1").also { sets().activate(FakePython(bundled)) }
        fetched(sets(), 2, "yt-dlp" to "2026.9.20").also { sets().activate(FakePython(bundled)) }

        val sets = sets()
        assertEquals("2026.9.20", sets.activate(FakePython(bundled))!!.versions["yt-dlp"])
        sets.runFinished(engineFailed = true)
        sets.runFinished(engineFailed = false)
        sets.runFinished(engineFailed = true)
        assertEquals("2026.9.20", sets().activate(FakePython(bundled))!!.versions["yt-dlp"])

        val again = sets()
        again.activate(FakePython(bundled))
        again.runFinished(engineFailed = true)
        again.runFinished(engineFailed = true)
        // Still in use until the app starts again: modules cannot be swapped under a running engine.
        assertTrue(File(dir, "yt-dlp-2026.9.20.zip").exists())

        val next = sets()
        assertEquals("2026.9.1", next.activate(FakePython(bundled))!!.versions["yt-dlp"])
        assertFalse(File(dir, "yt-dlp-2026.9.20.zip").exists())
    }

    @Test
    fun `failures of the app's own copy are not counted against anything`() {
        val sets = sets()
        sets.activate(FakePython(bundled))
        repeat(3) { sets.runFinished(engineFailed = true) }

        assertEquals(null, sets.status.value.note)
    }

    @Test
    fun `with updates turned off, the copy in the app is used`() {
        fetched(sets(), 1, "yt-dlp" to "2026.9.1").also { sets().activate(FakePython(bundled)) }
        sets().setUpdatesOn(false)
        val python = FakePython(bundled)

        val sets = sets()
        assertNull(sets.activate(python))
        assertEquals(emptyList<String>(), python.calls)
        assertFalse(sets.status.value.updatesOn)
        assertTrue(sets.status.value.fromApp)
    }

    @Test
    fun `a set whose files are gone is forgotten`() {
        val sets = sets()
        fetched(sets, 1, "yt-dlp" to "2026.9.1")
        File(dir, "yt-dlp-2026.9.1.zip").delete()

        assertNull(sets.activate(FakePython(bundled)))
        assertNull(sets.newest())
    }

    @Test
    fun `the last two good sets are kept and older ones deleted`() {
        for ((i, version) in listOf("2026.9.1", "2026.9.20", "2026.10.5").withIndex()) {
            fetched(sets(), i + 1L, "yt-dlp" to version, "gallery-dl" to "1.33.0")
            sets().activate(FakePython(bundled))
        }

        assertFalse(File(dir, "yt-dlp-2026.9.1.zip").exists())
        assertTrue(File(dir, "yt-dlp-2026.9.20.zip").exists())
        assertTrue(File(dir, "yt-dlp-2026.10.5.zip").exists())
        // Shared by the sets kept, so kept too.
        assertTrue(File(dir, "gallery-dl-1.33.0.zip").exists())
    }

    @Test
    fun `a newer set waiting for the next start shows as waiting`() {
        val sets = sets()
        sets.activate(FakePython(bundled))
        fetched(sets, 1, "yt-dlp" to "2026.9.1")

        val status = sets.status.value
        assertTrue(status.fromApp)
        assertTrue(status.started)
        assertEquals("2026.9.1", status.waiting!!["yt-dlp"])
        assertEquals(bundled, status.versions)
    }

    @Test
    fun `an unreadable state starts again from the copy in the app`() {
        fetched(sets(), 1, "yt-dlp" to "2026.9.1")
        File(dir, "state.json").writeText("{ not json")

        val sets = sets()
        assertNull(sets.activate(FakePython(bundled)))
        assertFalse(File(dir, "yt-dlp-2026.9.1.zip").exists())
    }

    @Test
    fun `the app's pins are read from BuildConfig's form`() {
        assertEquals(bundled, EngineSets.parsePins("yt-dlp==2026.8.19,yt-dlp-ejs==0.8.0,gallery-dl==1.32.16"))
    }
}
