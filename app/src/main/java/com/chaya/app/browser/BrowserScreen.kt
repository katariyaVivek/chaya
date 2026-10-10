package com.chaya.app.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chaya.app.ChayaApplication
import com.chaya.app.SharedLinks
import com.chaya.app.adblock.AdBlockSession
import com.chaya.app.adblock.CosmeticBridge
import com.chaya.app.detection.MediaBridge
import com.chaya.app.detection.MediaInterceptor
import com.chaya.app.detection.MediaNamer
import com.chaya.app.detection.MediaRanker
import com.chaya.app.detection.RankedMedia
import com.chaya.app.download.DownloadState
import com.chaya.app.model.DetectedMedia
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
import com.chaya.app.ui.theme.pressScale
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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

/** How long the page must stay put before the tabs are saved, so a burst of changes saves once. */
private const val TAB_SAVE_SETTLE_MILLIS = 500L

@OptIn(ExperimentalLayoutApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    onNavigateToDownloads: () -> Unit,
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

    // Pending download waiting for notification permission
    var pendingMedia by remember { mutableStateOf<DetectedMedia?>(null) }
    // One-line rationale before the system dialog (Phase 0.5): the download
    // is not obviously notification-related, so explain first. "Not now"
    // still downloads — matches the silent fallback below.
    var rationaleMedia by remember { mutableStateOf<DetectedMedia?>(null) }
    // The same two steps for a quality chosen in a link's sheet.
    var pendingChoice by remember { mutableStateOf<PlatformChoice?>(null) }
    var rationaleChoice by remember { mutableStateOf<PlatformChoice?>(null) }

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
        pendingMedia?.let { media ->
            pendingMedia = null
            viewModel.downloadMedia(media)
            scope.launch {
                val msg = if (granted) {
                    "Downloading ${downloadLabel(media)}"
                } else {
                    "Downloading (notifications off)"
                }
                snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
            }
        }
        pendingChoice?.let { choice ->
            pendingChoice = null
            viewModel.downloadLink(choice) { label ->
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = if (granted) "Downloading $label" else "Downloading (notifications off)",
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
    }

    val adBlocker = remember { (context.applicationContext as ChayaApplication).adBlocker }
    val app = remember { context.applicationContext as ChayaApplication }
    val tabStore = remember { app.tabStore }

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

    val tabs = WebViewHolder.tabs
    // Read so that opening, closing or showing a tab, or a page loading in a tab not shown, recomposes.
    @Suppress("UNUSED_VARIABLE")
    val tabsVersion = tabs.version.collectAsState().value
    // A link the page wanted in a new window; opened by the effect below, outside the WebView's callback.
    var pendingNewTabUrl by remember { mutableStateOf<String?>(null) }

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
            callbacks = tab.callbacks,
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
        val tab = BrowserTab(
            id = id,
            interceptor = MediaInterceptor { _, _ -> },
            bridge = MediaBridge(onMediaDetected = { _, _ -> }, onMediaCandidate = { _, _ -> }),
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
    var showAdBlockSheet by remember { mutableStateOf(false) }
    var showTabGrid by remember { mutableStateOf(false) }

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

        val targetUrl = when {
            query.startsWith("http://") || query.startsWith("https://") -> query
            query.contains(".") && !query.contains(" ") -> "https://$query"
            else -> "https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}"
        }
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
            rationaleMedia = media
        } else {
            viewModel.downloadMedia(media)
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "Downloading ${downloadLabel(media)}",
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    /** Fires after the rationale sheet: Allow shows the system dialog, Not now downloads silently. */
    /** Starts a quality chosen in a link's sheet, asking about notifications first as a detected file does. */
    fun requestPermissionAndDownloadLink(choice: PlatformChoice) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            rationaleChoice = choice
        } else {
            viewModel.downloadLink(choice) { label ->
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "Downloading $label",
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
    }

    fun proceedFromRationale(allow: Boolean) {
        val choice = rationaleChoice
        if (choice != null) {
            rationaleChoice = null
            if (allow) {
                pendingChoice = choice
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.downloadLink(choice) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = "Downloading (notifications off)",
                            duration = SnackbarDuration.Short
                        )
                    }
                }
            }
            return
        }
        val media = rationaleMedia ?: return
        rationaleMedia = null
        if (allow) {
            pendingMedia = media
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.downloadMedia(media)
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "Downloading (notifications off)",
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    val downloads by viewModel.downloads.collectAsState()
    val activeDownloads = remember(downloads) {
        downloads.count { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED }
    }
    val recentDownloads = remember(downloads) {
        downloads.filter { it.state == DownloadState.COMPLETED }.sortedByDescending { it.updatedAt }.take(3)
    }

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
            // A link to one video on YouTube, Instagram, TikTok or X opens its download sheet straight away.
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

    /** Opens a new tab, on the start screen or at [url]; past the limit, [url] opens in the tab shown instead. */
    fun openNewTab(url: String?) {
        if (!tabs.canOpenMore) {
            showMessage("Close a tab to open another (up to ${tabs.maxTabs})")
            if (url != null) navigateToUrl(url)
            return
        }
        val tab = newTab()
        tabs.add(tab)
        showTab(tab)
        if (url != null) navigateToUrl(url)
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
            if (event == Lifecycle.Event.ON_STOP) {
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

    LaunchedEffect(pendingNewTabUrl) {
        val url = pendingNewTabUrl ?: return@LaunchedEffect
        pendingNewTabUrl = null
        openNewTab(url)
    }

    // A link shared to Chaya from another app opens its sheet once the browser is on screen.
    val sharedText by SharedLinks.pending.collectAsState()
    LaunchedEffect(sharedText) {
        val text = sharedText ?: return@LaunchedEffect
        SharedLinks.consume(text)
        if (!viewModel.openLink(text)) {
            showMessage("Chaya can save videos from YouTube, Instagram, TikTok and X links.")
        }
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
                                onGo = { navigateToUrl(urlInput) },
                                onPaste = { pasteFromClipboard() },
                                onReload = {
                                    invalidateDetectionSession()
                                    activeWebView()?.reload()
                                },
                                adBlock = AdBlockBadge(
                                    active = adBlockEnabled && adSite !in adAllowedSites,
                                    blocked = adBlocked,
                                ).takeIf { adSite.isNotEmpty() },
                                onAdBlock = { showAdBlockSheet = true },
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
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = Color.Transparent
                            )
                        }
                    }
                }
            },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            NavAction(
                                icon = Icons.AutoMirrored.Filled.ArrowBack,
                                label = "Back",
                                enabled = uiState.canGoBack
                            ) {
                                invalidateDetectionSession()
                                activeWebView()?.goBack()
                            }

                            NavAction(
                                icon = Icons.AutoMirrored.Filled.ArrowForward,
                                label = "Forward",
                                enabled = uiState.canGoForward
                            ) {
                                invalidateDetectionSession()
                                activeWebView()?.goForward()
                            }

                            NavAction(
                                icon = Icons.Default.Home,
                                label = "Home",
                                enabled = true
                            ) {
                                invalidateDetectionSession()
                                activeWebView()?.loadUrl("about:blank")
                                viewModel.goHome()
                            }

                            NavAction(
                                icon = Icons.Default.Tab,
                                label = "Tabs (${tabs.size})",
                                enabled = true,
                                count = tabs.size,
                            ) {
                                // The shown tab's card pictures the page as it is now.
                                tabs.active?.let { rememberTab(it, uiState.homeVisible) }
                                showTabGrid = true
                            }

                            NavAction(
                                icon = Icons.Default.CloudDownload,
                                label = "Downloads",
                                enabled = true,
                                badge = activeDownloads,
                                onClick = onNavigateToDownloads
                            )
                        }
                    }
                }
            },
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

                HomeContent(
                    visible = uiState.homeVisible,
                    showOnboardingHint = showOnboardingHint,
                    onDismissOnboardingHint = { dismissOnboardingHint() },
                    recent = recentDownloads,
                    onSelectUrl = { target -> navigateToUrl(target) },
                    onOpenDownloads = onNavigateToDownloads
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

                // Floating download pill: the page's main video, one tap from its download options. On a page
                // that is one video on YouTube, Instagram, TikTok or X it shows what the engine found instead.
                val linkPillShown = !uiState.homeVisible &&
                    (linkState is LinkState.Looking || linkState is LinkState.Answer)
                // An Instagram or X account page offers its posts as one ZIP.
                val profileShown = uiState.profile.takeIf { !uiState.homeVisible && !linkPillShown }
                AnimatedVisibility(
                    visible = linkPillShown || profileShown != null || (!uiState.homeVisible && !sheetModel.isEmpty),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    enter = slideInVertically(ChayaMotion.tweenStandard()) { it } +
                            fadeIn(ChayaMotion.tweenShort()),
                    exit = slideOutVertically(ChayaMotion.tweenShort()) { it } +
                            fadeOut(ChayaMotion.tweenShort())
                ) {
                    if (linkPillShown) {
                        PlatformPill(state = linkState, onClick = { viewModel.toggleLinkSheet() })
                    } else if (profileShown != null) {
                        ProfilePill(profile = profileShown, onClick = { viewModel.toggleProfileSheet() })
                    } else {
                        MediaPill(model = sheetModel, onClick = { viewModel.toggleMediaSheet() })
                    }
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

                if (showAdBlockSheet) {
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
                            activeWebView()?.reload()
                        },
                        onBlocksOnSiteChange = { on ->
                            adBlocker.settings.setAllowed(adSite, allowed = !on)
                            invalidateDetectionSession()
                            activeWebView()?.reload()
                        },
                        onDismiss = { showAdBlockSheet = false },
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
                rationaleMedia?.let { media ->
                    NotificationRationaleSheet(
                        fileName = downloadLabel(media),
                        onAllow = { proceedFromRationale(true) },
                        onNotNow = { proceedFromRationale(false) },
                    )
                }
                rationaleChoice?.let { choice ->
                    NotificationRationaleSheet(
                        fileName = (linkState as? LinkState.Found)?.let { "${it.media.title} (${choice.label})" }
                            ?: choice.label,
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
                canOpenMore = tabs.canOpenMore,
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

/** Bottom-bar action with consistent disabled styling, press feedback and an optional count badge. */
@Composable
private fun NavAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    badge: Int = 0,
    /** A number drawn on the icon itself, as the tab count is; 0 for none. */
    count: Int = 0,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.pressScale(0.88f)) {
        BadgedBox(
            badge = {
                if (badge > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) { Text("$badge") }
                }
            }
        ) {
            val tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            if (count > 0) {
                // The tab count in a rounded square, as browsers show it.
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .border(1.75.dp, tint, RoundedCornerShape(6.dp))
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$count",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = tint,
                    )
                }
            } else {
                Icon(imageVector = icon, contentDescription = label, tint = tint)
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

/** Creates a WebView whose clients always dispatch through the mutable retained callback router. */
@SuppressLint("SetJavaScriptEnabled")
private fun createChayaWebView(
    ctx: android.content.Context,
    bridge: MediaBridge,
    interceptor: MediaInterceptor,
    adBlock: AdBlockSession,
    detectorJs: String,
    callbacks: RetainedWebViewCallbacks,
    /** The site's icon for this tab, for its card in the tab grid. */
    onIcon: (Bitmap) -> Unit = {},
): WebView {
    return WebView(ctx).apply {
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
                                    wv?.destroy()
                                    return true
                                }
                                // A link the person tapped that wants a new window opens in a new tab.
                                if (isUserGesture) {
                                    callbacks.onOpenInNewTab(it.toString())
                                    wv?.destroy()
                                    return true
                                }
                                invalidateDetectorNavigation()
                                view.loadUrl(it.toString())
                                wv?.destroy()
                            }
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
