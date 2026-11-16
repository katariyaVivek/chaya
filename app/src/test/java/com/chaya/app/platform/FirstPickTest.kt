package com.chaya.app.platform

import org.junit.Assert.assertEquals
import org.junit.Test

/** The quality picked first when a video is found: the best at most 1080p, not the biggest file. */
class FirstPickTest {

    private val file = PlatformFormat(
        id = "f", url = "https://cdn.example/f", ext = "mp4", protocol = "https", width = null, height = null, fps = null,
        videoCodec = "avc1", audioCodec = "mp4a", bitrateKbps = null, audioBitrateKbps = null, sizeBytes = null,
        note = null, language = null, headers = emptyMap(),
    )

    private fun choice(quality: Int?, audioOnly: Boolean = false) = PlatformChoice(
        label = if (audioOnly) "Audio only" else quality?.let { "${it}p" } ?: "Video",
        detail = "",
        quality = if (audioOnly) null else quality,
        file = file,
        audioToMerge = null,
        isAudioOnly = audioOnly,
    )

    @Test
    fun `the best at most 1080p is picked, as Stage shows it, not 4K`() {
        assertEquals(2, firstPick(listOf(choice(2160), choice(1440), choice(1080), choice(720), choice(null, audioOnly = true))))
        assertEquals(0, firstPick(listOf(choice(720), choice(480), choice(null, audioOnly = true))))
    }

    @Test
    fun `with only bigger pictures, or none with a height, the first picture`() {
        assertEquals(0, firstPick(listOf(choice(2160), choice(null, audioOnly = true))))
        assertEquals(1, firstPick(listOf(choice(null, audioOnly = true), choice(null))))
    }

    @Test
    fun `sound alone when that is all there is, and nothing with nothing`() {
        assertEquals(0, firstPick(listOf(choice(null, audioOnly = true))))
        assertEquals(-1, firstPick(emptyList()))
    }
}
