package com.chaya.app.detection

import android.webkit.JavascriptInterface
import com.chaya.app.model.DetectedMedia
import com.chaya.app.model.DetectionSource
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Keeps each main-document bridge capability beyond practical guessing reach. */
private const val NAVIGATION_CAPABILITY_BYTES = 32

/** Bounds native verification work even when a page repeatedly reports unique endpoints. */
private const val MAX_THOROUGH_SCAN_CANDIDATES = 10

/** Rebinds media destinations safely when the retained WebView returns to its browser screen. */
private data class MediaBridgeCallbacks(
    val onMediaDetected: (DetectedMedia, Long) -> Unit,
    val onMediaCandidate: (DetectedMedia, Long) -> Unit,
)

/** Enforces the user's scan consent in native code instead of trusting an arbitrary page script. */
private class MediaBridgeScanState {
    /** Records whether the visible browser UI has authorized deeper verification for this document. */
    private val thoroughScanAuthorized = AtomicBoolean(false)

    /** Deduplicates native verification requests independently of JavaScript's mutable page state. */
    private val reservedCandidateUrls = ConcurrentHashMap.newKeySet<String>()

    /** Prevents one opted-in document from consuming unbounded network and coroutine resources. */
    private val reservedCandidateCount = AtomicInteger(0)

    /** Enables deeper verification only after BrowserScreen receives the user's explicit action. */
    fun authorizeThoroughScan() {
        thoroughScanAuthorized.set(true)
    }

    /** Reserves one unique candidate only when native consent and the per-page limit both permit it. */
    fun reserveCandidate(url: String): Boolean {
        val normalizedUrl = url.substringBefore('#')
        if (!thoroughScanAuthorized.get() || !reservedCandidateUrls.add(normalizedUrl)) return false

        while (true) {
            val currentCount = reservedCandidateCount.get()
            if (currentCount >= MAX_THOROUGH_SCAN_CANDIDATES) {
                reservedCandidateUrls.remove(normalizedUrl)
                return false
            }
            if (reservedCandidateCount.compareAndSet(currentCount, currentCount + 1)) {
                return true
            }
        }
    }
}

/** Binds a token and native consent state to one top-level document generation. */
private data class MediaBridgeSession(
    val capability: String,
    val pageUrl: String,
    val navigationGeneration: Long,
    val scanState: MediaBridgeScanState = MediaBridgeScanState(),
)

/**
 * Receives media reports from the injected main-document detector without
 * trusting calls made by arbitrary WebView frames.
 *
 * `addJavascriptInterface` exposes this object to every frame, including
 * cross-origin iframes. Every exposed callback therefore requires a random
 * capability that BrowserScreen injects only into the active top-level page.
 */
class MediaBridge(
    onMediaDetected: (DetectedMedia, Long) -> Unit,
    onMediaCandidate: (DetectedMedia, Long) -> Unit = { _, _ -> },
) {
    /** Produces unpredictable tokens so iframe guesses cannot invoke native code. */
    private val secureRandom = SecureRandom()

    /** Rebinds media destinations safely when Compose reattaches the retained WebView. */
    private val callbacks = AtomicReference(
        MediaBridgeCallbacks(onMediaDetected, onMediaCandidate),
    )

    /** Publishes one capability, source page, and native scan state across WebView callback threads. */
    private val activeSession = AtomicReference<MediaBridgeSession?>(null)

    /** Starts a document boundary and returns the sole capability accepted for that exact generation. */
    fun beginNavigation(pageUrl: String, navigationGeneration: Long): String {
        val bytes = ByteArray(NAVIGATION_CAPABILITY_BYTES)
        secureRandom.nextBytes(bytes)
        val capability = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)
        activeSession.set(
            MediaBridgeSession(
                capability = capability,
                pageUrl = pageUrl,
                navigationGeneration = navigationGeneration,
            )
        )
        return capability
    }

    /** Immediately rejects reports from the document being navigated away from. */
    fun invalidateNavigation() {
        activeSession.set(null)
    }

    /** Authorizes native fetch/XHR verification only for the document represented by the tapped affordance. */
    fun enableThoroughScan(pageUrl: String, navigationGeneration: Long): Boolean {
        val session = activeSession.get() ?: return false
        if (session.pageUrl != pageUrl || session.navigationGeneration != navigationGeneration) {
            return false
        }

        session.scanState.authorizeThoroughScan()
        return activeSession.get() === session
    }

    /** Replaces stale destination callbacks after the singleton WebView returns to BrowserScreen. */
    fun updateCallbacks(
        onMediaDetected: (DetectedMedia, Long) -> Unit,
        onMediaCandidate: (DetectedMedia, Long) -> Unit,
    ) {
        callbacks.set(MediaBridgeCallbacks(onMediaDetected, onMediaCandidate))
    }

    /** Accepts DOM reports only when their document holds the active capability. */
    @JavascriptInterface
    fun onMediaDetected(capability: String?, url: String?, tagName: String?) {
        val session = activeSessionFor(capability) ?: return
        report(session, url, tagName, null)
    }

    /** Preserves MIME hints from DOM, fetch, and XHR reports behind the capability boundary. */
    @JavascriptInterface
    fun onMediaDetectedWithType(
        capability: String?,
        url: String?,
        tagName: String?,
        typeAttr: String?,
    ) {
        val session = activeSessionFor(capability) ?: return
        report(session, url, tagName, typeAttr)
    }

    /** Routes an opted-in unknown endpoint to native MIME verification only from its own page origin. */
    @JavascriptInterface
    fun onMediaCandidate(capability: String?, rawUrl: String?, source: String?) {
        val session = activeSessionFor(capability) ?: return

        val url = rawUrl?.trim()?.substringBefore('#')?.takeIf(::isHttpUrl) ?: return
        val isFetchOrXhr = source?.equals("fetch", ignoreCase = true) == true ||
            source?.equals("xhr", ignoreCase = true) == true

        if (!isFetchOrXhr || !isSameOrigin(session.pageUrl, url)) return
        if (!session.scanState.reserveCandidate(url) || activeSession.get() !== session) return

        callbacks.get().onMediaCandidate(
            DetectedMedia(
                url = url,
                pageUrl = session.pageUrl,
                mimeType = null,
                source = DetectionSource.XHR_FETCH,
            ),
            session.navigationGeneration,
        )
    }

    /** Avoids comparing malformed token sizes while preserving constant-time checks for viable capabilities. */
    private fun activeSessionFor(capability: String?): MediaBridgeSession? {
        val active = activeSession.get() ?: return null
        val provided = capability ?: return null
        if (provided.length != active.capability.length) return null

        return active.takeIf {
            MessageDigest.isEqual(
                it.capability.toByteArray(StandardCharsets.US_ASCII),
                provided.toByteArray(StandardCharsets.US_ASCII),
            )
        }
    }

    /** Creates a detected-media record only for directly downloadable HTTP(S) targets. */
    private fun report(
        session: MediaBridgeSession,
        rawUrl: String?,
        tagName: String?,
        typeAttr: String?,
    ) {
        val url = rawUrl?.trim()?.takeIf(::isHttpUrl) ?: return

        val mime = typeAttr?.trim()?.takeIf { it.isNotEmpty() }
            ?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
            ?: mimeTypeForTag(tagName)

        if (activeSession.get() !== session) return

        callbacks.get().onMediaDetected(
            DetectedMedia(
                url = url,
                pageUrl = session.pageUrl,
                mimeType = mime,
                source = detectionSourceFor(tagName),
            ),
            session.navigationGeneration,
        )
    }

    /** Rejects malformed, non-web, and hostless targets before they can reach download state. */
    private fun isHttpUrl(url: String): Boolean {
        val parsed = runCatching { URI(url) }.getOrNull() ?: return false
        return parsed.host != null &&
            (parsed.scheme.equals("http", ignoreCase = true) ||
                parsed.scheme.equals("https", ignoreCase = true))
    }

    /** Labels network-hook reports separately from DOM discoveries in the media sheet. */
    private fun detectionSourceFor(tagName: String?): DetectionSource {
        return when (tagName?.uppercase(Locale.ROOT)) {
            "FETCH", "XHR" -> DetectionSource.XHR_FETCH
            else -> DetectionSource.DOM
        }
    }

    /** Restricts authenticated verification requests to the active document's exact HTTP origin. */
    private fun isSameOrigin(pageUrl: String, targetUrl: String): Boolean {
        val page = runCatching { URI(pageUrl) }.getOrNull() ?: return false
        val target = runCatching { URI(targetUrl) }.getOrNull() ?: return false
        val pageHost = page.host ?: return false
        val targetHost = target.host ?: return false

        if (!page.scheme.equals(target.scheme, ignoreCase = true)) return false
        if (!pageHost.equals(targetHost, ignoreCase = true)) return false
        return effectivePort(page) == effectivePort(target)
    }

    /** Normalizes default HTTP(S) ports so equivalent serialized origins remain equal. */
    private fun effectivePort(uri: URI): Int {
        if (uri.port != -1) return uri.port
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
    }

    /** Supplies conservative MIME hints for plain video and audio tags lacking a type attribute. */
    private fun mimeTypeForTag(tagName: String?): String? {
        return when (tagName?.uppercase(Locale.ROOT)) {
            "VIDEO" -> "video/mp4"
            "AUDIO" -> "audio/mpeg"
            else -> null
        }
    }
}
