package com.chaya.app.browser

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chaya.app.ChayaApplication
import com.chaya.app.detection.sniffContentType
import com.chaya.app.download.DownloadManager
import com.chaya.app.model.DetectionSource
import com.chaya.app.model.DetectedMedia
import com.chaya.app.streaming.ManifestHelper
import com.chaya.app.streaming.StreamTrack
import com.chaya.app.ui.components.QualityPickerState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Lets ordinary DOM detection settle before asking the user to opt into extra network checks. */
private const val THOROUGH_SCAN_OFFER_DELAY_MILLIS = 1_500L

/** Binds a one-shot scan action to the document generation that received the user's consent. */
data class ThoroughScanRequest(
    val pageUrl: String,
    val navigationGeneration: Long,
)

/** Holds browser state that must reset at each top-level WebView navigation. */
data class BrowserUiState(
    /** Distinguishes identical URLs loaded by different top-level document navigations. */
    val navigationGeneration: Long = 0L,
    val url: String = "",
    val pageTitle: String = "",
    /** True while the start screen overlay should cover the WebView. */
    val homeVisible: Boolean = true,
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val detectedMedia: List<DetectedMedia> = emptyList(),
    val showMediaSheet: Boolean = false,
    /** Shows an explicit opt-in only when ordinary detection found no media. */
    val showThoroughScan: Boolean = false,
    /** Delivers a one-shot request for BrowserScreen to invoke the injected detector. */
    val thoroughScanRequest: ThoroughScanRequest? = null,
    val qualityPickerState: QualityPickerState? = null
)

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val downloadManager: DownloadManager
        get() = (getApplication<ChayaApplication>()).downloadManager

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    /** Issues monotonically increasing document identities outside state updates that may retry their lambda. */
    private val pageGeneration = AtomicLong(0L)

    /** Retains cancellable native HEAD checks so navigation stops requests that no longer serve the visible page. */
    private val mediaVerificationJobs = ConcurrentHashMap.newKeySet<Job>()

    fun onUrlChanged(url: String) {
        _uiState.value = _uiState.value.copy(url = url)
    }

    /** Retires the current document immediately so late callbacks cannot revive its media state before the next page starts. */
    fun onNavigationInvalidated() {
        cancelMediaVerifications()
        val navigationGeneration = pageGeneration.incrementAndGet()
        _uiState.update { state ->
            state.copy(
                navigationGeneration = navigationGeneration,
                detectedMedia = emptyList(),
                showMediaSheet = false,
                showThoroughScan = false,
                thoroughScanRequest = null,
                qualityPickerState = null,
            )
        }
    }

    /** Resets page-local state and returns the generation that bridge callbacks must carry for this document. */
    fun onPageStarted(url: String): Long {
        cancelMediaVerifications()
        val navigationGeneration = pageGeneration.incrementAndGet()
        _uiState.update { state ->
            if (url == "about:blank") {
                state.copy(
                    navigationGeneration = navigationGeneration,
                    url = "",
                    pageTitle = "",
                    homeVisible = true,
                    isLoading = false,
                    progress = 0,
                    detectedMedia = emptyList(),
                    showMediaSheet = false,
                    showThoroughScan = false,
                    thoroughScanRequest = null,
                    qualityPickerState = null,
                )
            } else {
                state.copy(
                    navigationGeneration = navigationGeneration,
                    url = url,
                    homeVisible = false,
                    isLoading = true,
                    progress = 0,
                    detectedMedia = emptyList(),
                    showMediaSheet = false,
                    showThoroughScan = false,
                    thoroughScanRequest = null,
                    qualityPickerState = null,
                )
            }
        }
        return navigationGeneration
    }

    /** Finalizes only the matching document generation before scheduling its optional deeper media scan. */
    fun onPageFinished(url: String, title: String, navigationGeneration: Long) {
        val state = _uiState.value
        if (url == "about:blank" ||
            url != state.url ||
            navigationGeneration != state.navigationGeneration
        ) return
        _uiState.update { activeState ->
            if (activeState.navigationGeneration != navigationGeneration || activeState.url != url) {
                activeState
            } else {
                activeState.copy(
                    url = url,
                    pageTitle = title,
                    isLoading = false,
                    progress = 100,
                )
            }
        }
        offerThoroughScanAfterDelay(url = url, navigationGeneration = navigationGeneration)
    }

    /** Ignores late progress callbacks from a document that no longer owns the browser UI. */
    fun onProgressChanged(progress: Int, navigationGeneration: Long) {
        _uiState.update { state ->
            if (state.navigationGeneration == navigationGeneration) {
                state.copy(progress = progress)
            } else {
                state
            }
        }
    }

    fun onNavigationStateChanged(canGoBack: Boolean, canGoForward: Boolean) {
        _uiState.update { state ->
            state.copy(
                canGoBack = canGoBack,
                canGoForward = canGoForward,
            )
        }
    }

    /** Clears page-local detection state when the browser returns to its home overlay. */
    fun goHome() {
        cancelMediaVerifications()
        val navigationGeneration = pageGeneration.incrementAndGet()
        _uiState.update { state ->
            state.copy(
                navigationGeneration = navigationGeneration,
                url = "",
                pageTitle = "",
                homeVisible = true,
                isLoading = false,
                progress = 0,
                detectedMedia = emptyList(),
                showMediaSheet = false,
                showThoroughScan = false,
                thoroughScanRequest = null,
                qualityPickerState = null,
            )
        }
    }

    /** Merges only the active document's concurrent WebView callbacks so stale media cannot overwrite its state. */
    fun onMediaDetected(media: DetectedMedia, navigationGeneration: Long? = null) {
        _uiState.update { state ->
            if (navigationGeneration != null && navigationGeneration != state.navigationGeneration) {
                state
            } else if (media.pageUrl != null && media.pageUrl != state.url) {
                state
            } else if (state.detectedMedia.none { it.normalizedUrl == media.normalizedUrl }) {
                state.copy(
                    detectedMedia = state.detectedMedia + media,
                    showThoroughScan = false,
                )
            } else {
                state
            }
        }
    }

    /** Converts the delayed affordance into one page-bound JavaScript scan request for the active page. */
    fun requestThoroughScan() {
        _uiState.update { state ->
            if (!state.showThoroughScan || state.homeVisible || state.detectedMedia.isNotEmpty()) {
                state
            } else {
                state.copy(
                    showThoroughScan = false,
                    thoroughScanRequest = ThoroughScanRequest(
                        pageUrl = state.url,
                        navigationGeneration = state.navigationGeneration,
                    ),
                )
            }
        }
    }

    /** Clears a dispatched request so recomposition cannot execute the same user opt-in twice. */
    fun completeThoroughScanRequest(request: ThoroughScanRequest) {
        _uiState.update { state ->
            if (state.thoroughScanRequest == request) {
                state.copy(thoroughScanRequest = null)
            } else {
                state
            }
        }
    }

    /** Verifies untyped same-origin fetch/XHR candidates before presenting them as downloadable media. */
    fun verifyMediaCandidate(candidate: DetectedMedia, navigationGeneration: Long) {
        if (candidate.source != DetectionSource.XHR_FETCH) return

        val verification = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val pageUrl = candidate.pageUrl ?: return@launch
            val startingState = _uiState.value
            if (startingState.navigationGeneration != navigationGeneration || pageUrl != startingState.url) {
                return@launch
            }
            val mimeType = sniffContentType(
                url = candidate.url,
                headers = headersForContentTypeSniff(candidate.url, pageUrl),
            ) ?: return@launch

            val completedState = _uiState.value
            if (completedState.navigationGeneration != navigationGeneration || pageUrl != completedState.url) {
                return@launch
            }
            onMediaDetected(
                media = candidate.copy(mimeType = mimeType),
                navigationGeneration = navigationGeneration,
            )
        }
        mediaVerificationJobs += verification
        verification.invokeOnCompletion { mediaVerificationJobs.remove(verification) }
        verification.start()
    }

    /** Builds only target-scoped browser headers for the additive verification request. */
    private fun headersForContentTypeSniff(url: String, pageUrl: String): Map<String, String> {
        return buildMap {
            runCatching { WebSettings.getDefaultUserAgent(getApplication()) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { put("User-Agent", it) }
            runCatching { CookieManager.getInstance().getCookie(url) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { put("Cookie", it) }
            put("Referer", pageUrl)
        }
    }

    /** Offers deeper fetch/XHR inspection only if this exact document still has no ordinary detections. */
    private fun offerThoroughScanAfterDelay(url: String, navigationGeneration: Long) {
        viewModelScope.launch {
            delay(THOROUGH_SCAN_OFFER_DELAY_MILLIS)
            _uiState.update { state ->
                if (navigationGeneration != state.navigationGeneration ||
                    state.url != url ||
                    state.homeVisible ||
                    state.detectedMedia.isNotEmpty()
                ) {
                    state
                } else {
                    state.copy(showThoroughScan = true)
                }
            }
        }
    }

    /** Cancels requests carrying prior-page cookies before a new document can make them irrelevant. */
    private fun cancelMediaVerifications() {
        mediaVerificationJobs.forEach { it.cancel() }
        mediaVerificationJobs.clear()
    }

    /** Start manifest analysis — shows loading, then quality picker or error. */
    fun analyzeStream(media: DetectedMedia) {
        _uiState.value = _uiState.value.copy(
            qualityPickerState = QualityPickerState.Loading
        )

        viewModelScope.launch {
            val result = ManifestHelper.parse(
                context = getApplication(),
                url = media.url,
                mimeType = media.mimeType,
                userAgent = WebSettings.getDefaultUserAgent(getApplication()),
                cookies = runCatching {
                    CookieManager.getInstance().getCookie(media.url)
                }.getOrNull()
            )

            result.fold(
                onSuccess = { tracks ->
                    if (tracks.isEmpty()) {
                        // No selectable tracks — download everything
                        dismissQualityPicker()
                        downloadManager.startDownload(media)
                    } else {
                        _uiState.value = _uiState.value.copy(
                            qualityPickerState = QualityPickerState.Ready(
                                tracks = tracks,
                                url = media.url,
                                mimeType = media.mimeType
                            )
                        )
                    }
                },
                onFailure = { error ->
                    // Keep the URL so "download anyway" still works.
                    _uiState.value = _uiState.value.copy(
                        qualityPickerState = QualityPickerState.Error(
                            message = error.message ?: "Failed to analyze stream",
                            url = media.url,
                            mimeType = media.mimeType
                        )
                    )
                }
            )
        }
    }

    /** Download stream with user-selected tracks. */
    fun downloadStream(tracks: List<StreamTrack>) {
        val state = _uiState.value.qualityPickerState
        if (state !is QualityPickerState.Ready) return

        _uiState.value = _uiState.value.copy(qualityPickerState = null)
        val media = DetectedMedia(
            url = state.url,
            pageUrl = _uiState.value.url,
            mimeType = state.mimeType,
            source = com.chaya.app.model.DetectionSource.MANIFEST
        )

        val streamKeys = tracks.flatMap { it.streamKeys }
        if (streamKeys.isEmpty()) {
            downloadManager.startDownload(media)
        } else {
            downloadManager.startDownload(media, streamKeys)
        }
    }

    /** Fallback: download the stream at default quality when analysis failed. */
    fun downloadStreamFallback() {
        val state = _uiState.value.qualityPickerState
        if (state !is QualityPickerState.Error) return

        _uiState.value = _uiState.value.copy(qualityPickerState = null)
        val media = DetectedMedia(
            url = state.url,
            pageUrl = _uiState.value.url,
            mimeType = state.mimeType,
            source = com.chaya.app.model.DetectionSource.MANIFEST
        )
        downloadManager.startDownload(media)
    }

    fun dismissQualityPicker() {
        _uiState.value = _uiState.value.copy(qualityPickerState = null)
    }

    fun downloadMedia(media: DetectedMedia) {
        downloadManager.startDownload(media)
    }

    fun cancelDownload(taskId: Long) {
        downloadManager.cancelDownload(taskId)
    }

    fun toggleMediaSheet() {
        _uiState.value = _uiState.value.copy(
            showMediaSheet = !_uiState.value.showMediaSheet
        )
    }

    fun dismissMediaSheet() {
        _uiState.value = _uiState.value.copy(showMediaSheet = false)
    }
}
