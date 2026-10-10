package com.chaya.app.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A page picked on the History or Bookmarks screen, held until the browser is on screen to open it in the tab
 * shown, as [com.chaya.app.SharedLinks] does for a link shared from another app.
 */
object PagesToOpen {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun open(url: String) {
        if (url.isNotBlank()) _pending.value = url
    }

    /** Marks [url] as opened, unless another page was picked meanwhile. */
    fun consume(url: String) {
        _pending.compareAndSet(url, null)
    }
}
