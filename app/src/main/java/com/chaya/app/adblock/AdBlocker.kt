package com.chaya.app.adblock

import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.chaya.app.adblock.engine.DomainResolver
import com.chaya.app.adblock.engine.FilterEngine
import com.chaya.app.adblock.engine.PageContext
import com.chaya.app.adblock.engine.RequestType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Chaya's ad blocker: one per app. It holds the rules from [FilterLists] and the person's [AdBlockSettings];
 * each browser view asks it through its own [AdBlockSession].
 */
class AdBlocker(
    val settings: AdBlockSettings,
    val lists: FilterLists,
    internal val resolver: DomainResolver = PublicSuffixSites,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _engine = MutableStateFlow<FilterEngine?>(null)
    /** The rules in use; null until the lists have been read. */
    val engine: StateFlow<FilterEngine?> = _engine.asStateFlow()

    private val started = AtomicBoolean(false)

    /**
     * Reads the lists in the background, then fetches fresh copies when a week has passed. Called when the
     * browser first shows; later calls do nothing. Pages load normally until the lists are read.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            runCatching { lists.buildEngine(resolver) }.getOrNull()?.let { _engine.value = it }
            if (lists.updateIfDue()) {
                runCatching { lists.buildEngine(resolver) }.getOrNull()?.let { _engine.value = it }
            }
        }
    }

    fun newSession(): AdBlockSession = AdBlockSession(this)

    internal fun useEngine(engine: FilterEngine) {
        _engine.value = engine
    }
}

/**
 * The ad blocker for one browser view: the page it shows, and how much was blocked there. Called from the
 * WebView's network thread for each request, and from the main thread when a page starts.
 */
class AdBlockSession internal constructor(private val blocker: AdBlocker) {
    @Volatile private var pageUrl: String? = null
    @Volatile private var page: PageContext? = null
    @Volatile private var pageEngine: FilterEngine? = null

    private val _blocked = MutableStateFlow(0)
    /** Requests blocked on the page shown now. */
    val blocked: StateFlow<Int> = _blocked.asStateFlow()

    private val _site = MutableStateFlow("")
    /** The site of the page shown now (example.com), which the person can let show ads. */
    val site: StateFlow<String> = _site.asStateFlow()

    /** A new page is loading: count afresh, and work out what the lists say about it. */
    fun beginPage(url: String) {
        pageUrl = url
        page = null
        pageEngine = null
        _blocked.value = 0
        val host = FilterEngine.hostOf(url)
        _site.value = if (host.isEmpty() || !url.startsWith("http")) "" else blocker.resolver.siteOf(host)
        contextOf(blocker.engine.value)
    }

    /** The page and the rules to judge it by, when ads are blocked on it; null to let everything through. */
    private fun active(): Pair<FilterEngine, PageContext>? {
        val engine = blocker.engine.value ?: return null
        val context = page?.takeIf { pageEngine === engine } ?: contextOf(engine) ?: return null
        if (!blocker.settings.blocksOn(context.site)) return null
        return engine to context
    }

    private fun contextOf(engine: FilterEngine?): PageContext? {
        val url = pageUrl ?: return null
        if (engine == null || !url.startsWith("http")) return null
        return engine.page(url).also {
            page = it
            pageEngine = engine
        }
    }

    /** Whether to block a request for [url] of [type]; counts it when blocked. */
    fun shouldBlock(url: String, type: Int): Boolean {
        val (engine, context) = active() ?: return false
        val block = engine.shouldBlock(url, type, context)
        if (block) _blocked.update { it + 1 }
        return block
    }

    /** For WebViewClient.shouldInterceptRequest: an empty answer for a blocked request, null to load it. Pages themselves are never blocked. */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (request.isForMainFrame) return null
        val url = request.url?.toString() ?: return null
        return if (shouldBlock(url, RequestTypes.of(url, request.requestHeaders.orEmpty()))) blockedResponse() else null
    }

    /** Whether a new window for [url] is an ad pop-up. */
    fun shouldBlockPopup(url: String): Boolean {
        val (engine, context) = active() ?: return false
        val block = engine.shouldBlockPopup(url, context)
        if (block) _blocked.update { it + 1 }
        return block
    }

    /** A script that hides the page's ad boxes, for the page's main document; null when nothing is hidden there. */
    fun cosmeticScript(): String? {
        val (engine, context) = active() ?: return null
        val css = engine.pageCss(context)
        val generic = engine.hidesGenerically(context)
        if (css.isEmpty() && !generic) return null
        return CosmeticScript.build(css, generic)
    }

    /** CSS hiding the rules for every site that start with one of [tokens] (`.class`, `#id`). */
    fun genericCss(tokens: List<String>): String {
        val (engine, context) = active() ?: return ""
        return engine.genericCss(context, tokens)
    }

    private fun blockedResponse() =
        WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}

/**
 * What the page script asks for: the CSS for the classes and ids it found. Exposed to pages as `ChayaCosmetic`;
 * it hands out only selectors from the public ad lists, so any frame may call it.
 */
class CosmeticBridge(private val session: AdBlockSession) {
    @JavascriptInterface
    fun generic(tokensJson: String?): String {
        if (tokensJson.isNullOrEmpty() || tokensJson.length > MAX_REQUEST_CHARS) return ""
        val tokens = runCatching {
            val array = JSONArray(tokensJson)
            List(minOf(array.length(), MAX_TOKENS)) { array.optString(it) }.filter { it.length in 2..MAX_TOKEN_CHARS }
        }.getOrNull() ?: return ""
        return session.genericCss(tokens)
    }

    private companion object {
        const val MAX_REQUEST_CHARS = 200_000
        const val MAX_TOKENS = 4_000
        const val MAX_TOKEN_CHARS = 200
    }
}

/** The script that adds the hiding CSS to a page and keeps asking for more as the page adds elements. */
internal object CosmeticScript {
    fun build(css: String, generic: Boolean): String =
        TEMPLATE.replace("%CSS%", JSONObject.quote(css)).replace("%GENERIC%", generic.toString())

    private val TEMPLATE = """
        (function () {
          if (window.__chayaHide) return;
          Object.defineProperty(window, '__chayaHide', { value: true });
          var css = %CSS%;
          var generic = %GENERIC%;
          function add(text) {
            if (!text) return;
            var style = document.createElement('style');
            style.textContent = text;
            (document.head || document.documentElement).appendChild(style);
          }
          function start() {
            add(css);
            var bridge = window.ChayaCosmetic;
            if (!generic || !bridge) return;
            var seen = Object.create(null), pending = [], timer = 0;
            function note(el) {
              if (el.id && !seen['#' + el.id]) { seen['#' + el.id] = 1; pending.push('#' + el.id); }
              var list = el.classList;
              if (list) for (var i = 0; i < list.length; i++) {
                var name = '.' + list[i];
                if (!seen[name]) { seen[name] = 1; pending.push(name); }
              }
            }
            function scan(root) {
              note(root);
              if (root.querySelectorAll) {
                var found = root.querySelectorAll('[id],[class]');
                for (var i = 0; i < found.length; i++) note(found[i]);
              }
            }
            function flush() {
              timer = 0;
              if (!pending.length) return;
              var batch = pending;
              pending = [];
              try { add(bridge.generic(JSON.stringify(batch))); } catch (e) {}
            }
            scan(document.documentElement);
            flush();
            new MutationObserver(function (mutations) {
              for (var i = 0; i < mutations.length; i++) {
                var added = mutations[i].addedNodes;
                for (var j = 0; j < added.length; j++) if (added[j].nodeType === 1) scan(added[j]);
              }
              if (pending.length && !timer) timer = setTimeout(flush, 150);
            }).observe(document.documentElement, { childList: true, subtree: true });
          }
          if (document.documentElement) start();
          else document.addEventListener('DOMContentLoaded', start);
        })();
    """.trimIndent()
}

/**
 * What a WebView request is for, from what it shows: the Sec-Fetch-Dest and Accept headers when present, else
 * the address's file extension. [RequestType.UNKNOWN] when nothing tells.
 */
object RequestTypes {
    fun of(url: String, headers: Map<String, String>): Int {
        val dest = header(headers, "Sec-Fetch-Dest")
        when (dest) {
            "script", "worker", "sharedworker", "serviceworker" -> return RequestType.SCRIPT
            "image" -> return RequestType.IMAGE
            "style" -> return RequestType.STYLESHEET
            "iframe", "frame" -> return RequestType.SUBDOCUMENT
            "font" -> return RequestType.FONT
            "audio", "video", "track" -> return RequestType.MEDIA
            "object", "embed" -> return RequestType.OBJECT
            "empty" -> return RequestType.XHR
        }
        val accept = header(headers, "Accept").orEmpty()
        when {
            accept.startsWith("text/css") -> return RequestType.STYLESHEET
            accept.startsWith("image/") -> return RequestType.IMAGE
            accept.contains("text/html") -> return RequestType.SUBDOCUMENT
        }
        val path = url.substringBefore('#').substringBefore('?').substringAfter("://").substringAfter('/', "")
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "js", "mjs" -> RequestType.SCRIPT
            "css" -> RequestType.STYLESHEET
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp" -> RequestType.IMAGE
            "woff", "woff2", "ttf", "otf", "eot" -> RequestType.FONT
            "mp4", "webm", "m4a", "m4v", "mp3", "ogg", "oga", "opus", "wav", "m3u8", "mpd", "m4s" -> RequestType.MEDIA
            "html", "htm" -> RequestType.SUBDOCUMENT
            else -> RequestType.UNKNOWN
        }
    }

    private fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()?.lowercase(Locale.ROOT)
}
