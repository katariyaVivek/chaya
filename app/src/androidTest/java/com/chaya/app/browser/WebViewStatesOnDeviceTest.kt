package com.chaya.app.browser

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A tab's back and forward history survives its WebView: saved as bytes ([WebViewStates.save]), as a tab is
 * discarded or the app goes to the background, then given to a brand new WebView, as a restored tab gets.
 */
@RunWith(AndroidJUnit4::class)
class WebViewStatesOnDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val name = request.path!!.trim('/').ifEmpty { "home" }
                return MockResponse().setHeader("Content-Type", "text/html")
                    .setBody("<html><head><title>Page $name</title></head><body><h1>$name</h1></body></html>")
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** A WebView on the main thread that counts down [loaded] each time a page finishes. */
    private class Loading {
        @Volatile
        var loaded = CountDownLatch(1)
    }

    private fun webView(loading: Loading): WebView {
        lateinit var web: WebView
        instrumentation.runOnMainSync {
            web = WebView(instrumentation.targetContext).apply {
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        loading.loaded.countDown()
                    }
                }
            }
        }
        return web
    }

    private fun load(web: WebView, loading: Loading, url: String) {
        loading.loaded = CountDownLatch(1)
        instrumentation.runOnMainSync { web.loadUrl(url) }
        assertTrue("$url did not load", loading.loaded.await(30, TimeUnit.SECONDS))
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    @Test
    fun theBackHistoryComesBackInANewWebView() {
        val first = Loading()
        val original = webView(first)
        load(original, first, server.url("/a").toString())
        load(original, first, server.url("/b").toString())
        load(original, first, server.url("/c").toString())
        // Back one page, so there is a forward page too.
        first.loaded = CountDownLatch(1)
        instrumentation.runOnMainSync { original.goBack() }
        assertTrue(first.loaded.await(30, TimeUnit.SECONDS))

        val bytes = onMain { WebViewStates.save(original) }
        assertNotNull(bytes)
        instrumentation.runOnMainSync { original.destroy() }

        val second = Loading()
        val restored = webView(second)
        assertTrue(onMain { WebViewStates.restore(restored, bytes!!) })
        assertTrue("the restored page did not load", second.loaded.await(30, TimeUnit.SECONDS))

        val history = onMain { restored.copyBackForwardList() }
        assertEquals(3, history.size)
        assertEquals(server.url("/b").toString(), history.currentItem?.url)
        assertTrue(onMain { restored.canGoBack() })
        assertTrue(onMain { restored.canGoForward() })

        second.loaded = CountDownLatch(1)
        instrumentation.runOnMainSync { restored.goBack() }
        assertTrue(second.loaded.await(30, TimeUnit.SECONDS))
        assertEquals(server.url("/a").toString(), onMain { restored.url })
        instrumentation.runOnMainSync { restored.destroy() }
    }

    @Test
    fun stateThatCannotBeReadIsRefusedSoTheTabOpensItsAddressInstead() {
        val loading = Loading()
        val web = webView(loading)

        assertFalse(onMain { WebViewStates.restore(web, byteArrayOf(1, 2, 3, 4)) })
        instrumentation.runOnMainSync { web.destroy() }
    }
}
