package com.chaya.app.browser

import com.chaya.app.detection.MediaSheetModel
import com.chaya.app.detection.RankedMedia
import com.chaya.app.home.BarAction
import com.chaya.app.model.DetectedMedia
import com.chaya.app.model.DetectionSource
import com.chaya.app.model.MediaKind
import com.chaya.app.platform.LinkState
import com.chaya.app.platform.Platform
import com.chaya.app.platform.PlatformException
import com.chaya.app.platform.PlatformMatcher
import com.chaya.app.platform.PlatformMedia
import com.chaya.app.platform.ProfileMatch
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the bar's ink circle shows on a page: a site's video first (looking, then found), then an account's posts,
 * then what the page plays; a new tab when there is nothing, or the lookup failed, so a page never nags.
 */
class PageFindTest {

    private val match = PlatformMatcher.match("https://www.youtube.com/watch?v=dQw4w9WgXcQ")!!

    private val video = PlatformMedia(
        id = "dQw4w9WgXcQ",
        title = "Never Gonna Give You Up",
        author = "Rick Astley",
        durationSeconds = 213.0,
        thumbnailUrl = null,
        pageUrl = match.url,
        extractor = "Youtube",
        isLive = false,
        formats = emptyList(),
    )

    private val account = ProfileMatch(Platform.INSTAGRAM, "harbour.dusk", "https://www.instagram.com/harbour.dusk/")

    private fun ranked(title: String, ad: Boolean = false) = RankedMedia(
        media = DetectedMedia(
            url = "https://cdn.example/$title.m3u8",
            pageUrl = "https://site.example/watch",
            mimeType = "application/vnd.apple.mpegurl",
            source = DetectionSource.NETWORK,
        ),
        kind = MediaKind.STREAM,
        title = title,
        subtitle = "10:35 · Stream",
        thumbnailUrl = null,
        durationSeconds = 635.0,
        videoHeight = null,
        isLikelyAd = ad,
        score = 0,
    )

    private val playing = MediaSheetModel(ranked("Big Buck Bunny"), emptyList(), emptyList(), 0)

    @Test
    fun `a page with nothing to save, and the start page, offer a new tab`() {
        assertEquals(PageFind.Nothing, PageFind.of(false, LinkState.Idle, null, MediaSheetModel.EMPTY))
        assertEquals(PageFind.Nothing, PageFind.of(true, LinkState.Looking(match), account, playing))
        assertEquals(BarAction.NewTab, PageFind.Nothing.action)
    }

    @Test
    fun `a site's video is looked up first, then saved from its own sheet`() {
        val looking = PageFind.of(false, LinkState.Looking(match), account, playing)
        assertEquals(PageFind.Looking("YouTube", "video"), looking)
        assertEquals(BarAction.Looking("Finding the video on YouTube"), looking.action)

        val found = PageFind.of(false, LinkState.FoundPost(match, video), account, playing)
        assertEquals(PageFind.Found("Never Gonna Give You Up", FoundSheet.LINK), found)
        assertEquals(BarAction.Save("Save Never Gonna Give You Up"), found.action)
    }

    @Test
    fun `a failed lookup shows nothing, even with media on the page`() {
        val failed = LinkState.Failed(match, PlatformException(PlatformException.Kind.PRIVATE), signInMayHelp = false)
        assertEquals(PageFind.Nothing, PageFind.of(false, failed, null, playing))
    }

    @Test
    fun `an account page offers its posts before what the page plays`() {
        assertEquals(
            PageFind.Found("@harbour.dusk's posts from Instagram", FoundSheet.PROFILE),
            PageFind.of(false, LinkState.Idle, account, playing),
        )
    }

    @Test
    fun `media on the page is named by its main item, else counted`() {
        assertEquals(PageFind.Found("Big Buck Bunny", FoundSheet.MEDIA), PageFind.of(false, LinkState.Idle, null, playing))
        val two = MediaSheetModel(null, listOf(ranked("A"), ranked("B")), emptyList(), 0)
        assertEquals(PageFind.Found("2 items", FoundSheet.MEDIA), PageFind.of(false, LinkState.Idle, null, two))
        val adsOnly = MediaSheetModel(null, emptyList(), listOf(ranked("Ad", ad = true)), 0)
        assertEquals(PageFind.Found("what looks like ads", FoundSheet.MEDIA), PageFind.of(false, LinkState.Idle, null, adsOnly))
    }
}
