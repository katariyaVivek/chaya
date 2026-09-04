package com.chaya.app.browser

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
}
