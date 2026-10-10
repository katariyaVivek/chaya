package com.chaya.app.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chaya.app.detection.MediaSheetModel
import com.chaya.app.detection.RankedMedia
import com.chaya.app.download.DownloadState
import com.chaya.app.download.DownloadTask
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
 * Exercises the browser chrome with real Compose semantics: the floating
 * download pill, the address bar's field and site-name modes, and the start
 * screen. Robolectric hosts the composition so these run with no emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserChromeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun ranked(title: String, kind: MediaKind = MediaKind.STREAM) = RankedMedia(
        media = DetectedMedia(
            url = "https://cdn.example/main.m3u8",
            pageUrl = "https://site.example/watch",
            mimeType = "application/vnd.apple.mpegurl",
            source = DetectionSource.NETWORK,
        ),
        kind = kind,
        title = title,
        subtitle = "10:35 · Stream",
        thumbnailUrl = null,
        durationSeconds = 635.0,
        videoHeight = null,
        isLikelyAd = false,
        score = 0,
    )

    // ---- floating pill ---- //

    @Test
    fun `pill names the main video and opens the sheet when tapped`() {
        var opened = 0
        composeRule.setContent {
            MediaPill(
                model = MediaSheetModel(ranked("Big Buck Bunny"), emptyList(), emptyList(), 0),
                onClick = { opened++ },
            )
        }

        composeRule.onNodeWithText("Big Buck Bunny").assertIsDisplayed()
        composeRule.onNodeWithText("10:35 · Stream").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Detected media, Big Buck Bunny").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `pill counts the items when there is no main video`() {
        composeRule.setContent {
            MediaPill(
                model = MediaSheetModel(null, listOf(ranked("A"), ranked("B")), emptyList(), 0),
                onClick = {},
            )
        }

        composeRule.onNodeWithText("2 items found").assertIsDisplayed()
        composeRule.onNodeWithText("Tap to review").assertIsDisplayed()
    }

    // ---- address bar ---- //

    @Test
    fun `start screen address bar offers paste while empty`() {
        var pasted = 0
        composeRule.setContent {
            AddressBar(
                url = "",
                homeVisible = true,
                input = "",
                onInputChange = {},
                onGo = {},
                onPaste = { pasted++ },
                onReload = {},
            )
        }

        composeRule.onNodeWithText("Search or paste a link").assertIsDisplayed()
        composeRule.onNodeWithText("Paste").performClick()

        assertEquals(1, pasted)
    }

    @Test
    fun `on a page the bar shows the site name and tapping it reveals the full address`() {
        val address = "https://www.example.com/watch?v=1"
        composeRule.setContent {
            var input by remember { mutableStateOf(address) }
            AddressBar(
                url = address,
                homeVisible = false,
                input = input,
                onInputChange = { input = it },
                onGo = {},
                onPaste = {},
                onReload = {},
            )
        }

        composeRule.onNodeWithText("example.com").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Secure connection").assertIsDisplayed()

        composeRule.onNodeWithText("example.com").performClick()

        composeRule.onNodeWithText(address).assertIsDisplayed()
    }

    @Test
    fun `reload button on the site pill reloads without opening the editor`() {
        var reloaded = 0
        composeRule.setContent {
            AddressBar(
                url = "http://plain.example/",
                homeVisible = false,
                input = "http://plain.example/",
                onInputChange = {},
                onGo = {},
                onPaste = {},
                onReload = { reloaded++ },
            )
        }

        composeRule.onNodeWithContentDescription("Not secure").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Refresh").performClick()

        assertEquals(1, reloaded)
        composeRule.onNodeWithText("plain.example").assertIsDisplayed()
    }

    @Test
    fun `the shield shows how much was blocked on the page and opens the ad blocker`() {
        var opened = 0
        var badge by mutableStateOf(AdBlockBadge(active = true, blocked = 12))
        composeRule.setContent {
            AddressBar(
                url = "https://news.example/",
                homeVisible = false,
                input = "",
                onInputChange = {},
                onGo = {},
                onPaste = {},
                onReload = {},
                adBlock = badge,
                onAdBlock = { opened++ },
            )
        }

        composeRule.onNodeWithText("12").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Ad blocker: 12 blocked").performClick()
        assertEquals(1, opened)
        composeRule.onNodeWithText("news.example").assertIsDisplayed()

        badge = AdBlockBadge(active = false, blocked = 12)
        composeRule.onNodeWithContentDescription("Ad blocker off here").assertIsDisplayed()
        composeRule.onNodeWithText("12").assertDoesNotExist()
    }

    @Test
    fun `without anything to say about the page there is no shield`() {
        composeRule.setContent {
            AddressBar(
                url = "https://news.example/",
                homeVisible = false,
                input = "",
                onInputChange = {},
                onGo = {},
                onPaste = {},
                onReload = {},
            )
        }
        composeRule.onNodeWithContentDescription("Ad blocker off here").assertDoesNotExist()
    }

    // ---- start screen ---- //

    @Test
    fun `start screen lists quick sites and tapping one opens it`() {
        val opened = mutableListOf<String>()
        composeRule.setContent {
            HomeContent(
                visible = true,
                showOnboardingHint = false,
                onDismissOnboardingHint = {},
                recent = emptyList(),
                onSelectUrl = { opened += it },
                onOpenDownloads = {},
            )
        }

        composeRule.onNodeWithText("Videos you save show up here.").assertIsDisplayed()
        composeRule.onNodeWithText("YouTube").performClick()

        assertEquals(listOf("https://m.youtube.com"), opened)
    }

    @Test
    fun `start screen shows recent downloads and opens the downloads list`() {
        var openedDownloads = 0
        val task = DownloadTask(
            id = 1,
            url = "https://cdn.example/a.mp4",
            pageUrl = "https://www.example.com/watch",
            fileName = "Big Buck Bunny (720p).mp4",
            mimeType = "video/mp4",
            state = DownloadState.COMPLETED,
            totalBytes = 50_331_648L,
            title = "Big Buck Bunny",
            qualityHeight = 720,
        )
        composeRule.setContent {
            HomeContent(
                visible = true,
                showOnboardingHint = false,
                onDismissOnboardingHint = {},
                recent = listOf(task),
                onSelectUrl = {},
                onOpenDownloads = { openedDownloads++ },
            )
        }

        composeRule.onNodeWithText("48.0 MB · 720p · example.com").assertIsDisplayed()
        composeRule.onNodeWithText("Big Buck Bunny").performClick()

        assertEquals(1, openedDownloads)
    }
}
