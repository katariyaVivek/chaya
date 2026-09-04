package com.chaya.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chaya.app.streaming.StreamTrack
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises QualitySelectorSheet with real Compose semantics (Phase 1.3):
 * checkbox toggles move the Download button's enabled state and label, and
 * the download callback receives exactly the chosen tracks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QualitySelectorSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** One video + one audio track, both pre-selected like the picker offers. */
    private fun tracks() = listOf(
        StreamTrack(rendererType = 2, label = "1080p", streamKeys = emptyList(), selected = true),
        StreamTrack(rendererType = 1, label = "English", streamKeys = emptyList(), selected = true),
    )

    /** Ready picker for a manifest URL; url/mime route the download after picking. */
    private fun readyState() = QualityPickerState.Ready(
        tracks = tracks(),
        url = "https://cdn.example.com/stream.m3u8",
        mimeType = "application/vnd.apple.mpegurl",
    )

    @Test
    fun `unchecking all tracks disables download, rechecking enables it`() {
        composeRule.setContent {
            QualitySelectorSheet(
                state = readyState(),
                onDismiss = {},
                onDownload = {},
            )
        }

        composeRule.onNodeWithText("Download 2 tracks").assertIsDisplayed()
        composeRule.onNodeWithText("1080p").performClick()
        composeRule.onNodeWithText("English").performClick()
        composeRule.onNodeWithText("Download 0 tracks").assertIsDisplayed()
        composeRule.onNodeWithText("1080p").performClick()
        composeRule.onNodeWithText("Download 1 track").assertIsDisplayed()
    }

    @Test
    fun `download callback receives exactly the chosen tracks`() {
        val chosen = mutableListOf<List<StreamTrack>>()
        val all = tracks()
        composeRule.setContent {
            QualitySelectorSheet(
                state = QualityPickerState.Ready(
                    tracks = all,
                    url = "https://cdn.example.com/stream.m3u8",
                    mimeType = "application/vnd.apple.mpegurl",
                ),
                onDismiss = {},
                onDownload = { chosen += it },
            )
        }

        // Deselect audio, download video only.
        composeRule.onNodeWithText("English").performClick()
        composeRule.onNodeWithText("Download 1 track").performClick()

        assertEquals(1, chosen.size)
        assertEquals(listOf("1080p"), chosen.single().map { it.label })
    }

    @Test
    fun `error state offers download-anyway without crashing`() {
        val chosen = mutableListOf<List<StreamTrack>>()
        composeRule.setContent {
            QualitySelectorSheet(
                state = QualityPickerState.Error("Could not read tracks"),
                onDismiss = {},
                onDownload = { chosen += it },
            )
        }

        composeRule.onNodeWithText("Could not read tracks").assertIsDisplayed()
        composeRule.onNodeWithText("Download anyway").performClick()
        assertEquals(listOf(emptyList<StreamTrack>()), chosen)
    }

    @Test
    fun `loading state shows progress text`() {
        composeRule.setContent {
            QualitySelectorSheet(
                state = QualityPickerState.Loading,
                onDismiss = {},
                onDownload = {},
            )
        }

        composeRule.onNodeWithText("Analyzing stream…").assertIsDisplayed()
    }
}
