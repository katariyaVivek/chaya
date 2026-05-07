package com.chaya.app.browser

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chaya.app.detection.MediaBridge
import com.chaya.app.detection.MediaInterceptor
import com.chaya.app.model.DetectedMedia
import com.chaya.app.streaming.StreamDownloader
import com.chaya.app.ui.components.DetectedMediaSheet
import com.chaya.app.ui.components.QualitySelectorSheet
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    onNavigateToDownloads: () -> Unit,
    viewModel: BrowserViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var urlInput by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Pending download waiting for notification permission
    var pendingMedia by remember { mutableStateOf<DetectedMedia?>(null) }

    // POST_NOTIFICATIONS permission launcher (Android 13+)
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingMedia?.let { media ->
            pendingMedia = null
            if (granted) {
                viewModel.downloadMedia(media)
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "Downloading: ${media.url.substringAfterLast("/").take(40)}",
                        duration = SnackbarDuration.Short
                    )
                }
            } else {
                viewModel.downloadMedia(media) // download still works, just no notifications
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "Notifications disabled — downloads continue in background",
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
    }

    // Create the interceptor once, wired to the ViewModel
    val interceptor = remember {
        MediaInterceptor { media ->
            viewModel.onMediaDetected(media)
        }
    }

    fun startDownload(media: DetectedMedia) {
        viewModel.downloadMedia(media)
        scope.launch {
            snackbarHostState.showSnackbar(
                message = "Downloading: ${media.url.substringAfterLast("/").take(40)}",
                duration = SnackbarDuration.Short
            )
        }
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
            startDownload(media)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { scaffoldPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(scaffoldPadding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                // URL bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = urlInput.ifEmpty { uiState.url },
                        onValueChange = { urlInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Enter URL") },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                val url = urlInput.trim()
                                if (url.isNotEmpty()) {
                                    val fullUrl = if (url.startsWith("http://") || url.startsWith("https://")) url
                                    else "https://$url"
                                    webView?.loadUrl(fullUrl)
                                    urlInput = ""
                                    viewModel.onPageStarted(fullUrl)
                                }
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        ),
                        trailingIcon = {
                            if (urlInput.isNotEmpty()) {
                                IconButton(onClick = { urlInput = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        }
                    )
                }

                // Progress bar
                if (uiState.isLoading) {
                    LinearProgressIndicator(
                        progress = { uiState.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // WebView
                AndroidView(
                    factory = { ctx ->
                        val bridge = MediaBridge { media ->
                            viewModel.onMediaDetected(media)
                        }

                        val detectorJs = try {
                            ctx.assets.open("detection/chaya_media_detector.js")
                                .bufferedReader().readText()
                        } catch (_: Exception) {
                            ""
                        }

                        WebView(ctx).apply {
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

                            addJavascriptInterface(bridge, "ChayaBridge")

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    url?.let { viewModel.onPageStarted(it) }
                                    interceptor.clearReportedUrls()
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    url?.let { viewModel.onPageFinished(it, view?.title ?: "") }
                                    view?.let {
                                        viewModel.onNavigationStateChanged(it.canGoBack(), it.canGoForward())
                                        if (detectorJs.isNotEmpty()) {
                                            it.evaluateJavascript(detectorJs, null)
                                        }
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean = false

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    if (request == null) return null
                                    return interceptor.shouldInterceptRequest(
                                        request = request,
                                        pageUrl = view?.url
                                    )
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    viewModel.onProgressChanged(newProgress)
                                }
                            }

                            webView = this
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )

                // Navigation bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { webView?.goBack() },
                        enabled = uiState.canGoBack
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = if (uiState.canGoBack) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }

                    IconButton(
                        onClick = { webView?.goForward() },
                        enabled = uiState.canGoForward
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Forward",
                            tint = if (uiState.canGoForward) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }

                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }

                    IconButton(onClick = onNavigateToDownloads) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "Downloads")
                    }
                }
            }

            // Floating detected-media button
            if (uiState.detectedMedia.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { viewModel.toggleMediaSheet() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 72.dp),
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box {
                        Icon(
                            Icons.Default.FileDownload,
                            contentDescription = "Detected media",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Badge(
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Text("${uiState.detectedMedia.size}")
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
                            // Show quality picker for streaming media
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
                            // Error state fallback — download all tracks
                            viewModel.downloadStreamFallback()
                        } else {
                            viewModel.downloadStream(tracks)
                        }
                    }
                )
            }
        }
    }
}
