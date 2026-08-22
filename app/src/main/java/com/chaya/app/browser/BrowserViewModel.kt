package com.chaya.app.browser

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chaya.app.ChayaApplication
import com.chaya.app.download.DownloadManager
import com.chaya.app.model.DetectedMedia
import com.chaya.app.streaming.ManifestHelper
import com.chaya.app.streaming.StreamTrack
import com.chaya.app.ui.components.QualityPickerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BrowserUiState(
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
    val qualityPickerState: QualityPickerState? = null
)

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val downloadManager: DownloadManager
        get() = (getApplication<ChayaApplication>()).downloadManager

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    fun onUrlChanged(url: String) {
        _uiState.value = _uiState.value.copy(url = url)
    }

    fun onPageStarted(url: String) {
        if (url == "about:blank") return  // home reset — keep start screen visible
        _uiState.value = _uiState.value.copy(
            url = url,
            homeVisible = false,
            isLoading = true,
            progress = 0,
            detectedMedia = emptyList(),
            showMediaSheet = false,
            qualityPickerState = null
        )
    }

    fun onPageFinished(url: String, title: String) {
        if (url == "about:blank") return
        _uiState.value = _uiState.value.copy(
            url = url,
            pageTitle = title,
            isLoading = false,
            progress = 100
        )
    }

    fun onProgressChanged(progress: Int) {
        _uiState.value = _uiState.value.copy(progress = progress)
    }

    fun onNavigationStateChanged(canGoBack: Boolean, canGoForward: Boolean) {
        _uiState.value = _uiState.value.copy(
            canGoBack = canGoBack,
            canGoForward = canGoForward
        )
    }

    /** Show the start screen again (Home button). */
    fun goHome() {
        _uiState.value = _uiState.value.copy(
            homeVisible = true,
            isLoading = false,
            detectedMedia = emptyList(),
            showMediaSheet = false,
            qualityPickerState = null
        )
    }

    fun onMediaDetected(media: DetectedMedia) {
        val current = _uiState.value.detectedMedia
        if (current.none { it.normalizedUrl == media.normalizedUrl }) {
            _uiState.value = _uiState.value.copy(
                detectedMedia = current + media
            )
        }
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
