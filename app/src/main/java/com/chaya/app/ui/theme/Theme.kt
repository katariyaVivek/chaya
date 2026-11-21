package com.chaya.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Shapes

/**
 * Colors outside the M3 scheme: success states, the player's backdrop, and Stage's amber as a signal (progress, a page
 * loading), the same in both themes and never text.
 */
data class ChayaExtendedColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val playerBackdrop: Color,
    val signal: Color = StageAmber
)

val LocalChayaColors = staticCompositionLocalOf {
    ChayaExtendedColors(
        success = SuccessLight,
        successContainer = SuccessContainerLight,
        onSuccessContainer = OnSuccessContainerLight,
        playerBackdrop = PlayerBackdrop
    )
}

// Stage: `primary` is ink, the one strong control (Save, Play), as Chaya for Windows has it; the amber is a signal
// (LocalChayaColors.signal), and the tertiary family carries it where an M3 role needs a colour (an audio file's icon).
internal val LightColorScheme = lightColorScheme(
    primary = DayInk,
    onPrimary = DayGround,
    primaryContainer = DayInkContainer,
    onPrimaryContainer = DayInk,
    secondary = DayMuted,
    onSecondary = DayGround,
    secondaryContainer = DayContainerHigh,
    onSecondaryContainer = DayInk,
    tertiary = DayAmberDeep,
    onTertiary = OnDayAmberDeep,
    tertiaryContainer = DayAmberContainer,
    onTertiaryContainer = OnDayAmberContainer,
    background = DayGround,
    onBackground = DayInk,
    surface = DaySurface,
    onSurface = DayInk,
    surfaceVariant = DayContainerHigh,
    onSurfaceVariant = DayMuted,
    outline = DayOutline,
    outlineVariant = DayOutlineVariant,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    inverseSurface = DayInverseSurface,
    inverseOnSurface = DayInverseOnSurface,
    inversePrimary = StageAmber,
    surfaceDim = DaySurfaceDim,
    surfaceBright = DaySurface,
    surfaceContainerLowest = DayContainerLowest,
    surfaceContainerLow = DayContainerLow,
    surfaceContainer = DayContainer,
    surfaceContainerHigh = DayContainerHigh,
    surfaceContainerHighest = DayContainerHighest,
    scrim = DayScrim
)

internal val DarkColorScheme = darkColorScheme(
    primary = NightInk,
    onPrimary = NightGround,
    primaryContainer = NightInkContainer,
    onPrimaryContainer = NightInk,
    secondary = NightMuted,
    onSecondary = NightGround,
    secondaryContainer = NightContainerHigh,
    onSecondaryContainer = NightInk,
    tertiary = StageAmber,
    onTertiary = OnNightAmber,
    tertiaryContainer = NightAmberContainer,
    onTertiaryContainer = OnNightAmberContainer,
    background = NightGround,
    onBackground = NightInk,
    surface = NightSurface,
    onSurface = NightInk,
    surfaceVariant = NightContainerHigh,
    onSurfaceVariant = NightMuted,
    outline = NightOutline,
    outlineVariant = NightOutlineVariant,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    inverseSurface = NightInk,
    inverseOnSurface = NightContainer,
    inversePrimary = StageAmberShadow,
    surfaceDim = NightContainerLowest,
    surfaceBright = NightSurfaceBright,
    surfaceContainerLowest = NightContainerLowest,
    surfaceContainerLow = NightContainerLow,
    surfaceContainer = NightContainer,
    surfaceContainerHigh = NightContainerHigh,
    surfaceContainerHighest = NightContainerHighest,
    scrim = NightScrim
)

/**
 * Brand shape vocabulary, the radius scale in DESIGN.md (Shape): 28 sheets and floating bars, 24 search fields and tab
 * cards, 18 pictures inside a card, 16 cards and chips, 14 cards and fields in the player, 12 small chips, 10 badges,
 * 6 the tab count square, and a pill (CircleShape) for every button and anything rounded to half its height.
 * `ShapeScaleTest` holds the sources to it.
 */
private val ChayaShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ChayaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Deliberately NOT using Material You dynamic color: the brand palette is
    // the product. See DESIGN.md.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val extended = if (darkTheme) {
        ChayaExtendedColors(
            success = SuccessDark,
            successContainer = SuccessContainerDark,
            onSuccessContainer = OnSuccessContainerDark,
            playerBackdrop = PlayerBackdrop
        )
    } else {
        ChayaExtendedColors(
            success = SuccessLight,
            successContainer = SuccessContainerLight,
            onSuccessContainer = OnSuccessContainerLight,
            playerBackdrop = PlayerBackdrop
        )
    }

    CompositionLocalProvider(LocalChayaColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = ChayaShapes,
            content = content
        )
    }
}
