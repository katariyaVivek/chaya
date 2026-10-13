package com.chaya.app.browser

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaya.app.ChayaApplication
import com.chaya.app.detection.MediaBridge
import com.chaya.app.detection.MediaInterceptor
import com.chaya.app.detection.PageMeta
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A show's page titled with the site's slogan, its name set in a styled div above the player (no h1, no "title"
 * class), as 7movies does: the detector still reports the name, so the stream and the saved file carry it.
 */
@RunWith(AndroidJUnit4::class)
class PageNameOnDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/html").setBody(
                "<html><head><title>Watch Movies &amp; TV Shows Online in HD</title>" +
                    "<meta name=\"viewport\" content=\"width=device-width\"></head>" +
                    "<body style=\"margin:0;background:#000;color:#fff;font-family:sans-serif\">" +
                    "<div style=\"display:flex;gap:12px;padding:16px\"><button style=\"font-size:20px\">&larr;</button>" +
                    "<div><div style=\"font-size:30px;font-weight:700\">The Mentalist</div>" +
                    "<div style=\"font-size:14px\">S1 &middot; E1 &mdash; Pilot</div></div>" +
                    "<span style=\"font-size:14px\">Ads</span></div>" +
                    "<div style=\"height:240px;background:#333\"></div>" +
                    "<p style=\"font-size:13px\">Having issues? Switch server.</p>" +
                    "<h2 style=\"font-size:48px;margin-top:1200px\">Episodes</h2></body></html>"
            )
        )
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun aNameSetInAStyledDivAboveThePlayerIsReported() {
        val context = instrumentation.targetContext
        val app = context.applicationContext as ChayaApplication
        val detectorJs = context.assets.open("detection/chaya_media_detector.js").bufferedReader().readText()
        val reported = CopyOnWriteArrayList<PageMeta>()

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var web: WebView
            scenario.onActivity { activity ->
                val interceptor = MediaInterceptor { _, _ -> }
                web = createChayaWebView(
                    ctx = activity,
                    bridge = MediaBridge(onMediaDetected = { _, _ -> }, onPageMeta = { meta, _ -> reported += meta }),
                    interceptor = interceptor,
                    adBlock = app.adBlocker.newSession(),
                    detectorJs = detectorJs,
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
                )
                // Laid out on screen: the name is found by where it sits and how big it is.
                activity.setContentView(web)
                web.loadUrl(server.url("/tv/1/watch").toString())
            }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (reported.none { it.heading != null } && System.nanoTime() < deadline) Thread.sleep(100)

            assertEquals("The Mentalist", reported.lastOrNull { it.heading != null }?.heading)
            scenario.onActivity { web.destroy() }
        }
    }
}
