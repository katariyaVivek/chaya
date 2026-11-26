package com.chaya.app.ui.player

/**
 * How the player is laid out: [UPRIGHT], the picture over its name, its lines and a bar of controls; [ACROSS], the
 * phone turned and the picture filling it; or [TALL], a tall video (a Short, a Reel) filling the upright screen. In
 * both full-screen layouts the controls are a slim row over the picture that steps aside while the video plays. One
 * value, from the screen's shape and whether a tall video was put full screen, never a flag kept beside it.
 */
enum class PlayerLayout {
    UPRIGHT,
    ACROSS,
    TALL;

    /** Whether the picture fills the screen. Back leaves full screen rather than the player. */
    val fullScreen: Boolean get() = this != UPRIGHT

    /** Whether the controls step aside while the video plays untouched: only over the picture, where they hide it. */
    val controlsStepAside: Boolean get() = fullScreen

    /** What the full-screen button does here: full screen for the video from upright, upright again from full screen. */
    fun turn(videoIsTall: Boolean): PlayerTurn = if (fullScreen) PlayerTurn.EXIT else PlayerTurn.fullScreen(videoIsTall)

    companion object {
        /** Turned across, the picture fills the screen; upright, it does when a tall video was put full screen. */
        fun of(landscape: Boolean, tallFullScreen: Boolean = false): PlayerLayout = when {
            landscape -> ACROSS
            tallFullScreen -> TALL
            else -> UPRIGHT
        }

        /** How long the controls stay over a playing video after the last touch. */
        const val CONTROLS_STAY_MS = 3_000L
    }
}
