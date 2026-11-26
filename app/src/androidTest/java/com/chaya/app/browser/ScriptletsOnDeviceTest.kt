package com.chaya.app.browser

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import com.chaya.app.adblock.AdBlockSettings
import com.chaya.app.adblock.AdBlocker
import com.chaya.app.adblock.FilterLists
import com.chaya.app.adblock.PublicSuffixSites
import com.chaya.app.adblock.engine.FilterEngine
import com.chaya.app.detection.MediaBridge
import com.chaya.app.detection.MediaInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The ad lists' scriptlets run in a real WebView before the page's own scripts: a page that writes its player's ads
 * into ytInitialPlayerResponse inline, as YouTube's does, finds them gone when it reads them back.
 */
@RunWith(AndroidJUnit4::class)
class ScriptletsOnDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/html").setBody(
                "<html><head><script>" +
                    "var ytInitialPlayerResponse = { adPlacements: [1, 2], videoDetails: { videoId: 'a' } };" +
                    "document.title = String(ytInitialPlayerResponse.adPlacements) + '|' + ytInitialPlayerResponse.videoDetails.videoId;" +
                    "</script></head><body>Watch</body></html>",
            ),
        )
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun aPlayersAdsAreGoneBeforeThePageReadsThem() {
        assumeTrue(
            "this WebView cannot run scripts at the start of a document",
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT),
        )
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("scriptlets-device-test", 0).apply { edit().clear().commit() }
        val blocker = AdBlocker(AdBlockSettings(prefs), FilterLists(File(context.cacheDir, "unused"), { error("unused") }, prefs))
        blocker.useEngine(
            FilterEngine.Builder(PublicSuffixSites)
                .apply { add("127.0.0.1##+js(set, ytInitialPlayerResponse.adPlacements, undefined)") }
                .build(),
        )
        val scriptletJs = context.assets.open(SCRIPTLETS_ASSET).bufferedReader().readText()
        val title = AtomicReference<String>()
        val finished = CountDownLatch(1)
        lateinit var web: WebView
        instrumentation.runOnMainSync {
            val interceptor = MediaInterceptor { _, _ -> }
            web = createChayaWebView(
                ctx = context,
                bridge = MediaBridge(onMediaDetected = { _, _ -> }, wasRequested = interceptor::wasRequested),
                interceptor = interceptor,
                adBlock = blocker.newSession(),
                detectorJs = "",
                scriptletJs = scriptletJs,
                callbacks = RetainedWebViewCallbacks(
                    RetainedWebViewCallbackState(
                        onNavigationInvalidated = {},
                        onPageStarted = { 1L },
                        onPageFinished = { _, pageTitle, _ ->
                            title.set(pageTitle)
                            finished.countDown()
                        },
                        onProgressChanged = { _, _ -> },
                        onNavigationStateChanged = { _, _ -> },
                        onEnterFullscreen = { _, _ -> },
                        onExitFullscreen = {},
                    ),
                ),
            )
            web.loadUrl("http://127.0.0.1:${server.port}/watch")
        }
        try {
            assertTrue("the page never finished", finished.await(30, TimeUnit.SECONDS))
            assertEquals("undefined|a", title.get())
        } finally {
            instrumentation.runOnMainSync { web.destroy() }
        }
    }
}
