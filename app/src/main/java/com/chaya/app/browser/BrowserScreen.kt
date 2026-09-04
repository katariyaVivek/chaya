package com.chaya.app.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material3.Badge
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chaya.app.detection.MediaBridge
import com.chaya.app.detection.MediaInterceptor
import com.chaya.app.model.DetectedMedia
import com.chaya.app.streaming.StreamDownloader
import com.chaya.app.ui.components.DetectedMediaSheet
import com.chaya.app.ui.components.QualitySelectorSheet
import com.chaya.app.ui.theme.ChayaMotion
import com.chaya.app.ui.theme.StaggeredAppear
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

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingMedia?.let { media ->
            pendingMedia = null
            viewModel.downloadMedia(media)
            scope.launch {
                val msg = if (granted) {
                    "Downloading ${media.url.substringAfterLast("/").take(32)}"
                } else {
                    "Downloading (notifications off)"
                }
                snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
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
            )
        } ?: MediaBridge(
            onMediaDetected = { media, navigationGeneration ->
                viewModel.onMediaDetected(media, navigationGeneration)
            },
            onMediaCandidate = { candidate, navigationGeneration ->
                viewModel.verifyMediaCandidate(candidate, navigationGeneration)
            },
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
            pendingMedia = media
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.downloadMedia(media)
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = "Downloading ${media.url.substringAfterLast("/").take(32)}",
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    // URL field focus animation: soft fill shift on focus.
    val urlInteraction = remember { MutableInteractionSource() }
    val urlFocused by urlInteraction.collectIsFocusedAsState()
    val fieldFill by animateColorAsState(
        targetValue = if (urlFocused) MaterialTheme.colorScheme.surfaceContainerHighest
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = ChayaMotion.tweenShort(),
        label = "fieldFill"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = urlInput,
                                onValueChange = { urlInput = it },
                                modifier = Modifier.weight(1f),
                                interactionSource = urlInteraction,
                                placeholder = {
                                    Text(
                                        text = "Search or enter address",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(24.dp),
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = if (urlFocused)
                                            MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                trailingIcon = {
                                    AnimatedVisibility(
                                        visible = urlInput.isNotEmpty(),
                                        enter = scaleIn(initialScale = 0.7f) + fadeIn(ChayaMotion.tweenShort()),
                                        exit = scaleOut(targetScale = 0.7f) + fadeOut(ChayaMotion.tweenShort())
                                    ) {
                                        IconButton(onClick = { urlInput = "" }) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Clear",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Go
                                ),
                                keyboardActions = KeyboardActions(
                                    onGo = { navigateToUrl(urlInput) }
                                ),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = fieldFill,
                                    unfocusedContainerColor = fieldFill,
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent
                                )
                            )

                            Spacer(modifier = Modifier.width(4.dp))

                            IconButton(onClick = onNavigateToDownloads) {
                                Icon(
                                    imageVector = Icons.Default.CloudDownload,
                                    contentDescription = "Downloads",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
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
                                icon = Icons.Default.Refresh,
                                label = "Refresh",
                                enabled = true
                            ) {
                                if (uiState.homeVisible) {
                                    navigateToUrl("https://www.google.com")
                                } else {
                                    invalidateDetectionSession()
                                    WebViewHolder.instance?.reload()
                                }
                            }
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

                StartScreenOverlay(
                    visible = uiState.homeVisible,
                    onSelectUrl = { target -> navigateToUrl(target) }
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

                // Floating detected-media button — spring entrance + live badge
                AnimatedVisibility(
                    visible = !uiState.homeVisible && uiState.detectedMedia.isNotEmpty(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = 20.dp),
                    enter = scaleIn(initialScale = 0.6f, animationSpec = ChayaMotion.springSmooth()) +
                            fadeIn(ChayaMotion.tweenShort()),
                    exit = scaleOut(targetScale = 0.6f, animationSpec = ChayaMotion.tweenShort()) +
                            fadeOut(ChayaMotion.tweenShort())
                ) {
                    FloatingActionButton(
                        onClick = { viewModel.toggleMediaSheet() },
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(
                            defaultElevation = 2.dp,
                            pressedElevation = 1.dp
                        ),
                        modifier = Modifier.pressScale(0.94f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = "Detected media",
                                modifier = Modifier.size(26.dp)
                            )
                            Badge(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.align(Alignment.TopEnd)
                            ) {
                                AnimatedContent(
                                    targetState = uiState.detectedMedia.size,
                                    transitionSpec = {
                                        (slideInVertically(ChayaMotion.tweenShort()) { it } +
                                                fadeIn(ChayaMotion.tweenShort())) togetherWith
                                                (slideOutVertically(ChayaMotion.tweenShort()) { -it } +
                                                fadeOut(ChayaMotion.tweenShort()))
                                    },
                                    label = "badgeCount"
                                ) { count ->
                                    Text("$count", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }

                // Media detection bottom sheet
                if (uiState.showMediaSheet) {
                    DetectedMediaSheet(
                        mediaList = uiState.detectedMedia,
                        onDismiss = { viewModel.dismissMediaSheet() },
                        onDownload = { media ->
                            viewModel.dismissMediaSheet()
                            if (StreamDownloader.isStreamingUrl(media.url) ||
                                StreamDownloader.isStreamingMime(media.mimeType)
                            ) {
                                viewModel.analyzeStream(media)
                            } else {
                                requestPermissionAndDownload(media)
                            }
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

/** Bottom-bar action with consistent disabled styling. */
@Composable
private fun NavAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        )
    }
}

// --------------------------------------------------------------------- //
// Start screen
// --------------------------------------------------------------------- //

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartScreenOverlay(
    visible: Boolean,
    onSelectUrl: (String) -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(ChayaMotion.tweenStandard()),
        exit = fadeOut(ChayaMotion.tweenShort())
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(96.dp))

                StaggeredAppear(index = 0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Chaya",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Find direct video, audio & HLS/DASH streams.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(48.dp))

                StaggeredAppear(index = 1) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "QUICK LINKS",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            QuickChip(
                                icon = Icons.Default.Movie,
                                label = "Big Buck Bunny",
                                url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                                onClick = onSelectUrl
                            )
                            QuickChip(
                                icon = Icons.Default.Stream,
                                label = "HLS test stream",
                                url = "https://test-streams.mux.dev",
                                onClick = onSelectUrl
                            )
                            QuickChip(
                                icon = Icons.Default.Movie,
                                label = "Sample videos",
                                url = "https://www.sample-videos.com",
                                onClick = onSelectUrl
                            )
                            QuickChip(
                                icon = Icons.Default.Audiotrack,
                                label = "Sample audio",
                                url = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
                                onClick = onSelectUrl
                            )
                        }
                    }
                }

                Spacer(Modifier.height(44.dp))

                StaggeredAppear(index = 2) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "HOW IT WORKS",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 14.dp, start = 4.dp)
                        )
                        HowItWorksStep(
                            index = 1,
                            text = "Open any site or paste a link above."
                        )
                        HowItWorksStep(
                            index = 2,
                            text = "Browse and play like normal; media is detected automatically."
                        )
                        HowItWorksStep(
                            index = 3,
                            text = "Tap the floating button to review formats and download."
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HowItWorksStep(index: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$index",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun QuickChip(
    icon: ImageVector,
    label: String,
    url: String,
    onClick: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .pressScale()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick(url) }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
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

/** Prepends the random main-document capability without interpolating page-controlled content. */
private fun detectorScriptWithCapability(detectorJs: String, capability: String): String {
    return "window.__chayaBridgeCapability = '$capability';\n$detectorJs"
}
