package com.chaya.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A link shared to Chaya from another app, held until the browser screen is on screen to pick it up.
 * [MainActivity] puts it here; the browser takes it from here, so the share works whether the app was
 * closed, open on the browser, or open on the downloads list.
 */
object SharedLinks {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /** [text] is whatever the sharing app sent, often a sentence with a link in it. */
    fun offer(text: String) {
        if (text.isNotBlank()) _pending.value = text
    }

    /** Marks [text] as taken, unless a newer share has arrived meanwhile. */
    fun consume(text: String) {
        _pending.compareAndSet(text, null)
    }
}
