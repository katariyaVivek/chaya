package com.chaya.app.browser

import android.app.SearchManager
import android.content.Intent

/**
 * The words of a web search, from a page's *Web search* on chosen words: WebView hands them to Android as
 * [Intent.ACTION_WEB_SEARCH], which would open Google's app instead of a tab in Chaya. Null for any other intent.
 */
internal fun webSearchOf(intent: Intent?): String? =
    intent?.takeIf { it.action == Intent.ACTION_WEB_SEARCH }
        ?.getStringExtra(SearchManager.QUERY)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
