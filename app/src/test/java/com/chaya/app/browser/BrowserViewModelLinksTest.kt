package com.chaya.app.browser

import com.chaya.app.download.DownloadTask
import com.chaya.app.platform.LinkFinder
import com.chaya.app.platform.LinkState
import com.chaya.app.platform.PlatformChoice
import com.chaya.app.platform.PlatformFormatFixtures
import com.chaya.app.platform.PlatformLinks
import com.chaya.app.platform.PlatformMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * How the browser's state follows a link on YouTube, Instagram, TikTok or X: a page that is one video asks the
 * engine, moving to another video in the page asks again, leaving forgets, and a pasted or shared link opens
 * the sheet. The engine itself is a stand-in that answers at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserViewModelLinksTest {

    private val video = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    private val other = "https://www.youtube.com/watch?v=abc123xyz"

    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    private val asked = mutableListOf<String>()
    private var now = 0L
    private lateinit var viewModel: BrowserViewModel

    @Before
    fun setUp() {
        val finder = LinkFinder { url, _ ->
            asked += url
            media()
        }
        viewModel = BrowserViewModel(RuntimeEnvironment.getApplication(), PlatformLinks(scope, finder, clock = { now }))
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun media() = PlatformMedia(
        id = "id",
        title = "Never Gonna Give You Up",
        author = "Rick Astley",
        durationSeconds = 213.0,
        thumbnailUrl = null,
        pageUrl = null,
        extractor = "Youtube",
        isLive = false,
        formats = listOf(
            PlatformFormatFixtures.format(
                id = "18", ext = "mp4", width = 640, height = 360, vcodec = "avc1.42001E", acodec = "mp4a.40.2",
                url = "https://cdn.example/video-18",
            ),
        ),
    )

    // ---- a page that is one video ---- //

    @Test
    fun `a page that is one video asks the engine about it`() {
        viewModel.onPageStarted(video)

        assertTrue(viewModel.linkState.value is LinkState.Found)
        assertEquals(listOf(video), asked)
    }

    @Test
    fun `a page that is not one video clears the answer`() {
        viewModel.onPageStarted(video)

        viewModel.onPageStarted("https://example.com/article")

        assertEquals(LinkState.Idle, viewModel.linkState.value)
    }

    @Test
    fun `moving to another video inside the page asks again, without a new document`() {
        val generation = viewModel.onPageStarted(video)

        viewModel.onPageAddressChanged(other)

        assertEquals("abc123xyz", viewModel.linkState.value.match?.id)
        assertEquals(listOf(video, other), asked)
        // The page state stays tied to the document that was loaded.
        assertEquals(video, viewModel.uiState.value.url)
        assertEquals(generation, viewModel.uiState.value.navigationGeneration)
    }

    @Test
    fun `the same address reported again is not asked about twice`() {
        viewModel.onPageStarted(video)
        viewModel.onPageAddressChanged(video)
        viewModel.onPageAddressChanged("$video&t=30")

        assertEquals(1, asked.size)
    }

    @Test
    fun `going home forgets the link and closes its sheet`() {
        viewModel.onPageStarted(video)
        viewModel.toggleLinkSheet()
        assertTrue(viewModel.uiState.value.showLinkSheet)

        viewModel.goHome()

        assertEquals(LinkState.Idle, viewModel.linkState.value)
        assertFalse(viewModel.uiState.value.showLinkSheet)
    }

    @Test
    fun `a new page closes the sheet of the old one`() {
        viewModel.onPageStarted(video)
        viewModel.toggleLinkSheet()

        viewModel.onPageStarted("https://example.com/")

        assertFalse(viewModel.uiState.value.showLinkSheet)
    }

    // ---- a pasted or shared link ---- //

    @Test
    fun `a pasted link opens the sheet and looks the video up`() {
        assertTrue(viewModel.openLink(video))

        assertTrue(viewModel.uiState.value.showLinkSheet)
        assertTrue(viewModel.linkState.value is LinkState.Found)
    }

    @Test
    fun `a link inside a sentence, as a share button writes it, is found`() {
        assertTrue(viewModel.openLink("Watch this one: https://youtu.be/dQw4w9WgXcQ?si=abc123, it's great"))

        assertEquals("dQw4w9WgXcQ", viewModel.linkState.value.match?.id)
        assertEquals(listOf("https://youtu.be/dQw4w9WgXcQ?si=abc123"), asked)
    }

    @Test
    fun `punctuation after a link is not part of it`() {
        assertTrue(viewModel.openLink("(see https://youtu.be/dQw4w9WgXcQ)."))

        assertEquals(listOf("https://youtu.be/dQw4w9WgXcQ"), asked)
    }

    @Test
    fun `a link that is not one supported video does not open the sheet`() {
        assertFalse(viewModel.openLink("https://example.com/video.mp4"))
        assertFalse(viewModel.openLink("just some words"))
        assertFalse(viewModel.openLink("https://www.youtube.com/@creator"))

        assertFalse(viewModel.uiState.value.showLinkSheet)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `the sheet can be dismissed and brought back from the pill`() {
        viewModel.openLink(video)
        viewModel.dismissLinkSheet()
        assertFalse(viewModel.uiState.value.showLinkSheet)

        viewModel.toggleLinkSheet()

        assertTrue(viewModel.uiState.value.showLinkSheet)
    }

    // ---- choosing a quality ---- //

    @Test
    fun `choosing a quality closes the sheet, reports it, and queues the download`() {
        viewModel.openLink(video)
        val found = viewModel.linkState.value as LinkState.Found
        var reported: String? = null

        viewModel.downloadLink(found.best) { reported = it }

        assertFalse(viewModel.uiState.value.showLinkSheet)
        assertEquals("360p", reported)
        waitForDownload { it.url == "https://cdn.example/video-18" }
    }

    @Test
    fun `a streaming quality is handed to the stream downloader as a named manifest`() {
        viewModel.openLink(video)
        val found = viewModel.linkState.value as LinkState.Found
        val manifest = PlatformChoice(
            label = "720p",
            detail = "",
            quality = 720,
            file = PlatformFormatFixtures.format(
                id = "hls", ext = "mp4", protocol = "m3u8_native", url = "https://cdn.example/master.m3u8?token=1",
                width = 1280, height = 720, vcodec = "avc1", acodec = "mp4a",
            ),
            audioToMerge = null,
            isAudioOnly = false,
        )

        val media = streamMediaFor(found, manifest)

        assertEquals("https://cdn.example/master.m3u8?token=1", media.url)
        assertEquals("application/x-mpegURL", media.mimeType)
        assertEquals(video, media.pageUrl)
        assertEquals("Never Gonna Give You Up (720p)", media.suggestedName)
        assertEquals("Never Gonna Give You Up", media.title)
        assertEquals(720, media.qualityHeight)
    }

    @Test
    fun `a DASH manifest is told apart from an HLS one`() {
        viewModel.openLink(video)
        val found = viewModel.linkState.value as LinkState.Found
        val dash = PlatformChoice(
            "1080p", "", 1080,
            PlatformFormatFixtures.format(id = "dash", protocol = "http_dash_segments", url = "https://cdn.example/manifest.mpd"),
            null, false,
        )

        assertEquals("application/dash+xml", streamMediaFor(found, dash).mimeType)
    }

    @Test
    fun `an answer old enough for its addresses to have expired is looked up again, not downloaded`() {
        viewModel.openLink(video)
        val found = viewModel.linkState.value as LinkState.Found
        now += 21 * 60 * 1000L
        var reported: String? = null

        viewModel.downloadLink(found.best) { reported = it }

        assertNull("nothing may start from stale addresses", reported)
        assertTrue("the sheet stays open for the new answer", viewModel.uiState.value.showLinkSheet)
        assertEquals(2, asked.size)
        assertTrue(viewModel.linkState.value is LinkState.Found)
    }

    @Test
    fun `a DASH manifest whose address does not say so is still told apart by its protocol`() {
        viewModel.openLink(video)
        val found = viewModel.linkState.value as LinkState.Found
        val dash = PlatformChoice(
            "1080p", "", 1080,
            PlatformFormatFixtures.format(id = "dash", protocol = "http_dash_segments", url = "https://cdn.example/v/abc?sig=1"),
            null, false,
        )
        val hls = PlatformChoice(
            "720p", "", 720,
            PlatformFormatFixtures.format(id = "hls", protocol = "m3u8_native", url = "https://cdn.example/v/abc?sig=2"),
            null, false,
        )

        assertEquals("application/dash+xml", streamMediaFor(found, dash).mimeType)
        assertEquals("application/x-mpegURL", streamMediaFor(found, hls).mimeType)
    }

    @Test
    fun `with nothing found there is nothing to download`() {
        val choice = PlatformChoice("720p", "", 720, PlatformFormatFixtures.format(id = "x"), null, false)
        var reported = false

        viewModel.downloadLink(choice) { reported = true }

        assertFalse(reported)
    }

    private fun waitForDownload(matches: (DownloadTask) -> Boolean): DownloadTask = runBlocking {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            viewModel.downloads.value.firstOrNull(matches)?.let { return@runBlocking it }
            delay(25)
        }
        throw AssertionError("No matching download appeared: ${viewModel.downloads.value}")
    }
}
