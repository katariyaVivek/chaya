package com.chaya.app.ui.theme

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThemeSettingsTest {

    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("theme-settings-test", Context.MODE_PRIVATE)

    @Test
    fun `the phone's setting is followed until the person chooses`() {
        assertEquals(ThemeMode.SYSTEM, ThemeSettings(prefs()).mode.value)
    }

    @Test
    fun `a choice applies at once and is still there after a restart`() {
        val settings = ThemeSettings(prefs())

        settings.set(ThemeMode.LIGHT)

        assertEquals(ThemeMode.LIGHT, settings.mode.value)
        assertEquals(ThemeMode.LIGHT, ThemeSettings(prefs()).mode.value)
    }

    @Test
    fun `an unknown stored value falls back to the phone's setting`() {
        prefs().edit().putString(ThemeSettings.KEY, "SEPIA").commit()

        assertEquals(ThemeMode.SYSTEM, ThemeSettings(prefs()).mode.value)
    }

    @Test
    fun `light and dark ignore the phone, same-as-phone follows it`() {
        assertFalse(ThemeMode.LIGHT.isDark(systemIsDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemIsDark = false))
        assertTrue(ThemeMode.SYSTEM.isDark(systemIsDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemIsDark = false))
    }
}
