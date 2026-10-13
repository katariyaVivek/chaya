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
    private val served = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val port = server.port
                served += request.path!!.substringBefore('?')
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
                    // A player like 7movies': a stream, a playlist of seek thumbnails, and pieces named and typed
                    // like videos, all fetched by the player's own XHRs.
                    "/show" -> html(
                        "<html><head><title>Show page</title></head><body>" +
                            "<iframe src=\"http://localhost:$port/hls-player\" width=\"320\" height=\"180\"></iframe></body></html>"
                    )
                    "/hls-player" -> html(
                        "<html><body><script>" +
                            "var paths = ['/hls/master', '/hls/tiles.m3u8', '/hls/a/x7Gq', '/hls/a/k2Lp'];" +
                            "function next(i) { if (i >= paths.length) { document.title = 'loaded'; return; }" +
                            " var x = new XMLHttpRequest(); x.open('GET', paths[i]);" +
                            " x.responseType = i < 2 ? '' : 'arraybuffer';" +
                            " x.onloadend = function () { next(i + 1); }; x.send(); }" +
                            "next(0);" +
                            "</script></body></html>"
                    )
                    "/hls/master" -> MockResponse().setHeader("Content-Type", "text/plain")
                        .setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nmedia.m3u8\n")
                    "/hls/tiles.m3u8" -> MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl")
                        .setBody("#EXTM3U\n#EXTINF:10,\nt1.jpg\n#EXTINF:10,\nt2.jpg\n#EXT-X-ENDLIST\n")
                    "/hls/a/x7Gq" -> MockResponse().setHeader("Content-Type", "video/mp2t").setBody("G-piece")
                    "/hls/a/k2Lp" -> MockResponse().setHeader("Content-Type", "video/mp4").setBody("piece")
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
        val found = CopyOnWriteArrayList<DetectedMedia>()
        val detected = CountDownLatch(1)
        val web = load("/page") { media ->
            found += media
            if (media.url.contains("/stream/abc")) detected.countDown()
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

    /**
     * 7movies' player listed a "tiles" stream and a new "Video N" for every piece it played. Only the stream is
     * offered: not the playlist of seek thumbnails, and not the pieces, though their names and types say video.
     */
    @Test
    fun aFramesStreamIsOfferedWithoutItsPiecesOrThumbnails() {
        assumeTrue(
            "this WebView cannot run scripts in frames",
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT),
        )
        val found = CopyOnWriteArrayList<DetectedMedia>()
        val detected = CountDownLatch(1)
        val web = load("/show") { media ->
            found += media
            if (media.url.contains("/hls/master")) detected.countDown()
        }

        try {
            assertTrue("the frame's stream was never reported; found $found", detected.await(30, TimeUnit.SECONDS))
            // The pieces and thumbnails are fetched after the stream; give their reports time to arrive.
            val deadline = System.currentTimeMillis() + 15_000
            while ("/hls/a/k2Lp" !in served && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("the player never fetched its pieces; served $served", "/hls/a/k2Lp" in served)
            Thread.sleep(3_000)
            assertEquals(
                listOf("http://localhost:${server.port}/hls/master"),
                found.map { it.url }.distinct(),
            )
        } finally {
            instrumentation.runOnMainSync { web.destroy() }
        }
    }

    /** Opens [path] in a browser tab's WebView; [onMedia] hears what the app is told about. */
    private fun load(path: String, onMedia: (DetectedMedia) -> Unit): WebView {
        val context = instrumentation.targetContext
        val app = context.applicationContext as ChayaApplication
        val assets = context.assets
        val detectorJs = assets.open("detection/chaya_media_detector.js").bufferedReader().readText()
        val frameAgentJs = assets.open(FRAME_AGENT_ASSET).bufferedReader().readText()
        lateinit var web: WebView
        instrumentation.runOnMainSync {
            val interceptor = MediaInterceptor { _, _ -> }
            val bridge = MediaBridge(
                onMediaDetected = { media, _ -> onMedia(media) },
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
            web.loadUrl("http://127.0.0.1:${server.port}$path")
        }
        return web
    }
}
