package com.chaya.app.adblock

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaya.app.adblock.engine.FilterEngine
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A real WebView loading a page from a local server, wired the way the browser wires it: the ad's request
 * never reaches the server, the page's own files do, and the ad boxes are hidden, both by a rule for the site
 * and by a rule for every site that the page script asks for by class name.
 */
@RunWith(AndroidJUnit4::class)
class AdBlockOnDeviceTest {

    private val server = MockWebServer()
    private val served = CopyOnWriteArrayList<String>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var webView: WebView

    private val page = """
        <html><head><title>News</title></head><body>
        <img src="/ads/banner.png" width="10" height="10">
        <img src="/content/photo.png" width="10" height="10">
        <div class="site-sponsor">sponsor</div>
        <div class="story">story</div>
        <script>
          setTimeout(function () {
            var late = document.createElement('div');
            late.className = 'ad-banner';
            late.textContent = 'late ad';
            document.body.appendChild(late);
          }, 300);
        </script>
        </body></html>
    """.trimIndent()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                served += request.path.orEmpty()
                return when (request.path) {
                    "/news" -> MockResponse().setHeader("Content-Type", "text/html").setBody(page)
                    else -> MockResponse().setHeader("Content-Type", "image/png").setBody("png")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { webView.destroy() }
        server.shutdown()
    }

    @Test
    fun anAdIsNeverRequestedAndAdBoxesAreHidden() {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("adblock-device-test", 0).apply { edit().clear().commit() }
        val host = server.hostName
        val blocker = AdBlocker(
            AdBlockSettings(prefs),
            FilterLists(File(context.cacheDir, "unused"), { error("unused") }, prefs),
        )
        blocker.useEngine(
            FilterEngine.Builder(PublicSuffixSites).apply {
                listOf("/ads/banner.", "$host##.site-sponsor", "##.ad-banner").forEach(::add)
            }.build(),
        )
        val session = blocker.newSession()
        val finished = CountDownLatch(1)

        instrumentation.runOnMainSync {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                addJavascriptInterface(CosmeticBridge(session), "ChayaCosmetic")
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        session.beginPage(url)
                        session.cosmeticScript()?.let { view.evaluateJavascript(it, null) }
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        session.cosmeticScript()?.let { view.evaluateJavascript(it, null) }
                        finished.countDown()
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        session.intercept(request)
                }
                loadUrl(server.url("/news").toString())
            }
        }
        assertTrue("the page loads", finished.await(30, TimeUnit.SECONDS))

        assertEquals("none", waitForDisplay(".site-sponsor", "none"))
        assertEquals("none", waitForDisplay(".ad-banner", "none"))
        assertEquals("block", waitForDisplay(".story", "block"))
        assertTrue("the page's own picture loads: $served", served.contains("/content/photo.png"))
        assertFalse("the ad is never asked for: $served", served.any { it.startsWith("/ads/") })
        assertEquals(1, session.blocked.value)
    }

    /** The computed display of [selector], once it is [expected] or ten seconds have passed. */
    private fun waitForDisplay(selector: String, expected: String): String {
        var last = ""
        repeat(100) {
            val latch = CountDownLatch(1)
            instrumentation.runOnMainSync {
                webView.evaluateJavascript(
                    "(function(){var e=document.querySelector('$selector');return e?getComputedStyle(e).display:'missing';})()",
                ) { value ->
                    last = value.trim('"')
                    latch.countDown()
                }
            }
            latch.await(5, TimeUnit.SECONDS)
            if (last == expected) return last
            Thread.sleep(100)
        }
        return last
    }
}
