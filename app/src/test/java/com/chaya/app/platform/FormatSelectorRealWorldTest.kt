package com.chaya.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs the selector on what yt-dlp 2026.8.19 really returned for a public YouTube video ("Me at the
 * zoo"), saved with its addresses replaced. The shape that matters: no format has both picture and
 * sound, every picture is video-only (direct MP4/WebM and HLS), and each sound track comes in a
 * plain and a loudness-compressed ("-drc") copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FormatSelectorRealWorldTest {

    private val zoo: PlatformMedia by lazy {
        val json = checkNotNull(javaClass.classLoader?.getResource("platform/youtube_me_at_the_zoo.json")) {
            "fixture missing"
        }.readText()
        PlatformMedia.parse(json)
    }

    @Test
    fun `the saved lookup is read in full`() {
        assertEquals("jNQXAC9IVRw", zoo.id)
        assertEquals("Me at the zoo", zoo.title)
        assertEquals("jawed", zoo.author)
        assertEquals(19.0, zoo.durationSeconds)
        assertEquals("Youtube", zoo.extractor)
        assertFalse(zoo.isLive)
        assertEquals(24, zoo.formats.size)
    }

    @Test
    fun `no YouTube format has both picture and sound`() {
        assertTrue(zoo.formats.none { it.isCompleteFile })
    }

    @Test
    fun `without a way to merge, only the sound can be offered`() {
        val choices = FormatSelector.choices(zoo, canMerge = false)

        assertEquals(listOf("Audio only"), choices.map { it.label })
        // The plain AAC track, not its loudness-compressed copy and not the smaller or Opus ones.
        assertEquals("140", choices.single().file.id)
    }

    @Test
    fun `with merging, each quality pairs an MP4 H264 picture with the AAC sound`() {
        val choices = FormatSelector.choices(zoo, canMerge = true)

        assertEquals(listOf("240p", "144p", "Audio only"), choices.map { it.label })

        val pictures = choices.filter { !it.isAudioOnly }
        // 133 is the higher-bitrate H.264 at 240p; AV1, VP9 and the HLS copies lose to it.
        assertEquals(listOf("133", "160"), pictures.map { it.file.id })
        assertTrue(pictures.all { it.needsMerge && it.audioToMerge?.id == "140" })
        assertTrue(pictures.all { it.file.isDirectFile && it.file.ext == "mp4" })
    }

    @Test
    fun `nothing offered is a streaming manifest, a WebM picture or a loudness-compressed copy`() {
        val offered = FormatSelector.choices(zoo, canMerge = true).flatMap { listOfNotNull(it.file, it.audioToMerge) }

        assertTrue(offered.all { it.isDirectFile })
        assertTrue(offered.none { it.ext == "webm" })
        assertTrue(offered.none { it.isLoudnessCompressed })
    }
}
