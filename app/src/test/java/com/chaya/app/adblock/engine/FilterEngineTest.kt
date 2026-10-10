package com.chaya.app.adblock.engine

import com.chaya.app.adblock.engine.RequestType.IMAGE
import com.chaya.app.adblock.engine.RequestType.SCRIPT
import com.chaya.app.adblock.engine.RequestType.STYLESHEET
import com.chaya.app.adblock.engine.RequestType.SUBDOCUMENT
import com.chaya.app.adblock.engine.RequestType.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule syntax EasyList and EasyPrivacy use, rule by rule, against a stand-in for the public suffix list. */
class FilterEngineTest {

    /** Enough of the public suffix list for these tests: two labels, or three under co.uk. */
    private val sites = DomainResolver { host ->
        val labels = host.split('.')
        val keep = if (host.endsWith(".co.uk")) 3 else 2
        labels.takeLast(keep).joinToString(".")
    }

    private fun engine(vararg rules: String) = FilterEngine.Builder(sites).apply { rules.forEach(::add) }.build()

    private fun FilterEngine.blocks(url: String, page: String = "https://news.example/article", type: Int = UNKNOWN) =
        shouldBlock(url, type, page(page))

    // ---- addresses ---- //

    @Test
    fun `a bare host rule blocks that host and every host under it, and nothing else`() {
        val e = engine("||ads.example.com^")

        assertTrue(e.blocks("https://ads.example.com/banner.png"))
        assertTrue(e.blocks("https://cdn.ads.example.com/x.js?y=1"))
        assertTrue(e.blocks("http://ads.example.com:8080/"))
        assertTrue(e.blocks("https://ads.example.com"))
        assertFalse(e.blocks("https://badads.example.com/"))
        assertFalse(e.blocks("https://example.com/ads.example.com/"))
        assertFalse(e.blocks("https://ads.example.com.evil.net/"))
    }

    @Test
    fun `a host rule with a path matches from the start of a host label`() {
        val e = engine("||tracker.net/collect^")

        assertTrue(e.blocks("https://tracker.net/collect?id=1"))
        assertTrue(e.blocks("https://eu.tracker.net/collect"))
        assertFalse(e.blocks("https://tracker.net/collector"))
        assertFalse(e.blocks("https://mytracker.net/collect"))
    }

    @Test
    fun `wildcards, separators and anchors`() {
        val e = engine("/banner/*/ad_", "|https://pop.", "swf|", "&ad_type=^")

        assertTrue(e.blocks("https://site.example/banner/300x250/ad_1.png"))
        assertFalse(e.blocks("https://site.example/banner/ad_1.png"))
        assertTrue(e.blocks("https://pop.example/"))
        assertFalse(e.blocks("https://x.example/?u=https://pop.example/"))
        assertTrue(e.blocks("https://x.example/movie.swf"))
        assertFalse(e.blocks("https://x.example/movie.swf?x"))
        assertTrue(e.blocks("https://x.example/?a=1&ad_type=&b"))
        assertTrue("a separator also matches the end", e.blocks("https://x.example/?a=1&ad_type="))
    }

    @Test
    fun `rules match whatever the case of the address, unless they ask not to`() {
        val e = engine("/AdServer/*", "/TrackMe\$match-case")

        assertTrue(e.blocks("https://x.example/adserver/a.js"))
        assertTrue(e.blocks("https://x.example/ADSERVER/a.js"))
        assertTrue(e.blocks("https://x.example/TrackMe"))
        assertFalse(e.blocks("https://x.example/trackme"))
    }

    // ---- options ---- //

    @Test
    fun `third-party rules block only what other sites send`() {
        val e = engine("||cdn.widgets.example^\$third-party", "||stats.example^\$~third-party")

        assertTrue(e.blocks("https://cdn.widgets.example/w.js", page = "https://news.example/"))
        assertFalse(e.blocks("https://cdn.widgets.example/w.js", page = "https://www.widgets.example/"))
        assertTrue(e.blocks("https://stats.example/p", page = "https://www.stats.example/"))
        assertFalse(e.blocks("https://stats.example/p", page = "https://news.example/"))
    }

    @Test
    fun `third party compares registered names, so a site's own subdomains are first party`() {
        val e = engine("||img.shop.co.uk^\$third-party")

        assertFalse(e.blocks("https://img.shop.co.uk/a.png", page = "https://www.shop.co.uk/"))
        assertTrue(e.blocks("https://img.shop.co.uk/a.png", page = "https://www.other.co.uk/"))
    }

    @Test
    fun `domain rules apply only on the pages they name`() {
        val e = engine("/sponsor.js\$domain=news.example|~sport.news.example", "/promo.\$domain=shop.*")

        assertTrue(e.blocks("https://cdn.example/sponsor.js", page = "https://www.news.example/"))
        assertFalse(e.blocks("https://cdn.example/sponsor.js", page = "https://sport.news.example/"))
        assertFalse(e.blocks("https://cdn.example/sponsor.js", page = "https://blog.example/"))
        assertTrue(e.blocks("https://cdn.example/promo.png", page = "https://www.shop.co.uk/"))
        assertTrue(e.blocks("https://cdn.example/promo.png", page = "https://shop.de/"))
        assertFalse(e.blocks("https://cdn.example/promo.png", page = "https://workshop.de/"))
    }

    @Test
    fun `a rule naming kinds of request never matches a request whose kind is unknown`() {
        val e = engine("/adframe.\$subdocument", "/pixel.\$~image", "/plain-ad.")

        assertTrue(e.blocks("https://x.example/adframe.html", type = SUBDOCUMENT))
        assertFalse(e.blocks("https://x.example/adframe.html", type = SCRIPT))
        assertFalse(e.blocks("https://x.example/adframe.html", type = UNKNOWN))
        assertTrue(e.blocks("https://x.example/pixel.js", type = SCRIPT))
        assertFalse(e.blocks("https://x.example/pixel.gif", type = IMAGE))
        assertFalse(e.blocks("https://x.example/pixel.gif", type = UNKNOWN))
        assertTrue(e.blocks("https://x.example/plain-ad.css", type = STYLESHEET))
        assertTrue(e.blocks("https://x.example/plain-ad.css", type = UNKNOWN))
    }

    @Test
    fun `exceptions win over blocks, and important blocks win over exceptions`() {
        val e = engine(
            "||ads.example^", "@@||ads.example/needed.js",
            "||track.example^\$important", "@@||track.example^",
        )

        assertTrue(e.blocks("https://ads.example/banner.js"))
        assertFalse(e.blocks("https://ads.example/needed.js"))
        assertTrue(e.blocks("https://track.example/t.gif"))
    }

    @Test
    fun `a page-wide exception turns everything off on that page only`() {
        val e = engine("||ads.example^", "##.ad-box", "@@||friendly.example^\$document")

        assertFalse(e.blocks("https://ads.example/a.js", page = "https://friendly.example/home"))
        assertEquals("", e.pageCss(e.page("https://friendly.example/home")))
        assertTrue(e.blocks("https://ads.example/a.js", page = "https://other.example/"))
    }

    @Test
    fun `pop-up rules apply to new windows only`() {
        val e = engine("||popads.example^\$popup", "||both.example^\$popup,third-party")

        assertFalse(e.blocks("https://popads.example/go"))
        assertTrue(e.shouldBlockPopup("https://popads.example/go", e.page("https://news.example/")))
        assertFalse(e.shouldBlockPopup("https://other.example/", e.page("https://news.example/")))
    }

    @Test
    fun `rules this engine cannot apply faithfully are skipped`() {
        val e = engine(
            "/^https?:\\/\\/ads\\./", // regular expression
            "/adserver/", // also read as a regular expression, as Adblock Plus reads it
            "||x.example^\$csp=script-src 'none'", // rewrites a header
            "||y.example^\$removeparam=utm", // rewrites the address
            "||z.example^\$redirect-rule=noop.js", // only redirects when something else blocks
            "\$third-party", // would block every third-party request
            "news.example##+js(set, adsbygoogle, {})", // scriptlet
            "news.example##.ad:has-text(Sponsored)", // procedural
        )

        assertFalse(e.blocks("https://ads.example/"))
        assertFalse(e.blocks("https://x.example/"))
        assertFalse(e.blocks("https://y.example/?utm=1"))
        assertFalse(e.blocks("https://z.example/a.js"))
        assertFalse(e.blocks("https://cdn.other/a.js"))
        assertEquals("", e.pageCss(e.page("https://news.example/")))
        assertEquals(0, e.ruleCount)
    }

    @Test
    fun `a redirect rule still blocks`() {
        assertTrue(engine("||ads.example/gpt.js\$script,redirect=googletagservices_gpt.js").blocks(
            "https://ads.example/gpt.js", type = SCRIPT,
        ))
    }

    @Test
    fun `only http and https requests are ever blocked`() {
        val e = engine("/ad.")
        assertFalse(e.blocks("data:text/plain,/ad.x"))
        assertFalse(e.blocks("blob:https://x.example/ad.1"))
    }

    // ---- hiding page elements ---- //

    @Test
    fun `site rules are hidden on that site and its subdomains, minus exceptions`() {
        val e = engine("news.example,~live.news.example##.sponsor", "news.example##div[data-ad]", "www.news.example#@#.sponsor")

        val css = e.pageCss(e.page("https://m.news.example/a"))
        assertTrue(css.contains(".sponsor{display:none!important}"))
        assertTrue(css.contains("div[data-ad]{display:none!important}"))
        assertFalse(e.pageCss(e.page("https://live.news.example/")).contains(".sponsor"))
        assertFalse(e.pageCss(e.page("https://www.news.example/")).contains(".sponsor"))
        assertEquals("", e.pageCss(e.page("https://other.example/")))
    }

    @Test
    fun `rules for every site are handed out by the classes and ids a page has`() {
        val e = engine("##.ad-banner", "##.ad-banner > .inner", "###top-ad", "##a[href^=\"https://ads.\"]", "~safe.example##.promo")
        val page = e.page("https://news.example/")

        val generic = e.genericCss(page, listOf(".ad-banner", ".article", "#top-ad"))
        assertTrue(generic.contains(".ad-banner{display:none!important}"))
        assertTrue(generic.contains(".ad-banner > .inner{display:none!important}"))
        assertTrue(generic.contains("#top-ad{display:none!important}"))
        assertEquals("", e.genericCss(page, listOf(".article")))
        val always = e.pageCss(page)
        assertTrue("selectors without a class or id are always included", always.contains("a[href^=\"https://ads.\"]"))
        assertTrue(always.contains(".promo"))
        assertFalse(e.pageCss(e.page("https://safe.example/")).contains(".promo"))
    }

    @Test
    fun `generichide keeps only a site's own rules, and a generic exception removes a rule everywhere`() {
        val e = engine("##.ad-banner", "##div[id^=\"ad-\"]", "shop.example##.deal-ad", "@@||shop.example^\$generichide", "#@#.ad-banner")

        val shop = e.page("https://shop.example/")
        assertEquals("", e.genericCss(shop, listOf(".ad-banner")))
        assertEquals(".deal-ad{display:none!important}\n", e.pageCss(shop))
        assertFalse(e.hidesGenerically(shop))
        assertEquals("", e.genericCss(e.page("https://news.example/"), listOf(".ad-banner")))
    }

    @Test
    fun `selectors that could break out of their CSS rule are refused`() {
        val e = engine("##.a{}body{display:none", "##.b</style>", "news.example##.c\\{")
        assertEquals("", e.pageCss(e.page("https://news.example/")))
        assertEquals("", e.genericCss(e.page("https://news.example/"), listOf(".a", ".b")))
    }

    // ---- helpers ---- //

    @Test
    fun `rules are indexed by a whole word of their pattern`() {
        assertEquals("adserver", FilterEngine.bestToken("/adserver/", anchoredStart = false, anchoredEnd = false))
        assertNull("a word next to a wildcard may be part of a longer word", FilterEngine.bestToken("ad*", false, false))
        assertEquals("banner", FilterEngine.bestToken("com/banner^", anchoredStart = true, anchoredEnd = false))
        assertNull("a word at an unanchored end may continue in the address", FilterEngine.bestToken("tracker", false, false))
    }

    @Test
    fun `the host of an address`() {
        assertEquals("ads.example.com", FilterEngine.hostOf("https://user:pw@ADS.example.com:8443/x?y#z"))
        assertEquals("a.example", FilterEngine.hostOf("http://a.example"))
        assertEquals("", FilterEngine.hostOf("about:blank"))
    }
}
