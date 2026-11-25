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
    fun `the full-screen button turns the phone the other way`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, PlayerLayout.UPRIGHT.turnTo)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, PlayerLayout.ACROSS.turnTo)
    }
}
