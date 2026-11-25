package com.chaya.app.browser

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chaya.app.MainActivity
import com.chaya.app.history.PageToOpen
import com.chaya.app.history.PagesToOpen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A page's *Web search* on chosen words searches in a new tab in Chaya, not in Google's app. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WebSearchTest {

    @After
    fun forgetThePage() {
        PagesToOpen.pending.value?.let(PagesToOpen::consume)
    }

    /** As WebView asks for it (Chromium's SelectionUtils.webSearch). */
    private fun webSearch(words: String) = Intent(Intent.ACTION_WEB_SEARCH)
        .putExtra(SearchManager.EXTRA_NEW_SEARCH, true)
        .putExtra(SearchManager.QUERY, words)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Test
    fun `a web search opens a new tab in Chaya and leaves the app for nothing`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.startActivity(webSearch("  slow morning ride "))

        assertNull("no other app is started", shadowOf(activity).nextStartedActivity)
        assertEquals(PageToOpen("slow morning ride", newTab = true), PagesToOpen.pending.value)
    }

    @Test
    fun `any other intent is started as asked`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
        activity.startActivity(view)

        assertEquals(Intent.ACTION_VIEW, shadowOf(activity).nextStartedActivity?.action)
        assertNull(PagesToOpen.pending.value)
    }

    @Test
    fun `only a web search with words in it counts`() {
        assertEquals("guitar lesson", webSearchOf(webSearch("guitar lesson")))
        assertNull(webSearchOf(webSearch("   ")))
        assertNull(webSearchOf(Intent(Intent.ACTION_WEB_SEARCH)))
        assertNull(webSearchOf(Intent(Intent.ACTION_VIEW).putExtra(SearchManager.QUERY, "guitar lesson")))
        assertNull(webSearchOf(null))
    }

    @Test
    fun `a page from History opens in the tab shown, a search in a tab of its own`() {
        PagesToOpen.open("https://example.com/")
        assertEquals(PageToOpen("https://example.com/", newTab = false), PagesToOpen.pending.value)
        PagesToOpen.openInNewTab("harbour at dusk")
        assertEquals(PageToOpen("harbour at dusk", newTab = true), PagesToOpen.pending.value)
    }
}
