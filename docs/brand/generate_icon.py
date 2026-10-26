"""Generates Chaya's launcher icon (the original and its shadow-copy) as Android vectors and a master SVG,
and the Windows app's icon (docs/brand/chaya-icon-desktop.svg) from the same measurements.

All numbers are on Android's 108-unit adaptive-icon grid; the mark stays inside the 66-unit safe circle.
Run it from anywhere: python docs/brand/generate_icon.py
"""
import math
import pathlib

root = pathlib.Path(__file__).resolve().parents[2]  # the repository

ORIGINAL = (33.0, 30.0, 32.0, 36.0)   # x, y, width, height: the media tile in front
OFFSET = 10.0                          # the copy sits this far right and down, like a cast shadow
RADIUS = 9.5
PLAY = [(45.0, 41.0), (57.5, 48.0), (45.0, 55.0)]  # optically centred in the original

def rrect(x, y, w, h, r):
    f = lambda v: f"{v:g}"
    return (f"M{f(x + r)},{f(y)} H{f(x + w - r)} A{f(r)},{f(r)} 0 0 1 {f(x + w)},{f(y + r)} "
            f"V{f(y + h - r)} A{f(r)},{f(r)} 0 0 1 {f(x + w - r)},{f(y + h)} H{f(x + r)} "
            f"A{f(r)},{f(r)} 0 0 1 {f(x)},{f(y + h - r)} V{f(y + r)} A{f(r)},{f(r)} 0 0 1 {f(x + r)},{f(y)} Z")

x, y, w, h = ORIGINAL
original = rrect(x, y, w, h, RADIUS)
copy = rrect(x + OFFSET, y + OFFSET, w, h, RADIUS)
play = "M{},{} L{},{} L{},{} Z".format(*[f"{v:g}" for p in PLAY for v in p])

COLORS = dict(bg_hi="#8A73D6", bg_mid="#5E48A6", bg_lo="#2E2154", copy="#2A1D57", rim="#BDAEF8",
              lit_top="#FFFFFF", lit_bottom="#E9E3FF", play="#5E48A6")

HEADER = '<?xml version="1.0" encoding="utf-8"?>\n'
VEC = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
       '    xmlns:aapt="http://schemas.android.com/aapt"\n'
       '    android:width="108dp"\n    android:height="108dp"\n'
       '    android:viewportWidth="108"\n    android:viewportHeight="108">\n')

background = HEADER + """<!--
  Chaya launcher background: the violet field (DESIGN.md primary family), lit from the upper left.
  Generated with the foreground from one set of measurements; see docs/brand/README.md.
-->
""" + VEC + f"""    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="34.56"
                android:centerY="27"
                android:gradientRadius="97.2">
                <item android:offset="0" android:color="{COLORS['bg_hi']}" />
                <item android:offset="0.55" android:color="{COLORS['bg_mid']}" />
                <item android:offset="1" android:color="{COLORS['bg_lo']}" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
"""

foreground = HEADER + """<!--
  Chaya launcher mark: an original (a media tile with a play mark) and its shadow-copy behind it.
  Chaya (छाया) means shadow; in the story, Chhaya is the copy made from Saranyu's shadow. A download
  is a copy of the original, so the copy falls where a shadow would: below and to the right.
-->
""" + VEC + f"""    <!-- The copy: deep shade with a lavender rim, so it reads on its own and not as a smudge. -->
    <path
        android:pathData="{copy}"
        android:fillColor="{COLORS['copy']}"
        android:strokeColor="{COLORS['rim']}"
        android:strokeWidth="1.6" />
    <!-- The original, lit from above. -->
    <path android:pathData="{original}">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="0" android:startY="{y:g}"
                android:endX="0" android:endY="{y + h:g}">
                <item android:offset="0" android:color="{COLORS['lit_top']}" />
                <item android:offset="1" android:color="{COLORS['lit_bottom']}" />
            </gradient>
        </aapt:attr>
    </path>
    <!-- Play mark, its corners softened by a round-joined stroke of the same colour. -->
    <path
        android:pathData="{play}"
        android:fillColor="{COLORS['play']}"
        android:strokeColor="{COLORS['play']}"
        android:strokeWidth="3"
        android:strokeLineJoin="round" />
</vector>
"""

monochrome = HEADER + """<!--
  Themed icon (Android 13+): the same mark in one colour. The copy is half-strength; the play mark is
  cut out of the original (even-odd fill) because a themed icon has no second colour to draw it with.
-->
""" + VEC.replace('    xmlns:aapt="http://schemas.android.com/aapt"\n', '') + f"""    <path
        android:pathData="{copy}"
        android:fillColor="#000000"
        android:fillAlpha="0.45" />
    <path
        android:pathData="{original} {play}"
        android:fillColor="#000000"
        android:fillType="evenOdd" />
</vector>
"""

svg = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">
  <!-- Master artwork for Chaya's launcher icon; the Android vectors in app/src/main/res/drawable are generated from the same measurements. -->
  <defs>
    <radialGradient id="bg" cx="32%" cy="25%" r="90%">
      <stop offset="0" stop-color="{COLORS['bg_hi']}"/><stop offset=".55" stop-color="{COLORS['bg_mid']}"/><stop offset="1" stop-color="{COLORS['bg_lo']}"/>
    </radialGradient>
    <linearGradient id="lit" x1="0" y1="{y:g}" x2="0" y2="{y + h:g}" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="{COLORS['lit_top']}"/><stop offset="1" stop-color="{COLORS['lit_bottom']}"/>
    </linearGradient>
  </defs>
  <rect width="108" height="108" fill="url(#bg)"/>
  <path d="{copy}" fill="{COLORS['copy']}" stroke="{COLORS['rim']}" stroke-width="1.6"/>
  <path d="{original}" fill="url(#lit)"/>
  <path d="{play}" fill="{COLORS['play']}" stroke="{COLORS['play']}" stroke-width="3" stroke-linejoin="round"/>
</svg>
"""

# Chaya for Windows: the same mark on a near-black plate, lit by the desktop's amber (desktop/design).
# The accent is light there, never paint, so it glows behind the original and rims the copy.
DESKTOP_COLORS = dict(bg_hi="#3A2516", bg_mid="#17110D", bg_lo="#0B0B0D", glow="#FF9A3C", copy="#1A1310",
                      rim="#FF9A3C", lit_top="#FFFFFF", lit_bottom="#FFE6CF", play="#D2641A")
PLATE_INSET, PLATE_RADIUS = 4.0, 22.0   # a rounded square, as Windows app icons are
DESKTOP_SCALE = 1.45                    # no launcher mask on Windows, so the mark can fill more of the plate
c = DESKTOP_COLORS
mark_cx, mark_cy = x + (w + OFFSET) / 2, y + (h + OFFSET) / 2
plate = PLATE_INSET, 108 - 2 * PLATE_INSET
desktop_svg = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">
  <!-- Chaya for Windows' icon, generated by generate_icon.py with the launcher icon's measurements. -->
  <defs>
    <radialGradient id="bg" cx="30%" cy="22%" r="95%">
      <stop offset="0" stop-color="{c['bg_hi']}"/><stop offset=".5" stop-color="{c['bg_mid']}"/><stop offset="1" stop-color="{c['bg_lo']}"/>
    </radialGradient>
    <radialGradient id="glow" cx="{x + w / 2:g}" cy="{y + h / 2:g}" r="{w * 0.95:g}" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="{c['glow']}" stop-opacity=".55"/><stop offset="1" stop-color="{c['glow']}" stop-opacity="0"/>
    </radialGradient>
    <linearGradient id="lit" x1="0" y1="{y:g}" x2="0" y2="{y + h:g}" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="{c['lit_top']}"/><stop offset="1" stop-color="{c['lit_bottom']}"/>
    </linearGradient>
  </defs>
  <rect x="{plate[0]:g}" y="{plate[0]:g}" width="{plate[1]:g}" height="{plate[1]:g}" rx="{PLATE_RADIUS:g}" fill="url(#bg)"
        stroke="#FFFFFF" stroke-opacity=".1" stroke-width=".8"/>  <!-- so the plate keeps its edge on a dark taskbar -->
  <g transform="translate(54 54) scale({DESKTOP_SCALE:g}) translate({-mark_cx:g} {-mark_cy:g})">
    <circle cx="{x + w / 2:g}" cy="{y + h / 2:g}" r="{w * 0.95:g}" fill="url(#glow)"/>
    <path d="{copy}" fill="{c['copy']}" stroke="{c['rim']}" stroke-width="1.6"/>
    <path d="{original}" fill="url(#lit)"/>
    <path d="{play}" fill="{c['play']}" stroke="{c['play']}" stroke-width="3" stroke-linejoin="round"/>
  </g>
</svg>
"""

res = root / "app/src/main/res/drawable"
(res / "ic_launcher_background.xml").write_text(background)
(res / "ic_launcher_foreground.xml").write_text(foreground)
(res / "ic_launcher_monochrome.xml").write_text(monochrome)
(root / "docs/brand").mkdir(parents=True, exist_ok=True)
(root / "docs/brand/chaya-icon.svg").write_text(svg)
(root / "docs/brand/chaya-icon-desktop.svg").write_text(desktop_svg)
# Bounds check: every corner of the mark must sit inside the 33-unit safe radius.
corners = [(x, y), (x + OFFSET + w, y + OFFSET + h), (x, y + h), (x + OFFSET + w, y + OFFSET)]
print("max corner distance", round(max(math.hypot(cx - 54, cy - 54) for cx, cy in corners), 1), "(safe < 33)")
