package com.chaya.app.browser

import android.webkit.WebView
import com.chaya.app.adblock.AdBlockSession
import com.chaya.app.detection.MediaBridge
import com.chaya.app.detection.MediaInterceptor
import com.chaya.app.detection.MediaUrlClassifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One tab: its WebView and the page helpers that WebView's clients captured. Only the tab shown reports to
 * the browser's state; the others keep their own page state in [saved] until they are shown again.
 */
internal class BrowserTab(
    val id: Long,
    val interceptor: MediaInterceptor,
    val bridge: MediaBridge,
    val adBlock: AdBlockSession,
    val callbacks: RetainedWebViewCallbacks,
) {
    var webView: WebView? = null

    /** The browser state of this tab while another one is shown; null while this one is. */
    var saved: BrowserUiState? = null
}

/** What the tab list shows for one tab. */
data class TabSummary(
    val id: Long,
    /** The page's title, else its site; empty for the start screen. */
    val title: String,
    val url: String,
) {
    val isStartScreen: Boolean get() = url.isEmpty()

    companion object {
        fun of(id: Long, state: BrowserUiState): TabSummary {
            val url = if (state.homeVisible) "" else state.url
            val title = when {
                url.isEmpty() -> ""
                state.pageTitle.isNotBlank() -> state.pageTitle
                else -> MediaUrlClassifier.hostOf(url)?.removePrefix("www.") ?: url
            }
            return TabSummary(id, title, url)
        }
    }
}

/**
 * The open tabs, in the order they sit in the list, and which one is shown. Lives as long as the app's
 * process, as the single WebView did before tabs. Used from the main thread only.
 */
internal class BrowserTabs(val maxTabs: Int = MAX_TABS) {
    private val tabs = ArrayList<BrowserTab>()
    private var lastId = 0L

    /** Changes whenever a tab opens, closes, is shown, or a tab not shown changes its page. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    var activeId: Long = NONE
        private set

    val all: List<BrowserTab> get() = tabs.toList()
    val size: Int get() = tabs.size
    val active: BrowserTab? get() = tabs.firstOrNull { it.id == activeId }
    val canOpenMore: Boolean get() = tabs.size < maxTabs

    fun nextId(): Long = ++lastId

    /** Adds [tab] just after the tab shown, as browsers place a tab opened from a page. */
    fun add(tab: BrowserTab) {
        val at = tabs.indexOfFirst { it.id == activeId }
        if (at < 0) tabs += tab else tabs.add(at + 1, tab)
        changed()
    }

    fun activate(id: Long) {
        if (tabs.any { it.id == id }) activeId = id
        changed()
    }

    /**
     * Removes the tab [id]. When it was the one shown, nothing is shown until the caller shows the tab this
     * returns: the one after it, else the one before. Null when it was not shown or no tab is left.
     */
    fun remove(id: Long): BrowserTab? {
        val at = tabs.indexOfFirst { it.id == id }
        if (at < 0) return null
        tabs.removeAt(at)
        changed()
        if (id != activeId) return null
        activeId = NONE
        return tabs.getOrNull(at) ?: tabs.getOrNull(at - 1)
    }

    fun changed() {
        _version.update { it + 1 }
    }

    companion object {
        /** Each tab keeps a whole WebView in memory; past this many a phone starts to struggle. */
        const val MAX_TABS = 10
        const val NONE = -1L
    }
}

/**
 * Callbacks for a tab that is not shown: its page keeps loading, and what the browser would show for it is
 * kept in [BrowserTab.saved], so showing the tab later shows where it got to. Media it finds is not kept; the
 * page is looked at again when it is shown and reloaded.
 */
internal fun backgroundCallbacks(
    tab: BrowserTab,
    nextGeneration: () -> Long,
    onChanged: () -> Unit,
    onOpenInNewTab: (String) -> Unit,
) = RetainedWebViewCallbackState(
    onNavigationInvalidated = {},
    onPageStarted = { url ->
        val generation = nextGeneration()
        tab.saved = (tab.saved ?: BrowserUiState()).startedPage(url, generation)
        onChanged()
        generation
    },
    onPageFinished = { url, title, generation ->
        tab.saved = tab.saved?.finishedPage(url, title, generation)
        onChanged()
    },
    onProgressChanged = { progress, generation -> tab.saved = tab.saved?.withProgress(progress, generation) },
    onNavigationStateChanged = { back, forward ->
        tab.saved = tab.saved?.copy(canGoBack = back, canGoForward = forward)
    },
    // A tab not shown cannot take over the screen.
    onEnterFullscreen = { _, callback -> callback.onCustomViewHidden() },
    onExitFullscreen = {},
    onOpenInNewTab = onOpenInNewTab,
)
