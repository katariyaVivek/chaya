package com.chaya.app.browser

import android.content.Intent
import androidx.core.net.toUri
import java.util.Locale

/**
 * Where a page's navigation goes when its address is not a web page: `tel:`, `mailto:`, `market:`, `intent:` and
 * every app's own scheme. A browser opens these in their app when the person tapped for them, and otherwise does
 * nothing: an ad sending the page to the Play Store or another app on its own is stopped, and a link a person tapped
 * no longer ends on WebView's "net::ERR_UNKNOWN_URL_SCHEME" page.
 */
internal object ExternalLinks {

    enum class Route {
        /** WebView loads it, as it does every web address. */
        WEB,

        /** An app opens it: the person asked. */
        APP,

        /** Nothing happens: the page asked on its own. */
        BLOCK,
    }

    /** What WebView itself loads. Everything else belongs to an app. */
    private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript", "file")

    /** How long after a tap a redirect still counts as what the tap asked for (a "Get the app" link via a tracker). */
    const val TAP_LASTS_MILLIS = 5_000L

    /**
     * Where [url] goes. [hasGesture] is whether the person tapped for this navigation; a server's redirect carries no
     * tap of its own, so it counts as asked for when a tap came [sinceTapMillis] ago, within [TAP_LASTS_MILLIS].
     */
    fun route(url: String, hasGesture: Boolean, isRedirect: Boolean, sinceTapMillis: Long?): Route {
        val scheme = schemeOf(url) ?: return Route.WEB
        if (scheme in WEB_SCHEMES) return Route.WEB
        val asked = hasGesture || (isRedirect && sinceTapMillis != null && sinceTapMillis in 0..TAP_LASTS_MILLIS)
        return if (asked) Route.APP else Route.BLOCK
    }

    /** The scheme of [url], lower case; null when it has none. */
    fun schemeOf(url: String): String? {
        val colon = url.indexOf(':')
        if (colon <= 0) return null
        val scheme = url.substring(0, colon)
        if (!scheme[0].isLetter() || !scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
        return scheme.lowercase(Locale.ROOT)
    }

    /**
     * The intent that opens [url] in an app, made safe as browsers make it: only apps that take links from a browser
     * (BROWSABLE), and no named component or selector, so a page cannot start any part of any app it likes. Null
     * when an `intent:` address cannot be read.
     */
    fun intentFor(url: String): Intent? {
        val intent = if (schemeOf(url) == "intent") {
            runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
        } else {
            Intent(Intent.ACTION_VIEW, url.toUri())
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    /** The web page an `intent:` link names for when no app takes it (`S.browser_fallback_url`), http(s) only. */
    fun fallbackOf(url: String): String? {
        if (schemeOf(url) != "intent") return null
        val fallback = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull()
            ?.getStringExtra("browser_fallback_url") ?: return null
        return fallback.takeIf { schemeOf(it) == "http" || schemeOf(it) == "https" }
    }
}
