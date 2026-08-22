# Product

## Register

product

## Users

Mobile-first Android users who browse sites with embedded or direct media and want to save videos, audio, and streams to their device. They are casual-to-power users: they paste URLs, browse pages, tap a download button, and check progress later. Context of use: phone, one hand, often on-the-go, sometimes backgrounding the app mid-download.

## Product Purpose

Chaya is a WebView browser with smart media detection and a robust download manager. It detects media flowing through pages (network-level and DOM-level), lists it, and downloads direct files (OkHttp) and HLS/DASH streams (Media3) with full pause/resume/retry support. Success: detect → tap → downloaded, without friction or confusion about where files went.

## Brand Personality

"Chaya" means light/shadow and speed (⚡). Three words: **fluid, assured, soft**. The interface should feel like a premium native tool: calm surfaces, confident motion, zero clutter. Emotions: quiet competence, delight in small interactions, trust that downloads just work.

## Anti-references

- Generic downloader apps: harsh blue/green accents, pure black/white, cramped lists, jarring instant state changes
- AI-slop patterns: glassmorphism gimmicks, gradient text, side-stripe borders, identical card grids
- Overstimulating UI: bounce animations, elastic overshoot, loud colors

## Design Principles

1. **Calm surfaces, alive details** — neutral tinted backgrounds let media content lead; motion lives in transitions and micro-interactions, not decoration
2. **State is always visible** — every download's state is legible at a glance; nothing pops in or out without explanation
3. **One-hand reach** — primary actions live at thumb height; bottom sheets over dialogs, always
4. **Motion explains, never performs** — springs and ease-outs that show spatial relationships; no bounce, no elastic
5. **Soft is not weak** — muted palettes with precise weight-contrast typography still read as premium

## Accessibility & Inclusion

- WCAG AA contrast for all text-on-surface pairs
- Touch targets ≥ 48dp
- Respect system font scaling (sp units throughout)
- Reduced-motion friendly: animations are short transitions, not essential signals; state is also conveyed through text/icons
- Dark/light parity: both themes get equal craft
