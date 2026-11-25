package com.chaya.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a saved video plays from, and which one Home offers to carry on with (as Chaya for Windows does). */
class WatchedTest {

    @Test
    fun `a video plays from where it was left`() {
        assertEquals(252_000L, Watched(atMs = 252_000, lengthMs = 760_000, stoppedAt = 1).resumeAtMs)
        assertTrue(Watched(252_000, 760_000, 1).partWay)
    }

    @Test
    fun `left in its first seconds, or watched to the end, it starts again from the start`() {
        assertEquals(0L, Watched(4_000, 760_000, 1).resumeAtMs)
        assertEquals(0L, Watched(755_000, 760_000, 1).resumeAtMs)
        assertFalse(Watched(755_000, 760_000, 1).partWay)
        // Its length not known yet: where it was left still counts
        assertEquals(30_000L, Watched(30_000, 0, 1).resumeAtMs)
    }

    @Test
    fun `Home carries on with the saved video stopped part way most recently`() {
        val watched = mapOf(
            1L to Watched(60_000, 600_000, stoppedAt = 100),
            2L to Watched(90_000, 600_000, stoppedAt = 300),
            3L to Watched(595_000, 600_000, stoppedAt = 900), // watched to the end
            4L to Watched(120_000, 600_000, stoppedAt = 950), // no longer saved
        )

        assertEquals(2L, Watched.toContinue(saved = listOf(1L, 2L, 3L), watched = watched))
        assertNull(Watched.toContinue(saved = listOf(3L), watched = watched))
        assertNull(Watched.toContinue(saved = emptyList(), watched = watched))
    }

    @Test
    fun `times read as a player shows them`() {
        assertEquals("4:12", clockOf(252_000))
        assertEquals("0:00", clockOf(-5))
        assertEquals("1:02:05", clockOf(3_725_000))
    }
}
