package com.chaya.app.browser

import com.chaya.app.download.DownloadState
import com.chaya.app.download.DownloadTask
import com.chaya.app.platform.LinkFinder
import com.chaya.app.platform.LinkState
import com.chaya.app.platform.PlatformChoice
import com.chaya.app.platform.PlatformFormatFixtures
import com.chaya.app.platform.PlatformLinks
import com.chaya.app.platform.PlatformMedia
import com.chaya.app.platform.PostItem
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
    private var answer: () -> PlatformMedia = { media() }
    private lateinit var viewModel: BrowserViewModel

    @Before
    fun setUp() {
        val finder = LinkFinder { url, _ ->
            asked += url
            answer()
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

    @Test
    fun `saving a post's items queues each as its own named file and closes the sheet`() {
        answer = {
            media().copy(
                title = "Sunset at the lake",
                formats = emptyList(),
                items = listOf(
                    PostItem("https://cdn.example/one.jpg", isVideo = false, ext = "jpg", width = 1, height = 1),
                    PostItem("https://cdn.example/two.mp4", isVideo = true, ext = "mp4", width = 1, height = 1),
                    PostItem("https://cdn.example/three.jpg", isVideo = false, ext = "jpg", width = 1, height = 1),
                ),
            )
        }
        viewModel.openLink("https://www.instagram.com/p/Cabc123/")
        assertTrue(viewModel.linkState.value is LinkState.FoundPost)
        var queued = 0

        viewModel.downloadPost(listOf(0, 2, 2, 9)) { queued = it }

        assertEquals("duplicates and positions outside the post are ignored", 2, queued)
        assertFalse(viewModel.uiState.value.showLinkSheet)
        waitForDownload { it.fileName == "Sunset at the lake (1 of 3).jpg" }
        waitForDownload { it.fileName == "Sunset at the lake (3 of 3).jpg" && it.url == "https://cdn.example/three.jpg" }
    }

    @Test
    fun `a post is not saved from a video's answer`() {
        viewModel.openLink(video)
        var queued = 0

        viewModel.downloadPost(listOf(0)) { queued = it }

        assertEquals(0, queued)
        assertTrue(viewModel.uiState.value.showLinkSheet)
    }

    // ---- tabs ---- //

    @Test
    fun `a new tab opens on the start screen, and the tab left keeps its page with its sheets closed`() {
        val generation = viewModel.onPageStarted(video)
        viewModel.onPageFinished(video, "Never Gonna Give You Up", generation)
        viewModel.toggleLinkSheet()

        val kept = viewModel.switchTab(incoming = null, canGoBack = false, canGoForward = false)

        assertEquals(video, kept.url)
        assertEquals("Never Gonna Give You Up", kept.pageTitle)
        assertFalse(kept.showLinkSheet)
        val shown = viewModel.uiState.value
        assertTrue(shown.homeVisible)
        assertEquals("", shown.url)
        assertEquals("the new tab forgets the other tab's link", LinkState.Idle, viewModel.linkState.value)
    }

    @Test
    fun `going back to a tab brings back its page, its history buttons and its link`() {
        val generation = viewModel.onPageStarted(video)
        viewModel.onPageFinished(video, "Never Gonna Give You Up", generation)
        val kept = viewModel.switchTab(incoming = null, canGoBack = false, canGoForward = false)
        viewModel.onPageStarted("https://example.com/")
        asked.clear()

        viewModel.switchTab(incoming = kept, canGoBack = true, canGoForward = false)

        val shown = viewModel.uiState.value
        assertEquals(video, shown.url)
        assertEquals("Never Gonna Give You Up", shown.pageTitle)
        assertTrue(shown.canGoBack)
        assertTrue("the video behind the page is offered again", viewModel.linkState.value is LinkState.Found)

        // The page's own document still reports into the state it got back.
        viewModel.onProgressChanged(70, generation)
        assertEquals(70, viewModel.uiState.value.progress)
    }

    @Test
    fun `an account page's offer comes back with its tab, and goes with the next one`() {
        viewModel.onPageStarted("https://www.instagram.com/someone/")
        val kept = viewModel.switchTab(incoming = null, canGoBack = false, canGoForward = false)
        assertNull(viewModel.uiState.value.profile)

        viewModel.switchTab(incoming = kept, canGoBack = false, canGoForward = false)
        assertEquals("someone", viewModel.uiState.value.profile?.username)
    }

    @Test
    fun `media a tab found is kept with it, and late reports from the tab left are ignored`() {
        val generation = viewModel.onPageStarted("https://news.example/story")
        viewModel.onMediaDetected(file("https://cdn.example/a.mp4"), generation)
        val kept = viewModel.switchTab(incoming = null, canGoBack = false, canGoForward = false)

        viewModel.onMediaDetected(file("https://cdn.example/late.mp4"), generation)
        assertTrue(viewModel.uiState.value.detectedMedia.isEmpty())

        viewModel.switchTab(incoming = kept, canGoBack = false, canGoForward = false)
        assertEquals(listOf("https://cdn.example/a.mp4"), viewModel.uiState.value.detectedMedia.map { it.url })
    }

    private fun file(url: String) = com.chaya.app.model.DetectedMedia(
        url = url,
        pageUrl = "https://news.example/story",
        mimeType = "video/mp4",
        source = com.chaya.app.model.DetectionSource.NETWORK,
    )

    // ---- an account, saved as one ZIP ---- //

    @Test
    fun `a pasted account link opens the account sheet, not the video sheet`() {
        assertTrue(viewModel.openLink("https://www.instagram.com/someone/"))

        assertEquals("someone", viewModel.uiState.value.profile?.username)
        assertTrue(viewModel.uiState.value.showProfileSheet)
        assertFalse(viewModel.uiState.value.showLinkSheet)
        assertTrue("an account is not looked up as a video", asked.isEmpty())
    }

    @Test
    fun `an account page offers its posts, and leaving it takes the offer away`() {
        viewModel.onPageStarted("https://x.com/someone")
        assertEquals("someone", viewModel.uiState.value.profile?.username)

        viewModel.onPageAddressChanged("https://x.com/someone/status/1")
        assertNull(viewModel.uiState.value.profile)
    }

    @Test
    fun `saving an account starts its archive with the sign-in choice made on the sheet`() {
        viewModel.openLink("https://www.instagram.com/someone/")
        var started: String? = null

        viewModel.saveProfile(useSignIn = false) { started = it }

        assertEquals("someone (Instagram).zip", started)
        assertFalse(viewModel.uiState.value.showProfileSheet)
        waitForDownload { it.url == "chaya-archive:instagram:someone" && it.isArchive }
        // There is no engine under test, so the listing fails. Waiting for that keeps this archive from still
        // writing to the database, which every test's app shares, while the next test runs.
        waitForDownload { it.isArchive && it.state == DownloadState.FAILED }
    }
}

