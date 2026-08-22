package com.chaya.app.detection

import android.webkit.JavascriptInterface
import com.chaya.app.model.DetectedMedia
import com.chaya.app.model.DetectionSource
import java.util.Locale

/**
 * JavaScript interface injected into WebView pages as `window.ChayaBridge`.
 *
 * The injected detector calls [onMediaDetectedWithType] to report media
 * elements found in the DOM. Only http(s) URLs are accepted — blob:/data:
 * URLs cannot be downloaded and would flood the list.
 */
class MediaBridge(
    /** Supplies the URL of the page currently loaded in the WebView. */
    private val currentPageUrl: () -> String? = { null },
    private val onMediaDetected: (DetectedMedia) -> Unit
) {
    @JavascriptInterface
    fun onMediaDetected(url: String, tagName: String) {
        report(url, tagName, null)
    }

    @JavascriptInterface
    fun onMediaDetectedWithType(url: String, tagName: String, typeAttr: String) {
        report(url, tagName, typeAttr)
    }

    private fun report(rawUrl: String, tagName: String, typeAttr: String?) {
        val url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return

        // <source type="video/mp4; codecs=..."> — take the bare MIME part.
        val mime = typeAttr?.trim()?.takeIf { it.isNotEmpty() }
            ?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
            ?: mimeTypeForTag(tagName)

        onMediaDetected(
            DetectedMedia(
                url = url,
                pageUrl = currentPageUrl(),
                mimeType = mime,
                source = DetectionSource.DOM
            )
        )
    }

    private fun mimeTypeForTag(tag: String): String? {
        return when (tag.uppercase(Locale.ROOT)) {
            "VIDEO" -> "video/mp4"
            "AUDIO" -> "audio/mpeg"
            else -> null  // SOURCE / others — let extension detection handle it
        }
    }
}
