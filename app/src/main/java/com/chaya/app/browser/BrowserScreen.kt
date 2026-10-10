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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chaya.app.SharedLinks
import com.chaya.app.detection.MediaBridge
import com.chaya.app.detection.MediaInterceptor
import com.chaya.app.detection.MediaNamer
import com.chaya.app.detection.MediaRanker
import com.chaya.app.detection.RankedMedia
import com.chaya.app.download.DownloadState
import com.chaya.app.model.DetectedMedia
import com.chaya.app.model.MediaKind
import com.chaya.app.platform.LinkState
import com.chaya.app.platform.PlatformChoice
import com.chaya.app.ui.components.DetectedMediaSheet
import com.chaya.app.ui.components.NotificationRationaleSheet
import com.chaya.app.ui.components.PlatformSheet
import com.chaya.app.ui.components.QualitySelectorSheet
import com.chaya.app.ui.theme.ChayaMotion
import com.chaya.app.ui.theme.pressScale
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * Holds the single WebView instance across navigation so the browsing session
 * (history, page, cookies) survives trips to the Downloads screen and back.
 */
private object WebViewHolder {
    /** Retains browsing history and renderer state while Compose destinations swap. */
    var instance: WebView? = null

    /** Retains the secured bridge captured by the WebViewClient across destination reattachment. */
    var mediaBridge: MediaBridge? = null

    /** Retains the request observer captured by the WebViewClient across destination reattachment. */
    var mediaInterceptor: MediaInterceptor? = null

    /** Retains dynamic UI routing for WebViewClient and WebChromeClient callbacks after reattachment. */
    var callbacks: RetainedWebViewCallbacks? = null
}

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

    val interceptor = remember {
        WebViewHolder.mediaInterceptor?.also {
            it.updateOnMediaDetected { media, navigationGeneration ->
                viewModel.onMediaDetected(media, navigationGeneration)
            }
        } ?: MediaInterceptor { media, navigationGeneration ->
            viewModel.onMediaDetected(media, navigationGeneration)
        }.also {
            WebViewHolder.mediaInterceptor = it
        }
    }
    val bridge = remember {
        WebViewHolder.mediaBridge?.also {
            it.updateCallbacks(
                onMediaDetected = { media, navigationGeneration ->
                    viewModel.onMediaDetected(media, navigationGeneration)
                },
                onMediaCandidate = { candidate, navigationGeneration ->
                    viewModel.verifyMediaCandidate(candidate, navigationGeneration)
                },
                onPageMeta = viewModel::onPageMeta,
            )
        } ?: MediaBridge(
            onMediaDetected = { media, navigationGeneration ->
                viewModel.onMediaDetected(media, navigationGeneration)
            },
            onMediaCandidate = { candidate, navigationGeneration ->
                viewModel.verifyMediaCandidate(candidate, navigationGeneration)
            },
            onPageMeta = viewModel::onPageMeta,
        ).also { WebViewHolder.mediaBridge = it }
    }
    // Rebind detector callbacks synchronously after every composition so retained objects never route to old state.
    SideEffect {
        interceptor.updateOnMediaDetected { media, navigationGeneration ->
            viewModel.onMediaDetected(media, navigationGeneration)
        }
        bridge.updateCallbacks(
            onMediaDetected = { media, navigationGeneration ->
                viewModel.onMediaDetected(media, navigationGeneration)
            },
            onMediaCandidate = { candidate, navigationGeneration ->
                viewModel.verifyMediaCandidate(candidate, navigationGeneration)
            },
            onPageMeta = viewModel::onPageMeta,
        )
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
    )
    val webViewCallbacks = remember {
        WebViewHolder.callbacks?.also {
            it.update(callbackState)
        } ?: RetainedWebViewCallbacks(
            initialState = callbackState,
        ).also { WebViewHolder.callbacks = it }
    }
    // Keeps every retained WebView client callback bound to the latest Compose-local fullscreen state.
    SideEffect {
        webViewCallbacks.update(callbackState)
    }
    val detectorJs = remember {
        runCatching {
            context.assets.open("detection/chaya_media_detector.js")
                .bufferedReader().readText()
        }.getOrDefault("")
    }

    // Stops old-page callbacks and HEAD work before any explicit top-level navigation request reaches WebView.
    fun invalidateDetectionSession() {
        bridge.invalidateNavigation()
        interceptor.invalidateNavigation()
        webViewCallbacks.onNavigationInvalidated()
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
        WebViewHolder.instance?.loadUrl(targetUrl)
    }

    LaunchedEffect(uiState.thoroughScanRequest) {
        val scanRequest = uiState.thoroughScanRequest ?: return@LaunchedEffect
        val webView = WebViewHolder.instance ?: return@LaunchedEffect
        if (uiState.homeVisible ||
            uiState.url != scanRequest.pageUrl ||
            uiState.navigationGeneration != scanRequest.navigationGeneration ||
            webView.url != scanRequest.pageUrl ||
            !bridge.enableThoroughScan(scanRequest.pageUrl, scanRequest.navigationGeneration)
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
        WebViewHolder.instance?.goBack()
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
                                    WebViewHolder.instance?.reload()
                                }
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
                                WebViewHolder.instance?.goBack()
                            }

                            NavAction(
                                icon = Icons.AutoMirrored.Filled.ArrowForward,
                                label = "Forward",
                                enabled = uiState.canGoForward
                            ) {
                                invalidateDetectionSession()
                                WebViewHolder.instance?.goForward()
                            }

                            NavAction(
                                icon = Icons.Default.Home,
                                label = "Home",
                                enabled = true
                            ) {
                                invalidateDetectionSession()
                                WebViewHolder.instance?.loadUrl("about:blank")
                                viewModel.goHome()
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
                AndroidView(
                    factory = { ctx ->
                        WebViewHolder.instance?.let { prev ->
                            (prev.parent as? ViewGroup)?.removeView(prev)
                            return@AndroidView prev
                        }
                        createChayaWebView(
                            ctx = ctx,
                            bridge = bridge,
                            interceptor = interceptor,
                            detectorJs = detectorJs,
                            callbacks = webViewCallbacks,
                        ).also { WebViewHolder.instance = it }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { /* keep instance alive for reattachment */ }
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
                AnimatedVisibility(
                    visible = linkPillShown || (!uiState.homeVisible && !sheetModel.isEmpty),
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
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            )
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
    detectorJs: String,
    callbacks: RetainedWebViewCallbacks,
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

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                val pageUrl = url ?: run {
                    invalidateDetectorNavigation()
                    return
                }
                val navigationGeneration = callbacks.onPageStarted(pageUrl)
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

            /** target="_blank" links open in the same WebView instead of doing nothing. */
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
