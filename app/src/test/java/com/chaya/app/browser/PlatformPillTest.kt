package com.chaya.app.browser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chaya.app.platform.LinkState
import com.chaya.app.platform.PlatformChoice
import com.chaya.app.platform.PlatformException
import com.chaya.app.platform.PlatformFormatFixtures
import com.chaya.app.platform.PlatformMatcher
import com.chaya.app.platform.PlatformMedia
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pill on a page that is one video on a supported site: a note while the video is found, then its title
 * and best quality; nothing otherwise, so a page that merely looks like a video page never nags.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlatformPillTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val match = PlatformMatcher.match("https://www.youtube.com/watch?v=dQw4w9WgXcQ")!!

    private val found = LinkState.Found(
        match = match,
        media = PlatformMedia(
            id = "dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            author = "Rick Astley",
            durationSeconds = 213.0,
            thumbnailUrl = null,
            pageUrl = match.url,
            extractor = "Youtube",
            isLive = false,
            formats = emptyList(),
        ),
        choices = listOf(
            PlatformChoice(
                label = "1080p",
                detail = "MP4 · ≈ 48.0 MB",
                quality = 1080,
                file = PlatformFormatFixtures.format(id = "137", ext = "mp4", width = 1920, height = 1080),
                audioToMerge = null,
                isAudioOnly = false,
            ),
        ),
    )

    @Test
    fun `while the video is found the pill says so and names the site`() {
        composeRule.setContent { PlatformPill(state = LinkState.Looking(match), onClick = {}) }

        composeRule.onNodeWithText("Finding the video…").assertIsDisplayed()
        composeRule.onNodeWithText("YouTube").assertIsDisplayed()
    }

    @Test
    fun `a found video shows its title and the best quality`() {
        composeRule.setContent { PlatformPill(state = found, onClick = {}) }

        composeRule.onNodeWithText("Never Gonna Give You Up").assertIsDisplayed()
        composeRule.onNodeWithText("1080p · YouTube").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Video from YouTube, Never Gonna Give You Up").assertIsDisplayed()
    }

    @Test
    fun `tapping the pill asks for its sheet`() {
        var taps = 0
        composeRule.setContent { PlatformPill(state = found, onClick = { taps++ }) }

        composeRule.onNodeWithContentDescription("Video from YouTube, Never Gonna Give You Up").performClick()

        assertEquals(1, taps)
    }

    @Test
    fun `a failed lookup and no link show no pill`() {
        composeRule.setContent {
            PlatformPill(
                state = LinkState.Failed(match, PlatformException(PlatformException.Kind.PRIVATE), signInMayHelp = false),
                onClick = {},
            )
        }
        composeRule.onNodeWithText("Finding the video…").assertDoesNotExist()
        composeRule.onNodeWithText("YouTube").assertDoesNotExist()
    }

    @Test
    fun `with no link the pill is empty`() {
        composeRule.setContent { PlatformPill(state = LinkState.Idle, onClick = {}) }

        composeRule.onNodeWithText("YouTube").assertDoesNotExist()
    }
}
