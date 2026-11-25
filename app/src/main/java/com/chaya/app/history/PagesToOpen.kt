package com.chaya.app.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the browser is asked to open: an address or words to search for, in the tab shown or a tab of its own. */
data class PageToOpen(val text: String, val newTab: Boolean = false)

/**
 * A page picked on the History or Bookmarks screen, or words a page's *Web search* asked for, held until the browser
 * is on screen to open it, as [com.chaya.app.SharedLinks] does for a link shared from another app.
 */
object PagesToOpen {
    private val _pending = MutableStateFlow<PageToOpen?>(null)
    val pending: StateFlow<PageToOpen?> = _pending.asStateFlow()

    /** Opens [url] in the tab shown. */
    fun open(url: String) = offer(PageToOpen(url))

    /** Opens [text], an address or words to search for, in a new tab. */
    fun openInNewTab(text: String) = offer(PageToOpen(text, newTab = true))

    private fun offer(page: PageToOpen) {
        if (page.text.isNotBlank()) _pending.value = page
    }

    /** Marks [page] as opened, unless another was asked for meanwhile. */
    fun consume(page: PageToOpen) {
        _pending.compareAndSet(page, null)
    }
}
