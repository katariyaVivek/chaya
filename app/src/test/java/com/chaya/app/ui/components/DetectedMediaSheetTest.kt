package com.chaya.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chaya.app.model.DetectedMedia
import com.chaya.app.model.DetectionSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises DetectedMediaSheet with real Compose semantics (Phase 1.3):
 * tapping a row invokes onDownload with that item, and the empty state
 * teaches instead of blanking. Robolectric hosts the composition so these
 * run in testDebugUnitTest with no emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DetectedMediaSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Two rows from different layers; filenames derive from the URL path. */
    private fun media(url: String, mime: String?, source: DetectionSource) = DetectedMedia(
        url = url,
        pageUrl = "https://site.example/watch",
        mimeType = mime,
        source = source,
    )

    @Test
    fun `tapping a row invokes onDownload with that item`() {
        val first = media("https://cdn.example.com/clip.mp4", "video/mp4", DetectionSource.NETWORK)
        val second = media("https://cdn.example.com/song.mp3", "audio/mpeg", DetectionSource.DOM)
        val downloaded = mutableListOf<DetectedMedia>()
        composeRule.setContent {
            DetectedMediaSheet(
                mediaList = listOf(first, second),
                onDismiss = {},
                onDownload = { downloaded += it },
            )
        }

        composeRule.onNodeWithText("Detected media").assertIsDisplayed()
        composeRule.onNodeWithText("song.mp3").performClick()

        assertEquals(listOf(second), downloaded)
    }

    @Test
    fun `empty list shows the teaching line and no rows`() {
        var dismissed = false
        composeRule.setContent {
            DetectedMediaSheet(mediaList = emptyList(), onDismiss = { dismissed = true }, onDownload = {})
        }

        composeRule.onNodeWithText("Nothing found yet. Play or scroll the page.").assertIsDisplayed()
        assertEquals(false, dismissed)
    }
}
