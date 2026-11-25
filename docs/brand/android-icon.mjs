// Writes the phone's launcher icon (app/src/main/res/drawable/ic_launcher_*.xml) from Chaya's mark, the two suns at
// rest, as Chaya for Windows draws them (desktop/design/chaya-mark.js): one source, its named constants.
// Run it after changing the mark: node docs/brand/android-icon.mjs
//
// An Android vector has no masks, which the mark cuts its gaps with. So each sun is drawn twice, clipped to above
// and to below the line of light (the gap follows the line's own points), and the twin also to outside the sun's
// ring (a rectangle with the ring wound the other way, a hole under the non-zero rule). Nothing is painted in the
// plate's colour, so the same shapes serve the themed (single-colour) icon of Android 13 and later.
import { writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { MARK, MARK_COLORS, REST, attributesOf, markMarkup } from '../../desktop/design/chaya-mark.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const drawable = join(root, 'app', 'src', 'main', 'res', 'drawable');

/** The adaptive icon's 108-unit canvas, its 66-unit safe circle, and the plate behind (Stage's night ground). */
const CANVAS = 108;
const PLATE = '#16151A';
/** The mark's 64-unit box scaled into the canvas, centred: its farthest edge stays inside the safe circle. */
const SCALE = 1.15;
const SHIFT = CANVAS / 2 - (SCALE * MARK.box) / 2;
/** The twin's ink in the themed icon: the copy is the fainter, as a shadow is. */
const THEMED_TWIN_ALPHA = 0.5;

const n = (v) => Number(v.toFixed(2));
const rest = attributesOf(REST);
const gap = MARK.line.gap / 2;
// The line at rest, as points ("M2.00,33.00 L2.94,33.03 …")
// Every fourth of its points (and its last): the wave is gentle, and Android Lint holds a vector's path to 800
// characters (VectorPath)
const STEP = 4;
const points = rest.d
  .match(/-?[\d.]+,-?[\d.]+/g)
  .map((p) => p.split(',').map(Number))
  .filter((_, i, all) => i % STEP === 0 || i === all.length - 1);
const first = points[0];
const last = points.at(-1);
const box = MARK.box;

/** Everything above the line's gap, and everything below it: the gap follows the line, offset by half its width. */
const above = `M0,0 L${box},0 L${box},${n(last[1] - gap)} ${points.slice().reverse().map(([x, y]) => `L${n(x)},${n(y - gap)}`).join(' ')} L0,${n(first[1] - gap)} Z`;
const below = `M0,${box} L0,${n(first[1] + gap)} ${points.map(([x, y]) => `L${n(x)},${n(y + gap)}`).join(' ')} L${box},${n(last[1] + gap)} L${box},${box} Z`;
// Beyond the line's ends its gap is round-capped; past x0..x1 nothing of either sun reaches, so straight ends suffice.

const circle = (cx, cy, r, ccw = false) => {
  const sweep = ccw ? 0 : 1;
  return `M${n(cx - r)},${n(cy)} A${n(r)},${n(r)} 0 1 ${sweep} ${n(cx + r)},${n(cy)} A${n(r)},${n(r)} 0 1 ${sweep} ${n(cx - r)},${n(cy)} Z`;
};
const { sun } = MARK;
const twin = { x: Number(rest.cx), y: Number(rest.cy), r: MARK.twin.r };
// Outside the sun's ring: the box clockwise, the ring anticlockwise
const outsideRing = `M0,0 L${box},0 L${box},${box} L0,${box} Z ${circle(sun.x, sun.y, sun.r + MARK.ring, true)}`;

function mark(sunColour, twinColour, twinAlpha = 1, { shift = SHIFT, scale = SCALE } = {}) {
  const part = (clip, extra, d, colour, alpha) =>
    `        <group>
            <clip-path android:pathData="${clip}" />${extra ? `\n            <group>\n                <clip-path android:pathData="${extra}" />` : ''}
            ${extra ? '    ' : ''}<path android:pathData="${d}" android:fillColor="${colour}"${alpha < 1 ? ` android:fillAlpha="${alpha}"` : ''} />${extra ? '\n            </group>' : ''}
        </group>`;
  const twinD = circle(twin.x, twin.y, twin.r);
  const sunD = circle(sun.x, sun.y, sun.r);
  return `    <group android:translateX="${n(shift)}" android:translateY="${n(shift)}" android:scaleX="${scale}" android:scaleY="${scale}">
        <!-- The twin, the copy: the darker, tucked behind the sun, the line of light through it -->
${part(above, outsideRing, twinD, twinColour, twinAlpha)}
${part(below, outsideRing, twinD, twinColour, twinAlpha)}
        <!-- The sun, the line of light through it -->
${part(above, null, sunD, sunColour, 1)}
${part(below, null, sunD, sunColour, 1)}
    </group>`;
}

const head = (what, size = CANVAS) => `<?xml version="1.0" encoding="utf-8"?>
<!--
  ${what}
  Written by docs/brand/android-icon.mjs from Chaya's mark (desktop/design/chaya-mark.js): do not edit by hand.
  The two suns: an amber sun and its shadow-twin, one line of light through both. Chaya (छाया) means shadow; a
  download is a copy of what was on the page.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="${size}dp"
    android:height="${size}dp"
    android:viewportWidth="${size}"
    android:viewportHeight="${size}">
`;

writeFileSync(join(drawable, 'ic_launcher_foreground.xml'), `${head('The launcher icon: the mark at rest.')}${mark(MARK_COLORS.light, MARK_COLORS.shadow)}
</vector>
`);
writeFileSync(join(drawable, 'ic_launcher_monochrome.xml'), `${head('The themed icon (Android 13+): one colour, the twin at half strength.')}${mark('#FFFFFF', '#FFFFFF', THEMED_TWIN_ALPHA)}
</vector>
`);
// The mark by itself, in its own box, for the app to show (Home's name): no plate, no room around it
writeFileSync(join(drawable, 'chaya_mark.xml'), `${head("Chaya's mark at rest, as the app shows it beside its name.", MARK.box)}${mark(MARK_COLORS.light, MARK_COLORS.shadow, 1, { shift: 0, scale: 1 })}
</vector>
`);
writeFileSync(join(drawable, 'ic_launcher_background.xml'), `<?xml version="1.0" encoding="utf-8"?>
<!-- The launcher icon's plate: Stage's night ground, the same in light and dark mode. Written by
     docs/brand/android-icon.mjs; do not edit by hand. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="${CANVAS}"
    android:viewportHeight="${CANVAS}">
    <path android:pathData="M0,0 L${CANVAS},0 L${CANVAS},${CANVAS} L0,${CANVAS} Z" android:fillColor="${PLATE}" />
</vector>
`);
// The master picture for anything outside the app (the README, store pages): the icon as the launcher shows it,
// its 72-unit middle on a rounded plate, the mark drawn with its masks as Windows draws it
const view = 72;
const corner = (CANVAS - view) / 2;
const markSvg = markMarkup('brand', { size: MARK.box, label: '', paint: 'inline' }).replace('<svg class="mark"', '<svg');
writeFileSync(join(root, 'docs', 'brand', 'chaya-icon.svg'), `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${corner} ${corner} ${view} ${view}" width="512" height="512">
<title>Chaya: an amber sun and its shadow-twin, one line of light through both</title>
<rect x="${corner}" y="${corner}" width="${view}" height="${view}" rx="16" fill="${PLATE}"/>
<g transform="translate(${n(SHIFT)} ${n(SHIFT)}) scale(${SCALE})">${markSvg}</g>
</svg>
`);
console.log('wrote ic_launcher_foreground.xml, ic_launcher_monochrome.xml, chaya_mark.xml, ic_launcher_background.xml, docs/brand/chaya-icon.svg');
