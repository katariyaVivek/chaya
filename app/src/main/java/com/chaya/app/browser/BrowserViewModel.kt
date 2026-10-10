package com.chaya.app.browser

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chaya.app.ChayaApplication
import com.chaya.app.detection.MediaNamer
import com.chaya.app.detection.MediaUrlClassifier
import com.chaya.app.detection.PageMeta
import com.chaya.app.detection.sniffContentType
import com.chaya.app.diagnostics.ChayaEvent
import com.chaya.app.download.DownloadManager
import com.chaya.app.download.DownloadTask
import com.chaya.app.model.DetectionSource
import com.chaya.app.model.DetectedMedia
import com.chaya.app.platform.LinkState
import com.chaya.app.platform.PlatformChoice
import com.chaya.app.platform.PlatformFormat
import com.chaya.app.platform.Platform
import com.chaya.app.platform.PlatformLinks
import com.chaya.app.platform.ProfileMatch
import com.chaya.app.platform.ProfileMatcher
import com.chaya.app.platform.SignIn
import com.chaya.app.platform.WebViewSignIn
import com.chaya.app.platform.toDownloadRequest
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
import java.util.Locale
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
    /** Page and player hints from the injected detector, used to rank and name [detectedMedia]. */
    val pageMeta: PageMeta? = null,
    /** Stream pieces seen on this page; counted for the sheet footnote, never listed. */
    val hiddenSegmentCount: Int = 0,
    val showMediaSheet: Boolean = false,
    /** Shows an explicit opt-in only when ordinary detection found no media. */
    val showThoroughScan: Boolean = false,
    /** Delivers a one-shot request for BrowserScreen to invoke the injected detector. */
    val thoroughScanRequest: ThoroughScanRequest? = null,
    val qualityPickerState: QualityPickerState? = null,
    /** Shows the sheet for the video behind a link on a supported site. */
    val showLinkSheet: Boolean = false,
    /** The Instagram or X account the page or the pasted link is, whose posts can be saved as one ZIP. */
    val profile: ProfileMatch? = null,
    val showProfileSheet: Boolean = false,
)

class BrowserViewModel @JvmOverloads constructor(
    application: Application,
    links: PlatformLinks? = null,
    signIn: SignIn? = null,
) : AndroidViewModel(application) {
    /** The browser's sign-in on a site, offered (never used on its own) for saving an account's posts. */
    private val signIn: SignIn = signIn ?: WebViewSignIn(application)

    /**
     * Looks up the video behind a link on YouTube, Instagram, TikTok or X, for the pill on such a page and for
     * a link that was pasted or shared.
     */
    private val links: PlatformLinks =
        links ?: PlatformLinks(viewModelScope, (application as ChayaApplication).platformEngine, WebViewSignIn(application))

    /** What is known about the video behind the current or pasted link. */
    val linkState: StateFlow<LinkState> get() = this.links.state

    private val downloadManager: DownloadManager
        get() = (getApplication<ChayaApplication>()).downloadManager

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    /** Every download, for the Downloads tab badge and the start screen's recent list. */
    val downloads: StateFlow<List<DownloadTask>>
        get() = downloadManager.downloads

    /** Issues monotonically increasing document identities outside state updates that may retry their lambda. */
    private val pageGeneration = AtomicLong(0L)

    /** Retains cancellable native HEAD checks so navigation stops requests that no longer serve the visible page. */
    private val mediaVerificationJobs = ConcurrentHashMap.newKeySet<Job>()

    /** Generation-scoped keys of stream pieces already counted, so both detection layers count each once. */
    private val countedSegments = ConcurrentHashMap.newKeySet<String>()

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
                pageMeta = null,
                hiddenSegmentCount = 0,
                showMediaSheet = false,
                showThoroughScan = false,
                thoroughScanRequest = null,
                qualityPickerState = null,
                showLinkSheet = false,
            )
        }
    }

    /** Resets page-local state and returns the generation that bridge callbacks must carry for this document. */
    fun onPageStarted(url: String): Long {
        cancelMediaVerifications()
        val navigationGeneration = pageGeneration.incrementAndGet()
        _uiState.update { state -> state.startedPage(url, navigationGeneration) }
        followAddress(url)
        return navigationGeneration
    }

    /** A new document identity for a page loading in a tab that is not shown. */
    fun nextNavigationGeneration(): Long = pageGeneration.incrementAndGet()

    /**
     * Shows another tab. Returns the page state of the tab being left, to keep while it is in the background,
     * and takes [incoming]: the state kept for the tab being shown, or null for a new tab, which opens on the
     * start screen. Sheets close and work for the page being left stops; the link and account offers follow
     * the page now shown. [canGoBack] and [canGoForward] come from the tab's own history.
     */
    fun switchTab(incoming: BrowserUiState?, canGoBack: Boolean, canGoForward: Boolean): BrowserUiState {
        cancelMediaVerifications()
        val outgoing = _uiState.value.withSheetsClosed()
        val shown = (incoming ?: BrowserUiState(navigationGeneration = pageGeneration.incrementAndGet()))
            .withSheetsClosed()
            .copy(canGoBack = canGoBack, canGoForward = canGoForward)
        _uiState.value = shown
        if (shown.homeVisible || shown.url.isEmpty()) {
            links.clear()
            _uiState.update { it.copy(profile = null) }
        } else {
            followAddress(shown.url)
        }
        return outgoing
    }

    /** A page that is one video asks the engine about it; a page that is an account offers to save its posts. */
    private fun followAddress(url: String) {
        val isVideo = links.look(url)
        val profile = if (isVideo) null else ProfileMatcher.match(url)
        _uiState.update { state ->
            if (state.profile == profile) state
            else state.copy(profile = profile, showProfileSheet = state.showProfileSheet && profile != null)
        }
    }

    /**
     * The page's address changed without a new document, as a video site does when it moves to another video.
     * Only the link lookup follows this: the page state above is deliberately tied to whole documents.
     */
    fun onPageAddressChanged(url: String) {
        followAddress(url)
    }

    /** Finalizes only the matching document generation before scheduling its optional deeper media scan. */
    fun onPageFinished(url: String, title: String, navigationGeneration: Long) {
        val state = _uiState.value
        if (url == "about:blank" ||
            url != state.url ||
            navigationGeneration != state.navigationGeneration
        ) return
        _uiState.update { activeState -> activeState.finishedPage(url, title, navigationGeneration) }
        getApplication<ChayaApplication>().eventLog.record(
            ChayaEvent.PageLoaded(url = ChayaEvent.scrubbed(url) ?: url)
        )
        offerThoroughScanAfterDelay(url = url, navigationGeneration = navigationGeneration)
    }

    /** Ignores late progress callbacks from a document that no longer owns the browser UI. */
    fun onProgressChanged(progress: Int, navigationGeneration: Long) {
        _uiState.update { state -> state.withProgress(progress, navigationGeneration) }
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
        links.clear()
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
                pageMeta = null,
                hiddenSegmentCount = 0,
                showMediaSheet = false,
                showThoroughScan = false,
                thoroughScanRequest = null,
                qualityPickerState = null,
                showLinkSheet = false,
                profile = null,
                showProfileSheet = false,
            )
        }
    }

    /** Merges only the active document's concurrent WebView callbacks so stale media cannot overwrite its state. */
    fun onMediaDetected(media: DetectedMedia, navigationGeneration: Long? = null) {
        // Every byte-range request of one file is the same download: the whole file.
        val whole = media.copy(url = MediaUrlClassifier.withoutRangeParams(media.url))
        if (MediaUrlClassifier.isSegment(whole.url)) {
            countSegment(whole, navigationGeneration)
            return
        }
        var recorded = false
        _uiState.update { state ->
            if (navigationGeneration != null && navigationGeneration != state.navigationGeneration) {
                state
            } else if (whole.pageUrl != null && whole.pageUrl != state.url) {
                state
            } else if (state.detectedMedia.none { it.normalizedUrl == whole.normalizedUrl }) {
                recorded = true
                state.copy(
                    detectedMedia = state.detectedMedia + whole,
                    showThoroughScan = false,
                )
            } else {
                state
            }
        }
        if (recorded) {
            getApplication<ChayaApplication>().eventLog.record(
                ChayaEvent.MediaDetected(
                    url = ChayaEvent.scrubbed(whole.url) ?: whole.url,
                    source = whole.source.name,
                    mimeType = whole.mimeType,
                )
            )
        }
    }

    /** Counts a stream piece once per document; pieces are never listed or logged. */
    private fun countSegment(media: DetectedMedia, navigationGeneration: Long?) {
        val state = _uiState.value
        if (navigationGeneration != null && navigationGeneration != state.navigationGeneration) return
        if (media.pageUrl != null && media.pageUrl != state.url) return
        val key = "${state.navigationGeneration}\u0000${media.normalizedUrl}"
        if (!countedSegments.add(key)) return
        _uiState.update { current ->
            if (current.navigationGeneration != state.navigationGeneration) {
                current
            } else {
                current.copy(hiddenSegmentCount = current.hiddenSegmentCount + 1)
            }
        }
    }

    /** Keeps the active document's latest page and player hints; reports from a retired document are dropped. */
    fun onPageMeta(meta: PageMeta, navigationGeneration: Long) {
        _uiState.update { state ->
            if (navigationGeneration != state.navigationGeneration) state else state.copy(pageMeta = meta)
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

    /**
     * Start manifest analysis — shows loading, then quality picker or error.
     * [durationSeconds], when the page's player knows it, lets the picker estimate sizes.
     */
    fun analyzeStream(media: DetectedMedia, durationSeconds: Double? = null) {
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
                                mimeType = media.mimeType,
                                suggestedName = media.suggestedName,
                                durationSeconds = durationSeconds,
                                title = media.title,
                                thumbnailUrl = media.thumbnailUrl,
                            )
                        )
                    }
                },
                onFailure = { error -> showStreamError(media, error) }
            )
        }
    }

    /**
     * One tap: reads the stream's renditions and starts the best one with its audio, without the picker.
     * [onStarted] gets the chosen quality (e.g. "1080p") once the download is queued. If the manifest
     * can't be read, the picker opens with its "Download anyway" fallback instead.
     */
    fun downloadStreamBest(media: DetectedMedia, onStarted: (String?) -> Unit = {}) {
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
                    val chosen = ManifestHelper.bestSelection(tracks)
                    startStreamDownload(media, chosen)
                    onStarted(chosen.maxOfOrNull { it.height }?.takeIf { it > 0 }?.let { "${it}p" })
                },
                onFailure = { error -> showStreamError(media, error) }
            )
        }
    }

    /** Keeps the URL so the picker's "Download anyway" still works after a failed analysis. */
    private fun showStreamError(media: DetectedMedia, error: Throwable) {
        _uiState.value = _uiState.value.copy(
            qualityPickerState = QualityPickerState.Error(
                message = error.message ?: "Failed to analyze stream",
                url = media.url,
                mimeType = media.mimeType,
                suggestedName = media.suggestedName,
                title = media.title,
                thumbnailUrl = media.thumbnailUrl,
            )
        )
    }

    /** Download stream with user-selected tracks. */
    fun downloadStream(tracks: List<StreamTrack>) {
        val state = _uiState.value.qualityPickerState
        if (state !is QualityPickerState.Ready) return

        _uiState.value = _uiState.value.copy(qualityPickerState = null)
        startStreamDownload(
            base = DetectedMedia(
                url = state.url,
                pageUrl = null,
                mimeType = state.mimeType,
                source = DetectionSource.MANIFEST,
                suggestedName = state.suggestedName,
                title = state.title,
                thumbnailUrl = state.thumbnailUrl,
            ),
            tracks = tracks,
        )
    }

    /** Starts [base] with exactly [tracks]; the chosen rendition, not the one the page was playing, names the file. */
    private fun startStreamDownload(base: DetectedMedia, tracks: List<StreamTrack>) {
        val chosenHeight = tracks.maxOfOrNull { it.height }?.takeIf { it > 0 }
        val media = base.copy(
            pageUrl = _uiState.value.url,
            source = DetectionSource.MANIFEST,
            suggestedName = base.suggestedName?.let { MediaNamer.fileBaseName(it, chosenHeight) },
            qualityHeight = chosenHeight,
        )

        val streamKeys = ManifestHelper.streamKeysFor(tracks)
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
            source = com.chaya.app.model.DetectionSource.MANIFEST,
            suggestedName = state.suggestedName,
            title = state.title,
            thumbnailUrl = state.thumbnailUrl,
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

    // ------------------------------------------------------------------ //
    // Links to a video on YouTube, Instagram, TikTok or X
    // ------------------------------------------------------------------ //

    /**
     * Opens a pasted or shared link: looks up its video and shows the sheet. [text] may be a whole sentence,
     * as the YouTube app shares one. Returns false when it holds no link to one video on a supported site.
     */
    fun openLink(text: String): Boolean {
        val url = LINK_IN_TEXT.find(text)?.value?.trimEnd('.', ',', ')', ']', '>', '"', '\'') ?: text.trim()
        if (links.look(url)) {
            _uiState.update { it.copy(showLinkSheet = true) }
            return true
        }
        val profile = ProfileMatcher.match(url) ?: return false
        _uiState.update { it.copy(profile = profile, showProfileSheet = true) }
        return true
    }

    // ------------------------------------------------------------------ //
    // An Instagram or X account, saved as one ZIP
    // ------------------------------------------------------------------ //

    fun toggleProfileSheet() {
        _uiState.update { it.copy(showProfileSheet = it.profile != null && !it.showProfileSheet) }
    }

    fun dismissProfileSheet() {
        _uiState.update { it.copy(showProfileSheet = false) }
    }

    /** Whether the browser here is signed in to [platform], so saving with that sign-in can be offered. */
    fun isSignedIn(platform: Platform): Boolean = signIn.isSignedIn(platform)

    /**
     * Starts saving every photo and video the current account has posted, as one ZIP. [useSignIn] is the
     * person's choice, made on the sheet, to let the site see their sign-in. [onStarted] gets the file name.
     */
    fun saveProfile(useSignIn: Boolean, onStarted: (String) -> Unit = {}) {
        val profile = _uiState.value.profile ?: return
        val request = profile.archiveRequest(useSignIn)
        downloadManager.startArchive(request)
        _uiState.update { it.copy(showProfileSheet = false) }
        onStarted(request.fileName)
    }

    fun toggleLinkSheet() {
        _uiState.update { it.copy(showLinkSheet = !it.showLinkSheet) }
    }

    fun dismissLinkSheet() {
        _uiState.update { it.copy(showLinkSheet = false) }
    }

    /** Asks again about the current link; [useSignIn] retries with the account the browser is signed in to. */
    fun retryLink(useSignIn: Boolean = false) {
        links.retry(useSignIn)
    }

    /**
     * Saves the video behind the current link at [choice]'s quality, and closes the sheet. A plain file, or a
     * picture and sound to join, goes to the engine's downloader; a streaming manifest goes through the stream
     * downloader, as one found in a page does. [onStarted] gets the quality, for a message. An answer whose
     * addresses may have expired is looked up again first, and the sheet stays open to show the new one.
     */
    fun downloadLink(choice: PlatformChoice, onStarted: (String) -> Unit = {}) {
        val found = links.freshFound() ?: return
        _uiState.update { it.copy(showLinkSheet = false) }
        if (choice.file.isDirectFile) {
            downloadManager.startDownload(choice.toDownloadRequest(found.media, pageUrl = found.match.url))
        } else {
            downloadManager.startDownload(streamMediaFor(found, choice))
        }
        onStarted(choice.label)
    }

    /**
     * Saves the items at [indices] (from 0) of the post behind the current link, each as its own file, and
     * closes the sheet. [onStarted] gets how many were queued. A post whose addresses may have expired is looked
     * up again first, and the sheet stays open to show it.
     */
    fun downloadPost(indices: List<Int>, onStarted: (Int) -> Unit = {}) {
        val post = links.freshAnswer() as? LinkState.FoundPost ?: return
        val chosen = indices.distinct().filter { it in post.items.indices }
        if (chosen.isEmpty()) return
        _uiState.update { it.copy(showLinkSheet = false) }
        chosen.forEach { index ->
            val request = post.items[index].toDownloadRequest(
                post.media, number = index + 1, count = post.items.size, pageUrl = post.match.url,
            )
            downloadManager.startDownload(request)
        }
        onStarted(chosen.size)
    }

    private companion object {
        /** The first address in a piece of text, such as the sentence a share button produces. */
        val LINK_IN_TEXT = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    }
}

/**
 * A streaming quality (an HLS or DASH manifest) in the form the stream downloader takes for a stream found in
 * a page, named after the video and the quality.
 */
internal fun streamMediaFor(found: LinkState.Found, choice: PlatformChoice): DetectedMedia = DetectedMedia(
    url = choice.file.url,
    pageUrl = found.match.url,
    mimeType = if (isDash(choice.file)) "application/dash+xml" else "application/x-mpegURL",
    source = DetectionSource.MANIFEST,
    suggestedName = MediaNamer.fileBaseName(found.media.title, choice.quality),
    title = found.media.title,
    thumbnailUrl = found.media.thumbnailUrl,
    qualityHeight = choice.quality,
)

/** The engine names a DASH stream in its protocol; an address can say `.mpd` too, but need not. */
private fun isDash(format: PlatformFormat): Boolean {
    val protocol = format.protocol?.lowercase(Locale.ROOT).orEmpty()
    return "dash" in protocol || ("m3u" !in protocol && ".mpd" in format.url.lowercase(Locale.ROOT))
}

/** The state for a new document at [url]; about:blank is the start screen. */
internal fun BrowserUiState.startedPage(url: String, navigationGeneration: Long): BrowserUiState =
    if (url == "about:blank") {
        copy(
            navigationGeneration = navigationGeneration,
            url = "",
            pageTitle = "",
            homeVisible = true,
            isLoading = false,
            progress = 0,
            detectedMedia = emptyList(),
            pageMeta = null,
            hiddenSegmentCount = 0,
            showMediaSheet = false,
            showThoroughScan = false,
            thoroughScanRequest = null,
            qualityPickerState = null,
            showLinkSheet = false,
        )
    } else {
        copy(
            navigationGeneration = navigationGeneration,
            url = url,
            homeVisible = false,
            isLoading = true,
            progress = 0,
            detectedMedia = emptyList(),
            pageMeta = null,
            hiddenSegmentCount = 0,
            showMediaSheet = false,
            showThoroughScan = false,
            thoroughScanRequest = null,
            qualityPickerState = null,
            showLinkSheet = false,
            showProfileSheet = false,
        )
    }

/** The document [navigationGeneration] at [url] has loaded; a late report from another document changes nothing. */
internal fun BrowserUiState.finishedPage(url: String, title: String, navigationGeneration: Long): BrowserUiState =
    if (this.navigationGeneration != navigationGeneration || this.url != url) this
    else copy(url = url, pageTitle = title, isLoading = false, progress = 100)

/** Loading progress for the document [navigationGeneration] only. */
internal fun BrowserUiState.withProgress(progress: Int, navigationGeneration: Long): BrowserUiState =
    if (this.navigationGeneration == navigationGeneration) copy(progress = progress) else this

/** The same page with every sheet and pending request closed, as a tab is when it is left. */
internal fun BrowserUiState.withSheetsClosed(): BrowserUiState = copy(
    showMediaSheet = false,
    thoroughScanRequest = null,
    qualityPickerState = null,
    showLinkSheet = false,
    showProfileSheet = false,
)
