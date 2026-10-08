package com.chaya.app.platform

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class FormatSelectorTest {

    private lateinit var originalLocale: Locale

    @Before
    fun fixLocale() {
        // File sizes are formatted with the default locale; pin it so "48.0 MB" is "48.0 MB" everywhere.
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() = Locale.setDefault(originalLocale)

    @Test
    fun `ready-made files come best first and end with audio only`() {
        val media = media(
            complete("18", 640, 360),
            complete("22", 1280, 720),
            audio("140", ext = "m4a", abr = 129.0),
        )

        val choices = FormatSelector.choices(media, canMerge = false)

        assertEquals(listOf("720p", "360p", "Audio only"), choices.map { it.label })
        assertEquals(listOf(720, 360, null), choices.map { it.quality })
        assertEquals(listOf(false, false, true), choices.map { it.isAudioOnly })
    }

    @Test
    fun `qualities that exist only as separate tracks are hidden while merging is unavailable`() {
        val media = media(
            complete("18", 640, 360),
            pictureOnly("137", 1920, 1080, vcodec = "avc1.640028"),
            audio("140", ext = "m4a"),
        )

        val choices = FormatSelector.choices(media, canMerge = false)

        assertEquals(listOf("360p", "Audio only"), choices.map { it.label })
    }

    @Test
    fun `separate picture and sound are paired once merging is available`() {
        val media = media(
            complete("18", 640, 360),
            pictureOnly("137", 1920, 1080, vcodec = "avc1.640028", size = 40_000_000),
            audio("140", ext = "m4a", size = 3_000_000),
        )

        val hd = FormatSelector.choices(media, canMerge = true).first()

        assertEquals("1080p", hd.label)
        assertTrue(hd.needsMerge)
        assertEquals("137", hd.file.id)
        assertEquals("140", hd.audioToMerge?.id)
        assertEquals(43_000_000L, hd.sizeBytes)
    }

    @Test
    fun `for a merge the MP4 H264 picture beats WebM and AV1 at the same quality`() {
        val media = media(
            pictureOnly("248", 1920, 1080, ext = "webm", vcodec = "vp9", tbr = 2500.0),
            pictureOnly("399", 1920, 1080, vcodec = "av01.0.08M.08", tbr = 1800.0),
            pictureOnly("137", 1920, 1080, vcodec = "avc1.640028", tbr = 4000.0),
            audio("140", ext = "m4a"),
        )

        val hd = FormatSelector.choices(media, canMerge = true).first()

        assertEquals("137", hd.file.id)
    }

    @Test
    fun `a WebM-only quality is not offered for merging`() {
        val media = media(
            pictureOnly("248", 1920, 1080, ext = "webm", vcodec = "vp9"),
            audio("140", ext = "m4a"),
        )

        val choices = FormatSelector.choices(media, canMerge = true)

        assertEquals(listOf("Audio only"), choices.map { it.label })
    }

    @Test
    fun `sound that joins to MP4 without re-encoding wins over WebM sound`() {
        val media = media(
            pictureOnly("137", 1920, 1080, vcodec = "avc1.640028"),
            audio("251", ext = "webm", abr = 160.0),
            audio("140", ext = "m4a", abr = 129.0),
        )

        val choices = FormatSelector.choices(media, canMerge = true)

        assertEquals("140", choices.first().audioToMerge?.id)
        assertEquals("140", choices.last().file.id)
    }

    @Test
    fun `sound that cannot join an MP4 is not paired with a picture`() {
        val media = media(
            pictureOnly("137", 1920, 1080, vcodec = "avc1.640028"),
            audio("251", ext = "webm", abr = 160.0),
        )

        val choices = FormatSelector.choices(media, canMerge = true)

        // The WebM sound is still offered on its own.
        assertEquals(listOf("Audio only"), choices.map { it.label })
        assertEquals("251", choices.single().file.id)
    }

    @Test
    fun `the original-language sound beats a dub whatever the bitrate`() {
        val media = media(
            pictureOnly("137", 1920, 1080, vcodec = "avc1.640028"),
            audio("140-1", ext = "m4a", abr = 129.0, languagePreference = -1), // a dub
            audio("140-0", ext = "m4a", abr = 48.0, languagePreference = 10), // the original
        )

        val choices = FormatSelector.choices(media, canMerge = true)

        assertEquals("140-0", choices.first().audioToMerge?.id)
        assertEquals("140-0", choices.last().file.id)
    }

    @Test
    fun `plain sound is chosen over its loudness-compressed copy`() {
        val media = media(
            audio("140-drc", ext = "m4a", abr = 130.0, note = "medium, DRC"),
            audio("140", ext = "m4a", abr = 129.0, note = "medium"),
        )

        assertEquals("140", FormatSelector.choices(media, canMerge = false).single().file.id)
    }

    @Test
    fun `a loudness-compressed copy is recognized by its id even without a note`() {
        val media = media(
            audio("251-drc", ext = "m4a", abr = 200.0),
            audio("251", ext = "m4a", abr = 100.0),
        )

        assertEquals("251", FormatSelector.choices(media, canMerge = false).single().file.id)
    }

    @Test
    fun `SDR is chosen over HDR at the same quality`() {
        val ready = media(
            complete("hdr", 1920, 1080, tbr = 9000.0, dynamicRange = "HDR10"),
            complete("sdr", 1920, 1080, tbr = 4000.0, dynamicRange = "SDR"),
        )
        val pair = media(
            pictureOnly("av1-hdr", 1920, 1080, vcodec = "av01.0.12M.10", tbr = 5000.0, dynamicRange = "HDR10"),
            pictureOnly("av1-sdr", 1920, 1080, vcodec = "av01.0.08M.08", tbr = 3000.0, dynamicRange = "SDR"),
            audio("140", ext = "m4a"),
        )

        assertEquals("sdr", FormatSelector.choices(ready, canMerge = false).single().file.id)
        assertEquals("av1-sdr", FormatSelector.choices(pair, canMerge = true).first().file.id)
    }

    @Test
    fun `HDR is still offered when it is the only picture at that quality`() {
        val media = media(complete("hdr", 1920, 1080, dynamicRange = "HDR10"))

        assertEquals("hdr", FormatSelector.choices(media, canMerge = false).single().file.id)
    }

    @Test
    fun `only pictures the app can join are paired with sound`() {
        val media = media(
            pictureOnly("h264-1080", 1920, 1080, vcodec = "avc1.640028"),
            pictureOnly("av1-2160", 3840, 2160, vcodec = "av01.0.12M.08"),
            pictureOnly("h264-720", 1280, 720, vcodec = "avc1.4d401f"),
            audio("140", ext = "m4a"),
        )

        val choices = FormatSelector.choices(media) { it.videoCodec?.startsWith("avc") == true }

        assertEquals(listOf("1080p", "720p", "Audio only"), choices.map { it.label })
    }

    @Test
    fun `a ready-made file beats a merge at the same quality`() {
        val media = media(
            complete("22", 1280, 720),
            pictureOnly("136", 1280, 720, vcodec = "avc1.4d401f"),
            audio("140", ext = "m4a"),
        )

        val choice = FormatSelector.choices(media, canMerge = true).first()

        assertEquals("22", choice.file.id)
        assertFalse(choice.needsMerge)
    }

    @Test
    fun `a streaming manifest is offered when nothing else reaches that quality`() {
        val media = media(
            complete("18", 640, 360),
            stream("hls-1080", 1920, 1080),
        )

        val choices = FormatSelector.choices(media, canMerge = false)

        assertEquals(listOf("1080p", "360p"), choices.map { it.label })
        assertEquals("hls-1080", choices.first().file.id)
        assertFalse(choices.first().file.isDirectFile)
        assertFalse(choices.first().needsMerge)
    }

    @Test
    fun `a merge of direct files beats a streaming manifest at the same quality`() {
        val media = media(
            stream("hls-1080", 1920, 1080),
            pictureOnly("137", 1920, 1080, vcodec = "avc1.640028"),
            audio("140", ext = "m4a"),
        )

        assertEquals("137", FormatSelector.choices(media, canMerge = true).first().file.id)
        assertEquals("hls-1080", FormatSelector.choices(media, canMerge = false).first().file.id)
    }

    @Test
    fun `a streaming manifest without sound is not offered`() {
        val media = media(stream("hls-v", 1280, 720, acodec = "none"))

        assertTrue(FormatSelector.choices(media, canMerge = true).isEmpty())
    }

    @Test
    fun `vertical videos are named by their shorter side`() {
        val media = media(
            complete("h264_540p", 576, 1024),
            complete("bytevc1_1080p", 1080, 1920),
        )

        assertEquals(listOf("1080p", "576p"), FormatSelector.choices(media, canMerge = false).map { it.label })
    }

    @Test
    fun `files with unknown codecs are complete files, as simple sites report them`() {
        val media = media(
            unknownCodecs("http-632", 640, 360),
            unknownCodecs("http-2176", 1280, 720),
        )

        val choices = FormatSelector.choices(media, canMerge = false)

        assertEquals(listOf("720p", "360p"), choices.map { it.label })
        assertTrue(choices.none { it.isAudioOnly })
    }

    @Test
    fun `a high frame rate is part of the label`() {
        val media = media(
            complete("fast", 1920, 1080, fps = 60.0),
            complete("normal", 1280, 720, fps = 30.0),
            complete("unknown", 640, 360, fps = null),
        )

        assertEquals(listOf("1080p60", "720p", "360p"), FormatSelector.choices(media, canMerge = false).map { it.label })
    }

    @Test
    fun `several files at one quality give a single choice, preferring MP4 then bitrate`() {
        val media = media(
            complete("web", 1280, 720, ext = "webm", tbr = 3000.0),
            complete("mp4-low", 1280, 720, tbr = 1000.0),
            complete("mp4-high", 1280, 720, tbr = 2000.0),
        )

        val choices = FormatSelector.choices(media, canMerge = false)

        assertEquals(1, choices.size)
        assertEquals("mp4-high", choices.single().file.id)
    }

    @Test
    fun `live broadcasts offer nothing`() {
        val media = media(complete("live", 1280, 720), live = true)

        assertTrue(FormatSelector.choices(media, canMerge = true).isEmpty())
    }

    @Test
    fun `storyboards and formats without a size in pixels are ignored`() {
        val media = media(
            PlatformFormatFixtures.format(id = "sb0", ext = "mhtml", vcodec = "none", acodec = "none", width = 48, height = 27),
            complete("unsized", null, null),
        )

        assertTrue(FormatSelector.choices(media, canMerge = true).isEmpty())
    }

    @Test
    fun `a page with only sound offers audio only`() {
        val media = media(audio("a1", ext = "m4a"), audio("a2", ext = "webm", abr = 200.0))

        val choices = FormatSelector.choices(media, canMerge = false)

        assertEquals(listOf("Audio only"), choices.map { it.label })
        assertNull(choices.single().quality)
        assertEquals("a1", choices.single().file.id)
    }

    @Test
    fun `nothing found gives no choices`() {
        assertTrue(FormatSelector.choices(media(), canMerge = true).isEmpty())
    }

    @Test
    fun `details give the container and the size when known`() {
        val media = media(
            complete("sized", 1280, 720, size = 50_331_648),
            complete("unsized", 640, 360),
            audio("a", ext = "m4a", size = 4_194_304),
        )

        val details = FormatSelector.choices(media, canMerge = false).map { it.detail }

        assertEquals(listOf("MP4 · ≈ 48.0 MB", "MP4", "M4A · ≈ 4.0 MB"), details)
    }

    @Test
    fun `a merged choice shows both pieces in its size`() {
        val media = media(
            pictureOnly("137", 1920, 1080, vcodec = "avc1.640028", size = 31_457_280),
            audio("140", ext = "m4a", size = 3_145_728),
        )

        assertEquals("MP4 · ≈ 33.0 MB", FormatSelector.choices(media, canMerge = true).first().detail)
    }

    @Test
    fun `an unknown audio size does not shrink the total below the picture`() {
        val media = media(
            pictureOnly("137", 1920, 1080, vcodec = "avc1.640028", size = 20_971_520),
            audio("140", ext = "m4a", size = null),
        )

        assertEquals(20_971_520L, FormatSelector.choices(media, canMerge = true).first().sizeBytes)
    }

    // region fixtures

    private fun media(vararg formats: PlatformFormat, live: Boolean = false) = PlatformMedia(
        id = "id",
        title = "Title",
        author = null,
        durationSeconds = null,
        thumbnailUrl = null,
        pageUrl = null,
        extractor = null,
        isLive = live,
        formats = formats.toList(),
    )

    /** A single file with picture and sound, like YouTube's format 18 or a TikTok download. */
    private fun complete(
        id: String,
        width: Int?,
        height: Int?,
        ext: String = "mp4",
        fps: Double? = null,
        tbr: Double? = null,
        size: Long? = null,
        dynamicRange: String? = null,
    ) = PlatformFormatFixtures.format(
        id = id, ext = ext, width = width, height = height, fps = fps, tbr = tbr, size = size,
        vcodec = "avc1.42001E", acodec = "mp4a.40.2", dynamicRange = dynamicRange,
    )

    private fun pictureOnly(
        id: String,
        width: Int,
        height: Int,
        ext: String = "mp4",
        vcodec: String,
        tbr: Double? = null,
        size: Long? = null,
        dynamicRange: String? = null,
    ) = PlatformFormatFixtures.format(
        id = id, ext = ext, width = width, height = height, tbr = tbr, size = size,
        vcodec = vcodec, acodec = "none", dynamicRange = dynamicRange,
    )

    private fun audio(
        id: String,
        ext: String,
        abr: Double? = null,
        size: Long? = null,
        note: String? = null,
        languagePreference: Int? = null,
    ) = PlatformFormatFixtures.format(
        id = id, ext = ext, abr = abr, size = size, vcodec = "none", acodec = "mp4a.40.2",
        note = note, languagePreference = languagePreference,
    )

    private fun stream(id: String, width: Int, height: Int, acodec: String = "mp4a.40.2") =
        PlatformFormatFixtures.format(
            id = id, ext = "mp4", protocol = "m3u8_native", width = width, height = height,
            vcodec = "avc1.640028", acodec = acodec, url = "https://cdn.example/$id/index.m3u8",
        )

    private fun unknownCodecs(id: String, width: Int, height: Int) =
        PlatformFormatFixtures.format(id = id, ext = "mp4", width = width, height = height)

    // endregion
}

/** Builds a [PlatformFormat] with everything optional, for tests in this package. */
internal object PlatformFormatFixtures {
    fun format(
        id: String,
        ext: String? = "mp4",
        protocol: String? = "https",
        url: String = "https://cdn.example/$id",
        width: Int? = null,
        height: Int? = null,
        fps: Double? = null,
        vcodec: String? = null,
        acodec: String? = null,
        tbr: Double? = null,
        abr: Double? = null,
        size: Long? = null,
        note: String? = null,
        languagePreference: Int? = null,
        dynamicRange: String? = null,
        headers: Map<String, String> = emptyMap(),
    ) = PlatformFormat(
        id = id,
        url = url,
        ext = ext,
        protocol = protocol,
        width = width,
        height = height,
        fps = fps,
        videoCodec = vcodec,
        audioCodec = acodec,
        bitrateKbps = tbr,
        audioBitrateKbps = abr,
        sizeBytes = size,
        note = note,
        language = null,
        headers = headers,
        languagePreference = languagePreference,
        dynamicRange = dynamicRange,
    )
}
