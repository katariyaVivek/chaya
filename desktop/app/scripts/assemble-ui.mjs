// Puts the window's files together in dist/: the app's own (ui/) and the design system's
// (../design/), which the window loads from dist/design/. Tauri runs this before dev and build.
import { cpSync, existsSync, mkdirSync, readdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const app = join(dirname(fileURLToPath(import.meta.url)), '..');
const design = join(app, '..', 'design');
const dist = join(app, 'dist');

rmSync(dist, { recursive: true, force: true });
cpSync(join(app, 'ui'), dist, { recursive: true });

mkdirSync(join(dist, 'design', 'fonts'), { recursive: true });
for (const file of ['tokens.css', 'fonts.css', 'components.css', 'chaya-motion.js']) {
  cpSync(join(design, file), join(dist, 'design', file));
}
const fonts = readdirSync(join(design, 'fonts')).filter((f) => /\.(woff2|ttf)$/.test(f));
for (const font of fonts) cpSync(join(design, 'fonts', font), join(dist, 'design', 'fonts', font));

const cabinet = existsSync(join(design, 'fonts', 'CabinetGrotesk-Variable.ttf'));
console.log(`Window assembled in dist/ with ${fonts.join(', ')}.`);
if (!cabinet) console.log('Cabinet Grotesk is not in desktop/design/fonts, so titles use Geist (see its README).');
