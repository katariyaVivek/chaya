package com.chaya.app.adblock

import android.content.SharedPreferences
import com.chaya.app.adblock.engine.RequestType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The lists shipped in the app, and the weekly fetch of fresh ones, against a local server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FilterListsTest {

    private val server = MockWebServer()
    private lateinit var dir: File
    private lateinit var prefs: SharedPreferences
    private var now = 1_000_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Before
    fun setUp() {
        server.start()
        val context = RuntimeEnvironment.getApplication()
        dir = File(context.cacheDir, "adblock-lists").apply { deleteRecursively() }
        prefs = context.getSharedPreferences("lists-test", 0).apply { edit().clear().commit() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun lists() = FilterLists(
        dir = dir,
        openBundled = { name -> (if (name == "ads.txt") list("||bundled-ads.example^") else list("||bundled-trackers.example^")).byteInputStream() },
        prefs = prefs,
        clock = { now },
        sources = listOf(
            FilterLists.Source("Ads", "ads.txt", server.url("/ads.txt").toString()),
            FilterLists.Source("Trackers", "trackers.txt", server.url("/trackers.txt").toString()),
        ),
    )

    /** A list as the sites serve it: the header, then enough rules to be believed. */
    private fun list(vararg rules: String) = buildString {
        append("[Adblock Plus 2.0]\n! Title: Test\n")
        rules.forEach { append(it).append('\n') }
        repeat(FilterLists.MIN_RULES) { append("||filler-$it.example^\n") }
    }

    private fun blocks(lists: FilterLists, url: String): Boolean {
        val engine = lists.buildEngine(PublicSuffixSites)
        return engine.shouldBlock(url, RequestType.SCRIPT, engine.page("https://news.example/"))
    }

    /** Lists as Chaya has them: two shipped in the app, and one (uBlock Origin's) that is only ever fetched. */
    private fun listsWithFetchedOnly() = FilterLists(
        dir = dir,
        openBundled = { name -> (if (name == "ads.txt") list("||bundled-ads.example^") else list("||bundled-trackers.example^")).byteInputStream() },
        prefs = prefs,
        clock = { now },
        sources = listOf(
            FilterLists.Source("Ads", "ads.txt", server.url("/ads.txt").toString()),
            FilterLists.Source("Trackers", "trackers.txt", server.url("/trackers.txt").toString()),
            FilterLists.Source("uBlock", "ublock.txt", server.url("/ublock.txt").toString(), bundled = false, minRules = 2),
        ),
    )

    @Test
    fun `a list not shipped in the app is fetched the first time, not a week later, and used once it is`() = runBlocking {
        val lists = listsWithFetchedOnly()
        // Before: the shipped lists work, and the missing one makes a fetch due at once
        assertTrue(blocks(lists, "https://bundled-ads.example/a.js"))
        assertFalse(blocks(lists, "https://popunder.example/p.js"))
        assertTrue(lists.isUpdateDue())

        server.enqueue(MockResponse().setBody(list("||bundled-ads.example^")))
        server.enqueue(MockResponse().setBody(list("||bundled-trackers.example^")))
        // uBlock Origin's lists start with their title, not an Adblock Plus header, and can be short
        server.enqueue(MockResponse().setBody("! Title: uBlock filters\n||popunder.example^\n||more.example^\n"))
        assertTrue(lists.updateIfDue())

        assertTrue(blocks(lists, "https://popunder.example/p.js"))
        assertFalse("nothing is due again for a week", lists.isUpdateDue())
    }

    @Test
    fun `a failed first fetch of a list not shipped is tried again hours later, not on every page`() = runBlocking {
        val lists = listsWithFetchedOnly()
        server.enqueue(MockResponse().setResponseCode(503))
        assertFalse(lists.updateIfDue())
        assertFalse(lists.isUpdateDue())
        now += FilterLists.RETRY_AFTER_MILLIS
        assertTrue(lists.isUpdateDue())
    }

    @Test
    fun `a uBlock Origin list is believed by its title, but not a page that only looks like one`() {
        val good = File(dir.apply { mkdirs() }, "good.txt").apply { writeText("! Title: uBlock filters – Quick fixes\n||a.example^\n||b.example^\n||c.example^\n") }
        assertTrue(FilterLists.looksLikeList(good, minRules = 2))
        assertFalse("too short for what it claims", FilterLists.looksLikeList(good, minRules = 10))
        val html = File(dir, "html.txt").apply { writeText("<!DOCTYPE html>\n<p>Not found</p>\n".repeat(600)) }
        assertFalse(FilterLists.looksLikeList(html, minRules = 2))
    }

    @Test
    fun `the copies in the app are used until fresh ones are fetched`() {
        val lists = lists()
        assertTrue(blocks(lists, "https://bundled-ads.example/a.js"))
        assertTrue(blocks(lists, "https://bundled-trackers.example/t.js"))
        assertNull(lists.lastUpdated())
    }

    @Test
    fun `when a week has passed every list is fetched, and the fresh copies replace the shipped ones`() = runBlocking {
        server.enqueue(MockResponse().setBody(list("||fresh-ads.example^")))
        server.enqueue(MockResponse().setBody(list("||fresh-trackers.example^")))
        val lists = lists()

        assertTrue(lists.updateIfDue())

        assertEquals(2, server.requestCount)
        val asked = listOf(server.takeRequest(), server.takeRequest())
        assertEquals(listOf("/ads.txt", "/trackers.txt"), asked.map { it.path })
        assertTrue("nothing about the person is sent", asked.all { it.getHeader("Cookie") == null })
        assertTrue(blocks(lists, "https://fresh-ads.example/a.js"))
        assertFalse(blocks(lists, "https://bundled-ads.example/a.js"))
        assertEquals(now, lists.lastUpdated())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".download") })
    }

    @Test
    fun `within the week nothing is fetched`() = runBlocking {
        server.enqueue(MockResponse().setBody(list("||fresh-ads.example^")))
        server.enqueue(MockResponse().setBody(list("||fresh-trackers.example^")))
        val lists = lists()
        assertTrue(lists.updateIfDue())

        now += 6 * day
        assertFalse(lists.updateIfDue())
        assertEquals(2, server.requestCount)

        now += day
        assertTrue(lists.isUpdateDue())
    }

    @Test
    fun `something that is not a list is thrown away, the lists in use stay, and the next try waits hours`() = runBlocking {
        server.enqueue(MockResponse().setBody(list("||fresh-ads.example^")))
        server.enqueue(MockResponse().setBody("<html>Captive portal: please log in</html>"))
        val lists = lists()

        assertFalse(lists.updateIfDue())

        assertTrue("neither list is replaced when one fails", blocks(lists, "https://bundled-ads.example/a.js"))
        assertFalse(blocks(lists, "https://fresh-ads.example/a.js"))
        assertNull(lists.lastUpdated())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".download") })

        now += 2 * 60 * 60 * 1000
        assertFalse("not again within six hours", lists.isUpdateDue())
        now += 5 * 60 * 60 * 1000
        assertTrue(lists.isUpdateDue())
    }

    @Test
    fun `a server error or a cut-off list keeps the lists in use`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val lists = lists()
        assertFalse(lists.updateIfDue())

        now += 7 * 60 * 60 * 1000
        server.enqueue(MockResponse().setBody("[Adblock Plus 2.0]\n||fresh-ads.example^\n"))
        assertFalse(lists.updateIfDue())

        assertTrue(blocks(lists, "https://bundled-ads.example/a.js"))
    }
}
