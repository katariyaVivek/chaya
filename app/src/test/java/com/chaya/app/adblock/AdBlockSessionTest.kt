package com.chaya.app.adblock

import android.net.Uri
import android.webkit.WebResourceRequest
import com.chaya.app.adblock.engine.FilterEngine
import com.chaya.app.adblock.engine.RequestType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** One browser view's ad blocking: the page it judges by, the person's switches, the count, the page script. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AdBlockSessionTest {

    private lateinit var settings: AdBlockSettings
    private lateinit var blocker: AdBlocker
    private lateinit var session: AdBlockSession

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("adblock-test", 0).apply { edit().clear().commit() }
        settings = AdBlockSettings(prefs)
        val lists = FilterLists(File(context.cacheDir, "lists"), { error("not read here") }, prefs)
        blocker = AdBlocker(settings, lists)
        blocker.useEngine(
            FilterEngine.Builder(PublicSuffixSites).apply {
                listOf(
                    "||ads.example^", "/banner.\$image", "||popads.example^\$popup",
                    "news.example##.sponsor", "##.ad-banner", "@@||friendly.example^\$document",
                ).forEach(::add)
            }.build(),
        )
        session = blocker.newSession()
    }

    @Test
    fun `requests are judged by the page shown, and each one blocked is counted`() {
        session.beginPage("https://www.news.example/story")

        assertTrue(session.shouldBlock("https://ads.example/a.js", RequestType.SCRIPT))
        assertTrue(session.shouldBlock("https://cdn.example/banner.gif", RequestType.IMAGE))
        assertFalse(session.shouldBlock("https://cdn.example/photo.jpg", RequestType.IMAGE))
        assertEquals(2, session.blocked.value)
        assertEquals("news.example", session.site.value)

        session.beginPage("https://www.news.example/next")
        assertEquals("a new page counts afresh", 0, session.blocked.value)
    }

    @Test
    fun `nothing is blocked when the person turns blocking off, or allows the site`() {
        session.beginPage("https://www.news.example/story")

        settings.setEnabled(false)
        assertFalse(session.shouldBlock("https://ads.example/a.js", RequestType.SCRIPT))
        assertNull(session.cosmeticScript())

        settings.setEnabled(true)
        settings.setAllowed("news.example", allowed = true)
        assertFalse(session.shouldBlock("https://ads.example/a.js", RequestType.SCRIPT))
        assertNull(session.cosmeticScript())

        settings.setAllowed("news.example", allowed = false)
        assertTrue(session.shouldBlock("https://ads.example/a.js", RequestType.SCRIPT))
    }

    @Test
    fun `the page itself is never blocked, only what it loads`() {
        session.beginPage("https://www.news.example/story")

        assertNull(session.intercept(request("https://ads.example/landing", mainFrame = true)))
        val blocked = session.intercept(request("https://ads.example/a.js", mainFrame = false))
        assertNotNull(blocked)
        assertEquals(204, blocked!!.statusCode)
        assertEquals(0, blocked.data.available())
        assertNull(session.intercept(request("https://cdn.example/app.js", mainFrame = false)))
    }

    @Test
    fun `a page's own request begins it, so its first files are judged by it even before the WebView reports it`() {
        session.beginPage("https://friendly.example/")

        session.intercept(request("https://www.news.example/story", mainFrame = true))
        assertNotNull(session.intercept(request("https://ads.example/a.js", mainFrame = false)))
        assertEquals("news.example", session.site.value)

        // The WebView's own report of the same page comes later and keeps the count.
        session.pageStarted("https://www.news.example/story")
        assertEquals(1, session.blocked.value)

        // A reload asks for the page again, and counts afresh.
        session.intercept(request("https://www.news.example/story", mainFrame = true))
        assertEquals(0, session.blocked.value)

        // A page shown without a request (the back-forward cache) begins when it is reported.
        session.pageStarted("https://friendly.example/")
        assertEquals("friendly.example", session.site.value)
        assertFalse(session.shouldBlock("https://ads.example/a.js", RequestType.SCRIPT))
    }

    @Test
    fun `a page the lists exempt, or a page before any is shown, blocks nothing`() {
        assertFalse(session.shouldBlock("https://ads.example/a.js", RequestType.SCRIPT))

        session.beginPage("https://friendly.example/")
        assertFalse(session.shouldBlock("https://ads.example/a.js", RequestType.SCRIPT))
        assertNull(session.cosmeticScript())
    }

    @Test
    fun `before the lists are read nothing is blocked, and the page is judged once they are`() {
        val fresh = AdBlocker(settings, FilterLists(File("unused"), { error("unused") }, prefsOf("fresh"))).newSession()
        fresh.beginPage("https://www.news.example/")
        assertFalse(fresh.shouldBlock("https://ads.example/a.js", RequestType.SCRIPT))
        assertEquals("the site is known without the lists", "news.example", fresh.site.value)
    }

    @Test
    fun `ad pop-ups are refused`() {
        session.beginPage("https://www.news.example/")
        assertTrue(session.shouldBlockPopup("https://popads.example/go"))
        assertFalse(session.shouldBlockPopup("https://shop.example/"))
    }

    @Test
    fun `the page script carries the site's hiding rules and asks for the rest by class and id`() {
        session.beginPage("https://www.news.example/")

        val script = session.cosmeticScript()!!
        assertTrue(script.contains(".sponsor{display:none!important}"))
        assertTrue(script.contains("var generic = true;"))
        assertTrue(script.contains("ChayaCosmetic"))

        val bridge = CosmeticBridge(session)
        assertEquals(".ad-banner{display:none!important}\n", bridge.generic("""[".ad-banner", ".article"]"""))
        assertEquals("", bridge.generic("not json"))
        assertEquals("", bridge.generic(null))
    }

    @Test
    fun `about blank and the start page have no site`() {
        session.beginPage("about:blank")
        assertEquals("", session.site.value)
    }

    private fun prefsOf(name: String) = RuntimeEnvironment.getApplication().getSharedPreferences(name, 0)

    private fun request(url: String, mainFrame: Boolean) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = mapOf("Accept" to "*/*")
    }
}
