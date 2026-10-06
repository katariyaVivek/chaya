package com.chaya.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chaya.app.detection.MediaSheetModel
import com.chaya.app.detection.RankedMedia
import com.chaya.app.model.DetectedMedia
import com.chaya.app.model.DetectionSource
import com.chaya.app.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises DetectedMediaSheet with real Compose semantics: the main item's
 * Download action, secondary rows, ads folded until asked for, the stream
 * pieces footnote, and the teaching empty state. Robolectric hosts the
 * composition so these run in testDebugUnitTest with no emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DetectedMediaSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun ranked(url: String, title: String, kind: MediaKind, isAd: Boolean = false) = RankedMedia(
        media = DetectedMedia(
            url = url,
            pageUrl = "https://site.example/watch",
            mimeType = if (kind == MediaKind.AUDIO) "audio/mpeg" else "video/mp4",
            source = DetectionSource.NETWORK,
        ),
        kind = kind,
        title = title,
        subtitle = if (isAd) "Ad · 0:15 · MP4" else "1:42 · MP4",
        thumbnailUrl = null,
        durationSeconds = 102.0,
        videoHeight = null,
        isLikelyAd = isAd,
        score = 0,
    )

    private val main = ranked("https://cdn.example/main.m3u8", "Big Buck Bunny", MediaKind.STREAM)
    private val song = ranked("https://cdn.example/song.mp3", "Theme song", MediaKind.AUDIO)
    private val ad = ranked("https://s0.2mdn.net/ad.mp4", "Ad 1", MediaKind.VIDEO, isAd = true)

    @Test
    fun `main item download and secondary rows invoke onDownload with their item`() {
        val downloaded = mutableListOf<RankedMedia>()
        composeRule.setContent {
            DetectedMediaSheet(
                model = MediaSheetModel(main, listOf(song), emptyList(), 0),
                onDismiss = {},
                onDownload = { downloaded += it },
            )
        }

        composeRule.onNodeWithText("Big Buck Bunny").assertIsDisplayed()
        composeRule.onNodeWithText("Download").performClick()
        scrollTo("Theme song")
        composeRule.onNodeWithText("Theme song").performClick()

        assertEquals(listOf(main, song), downloaded)
    }

    /** The main card fills Robolectric's small default window; later rows compose only once scrolled to. */
    private fun scrollTo(text: String) {
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = true))
    }

    @Test
    fun `likely ads stay folded until the toggle is tapped`() {
        composeRule.setContent {
            DetectedMediaSheet(
                model = MediaSheetModel(main, emptyList(), listOf(ad), 28),
                onDismiss = {},
                onDownload = {},
            )
        }

        scrollTo("28 stream pieces")
        composeRule.onNodeWithText("28 stream pieces hidden. They're parts of a stream, not separate videos.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Likely ads · 1").assertIsDisplayed()
        composeRule.onNodeWithText("Ad 1").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Show likely ads").performClick()

        scrollTo("Ad 1")
        composeRule.onNodeWithText("Ad 1").assertIsDisplayed()
    }

    @Test
    fun `a page with only ads explains itself instead of showing a main item`() {
        composeRule.setContent {
            DetectedMediaSheet(
                model = MediaSheetModel(null, emptyList(), listOf(ad), 0),
                onDismiss = {},
                onDownload = {},
            )
        }

        composeRule.onNodeWithText("Only ads so far. Play the video you want, then check again.").assertIsDisplayed()
    }

    @Test
    fun `empty model shows the teaching line`() {
        var dismissed = false
        composeRule.setContent {
            DetectedMediaSheet(model = MediaSheetModel.EMPTY, onDismiss = { dismissed = true }, onDownload = {})
        }

        composeRule.onNodeWithText("Nothing found yet. Play or scroll the page.").assertIsDisplayed()
        assertEquals(false, dismissed)
    }
}
