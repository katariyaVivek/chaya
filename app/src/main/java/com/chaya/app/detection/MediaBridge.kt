package com.chaya.app.detection

import android.webkit.JavascriptInterface
import com.chaya.app.model.DetectedMedia
import com.chaya.app.model.DetectionSource
import java.util.Locale

/**
 * JavaScript interface injected into WebView pages.
 *
 * Exposed as `window.ChayaBridge` — DOM detection scripts call
 * `ChayaBridge.onMediaDetected(url, tagName)` to report media elements
 * found in the page.
 */
class MediaBridge(
    private val onMediaDetected: (DetectedMedia) -> Unit
) {
    @JavascriptInterface
    fun onMediaDetected(url: String, tagName: String) {
        val mimeType = mimeTypeForTag(tagName)
        val media = DetectedMedia(
            url = url,
            pageUrl = null,
            mimeType = mimeType,
            contentLength = null,
            source = DetectionSource.DOM
        )
        onMediaDetected(media)
    }

    private fun mimeTypeForTag(tag: String): String? {
        return when (tag.uppercase(Locale.ROOT)) {
            "VIDEO" -> "video/mp4"
            "AUDIO" -> "audio/mpeg"
            "SOURCE" -> null  // let extension detection handle it
            else -> null
        }
    }
}
