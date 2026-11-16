package com.chaya.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Stage's palette on the phone (Color.kt, Theme.kt): every text colour readable on the grounds it is set on (WCAG AA,
 * 4.5:1), the one strong control in ink, and Stage's amber kept as a signal, the same as Chaya for Windows has it.
 */
class StagePaletteTest {

    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun assertReadable(what: String, text: Color, ground: Color, at: Double = 4.5) {
        val ratio = contrast(text, ground)
        assertTrue("$what: %.2f:1, wanted %.1f:1".format(ratio, at), ratio >= at)
    }

    private fun assertTextReadable(name: String, s: ColorScheme) {
        for ((groundName, ground) in listOf(
            "background" to s.background,
            "surface" to s.surface,
            "surfaceContainer" to s.surfaceContainer,
            "surfaceContainerHigh" to s.surfaceContainerHigh,
        )) {
            assertReadable("$name onSurface on $groundName", s.onSurface, ground, at = 7.0)
            assertReadable("$name onSurfaceVariant on $groundName", s.onSurfaceVariant, ground)
        }
        assertReadable("$name onPrimary on primary", s.onPrimary, s.primary, at = 7.0)
        assertReadable("$name onPrimaryContainer on primaryContainer", s.onPrimaryContainer, s.primaryContainer)
        assertReadable("$name onSecondaryContainer", s.onSecondaryContainer, s.secondaryContainer)
        assertReadable("$name onTertiary on tertiary", s.onTertiary, s.tertiary)
        assertReadable("$name onTertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer)
        assertReadable("$name onError on error", s.onError, s.error)
        // An audio file's icon is drawn in tertiary on the ground
        assertReadable("$name tertiary on background", s.tertiary, s.background, at = 3.0)
    }

    @Test
    fun `Day and Night keep every text colour readable on the grounds it is set on`() {
        assertTextReadable("Day", LightColorScheme)
        assertTextReadable("Night", DarkColorScheme)
    }

    @Test
    fun `the strong control is ink, the text colour itself, not a hue`() {
        assertEquals(LightColorScheme.onSurface, LightColorScheme.primary)
        assertEquals(DarkColorScheme.onSurface, DarkColorScheme.primary)
        assertEquals(LightColorScheme.background, LightColorScheme.onPrimary)
        assertEquals(DarkColorScheme.background, DarkColorScheme.onPrimary)
    }

    @Test
    fun `Stage's amber is the mark's light, the same in both themes, and too pale to be text by day`() {
        assertEquals(Color(0xFFF2B25A), StageAmber)
        assertEquals(Color(0xFFB9792F), StageAmberShadow)
        // The twin is always the darker sun, as a shadow is
        assertTrue(luminance(StageAmberShadow) < luminance(StageAmber))
        // Why it is a signal only: on Day's ground it would fail as text
        assertTrue(contrast(StageAmber, LightColorScheme.background) < 3.0)
        assertEquals(StageAmber, ChayaExtendedColors(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified).signal)
    }
}
