package com.chaya.app.downloads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.chaya.app.download.DownloadState
import com.chaya.app.download.DownloadTask
import com.chaya.app.library.FileKind
import com.chaya.app.library.LibraryFiles
import com.chaya.app.library.VIEWER_TITLE_TAG
import com.chaya.app.library.ZipItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * The Downloads screen as a library: list and grid, kind, site and title, a post's pictures in the viewer,
 * choosing several to share or delete, and an account's ZIP opened to show what it holds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class DownloadsLibraryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val dir: File = Files.createTempDirectory("chaya-library").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val now = System.currentTimeMillis()
    private val post = "https://www.instagram.com/p/abc/\nSunset"

    private fun task(
        id: Long,
        title: String,
        fileName: String,
        mimeType: String,
        page: String,
        state: DownloadState = DownloadState.COMPLETED,
        groupKey: String? = null,
        url: String = "https://cdn.example/$id",
    ) = DownloadTask(
        id = id, url = url, pageUrl = page, fileName = fileName, mimeType = mimeType, state = state,
        filePath = File(dir, fileName).path, createdAt = now - 10_000 + id, updatedAt = now - 10_000 + id,
        title = title, groupKey = groupKey,
    )

    private val zoo = task(1, "Me at the zoo", "Me at the zoo.mp4", "video/mp4", "https://www.youtube.com/watch?v=1")
    private val sunset = (1..3).map { n ->
        task(1L + n, "Sunset ($n of 3)", "Sunset ($n of 3).jpg", "image/jpeg", "https://www.instagram.com/p/abc/", groupKey = post)
    }
    private val cat = task(5, "Cat", "Cat.webp", "image/webp", "https://x.com/a/status/5")
    private val dog = task(6, "Dog", "Dog.webp", "image/webp", "https://x.com/a/status/6")
    private val zip = task(7, "Everything @someone posted", "someone.zip", "application/zip", "https://x.com/someone",
        url = "chaya-archive://x/someone")
    private val running = task(8, "Still going", "Still going.mp4", "video/mp4", "https://www.youtube.com/watch?v=8",
        state = DownloadState.DOWNLOADING)

    private val all = listOf(zoo) + sunset + listOf(cat, dog, zip, running)

    private val events = mutableListOf<String>()

    private val zipEntries = listOf(
        ZipItem("a.jpg", "a.jpg", 10, FileKind.PICTURE),
        ZipItem("b.mp4", "b.mp4", 20, FileKind.VIDEO),
    )

    private val files = object : LibraryFiles {
        override suspend fun frameFor(task: DownloadTask): File? = null
        override suspend fun zipItems(task: DownloadTask) = zipEntries
        override suspend fun zipFile(task: DownloadTask, item: ZipItem) = File(dir, item.name).apply { writeText(item.name) }
        override fun forget(task: DownloadTask) = Unit
    }

    private val actions = DownloadActions(
        open = { events += "open ${it.id}" },
        play = { events += "play $it" },
        delete = { chosen -> events += "delete ${chosen.map { it.id }}" },
        share = { chosen -> events += "share ${chosen.map { it.id }}" },
        openFile = { file, mime -> events += "open file ${file.name} $mime" },
        shareFile = { file, _ -> events += "share file ${file.name}" },
        saveFile = { file, name, _ -> events += "save file ${file.name} as $name" },
    )

    private fun show(startInGrid: Boolean = true, tasks: List<DownloadTask> = all) {
        composeRule.setContent {
            var grid by remember { mutableStateOf(startInGrid) }
            DownloadsContent(
                tasks = tasks,
                grid = grid,
                onGridChange = {
                    grid = it
                    events += "grid $it"
                },
                files = files,
                actions = actions,
            )
        }
    }

    @Test
    fun `the switch moves between the list and a grid of what is done, a post on one tile`() {
        show(startInGrid = false)
        composeRule.onNodeWithText("Still going").assertExists()

        composeRule.onNodeWithContentDescription("Show as grid").performClick()

        composeRule.onNodeWithContentDescription("Sunset, 3 files").assertExists()
        composeRule.onNodeWithContentDescription("Dog").assertExists()
        // What is still downloading stays in the list; the grid points to it.
        composeRule.onAllNodesWithContentDescription("Still going").assertCountEquals(0)
        composeRule.onNodeWithText("1 still downloading · Show list").performClick()

        composeRule.onNodeWithText("Still going").assertExists()
        assertEquals(listOf("grid true", "grid false"), events)
    }

    @Test
    fun `kind, site and title narrow the grid`() {
        show()

        composeRule.onNodeWithText("Pictures").performClick()
        composeRule.onNodeWithContentDescription("Sunset, 3 files").assertExists()
        composeRule.onAllNodesWithContentDescription("Me at the zoo").assertCountEquals(0)

        composeRule.onNodeWithText("X").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Cat").assertExists()
        composeRule.onAllNodesWithContentDescription("Sunset, 3 files").assertCountEquals(0)

        composeRule.onNodeWithText("Pictures").performClick()
        composeRule.onNodeWithText("X").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Search downloads").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("zoo")
        composeRule.onNodeWithContentDescription("Me at the zoo").assertExists()
        composeRule.onAllNodesWithContentDescription("Dog").assertCountEquals(0)

        composeRule.onNode(hasSetTextAction()).performTextInput("nothing")
        composeRule.onNodeWithText("Nothing matches").assertExists()
    }

    @Test
    fun `a post's tile opens the viewer at its first picture, and swipes inside the post`() {
        show()

        composeRule.onNodeWithContentDescription("Sunset, 3 files").performClick()

        composeRule.onNodeWithText("1 of 3").assertExists()
        composeRule.onNodeWithTag(VIEWER_TITLE_TAG).assertTextEquals("Sunset (1 of 3)")
        composeRule.onNodeWithContentDescription("Share").performClick()
        assertEquals(listOf("share [2]"), events)

        composeRule.onNodeWithContentDescription("Close").performClick()
        composeRule.onAllNodesWithText("1 of 3").assertCountEquals(0)
    }

    @Test
    fun `a picture on its own opens at its place among the other pictures on their own`() {
        show()

        composeRule.onNodeWithContentDescription("Cat").performClick()

        // Newest first: Dog, then Cat.
        composeRule.onNodeWithText("2 of 2").assertExists()
        composeRule.onNodeWithTag(VIEWER_TITLE_TAG).assertTextEquals("Cat")
    }

    @Test
    fun `a video opens in another app as before`() {
        show()

        composeRule.onNodeWithContentDescription("Me at the zoo").performClick()

        assertEquals(listOf("open 1"), events)
    }

    @Test
    fun `a long press chooses, taps add more, and the chosen are shared or deleted together`() {
        show()

        composeRule.onNodeWithContentDescription("Dog").performTouchInput { longClick() }
        composeRule.onNodeWithText("1 chosen").assertExists()
        composeRule.onNodeWithContentDescription("Dog").assertIsSelected()
        composeRule.onNodeWithContentDescription("Sunset, 3 files").performClick()
        composeRule.onNodeWithText("2 chosen").assertExists()

        composeRule.onNodeWithContentDescription("Share chosen").performClick()
        composeRule.onNodeWithContentDescription("Delete chosen").performClick()

        assertEquals(listOf("share [6, 2, 3, 4]", "delete [6, 2, 3, 4]"), events)
        composeRule.onAllNodesWithText("2 chosen").assertCountEquals(0)
    }

    @Test
    fun `an account's ZIP opens to show what it holds, one file at a time`() {
        show()

        composeRule.onNodeWithContentDescription("Everything @someone posted").performClick()
        composeRule.onNodeWithText("2 files").assertExists()

        composeRule.onNodeWithContentDescription("b.mp4").performClick()
        composeRule.waitUntil(5_000) { events.isNotEmpty() }
        assertTrue(events.last(), events.last().startsWith("open file b.mp4 "))

        composeRule.onNodeWithContentDescription("b.mp4").performTouchInput { longClick() }
        composeRule.onNodeWithText("Save to phone").performClick()
        composeRule.waitUntil(5_000) { events.size == 2 }
        assertEquals("save file b.mp4 as b.mp4", events.last())

        composeRule.onNodeWithContentDescription("a.jpg").performClick()
        composeRule.onNodeWithTag(VIEWER_TITLE_TAG).assertTextEquals("a.jpg")
        composeRule.onNodeWithContentDescription("Save to phone").assertExists()
    }

    @Test
    fun `a finished file can be shared from the list`() {
        show(startInGrid = false, tasks = listOf(zoo))

        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Share").performClick()

        assertEquals(listOf("share [1]"), events)
    }
}
