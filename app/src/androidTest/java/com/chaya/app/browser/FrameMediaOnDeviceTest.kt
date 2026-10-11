package com.chaya.app.browser

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import com.chaya.app.ChayaApplication
import com.chaya.app.detection.MediaBridge
import com.chaya.app.detection.MediaInterceptor
import com.chaya.app.model.DetectedMedia
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A player embedded from another server (another origin: `localhost` inside a page from `127.0.0.1`) loads a
 * stream whose address says nothing and whose answer claims to be text. The agent inside the frame recognizes the
 * manifest, the page's detector relays it, and the app takes it because the browser really requested it.
 */
@RunWith(AndroidJUnit4::class)
class FrameMediaOnDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val port = server.port
                return when (request.path!!.substringBefore('?')) {
                    "/page" -> html(
                        "<html><head><title>Show page</title></head><body><h1>Episode 1</h1>" +
                            "<iframe src=\"http://localhost:$port/player\" width=\"320\" height=\"180\"></iframe></body></html>"
                    )
                    "/player" -> html(
                        "<html><body><script>" +
                            "fetch('/stream/abc?t=1').then(function (r) { return r.text(); })" +
                            ".then(function () { document.title = 'loaded'; });" +
                            "</script></body></html>"
                    )
                    "/stream/abc" -> MockResponse().setHeader("Content-Type", "text/plain")
                        .setBody("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun html(body: String) = MockResponse().setHeader("Content-Type", "text/html").setBody(body)

    @Test
    fun aManifestLoadedInsideAFrameFromAnotherServerIsDetected() {
        assumeTrue(
            "this WebView cannot run scripts in frames",
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT),
        )
        val context = instrumentation.targetContext
        val app = context.applicationContext as ChayaApplication
        val assets = context.assets
        val detectorJs = assets.open("detection/chaya_media_detector.js").bufferedReader().readText()
        val frameAgentJs = assets.open(FRAME_AGENT_ASSET).bufferedReader().readText()
        val found = CopyOnWriteArrayList<DetectedMedia>()
        val detected = CountDownLatch(1)
        lateinit var web: WebView

        instrumentation.runOnMainSync {
            val interceptor = MediaInterceptor { _, _ -> }
            val bridge = MediaBridge(
                onMediaDetected = { media, _ ->
                    found += media
                    if (media.url.contains("/stream/abc")) detected.countDown()
                },
                wasRequested = interceptor::wasRequested,
            )
            web = createChayaWebView(
                ctx = context,
                bridge = bridge,
                interceptor = interceptor,
                adBlock = app.adBlocker.newSession(),
                detectorJs = detectorJs,
                frameAgentJs = frameAgentJs,
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
            web.loadUrl("http://127.0.0.1:${server.port}/page")
        }

        try {
            assertTrue("the frame's stream was never reported; found $found", detected.await(30, TimeUnit.SECONDS))
            val media = found.first { it.url.contains("/stream/abc") }
            assertEquals("http://localhost:${server.port}/stream/abc?t=1", media.url)
            assertEquals("http://127.0.0.1:${server.port}/page", media.pageUrl)
            assertEquals("http://localhost:${server.port}/player", media.refererUrl)
            assertEquals("application/vnd.apple.mpegurl", media.mimeType)
        } finally {
            instrumentation.runOnMainSync { web.destroy() }
        }
    }
}
