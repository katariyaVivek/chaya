package com.chaya.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The address and title a page shows when it moves within itself, as YouTube, Instagram, X and TikTok do between
 * videos and posts without loading a new document. The bar, bookmarks, sharing, history and the saved tab kept the
 * first page's address and title, so the wrong page was starred, shared, and opened again after a restart.
 */
class PageAddressTest {

    private val first = "https://m.youtube.com/watch?v=first"
    private val second = "https://m.youtube.com/watch?v=second"

    private fun loaded(url: String = first) = BrowserUiState().startedPage(url, navigationGeneration = 3)

    @Test
    fun `a page that moves within itself shows its new address, while its document stays the one loaded`() {
        val moved = loaded().movedWithin(second)
        assertEquals(second, moved.address)
        assertEquals(first, moved.url)
        assertEquals(3, moved.navigationGeneration)
    }

    @Test
    fun `moving back to the document's own address shows it again`() {
        val back = loaded().movedWithin(second).movedWithin(first)
        assertEquals(first, back.address)
        assertNull(back.movedTo)
    }

    @Test
    fun `the start screen, an empty tab and addresses that are not web pages do not move`() {
        val start = BrowserUiState()
        assertSame(start, start.movedWithin(second))
        val page = loaded()
        assertSame(page, page.movedWithin("about:blank"))
        assertSame(page, page.movedWithin("javascript:void(0)"))
    }

    @Test
    fun `a new document starts at its own address, forgetting where the last one moved`() {
        val next = loaded().movedWithin(second).startedPage("https://example.com/", navigationGeneration = 4)
        assertEquals("https://example.com/", next.address)
        assertNull(next.movedTo)
    }

    @Test
    fun `a page that moved while loading still finishes`() {
        // It was left loading forever: the finish names the address it moved to, not the one it started at
        val finished = loaded().movedWithin(second).finishedPage(second, "Second", navigationGeneration = 3)
        assertFalse(finished.isLoading)
        assertEquals("Second", finished.pageTitle)
        assertEquals(second, finished.address)
    }

    @Test
    fun `a finish from an older document changes nothing`() {
        val page = loaded()
        assertSame(page, page.finishedPage(first, "Old", navigationGeneration = 2))
        assertSame(page, page.finishedPage("https://elsewhere.example/", "Elsewhere", navigationGeneration = 3))
    }

    @Test
    fun `a page's new title is shown, but not a blank one or one from an older document`() {
        val page = loaded().finishedPage(first, "First video", navigationGeneration = 3)
        assertEquals("Second video", page.withTitle("Second video", navigationGeneration = 3).pageTitle)
        assertEquals("First video", page.withTitle(" ", navigationGeneration = 3).pageTitle)
        assertEquals("First video", page.withTitle("Late", navigationGeneration = 2).pageTitle)
        // A finish with no title keeps the one the page gave
        assertEquals("First video", page.finishedPage(first, "", navigationGeneration = 3).pageTitle)
    }

    @Test
    fun `a tab's card shows where its page moved`() {
        val summary = TabSummary.of(1, loaded().movedWithin(second).withTitle("Second video", 3))
        assertEquals(second, summary.url)
        assertEquals("Second video", summary.title)
    }
}
