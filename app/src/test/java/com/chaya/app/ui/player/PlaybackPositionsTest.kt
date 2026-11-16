package com.chaya.app.ui.player

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackPositionsTest {

    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("playback-positions-test", Context.MODE_PRIVATE)

    @Test
    fun `where a video was left is there after a restart`() {
        PlaybackPositions(prefs()).put(7, Watched(252_000, 760_000, 1_000))

        assertEquals(mapOf(7L to Watched(252_000, 760_000, 1_000)), PlaybackPositions(prefs()).watched.value)
    }

    @Test
    fun `a deleted video's place goes with it`() {
        val positions = PlaybackPositions(prefs())
        positions.put(7, Watched(252_000, 760_000, 1_000))
        positions.forget(7)

        assertTrue(positions.watched.value.isEmpty())
        assertTrue(PlaybackPositions(prefs()).watched.value.isEmpty())
    }

    @Test
    fun `a stray value is skipped, not a crash`() {
        prefs().edit().putString("not-an-id", "1,2,3").putString("8", "garbage").commit()

        assertTrue(PlaybackPositions(prefs()).watched.value.isEmpty())
    }
}
