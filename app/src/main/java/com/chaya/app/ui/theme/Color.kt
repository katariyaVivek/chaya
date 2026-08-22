package com.chaya.app.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Chaya palette — computed from OKLCH (see DESIGN.md).
// Light "Porcelain": warm-tinted neutrals (hue 80) + violet-indigo accent.
// Dark "Violet Charcoal": violet-tinted neutrals (hue 285), lifted accent.
// No pure black/white anywhere.
// ---------------------------------------------------------------------------

// Light scheme
val PorcelainBackground = Color(0xFFF8F6F3)
val PorcelainSurface = Color(0xFFFEFCF9)
val PorcelainContainerLowest = Color(0xFFFBF9F7)
val PorcelainContainerLow = Color(0xFFF5F3F0)
val PorcelainContainer = Color(0xFFF1EEEA)
val PorcelainContainerHigh = Color(0xffece9e4)
val PorcelainContainerHighest = Color(0xFFE7E3DE)
val PorcelainOnSurface = Color(0xFF242019)
val PorcelainOnSurfaceVariant = Color(0xFF676158)
val PorcelainOutline = Color(0xFF857F77)
val PorcelainOutlineVariant = Color(0xFFDAD6D0)
val VioletPrimaryLight = Color(0xFF6D57AF)
val OnVioletPrimaryLight = Color(0xFFFAF9FD)
val VioletContainerLight = Color(0xFFDFD8FF)
val OnVioletContainerLight = Color(0xFF392863)
val SlateSecondaryLight = Color(0xFF6B6E8A)
val OnSlateSecondaryLight = Color(0xFFF9FAFD)
val SlateContainerLight = Color(0xFFDADAED)
val OnSlateContainerLight = Color(0xFF353451)
val BronzeTertiaryLight = Color(0xFFB6753B)
val OnBronzeTertiaryLight = Color(0xFFFDF9F7)
val BronzeContainerLight = Color(0xFFF8DAB9)
val OnBronzeContainerLight = Color(0xFF5E2F00)
val ErrorLight = Color(0xFFCA322E)
val OnErrorLight = Color(0xFFFFF8F7)
val ErrorContainerLight = Color(0xFFFFD1CA)
val OnErrorContainerLight = Color(0xFF720309)
val InverseSurfaceLight = Color(0xFF37322B)
val InverseOnSurfaceLight = Color(0xFFF4F1ED)
val SurfaceDimLight = Color(0xFFE2DFDA)
val SurfaceBrightLight = Color(0xFFFBFAF7)
val ScrimLight = Color(0xFF050509)

// Dark scheme
val CharcoalBackground = Color(0xFF0E0E15)
val CharcoalSurface = Color(0xFF131319)
val CharcoalContainerLowest = Color(0xFF09090F)
val CharcoalContainerLow = Color(0xFF16161D)
val CharcoalContainer = Color(0xFF1B1B21)
val CharcoalContainerHigh = Color(0xFF212127)
val CharcoalContainerHighest = Color(0xFF28282E)
val CharcoalOnSurface = Color(0xFFE2E2E9)
val CharcoalOnSurfaceVariant = Color(0xFFA3A4AD)
val CharcoalOutline = Color(0xFF797983)
val CharcoalOutlineVariant = Color(0xFF34353B)
val VioletPrimaryDark = Color(0xFFBDAEF8)
val OnVioletPrimaryDark = Color(0xFF291A4D)
val VioletContainerDark = Color(0xFF4B387F)
val OnVioletContainerDark = Color(0xFFE0DBFC)
val SlateSecondaryDark = Color(0xFFB3B6CB)
val OnSlateSecondaryDark = Color(0xFF28293D)
val SlateContainerDark = Color(0xFF3B3B4D)
val OnSlateContainerDark = Color(0xFFDCDDEA)
val GoldTertiaryDark = Color(0xFFE5B379)
val OnGoldTertiaryDark = Color(0xFF412400)
val GoldContainerDark = Color(0xFF6F4606)
val OnGoldContainerDark = Color(0xFFF9E0C5)
val ErrorDark = Color(0xFFFF958D)
val OnErrorDark = Color(0xFF590007)
val ErrorContainerDark = Color(0xFF8D1A1E)
val OnErrorContainerDark = Color(0xFFFFD8D4)
val InverseSurfaceDark = Color(0xFFE2E2E9)
val InverseOnSurfaceDark = Color(0xFF1E1F25)
val SurfaceDimDark = Color(0xFF0A0A10)
val SurfaceBrightDark = Color(0xFF1E1F25)
val ScrimDark = Color(0xFF020203)

// Extended semantic colors (outside Material 3 scheme)
val SuccessLight = Color(0xFF338F54)
val SuccessContainerLight = Color(0xFFC4E9CC)
val OnSuccessContainerLight = Color(0xFF023B1B)
val SuccessDark = Color(0xFF7CCD93)
val SuccessContainerDark = Color(0xFF1A4E2C)
val OnSuccessContainerDark = Color(0xFFC9E7D0)

/** Player screen is always near-black regardless of theme. */
val PlayerBackdrop = Color(0xFF08080F)

// Legacy aliases kept for existing references
val Blue80 = VioletPrimaryDark
val Blue40 = VioletPrimaryLight
val BlueGrey80 = SlateSecondaryDark
val BlueGrey40 = SlateSecondaryLight
val Teal80 = GoldTertiaryDark
val Teal40 = BronzeTertiaryLight
