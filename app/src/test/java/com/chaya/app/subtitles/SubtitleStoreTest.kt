package com.chaya.app.subtitles

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A site's subtitles fetched for a download, kept as SRT by download, read back, and deleted with it. */
class SubtitleStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: SubtitleStore

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        store = SubtitleStore(folder.newFolder("subtitles"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.500\nHello there.\n\n00:00:03.000 --> 00:00:04.000\nGoodbye.\n"

    @Test
    fun `a site's subtitles are kept as SRT and read back by the download they belong to`() = runBlocking {
        server.enqueue(MockResponse().setBody(vtt))
        val source = SubtitleSource("en", server.url("/timedtext?lang=en").toString(), mapOf("Referer" to "https://www.youtube.com/"))

        assertTrue(store.save(12, source))

        val sent = server.takeRequest()
        assertEquals("https://www.youtube.com/", sent.getHeader("Referer"))
        val saved = store.load(12)!!
        assertEquals("en", saved.language)
        assertEquals(listOf("Hello there.", "Goodbye."), saved.lines.map { it.text })
        assertEquals(0, saved.lineAt(1500))
        assertNull(saved.lineAt(2700))
        assertTrue(store.has(12))
        assertNull(store.load(13))
    }

    @Test
    fun `nothing is kept when the site fails, answers no subtitles, or answers far too much`() = runBlocking {
        val url = server.url("/subs").toString()
        server.enqueue(MockResponse().setResponseCode(404))
        assertFalse(store.save(1, SubtitleSource("en", url)))
        server.enqueue(MockResponse().setBody("<html>Sign in</html>"))
        assertFalse(store.save(2, SubtitleSource("en", url)))
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray((SubtitleStore.MAX_BYTES + 10).toInt()) { 'a'.code.toByte() })))
        assertFalse(store.save(3, SubtitleSource("en", url)))
        // An address that is not one, and a language that would name another file
        assertFalse(store.save(4, SubtitleSource("en", "not an address")))
        server.enqueue(MockResponse().setBody(vtt))
        assertFalse(store.save(5, SubtitleSource("../en", url)))
        listOf(1L, 2, 3, 4, 5).forEach { assertNull(store.load(it)) }
    }

    @Test
    fun `a deleted download's subtitles go with it, and no other's`() = runBlocking {
        server.enqueue(MockResponse().setBody(vtt))
        server.enqueue(MockResponse().setBody(vtt))
        store.save(7, SubtitleSource("en", server.url("/a").toString()))
        store.save(70, SubtitleSource("fr", server.url("/b").toString()))

        store.forget(7)

        assertNull(store.load(7))
        assertEquals("fr", store.load(70)?.language)
    }

    @Test
    fun `a sign-in cookie goes only to the video's own site`() {
        val headers = mapOf("Cookie" to "SID=1", "User-Agent" to "Chaya")
        assertEquals(headers, SubtitleStore.headersFor("https://www.youtube.com/api/timedtext", "https://www.youtube.com/v", headers))
        assertEquals(
            mapOf("User-Agent" to "Chaya"),
            SubtitleStore.headersFor("https://subs.example/en.vtt", "https://rr1.googlevideo.com/v", headers),
        )
    }
}
