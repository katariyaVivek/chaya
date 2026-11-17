package com.chaya.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/** The player's address: a video, and where to start in it when a line found by the search says so. */
class ScreenTest {
    @Test
    fun `the player opens where the video was left, or at a time`() {
        assertEquals("player/12", Screen.playerRoute(12))
        assertEquals("player/12?at=5000", Screen.playerRoute(12, 5000))
        assertEquals("player/{taskId}?at={at}", Screen.Player.route)
    }
}
