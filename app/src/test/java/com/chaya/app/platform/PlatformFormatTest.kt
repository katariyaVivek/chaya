package com.chaya.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformFormatTest {

    @Test
    fun `a track yt-dlp marks as none is absent, anything else is present`() {
        val pictureOnly = format(vcodec = "avc1.640028", acodec = "none")
        val soundOnly = format(vcodec = "none", acodec = "mp4a.40.2")
        val complete = format(vcodec = "avc1.42001E", acodec = "mp4a.40.2")

        assertTrue(pictureOnly.hasVideo)
        assertFalse(pictureOnly.hasAudio)
        assertFalse(soundOnly.hasVideo)
        assertTrue(soundOnly.hasAudio)
        assertTrue(complete.hasVideo && complete.hasAudio)
    }

    @Test
    fun `unknown codecs count as present because simple sites leave them out`() {
        val unknown = format(vcodec = null, acodec = null)

        assertTrue(unknown.hasVideo)
        assertTrue(unknown.hasAudio)
        assertTrue(unknown.isCompleteFile)
        assertFalse(unknown.isAudioOnly)
    }

    @Test
    fun `audio only means sound present and picture explicitly absent`() {
        assertTrue(format(vcodec = "none", acodec = "opus").isAudioOnly)
        assertTrue(format(vcodec = null, acodec = "mp4a.40.2").isAudioOnly)
        // A bare audio link often names no codec at all, but it does say there is no picture.
        assertTrue(format(vcodec = "none", acodec = null).isAudioOnly)
        assertFalse(format(vcodec = "avc1", acodec = "mp4a").isAudioOnly)
        assertFalse(format(vcodec = "none", acodec = "none").isAudioOnly)
        assertFalse(format(vcodec = null, acodec = null).isAudioOnly)
    }

    @Test
    fun `a storyboard has neither picture nor sound`() {
        val storyboard = format(ext = "mhtml", vcodec = "none", acodec = "none", width = 48, height = 27)

        assertFalse(storyboard.hasVideo)
        assertFalse(storyboard.hasAudio)
        assertFalse(storyboard.isAudioOnly)
    }

    @Test
    fun `streaming manifests are not direct files`() {
        assertTrue(format(protocol = "https").isDirectFile)
        assertTrue(format(protocol = null).isDirectFile)
        assertFalse(format(protocol = "m3u8_native").isDirectFile)
        assertFalse(format(protocol = "m3u8").isDirectFile)
        assertFalse(format(protocol = "http_dash_segments").isDirectFile)
        assertFalse(format(protocol = "https", url = "https://cdn.example/master.m3u8?token=1").isDirectFile)
        assertFalse(format(protocol = "https", url = "https://cdn.example/manifest.MPD").isDirectFile)
    }

    @Test
    fun `a complete file is a direct file with picture and sound`() {
        assertTrue(format(vcodec = "avc1", acodec = "mp4a").isCompleteFile)
        assertFalse(format(vcodec = "avc1", acodec = "none").isCompleteFile)
        assertFalse(format(vcodec = "avc1", acodec = "mp4a", protocol = "m3u8_native").isCompleteFile)
    }

    @Test
    fun `a loudness-compressed track is recognized by its note or its id`() {
        assertTrue(format(note = "medium, DRC").isLoudnessCompressed)
        assertTrue(format(note = "low, drc").isLoudnessCompressed)
        assertTrue(format(id = "140-drc").isLoudnessCompressed)
        assertFalse(format(id = "140", note = "medium").isLoudnessCompressed)
        assertFalse(format(id = "140", note = null).isLoudnessCompressed)
    }

    @Test
    fun `a picture is standard range unless it says otherwise`() {
        assertTrue(format(dynamicRange = null).isStandardRange)
        assertTrue(format(dynamicRange = "SDR").isStandardRange)
        assertTrue(format(dynamicRange = "sdr").isStandardRange)
        assertFalse(format(dynamicRange = "HDR10").isStandardRange)
        assertFalse(format(dynamicRange = "HLG").isStandardRange)
    }

    @Test
    fun `quality is the shorter side so vertical videos are named like landscape ones`() {
        assertEquals(1080, format(width = 1920, height = 1080).quality)
        assertEquals(1080, format(width = 1080, height = 1920).quality)
        assertEquals(576, format(width = 576, height = 1024).quality)
        assertEquals(720, format(width = null, height = 720).quality)
        assertEquals(720, format(width = 0, height = 720).quality)
    }

    @Test
    fun `quality is unknown without a usable height`() {
        assertNull(format(width = null, height = null).quality)
        assertNull(format(width = 1280, height = null).quality)
        assertNull(format(width = 1280, height = 0).quality)
    }

    private fun format(
        id: String = "f",
        ext: String? = "mp4",
        protocol: String? = "https",
        url: String = "https://cdn.example/video",
        width: Int? = null,
        height: Int? = null,
        vcodec: String? = null,
        acodec: String? = null,
        note: String? = null,
        dynamicRange: String? = null,
    ) = PlatformFormat(
        id = id,
        url = url,
        ext = ext,
        protocol = protocol,
        width = width,
        height = height,
        fps = null,
        videoCodec = vcodec,
        audioCodec = acodec,
        bitrateKbps = null,
        audioBitrateKbps = null,
        sizeBytes = null,
        note = note,
        language = null,
        headers = emptyMap(),
        dynamicRange = dynamicRange,
    )
}
