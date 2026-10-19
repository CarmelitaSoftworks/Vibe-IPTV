// Navegación espacial con flechas / D-pad (teclado, mando de TV, navegadores de Smart TV)
const FOCUSABLE = '[data-nav]:not([disabled]):not([hidden])';

const KEYMAP = {
  ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right',
  38: 'up', 40: 'down', 37: 'left', 39: 'right',
};
export const BACK_KEYS = new Set(['Escape', 'Backspace', 'BrowserBack', 'GoBack', 'XF86Back', 10009, 461]);

function visible(el) {
  const r = el.getBoundingClientRect();
  return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden';
}

function inScope(el, scope) { return !scope || scope.contains(el); }

export function moveFocus(dir, scope = null) {
  const cur = document.activeElement;
  const all = [...document.querySelectorAll(FOCUSABLE)].filter((e) => e !== cur && visible(e) && inScope(e, scope));
  if (!cur || !cur.matches?.(FOCUSABLE)) { all[0]?.focus(); return !!all[0]; }
  const a = cur.getBoundingClientRect();
  const ax = a.left + a.width / 2, ay = a.top + a.height / 2;
  let best = null, bestScore = Infinity;
  for (const el of all) {
    const b = el.getBoundingClientRect();
    const bx = b.left + b.width / 2, by = b.top + b.height / 2;
    const dx = bx - ax, dy = by - ay;
    let main, cross;
    if (dir === 'right') { if (b.left < a.right - 4) continue; main = dx; cross = Math.abs(dy); }
    if (dir === 'left') { if (b.right > a.left + 4) continue; main = -dx; cross = Math.abs(dy); }
    if (dir === 'down') { if (b.top < a.bottom - 4) continue; main = dy; cross = Math.abs(dx); }
    if (dir === 'up') { if (b.bottom > a.top + 4) continue; main = -dy; cross = Math.abs(dx); }
    const score = main + cross * 2.5;
    if (score < bestScore) { bestScore = score; best = el; }
  }
  if (best) { best.focus({ preventScroll: false }); best.scrollIntoView({ block: 'nearest', inline: 'nearest' }); }
  return !!best;
}

export function keyDir(e) { return KEYMAP[e.key] || KEYMAP[e.keyCode] || null; }
export function isBack(e) { return BACK_KEYS.has(e.key) || BACK_KEYS.has(e.keyCode); }
export function isOk(e) { return e.key === 'Enter' || e.keyCode === 13 || e.keyCode === 23; }
