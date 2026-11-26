package com.chaya.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player's gestures and settings, as their rules: tall videos, double-tap seeks, speeds and the sleep timer. */
class PlayerModesTest {

    @Test
    fun `a video is tall when it shows taller than wide, turned and with its pixels' shape`() {
        assertTrue(isTallVideo(1080, 1920))
        assertFalse(isTallVideo(1920, 1080))
        assertFalse(isTallVideo(1080, 1080))
        // Stored wide but turned a quarter, it shows tall
        assertTrue(isTallVideo(1920, 1080, rotationDegrees = 90))
        // Anamorphic: narrow pixels stretched wide show wide
        assertFalse(isTallVideo(720, 960, pixelWidthHeightRatio = 2f))
        assertFalse("unknown size is not tall", isTallVideo(0, 0))
    }

    @Test
    fun `a double tap on the left goes back and on the right forward`() {
        assertEquals(SeekSide.BACK, SeekSide.of(x = 100f, width = 1000f))
        assertEquals(SeekSide.FORWARD, SeekSide.of(x = 900f, width = 1000f))
        assertEquals(-10_000L, SeekStreak.next(null, SeekSide.BACK, 0).stepMs)
        assertEquals(10_000L, SeekStreak.next(null, SeekSide.FORWARD, 0).stepMs)
    }

    @Test
    fun `double taps on one side add up while they come quickly, and start again otherwise`() {
        val first = SeekStreak.next(null, SeekSide.FORWARD, nowMs = 1_000)
        val second = SeekStreak.next(first, SeekSide.FORWARD, nowMs = 1_500)
        val third = SeekStreak.next(second, SeekSide.FORWARD, nowMs = 2_200)
        assertEquals(listOf(10, 20, 30), listOf(first.seconds, second.seconds, third.seconds))
        // Each tap still moves 10 seconds: the count is what has been added up
        assertEquals(10_000L, third.stepMs)
        assertEquals(10, SeekStreak.next(third, SeekSide.BACK, nowMs = 2_300).seconds)
        assertEquals(10, SeekStreak.next(third, SeekSide.FORWARD, nowMs = 2_200 + SeekStreak.STREAK_MS + 1).seconds)
    }

    @Test
    fun `speeds are offered as YouTube offers them, and named so`() {
        assertEquals(listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f), PLAYBACK_SPEEDS)
        assertEquals("Normal", speedLabel(1f))
        assertEquals("2×", speedLabel(2f))
        assertEquals("1.25×", speedLabel(1.25f))
        assertEquals("0.5×", speedLabel(0.5f))
        assertEquals(2f, HOLD_SPEED)
    }

    @Test
    fun `the sleep timer says how long is left, rounded up to the minute`() {
        val timer = SleepTimer.after(minutes = 15, nowMs = 0)
        assertEquals(15 * 60_000L, timer.atMs)
        assertEquals("15 min left", timer.label(nowMs = 0))
        assertEquals("1 min left", timer.label(nowMs = 15 * 60_000L - 1_000))
        assertEquals("0 min left", timer.label(nowMs = 16 * 60_000L))
        assertEquals("Off", SleepTimer.Off.label(0))
        assertEquals("End of video", SleepTimer.EndOfVideo.label(0))
        assertEquals(listOf(15, 30, 45, 60), SleepTimer.MINUTES)
    }
}
