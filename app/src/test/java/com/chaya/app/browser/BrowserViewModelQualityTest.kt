package com.chaya.app.browser

import android.os.Looper
import androidx.media3.common.C
import com.chaya.app.model.DetectedMedia
import com.chaya.app.model.DetectionSource
import com.chaya.app.streaming.StreamTrack
import com.chaya.app.ui.components.QualityPickerState
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Asking for a stream's qualities shows them, or says there are none; it never starts a download by itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserViewModelQualityTest {

    private val stream = DetectedMedia(
        url = "https://player.example/stream/abc",
        pageUrl = "https://site.example/watch",
        mimeType = "application/vnd.apple.mpegurl",
        source = DetectionSource.XHR_FETCH,
    )

    @Test
    fun `a stream with no qualities to read says so instead of downloading`() {
        val viewModel = BrowserViewModel(RuntimeEnvironment.getApplication())
        viewModel.readQualities = { Result.success(emptyList()) }

        viewModel.analyzeStream(stream)
        shadowOf(Looper.getMainLooper()).idle()

        val state = viewModel.uiState.value.qualityPickerState
        assertTrue("got $state", state is QualityPickerState.Error)
        assertEquals("Download anyway still knows the stream", stream.url, (state as QualityPickerState.Error).url)
        // Downloads restored from the app's database may appear meanwhile; none of them is this stream.
        assertTrue("nothing was downloaded", viewModel.downloads.value.none { it.url == stream.url })
    }

    @Test
    fun `without the page player's length, the stream's own length lets the picker estimate sizes`() {
        val viewModel = BrowserViewModel(RuntimeEnvironment.getApplication())
        val track = StreamTrack(rendererType = C.TRACK_TYPE_VIDEO, label = "720p", streamKeys = emptyList(),
            height = 720, bitrate = 600_000, streamDurationSeconds = 2_700.0)
        viewModel.readQualities = { Result.success(listOf(track)) }

        viewModel.analyzeStream(stream, durationSeconds = null)
        shadowOf(Looper.getMainLooper()).idle()

        val state = viewModel.uiState.value.qualityPickerState
        assertEquals(2_700.0, (state as QualityPickerState.Ready).durationSeconds!!, 0.001)
    }

    @Test
    fun `a picker dismissed while it reads the qualities stays closed when they arrive`() {
        val viewModel = BrowserViewModel(RuntimeEnvironment.getApplication())
        val answer = CompletableDeferred<Result<List<StreamTrack>>>()
        viewModel.readQualities = { answer.await() }

        viewModel.analyzeStream(stream)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(QualityPickerState.Loading, viewModel.uiState.value.qualityPickerState)
        viewModel.dismissQualityPicker()

        answer.complete(Result.success(listOf(track(720))))
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(viewModel.uiState.value.sheet)
    }

    @Test
    fun `an older question's answer does not fill the picker of a newer one`() {
        val viewModel = BrowserViewModel(RuntimeEnvironment.getApplication())
        val first = CompletableDeferred<Result<List<StreamTrack>>>()
        val second = CompletableDeferred<Result<List<StreamTrack>>>()
        val answers = ArrayDeque(listOf(first, second))
        viewModel.readQualities = { answers.removeFirst().await() }

        viewModel.analyzeStream(stream)
        shadowOf(Looper.getMainLooper()).idle()
        viewModel.analyzeStream(stream.copy(url = "https://player.example/stream/other"))
        shadowOf(Looper.getMainLooper()).idle()

        first.complete(Result.success(listOf(track(360))))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(QualityPickerState.Loading, viewModel.uiState.value.qualityPickerState)

        second.complete(Result.success(listOf(track(1080))))
        shadowOf(Looper.getMainLooper()).idle()
        val ready = viewModel.uiState.value.qualityPickerState as QualityPickerState.Ready
        assertEquals("https://player.example/stream/other", ready.url)
    }

    private fun track(height: Int) = StreamTrack(
        rendererType = C.TRACK_TYPE_VIDEO, label = "${height}p", streamKeys = emptyList(), height = height,
    )
}
