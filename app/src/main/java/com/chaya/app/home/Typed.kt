package com.chaya.app.home

import com.chaya.app.platform.PlatformMatcher
import com.chaya.app.platform.ProfileMatcher

/**
 * What Home's "+" does with what the person typed or pasted, as the browser would take it: a link to a video, post,
 * pin or account is looked up (as a link shared from another app is, [com.chaya.app.SharedLinks]); anything else,
 * an address or words to search for, opens in the browser as the address bar opens it
 * ([com.chaya.app.history.PagesToOpen]).
 */
sealed interface Typed {
    val text: String

    data class LookUp(override val text: String) : Typed

    data class Open(override val text: String) : Typed

    companion object {
        private val LINK_IN_TEXT = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

        /** Null for nothing at all. */
        fun of(input: String): Typed? {
            val text = input.trim()
            if (text.isEmpty()) return null
            val link = LINK_IN_TEXT.find(text)?.value?.trimEnd('.', ',', ')', ']', '>', '"', '\'') ?: text
            val known = PlatformMatcher.match(link) != null || ProfileMatcher.match(link) != null
            return if (known) LookUp(text) else Open(text)
        }
    }
}
