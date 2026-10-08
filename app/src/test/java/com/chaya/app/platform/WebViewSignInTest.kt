package com.chaya.app.platform

import android.webkit.CookieManager
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

class NetscapeCookiesTest {

    @Test
    fun `each cookie becomes a line for the whole site and its subdomains`() {
        val text = NetscapeCookies.format(listOf("instagram.com" to "sessionid=abc123; csrftoken=xyz"))

        assertEquals(
            "# Netscape HTTP Cookie File\n" +
                ".instagram.com\tTRUE\t/\tTRUE\t0\tsessionid\tabc123\n" +
                ".instagram.com\tTRUE\t/\tTRUE\t0\tcsrftoken\txyz\n",
            text,
        )
    }

    @Test
    fun `several domains each keep their own cookies`() {
        val text = NetscapeCookies.format(listOf("x.com" to "auth_token=1", "twitter.com" to "auth_token=2"))

        assertTrue(".x.com\tTRUE\t/\tTRUE\t0\tauth_token\t1" in text)
        assertTrue(".twitter.com\tTRUE\t/\tTRUE\t0\tauth_token\t2" in text)
    }

    @Test
    fun `a value may contain equals signs`() {
        val text = NetscapeCookies.format(listOf("tiktok.com" to "tt_chain_token=abc==; sid_tt=def"))

        assertTrue("\ttt_chain_token\tabc==\n" in text)
    }

    @Test
    fun `junk and unsafe entries are left out instead of corrupting the file`() {
        val text = NetscapeCookies.format(listOf("a.com" to "ok=1; =novalue; justtext; bad\tname=2; good=3; evil=x\ny"))

        val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(listOf(".a.com\tTRUE\t/\tTRUE\t0\tok\t1", ".a.com\tTRUE\t/\tTRUE\t0\tgood\t3"), lines)
    }

    @Test
    fun `no cookies is just the header line`() {
        assertEquals("# Netscape HTTP Cookie File\n", NetscapeCookies.format(listOf("a.com" to "")))
        assertEquals("# Netscape HTTP Cookie File\n", NetscapeCookies.format(emptyList()))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebViewSignInTest {

    private val files = mutableListOf<File>()

    @Before
    fun startWithNoCookies() {
        CookieManager.getInstance().removeAllCookies(null)
    }

    @After
    fun cleanUp() {
        files.forEach { it.delete() }
        CookieManager.getInstance().removeAllCookies(null)
    }

    private fun signIn() = WebViewSignIn(RuntimeEnvironment.getApplication())

    @Test
    fun `a browser with no cookies is not signed in anywhere`() {
        Platform.entries.forEach { assertFalse("$it", signIn().isSignedIn(it)) }
        assertNull(signIn().cookieFile(Platform.YOUTUBE))
    }

    @Test
    fun `a login cookie means signed in, and ordinary cookies do not`() {
        CookieManager.getInstance().setCookie("https://www.instagram.com", "sessionid=abc")
        CookieManager.getInstance().setCookie("https://www.youtube.com", "VISITOR_INFO1_LIVE=anon; PREF=x")

        assertTrue(signIn().isSignedIn(Platform.INSTAGRAM))
        assertFalse("visitor cookies are not a sign-in", signIn().isSignedIn(Platform.YOUTUBE))
        assertFalse(signIn().isSignedIn(Platform.TIKTOK))
    }

    @Test
    fun `the cookie file holds the session in the format yt-dlp reads`() {
        CookieManager.getInstance().setCookie("https://www.instagram.com", "sessionid=abc")

        val file = signIn().cookieFile(Platform.INSTAGRAM)!!.also { files += it }

        assertTrue(file.exists())
        val text = file.readText()
        assertTrue(text.startsWith("# Netscape HTTP Cookie File"))
        assertTrue(".instagram.com\tTRUE\t/\tTRUE\t0\tsessionid\tabc" in text)
    }

    @Test
    fun `a cookie file is a fresh temporary file each time`() {
        CookieManager.getInstance().setCookie("https://www.instagram.com", "sessionid=abc")

        val first = signIn().cookieFile(Platform.INSTAGRAM)!!.also { files += it }
        val second = signIn().cookieFile(Platform.INSTAGRAM)!!.also { files += it }

        assertTrue(first.path != second.path)
    }
}
