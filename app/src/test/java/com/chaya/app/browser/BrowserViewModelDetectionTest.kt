package com.chaya.app.browser

import com.chaya.app.detection.PageMeta
import com.chaya.app.model.DetectionSource
import com.chaya.app.model.DetectedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Verifies that browser UI state refuses late media and page callbacks once a
 * retained WebView navigation has made its prior document obsolete.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserViewModelDetectionTest {

    /** Holds the state owner used to simulate WebView callbacks without a rendered Compose screen. */
    private lateinit var viewModel: BrowserViewModel

    /** Creates a Robolectric application because BrowserViewModel is an AndroidViewModel. */
    @Before
    fun setUp() {
        viewModel = BrowserViewModel(RuntimeEnvironment.getApplication())
    }

    /** Ensures explicit invalidation prevents old bridge work from appearing before the next page starts. */
    @Test
    fun `navigation invalidation clears page media and rejects late callbacks`() {
        val pageUrl = "https://media.example/watch"
        val oldGeneration = viewModel.onPageStarted(pageUrl)
        viewModel.onMediaDetected(
            DetectedMedia(
                url = "https://media.example/media/visible.mp4",
                pageUrl = pageUrl,
                mimeType = "video/mp4",
                source = DetectionSource.DOM,
            ),
            oldGeneration,
        )

        viewModel.onNavigationInvalidated()
        val invalidatedState = viewModel.uiState.value

        assertTrue(invalidatedState.navigationGeneration > oldGeneration)
        assertTrue(invalidatedState.detectedMedia.isEmpty())
        viewModel.onMediaDetected(
            DetectedMedia(
                url = "https://media.example/media/late.mp4",
                pageUrl = pageUrl,
                mimeType = "video/mp4",
                source = DetectionSource.DOM,
            ),
            oldGeneration,
        )
        assertTrue(viewModel.uiState.value.detectedMedia.isEmpty())
    }

    /** Ensures page-finished, progress, and media events from a prior generation cannot revive old UI state. */
    @Test
    fun `stale WebView callbacks do not overwrite the active document`() {
        val firstPage = "https://first.example/watch"
        val secondPage = "https://second.example/watch"
        val firstGeneration = viewModel.onPageStarted(firstPage)
        val secondGeneration = viewModel.onPageStarted(secondPage)

        viewModel.onPageFinished(firstPage, "First title", firstGeneration)
        viewModel.onProgressChanged(90, firstGeneration)
        viewModel.onMediaDetected(
            DetectedMedia(
                url = "https://first.example/media/late.mp4",
                pageUrl = firstPage,
                mimeType = "video/mp4",
                source = DetectionSource.DOM,
            ),
            firstGeneration,
        )

        val state = viewModel.uiState.value
        assertEquals(secondGeneration, state.navigationGeneration)
        assertEquals(secondPage, state.url)
        assertTrue(state.isLoading)
        assertEquals(0, state.progress)
        assertTrue(state.detectedMedia.isEmpty())
    }

    /** The v0.3 flood: both detection layers reported every HLS piece as its own downloadable item. */
    @Test
    fun `stream pieces are counted once across layers and never listed`() {
        val pageUrl = "https://media.example/watch"
        val generation = viewModel.onPageStarted(pageUrl)
        fun piece(index: Int, source: DetectionSource) = DetectedMedia(
            url = "https://cdn.example/hls/url_$index/193039199_mp4_h264_aac_hd_7.ts",
            pageUrl = pageUrl,
            mimeType = "video/mp2t",
            source = source,
        )

        viewModel.onMediaDetected(piece(0, DetectionSource.NETWORK), generation)
        viewModel.onMediaDetected(piece(0, DetectionSource.XHR_FETCH), generation)
        viewModel.onMediaDetected(piece(1, DetectionSource.NETWORK), generation)

        val state = viewModel.uiState.value
        assertTrue(state.detectedMedia.isEmpty())
        assertEquals(2, state.hiddenSegmentCount)
    }

    /** Byte-range fetches of one file become a single item pointing at the whole file. */
    @Test
    fun `byte-range requests merge into one whole-file item`() {
        val pageUrl = "https://media.example/watch"
        val generation = viewModel.onPageStarted(pageUrl)
        listOf("bytestart=0&byteend=999", "bytestart=1000&byteend=1999").forEach { range ->
            viewModel.onMediaDetected(
                DetectedMedia(
                    url = "https://video.fbcdn.example/clip.mp4?_nc_cat=1&$range",
                    pageUrl = pageUrl,
                    mimeType = "video/mp4",
                    source = DetectionSource.NETWORK,
                ),
                generation,
            )
        }

        val media = viewModel.uiState.value.detectedMedia
        assertEquals(listOf("https://video.fbcdn.example/clip.mp4?_nc_cat=1"), media.map { it.url })
    }

    /** Page hints follow the same generation rules as media: stale reports never land, navigation clears them. */
    @Test
    fun `page meta is kept for the active document only and cleared on navigation`() {
        val generation = viewModel.onPageStarted("https://media.example/watch")
        val meta = PageMeta(
            title = "Watch", ogTitle = null, siteName = null, ogImage = null, videoUrls = emptyList(),
            ldName = null, ldThumbnail = null, ldDurationSeconds = null, players = emptyList(),
        )

        viewModel.onPageMeta(meta, generation - 1)
        assertEquals(null, viewModel.uiState.value.pageMeta)

        viewModel.onPageMeta(meta, generation)
        assertEquals(meta, viewModel.uiState.value.pageMeta)

        viewModel.onPageStarted("https://media.example/next")
        assertEquals(null, viewModel.uiState.value.pageMeta)
        assertEquals(0, viewModel.uiState.value.hiddenSegmentCount)
    }
}
