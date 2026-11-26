package com.chaya.app.ui.player

import android.content.pm.ActivityInfo

/**
 * What the full-screen button, and Back from full screen, do: the way the phone is to be held, and whether the picture
 * fills it upright ([PlayerLayout.TALL]).
 */
data class PlayerTurn(val orientation: Int, val tall: Boolean) {
    companion object {
        /** Full screen for the video: a wide one across the phone, a tall one (Shorts, Reels) upright, filling it. */
        fun fullScreen(videoIsTall: Boolean): PlayerTurn =
            if (videoIsTall) PlayerTurn(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, tall = true)
            else PlayerTurn(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, tall = false)

        /** Back to the upright player, at once: the phone is told upright, rather than left to turn when it likes. */
        val EXIT = PlayerTurn(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, tall = false)
    }
}

/** Whether a video is taller than it is wide, as it is shown: turned by its rotation, and with its pixels' own shape. */
fun isTallVideo(width: Int, height: Int, rotationDegrees: Int = 0, pixelWidthHeightRatio: Float = 1f): Boolean {
    if (width <= 0 || height <= 0) return false
    val shownWidth = width * (pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f)
    val turned = rotationDegrees % 180 != 0
    val w = if (turned) height.toFloat() else shownWidth
    val h = if (turned) shownWidth else height.toFloat()
    return h > w
}

/** Which side of the picture a double tap landed on: back on the left half, forward on the right. */
enum class SeekSide {
    BACK,
    FORWARD;

    companion object {
        fun of(x: Float, width: Float): SeekSide = if (x < width / 2f) BACK else FORWARD
    }
}

/**
 * The seek a run of double taps adds up to, as it shows over its side of the picture: "20 seconds" after two. A tap on
 * the same side within [STREAK_MS] of the last adds [STEP_SECONDS]; on the other side, or later, it starts again.
 */
data class SeekStreak(val side: SeekSide, val seconds: Int, val atMs: Long) {
    /** The step this tap moves the video, in milliseconds: back is negative. */
    val stepMs: Long get() = if (side == SeekSide.FORWARD) STEP_SECONDS * 1000L else -STEP_SECONDS * 1000L

    companion object {
        const val STEP_SECONDS = 10

        /** How long after a double tap another on the same side still adds to it, and how long the streak shows. */
        const val STREAK_MS = 900L

        fun next(previous: SeekStreak?, side: SeekSide, nowMs: Long): SeekStreak {
            val carried = previous?.takeIf { it.side == side && nowMs - it.atMs <= STREAK_MS }?.seconds ?: 0
            return SeekStreak(side, carried + STEP_SECONDS, nowMs)
        }
    }
}

/** The speeds the settings offer, as YouTube's do. */
val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** The speed while the picture is held. */
const val HOLD_SPEED = 2f

/** "Normal" for 1×, else "1.25×". */
fun speedLabel(speed: Float): String {
    if (speed == 1f) return "Normal"
    val text = if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString().trimEnd('0')
    return "$text×"
}

/** When the sleep timer stops the video: never, after some minutes, or at the end of this video. */
sealed class SleepTimer {
    data object Off : SleepTimer()

    /** Stops at [atMs] on the phone's clock since boot ([android.os.SystemClock.elapsedRealtime]). */
    data class At(val atMs: Long, val minutes: Int) : SleepTimer()

    /** Stops when the video ends, even with Loop on. */
    data object EndOfVideo : SleepTimer()

    /** What the settings row says: "Off", "12 min left", "End of video". */
    fun label(nowMs: Long): String = when (this) {
        Off -> "Off"
        EndOfVideo -> "End of video"
        is At -> "${((atMs - nowMs).coerceAtLeast(0) + 59_999) / 60_000} min left"
    }

    companion object {
        /** The choices offered, in minutes. */
        val MINUTES = listOf(15, 30, 45, 60)

        fun after(minutes: Int, nowMs: Long): At = At(nowMs + minutes * 60_000L, minutes)
    }
}
