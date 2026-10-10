package com.chaya.app.browser

import com.chaya.app.adblock.AdBlockSettings
import com.chaya.app.adblock.AdBlocker
import com.chaya.app.adblock.FilterLists
import com.chaya.app.detection.MediaBridge
import com.chaya.app.detection.MediaInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The list of tabs, and what a tab that is not shown keeps of its page while it loads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserTabsTest {

    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("tabs-test", 0)
    private val adBlocker = AdBlocker(AdBlockSettings(prefs), FilterLists(File("unused"), { error("unused") }, prefs))

    private fun BrowserTabs.open(): BrowserTab = BrowserTab(
        id = nextId(),
        interceptor = MediaInterceptor { _, _ -> },
        bridge = MediaBridge(onMediaDetected = { _, _ -> }),
        adBlock = adBlocker.newSession(),
        callbacks = RetainedWebViewCallbacks(backgroundState()),
    ).also { add(it) }

    private fun backgroundState() = RetainedWebViewCallbackState(
        onNavigationInvalidated = {},
        onPageStarted = { 0L },
        onPageFinished = { _, _, _ -> },
        onProgressChanged = { _, _ -> },
        onNavigationStateChanged = { _, _ -> },
        onEnterFullscreen = { _, _ -> },
        onExitFullscreen = {},
    )

    @Test
    fun `a new tab goes just after the one shown`() {
        val tabs = BrowserTabs()
        val first = tabs.open()
        tabs.activate(first.id)
        val second = tabs.open()
        tabs.activate(first.id)
        val third = tabs.open()

        assertEquals(listOf(first.id, third.id, second.id), tabs.all.map { it.id })
    }

    @Test
    fun `closing the tab shown hands over to the next one, else the one before`() {
        val tabs = BrowserTabs()
        val (a, b, c) = List(3) { tabs.open() }
        tabs.activate(b.id)

        assertEquals(c.id, tabs.remove(b.id)?.id)
        assertNull("nothing is shown until the caller shows the next tab", tabs.active)
        tabs.activate(c.id)
        assertEquals(a.id, tabs.remove(c.id)?.id)
        tabs.activate(a.id)
        assertNull("the last one leaves nothing to show", tabs.remove(a.id))
        assertEquals(0, tabs.size)
    }

    @Test
    fun `closing a tab that is not shown changes nothing else`() {
        val tabs = BrowserTabs()
        val (a, b) = List(2) { tabs.open() }
        tabs.activate(a.id)

        assertNull(tabs.remove(b.id))
        assertEquals(a.id, tabs.active?.id)
    }

    @Test
    fun `there is a limit to how many tabs can be open`() {
        val tabs = BrowserTabs(maxTabs = 2)
        tabs.open()
        assertTrue(tabs.canOpenMore)
        tabs.open()
        assertFalse(tabs.canOpenMore)
    }

    @Test
    fun `every change is announced, so the tab list redraws`() {
        val tabs = BrowserTabs()
        val before = tabs.version.value
        val tab = tabs.open()
        tabs.activate(tab.id)
        tabs.remove(tab.id)
        assertEquals(before + 3, tabs.version.value)
    }

    @Test
    fun `a tab not shown keeps where its page got to, and its documents keep their own identities`() {
        val tabs = BrowserTabs()
        val tab = tabs.open()
        tab.saved = BrowserUiState()
        var generation = 40L
        var changes = 0
        val opened = mutableListOf<String>()
        val callbacks = backgroundCallbacks(tab, { ++generation }, { changes++ }, { opened += it })

        val started = callbacks.onPageStarted("https://news.example/a")
        callbacks.onProgressChanged(60, started)
        callbacks.onNavigationStateChanged(true, false)
        assertEquals(41L, started)
        assertEquals("https://news.example/a", tab.saved?.url)
        assertEquals(true, tab.saved?.isLoading)
        assertEquals(60, tab.saved?.progress)
        assertFalse(tab.saved!!.homeVisible)

        callbacks.onPageFinished("https://news.example/a", "A story", started - 1)
        assertEquals("a report from an older document changes nothing", "", tab.saved?.pageTitle)
        callbacks.onPageFinished("https://news.example/a", "A story", started)
        assertEquals("A story", tab.saved?.pageTitle)
        assertEquals(false, tab.saved?.isLoading)
        assertTrue(tab.saved!!.canGoBack)
        assertEquals(3, changes)

        callbacks.onOpenInNewTab("https://other.example/")
        assertEquals(listOf("https://other.example/"), opened)
    }

    @Test
    fun `what the tab list shows`() {
        assertEquals(TabSummary(1, "", ""), TabSummary.of(1, BrowserUiState()))
        assertTrue(TabSummary.of(1, BrowserUiState()).isStartScreen)
        val page = BrowserUiState(url = "https://www.news.example/a", homeVisible = false)
        assertEquals("news.example", TabSummary.of(2, page).title)
        assertEquals("A story", TabSummary.of(2, page.copy(pageTitle = "A story")).title)
    }
}
