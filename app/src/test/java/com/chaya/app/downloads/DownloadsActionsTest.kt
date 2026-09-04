package com.chaya.app.downloads

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.chaya.app.download.DownloadError
import com.chaya.app.download.DownloadState
import com.chaya.app.download.DownloadTask
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the state→action mapping in ActionsRow with real Compose semantics
 * (Phase 1.3): every DownloadState renders its documented actions, tapping
 * fires the matching callback, and FAILED offers Retry only when the
 * classified error is retryable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadsActionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Minimal task in [state]; FAILED variants carry the given error. */
    private fun task(state: DownloadState, error: DownloadError? = null) = DownloadTask(
        id = 1,
        url = "https://cdn.example.com/a.mp4",
        pageUrl = null,
        fileName = "a.mp4",
        mimeType = "video/mp4",
        filePath = "/tmp/a.mp4",
        state = state,
        error = error,
    )

    /** Renders ActionsRow recording which callback fired. */
    private fun render(state: DownloadState, error: DownloadError? = null): MutableList<String> {
        val fired = mutableListOf<String>()
        composeRule.setContent {
            ActionsRow(
                task = task(state, error),
                onPause = { fired += "pause" },
                onResume = { fired += "resume" },
                onCancel = { fired += "cancel" },
                onDelete = { fired += "delete" },
                onOpen = { fired += "open" },
                onPlay = { fired += "play" },
            )
        }
        return fired
    }

    @Test
    fun `downloading shows pause and cancel`() {
        val fired = render(DownloadState.DOWNLOADING)

        composeRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cancel").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause").performClick()
        composeRule.onNodeWithContentDescription("Cancel").performClick()
        assertEquals(listOf("pause", "cancel"), fired)
    }

    @Test
    fun `paused shows resume and delete`() {
        val fired = render(DownloadState.PAUSED)

        composeRule.onNodeWithContentDescription("Resume").performClick()
        composeRule.onNodeWithContentDescription("Delete").performClick()
        assertEquals(listOf("resume", "delete"), fired)
    }

    @Test
    fun `retryable failure shows retry`() {
        val retryable = render(DownloadState.FAILED, DownloadError.HttpStatus(503))
        composeRule.onNodeWithContentDescription("Retry").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Retry").performClick()
        assertEquals(listOf("resume"), retryable)
    }

    @Test
    fun `fatal failure hides retry but keeps delete`() {
        render(DownloadState.FAILED, DownloadError.HttpStatus(404))
        composeRule.onNodeWithContentDescription("Delete").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("Retry").fetchSemanticsNodes().size)
    }

    @Test
    fun `completed file shows open`() {
        val fileFired = render(DownloadState.COMPLETED)
        composeRule.onNodeWithContentDescription("Open").performClick()
        assertEquals(listOf("open"), fileFired)
    }

    @Test
    fun `completed stream shows play`() {
        val streamFired = mutableListOf<String>()
        composeRule.setContent {
            ActionsRow(
                task = task(DownloadState.COMPLETED).copy(filePath = null, exportedUri = null),
                onPause = {}, onResume = {}, onCancel = {},
                onDelete = { streamFired += "delete" },
                onOpen = {}, onPlay = { streamFired += "play" },
            )
        }
        composeRule.onNodeWithContentDescription("Play").performClick()
        assertEquals(listOf("play"), streamFired)
    }
}
