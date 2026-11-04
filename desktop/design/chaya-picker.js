// Chaya desktop: a drop-down of Chaya's own, in place of Windows' list.
//
// A native <select> opens a list Windows draws: its own font, its own scrollbar, and in Night light words on white.
// `Picker` keeps the <select> (hidden, still the value, still firing `change`), and shows a button and a list drawn
// from the tokens instead. The list is a popover, so it sits above everything (a panel's edges and its scrolling
// included) and closes on a click elsewhere. Focus stays on the button; the arrow keys, Home and End move through the
// list, Enter or Space chooses, Escape and Tab close it, and typing jumps to the choice that starts with what was
// typed, open or not, as Windows' own list does. Plain ES module, no dependencies.

/** Typed letters this close together are one word for jumping. */
export const TYPE_AHEAD_MS = 700;

/**
 * The next choice from `from`, `step` along (1 down, -1 up), skipping those `disabled`; `from` itself when there is
 * none that way. With `from` -1 (nothing chosen), a step down lands on the first that can be chosen.
 * @param {{disabled: boolean}[]} options
 */
export function nextEnabled(options, from, step) {
  for (let i = from + step; i >= 0 && i < options.length; i += step) {
    if (!options[i].disabled) return i;
  }
  return from;
}

/** The first choice that can be chosen, or the last (`last`); -1 when none can. */
export function edgeEnabled(options, last = false) {
  return nextEnabled(options, last ? options.length : -1, last ? -1 : 1);
}

/**
 * The choice `typed` jumps to: the first after `from` (wrapping round) whose label starts with it, ignoring case and
 * accents; one letter typed again goes on to the next that starts with it. -1 when none does.
 * @param {{label: string, disabled: boolean}[]} options
 */
export function typeAhead(options, typed, from) {
  const fold = (s) => s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
  const wanted = fold(typed);
  if (!wanted) return -1;
  const repeated = [...wanted].every((c) => c === wanted[0]) ? wanted[0] : wanted;
  // A word being typed may still match the choice already on: it is looked at first.
  const start = repeated === wanted && wanted.length > 1 ? from : from + 1;
  for (let k = 0; k < options.length; k++) {
    const i = (((start + k) % options.length) + options.length) % options.length;
    if (!options[i].disabled && fold(options[i].label).startsWith(repeated)) return i;
  }
  return -1;
}

const CHEVRON = '<svg class="picker__chevron" viewBox="0 0 16 16" aria-hidden="true"><path d="M4 6l4 4 4-4"/></svg>';
const CHECK = '<svg class="picker__check" viewBox="0 0 16 16" aria-hidden="true"><path d="M3.5 8.5l3 3 6-7"/></svg>';
let made = 0;

export class Picker {
  /** @param {HTMLSelectElement} select */
  constructor(select) {
    this.select = select;
    this.active = -1;
    this.typed = '';
    this.typedAt = 0;
    const id = select.id || `picker-${++made}`;

    this.root = document.createElement('div');
    this.root.className = 'picker';
    select.replaceWith(this.root);
    select.hidden = true;
    select.tabIndex = -1;
    select.setAttribute('aria-hidden', 'true');

    this.button = document.createElement('button');
    this.button.type = 'button';
    this.button.id = `${id}-picker`;
    this.button.className = [...select.classList].filter((c) => c !== 'select').concat('picker__button').join(' ');
    this.button.setAttribute('aria-haspopup', 'listbox');
    this.button.setAttribute('aria-expanded', 'false');
    this.value = document.createElement('span');
    this.value.id = `${id}-value`;
    this.value.className = 'picker__value';
    this.button.append(this.value);
    this.button.insertAdjacentHTML('beforeend', CHEVRON);

    this.list = document.createElement('ul');
    this.list.id = `${id}-list`;
    this.list.className = 'picker__list';
    this.list.setAttribute('role', 'listbox');
    this.list.tabIndex = -1;
    this.list.popover = 'auto';
    this.button.setAttribute('aria-controls', this.list.id);
    this.root.append(select, this.button, this.list);

    // Its name, then what is chosen: the select's own name, or the label that named it, which now names (and focuses)
    // the button.
    const label = select.id && document.querySelector(`label[for="${CSS.escape(select.id)}"]`);
    let name = select.getAttribute('aria-labelledby');
    if (label) {
      label.id ||= `${id}-label`;
      label.htmlFor = this.button.id;
      name = label.id;
    } else if (!name && select.hasAttribute('aria-label')) {
      this.list.setAttribute('aria-label', select.getAttribute('aria-label'));
      this.button.setAttribute('aria-label', select.getAttribute('aria-label'));
    }
    if (name) {
      this.button.setAttribute('aria-labelledby', `${name} ${this.value.id}`);
      this.list.setAttribute('aria-labelledby', name);
    }

    this.follow();
    this.button.addEventListener('click', () => (this.isOpen() ? this.close() : this.open()));
    this.button.addEventListener('keydown', (e) => this.keyClosed(e));
    this.list.addEventListener('pointermove', (e) => {
      const item = e.target.closest('[role="option"]');
      if (item && item.getAttribute('aria-disabled') !== 'true') this.light(Number(item.dataset.index), false);
    });
    this.list.addEventListener('click', (e) => {
      const item = e.target.closest('[role="option"]');
      if (item && item.getAttribute('aria-disabled') !== 'true') this.choose(Number(item.dataset.index));
    });
    // Its own keys while open, before the window's (Escape closes the list, not the panel or the player under it).
    this.onKey = (e) => this.keyOpen(e);
    this.list.addEventListener('toggle', (e) => {
      const open = e.newState === 'open';
      this.button.setAttribute('aria-expanded', String(open));
      this.root.classList.toggle('is-open', open);
      if (open) window.addEventListener('keydown', this.onKey, true);
      else {
        window.removeEventListener('keydown', this.onKey, true);
        this.button.removeAttribute('aria-activedescendant');
      }
    });
    this.onMove = () => this.isOpen() && this.place();
    window.addEventListener('resize', this.onMove);
    // Something under it scrolled (the panel it is in): the list stays with its button.
    document.addEventListener('scroll', (e) => e.target !== this.list && this.onMove(), true);
    this.render();
  }

  /** The <select>, changed by the app (its value, its choices, whether it can be used), shows as it is. */
  follow() {
    const select = this.select;
    const proto = HTMLSelectElement.prototype;
    for (const name of ['value', 'selectedIndex']) {
      const native = Object.getOwnPropertyDescriptor(proto, name);
      Object.defineProperty(select, name, {
        configurable: true,
        get: () => native.get.call(select),
        set: (v) => {
          native.set.call(select, v);
          this.render();
        },
      });
    }
    new MutationObserver(() => this.render()).observe(select, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['disabled', 'label', 'hidden'],
      characterData: true,
    });
  }

  options() {
    return [...this.select.options].map((o) => ({ label: o.label || o.text, disabled: o.disabled || o.hidden }));
  }

  isOpen() {
    return this.list.matches(':popover-open');
  }

  render() {
    const chosen = this.select.selectedIndex;
    const options = this.options();
    this.value.textContent = options[chosen]?.label ?? '';
    this.button.disabled = this.select.disabled;
    this.list.replaceChildren(
      ...options.map((o, i) => {
        const item = document.createElement('li');
        item.id = `${this.list.id}-${i}`;
        item.className = 'picker__option';
        item.setAttribute('role', 'option');
        item.dataset.index = String(i);
        item.setAttribute('aria-selected', String(i === chosen));
        if (o.disabled) item.setAttribute('aria-disabled', 'true');
        item.insertAdjacentHTML('afterbegin', CHECK);
        item.append(o.label);
        return item;
      }),
    );
    if (this.isOpen()) this.light(this.active, false);
  }

  open() {
    if (this.select.disabled || this.isOpen()) return;
    this.list.showPopover();
    this.place();
    const options = this.options();
    const chosen = this.select.selectedIndex;
    this.light(chosen >= 0 && !options[chosen]?.disabled ? chosen : edgeEnabled(options), true);
  }

  close() {
    if (this.isOpen()) this.list.hidePopover();
  }

  /** The list under the button (above it when there is more room there), as wide as it at least. */
  place() {
    const box = this.button.getBoundingClientRect();
    const room = { below: window.innerHeight - box.bottom - 8, above: box.top - 8 };
    const below = room.below >= Math.min(this.list.scrollHeight, 240) || room.below >= room.above;
    const style = this.list.style;
    style.left = `${Math.max(8, Math.min(box.left, window.innerWidth - Math.max(box.width, this.list.offsetWidth) - 8))}px`;
    style.minWidth = `${box.width}px`;
    style.maxHeight = `${Math.max(120, Math.min(320, below ? room.below : room.above))}px`;
    style.top = below ? `${box.bottom + 4}px` : 'auto';
    style.bottom = below ? 'auto' : `${window.innerHeight - box.top + 4}px`;
  }

  /** The choice `index` lit (the one Enter takes), scrolled into sight when it came by key. */
  light(index, scroll) {
    this.active = index;
    for (const item of this.list.children) item.classList.toggle('is-active', Number(item.dataset.index) === index);
    const item = this.list.children[index];
    if (item) {
      this.button.setAttribute('aria-activedescendant', item.id);
      // Only the list scrolls: scrolling a panel behind it would close it.
      if (scroll && item.offsetTop < this.list.scrollTop) this.list.scrollTop = item.offsetTop;
      else if (scroll && item.offsetTop + item.offsetHeight > this.list.scrollTop + this.list.clientHeight) {
        this.list.scrollTop = item.offsetTop + item.offsetHeight - this.list.clientHeight;
      }
    }
  }

  /** `index` chosen: the <select> takes it and says so, as when Windows' list is used. */
  choose(index) {
    this.close();
    if (index < 0 || index === this.select.selectedIndex) return;
    this.select.selectedIndex = index;
    this.select.dispatchEvent(new Event('input', { bubbles: true }));
    this.select.dispatchEvent(new Event('change', { bubbles: true }));
  }

  jump(key) {
    const now = performance.now();
    this.typed = now - this.typedAt < TYPE_AHEAD_MS ? this.typed + key : key;
    this.typedAt = now;
    return typeAhead(this.options(), this.typed, this.isOpen() ? this.active : this.select.selectedIndex);
  }

  keyClosed(e) {
    if (this.isOpen() || e.ctrlKey || e.metaKey) return;
    if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(e.key)) {
      e.preventDefault();
      this.open();
    } else if (e.key.length === 1 && !e.altKey) {
      const to = this.jump(e.key);
      if (to >= 0) this.choose(to);
    }
  }

  keyOpen(e) {
    const options = this.options();
    let handled = true;
    if (e.key === 'ArrowDown') this.light(nextEnabled(options, this.active, 1), true);
    else if (e.key === 'ArrowUp') this.light(nextEnabled(options, this.active, -1), true);
    else if (e.key === 'Home' || e.key === 'PageUp') this.light(edgeEnabled(options), true);
    else if (e.key === 'End' || e.key === 'PageDown') this.light(edgeEnabled(options, true), true);
    else if (e.key === 'Enter' || e.key === ' ') this.choose(this.active);
    else if (e.key === 'Escape') this.close();
    else if (e.key === 'Tab') {
      this.close();
      handled = false;
    } else if (e.key.length === 1 && !e.ctrlKey && !e.altKey && !e.metaKey) {
      const to = this.jump(e.key);
      if (to >= 0) this.light(to, true);
    } else handled = false;
    if (handled) {
      e.preventDefault();
      e.stopPropagation();
    }
  }
}

/** Every `select.select` under `root` drawn as Chaya's own; each only once. */
export function enhanceSelects(root = document) {
  return [...root.querySelectorAll('select.select')].filter((s) => !s.closest('.picker')).map((s) => new Picker(s));
}
