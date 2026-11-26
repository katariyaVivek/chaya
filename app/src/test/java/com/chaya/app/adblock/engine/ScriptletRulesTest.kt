package com.chaya.app.adblock.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scriptlet rules from uBlock Origin's lists, and the navigations the lists stop. */
class ScriptletRulesTest {

    private val sites = DomainResolver { host -> host.split('.').takeLast(2).joinToString(".") }

    private fun engine(vararg rules: String) = FilterEngine.Builder(sites).apply { rules.forEach(::add) }.build()

    private fun FilterEngine.on(url: String) = scriptlets(page(url))

    @Test
    fun `a site's scriptlets are handed to its pages and its subdomains, by their full names`() {
        val e = engine(
            "m.youtube.com,www.youtube.com##+js(set, ytInitialPlayerResponse.adPlacements, undefined)",
            "example.com##+js(nowoif)",
        )
        assertEquals(
            listOf(listOf("set-constant", "ytInitialPlayerResponse.adPlacements", "undefined")),
            e.on("https://m.youtube.com/watch?v=1"),
        )
        assertEquals(listOf(listOf("no-window-open-if")), e.on("https://video.example.com/"))
        assertEquals(emptyList<List<String>>(), e.on("https://youtube.com/"))
        assertEquals(emptyList<List<String>>(), e.on("https://news.example/"))
    }

    @Test
    fun `arguments are split at commas, an escaped comma kept, and quotes taken off`() {
        assertEquals(
            listOf("json-prune", "a.b c", "x,y", "q"),
            ScriptletRules.parseArguments("json-prune, a.b c, x\\,y, 'q'"),
        )
        assertEquals(listOf("nowoif"), ScriptletRules.parseArguments("nowoif"))
        assertEquals(emptyList<String>(), ScriptletRules.parseArguments(""))
    }

    @Test
    fun `scriptlets Chaya does not carry, and uBlock's trusted ones, are left out`() {
        val e = engine(
            "example.com##+js(trusted-replace-xhr-response, a, b)",
            "example.com##+js(href-sanitizer, a)",
            "example.com##+js(aopr, adsbygoogle)",
        )
        assertEquals(listOf(listOf("abort-on-property-read", "adsbygoogle")), e.on("https://example.com/"))
    }

    @Test
    fun `a site can be left out, and a site's exception turns one scriptlet or all of them off`() {
        val e = engine(
            "example.com,~shop.example.com##+js(aopr, ads)",
            "news.example##+js(aopr, ads)",
            "news.example##+js(nowoif)",
            "news.example#@#+js(aopr, ads)",
            "quiet.example##+js(nowoif)",
            "quiet.example#@#+js()",
        )
        assertEquals(listOf(listOf("abort-on-property-read", "ads")), e.on("https://example.com/"))
        assertEquals(emptyList<List<String>>(), e.on("https://shop.example.com/"))
        assertEquals(listOf(listOf("no-window-open-if")), e.on("https://news.example/"))
        assertEquals(emptyList<List<String>>(), e.on("https://quiet.example/"))
    }

    @Test
    fun `a rule for every site, and one for a name under any ending`() {
        val e = engine("##+js(nostif, adblock)", "torrent.*##+js(nowoif)")
        assertEquals(listOf(listOf("no-setTimeout-if", "adblock")), e.on("https://anything.example/"))
        assertTrue(listOf("no-window-open-if") in e.on("https://torrent.example/"))
    }

    @Test
    fun `the same scriptlet named on a site twice runs once, and none run on a page the lists allow whole`() {
        val e = engine(
            "example.com##+js(aopr, ads)",
            "www.example.com##+js(aopr, ads)",
            "@@||allowed.example^\$document",
            "allowed.example##+js(aopr, ads)",
        )
        assertEquals(1, e.on("https://www.example.com/").size)
        assertEquals(emptyList<List<String>>(), e.on("https://allowed.example/"))
    }

    @Test
    fun `scriptlet rules count as rules in use, and never become hiding CSS`() {
        val e = engine("example.com##+js(aopr, ads)")
        assertEquals(1, e.ruleCount)
        assertEquals("", e.pageCss(e.page("https://example.com/")))
    }

    @Test
    fun `a page may not send itself to a host the lists block outright, or to an ad pop-up`() {
        val e = engine("||popads.example^", "||adserver.example^\$popup", "||tracker.example/pixel.gif\$image")
        val page = e.page("https://stream.example/watch")
        assertTrue(e.shouldBlockNavigation("https://popads.example/landing", page))
        assertTrue(e.shouldBlockNavigation("https://adserver.example/go", page))
        // A rule for one kind of request only (an image) does not stop a page going there
        assertFalse(e.shouldBlockNavigation("https://tracker.example/pixel.gif", page))
        assertFalse(e.shouldBlockNavigation("https://news.example/story", page))
    }
}
