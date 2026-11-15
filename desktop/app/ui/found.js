// The browser's "Found on this page" panel (Stage): which quality it offers first, and when it opens on its own.
// Pure functions of what main.js knows, so they can be tested without the window.

/** The tallest picture it offers first: past this, a size is rarely worth its file. */
const FIRST_PICK_AT_MOST = 1080;

/** The picture height a choice's label names ("1080p", "720p60", "2160p"); null for one that names none. */
export function heightOf(label) {
  const m = /^(\d{3,4})p/.exec(String(label ?? ''));
  return m ? Number(m[1]) : null;
}

/**
 * The choice picked when a video is found: the best at most 1080p, in the order offered (best first); else the
 * first that is a picture at all; else the first.
 * @param {{label: string, audioOnly?: boolean}[]} choices
 * @returns {number} its index, or -1 with none
 */
export function firstPick(choices) {
  if (!choices.length) return -1;
  let best = -1;
  choices.forEach((c, i) => {
    const h = heightOf(c.label);
    if (c.audioOnly || h === null || h > FIRST_PICK_AT_MOST) return;
    if (best === -1 || h > heightOf(choices[best].label)) best = i;
  });
  if (best !== -1) return best;
  const picture = choices.findIndex((c) => !c.audioOnly);
  return picture === -1 ? 0 : picture;
}

/**
 * Whether the panel opens itself now. It does once for each address that has something to save on it, and only
 * when nothing else is open and the page still has room beside it; after that, only its pill opens it. `offeredFor`
 * is the one address it was last offered for, the panel's only memory, so closing it keeps it closed there.
 * @param {{ address: string, offeredFor: string, something: boolean, open: string | null, fits: boolean }} now
 * @returns {{ open: boolean, offeredFor: string }}
 */
export function offerFound({ address, offeredFor, something, open, fits }) {
  if (!address || !something || address === offeredFor) return { open: false, offeredFor };
  // Offered once here, whether or not it can open: another panel open, or no room, leaves the pill lit instead.
  return { open: open === null && fits, offeredFor: address };
}
