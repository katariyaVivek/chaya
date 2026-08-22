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

/** Semantic colors outside the M3 scheme (success states, player backdrop). */
data class ChayaExtendedColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val playerBackdrop: Color
)

val LocalChayaColors = staticCompositionLocalOf {
    ChayaExtendedColors(
        success = SuccessLight,
        successContainer = SuccessContainerLight,
        onSuccessContainer = OnSuccessContainerLight,
        playerBackdrop = PlayerBackdrop
    )
}

private val LightColorScheme = lightColorScheme(
    primary = VioletPrimaryLight,
    onPrimary = OnVioletPrimaryLight,
    primaryContainer = VioletContainerLight,
    onPrimaryContainer = OnVioletContainerLight,
    secondary = SlateSecondaryLight,
    onSecondary = OnSlateSecondaryLight,
    secondaryContainer = SlateContainerLight,
    onSecondaryContainer = OnSlateContainerLight,
    tertiary = BronzeTertiaryLight,
    onTertiary = OnBronzeTertiaryLight,
    tertiaryContainer = BronzeContainerLight,
    onTertiaryContainer = OnBronzeContainerLight,
    background = PorcelainBackground,
    onBackground = PorcelainOnSurface,
    surface = PorcelainSurface,
    onSurface = PorcelainOnSurface,
    surfaceVariant = PorcelainContainerHigh,
    onSurfaceVariant = PorcelainOnSurfaceVariant,
    outline = PorcelainOutline,
    outlineVariant = PorcelainOutlineVariant,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = InverseOnSurfaceLight,
    surfaceDim = SurfaceDimLight,
    surfaceBright = SurfaceBrightLight,
    surfaceContainerLowest = PorcelainContainerLowest,
    surfaceContainerLow = PorcelainContainerLow,
    surfaceContainer = PorcelainContainer,
    surfaceContainerHigh = PorcelainContainerHigh,
    surfaceContainerHighest = PorcelainContainerHighest,
    scrim = ScrimLight
)

private val DarkColorScheme = darkColorScheme(
    primary = VioletPrimaryDark,
    onPrimary = OnVioletPrimaryDark,
    primaryContainer = VioletContainerDark,
    onPrimaryContainer = OnVioletContainerDark,
    secondary = SlateSecondaryDark,
    onSecondary = OnSlateSecondaryDark,
    secondaryContainer = SlateContainerDark,
    onSecondaryContainer = OnSlateContainerDark,
    tertiary = GoldTertiaryDark,
    onTertiary = OnGoldTertiaryDark,
    tertiaryContainer = GoldContainerDark,
    onTertiaryContainer = OnGoldContainerDark,
    background = CharcoalBackground,
    onBackground = CharcoalOnSurface,
    surface = CharcoalSurface,
    onSurface = CharcoalOnSurface,
    surfaceVariant = CharcoalContainerHigh,
    onSurfaceVariant = CharcoalOnSurfaceVariant,
    outline = CharcoalOutline,
    outlineVariant = CharcoalOutlineVariant,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    inverseSurface = InverseSurfaceDark,
    inverseOnSurface = InverseOnSurfaceDark,
    surfaceDim = SurfaceDimDark,
    surfaceBright = SurfaceBrightDark,
    surfaceContainerLowest = CharcoalContainerLowest,
    surfaceContainerLow = CharcoalContainerLow,
    surfaceContainer = CharcoalContainer,
    surfaceContainerHigh = CharcoalContainerHigh,
    surfaceContainerHighest = CharcoalContainerHighest,
    scrim = ScrimDark
)

/** Brand shape vocabulary: generous but disciplined rounding. */
private val ChayaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
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
