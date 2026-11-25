package com.chaya.app.browser

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * The words of a web search, from a page's *Web search* on chosen words: WebView hands them to Android as
 * [Intent.ACTION_WEB_SEARCH], which would open Google's app instead of a tab in Chaya. Null for any other intent.
 */
internal fun webSearchOf(intent: Intent?): String? =
    intent?.takeIf { it.action == Intent.ACTION_WEB_SEARCH }
        ?.getStringExtra(SearchManager.QUERY)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/** A web search for [words], as WebView starts one (Chromium's SelectionUtils.webSearch). */
internal fun webSearchIntent(words: String): Intent = Intent(Intent.ACTION_WEB_SEARCH)
    .putExtra(SearchManager.EXTRA_NEW_SEARCH, true)
    .putExtra(SearchManager.QUERY, words)

/**
 * Whether an app on the phone takes web searches. WebView offers its own *Web search* only then (its
 * AwActionModeCallback asks the same, from this app); without one, Chaya offers it instead.
 */
internal fun Context.hasWebSearchApp(): Boolean {
    val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.EXTRA_NEW_SEARCH, true)
    val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
    } else {
        @Suppress("DEPRECATION") // The only form before Android 13
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }
    return apps.isNotEmpty()
}
