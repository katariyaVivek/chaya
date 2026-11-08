// Which of the window's panels is open (`main.js`): Settings, the ad blocker's shield, or what the page holds. One at
// a time. Each panel used to keep its own `hidden` flag, so a panel opened by the app itself (the page's media from
// the address bar, Settings from the player) could open over another one, and Escape and the page's place each worked
// out again from the page which one was open.

/**
 * One open panel at a time. `show(name, open)` draws a panel opened or closed; it is called only when that changes,
 * the panel being closed first when another opens in its place.
 */
export function onePanel(show) {
  let open = null;
  return {
    /** The panel open, or null. */
    get open() {
      return open;
    },
    /** Opens `name` in place of the panel open; closing `name` closes it only if it is the one open. */
    set(name, on) {
      if (on) {
        if (open === name) return;
        const before = open;
        open = name;
        if (before) show(before, false);
        show(name, true);
      } else if (open === name) {
        open = null;
        show(name, false);
      }
    },
    /** What a panel's button does: opens it, or closes it when it is the one open. */
    toggle(name) {
      this.set(name, open !== name);
    },
  };
}
