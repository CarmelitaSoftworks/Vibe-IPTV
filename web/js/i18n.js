// Idiomas de la interfaz / UI languages.
// La app está escrita en español; el inglés sale del diccionario compartido i18n/en.json
// (lo usan la web y Android). The app is written in Spanish; English comes from the shared
// dictionary i18n/en.json (used by both web and Android). Regenerate with: node i18n/build.mjs
import EN from './i18n-en.js';

let lang = 'es';
const exact = new Map(Object.entries(EN));
const patterns = Object.entries(EN)
  .filter(([k]) => k.includes('{}'))
  .sort((a, b) => b[0].length - a[0].length)
  .map(([k, v]) => ({
    re: new RegExp('^' + k.split('{}').map((p) => p.replace(/[.*+?^$()|[\]\\]/g, '\\$&')).join('((?:(?! · ).)+?)') + '$', 's'),   // un valor nunca cruza " · "
    v,
  }));
const cache = new Map();

export const getLang = () => lang;
export const locale = () => (lang === 'en' ? 'en-US' : 'es-CL');

/** 'auto' sigue el idioma del sistema: español si es español, si no inglés. */
export function resolveLang(pref) {
  if (pref === 'es' || pref === 'en') return pref;
  const sys = (navigator.languages?.[0] || navigator.language || 'es').toLowerCase();
  return sys.startsWith('es') ? 'es' : 'en';
}

function one(s) {
  const hit = exact.get(s);
  if (hit !== undefined) return hit;
  for (const p of patterns) {
    const m = p.re.exec(s);
    if (m) { let i = 1; return p.v.replace(/\{\}/g, () => { const a = m[i++] ?? ''; return exact.get(a) ?? a; }); }
  }
  return null;
}

/** Traduce un texto en español al idioma activo (lo deja igual si no hay traducción). */
export function t(s) {
  if (lang === 'es' || typeof s !== 'string' || !/[A-Za-zÀ-ÿ]/.test(s)) return s;
  const c = cache.get(s);
  if (c !== undefined) return c;
  const lead = s.match(/^\s*/)[0], trail = s.slice(lead.length).match(/\s*$/)[0];
  const core = s.slice(lead.length, s.length - trail.length);
  let out = one(core);
  if (out == null && core.includes('\n')) out = core.split('\n').map((x) => one(x) ?? x).join('\n');
  if (out == null && core.includes(' · ')) out = core.split(' · ').map((x) => one(x) ?? x).join(' · ');
  if (out == null) out = core;
  out = lead + out + trail;
  if (cache.size > 4000) cache.clear();
  cache.set(s, out);
  return out;
}

/* ---------- Traducción automática del documento ---------- */
const textSrc = new WeakMap();   // nodo de texto → { src, out }
const attrSrc = new WeakMap();   // elemento → { atributo: { src, out } }
const ATTRS = ['title', 'placeholder', 'aria-label'];
const SKIP = new Set(['SCRIPT', 'STYLE', 'TEXTAREA', 'CODE', 'PRE']);

function doText(node) {
  const cur = node.nodeValue;
  const rec = textSrc.get(node);
  const src = rec && cur === rec.out ? rec.src : cur;
  const out = t(src);
  if (out !== cur) node.nodeValue = out;
  textSrc.set(node, { src, out });
}

function doAttrs(el) {
  let recs = attrSrc.get(el);
  for (const a of ATTRS) {
    if (!el.hasAttribute(a)) continue;
    const cur = el.getAttribute(a);
    recs ||= {};
    const rec = recs[a];
    const src = rec && cur === rec.out ? rec.src : cur;
    const out = t(src);
    if (out !== cur) el.setAttribute(a, out);
    recs[a] = { src, out };
  }
  if (recs) attrSrc.set(el, recs);
}

export function translateTree(root) {
  if (!root) return;
  if (root.nodeType === 3) { if (!root.parentElement?.closest('[data-no-i18n]')) doText(root); return; }
  if (root.nodeType !== 1 || SKIP.has(root.tagName) || root.closest?.('[data-no-i18n]')) return;
  doAttrs(root);
  const w = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
    acceptNode: (n) => (n.nodeType === 1 && (SKIP.has(n.tagName) || n.hasAttribute('data-no-i18n')) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT),
  });
  for (let n = w.nextNode(); n; n = w.nextNode()) { if (n.nodeType === 3) doText(n); else doAttrs(n); }
}

let observer = null;
let pending = new Set();
function flush() {
  const nodes = pending; pending = new Set();
  for (const n of nodes) if (n.isConnected) translateTree(n);
}

/** Cambia el idioma sin tocar la pantalla (útil en pruebas). */
export function useLang(l) { const n = l === 'en' ? 'en' : 'es'; if (n !== lang) { lang = n; cache.clear(); } }

/** Activa el idioma y traduce lo que ya está en pantalla y lo que aparezca después. */
export function setLang(l) {
  useLang(l);
  document.documentElement.lang = lang;
  if (lang === 'es' && !observer) return;          // español de origen: no hay nada que traducir
  translateTree(document.body);
  if (!observer) {
    observer = new MutationObserver((muts) => {
      for (const m of muts) {
        if (m.type === 'childList') m.addedNodes.forEach((n) => pending.add(n));
        else if (m.type === 'characterData') pending.add(m.target);
        else if (m.type === 'attributes') pending.add(m.target);
      }
      if (pending.size) queueMicrotask(flush);
    });
    observer.observe(document.body, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ATTRS });
  }
}
