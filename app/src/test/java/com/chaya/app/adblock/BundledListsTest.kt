package com.chaya.app.adblock

import com.chaya.app.adblock.engine.FilterEngine
import com.chaya.app.adblock.engine.RequestType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * The lists that ship in the app, read by the real engine: common ads and trackers are blocked, and nothing
 * Chaya itself relies on is: the video and picture files of YouTube, Instagram, TikTok and X, their players
 * and their APIs, and everyday page files.
 */
class BundledListsTest {

    companion object {
        private lateinit var engine: FilterEngine

        @BeforeClass
        @JvmStatic
        fun readLists() {
            val builder = FilterEngine.Builder(PublicSuffixSites)
            for (name in listOf("easylist.txt", "easyprivacy.txt")) {
                File("src/main/assets/adblock/$name").useLines { builder.addAll(it) }
            }
            engine = builder.build()
        }
    }

    private fun blocked(page: String, url: String, type: Int = RequestType.UNKNOWN) =
        engine.shouldBlock(url, type, engine.page(page))

    @Test
    fun `the lists are whole`() {
        assertTrue("rules: ${engine.ruleCount}", engine.ruleCount > 100_000)
    }

    @Test
    fun `common ads and trackers are blocked`() {
        val page = "https://www.theguardian.com/world/2026/oct/10/story"
        listOf(
            "https://securepubads.g.doubleclick.net/tag/js/gpt.js",
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=ca-pub-1",
            "https://www.googletagmanager.com/gtm.js?id=GTM-1",
            "https://www.google-analytics.com/g/collect?v=2",
            "https://c.amazon-adsystem.com/aax2/apstag.js",
            "https://cdn.taboola.com/libtrc/x/loader.js",
        ).forEach { assertTrue(it, blocked(page, it, RequestType.SCRIPT)) }
    }

    @Test
    fun `nothing Chaya downloads from, or the video sites need to play, is blocked`() {
        val youTube = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val instagram = "https://www.instagram.com/p/Cabc123/"
        val x = "https://x.com/someone/status/1"
        val tikTok = "https://www.tiktok.com/@someone/video/1"
        listOf(
            Triple(youTube, "https://rr3---sn-4g5e6nzz.googlevideo.com/videoplayback?expire=1&itag=18", RequestType.MEDIA),
            Triple(youTube, "https://rr3---sn-4g5e6nzz.googlevideo.com/videoplayback?expire=1&itag=18", RequestType.UNKNOWN),
            Triple(youTube, "https://www.youtube.com/s/player/abc/player_ias.vflset/en_US/base.js", RequestType.SCRIPT),
            Triple(youTube, "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", RequestType.IMAGE),
            Triple(youTube, "https://www.youtube.com/youtubei/v1/player?key=x", RequestType.UNKNOWN),
            Triple(instagram, "https://scontent-lhr8-1.cdninstagram.com/v/t51.29350-15/1_n.jpg?stp=dst-jpg", RequestType.IMAGE),
            Triple(instagram, "https://instagram.flhr8-1.fna.fbcdn.net/o1/v/t16/f2/m86/a.mp4?efg=x", RequestType.MEDIA),
            Triple(instagram, "https://www.instagram.com/graphql/query", RequestType.UNKNOWN),
            Triple(instagram, "https://static.cdninstagram.com/rsrc.php/v3/yR/r/a.js", RequestType.SCRIPT),
            Triple(x, "https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/720x1280/a.mp4?tag=12", RequestType.MEDIA),
            Triple(x, "https://pbs.twimg.com/media/Fabc?format=jpg&name=small", RequestType.IMAGE),
            Triple(x, "https://abs.twimg.com/responsive-web/client-web/main.a.js", RequestType.SCRIPT),
            Triple(x, "https://api.x.com/graphql/abc/TweetDetail?variables=x", RequestType.UNKNOWN),
            Triple(tikTok, "https://v16-webapp-prime.tiktok.com/video/tos/a/?mime_type=video_mp4", RequestType.MEDIA),
            Triple("https://news.example/", "https://code.jquery.com/jquery-3.7.1.min.js", RequestType.SCRIPT),
            Triple("https://news.example/", "https://fonts.googleapis.com/css2?family=Inter", RequestType.STYLESHEET),
        ).forEach { (page, url, type) -> assertFalse(url, blocked(page, url, type)) }
    }

    @Test
    fun `the video sites keep their own pages`() {
        listOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "https://www.instagram.com/someone/", "https://x.com/home")
            .forEach { assertFalse(it, engine.page(it).allowAll) }
    }
}
