package com.chaya.app.browser

import android.os.Looper
import androidx.media3.common.C
import com.chaya.app.model.DetectedMedia
import com.chaya.app.model.DetectionSource
import com.chaya.app.streaming.StreamTrack
import com.chaya.app.ui.components.QualityPickerState
import org.junit.Assert.assertEquals
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
}
