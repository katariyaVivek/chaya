// Makes Chaya for Windows' icon files in src-tauri/icons from the mark (design/chaya-mark.js), at rest, on a
// near-black plate (Windows applies no mask). Run it after changing the mark: node scripts/icons.mjs
//
// Two sources, both from the mark's constants: icon.svg for 48 px and up, and icon-small.svg for 32 px and down,
// where the mark sits larger on the plate and its gap and hairline are wider, as at 16 px the usual ones blur.
// Tauri's own `tauri icon` renders each; icon.ico takes its small pictures from the second and the rest from the first.
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { markMarkup, MARK } from '../../design/chaya-mark.js';

const app = join(dirname(fileURLToPath(import.meta.url)), '..');
const icons = join(app, 'src-tauri', 'icons');

// The plate: a rounded square with a faint light edge, so it keeps its outline on a dark taskbar
const PLATE = { inset: 2, radius: 13.5, fill: '#16151A', edge: 'rgba(255,255,255,0.12)' };
// How much of the box the mark fills (it is centred already: its sun and twin together span 8..56 by 11..53)
const LARGE = { scale: 0.74 };
const SMALL = { scale: 0.9, gap: 6, ring: 3.4 };
const SMALL_SIZES = [16, 24, 32];

function source({ scale, gap, ring }) {
  const box = MARK.box;
  const side = box - 2 * PLATE.inset;
  const mark = markMarkup('icon', { size: box, label: '', paint: 'inline', gap, ring }).replace('<svg class="mark"', '<svg');
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${box} ${box}" width="1024" height="1024">
<rect x="${PLATE.inset}" y="${PLATE.inset}" width="${side}" height="${side}" rx="${PLATE.radius}" fill="${PLATE.fill}" stroke="${PLATE.edge}" stroke-width="0.5"/>
<g transform="translate(${box / 2} ${box / 2}) scale(${scale}) translate(${-box / 2} ${-box / 2})">${mark}</g>
</svg>
`;
}

/** The pictures in an .ico: [{ width, height, entry (its 16-byte directory entry), data }]. */
function readIco(path) {
  const buf = readFileSync(path);
  const count = buf.readUInt16LE(4);
  const out = [];
  for (let i = 0; i < count; i++) {
    const at = 6 + 16 * i;
    const size = buf.readUInt32LE(at + 8);
    const offset = buf.readUInt32LE(at + 12);
    out.push({ width: buf[at] || 256, height: buf[at + 1] || 256, entry: buf.subarray(at, at + 16), data: buf.subarray(offset, offset + size) });
  }
  return out;
}

function writeIco(path, pictures) {
  const head = Buffer.alloc(6);
  head.writeUInt16LE(1, 2);
  head.writeUInt16LE(pictures.length, 4);
  let offset = 6 + 16 * pictures.length;
  const entries = pictures.map((p) => {
    const e = Buffer.from(p.entry);
    e.writeUInt32LE(p.data.length, 8);
    e.writeUInt32LE(offset, 12);
    offset += p.data.length;
    return e;
  });
  writeFileSync(path, Buffer.concat([head, ...entries, ...pictures.map((p) => p.data)]));
}

const render = (svg) => {
  const out = mkdtempSync(join(tmpdir(), 'chaya-icon-'));
  execFileSync('npx', ['tauri', 'icon', svg, '-o', out], { cwd: app, stdio: 'ignore', shell: process.platform === 'win32' });
  return out;
};

writeFileSync(join(icons, 'icon.svg'), source(LARGE));
writeFileSync(join(icons, 'icon-small.svg'), source(SMALL));
const large = render(join(icons, 'icon.svg'));
const small = render(join(icons, 'icon-small.svg'));

for (const file of ['64x64.png', '128x128.png', '128x128@2x.png', 'icon.png']) cpSync(join(large, file), join(icons, file));
cpSync(join(small, '32x32.png'), join(icons, '32x32.png'));
// In Tauri's own order (which picture comes first matters to Windows), with the small sizes' pictures swapped in
const smallOnes = readIco(join(small, 'icon.ico'));
const pictures = readIco(join(large, 'icon.ico')).map((p) =>
  SMALL_SIZES.includes(p.width) ? smallOnes.find((q) => q.width === p.width) ?? p : p,
);
writeIco(join(icons, 'icon.ico'), pictures);

rmSync(large, { recursive: true, force: true });
rmSync(small, { recursive: true, force: true });
console.log(`icon.ico holds ${pictures.map((p) => p.width).join(', ')} px; PNGs and both sources written to ${icons}`);
