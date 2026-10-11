package com.chaya.app.browser

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaya.app.ChayaApplication
import com.chaya.app.detection.MediaBridge
import com.chaya.app.detection.MediaInterceptor
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A page's renderer that stops takes only the page with it, not the app. `chrome://crash` makes WebView's
 * renderer crash on purpose; before this was handled, Android ended the whole process (and this test with it).
 */
@RunWith(AndroidJUnit4::class)
class RendererGoneOnDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun aCrashedRendererIsReportedAndTheAppCarriesOn() {
        val app = instrumentation.targetContext.applicationContext as ChayaApplication
        val gone = CountDownLatch(1)
        lateinit var web: WebView
        instrumentation.runOnMainSync {
            web = createChayaWebView(
                ctx = instrumentation.targetContext,
                bridge = MediaBridge(onMediaDetected = { _, _ -> }),
                interceptor = MediaInterceptor { _, _ -> },
                adBlock = app.adBlocker.newSession(),
                detectorJs = "",
                callbacks = RetainedWebViewCallbacks(
                    RetainedWebViewCallbackState(
                        onNavigationInvalidated = {},
                        onPageStarted = { 1L },
                        onPageFinished = { _, _, _ -> },
                        onProgressChanged = { _, _ -> },
                        onNavigationStateChanged = { _, _ -> },
                        onEnterFullscreen = { _, _ -> },
                        onExitFullscreen = {},
                    ),
                ),
                onRendererGone = { gone.countDown() },
            )
            web.loadUrl("chrome://crash")
        }

        assertTrue("the renderer's loss was never reported", gone.await(30, TimeUnit.SECONDS))

        // Still here: a new WebView works after the old one's renderer went.
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.destroy()
            WebView(instrumentation.targetContext).apply {
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        loaded.countDown()
                    }
                }
                loadData("<p>back</p>", "text/html", null)
            }
        }
        assertTrue("a new WebView did not load after the crash", loaded.await(30, TimeUnit.SECONDS))
    }
}
