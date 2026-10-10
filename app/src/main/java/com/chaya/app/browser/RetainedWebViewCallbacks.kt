package com.chaya.app.browser

import android.view.View
import android.webkit.WebChromeClient
import java.util.concurrent.atomic.AtomicReference

/** Holds every callback that must follow the currently composed browser destination. */
internal data class RetainedWebViewCallbackState(
    val onNavigationInvalidated: () -> Unit,
    val onPageStarted: (String) -> Long,
    val onPageFinished: (String, String, Long) -> Unit,
    val onProgressChanged: (Int, Long) -> Unit,
    val onNavigationStateChanged: (Boolean, Boolean) -> Unit,
    val onEnterFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit,
    val onExitFullscreen: () -> Unit,
    /** The page's address changed, possibly without a new document (a video site moving to another video). */
    val onAddressChanged: (String) -> Unit = {},
    /** The page asked for a link to open in a new window, on the person's tap. */
    val onOpenInNewTab: (String) -> Unit = {},
)

/** Rebinds retained WebView callbacks so they never retain an obsolete Compose destination. */
internal class RetainedWebViewCallbacks(initialState: RetainedWebViewCallbackState) {
    /** Publishes an entire replacement callback set atomically to avoid mixing destination ownership. */
    private val state = AtomicReference(initialState)

    /** Replaces callbacks whenever BrowserScreen is recreated around the existing WebView instance. */
    fun update(newState: RetainedWebViewCallbackState) {
        state.set(newState)
    }

    /** Stops page-scoped work before a top-level navigation has completed its new-page callback. */
    fun onNavigationInvalidated() {
        state.get().onNavigationInvalidated()
    }

    /** Starts the current destination's document state and returns its unique generation identity. */
    fun onPageStarted(url: String): Long = state.get().onPageStarted(url)

    /** Completes a page only for the destination currently attached to the retained WebView. */
    fun onPageFinished(url: String, title: String, navigationGeneration: Long) {
        state.get().onPageFinished(url, title, navigationGeneration)
    }

    /** Updates loading progress only for the current document generation. */
    fun onProgressChanged(progress: Int, navigationGeneration: Long) {
        state.get().onProgressChanged(progress, navigationGeneration)
    }

    /** Updates browser history controls in the currently composed destination. */
    fun onNavigationStateChanged(canGoBack: Boolean, canGoForward: Boolean) {
        state.get().onNavigationStateChanged(canGoBack, canGoForward)
    }

    /** Reports the address the page now shows, to the destination currently attached to the retained WebView. */
    fun onAddressChanged(url: String) {
        state.get().onAddressChanged(url)
    }

    /** Opens [url] in a new tab, for a link the page wanted in a new window. */
    fun onOpenInNewTab(url: String) {
        state.get().onOpenInNewTab(url)
    }

    /** Delivers fullscreen entry to the screen that is currently rendering the retained WebView. */
    fun onEnterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        state.get().onEnterFullscreen(view, callback)
    }

    /** Delivers fullscreen exit to the screen that is currently rendering the retained WebView. */
    fun onExitFullscreen() {
        state.get().onExitFullscreen()
    }
}
