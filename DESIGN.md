# Design System — Chaya

Product register. Soft premium, fluid-assured-soft personality. Strategy: **Restrained** — warm tinted neutrals + one violet-indigo accent ≤10% of surface.

## Color

All colors computed from OKLCH; neutrals tinted warm (hue 80) in light, violet (hue 285) in dark. No pure black/white anywhere.

### Light — "Porcelain"

| Role | OKLCH | Hex |
|---|---|---|
| background | 0.975 0.005 80 | #F8F6F3 |
| surface | 0.992 0.004 80 | #FEFCF9 |
| surfaceContainer | 0.950 0.006 80 | #F1EEEA |
| surfaceContainerHigh | 0.934 0.007 80 | #ECE9E4 |
| onSurface | 0.245 0.014 80 | #242019 |
| onSurfaceVariant | 0.495 0.016 80 | #676158 |
| outlineVariant | 0.878 0.010 80 | #DAD6D0 |
| primary (accent) | 0.520 0.135 293 | #6D57AF |
| primaryContainer | 0.900 0.055 293 | #DFD8FF |
| onPrimaryContainer | 0.330 0.100 293 | #392863 |
| error | 0.555 0.190 27 | #CA322E |
| success (ext) | 0.580 0.125 152 | #338F54 |

### Dark — "Violet Charcoal"

| Role | OKLCH | Hex |
|---|---|---|
| background | 0.168 0.014 285 | #0E0E15 |
| surface | 0.188 0.013 285 | #131319 |
| surfaceContainer | 0.225 0.012 285 | #1B1B21 |
| surfaceContainerHigh | 0.250 0.011 285 | #212127 |
| onSurface | 0.915 0.010 285 | #E2E2E9 |
| onSurfaceVariant | 0.720 0.014 285 | #A3A4AD |
| outlineVariant | 0.330 0.012 285 | #34353B |
| primary (accent) | 0.790 0.105 293 | #BDAEF8 |
| primaryContainer | 0.400 0.115 293 | #4B387F |
| onPrimaryContainer | 0.905 0.045 293 | #E0DBFC |
| error | 0.780 0.130 25 | #FF958D |
| success (ext) | 0.780 0.115 152 | #7CCD93 |

Accent usage: primary actions, active states, progress, selection only. Success green reserved for COMPLETED state dots. Never decorative.

## Typography

System sans (FontFamily.Default), one family. Tight scale ratio ~1.15–1.25, hierarchy via weight (SemiBold titles vs Regular body) and size.

| Style | Spec |
|---|---|
| headlineMedium | 26sp / SemiBold / lh 32 / ls -0.25 |
| titleLarge | 20sp / SemiBold / lh 28 / ls -0.1 |
| titleMedium | 16sp / SemiBold / lh 22 / ls -0.1 |
| titleSmall | 14sp / SemiBold / lh 20 |
| bodyLarge | 15sp / Regular / lh 22 / ls 0.15 |
| bodyMedium | 14sp / Regular / lh 20 |
| bodySmall | 12sp / Regular / lh 16 |
| labelLarge | 13sp / Medium / lh 18 / ls 0.2 |
| labelMedium | 12sp / Medium / lh 16 / ls 0.3 |

## Shape

Rounded, generous but disciplined: sheets 28dp top radius · cards/chips 16dp · tonal icon circles 50% · inputs 24dp pill-ish · buttons 14dp.

## Elevation & surfaces

Flat-first: hairline `outlineVariant` borders or container-tone steps instead of shadows. Max shadow 2dp. Sheets use `surfaceContainerLow`.

## Motion ("butter smooth")

Springs: critical damping (no bounce). Tweens: ease-out-expo/quart.

| Token | Value |
|---|---|
| durationShort | 180ms |
| durationMedium | 300ms |
| durationLong | 450ms |
| easingStandard | cubic-bezier(0.16, 1, 0.3, 1) (out-expo) |
| springSmooth | NoBouncy, stiffness MediumLow (280) |
| pressScale | 0.96 on press, springSmooth |

Patterns: nav = fade-through (fade + slight rise/scale) · lists = staggered fade-rise entrances + animateItem reflow · progress = animated fraction · state labels = AnimatedContent crossfade/slide · FAB/badges = spring scale-in · press feedback = scale + ripple on every tappable row/card/chip.

## Components vocabulary

- **Tonal circle** (56dp, surfaceContainerHigh): leading media-type icon
- **State chip** (pill, 12sp medium): colored dot + label, per download state
- **Pill chips** for quick links/actions: surfaceContainerHigh fill, no border
- **Sheets**: drag handle, 28dp top corners, list rows with trailing icon action
- **Empty states**: soft icon circle + one teaching line, never "nothing here"
