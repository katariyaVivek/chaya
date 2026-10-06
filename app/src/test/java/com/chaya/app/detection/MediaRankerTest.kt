package com.chaya.app.detection

import com.chaya.app.model.DetectedMedia
import com.chaya.app.model.DetectionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the sheet's main-item choice on the page shapes that broke v0.3:
 * stream pieces flooding the list, pre-roll ads on top, decorative
 * background loops outranking the real video, and declared page videos.
 */
class MediaRankerTest {

    private var clock = 1_000L

    /** Detection order matters (ads load first), so each fixture item gets the next timestamp. */
    private fun media(url: String, mime: String? = null, source: DetectionSource = DetectionSource.NETWORK) =
        DetectedMedia(
            url = url,
            pageUrl = PAGE,
            mimeType = mime,
            source = source,
            detectedAt = clock++,
        )

    private fun player(
        src: String? = null,
        blob: Boolean = false,
        audio: Boolean = false,
        width: Int = 640,
        height: Int = 360,
        visible: Boolean = true,
        duration: Double? = null,
        videoHeight: Int? = null,
        playing: Boolean = false,
        muted: Boolean = false,
        autoplay: Boolean = false,
        loop: Boolean = false,
        controls: Boolean = true,
        poster: String? = null,
        title: String? = null,
        ad: Boolean = false,
    ) = PlayerMeta(
        src = src,
        isBlob = blob,
        isAudio = audio,
        width = width,
        height = height,
        visible = visible,
        durationSeconds = duration,
        videoWidth = null,
        videoHeight = videoHeight,
        playing = playing,
        played = playing,
        muted = muted,
        autoplay = autoplay,
        loop = loop,
        controls = controls,
        poster = poster,
        title = title,
        inAdContainer = ad,
    )

    private fun page(
        title: String? = null,
        ogTitle: String? = null,
        siteName: String? = null,
        ogImage: String? = null,
        videoUrls: List<String> = emptyList(),
        players: List<PlayerMeta> = emptyList(),
    ) = PageMeta(
        title = title,
        ogTitle = ogTitle,
        siteName = siteName,
        ogImage = ogImage,
        videoUrls = videoUrls,
        ldName = null,
        ldThumbnail = null,
        ldDurationSeconds = null,
        players = players,
    )

    /** The emulator reproduction: one HLS video produced 30 sheet rows in v0.3. */
    @Test
    fun `hls page collapses to one main stream with its variant folded and pieces hidden`() {
        val master = media("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", source = DetectionSource.XHR_FETCH)
        val variant = media(
            "https://test-streams.mux.dev/x36xhzz/url_0/193039199_mp4_h264_aac_hd_7.m3u8",
            source = DetectionSource.XHR_FETCH,
        )
        val pieces = (0 until 28).map {
            media("https://test-streams.mux.dev/x36xhzz/url_$it/193039199_mp4_h264_aac_hd_7.ts")
        }
        val meta = page(
            title = "hls.js demo",
            players = listOf(player(blob = true, width = 784, height = 441, duration = 596.0, playing = true)),
        )

        val model = MediaRanker.rank(listOf(master, variant) + pieces, meta, PAGE, hiddenSegmentCount = 28)

        assertEquals(master, model.primary?.media)
        assertEquals(listOf(variant), model.primary?.alternates)
        assertTrue(model.others.isEmpty())
        assertTrue(model.likelyAds.isEmpty())
        assertEquals(1, model.visibleCount)
        assertEquals(28, model.hiddenSegmentCount)
        assertEquals("hls.js demo", model.primary?.title)
        assertEquals("9:56 · Stream", model.primary?.subtitle)
    }

    /** IMA-style pre-roll: the ad loads first and is short, the content video is long and largest. */
    @Test
    fun `pre-roll ad is folded into likely ads and the content video becomes primary`() {
        val ad = media("https://s0.2mdn.net/videoplayback/9f2c1e/creative.mp4", "video/mp4")
        val content = media("https://cdn.site.example/videos/launch-event.mp4", "video/mp4")
        val meta = page(
            title = "Launch event - Site Example",
            siteName = "Site Example",
            players = listOf(
                player(src = ad.url, width = 320, height = 180, duration = 15.0, playing = true, ad = true),
                player(src = content.url, width = 960, height = 540, duration = 1800.0, videoHeight = 720),
            ),
        )

        val model = MediaRanker.rank(listOf(ad, content), meta, PAGE)

        assertEquals(content, model.primary?.media)
        assertEquals("Launch event", model.primary?.title)
        assertEquals("30:00 · 720p · MP4", model.primary?.subtitle)
        assertEquals(listOf(ad), model.likelyAds.map { it.media })
        assertTrue(model.likelyAds.single().subtitle.startsWith("Ad · 0:15"))
        assertTrue(model.likelyAds.single().subtitle.endsWith("s0.2mdn.net"))
    }

    /** A muted autoplay loop behind the headline is decoration; the article's player is the content. */
    @Test
    fun `decorative background loop ranks below the article video`() {
        val loop = media("https://site.example/media/hero-loop.mp4", "video/mp4")
        val interview = media("https://site.example/media/interview.mp4", "video/mp4")
        val meta = page(
            players = listOf(
                player(
                    src = loop.url, width = 1280, height = 720, duration = 12.0, playing = true,
                    muted = true, autoplay = true, loop = true, controls = false,
                ),
                player(src = interview.url, width = 640, height = 360, duration = 240.0),
            ),
        )

        val model = MediaRanker.rank(listOf(loop, interview), meta, PAGE)

        assertEquals(interview, model.primary?.media)
        assertEquals(listOf(loop), model.others.map { it.media })
    }

    /** A declared og:video beats size and play-state signals. */
    @Test
    fun `declared page video wins over a larger player`() {
        val promo = media("https://cdn.example/v/promo.mp4", "video/mp4")
        val declared = media("https://cdn.example/v/main.mp4?sig=abc", "video/mp4")
        val meta = page(
            ogTitle = "The Main Feature | Example",
            siteName = "Example",
            videoUrls = listOf("https://cdn.example/v/main.mp4?sig=xyz"),
            players = listOf(player(src = promo.url, width = 1280, height = 720, playing = true)),
        )

        val model = MediaRanker.rank(listOf(promo, declared), meta, PAGE)

        assertEquals(declared, model.primary?.media)
        assertEquals("The Main Feature", model.primary?.title)
    }

    /** Podcast pages: the captioned audio element is the episode. */
    @Test
    fun `audio page picks the captioned player and titles it from the caption`() {
        val episode = media("https://cdn.example/audio/ep12-final-master.mp3", "audio/mpeg")
        val jingle = media("https://cdn.example/audio/sting.mp3", "audio/mpeg")
        val meta = page(
            players = listOf(
                player(src = episode.url, audio = true, width = 300, height = 54, duration = 1800.0, title = "Episode 12"),
            ),
        )

        val model = MediaRanker.rank(listOf(jingle, episode), meta, PAGE)

        assertEquals(episode, model.primary?.media)
        assertEquals("Episode 12", model.primary?.title)
        assertEquals("30:00 · MP3", model.primary?.subtitle)
    }

    @Test
    fun `a page with only ads has no primary`() {
        val ad = media("https://ads.example.doubleclick.net/vast/clip.mp4", "video/mp4")

        val model = MediaRanker.rank(listOf(ad), null, PAGE)

        assertNull(model.primary)
        assertEquals(0, model.visibleCount)
        assertEquals(1, model.likelyAds.size)
        assertEquals("Ad 1", model.likelyAds.single().title)
    }

    /** Facebook-style CDNs fetch one file in byte ranges; that is one download, not many. */
    @Test
    fun `byte-range requests of one file collapse into one item`() {
        val first = media("https://video.fbcdn.example/v/t42/clip.mp4?_nc_cat=1&bytestart=0&byteend=999", "video/mp4")
        val second = media("https://video.fbcdn.example/v/t42/clip.mp4?_nc_cat=1&bytestart=1000&byteend=1999", "video/mp4")

        val model = MediaRanker.rank(listOf(first, second), null, PAGE)

        assertEquals(1, model.visibleCount)
    }

    /** Two different videos served from one directory must not merge into one entry. */
    @Test
    fun `sibling streams with ordinary names stay separate`() {
        val first = media("https://cdn.example/hls/trailer.m3u8")
        val second = media("https://cdn.example/hls/full-match.m3u8")

        val model = MediaRanker.rank(listOf(first, second), null, PAGE)

        assertEquals(2, model.visibleCount)
        assertEquals(first, model.primary?.media)
        assertEquals("trailer", model.primary?.title)
        assertEquals("full match", model.others.single().title)
        assertTrue(model.primary?.alternates.orEmpty().isEmpty())
    }

    @Test
    fun `secondary items get readable names or ordinals, never hashes`() {
        val main = media("https://cdn.example/v/a8f3b2c9d0e1f4a5.mp4", "video/mp4")
        val bunny = media("https://cdn.example/v/BigBuckBunny.mp4", "video/mp4")
        val hashed = media("https://cdn.example/v/193039199_mp4_h264_aac_hd_7.mp4", "video/mp4")
        val meta = page(
            title = "Sample clips",
            players = listOf(player(src = main.url, width = 1280, height = 720, duration = 300.0)),
        )

        val model = MediaRanker.rank(listOf(main, bunny, hashed), meta, PAGE)

        assertEquals("Sample clips", model.primary?.title)
        assertEquals(listOf("Big Buck Bunny", "Video 3"), model.others.map { it.title })
    }

    @Test
    fun `empty input gives an empty model that still reports hidden pieces`() {
        val model = MediaRanker.rank(emptyList(), null, PAGE, hiddenSegmentCount = 4)

        assertTrue(model.isEmpty)
        assertEquals(4, model.hiddenSegmentCount)
    }

    private companion object {
        const val PAGE = "https://site.example/watch"
    }
}
