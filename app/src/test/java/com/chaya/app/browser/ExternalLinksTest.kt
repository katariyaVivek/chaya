package com.chaya.app.browser

import android.content.Intent
import com.chaya.app.browser.ExternalLinks.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Links that are not web pages: a tapped `tel:` or `intent:` link opened WebView's "net::ERR_UNKNOWN_URL_SCHEME"
 * page, and an ad could send the page to the Play Store on its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExternalLinksTest {

    @Test
    fun `web addresses stay in the browser, tapped or not`() {
        for (url in listOf("https://example.com", "http://a.b/c", "about:blank", "data:text/html,hi", "blob:https://a.b/1", "javascript:void(0)")) {
            assertEquals(url, Route.WEB, ExternalLinks.route(url, hasGesture = false, isRedirect = false, sinceTapMillis = null))
        }
    }

    @Test
    fun `an app's link the person tapped opens in its app`() {
        for (url in listOf("tel:+15551234", "mailto:a@b.c", "intent://scan/#Intent;scheme=zxing;end", "market://details?id=a.b", "whatsapp://send?text=hi")) {
            assertEquals(url, Route.APP, ExternalLinks.route(url, hasGesture = true, isRedirect = false, sinceTapMillis = null))
        }
    }

    @Test
    fun `a page sending itself to an app with no tap is stopped`() {
        assertEquals(Route.BLOCK, ExternalLinks.route("market://details?id=ad.app", hasGesture = false, isRedirect = false, sinceTapMillis = 100))
        assertEquals(Route.BLOCK, ExternalLinks.route("intent://x#Intent;package=ad.app;end", hasGesture = false, isRedirect = true, sinceTapMillis = null))
    }

    @Test
    fun `a redirect soon after a tap counts as tapped, as a Get the app link through a tracker does`() {
        assertEquals(Route.APP, ExternalLinks.route("market://details?id=a.b", hasGesture = false, isRedirect = true, sinceTapMillis = 1_200))
        val late = ExternalLinks.TAP_LASTS_MILLIS + 1
        assertEquals(Route.BLOCK, ExternalLinks.route("market://details?id=a.b", hasGesture = false, isRedirect = true, sinceTapMillis = late))
    }

    @Test
    fun `an address without a real scheme is left to WebView`() {
        assertNull(ExternalLinks.schemeOf("no-scheme-here"))
        assertNull(ExternalLinks.schemeOf(":odd"))
        assertEquals("tel", ExternalLinks.schemeOf("TEL:123"))
    }

    @Test
    fun `an intent link cannot name a part of an app, only ask apps that take browser links`() {
        val url = "intent://open/#Intent;scheme=myapp;package=com.example;component=com.example/.Secret;" +
            "SEL;action=android.intent.action.DELETE;end"
        val intent = ExternalLinks.intentFor(url)!!
        assertNull(intent.component)
        assertNull(intent.selector)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals("com.example", intent.`package`)
    }

    @Test
    fun `an intent link's web fallback is used only when it is a web page`() {
        val web = "intent://x/#Intent;scheme=myapp;S.browser_fallback_url=https%3A%2F%2Fexample.com%2Fapp;end"
        assertEquals("https://example.com/app", ExternalLinks.fallbackOf(web))
        val sneaky = "intent://x/#Intent;scheme=myapp;S.browser_fallback_url=javascript%3Aalert(1);end"
        assertNull(ExternalLinks.fallbackOf(sneaky))
        assertNull(ExternalLinks.fallbackOf("tel:123"))
    }
}
