package com.chaya.app.ui.player

import android.content.pm.ActivityInfo

/**
 * How the player is laid out, from which way the phone is held: [UPRIGHT], the picture over its name, its lines and a
 * bar of controls; or [ACROSS], the picture filling the screen and the controls a slim row over it that steps aside
 * while the video plays. One value, from the screen's shape, never a flag kept beside it.
 */
enum class PlayerLayout {
    UPRIGHT,
    ACROSS;

    /** Whether the controls step aside while the video plays untouched: only over the picture, where they hide it. */
    val controlsStepAside: Boolean get() = this == ACROSS

    /** What the full-screen button turns the phone to: across from upright, upright from across. */
    val turnTo: Int
        get() = when (this) {
            UPRIGHT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ACROSS -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

    companion object {
        fun of(landscape: Boolean): PlayerLayout = if (landscape) ACROSS else UPRIGHT

        /** How long the controls stay over a playing video after the last touch. */
        const val CONTROLS_STAY_MS = 3_000L
    }
}
