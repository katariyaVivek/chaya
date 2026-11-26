package com.chaya.app.ui.player

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLayoutTest {

    @Test
    fun `held across the player fills the screen and its controls step aside, upright they stay`() {
        assertEquals(PlayerLayout.ACROSS, PlayerLayout.of(landscape = true))
        assertEquals(PlayerLayout.UPRIGHT, PlayerLayout.of(landscape = false))
        assertTrue(PlayerLayout.ACROSS.controlsStepAside)
        assertFalse(PlayerLayout.UPRIGHT.controlsStepAside)
    }

    @Test
    fun `a tall video put full screen fills the upright screen, and turning the phone still makes it across`() {
        assertEquals(PlayerLayout.TALL, PlayerLayout.of(landscape = false, tallFullScreen = true))
        assertEquals(PlayerLayout.ACROSS, PlayerLayout.of(landscape = true, tallFullScreen = true))
        assertTrue(PlayerLayout.TALL.fullScreen)
        assertTrue(PlayerLayout.TALL.controlsStepAside)
        assertFalse(PlayerLayout.UPRIGHT.fullScreen)
    }

    @Test
    fun `full screen turns the phone for a wide video and keeps a tall one upright`() {
        assertEquals(PlayerTurn(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, tall = false), PlayerLayout.UPRIGHT.turn(videoIsTall = false))
        assertEquals(PlayerTurn(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, tall = true), PlayerLayout.UPRIGHT.turn(videoIsTall = true))
    }

    @Test
    fun `out of full screen is upright at once, whichever full screen it was`() {
        // Back used to close the player from across, leaving the phone turned for a moment after
        assertEquals(PlayerTurn.EXIT, PlayerLayout.ACROSS.turn(videoIsTall = false))
        assertEquals(PlayerTurn.EXIT, PlayerLayout.TALL.turn(videoIsTall = true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, PlayerTurn.EXIT.orientation)
        assertFalse(PlayerTurn.EXIT.tall)
    }
}
