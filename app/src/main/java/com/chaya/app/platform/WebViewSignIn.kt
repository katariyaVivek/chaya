package com.chaya.app.platform

import android.content.Context
import android.webkit.CookieManager
import java.io.File

/** Writes cookies in the Netscape file format that yt-dlp reads. */
object NetscapeCookies {

    /**
     * [headers] pairs a domain with a Cookie header value ("name=value; name2=value2"), which is how WebView
     * reports a site's cookies. Each cookie is written for that domain and its subdomains, for the whole site.
     * WebView does not say when a cookie expires, so each is written as a session cookie, which yt-dlp reads
     * and uses for its run.
     */
    fun format(headers: List<Pair<String, String>>): String = buildString {
        append("# Netscape HTTP Cookie File\n")
        for ((domain, header) in headers) {
            for (cookie in header.split(';')) {
                val equals = cookie.indexOf('=')
                if (equals <= 0) continue
                val name = cookie.substring(0, equals).trim()
                val value = cookie.substring(equals + 1).trim()
                if (name.isEmpty() || !name.isSafe() || !value.isSafe()) continue
                append('.').append(domain).append("\tTRUE\t/\tTRUE\t0\t").append(name).append('\t').append(value).append('\n')
            }
        }
    }

    /** A tab or line break would split the cookie across fields or lines. */
    private fun String.isSafe() = '\t' !in this && '\n' !in this && '\r' !in this
}

/**
 * The sign-in the browser here already has, read from WebView's cookies. Using it is always the person's
 * choice: yt-dlp's own guidance warns that sites can limit accounts that are used for automated downloads.
 */
class WebViewSignIn(private val context: Context) : SignIn {

    override fun isSignedIn(platform: Platform): Boolean {
        val markers = LOGIN_COOKIES[platform] ?: return false
        val names = cookieHeaders(platform)
            .flatMap { (_, header) -> header.split(';') }
            .map { it.substringBefore('=').trim() }
            .toSet()
        return markers.any { it in names }
    }

    override fun cookieFile(platform: Platform): File? {
        val headers = cookieHeaders(platform).filter { (_, header) -> header.isNotBlank() }
        if (headers.isEmpty()) return null
        val folder = File(context.cacheDir, "signin").apply { mkdirs() }
        return File.createTempFile("cookies-", ".txt", folder).apply { writeText(NetscapeCookies.format(headers)) }
    }

    /** The cookies WebView holds for each address of [platform], as (domain, header) pairs. */
    private fun cookieHeaders(platform: Platform): List<Pair<String, String>> =
        (SITES[platform] ?: emptyList()).map { domain ->
            domain to (runCatching { CookieManager.getInstance().getCookie("https://www.$domain") }.getOrNull() ?: "")
        }

    private companion object {
        val SITES = mapOf(
            Platform.YOUTUBE to listOf("youtube.com"),
            Platform.INSTAGRAM to listOf("instagram.com"),
            Platform.TIKTOK to listOf("tiktok.com"),
            Platform.TWITTER to listOf("x.com", "twitter.com"),
        )

        /** Cookies that exist only while signed in; their presence is how we tell. */
        val LOGIN_COOKIES = mapOf(
            Platform.YOUTUBE to setOf("SAPISID", "__Secure-3PAPISID", "SID"),
            Platform.INSTAGRAM to setOf("sessionid"),
            Platform.TIKTOK to setOf("sessionid", "sid_tt"),
            Platform.TWITTER to setOf("auth_token"),
        )
    }
}
