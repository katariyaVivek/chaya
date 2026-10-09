package com.chaya.app.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which pictures the muxer is trusted with. The joining itself needs a real media framework and is
 * checked on a device; what can be decided without one is which codecs are offered at all.
 */
class Mp4MergerTest {

    @Test
    fun `H264 pictures can be joined`() {
        assertTrue(Mp4Merger.canJoinPicture("avc1.640028"))
        assertTrue(Mp4Merger.canJoinPicture("avc1.4D400C"))
        assertTrue(Mp4Merger.canJoinPicture("avc3.640028"))
        assertTrue(Mp4Merger.canJoinPicture("AVC1.640028"))
    }

    @Test
    fun `other codecs are not offered until their muxing is verified`() {
        assertFalse(Mp4Merger.canJoinPicture("av01.0.08M.08"))
        assertFalse(Mp4Merger.canJoinPicture("vp09.00.40.08"))
        assertFalse(Mp4Merger.canJoinPicture("vp9"))
        assertFalse(Mp4Merger.canJoinPicture("hvc1.1.6.L93.B0"))
        assertFalse(Mp4Merger.canJoinPicture("hev1.1.6.L93.B0"))
    }

    @Test
    fun `an unknown codec is not joined`() {
        assertFalse(Mp4Merger.canJoinPicture(null))
        assertFalse(Mp4Merger.canJoinPicture(""))
        assertFalse(Mp4Merger.canJoinPicture("none"))
    }
}
