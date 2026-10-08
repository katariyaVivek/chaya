package com.chaya.app.streaming

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.StreamKey
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Pins the picker's view of a manifest: one entry per rendition (an HLS
 * master's adaptive group used to collapse into a single entry that
 * downloaded every rendition), labeled by height and ordered best-first.
 * Robolectric because Format normalizes language codes through TextUtils.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestHelperTracksTest {

    private fun video(width: Int, height: Int, peakBitrate: Int) =
        Format.Builder().setWidth(width).setHeight(height).setPeakBitrate(peakBitrate).build()

    /** Same renditions and order as test-streams.mux.dev/x36xhzz, the emulator reproduction. */
    @Test
    fun `renditions become separate entries ordered best first with their own keys`() {
        val tracks = ManifestHelper.orderedForPicker(
            listOf(
                ManifestHelper.trackFor(video(1280, 720, 2_149_280), C.TRACK_TYPE_VIDEO, StreamKey(0, 0, 0)),
                ManifestHelper.trackFor(video(320, 184, 246_440), C.TRACK_TYPE_VIDEO, StreamKey(0, 0, 1)),
                ManifestHelper.trackFor(video(512, 288, 460_560), C.TRACK_TYPE_VIDEO, StreamKey(0, 0, 2)),
                ManifestHelper.trackFor(video(848, 480, 836_280), C.TRACK_TYPE_VIDEO, StreamKey(0, 0, 3)),
                ManifestHelper.trackFor(video(1920, 1080, 6_221_600), C.TRACK_TYPE_VIDEO, StreamKey(0, 0, 4)),
                ManifestHelper.trackFor(Format.Builder().build(), C.TRACK_TYPE_AUDIO, StreamKey(0, 1, 0)),
            )
        )

        assertEquals(listOf("1080p", "720p", "480p", "288p", "184p", "Audio"), tracks.map { it.label })
        assertEquals("1920×1080 · 6.2 Mbps", tracks.first().detail)
        assertEquals(listOf(StreamKey(0, 0, 4)), tracks.first().streamKeys)
        assertEquals(1080, tracks.first().height)
    }

    @Test
    fun `one tap picks the best video and the first audio track only`() {
        val ordered = ManifestHelper.orderedForPicker(
            listOf(
                ManifestHelper.trackFor(video(1280, 720, 2_149_280), C.TRACK_TYPE_VIDEO, StreamKey(0, 0, 0)),
                ManifestHelper.trackFor(video(1920, 1080, 6_221_600), C.TRACK_TYPE_VIDEO, StreamKey(0, 0, 1)),
                ManifestHelper.trackFor(Format.Builder().setLanguage("en").build(), C.TRACK_TYPE_AUDIO, StreamKey(0, 1, 0)),
                ManifestHelper.trackFor(Format.Builder().setLanguage("hi").build(), C.TRACK_TYPE_AUDIO, StreamKey(0, 1, 1)),
            )
        )

        val chosen = ManifestHelper.bestSelection(ordered)

        assertEquals(listOf(StreamKey(0, 0, 1), StreamKey(0, 1, 0)), chosen.flatMap { it.streamKeys })
        assertEquals(1080, chosen.first().height)
    }

    @Test
    fun `one tap on audio-only or video-only streams picks what exists`() {
        val videoOnly = listOf(ManifestHelper.trackFor(video(640, 360, 800_000), C.TRACK_TYPE_VIDEO, StreamKey(0, 0, 0)))
        val audioOnly = listOf(ManifestHelper.trackFor(Format.Builder().build(), C.TRACK_TYPE_AUDIO, StreamKey(0, 0, 0)))

        assertEquals(1, ManifestHelper.bestSelection(videoOnly).size)
        assertEquals(1, ManifestHelper.bestSelection(audioOnly).size)
        assertEquals(0, ManifestHelper.bestSelection(emptyList()).size)
    }

    @Test
    fun `audio tracks are named by language and described by bitrate and channels`() {
        val track = ManifestHelper.trackFor(
            Format.Builder().setLanguage("en").setAverageBitrate(128_000).setChannelCount(2).build(),
            C.TRACK_TYPE_AUDIO,
            StreamKey(0, 1, 0),
        )

        assertEquals(Locale.forLanguageTag("en").getDisplayLanguage(Locale.getDefault()), track.label)
        assertEquals("128 kbps · 2ch", track.detail)
    }

    @Test
    fun `undetermined language falls back to a plain audio label`() {
        val track = ManifestHelper.trackFor(
            Format.Builder().setLanguage(C.LANGUAGE_UNDETERMINED).build(),
            C.TRACK_TYPE_AUDIO,
            StreamKey(0, 1, 0),
        )

        assertEquals("Audio", track.label)
        assertEquals("", track.detail)
    }
}
