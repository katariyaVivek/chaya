package com.chaya.app.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnAttach
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.chaya.app.ChayaApplication
import com.chaya.app.SharedLinks
import com.chaya.app.adblock.AdBlockSession
import com.chaya.app.adblock.CosmeticBridge
import com.chaya.app.detection.MediaBridge
import com.chaya.app.detection.MediaInterceptor
import com.chaya.app.detection.MediaNamer
import com.chaya.app.detection.MediaRanker
import com.chaya.app.detection.RankedMedia
import com.chaya.app.history.BrowsingRecord
import com.chaya.app.history.PagesToOpen
import com.chaya.app.home.BarAction
import com.chaya.app.home.BarMiddle
import com.chaya.app.home.ChayaBar
import com.chaya.app.home.HomeShelf
import com.chaya.app.home.Place
import com.chaya.app.history.Suggestion
import com.chaya.app.model.DetectedMedia
import com.chaya.app.model.DetectionSource
import com.chaya.app.model.MediaKind
import com.chaya.app.platform.LinkState
import com.chaya.app.platform.Platform
import com.chaya.app.platform.PlatformChoice
import com.chaya.app.ui.components.AdBlockSheet
import com.chaya.app.ui.components.DetectedMediaSheet
import com.chaya.app.ui.components.NotificationRationaleSheet
import com.chaya.app.ui.components.PlatformSheet
import com.chaya.app.ui.components.ProfileSheet
import com.chaya.app.ui.components.QualitySelectorSheet
import com.chaya.app.ui.components.TabGrid
import com.chaya.app.ui.theme.ChayaMotion
import com.chaya.app.ui.theme.LocalChayaColors
import com.chaya.app.ui.theme.pressScale
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Holds the open tabs across navigation so each browsing session (history, page, cookies) survives trips
 * to the Downloads screen and back.
 */
private object WebViewHolder {
    val tabs = BrowserTabs()
}

/** One lane for tab files, so a later save never lands before an earlier one. */
private val tabDisk = Dispatchers.IO.limitedParallelism(1)

/** The script that runs inside embedded frames ([createChayaWebView]'s frameAgentJs). */
internal const val FRAME_AGENT_ASSET = "detection/chaya_frame_agent.js"

/** How long the page must stay put before the tabs are saved, so a burst of changes saves once. */
private const val TAB_SAVE_SETTLE_MILLIS = 500L

/** How long typing must pause before the address bar looks for suggestions. */
private const val SUGGEST_AFTER_MILLIS = 150L

/** Bookmarks shown among the start screen's quick sites; the rest are on the Bookmarks screen. */
private const val START_SCREEN_BOOKMARKS = 8

@OptIn(ExperimentalLayoutApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    /** Chaya's places, from the bar: Home and the Library. The browser's own start page is a new tab's, never Home. */
    onPlace: (Place) -> Unit,
    onNavigateToHistory: () -> Unit = {},
    onNavigateToBookmarks: () -> Unit = {},
    viewModel: BrowserViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val linkState by viewModel.linkState.collectAsState()
    var urlInput by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Fullscreen video state (WebChromeClient custom view)
    var customView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember {
        mutableStateOf<WebChromeClient.CustomViewCallback?>(null)
    }

    fun hideSystemBars() {
        runCatching {
            val window = (context as? Activity)?.window ?: return
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    fun showSystemBars() {
        runCatching {
            val window = (context as? Activity)?.window ?: return
            WindowInsetsControllerCompat(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        customView = view
        customViewCallback = callback
        hideSystemBars()
    }

    fun exitFullscreen() {
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        customView = null
        showSystemBars()
    }

    // Sync input when page loads
    LaunchedEffect(uiState.url) {
        if (uiState.url.isNotEmpty()) {
            urlInput = uiState.url
        }
    }

    // Back: exit fullscreen first, then WebView history, then default (exit app)
    BackHandler(enabled = customView != null) { exitFullscreen() }

    // A download asked for before notifications were allowed takes two steps. First a one-line sentence
    // (BrowserSheet.Notifications, Phase 0.5): the download is not obviously notification-related, so explain
    // first, and "Not now" still downloads. Then, after Allow, Android's own question, which this waits for.
    var awaitingPermission by remember { mutableStateOf<AskedDownload?>(null) }

    /** Starts [asked] and says so; [notificationsOn] false says it runs without notifications. */
    fun startAsked(asked: AskedDownload, notificationsOn: Boolean) {
        fun say(message: String) {
            scope.launch { snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short) }
        }
        when (asked) {
            is AskedDownload.File -> {
                viewModel.downloadMedia(asked.media)
                say(if (notificationsOn) "Downloading ${downloadLabel(asked.media)}" else "Downloading (notifications off)")
            }
            is AskedDownload.LinkQuality -> viewModel.downloadLink(asked.choice) { label ->
                say(if (notificationsOn) "Downloading $label" else "Downloading (notifications off)")
            }
        }
    }

    // First-run hint (Phase 4.5): shown once, persisted in SharedPreferences.
    // Plain SharedPreferences — one boolean, no DataStore dependency warranted.
    var showOnboardingHint by remember {
        mutableStateOf(
            context.getSharedPreferences("chaya_prefs", android.content.Context.MODE_PRIVATE)
                .getBoolean("onboarding_seen", true)
                .not()
        )
    }
    fun dismissOnboardingHint() {
        showOnboardingHint = false
        context.getSharedPreferences("chaya_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_seen", true).apply()
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        awaitingPermission?.let { asked ->
            awaitingPermission = null
            startAsked(asked, notificationsOn = granted)
        }
    }

    val adBlocker = remember { (context.applicationContext as ChayaApplication).adBlocker }
    val app = remember { context.applicationContext as ChayaApplication }
    val tabStore = remember { app.tabStore }
    val pageBookmarked by remember(uiState.url) { app.browsing.isBookmarked(uiState.url) }.collectAsState(initial = false)
    val bookmarkTiles by remember {
        app.browsing.bookmarkRows.map { rows -> rows.take(START_SCREEN_BOOKMARKS).map { QuickSite.of(it.url, it.title) } }
    }.collectAsState(initial = emptyList())
    var suggestions by remember { mutableStateOf(emptyList<Suggestion>()) }
    LaunchedEffect(urlInput) {
        // A short pause, so a fast typist does not query on every letter.
        delay(SUGGEST_AFTER_MILLIS)
        suggestions = if (urlInput.isBlank()) emptyList() else app.browsing.suggestions(urlInput)
    }

    /** Tab files are written off the main thread, one at a time and in order. */
    fun onTabDisk(work: () -> Unit) {
        app.appScope.launch(tabDisk) { runCatching(work) }
    }
    LaunchedEffect(Unit) { adBlocker.start() }
    val detectorJs = remember {
        runCatching {
            context.assets.open("detection/chaya_media_detector.js")
                .bufferedReader().readText()
        }.getOrDefault("")
    }
    val frameAgentJs = remember {
        runCatching { context.assets.open(FRAME_AGENT_ASSET).bufferedReader().readText() }.getOrDefault("")
    }

    val tabs = WebViewHolder.tabs
    // Read so that opening, closing or showing a tab, or a page loading in a tab not shown, recomposes.
    @Suppress("UNUSED_VARIABLE")
    val tabsVersion = tabs.version.collectAsState().value
    // A link the page wanted in a new window; opened by the effect below, outside the WebView's callback.
    var pendingNewTabUrl by remember { mutableStateOf<String?>(null) }
    // What a long press on the page landed on, for its menu; null when no menu is open.
    var pressedOn by remember { mutableStateOf<PressedOn?>(null) }
    // How far the page is pulled down at its top (0 to 1), and whether it was let go far enough to load again.
    var pulled by remember { mutableFloatStateOf(0f) }
    var pullRefreshRequested by remember { mutableStateOf(false) }

    // Packages all destination-specific WebView callbacks so one atomic router update owns their lifetime together.
    val callbackState = RetainedWebViewCallbackState(
        onNavigationInvalidated = viewModel::onNavigationInvalidated,
        onPageStarted = viewModel::onPageStarted,
        onPageFinished = viewModel::onPageFinished,
        onProgressChanged = viewModel::onProgressChanged,
        onNavigationStateChanged = viewModel::onNavigationStateChanged,
        onEnterFullscreen = ::enterFullscreen,
        onExitFullscreen = ::exitFullscreen,
        onAddressChanged = viewModel::onPageAddressChanged,
        onOpenInNewTab = { pendingNewTabUrl = it },
        onPressed = { pressedOn = it },
        onPulled = { pulled = it },
        onPullToRefresh = { pullRefreshRequested = true },
    )

    /** Routes the tab shown to the browser's state, and every other tab to the state it keeps for itself. */
    fun bindTabs() {
        for (tab in tabs.all) {
            if (tab.id == tabs.activeId) {
                tab.interceptor.updateOnMediaDetected { media, navigationGeneration ->
                    viewModel.onMediaDetected(media, navigationGeneration)
                }
                tab.bridge.updateCallbacks(
                    onMediaDetected = { media, navigationGeneration ->
                        viewModel.onMediaDetected(media, navigationGeneration)
                    },
                    onMediaCandidate = { candidate, navigationGeneration ->
                        viewModel.verifyMediaCandidate(candidate, navigationGeneration)
                    },
                    onPageMeta = viewModel::onPageMeta,
                )
                tab.callbacks.update(callbackState)
            } else {
                tab.interceptor.updateOnMediaDetected { _, _ -> }
                tab.bridge.updateCallbacks(onMediaDetected = { _, _ -> }, onMediaCandidate = { _, _ -> })
                tab.callbacks.update(
                    backgroundCallbacks(
                        tab = tab,
                        nextGeneration = viewModel::nextNavigationGeneration,
                        onChanged = tabs::changed,
                        onOpenInNewTab = { pendingNewTabUrl = it },
                    ),
                )
            }
        }
    }

    /** Said once when a page's renderer stopped, so a page that vanished and came back is not a mystery. */
    var rendererNotice by remember { mutableStateOf<String?>(null) }

    /**
     * Gives [tab] a WebView. A tab coming back from disk ([restore]) gets its back and forward history again;
     * when that cannot be read, it opens its address only.
     */
    fun makeLive(tab: BrowserTab, restore: Boolean) {
        if (tab.webView != null) return
        val web = createChayaWebView(
            ctx = context,
            bridge = tab.bridge,
            interceptor = tab.interceptor,
            adBlock = tab.adBlock,
            detectorJs = detectorJs,
            frameAgentJs = frameAgentJs,
            callbacks = tab.callbacks,
            onVisited = { url, title -> app.appScope.launch { app.browsing.visited(url, title) } },
            onRendererGone = {
                // The page's renderer stopped. This tab's WebView is unusable; the others' get their own call.
                tab.webView?.let { dead ->
                    (dead.parent as? ViewGroup)?.removeView(dead)
                    destroyLater(dead)
                }
                tab.webView = null
                if (tab.id == tabs.activeId) {
                    // The tab shown gets a new WebView at once and its page again, unless that keeps happening.
                    val now = SystemClock.elapsedRealtime()
                    val reload = RendererLoss.shouldReload(tab.rendererGoneAt, now)
                    tab.rendererGoneAt = now
                    makeLive(tab, restore = false)
                    val page = viewModel.uiState.value
                    if (reload && !page.homeVisible && page.url.isNotEmpty()) tab.webView?.loadUrl(page.url)
                    rendererNotice = if (reload) "The page stopped working and was opened again"
                    else "The page keeps stopping. Tap reload to try again"
                }
                tabs.changed()
            },
            onIcon = { icon ->
                // The WebView owns the bitmap it hands over; a copy is written off the main thread.
                val copy = runCatching { icon.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                if (copy != null) {
                    onTabDisk {
                        tabStore.writeIcon(tab.id) { out -> TabPictures.writePng(copy, out) }
                        copy.recycle()
                        tabs.changed()
                    }
                }
            },
        )
        tab.webView = web
        if (!restore) return
        val bytes = tabStore.readState(tab.id)
        if (bytes == null || !WebViewStates.restore(web, bytes)) {
            val page = tab.saved
            if (page != null && !page.homeVisible && page.url.isNotEmpty()) web.loadUrl(page.url)
        }
    }

    /** A new tab with its own page helpers, not yet shown; [live] gives it its WebView now. */
    fun newTab(id: Long = tabs.nextId(), live: Boolean = true): BrowserTab {
        val interceptor = MediaInterceptor { _, _ -> }
        val tab = BrowserTab(
            id = id,
            interceptor = interceptor,
            bridge = MediaBridge(
                onMediaDetected = { _, _ -> },
                onMediaCandidate = { _, _ -> },
                wasRequested = interceptor::wasRequested,
            ),
            adBlock = adBlocker.newSession(),
            callbacks = RetainedWebViewCallbacks(callbackState),
        )
        tab.saved = BrowserUiState()
        if (live) makeLive(tab, restore = false)
        return tab
    }

    // The tabs, once per process: those saved when Android last closed the app, else one new one. Tabs outlive
    // this screen, as the single WebView did. Restored tabs start discarded; only the one shown gets a WebView.
    remember {
        if (tabs.active == null) {
            val saved = tabStore.load()
            if (saved != null) {
                tabs.reserveIds(saved.tabs.maxOf { it.id })
                for (entry in saved.tabs) {
                    val tab = newTab(id = entry.id, live = false)
                    tab.saved = BrowserUiState(url = entry.url, pageTitle = entry.title, homeVisible = entry.url.isEmpty())
                    tabs.add(tab)
                    tabs.activate(tab.id) // keeps the saved order: each goes after the one before
                }
                val first = tabs.all.firstOrNull { it.id == saved.activeId } ?: tabs.all.first()
                makeLive(first, restore = true)
                viewModel.switchTab(first.saved, canGoBack = false, canGoForward = false)
                first.saved = null
                tabs.activate(first.id)
                urlInput = viewModel.uiState.value.url
            } else {
                val first = newTab()
                first.saved = null
                tabs.add(first)
                tabs.activate(first.id)
            }
        }
        true
    }
    val activeTab = tabs.active
    // Rebind after every composition so retained objects never route to an old screen's state.
    SideEffect { bindTabs() }

    fun activeWebView(): WebView? = tabs.active?.webView

    // Stops old-page callbacks and HEAD work before any explicit top-level navigation request reaches WebView.
    fun invalidateDetectionSession() {
        val tab = tabs.active ?: return
        tab.bridge.invalidateNavigation()
        tab.interceptor.invalidateNavigation()
        tab.callbacks.onNavigationInvalidated()
    }

    /** The tabs as they are now, for [TabStore]: the tab shown is described by the browser's own state. */
    fun savedTabs(): TabStore.Saved = TabStore.Saved(
        tabs = tabs.all.map { tab ->
            val page = if (tab.id == tabs.activeId) viewModel.uiState.value else tab.saved ?: BrowserUiState()
            TabStore.SavedTab(tab.id, if (page.homeVisible) "" else page.url, if (page.homeVisible) "" else page.pageTitle)
        },
        activeId = tabs.activeId,
    )

    fun persistTabs() {
        val saved = savedTabs()
        onTabDisk { tabStore.save(saved) }
    }

    /** Keeps [tab]'s history on disk and, unless it is on the start screen, a picture of its page. */
    fun rememberTab(tab: BrowserTab, onStartScreen: Boolean) {
        val web = tab.webView ?: return
        val bytes = WebViewStates.save(web)
        val picture = if (onStartScreen) null else TabPictures.capture(web)
        onTabDisk {
            bytes?.let { tabStore.writeState(tab.id, it) }
            when {
                onStartScreen -> tabStore.thumbnail(tab.id).delete()
                picture != null -> {
                    tabStore.writeThumbnail(tab.id) { out -> TabPictures.writeWebp(picture, out) }
                    picture.recycle()
                }
            }
            tabs.changed()
        }
    }

    /** Destroys a tab's WebView to save memory; its history stays on disk for when it is shown again. */
    fun discard(tab: BrowserTab) {
        val web = tab.webView ?: return
        WebViewStates.save(web)?.let { bytes -> onTabDisk { tabStore.writeState(tab.id, bytes) } }
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        tab.webView = null
    }

    /** Shows [target]: the tab left keeps its page state, and [target]'s comes back. */
    fun showTab(target: BrowserTab) {
        val current = tabs.active
        if (current?.id == target.id) return
        if (customView != null) exitFullscreen()
        current?.let { rememberTab(it, viewModel.uiState.value.homeVisible) }
        makeLive(target, restore = true)
        val web = target.webView
        val kept = viewModel.switchTab(target.saved, web?.canGoBack() == true, web?.canGoForward() == true)
        current?.saved = kept
        target.saved = null
        tabs.activate(target.id)
        bindTabs()
        urlInput = viewModel.uiState.value.url
        tabs.toDiscard().forEach(::discard)
        persistTabs()
    }

    val noCount = remember { MutableStateFlow(0) }
    val noSite = remember { MutableStateFlow("") }
    val adBlocked by (activeTab?.adBlock?.blocked ?: noCount).collectAsState()
    val adSite by (activeTab?.adBlock?.site ?: noSite).collectAsState()
    val adBlockEnabled by adBlocker.settings.enabled.collectAsState()
    val adAllowedSites by adBlocker.settings.allowedSites.collectAsState()
    val adEngine by adBlocker.engine.collectAsState()
    var showTabGrid by remember { mutableStateOf(false) }
    // A fresh tab's address field asks for focus once; the field says when it has it
    var focusAddress by remember { mutableStateOf(false) }
    // A sheet opening (a link shared from another app, say) shows over the tab grid's place: what was just asked for.
    LaunchedEffect(uiState.sheet) {
        if (uiState.sheet != null) showTabGrid = false
    }

    // Ranks and names the page's media; pure and cheap, so it recomputes only when its inputs change.
    val sheetModel = remember(
        uiState.detectedMedia,
        uiState.pageMeta,
        uiState.url,
        uiState.pageTitle,
        uiState.hiddenSegmentCount,
    ) {
        MediaRanker.rank(
            media = uiState.detectedMedia,
            pageMeta = uiState.pageMeta,
            pageUrl = uiState.url,
            pageTitle = uiState.pageTitle,
            hiddenSegmentCount = uiState.hiddenSegmentCount,
        )
    }

    fun navigateToUrl(rawInput: String) {
        val query = rawInput.trim()
        if (query.isEmpty()) return

        val targetUrl = addressFor(query)
        urlInput = targetUrl
        invalidateDetectionSession()
        activeWebView()?.loadUrl(targetUrl)
    }

    LaunchedEffect(uiState.thoroughScanRequest) {
        val scanRequest = uiState.thoroughScanRequest ?: return@LaunchedEffect
        val tab = tabs.active
        val webView = tab?.webView ?: return@LaunchedEffect
        if (uiState.homeVisible ||
            uiState.url != scanRequest.pageUrl ||
            uiState.navigationGeneration != scanRequest.navigationGeneration ||
            webView.url != scanRequest.pageUrl ||
            !tab.bridge.enableThoroughScan(scanRequest.pageUrl, scanRequest.navigationGeneration)
        ) {
            viewModel.completeThoroughScanRequest(scanRequest)
            return@LaunchedEffect
        }

        viewModel.completeThoroughScanRequest(scanRequest)
        webView.evaluateJavascript(
            "window.__chayaScanMoreThoroughly && window.__chayaScanMoreThoroughly();",
            null,
        )
    }

    BackHandler(enabled = customView == null && uiState.canGoBack) {
        invalidateDetectionSession()
        activeWebView()?.goBack()
    }

    fun requestPermissionAndDownload(media: DetectedMedia) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.askAboutNotifications(AskedDownload.File(media))
        } else {
            startAsked(AskedDownload.File(media), notificationsOn = true)
        }
    }

    /** Starts a quality chosen in a link's sheet, asking about notifications first as a detected file does. */
    fun requestPermissionAndDownloadLink(choice: PlatformChoice) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.askAboutNotifications(AskedDownload.LinkQuality(choice))
        } else {
            startAsked(AskedDownload.LinkQuality(choice), notificationsOn = true)
        }
    }

    /** After the notification sentence: Allow asks Android, Not now downloads without notifications. */
    fun proceedFromRationale(allow: Boolean) {
        val asked = viewModel.answerNotifications() ?: return
        if (allow) {
            awaitingPermission = asked
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startAsked(asked, notificationsOn = false)
        }
    }

    val downloads by viewModel.downloads.collectAsState()
    // How the downloads still going are getting on, as the bar's Library slot shows it on every screen
    val saving = remember(downloads) { HomeShelf.progressOf(downloads) }

    /** Fills the address bar from the clipboard; a link opens straight away. The clipboard is read only when Paste is tapped. */
    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.trim()
            .orEmpty()
        when {
            text.isEmpty() -> scope.launch {
                snackbarHostState.showSnackbar("Your clipboard is empty", duration = SnackbarDuration.Short)
            }
            // A link to one video on YouTube, Instagram, TikTok, X or Pinterest opens its download sheet straight away.
            viewModel.openLink(text) -> Unit
            text.startsWith("http://") || text.startsWith("https://") -> navigateToUrl(text)
            else -> urlInput = text
        }
    }

    /** Replaces any message still showing, so quick successive actions never queue up stale snackbars. */
    fun showMessage(text: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message = text, duration = SnackbarDuration.Short)
        }
    }

    LaunchedEffect(rendererNotice) {
        rendererNotice?.let(::showMessage)
        rendererNotice = null
    }

    /** Reloads the page shown; a new WebView left blank after its page kept stopping opens the page instead. */
    fun reloadPage() {
        val web = activeWebView() ?: return
        val page = viewModel.uiState.value
        if (web.url.isNullOrEmpty() && !page.homeVisible && page.url.isNotEmpty()) web.loadUrl(page.url) else web.reload()
    }

    /** Opens a new tab, on the start screen or at [url]; past the limit, [url] opens in the tab shown instead. */
    fun openNewTab(url: String?) {
        if (!tabs.canOpenMore) {
            showMessage("Close a tab to open another (up to ${tabs.maxTabs})")
            if (url != null) navigateToUrl(url)
            return
        }
        val tab = newTab()
        val target = url?.trim()?.takeIf { it.isNotEmpty() }?.let(::addressFor)
        // A blank tab is for typing in: its field is ready, keyboard up, as a browser's new tab is.
        if (target == null) focusAddress = true
        // A tab opened for a page shows that page from the start, as a tab restored from disk does, rather than its
        // start screen until the page says it began: a link opened from the long-press menu was left on the start
        // screen with its address in the field.
        if (target != null) tab.saved = openingState(target, viewModel.nextNavigationGeneration())
        tabs.add(tab)
        showTab(tab)
        if (target != null) {
            urlInput = target
            // Loaded once its WebView is on screen (at once if it already is).
            tab.webView?.let { web -> web.doOnAttach { web.loadUrl(target) } }
        }
    }

    /** Closes [target]; closing the tab shown shows its neighbour, and closing the last opens a fresh one. */
    fun closeTab(target: BrowserTab) {
        val next = tabs.remove(target.id)
        target.webView?.let { web ->
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        target.webView = null
        onTabDisk { tabStore.remove(target.id) }
        when {
            next != null -> showTab(next)
            tabs.active == null -> {
                val fresh = newTab()
                tabs.add(fresh)
                showTab(fresh)
            }
            else -> persistTabs()
        }
    }

    /** Closes every tab and deletes their files (history, pictures, icons), then opens a fresh one. */
    fun closeAllTabs() {
        if (customView != null) exitFullscreen()
        for (tab in tabs.all) {
            tabs.remove(tab.id)
            tab.webView?.let { web ->
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
            }
            tab.webView = null
        }
        onTabDisk { tabStore.clear() }
        val fresh = newTab()
        tabs.add(fresh)
        showTab(fresh)
    }

    // Saved when a page finishes and when the tab list changes; history and a picture also when the app goes to
    // the background, which is when Android may close it.
    LaunchedEffect(uiState.url, uiState.pageTitle, uiState.homeVisible, uiState.isLoading, tabsVersion) {
        delay(TAB_SAVE_SETTLE_MILLIS)
        if (!uiState.isLoading) tabs.active?.webView?.let { web -> WebViewStates.save(web) }?.let { bytes ->
            val id = tabs.activeId
            onTabDisk { tabStore.writeState(id, bytes) }
        }
        persistTabs()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // Out of sight (Home, the Library, another app), the pages stop: a video on one no longer plays on
            // behind them. Back on screen, they carry on.
            if (event == Lifecycle.Event.ON_START) tabs.all.forEach { tab -> tab.webView?.onResume() }
            if (event == Lifecycle.Event.ON_STOP) {
                tabs.all.forEach { tab -> tab.webView?.onPause() }
                tabs.all.forEach { tab ->
                    if (tab.id == tabs.activeId) rememberTab(tab, viewModel.uiState.value.homeVisible)
                    else tab.webView?.let { web -> WebViewStates.save(web) }?.let { bytes -> onTabDisk { tabStore.writeState(tab.id, bytes) } }
                }
                persistTabs()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A page picked on the History or Bookmarks screen opens in the tab shown.
    val pageToOpen by PagesToOpen.pending.collectAsState()
    LaunchedEffect(pageToOpen) {
        val page = pageToOpen ?: return@LaunchedEffect
        PagesToOpen.consume(page)
        if (page.newTab) openNewTab(page.text.ifBlank { null }) else navigateToUrl(page.text)
    }

    LaunchedEffect(pendingNewTabUrl) {
        val url = pendingNewTabUrl ?: return@LaunchedEffect
        pendingNewTabUrl = null
        openNewTab(url)
    }

    // The page let go after being pulled down far enough at its top: it loads again.
    LaunchedEffect(pullRefreshRequested) {
        if (!pullRefreshRequested) return@LaunchedEffect
        pullRefreshRequested = false
        pulled = 0f
        if (!viewModel.uiState.value.homeVisible) reloadPage()
    }

    /** Puts [text] on the clipboard; Android 13 and newer say so themselves. */
    fun copy(label: String, text: String, said: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
        if (Build.VERSION.SDK_INT < 33) showMessage(said)
    }

    /** Hands [url] to the app the person picks. */
    fun shareAddress(url: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        runCatching { context.startActivity(Intent.createChooser(send, null)) }
            .onFailure { showMessage("No app on this phone can share a link") }
    }

    /** Saves [url]: a video site's link opens its sheet, as a pasted one does; anything else downloads as a file. */
    fun downloadAddress(url: String) {
        if (viewModel.openLink(url)) return
        val page = viewModel.uiState.value.url.takeIf { it.isNotEmpty() }
        requestPermissionAndDownload(DetectedMedia(url = url, pageUrl = page, mimeType = null, source = DetectionSource.DOM))
    }

    /** One row of a long press's menu, done. */
    fun act(pressed: PressedOn, action: PageAction) {
        val link = (pressed as? PressedOn.Link)?.url ?: (pressed as? PressedOn.Image)?.link
        val picture = (pressed as? PressedOn.Image)?.src
        when (action) {
            PageAction.OPEN_IN_NEW_TAB -> link?.let(::openNewTab)
            PageAction.COPY_LINK -> link?.let { copy("Link", it, "Link copied") }
            PageAction.COPY_LINK_TEXT -> (pressed as? PressedOn.Link)?.let { copy("Link text", it.text, "Link text copied") }
            PageAction.DOWNLOAD_LINK -> link?.let(::downloadAddress)
            PageAction.SHARE_LINK -> link?.let(::shareAddress)
            PageAction.OPEN_IMAGE_IN_NEW_TAB -> picture?.let(::openNewTab)
            PageAction.DOWNLOAD_IMAGE -> picture?.let(::downloadAddress)
            PageAction.COPY_IMAGE_ADDRESS -> picture?.let { copy("Image address", it, "Image address copied") }
            PageAction.SHARE_IMAGE -> picture?.let(::shareAddress)
        }
    }

    pressedOn?.let { pressed ->
        PageMenu(
            pressed = pressed,
            onAction = { action ->
                pressedOn = null
                act(pressed, action)
            },
            onDismiss = { pressedOn = null },
        )
    }

    // A link shared to Chaya from another app opens its sheet once the browser is on screen.
    val sharedText by SharedLinks.pending.collectAsState()
    LaunchedEffect(sharedText) {
        val text = sharedText ?: return@LaunchedEffect
        SharedLinks.consume(text)
        if (!viewModel.openLink(text)) {
            showMessage("Chaya can save videos from YouTube, Instagram, TikTok, X and Pinterest links.")
        }
    }

    // What the page has to save, one value, for the bar's ink circle
    val pageFind = PageFind.of(uiState.homeVisible, linkState, uiState.profile, sheetModel)

    /** The bar under the page, and over the tab grid with Tabs lit; [newTab] is what its + does there. */
    @Composable
    fun PageBar(tabsShown: Boolean, newTab: () -> Unit = { openNewTab(null) }) {
        ChayaBar(
            place = Place.BROWSE,
            middle = BarMiddle.Page(uiState.canGoBack, uiState.canGoForward, tabs.size, tabsShown),
            action = if (tabsShown) BarAction.NewTab else pageFind.action,
            progress = saving,
            onPlace = onPlace,
            onAction = {
                if (tabsShown) {
                    showTabGrid = false
                    newTab()
                } else {
                    when (val find = pageFind) {
                        PageFind.Nothing -> newTab()
                        is PageFind.Looking -> viewModel.toggleLinkSheet()
                        is PageFind.Found -> when (find.sheet) {
                            FoundSheet.LINK -> viewModel.toggleLinkSheet()
                            FoundSheet.PROFILE -> viewModel.toggleProfileSheet()
                            FoundSheet.MEDIA -> viewModel.toggleMediaSheet()
                        }
                    }
                }
            },
            onBack = {
                invalidateDetectionSession()
                activeWebView()?.goBack()
            },
            onForward = {
                invalidateDetectionSession()
                activeWebView()?.goForward()
            },
            onTabs = {
                if (tabsShown) {
                    showTabGrid = false
                } else {
                    // The shown tab's card pictures the page as it is now.
                    tabs.active?.let { rememberTab(it, uiState.homeVisible) }
                    showTabGrid = true
                }
            },
            // Swipe sideways along the bar for the tab beside this one.
            pillModifier = if (tabsShown) Modifier else Modifier.swipeBetweenTabs { step -> tabs.beside(step)?.let { showTab(it) } },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            AddressBar(
                                url = uiState.url,
                                homeVisible = uiState.homeVisible,
                                input = urlInput,
                                onInputChange = { urlInput = it },
                                // On a new tab the field is where a link is pasted to save it, as Home's + had it: a link
                                // to a video, post or account opens its sheet; anything else, an address or words, loads.
                                onGo = {
                                    if (!(uiState.homeVisible && viewModel.openLink(urlInput))) navigateToUrl(urlInput)
                                },
                                onPaste = { pasteFromClipboard() },
                                onReload = {
                                    invalidateDetectionSession()
                                    reloadPage()
                                },
                                adBlock = AdBlockBadge(
                                    active = adBlockEnabled && adSite !in adAllowedSites,
                                    blocked = adBlocked,
                                ).takeIf { adSite.isNotEmpty() },
                                onAdBlock = { viewModel.openAdBlockSheet() },
                                bookmarked = pageBookmarked,
                                // Only a web page can be starred; the start screen and other pages have no star.
                                onToggleBookmark = if (BrowsingRecord.isWebPage(uiState.url)) {
                                    {
                                        val page = uiState
                                        scope.launch {
                                            val now = app.browsing.toggleBookmark(page.url, page.pageTitle)
                                            showMessage(if (now) "Added to bookmarks" else "Removed from bookmarks")
                                        }
                                    }
                                } else {
                                    null
                                },
                                suggestions = suggestions,
                                onSuggestion = { url -> navigateToUrl(url) },
                                focusNow = focusAddress,
                                onFocused = { focusAddress = false },
                            )
                        }

                        AnimatedVisibility(
                            visible = uiState.isLoading,
                            enter = fadeIn(ChayaMotion.tweenShort()),
                            exit = fadeOut(ChayaMotion.tweenShort())
                        ) {
                            LinearProgressIndicator(
                                progress = { uiState.progress / 100f },
                                modifier = Modifier.fillMaxWidth().height(2.dp),
                                color = LocalChayaColors.current.signal,
                                trackColor = Color.Transparent
                            )
                        }
                    }
                }
            },
            // Chaya's one bar: Home and the Library at its ends, the page's Back, Forward and Tabs between, and the
            // ink circle for what the page has to save, else a new tab. The page ends above it.
            bottomBar = { PageBar(tabsShown = false) },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                // One place on screen for the tab shown; tabs keep their WebViews while not shown.
                val shownWebView = activeTab?.webView
                AndroidView(
                    factory = { ctx -> FrameLayout(ctx) },
                    update = { host ->
                        if (shownWebView != null &&
                            (host.childCount != 1 || host.getChildAt(0) !== shownWebView)
                        ) {
                            host.removeAllViews()
                            (shownWebView.parent as? ViewGroup)?.removeView(shownWebView)
                            host.addView(
                                shownWebView,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    // The WebViews live on for reattachment; only this screen's container goes.
                    onRelease = { host -> host.removeAllViews() }
                )

                // Pulled down at its top, the page shows how far; let go past the end, it loads again.
                PullIndicator(
                    pulled = if (uiState.homeVisible) 0f else pulled,
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                NewTabPage(
                    visible = uiState.homeVisible,
                    showOnboardingHint = showOnboardingHint,
                    onDismissOnboardingHint = { dismissOnboardingHint() },
                    onSelectUrl = { target -> navigateToUrl(target) },
                    bookmarks = bookmarkTiles,
                    onOpenBookmarks = onNavigateToBookmarks,
                    onOpenHistory = onNavigateToHistory,
                )

                // Opt-in inspection stays separate from ordinary detection to avoid background HEAD traffic.
                AnimatedVisibility(
                    visible = !uiState.homeVisible &&
                            uiState.showThoroughScan &&
                            uiState.detectedMedia.isEmpty(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = 20.dp),
                    enter = scaleIn(initialScale = 0.8f, animationSpec = ChayaMotion.springSmooth()) +
                            fadeIn(ChayaMotion.tweenShort()),
                    exit = scaleOut(targetScale = 0.8f, animationSpec = ChayaMotion.tweenShort()) +
                            fadeOut(ChayaMotion.tweenShort())
                ) {
                    ExtendedFloatingActionButton(
                        onClick = { viewModel.requestThoroughScan() },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                            )
                        },
                        text = { Text("Scan more thoroughly") },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.pressScale(0.96f),
                    )
                }

                // The video behind a link on a supported site: found, with the qualities it can be saved at.
                if (uiState.showLinkSheet && linkState !is LinkState.Idle) {
                    PlatformSheet(
                        state = linkState,
                        onDismiss = { viewModel.dismissLinkSheet() },
                        onChoose = { choice -> requestPermissionAndDownloadLink(choice) },
                        onRetry = { viewModel.retryLink() },
                        onRetryWithSignIn = { viewModel.retryLink(useSignIn = true) },
                        onSavePost = { indices ->
                            viewModel.downloadPost(indices) { count ->
                                showMessage(if (count == 1) "Saving 1 item" else "Saving $count items")
                            }
                        },
                    )
                }

                // An account's posts, saved as one ZIP; its sign-in is used only when chosen here.
                val sheetProfile = uiState.profile
                if (uiState.showProfileSheet && sheetProfile != null) {
                    ProfileSheet(
                        profile = sheetProfile,
                        signedIn = remember(sheetProfile) { viewModel.isSignedIn(sheetProfile.platform) },
                        onSave = { useSignIn ->
                            viewModel.saveProfile(useSignIn) { name -> showMessage("Saving $name") }
                        },
                        onSignIn = {
                            viewModel.dismissProfileSheet()
                            navigateToUrl(signInPageOf(sheetProfile.platform))
                        },
                        onDismiss = { viewModel.dismissProfileSheet() },
                    )
                }

                if (uiState.sheet == BrowserSheet.AdBlock) {
                    AdBlockSheet(
                        site = adSite,
                        blocked = adBlocked,
                        enabled = adBlockEnabled,
                        blocksOnSite = adSite !in adAllowedSites,
                        ruleCount = adEngine?.ruleCount,
                        lastUpdated = adBlocker.lists.lastUpdated(),
                        onEnabledChange = { on ->
                            adBlocker.settings.setEnabled(on)
                            invalidateDetectionSession()
                            reloadPage()
                        },
                        onBlocksOnSiteChange = { on ->
                            adBlocker.settings.setAllowed(adSite, allowed = !on)
                            invalidateDetectionSession()
                            reloadPage()
                        },
                        onDismiss = { viewModel.dismissAdBlockSheet() },
                    )
                }

                // Media detection bottom sheet
                if (uiState.showMediaSheet) {
                    DetectedMediaSheet(
                        model = sheetModel,
                        onDismiss = { viewModel.dismissMediaSheet() },
                        onDownload = { item ->
                            viewModel.dismissMediaSheet()
                            if (item.kind == MediaKind.STREAM) {
                                // One tap: best quality, no picker. The Quality button is for choosing.
                                showMessage("Preparing ${item.title.take(40)}…")
                                viewModel.downloadStreamBest(streamMediaFor(item)) { quality ->
                                    showMessage("Downloading ${item.title.take(40)}" + (quality?.let { " ($it)" } ?: ""))
                                }
                            } else {
                                requestPermissionAndDownload(fileMediaFor(item))
                            }
                        },
                        onChooseQuality = { item ->
                            viewModel.dismissMediaSheet()
                            viewModel.analyzeStream(streamMediaFor(item), item.durationSeconds)
                        }
                    )
                }

                // Quality picker for streaming (HLS/DASH) downloads
                uiState.qualityPickerState?.let { pickerState ->
                    QualitySelectorSheet(
                        state = pickerState,
                        onDismiss = { viewModel.dismissQualityPicker() },
                        onDownload = { tracks ->
                            if (tracks.isEmpty()) {
                                viewModel.downloadStreamFallback()
                            } else {
                                viewModel.downloadStream(tracks)
                            }
                        }
                    )
                }

                // Notification rationale (Phase 0.5): one sentence before the
                // system dialog; declining still starts the download.
                (uiState.sheet as? BrowserSheet.Notifications)?.let { sheet ->
                    NotificationRationaleSheet(
                        fileName = when (val asked = sheet.download) {
                            is AskedDownload.File -> downloadLabel(asked.media)
                            is AskedDownload.LinkQuality ->
                                (linkState as? LinkState.Found)?.let { "${it.media.title} (${asked.choice.label})" }
                                    ?: asked.choice.label
                        },
                        onAllow = { proceedFromRationale(true) },
                        onNotNow = { proceedFromRationale(false) },
                    )
                }
            }
        }

        // Every tab as a card, over the whole screen.
        AnimatedVisibility(
            visible = showTabGrid,
            enter = fadeIn(ChayaMotion.tweenShort()) + scaleIn(ChayaMotion.tweenStandard(), initialScale = 1.04f),
            exit = fadeOut(ChayaMotion.tweenShort()) + scaleOut(ChayaMotion.tweenStandard(), targetScale = 1.04f),
        ) {
            TabGrid(
                tabs = tabs.all.map { tab ->
                    val page = if (tab.id == tabs.activeId) uiState else tab.saved ?: BrowserUiState()
                    TabSummary.of(
                        tab.id,
                        page,
                        thumbnail = tabStore.thumbnail(tab.id).takeUnless { page.homeVisible },
                        icon = tabStore.icon(tab.id).takeUnless { page.homeVisible },
                    )
                },
                activeId = tabs.activeId,
                picturesVersion = tabsVersion,
                onSelect = { id ->
                    showTabGrid = false
                    tabs.all.firstOrNull { it.id == id }?.let(::showTab)
                },
                onClose = { id -> tabs.all.firstOrNull { it.id == id }?.let(::closeTab) },
                onNewTab = {
                    showTabGrid = false
                    openNewTab(null)
                },
                onCloseAll = {
                    showTabGrid = false
                    closeAllTabs()
                },
                onDismiss = { showTabGrid = false },
                bottomBar = { newTab -> PageBar(tabsShown = true, newTab = newTab) },
            )
        }

        // Fullscreen video overlay — covers everything including bars.
        customView?.let { view ->
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
                AndroidView(
                    factory = { view },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

// --------------------------------------------------------------------- //
// WebView construction
// --------------------------------------------------------------------- //

/** Holds one detector capability and generation while the WebView finishes its active document. */
private data class DetectorNavigationSession(
    val pageUrl: String,
    val navigationGeneration: Long,
    val capability: String,
)

/** What the address bar's [input] opens: an address as it is, a bare site with https://, anything else searched. */
internal fun addressFor(input: String): String {
    val query = input.trim()
    return when {
        query.startsWith("http://") || query.startsWith("https://") -> query
        query.contains(".") && !query.contains(" ") -> "https://$query"
        else -> "https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}"
    }
}

/** A new tab's state when it opens for [url]: that page, loading, never the start screen. */
internal fun openingState(url: String, navigationGeneration: Long): BrowserUiState =
    BrowserUiState().startedPage(url, navigationGeneration)

/** Creates a WebView whose clients always dispatch through the mutable retained callback router. */
@SuppressLint("SetJavaScriptEnabled")
internal fun createChayaWebView(
    ctx: android.content.Context,
    bridge: MediaBridge,
    interceptor: MediaInterceptor,
    adBlock: AdBlockSession,
    detectorJs: String,
    callbacks: RetainedWebViewCallbacks,
    /** Runs at the start of every embedded frame's document (not the page's own), where WebView allows it. */
    frameAgentJs: String = "",
    /** The site's icon for this tab, for its card in the tab grid. */
    onIcon: (Bitmap) -> Unit = {},
    /** A page finished loading in this tab, for history. */
    onVisited: (url: String, title: String) -> Unit = { _, _ -> },
    /** The page's renderer stopped; this WebView can no longer be used and must be replaced. */
    onRendererGone: () -> Unit = {},
): WebView {
    return ChayaWebView(ctx, callbacks).apply {
        // Tracks injection eligibility so a superseded redirect cannot reintroduce an old capability on finish.
        var activeDetectorSession: DetectorNavigationSession? = null

        // Invalidates every page-scoped collaborator before a transition can deliver stale callbacks.
        fun invalidateDetectorNavigation() {
            activeDetectorSession = null
            bridge.invalidateNavigation()
            interceptor.invalidateNavigation()
            callbacks.onNavigationInvalidated()
        }

        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        // The bridge is visible to every frame, so its callbacks validate a per-navigation capability.
        addJavascriptInterface(bridge, "ChayaBridge")
        // Players embedded from another server are out of the page detector's reach; a small agent runs inside
        // them instead and tells the page's detector what they loaded.
        if (frameAgentJs.isNotEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(this, frameAgentJs, setOf("*")) }
        }
        // Hands out only selectors from the public ad lists, so it needs no capability.
        addJavascriptInterface(CosmeticBridge(adBlock), "ChayaCosmetic")

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                val pageUrl = url ?: run {
                    invalidateDetectorNavigation()
                    return
                }
                val navigationGeneration = callbacks.onPageStarted(pageUrl)
                adBlock.pageStarted(pageUrl)
                if (pageUrl == "about:blank") {
                    activeDetectorSession = null
                    bridge.invalidateNavigation()
                    interceptor.invalidateNavigation()
                    return
                }

                val capability = bridge.beginNavigation(pageUrl, navigationGeneration)
                interceptor.beginNavigation(pageUrl, navigationGeneration)
                activeDetectorSession = DetectorNavigationSession(
                    pageUrl = pageUrl,
                    navigationGeneration = navigationGeneration,
                    capability = capability,
                )
                // Inject early and only into the main document so the capability stays out of iframes.
                if (detectorJs.isNotEmpty()) {
                    view?.evaluateJavascript(
                        detectorScriptWithCapability(detectorJs, capability),
                        null,
                    )
                }
                adBlock.cosmeticScript()?.let { view?.evaluateJavascript(it, null) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val pageUrl = url ?: return
                val session = activeDetectorSession
                if (session?.pageUrl == pageUrl) {
                    callbacks.onPageFinished(
                        pageUrl,
                        view?.title.orEmpty(),
                        session.navigationGeneration,
                    )
                    onVisited(pageUrl, view?.title.orEmpty())
                    // Reinject after commit because a load-start evaluation can be discarded with the old document.
                    if (detectorJs.isNotEmpty()) {
                        view?.evaluateJavascript(
                            detectorScriptWithCapability(detectorJs, session.capability),
                            null,
                        )
                    }
                    // Again after commit, as for the detector; the script runs once per document.
                    adBlock.cosmeticScript()?.let { view?.evaluateJavascript(it, null) }
                }
                view?.let {
                    callbacks.onNavigationStateChanged(it.canGoBack(), it.canGoForward())
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                if (request?.isForMainFrame == true) {
                    invalidateDetectorNavigation()
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request == null) return null
                // A blocked ad never reaches media detection, so it is never offered for download either.
                adBlock.intercept(request)?.let { return it }
                return interceptor.shouldInterceptRequest(request)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                view?.let {
                    callbacks.onNavigationStateChanged(it.canGoBack(), it.canGoForward())
                }
                // Also fires when a video site moves to another video without loading a new document.
                url?.let { callbacks.onAddressChanged(it) }
                super.doUpdateVisitedHistory(view, url, isReload)
            }

            // Android ends the whole app when a page's renderer stops (a crash, or its memory taken back) unless
            // every WebView handles it, so the person lost everything to one bad page. Now only the page goes.
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                invalidateDetectorNavigation()
                onRendererGone()
                return true
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                activeDetectorSession?.let { session ->
                    callbacks.onProgressChanged(newProgress, session.navigationGeneration)
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback) {
                view?.let { callbacks.onEnterFullscreen(it, callback) }
            }

            override fun onHideCustomView() {
                callbacks.onExitFullscreen()
            }

            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                icon?.let(onIcon)
            }

            /** target="_blank" links open in a new tab when tapped, else in this tab, instead of doing nothing. */
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                if (view == null || resultMsg == null) return false
                val tempWebView = WebView(view.context).apply {
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            wv: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            request?.url?.let {
                                if (adBlock.shouldBlockPopup(it.toString())) {
                                    destroyLater(wv)
                                    return true
                                }
                                // A link the person tapped that wants a new window opens in a new tab.
                                if (isUserGesture) {
                                    callbacks.onOpenInNewTab(it.toString())
                                    destroyLater(wv)
                                    return true
                                }
                                invalidateDetectorNavigation()
                                view.loadUrl(it.toString())
                                destroyLater(wv)
                            }
                            return true
                        }

                        // The window a page opens shares the renderer: it must survive its loss too, or the app goes.
                        override fun onRenderProcessGone(wv: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                            destroyLater(wv)
                            return true
                        }
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = tempWebView
                resultMsg.sendToTarget()
                return true
            }
        }
    }
}

/** The media a stream download starts from; the chosen rendition later adds its height to the name. */
private fun streamMediaFor(item: RankedMedia): DetectedMedia = item.media.copy(
    suggestedName = MediaNamer.fileBaseName(item.title, null),
    title = item.title,
    thumbnailUrl = item.thumbnailUrl,
)

/** The media a plain file download starts from. */
private fun fileMediaFor(item: RankedMedia): DetectedMedia = item.media.copy(
    suggestedName = item.fileBaseName,
    title = item.title,
    thumbnailUrl = item.thumbnailUrl,
    qualityHeight = item.videoHeight,
)

/** Short label for snackbars and the notification rationale: the chosen title, else the URL's file name. */
private fun downloadLabel(media: DetectedMedia): String =
    media.suggestedName?.takeIf { it.isNotBlank() }
        ?: media.url.substringBefore('?').substringAfterLast('/').take(32)

/** Prepends the random main-document capability without interpolating page-controlled content. */
private fun detectorScriptWithCapability(detectorJs: String, capability: String): String {
    return "window.__chayaBridgeCapability = '$capability';\n$detectorJs"
}

/** Where to sign in to an account's site, inside Chaya's browser. */
private fun signInPageOf(platform: Platform): String = when (platform) {
    Platform.TWITTER -> "https://x.com/i/flow/login"
    else -> "https://www.instagram.com/accounts/login/"
}

/**
 * Destroys a WebView once the callback running on it has returned: destroying it from inside its own callback
 * pulls it out from under WebView's code.
 */
private fun destroyLater(webView: WebView?) {
    webView ?: return
    android.os.Handler(android.os.Looper.getMainLooper()).post { webView.destroy() }
}
