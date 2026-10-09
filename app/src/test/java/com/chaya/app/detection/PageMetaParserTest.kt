package com.chaya.app.detection

import com.chaya.app.model.DetectedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The page report is page-controlled input crossing into native code, so the
 * parser and its bridge entry point must cap, clamp, and filter before
 * anything reaches UI state. Robolectric supplies a real org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PageMetaParserTest {

    @Test
    fun `well-formed reports parse into page and player hints`() {
        val meta = PageMetaParser.parse(
            """
            {"title":"Launch  event\n- Site","ogTitle":"Launch event","siteName":"Site",
             "ogImage":"https://img.example/poster.jpg",
             "videoUrls":["https://cdn.example/v/main.mp4","javascript:alert(1)"],
             "ldName":"Launch event","ldDuration":"PT1M42S",
             "players":[{"src":"https://cdn.example/v/main.mp4","blob":false,"audio":false,"w":960,"h":540,
               "visible":true,"duration":102.5,"vw":1280,"vh":720,"playing":true,"played":true,
               "muted":false,"autoplay":false,"loop":false,"controls":true,
               "poster":"https://img.example/poster.jpg","title":"Launch","ad":false}]}
            """.trimIndent()
        )!!

        assertEquals("Launch event - Site", meta.title)
        assertEquals(listOf("https://cdn.example/v/main.mp4"), meta.videoUrls)
        assertEquals(102.0, meta.ldDurationSeconds!!, 0.001)
        val player = meta.players.single()
        assertEquals(960, player.width)
        assertEquals(720, player.videoHeight)
        assertEquals(102.5, player.durationSeconds!!, 0.001)
        assertTrue(player.playing)
    }

    @Test
    fun `non-web urls negative numbers and oversized text are neutralized`() {
        val meta = PageMetaParser.parse(
            """
            {"title":"${"t".repeat(500)}","ogImage":"data:image/png;base64,AAAA",
             "players":[{"src":"blob:https://site.example/1","blob":true,"w":-5,"h":1e12,"duration":-3,
               "poster":"file:///sdcard/x.jpg"}]}
            """.trimIndent()
        )!!

        assertEquals(300, meta.title!!.length)
        assertNull(meta.ogImage)
        val player = meta.players.single()
        assertNull(player.src)
        assertTrue(player.isBlob)
        assertEquals(0, player.width)
        assertEquals(100_000, player.height)
        assertNull(player.durationSeconds)
        assertNull(player.poster)
    }

    @Test
    fun `malformed and oversized reports are rejected`() {
        assertNull(PageMetaParser.parse(null))
        assertNull(PageMetaParser.parse("not json"))
        assertNull(PageMetaParser.parse("[1,2,3]"))
        assertNull(PageMetaParser.parse("{\"title\":\"" + "x".repeat(PageMetaParser.MAX_JSON_CHARS) + "\"}"))
    }

    @Test
    fun `iso durations convert to seconds`() {
        assertEquals(3723.0, PageMetaParser.parseIsoDuration("PT1H2M3S")!!, 0.001)
        assertEquals(42.5, PageMetaParser.parseIsoDuration("PT42.5S")!!, 0.001)
        assertNull(PageMetaParser.parseIsoDuration("P"))
        assertNull(PageMetaParser.parseIsoDuration("1:42"))
    }

    /** The bridge entry point keeps the capability boundary: only the active main document can report. */
    @Test
    fun `bridge forwards page meta only with the active capability`() {
        val received = mutableListOf<Pair<PageMeta, Long>>()
        val bridge = MediaBridge(
            onMediaDetected = { _: DetectedMedia, _: Long -> },
            onPageMeta = { meta, generation -> received += meta to generation },
        )
        val capability = bridge.beginNavigation("https://site.example/watch", 7L)

        bridge.onPageMeta(null, """{"title":"forged"}""")
        bridge.onPageMeta("not-the-capability", """{"title":"forged"}""")
        bridge.onPageMeta(capability, "not json")
        bridge.onPageMeta(capability, """{"title":"Real page"}""")
        bridge.invalidateNavigation()
        bridge.onPageMeta(capability, """{"title":"late"}""")

        assertEquals(1, received.size)
        assertEquals("Real page", received.single().first.title)
        assertEquals(7L, received.single().second)
    }
}
