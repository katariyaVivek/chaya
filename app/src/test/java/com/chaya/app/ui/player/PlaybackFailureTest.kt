package com.chaya.app.ui.player

import androidx.media3.common.PlaybackException
import com.chaya.app.ui.player.PlaybackFailure.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the player says when a saved video will not play, instead of the same sentence for every cause. */
class PlaybackFailureTest {

    private val portrait = Track("video/avc", 1080, 1920)

    @Test
    fun `a decoder that will not take the picture is named with the picture's codec and size`() {
        assertEquals(
            "This phone can't decode this video (H.264, 1080×1920).",
            PlaybackFailure.sentence(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, portrait),
        )
        assertEquals(
            "This phone can't decode this video (AV1).",
            PlaybackFailure.sentence(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, Track("video/av01", null, null)),
        )
        assertEquals(
            "This phone can't decode this video.",
            PlaybackFailure.sentence(PlaybackException.ERROR_CODE_DECODING_FAILED, Track(null, null, null)),
        )
    }

    @Test
    fun `a damaged file and a missing one are told apart from a decoder`() {
        assertEquals(
            "This file looks damaged or incomplete.",
            PlaybackFailure.sentence(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, portrait),
        )
        assertEquals(
            "The file is no longer where Chaya saved it.",
            PlaybackFailure.sentence(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, Track(null, null, null)),
        )
        assertEquals("Chaya can't play this file.", PlaybackFailure.sentence(PlaybackException.ERROR_CODE_UNSPECIFIED, portrait))
    }

    @Test
    fun `Diagnostics gets the player's name for the error and the track, never an address`() {
        assertEquals(
            "ERROR_CODE_DECODER_INIT_FAILED · video/hevc · 1920×1080",
            PlaybackFailure.diagnostic("ERROR_CODE_DECODER_INIT_FAILED", Track("video/hevc", 1920, 1080)),
        )
        assertEquals("ERROR_CODE_IO_FILE_NOT_FOUND", PlaybackFailure.diagnostic("ERROR_CODE_IO_FILE_NOT_FOUND", Track(null, -1, -1)))
    }

    @Test
    fun `codecs are named as people know them`() {
        assertEquals("H.265", PlaybackFailure.codecName("video/hevc"))
        assertEquals("VP9", PlaybackFailure.codecName("video/x-vnd.on2.vp9"))
        assertEquals("x-unknown", PlaybackFailure.codecName("video/x-unknown"))
    }
}
