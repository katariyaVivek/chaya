package com.chaya.app.subtitles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chaya.app.ui.player.ExportKind
import com.chaya.app.ui.player.ExportPanel
import com.chaya.app.ui.player.Exporting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** `core::export`'s cases that are not ffmpeg's, ported: a size to fit, a clip's SRT, its name; and the panel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipExportTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    /** The walk in the park Windows' tests use. */
    private val walk = listOf(
        SubtitleLine(1_000, 4_000, "This is a walk in the park."),
        SubtitleLine(5_000, 8_000, "The numbers count the seconds."),
        SubtitleLine(9_000, 12_000, "Chaya draws these lines itself,"),
        SubtitleLine(13_000, 16_000, "and lists them\nbeside the video."),
    )

    @Test
    fun `a size to fit sets the bit rates and makes the picture smaller until each pixel has enough`() {
        // A minute in 10 MB: 1.27 Mb/s, 96 kb/s of it sound; 1080p and 720p would be too thin, 540p is not.
        assertEquals(ClipExport.Fit(960, 540, 1_170_666, 96_000), ClipExport.fit(10_000_000, 60_000, 1920, 1080, true))
        // Twenty seconds in 25 MB, no sound: room enough for 720p as it is.
        assertEquals(ClipExport.Fit(1280, 720, 9_500_000, 0), ClipExport.fit(25_000_000, 20_000, 1280, 720, false))
        // A standing phone video keeps its shape.
        val standing = ClipExport.fit(10_000_000, 60_000, 1080, 1920, true)!!
        assertEquals(540 to 960, standing.width to standing.height)
        // Half an hour in 10 MB is not a video any more.
        assertNull(ClipExport.fit(10_000_000, 1_800_000, 1920, 1080, true))
        assertEquals(8_000_000, ClipExport.bitRate(1920, 1080))
        assertEquals(1_500_000, ClipExport.bitRate(320, 180))
    }

    @Test
    fun `an SRT for a clip is timed from its start and cut at its edges`() {
        assertEquals(
            "1\n00:00:00,000 --> 00:00:02,000\nThe numbers count the seconds.\n\n" +
                "2\n00:00:03,000 --> 00:00:06,000\nChaya draws these lines itself,\n\n" +
                "3\n00:00:07,000 --> 00:00:07,500\nand lists them\nbeside the video.\n\n",
            ClipExport.srt(walk, 6_000, 13_500),
        )
    }

    @Test
    fun `a translation goes in the same SRT, each line with its own under it`() {
        val french = listOf("FR This is a walk in the park.", "FR The numbers count the seconds.", null, null)
        assertEquals(
            "1\n00:00:00,000 --> 00:00:03,000\nThis is a walk in the park.\nFR This is a walk in the park.\n\n" +
                "2\n00:00:04,000 --> 00:00:07,000\nThe numbers count the seconds.\nFR The numbers count the seconds.\n\n" +
                "3\n00:00:08,000 --> 00:00:11,000\nChaya draws these lines itself,\n\n",
            ClipExport.srt(walk, 1_000, 12_000, french),
        )
        assertEquals("a translation that no longer fits is left out", walk, ClipExport.together(walk, listOf("x")))
    }

    @Test
    fun `a clip is named after its video and never over another`() {
        val dir = folder.newFolder()
        val first = ClipExport.fileFor(dir, "A walk: in the park?", "The \"best\" bit", "mp4")
        assertEquals("A walk in the park - The best bit.mp4", first.name)
        first.writeText("")
        assertEquals("A walk in the park - The best bit (2).mp4", ClipExport.fileFor(dir, "A walk: in the park?", "The \"best\" bit", "mp4").name)
        assertEquals("Clip.srt", ClipExport.fileFor(dir, "", "", "srt").name)
    }

    @Test
    fun `the panel saves what is chosen, at the size chosen, and offers Share once it is made`() {
        val asked = mutableListOf<Pair<ExportKind, Int?>>()
        var state by androidx.compose.runtime.mutableStateOf<Exporting>(Exporting.Idle)
        composeRule.setContent {
            ExportPanel(hasSubtitles = true, state = state, onExport = { k, s -> asked += k to s }, onStop = {}, onShare = { _, _ -> }, onSave = { _, _ -> })
        }
        composeRule.onNodeWithText("25 MB").performClick()
        composeRule.onNodeWithText("Save clip").performClick()
        composeRule.onNodeWithText("Subtitles (SRT)").performClick()
        composeRule.onNodeWithText("Save clip").performClick()
        assertEquals(listOf(ExportKind.WITH_SUBTITLES to 25, ExportKind.SRT to null), asked)
        state = Exporting.Done(File("A walk - Clip 1.mp4"), "video/mp4")
        composeRule.onNodeWithText("Share").assertExists()
        composeRule.onNodeWithText("A walk - Clip 1.mp4").assertExists()
    }
}
