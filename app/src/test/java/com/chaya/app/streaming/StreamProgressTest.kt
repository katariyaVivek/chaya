package com.chaya.app.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A stream download shows a total once the share of its pieces done allows a steady guess. */
class StreamProgressTest {

    @Test
    fun `a known length is used as it is`() {
        assertEquals(5_000L, StreamProgress.totalBytes(5_000, 1_000, 20f))
    }

    @Test
    fun `an unknown length is estimated from the share of pieces done`() {
        // 44.5 MB with a fifth of the pieces in: about 222 MB in all.
        assertEquals(222_500_000L, StreamProgress.totalBytes(-1, 44_500_000, 20f))
    }

    @Test
    fun `too little done, or nothing reported, gives no total`() {
        assertNull(StreamProgress.totalBytes(-1, 300_000, 0.5f))
        assertNull(StreamProgress.totalBytes(-1, 300_000, -1f))
        assertNull(StreamProgress.totalBytes(-1, 0, 50f))
    }

    @Test
    fun `the estimate never falls below what is already in`() {
        assertEquals(1_000L, StreamProgress.totalBytes(-1, 1_000, 100f))
    }
}
