package com.chaya.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.chaya.app.streaming.StreamTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises QualitySelectorSheet with real Compose semantics: video
 * renditions are one choice with the best preselected, audio tracks are
 * independent toggles, and the callback receives exactly that selection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QualitySelectorSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Two renditions of one stream plus its audio, ordered best-first as ManifestHelper delivers them. */
    private fun tracks() = listOf(
        StreamTrack(
            rendererType = 2, label = "1080p", streamKeys = emptyList(),
            height = 1080, bitrate = 6_000_000, detail = "1920×1080 · 6.0 Mbps",
        ),
        StreamTrack(
            rendererType = 2, label = "720p", streamKeys = emptyList(),
            height = 720, bitrate = 2_000_000, detail = "1280×720 · 2.0 Mbps",
        ),
        StreamTrack(rendererType = 1, label = "English", streamKeys = emptyList()),
    )

    private fun readyState(durationSeconds: Double? = null) = QualityPickerState.Ready(
        tracks = tracks(),
        url = "https://cdn.example.com/stream.m3u8",
        mimeType = "application/vnd.apple.mpegurl",
        durationSeconds = durationSeconds,
    )

    @Test
    fun `best video is preselected and picking another replaces it`() {
        composeRule.setContent {
            QualitySelectorSheet(state = readyState(), onDismiss = {}, onDownload = {})
        }

        composeRule.onNodeWithText("Download 1080p").assertIsDisplayed()
        composeRule.onNodeWithText("720p").performClick()
        composeRule.onNodeWithText("Download 720p").assertIsDisplayed()
    }

    @Test
    fun `download callback receives one rendition plus the selected audio`() {
        val chosen = mutableListOf<List<StreamTrack>>()
        composeRule.setContent {
            QualitySelectorSheet(state = readyState(), onDismiss = {}, onDownload = { chosen += it })
        }

        composeRule.onNodeWithText("720p").performClick()
        composeRule.onNodeWithText("Download 720p").performClick()

        assertEquals(listOf(listOf("720p", "English")), chosen.map { picked -> picked.map { it.label } })
    }

    @Test
    fun `clearing video leaves audio only and clearing everything disables download`() {
        composeRule.setContent {
            QualitySelectorSheet(state = readyState(), onDismiss = {}, onDownload = {})
        }

        composeRule.onNodeWithText("1080p").performClick()
        composeRule.onNodeWithText("Download audio only").assertIsDisplayed()
        // Robolectric's small window clips the list above the pinned button; scroll as a user would.
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("English"))
        composeRule.onNodeWithText("English").performClick()
        composeRule.onNodeWithText("Choose a quality").assertIsNotEnabled()
    }

    @Test
    fun `each rendition shows its size when the duration is known`() {
        composeRule.setContent {
            QualitySelectorSheet(state = readyState(durationSeconds = 600.0), onDismiss = {}, onDownload = {})
        }

        composeRule.onNodeWithText("1920×1080 · 6.0 Mbps · ≈ 450 MB").assertIsDisplayed()
    }

    @Test
    fun `size estimates scale from megabytes to gigabytes and need both inputs`() {
        assertEquals("≈ 450 MB", estimateSize(6_000_000, 600.0))
        assertEquals("≈ 5.4 GB", estimateSize(6_000_000, 7_200.0))
        assertNull(estimateSize(0, 600.0))
        assertNull(estimateSize(6_000_000, null))
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
