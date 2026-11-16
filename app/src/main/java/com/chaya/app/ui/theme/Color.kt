package com.chaya.app.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Stage, as Chaya for Windows has it (desktop/design/tokens.css) and the approved phone designs draw it.
// Saved media is the light; the interface is warm neutrals, and its one strong control is ink (near-black by day,
// warm white by night), never a colour. Stage's amber, the mark's own light, is a signal only: progress, a page
// loading, the mark. It is never text on a light ground (1.7:1 there). See DESIGN.md.
// ---------------------------------------------------------------------------

// Day
val DayGround = Color(0xFFF6F4F0)
val DaySurface = Color(0xFFFCFBF8)
val DayContainerLowest = Color(0xFFFEFDFB)
val DayContainerLow = Color(0xFFF9F7F4)
val DayContainer = Color(0xFFF0EEE9)
val DayContainerHigh = Color(0xFFEAE7E2)
val DayContainerHighest = Color(0xFFE3E0DA)
val DayInk = Color(0xFF1A1917)
val DayMuted = Color(0xFF6B6862)
val DayOutline = Color(0xFF8C8983)
val DayOutlineVariant = Color(0xFFDEDAD3)
val DayInkContainer = Color(0xFFE7E4DE)
val DayAmberDeep = Color(0xFF8F5B1E)
val OnDayAmberDeep = Color(0xFFFFF9F0)
val DayAmberContainer = Color(0xFFF6E3C4)
val OnDayAmberContainer = Color(0xFF4A2E08)
val ErrorLight = Color(0xFFCA322E)
val OnErrorLight = Color(0xFFFFF8F7)
val ErrorContainerLight = Color(0xFFFFD1CA)
val OnErrorContainerLight = Color(0xFF720309)
val DayInverseSurface = Color(0xFF2A2926)
val DayInverseOnSurface = Color(0xFFF2F0EC)
val DaySurfaceDim = Color(0xFFE2DFD9)
val DayScrim = Color(0xFF08080A)

// Night
val NightGround = Color(0xFF0F0E11)
val NightSurface = Color(0xFF141316)
val NightContainerLowest = Color(0xFF0A090C)
val NightContainerLow = Color(0xFF17161A)
val NightContainer = Color(0xFF1E1D21)
val NightContainerHigh = Color(0xFF262529)
val NightContainerHighest = Color(0xFF2E2D31)
val NightInk = Color(0xFFF5F3F0)
val NightMuted = Color(0xFFB5B3B2)
val NightOutline = Color(0xFF7E7C80)
val NightOutlineVariant = Color(0xFF343337)
val NightInkContainer = Color(0xFF2C2B2F)
val NightAmberContainer = Color(0xFF4A3416)
val OnNightAmber = Color(0xFF2A1A05)
val OnNightAmberContainer = Color(0xFFF9E3C2)
val ErrorDark = Color(0xFFFF958D)
val OnErrorDark = Color(0xFF590007)
val ErrorContainerDark = Color(0xFF8D1A1E)
val OnErrorContainerDark = Color(0xFFFFD8D4)
val NightSurfaceBright = Color(0xFF2A292D)
val NightScrim = Color(0xFF020203)

/** Stage's amber, the mark's light (`--mark-light` on Windows): the same in both themes, a signal only. */
val StageAmber = Color(0xFFF2B25A)

/** The mark's shadow-twin (`--mark-shadow`), always the darker of the two suns. */
val StageAmberShadow = Color(0xFFB9792F)

// Extended semantic colors (outside Material 3 scheme)
val SuccessLight = Color(0xFF338F54)
val SuccessContainerLight = Color(0xFFC4E9CC)
val OnSuccessContainerLight = Color(0xFF023B1B)
val SuccessDark = Color(0xFF7CCD93)
val SuccessContainerDark = Color(0xFF1A4E2C)
val OnSuccessContainerDark = Color(0xFFC9E7D0)

/** Behind a playing video, whichever theme: the picture's own frame. */
val PlayerBackdrop = Color(0xFF050506)
