package com.chaya.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.chaya.app.platform.LinkState
import com.chaya.app.platform.PlatformChoice
import com.chaya.app.platform.PlatformException
import com.chaya.app.platform.PlatformFormatFixtures
import com.chaya.app.platform.PlatformMatcher
import com.chaya.app.platform.PlatformMedia
import com.chaya.app.platform.PostItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sheet for a link on a supported site, with real Compose semantics: a note while the video is found,
 * the title with the best quality as the one main button and the rest as rows, and a failure with the
 * actions that could help. Robolectric hosts the composition, so no emulator is needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlatformSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val match = PlatformMatcher.match("https://www.youtube.com/watch?v=dQw4w9WgXcQ")!!

    private fun choice(label: String, detail: String, quality: Int?, audioOnly: Boolean = false) = PlatformChoice(
        label = label,
        detail = detail,
        quality = quality,
        file = PlatformFormatFixtures.format(id = label, ext = "mp4", width = 1, height = quality),
        audioToMerge = null,
        isAudioOnly = audioOnly,
    )

    private val best = choice("1080p", "MP4 · ≈ 48.0 MB", 1080)
    private val medium = choice("720p", "MP4 · ≈ 22.0 MB", 720)
    private val sound = choice("Audio only", "M4A · ≈ 3.0 MB", null, audioOnly = true)

    private fun found(vararg choices: PlatformChoice, author: String? = "Rick Astley") = LinkState.Found(
        match = match,
        media = PlatformMedia(
            id = "dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            author = author,
            durationSeconds = 213.0,
            thumbnailUrl = null,
            pageUrl = match.url,
            extractor = "Youtube",
            isLive = false,
            formats = emptyList(),
        ),
        choices = choices.toList(),
    )

    private fun show(
        state: LinkState,
        onChoose: (PlatformChoice) -> Unit = {},
        onRetry: () -> Unit = {},
        onRetryWithSignIn: () -> Unit = {},
        onSavePost: (List<Int>) -> Unit = {},
    ) {
        composeRule.setContent {
            PlatformSheet(
                state = state,
                onDismiss = {},
                onChoose = onChoose,
                onRetry = onRetry,
                onRetryWithSignIn = onRetryWithSignIn,
                onSavePost = onSavePost,
            )
        }
    }

    private fun scrollTo(text: String) {
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    }

    // ---- looking ---- //

    @Test
    fun `while the video is found the sheet says so and names the site`() {
        show(LinkState.Looking(match))

        composeRule.onNodeWithText("Finding the video…").assertIsDisplayed()
        composeRule.onNodeWithText("From YouTube. This takes a few seconds.").assertIsDisplayed()
    }

    @Test
    fun `with no link there is nothing to show`() {
        show(LinkState.Idle)

        composeRule.onNodeWithText("Finding the video…").assertDoesNotExist()
    }

    // ---- found ---- //

    @Test
    fun `a found video shows its title, its facts and the best quality as the main button`() {
        show(found(best, medium, sound))

        composeRule.onNodeWithText("Never Gonna Give You Up").assertIsDisplayed()
        composeRule.onNodeWithText("Rick Astley · 3:33 · YouTube").assertIsDisplayed()
        composeRule.onNodeWithText("Download 1080p").assertIsDisplayed()
        composeRule.onNodeWithText("MP4 · ≈ 48.0 MB").assertIsDisplayed()
    }

    @Test
    fun `the main button picks the best quality and a row picks its own`() {
        val chosen = mutableListOf<PlatformChoice>()
        show(found(best, medium, sound), onChoose = { chosen += it })

        composeRule.onNodeWithText("Download 1080p").performClick()
        scrollTo("720p")
        composeRule.onNodeWithText("720p").performClick()
        scrollTo("Audio only")
        composeRule.onNodeWithText("Audio only").performClick()

        assertEquals(listOf(best, medium, sound), chosen)
    }

    @Test
    fun `the other qualities are listed under their own heading`() {
        show(found(best, medium, sound))

        scrollTo("Other qualities")
        composeRule.onNodeWithText("Other qualities").assertIsDisplayed()
        composeRule.onNodeWithText("MP4 · ≈ 22.0 MB").assertIsDisplayed()
        composeRule.onNodeWithText("M4A · ≈ 3.0 MB").assertIsDisplayed()
    }

    @Test
    fun `a single choice has no list of others`() {
        show(found(best))

        composeRule.onNodeWithText("Other qualities").assertDoesNotExist()
    }

    @Test
    fun `sound alone offers a download audio button`() {
        show(found(sound))

        composeRule.onNodeWithText("Download audio").assertIsDisplayed()
    }

    @Test
    fun `a bare file with no quality to name just says Download`() {
        show(found(choice("Video", "MP4", quality = null)))

        composeRule.onNodeWithText("Download").assertIsDisplayed()
    }

    @Test
    fun `a video with no author or length shows only what is known`() {
        val state = found(best, author = null).let { it.copy(media = it.media.copy(durationSeconds = null)) }
        show(state)

        composeRule.onNodeWithText("YouTube").assertIsDisplayed()
    }

    // ---- failed ---- //

    @Test
    fun `a failure says why and offers to try again`() {
        var retried = 0
        show(
            LinkState.Failed(match, PlatformException(PlatformException.Kind.PRIVATE), signInMayHelp = false),
            onRetry = { retried++ },
        )

        composeRule.onNodeWithText("Couldn't get this video").assertIsDisplayed()
        composeRule.onNodeWithText("This video is private.").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
        composeRule.onNodeWithText("Use my sign-in").assertDoesNotExist()
    }

    @Test
    fun `a sign-in wall offers the account, with a word about the risk, and only on request`() {
        var withAccount = 0
        show(
            LinkState.Failed(match, PlatformException(PlatformException.Kind.BOT_CHECK), signInMayHelp = true),
            onRetryWithSignIn = { withAccount++ },
        )

        composeRule.onNodeWithText("Use my sign-in").assertIsDisplayed()
        composeRule.onNodeWithText("This uses the account you're signed in to here", substring = true).assertIsDisplayed()
        assertEquals("nothing happens until it is tapped", 0, withAccount)
        composeRule.onNodeWithText("Use my sign-in").performClick()
        assertEquals(1, withAccount)
    }

    @Test
    fun `every failure kind has a message that fits on the sheet`() {
        PlatformException.Kind.entries.forEach { kind ->
            assertTrue("$kind", PlatformException(kind).message.orEmpty().isNotBlank())
        }
    }

    // ---- a post with pictures ---- //

    private val postMatch = PlatformMatcher.match("https://www.instagram.com/p/Cabc123/")!!

    private fun post(vararg items: PostItem) = LinkState.FoundPost(
        match = postMatch,
        media = PlatformMedia(
            id = "Cabc123", title = "Sunset at the lake", author = "someone", durationSeconds = null,
            thumbnailUrl = null, pageUrl = postMatch.url, extractor = "Instagram", isLive = false,
            formats = emptyList(), items = items.toList(),
        ),
    )

    private fun picture(n: Int) = PostItem("https://cdn.example/$n.jpg", isVideo = false, ext = "jpg", width = 1, height = 1)

    @Test
    fun `a post shows its caption, what it holds, and saves everything from the main button`() {
        val saved = mutableListOf<List<Int>>()
        show(post(picture(1), picture(2), picture(3)), onSavePost = { saved += it })

        composeRule.onNodeWithText("Sunset at the lake").assertIsDisplayed()
        composeRule.onNodeWithText("someone · 3 pictures · Instagram").assertIsDisplayed()
        composeRule.onNodeWithText("Save all 3").performClick()

        assertEquals(listOf(listOf(0, 1, 2)), saved)
    }

    @Test
    fun `tapping one item saves just that one`() {
        val saved = mutableListOf<List<Int>>()
        val clip = PostItem("https://cdn.example/clip.mp4", isVideo = true, ext = "mp4", width = 1, height = 1)
        show(post(picture(1), clip), onSavePost = { saved += it })

        composeRule.onNodeWithText("someone · 2 items · Instagram").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Save video 2 of 2").performClick()

        assertEquals(listOf(listOf(1)), saved)
    }

    @Test
    fun `a post of one picture has one button and no grid`() {
        show(post(picture(1)))

        composeRule.onNodeWithText("Save picture").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Save picture 1 of 1").assertDoesNotExist()
    }
}
