package com.chaya.app.browser

import android.view.View
import android.webkit.WebChromeClient
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Proves that retained WebView clients always use one newly composed callback
 * set instead of retaining a departed screen's ViewModel or fullscreen state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetainedWebViewCallbacksTest {

    /** Ensures page, navigation, progress, and fullscreen callbacks all switch destinations together after reattachment. */
    @Test
    fun `callback router atomically rebinds page and navigation callbacks`() {
        val firstDestinationEvents = mutableListOf<String>()
        val reattachedDestinationEvents = mutableListOf<String>()
        var receivedFullscreenView: View? = null
        var receivedFullscreenCallback: WebChromeClient.CustomViewCallback? = null
        val callbacks = RetainedWebViewCallbacks(
            RetainedWebViewCallbackState(
                onNavigationInvalidated = { firstDestinationEvents += "invalidated" },
                onPageStarted = { firstDestinationEvents += "started"; 1L },
                onPageFinished = { _, _, _ -> firstDestinationEvents += "finished" },
                onProgressChanged = { _, _ -> firstDestinationEvents += "progress" },
                onNavigationStateChanged = { _, _ -> firstDestinationEvents += "history" },
                onEnterFullscreen = { _, _ -> firstDestinationEvents += "fullscreen" },
                onExitFullscreen = { firstDestinationEvents += "exit-fullscreen" },
            ),
        )

        callbacks.update(
            RetainedWebViewCallbackState(
                onNavigationInvalidated = { reattachedDestinationEvents += "invalidated" },
                onPageStarted = { reattachedDestinationEvents += "started"; 2L },
                onPageFinished = { _, _, _ -> reattachedDestinationEvents += "finished" },
                onProgressChanged = { _, _ -> reattachedDestinationEvents += "progress" },
                onNavigationStateChanged = { _, _ -> reattachedDestinationEvents += "history" },
                onEnterFullscreen = { view, callback ->
                    reattachedDestinationEvents += "fullscreen"
                    receivedFullscreenView = view
                    receivedFullscreenCallback = callback
                },
                onExitFullscreen = { reattachedDestinationEvents += "exit-fullscreen" },
            ),
        )

        val fullscreenView = View(RuntimeEnvironment.getApplication())
        val fullscreenCallback = mockk<WebChromeClient.CustomViewCallback>(relaxed = true)
        callbacks.onNavigationInvalidated()
        val generation = callbacks.onPageStarted("https://reattached.example/watch")
        callbacks.onPageFinished("https://reattached.example/watch", "Reattached", generation)
        callbacks.onProgressChanged(75, generation)
        callbacks.onNavigationStateChanged(canGoBack = true, canGoForward = false)
        callbacks.onEnterFullscreen(fullscreenView, fullscreenCallback)
        callbacks.onExitFullscreen()

        assertTrue(firstDestinationEvents.isEmpty())
        assertEquals(2L, generation)
        assertSame(fullscreenView, receivedFullscreenView)
        assertSame(fullscreenCallback, receivedFullscreenCallback)
        assertEquals(
            listOf("invalidated", "started", "finished", "progress", "history", "fullscreen", "exit-fullscreen"),
            reattachedDestinationEvents,
        )
    }
}
