import { t, setLang, resolveLang, locale } from './i18n.js';
import { parseM3U, splitGroup } from './m3u.js';
import { XtreamClient, normalizeServer } from './xtream.js';
import { Epg, loadEpg } from './epg.js';
import { Player, altFormat, getCaps } from './player.js';
import { fetchSmart, redact, proxify, proxyBase } from './net.js';
import { initSettings, openSettings, refreshSettings, handleSettingsKey } from './settings.js';
import { moveFocus, keyDir, isBack, isOk } from './nav.js';
import * as store from './storage.js';
import * as subs from './subtitles.js';
import { demoM3U, demoEpg, DEMO_SOURCE } from './demo.js';

// En la versión artefacto (vista previa dentro de Claude) se define __ARTIFACT__ al empaquetar
const IS_ARTIFACT = typeof __ARTIFACT__ !== 'undefined' && __ARTIFACT__;
window.__IPTV_ARTIFACT__ = IS_ARTIFACT;

const $ = (id) => document.getElementById(id);
const SECTIONS = { live: 'TV en vivo', movie: 'Películas', series: 'Series' };
const SECTION_ICONS = {
  live: '<svg viewBox="0 0 48 48"><rect x="5" y="9" width="38" height="26" rx="5"/><path d="M17 41h14M24 35v6M19 4l5 5 5-5"/><circle cx="36" cy="16" r="1.6" fill="currentColor"/></svg>',
  movie: '<svg viewBox="0 0 48 48"><rect x="6" y="16" width="36" height="25" rx="4"/><path d="m6 16 4-9 34 0-3 9M14 7l5 9M24 7l5 9M34 7l5 9"/><path d="m21 24 8 4.5-8 4.5z" fill="currentColor"/></svg>',
  series: '<svg viewBox="0 0 48 48"><rect x="4" y="15" width="30" height="22" rx="4"/><path d="M10 10h28a4 4 0 0 1 4 4v18M16 5h26a4 4 0 0 1 4 4v16"/><path d="m16 22 7 4-7 4z" fill="currentColor"/></svg>',
};
const ALL = '__all__', FAV = '__fav__';

const state = {
  source: null, items: [], epg: new Epg(), xt: null,
  home: true, section: 'live', category: ALL, query: '',
  series: null, episodes: [],
  view: [], page: 0, cols: 4, rows: 3,
  favs: new Set(),
  playing: null, playList: [], playIndex: -1,
};

/* ---------- utilidades ---------- */
const norm = (s) => (s || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmtTime = (ms) => new Date(ms).toLocaleTimeString(locale(), { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
const initials = (name) => (name || '?').replace(/^[A-Z]{2,3}\s*[|:]\s*/, '').split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]).join('').toUpperCase();

let toastTimer;
function toast(msg, ms = 3500) {
  const t = $('toast');
  t.textContent = redact(msg); t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.hidden = true), ms);
}
function loader(on, text = 'Cargando…') { $('loader').hidden = !on; $('loaderText').textContent = text; }

function tickClock() {
  const d = new Date();
  const day = d.toLocaleDateString(locale(), { weekday: 'short' }).replace('.', '');
  const date = d.toLocaleDateString(locale(), { day: '2-digit', month: '2-digit' });
  $('dockClock').textContent = `${day[0].toUpperCase()}${day.slice(1)} ${date} · ${fmtTime(d)}`;
  $('pClock').textContent = fmtTime(d);
}

/* ---------- ajustes aplicados ---------- */
const cfg = () => store.getSettings();
const epgShift = () => (Number(cfg().epgOffset) || 0) * 3600e3;          // desfase elegido en Ajustes
const nowNext = (item) => state.epg.nowNext(item, Date.now() - epgShift());
const pTime = (ms) => fmtTime(ms + epgShift());

function applySettings(key) {
  const s = cfg(), root = document.documentElement, body = document.body;
  root.dataset.appTheme = s.theme;
  setLang(resolveLang(s.lang));
  if (s.accent && s.accent !== 'celeste') root.dataset.accent = s.accent; else delete root.dataset.accent;
  body.classList.toggle('no-nums', !s.showNumbers);
  body.classList.toggle('no-anim', !s.animations);
  body.classList.toggle('no-epginfo', !s.showEpgInfo);
  root.style.setProperty('--cue-size', { small: '.8em', normal: '1em', large: '1.3em', xlarge: '1.6em' }[s.subSize] || '1em');
  if (key === 'tileSize' && state.source) refresh();
  if (key === 'epgOffset') updateFocusInfo();
  if (key === 'liveFormat' && state.source?.type === 'xtream') openSource(state.source);
}

/* ---------- guía EPG: caché + actualización automática ---------- */
const EPG_ON_RETURN_MS = 30 * 60e3;   // al volver a la app si la guía tiene más de 30 min
let epgJob = 0;

function epgFrom(cache) { return new Epg(new Map(cache.byId), new Map(cache.names)); }

async function loadCachedEpg(src) {
  try {
    const c = await store.idbGet(`epg:${src.id}`);
    if (!c || state.source !== src || state.epgAt > c.at) return;
    state.epg = epgFrom(c); state.epgAt = c.at; state.epgUrls = c.urls;
    state.epgStatus = `Guía guardada del ${new Date(c.at).toLocaleString(locale(), { dateStyle: 'short', timeStyle: 'short', hourCycle: 'h23' })} · actualizando…`;
    updateFocusInfo(); updateEpgStatus();
  } catch { /* sin caché disponible */ }
}

async function refreshEpg(src = state.source, urls = state.epgUrls) {
  if (!src || src.demo) return;
  state.epgUrls = urls || '';
  if (!urls) { state.epgStatus = 'Esta lista no trae guía. Agrega una URL XMLTV al editar la lista.'; updateEpgStatus(); return; }
  const job = ++epgJob;
  state.epgStatus = 'Actualizando guía…'; updateEpgStatus();
  try {
    const e = await loadEpg(urls);
    if (job !== epgJob || state.source !== src) return;
    state.epg = e; state.epgAt = Date.now();
    state.epgStatus = `Guía actualizada a las ${fmtTime(state.epgAt)} · ${e.byId.size} canales con programación`;
    store.idbSet(`epg:${src.id}`, { at: state.epgAt, urls, byId: [...e.byId], names: [...e.nameToId] }).catch(() => {});
    updateFocusInfo();
    if (!$('playerView').hidden) renderPlayerEpg();
  } catch (err) {
    if (job !== epgJob) return;
    state.epgStatus = `No se pudo actualizar la guía (sin respuesta del servidor de la guía). ${state.epg.byId.size ? 'Se mantiene la última guardada.' : ''}`;
  }
  updateEpgStatus();
}

function updateEpgStatus() { refreshSettings(); }

setInterval(() => {
  const every = Number(cfg().epgEvery) * 3600e3; // 0 = sólo al abrir la app
  if (every && state.source && state.epgUrls && Date.now() - state.epgAt > every) refreshEpg();
}, 10 * 60e3);
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && state.source && state.epgUrls && Date.now() - state.epgAt > EPG_ON_RETURN_MS) refreshEpg();
});

/* ---------- fuentes ---------- */
async function openSource(src) {
  showWelcome(false, false);
  closeSheets();
  closeSeries(false);
  state.source = src;
  state.items = [];
  state.epg = new Epg();
  state.epgUrls = ''; state.epgAt = 0; state.epgStatus = '';
  state.favs = store.getFavs(src.id);
  if (!src.demo) loadCachedEpg(src); // la última guía guardada se muestra al instante
  $('sourceName').textContent = src.name;
  store.saveLast({ sourceId: src.id });
  renderGrid();
  loader(true, `Cargando ${src.name}…`);
  try {
    let epgUrl = src.epgUrl;
    if (src.type === 'xtream') {
      state.xt = new XtreamClient(src, store.getSettings().liveFormat);
      await state.xt.login();
      if (state.xt.workingBase && state.xt.workingBase !== normalizeServer(src.server)) {
        // La dirección escrita no respondía pero una variante sí: se guarda la que funciona
        src.server = state.xt.workingBase;
        store.upsertSource(src);
        toast(`Dirección corregida a ${src.server}`, 4000);
      }
      state.items = await state.xt.loadAll();
      epgUrl ||= state.xt.epgUrl();
    } else {
      state.xt = null;
      const text = src.demo ? demoM3U() : src.url ? await fetchSmart(src.url) : await store.idbGet(`m3u:${src.id}`);
      if (!text) throw new Error('La lista está vacía');
      const parsed = parseM3U(text);
      state.items = parsed.items;
      epgUrl ||= parsed.epgUrl;
    }
    for (const it of state.items) Object.assign(it, splitGroup(it.group));   // categoría y subcategoría
    const kinds = Object.keys(SECTIONS).filter((k) => state.items.some((i) => i.kind === k));
    state.section = kinds.includes(state.section) ? state.section : (kinds[0] || 'live');
    state.category = ALL; state.sub = '';
    state.query = '';
    state.home = true;
    renderTabs();
    refresh(true);
    if (!openSheetId()) focusFirstTile();
    if (src.demo) { state.epg = demoEpg(); state.epgAt = Date.now(); state.epgStatus = 'Guía de demostración'; }
    else refreshEpg(src, epgUrl); // siempre al añadir la lista y al abrir la app
  } catch (e) {
    console.error(e);
    toast(e.message || String(e), 9000);
    renderGrid();
    if (!state.items.length) showWelcome(true);   // volver a la pantalla de listas para corregir los datos
  } finally { loader(false); }
}

function renderSourceList() {
  const list = store.getSources();
  $('sourceList').innerHTML = list.length ? '' : '<p class="hint">Aún no tienes listas guardadas.</p>';
  if (IS_ARTIFACT) $('sourceList').insertAdjacentHTML('afterbegin', '<p class="hint notice">Vista previa: aquí sólo se reproducen los videos de demostración. Este entorno bloquea las conexiones a servidores externos, así que tus listas reales funcionan en la app web local o en Android.</p>');
  for (const s of list) {
    const row = document.createElement('div');
    row.className = 'source-row' + (state.source?.id === s.id ? ' current' : '');
    row.innerHTML = `<div class="meta"><b>${esc(s.name)}</b><small>${s.type === 'xtream' ? `Xtream · ${esc(s.server)}` : esc(s.url || 'Archivo local')}</small></div>
      <button class="btn" data-nav data-a="open">Abrir</button>
      <button class="btn ghost" data-nav data-a="edit">Editar</button>
      <button class="btn danger" data-nav data-a="del">Borrar</button>`;
    row.addEventListener('click', async (e) => {
      const a = e.target.dataset.a;
      if (a === 'open') openSource(s);
      if (a === 'edit') fillForm(s);
      if (a === 'del' && e.target.dataset.sure !== '1') {
        e.target.dataset.sure = '1';
        e.target.textContent = '¿Borrar?';
        setTimeout(() => { if (e.target.isConnected) { e.target.dataset.sure = ''; e.target.textContent = 'Borrar'; } }, 3000);
        return;
      }
      if (a === 'del') {
        store.removeSource(s.id);
        store.idbDel(`m3u:${s.id}`).catch(() => {});
        if (state.source?.id === s.id) { state.source = null; state.items = []; $('sourceName').textContent = 'Sin lista'; renderTabs(); refresh(true); }
        renderSourceList();
        if (!store.getSources().length) { closeSheets(false); showWelcome(true); }
      }
    });
    $('sourceList').appendChild(row);
  }
}

function setFormType(type) {
  const f = $('sourceForm');
  f.querySelector(`input[name=type][value=${type}]`).checked = true;
  f.querySelector('.only-m3u').hidden = type !== 'm3u';
  f.querySelector('.only-xtream').hidden = type !== 'xtream';
}
function fillForm(s) {
  const f = $('sourceForm');
  f.reset();
  $('fileName').textContent = '';
  $('formTitle').textContent = s ? 'Editar lista' : 'Agregar lista';
  for (const k of ['id', 'name', 'url', 'server', 'username', 'password', 'epgUrl']) f.elements[k].value = s?.[k] || '';
  setFormType(s?.type || 'm3u');
  $('formError').hidden = true;
  f.elements.name.focus();
}

async function submitSource(e) {
  e.preventDefault();
  const f = e.target, v = (k) => f.elements[k].value.trim();
  const type = f.querySelector('input[name=type]:checked').value;
  const src = { id: v('id') || 's' + Date.now().toString(36), name: v('name'), type, epgUrl: v('epgUrl') };
  try {
    if (type === 'm3u') {
      src.url = v('url');
      const file = f.elements.file.files[0];
      if (!src.url && !file && !v('id')) throw new Error('Indica la URL o carga un archivo');
      if (file) { src.url = ''; await store.idbSet(`m3u:${src.id}`, await file.text()); }
    } else {
      Object.assign(src, { server: v('server'), username: v('username'), password: f.elements.password.value });
      if (!src.server || !src.username) throw new Error('Completa servidor, usuario y contraseña');
    }
    if (!src.name) src.name = type === 'xtream' ? src.server.replace(/^https?:\/\//, '') : 'Mi lista';
    store.upsertSource(src);
    openSource(src);
  } catch (err) { $('formError').textContent = err.message; $('formError').hidden = false; }
}

/* ---------- pantalla de inicio: añadir listas ---------- */
const welcomeOpen = () => !$('welcome').hidden;

function showWelcome(on = true, focus = true) {
  $('welcome').hidden = !on;
  $('menu').inert = on;
  if (on) {
    renderWelcome();
    if (focus) ($('welcomeLists').querySelector('.current') || $('welcome').querySelector('[data-add]')).focus();
  } else if (focus && state.source) focusFirstTile();
}

function renderWelcome() {
  const list = store.getSources();
  $('welcomeBack').hidden = !state.source;
  $('welcomeSub').textContent = list.length ? 'Abre una de tus listas o agrega otra' : '¿Cómo quieres agregar tu lista?';
  $('welcomeSaved').hidden = !list.length;
  const box = $('welcomeLists');
  box.innerHTML = '';
  for (const s of list) {
    const b = document.createElement('button');
    b.className = 'saved-card' + (state.source?.id === s.id ? ' current' : '');
    b.dataset.nav = '';
    const kind = s.demo ? 'Demostración' : s.type === 'xtream' ? 'Xtream Codes' : s.url ? 'Lista M3U' : 'Archivo M3U';
    b.innerHTML = `<span class="saved-kind">${kind}${state.source?.id === s.id ? ' · abierta' : ''}</span><b>${esc(s.name)}</b><small>${esc(s.type === 'xtream' ? s.server : s.url || (s.demo ? 'Canales, películas y series de prueba' : 'Guardada en este equipo'))}</small>`;
    b.onclick = () => openSource(s);
    box.appendChild(b);
  }
  const n = $('welcomeNotice');
  n.hidden = !IS_ARTIFACT;
  if (IS_ARTIFACT) n.textContent = 'Vista previa: aquí sólo se reproducen los videos de demostración. Puedes cargar tu archivo M3U para ver cómo se ordena, pero los canales reales se reproducen en la app web local o en Android.';
}

function startAdd(kind) {
  openSheet('sourcesSheet', { mode: 'add', type: kind === 'xtream' ? 'xtream' : 'm3u' });
  if (kind === 'm3u-file') $('sourceForm').elements.file.click(); // abre el selector de archivos
}

/* ---------- vista / filtros ---------- */
function baseList() {
  if (state.series) return state.episodes;
  if (state.home) return state.items; // búsqueda desde el inicio: todo el contenido
  return state.items.filter((i) => i.kind === state.section);
}

const countOf = (k) => state.items.filter((i) => i.kind === k).length;

function enterSection(k) {
  closeSeries(false);
  state.home = false; state.section = k; state.category = ALL; state.sub = ''; state.query = '';
  renderTabs(); refresh(true); focusFirstTile();
}
function goHome() {
  closeSeries(false);
  const from = state.section;
  state.home = true; state.category = ALL; state.sub = ''; state.query = '';
  renderTabs(); refresh(true);
  ($('grid').querySelector(`.tile[data-home="${from}"]`) || $('grid').querySelector('.tile[data-home]'))?.focus();
}
function computeView() {
  if (state.home && !state.query) { state.view = []; return; }
  let list = baseList();
  if (state.query) {
    const q = norm(state.query);
    list = list.filter((i) => norm(i.name).includes(q));
  } else if (!state.series) {
    if (state.category === FAV) list = list.filter((i) => state.favs.has(i.id));
    else if (state.category !== ALL) {
      list = list.filter((i) => i.cat === state.category);
      if (state.sub) list = list.filter((i) => i.sub === state.sub);
    }
  }
  state.view = list;
}

function categoryLabel() {
  if (state.home && !state.query) return 'Inicio';
  if (state.query) return `Búsqueda: “${state.query}”`;
  if (state.series) return state.series.name;
  if (state.category === ALL) return `${SECTIONS[state.section]} · Todos`;
  if (state.category === FAV) return 'Favoritos';
  return state.sub ? `${state.category} · ${state.sub}` : state.category;
}

/* ---------- barra de categorías y subcategorías ---------- */
function selectCategory(cat, sub = '') {
  closeSeries(false);
  state.home = false; state.category = cat; state.sub = sub; state.query = '';
  renderTabs(); refresh(true);
}
function renderCatBar() {
  const bar = $('catBar');
  const show = !!state.source && !state.home && !state.series && !state.query;
  bar.hidden = !show;
  if (!show) return;
  const items = state.items.filter((i) => i.kind === state.section);
  const counts = new Map();
  for (const it of items) counts.set(it.cat, (counts.get(it.cat) || 0) + 1);
  const chip = (label, active, onclick, count) => {
    const b = document.createElement('button');
    b.className = 'chip' + (active ? ' active' : ''); b.dataset.nav = '';
    b.innerHTML = `${esc(label)}${count != null ? ` <small>${count}</small>` : ''}`;
    b.onclick = () => { onclick(); const again = bar.querySelector('.chip.active'); again?.focus(); };
    return b;
  };
  const row1 = $('catRow'), row2 = $('subRow');
  row1.innerHTML = ''; row2.innerHTML = '';
  row1.append(chip('Todos', state.category === ALL, () => selectCategory(ALL), items.length));
  const nFav = items.filter((i) => state.favs.has(i.id)).length;
  if (nFav) row1.append(chip('★ Favoritos', state.category === FAV, () => selectCategory(FAV), nFav));
  for (const [cat, n] of counts) row1.append(chip(cat, state.category === cat, () => selectCategory(cat), n));
  // Subcategorías de la categoría elegida (si la lista las trae)
  const subs = new Map();
  if (state.category !== ALL && state.category !== FAV) for (const it of items) if (it.cat === state.category && it.sub) subs.set(it.sub, (subs.get(it.sub) || 0) + 1);
  row2.hidden = subs.size === 0;
  if (subs.size) {
    row2.append(chip('Todas', !state.sub, () => selectCategory(state.category, '')));
    for (const [sub, n] of subs) row2.append(chip(sub, state.sub === sub, () => selectCategory(state.category, sub), n));
  }
  row1.querySelector('.active')?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
}

function refresh(resetPage = false) {
  computeView();
  if (resetPage) state.page = 0;
  $('dockTitle').textContent = state.source ? categoryLabel() : 'Bienvenido';
  renderCatBar();
  renderGrid();
}

function renderTabs() {
  const tabs = $('tabs');
  tabs.innerHTML = '';
  if (!state.source) return;
  const add = (label, active, onclick, count) => {
    const b = document.createElement('button');
    b.className = 'pill' + (active ? ' active' : '');
    b.dataset.nav = '';
    b.innerHTML = `${esc(label)}${count != null ? ` <small class="count">${count}</small>` : ''}`;
    b.onclick = onclick;
    tabs.appendChild(b);
  };
  add('Inicio', state.home, goHome);
  for (const k of Object.keys(SECTIONS)) add(SECTIONS[k], !state.home && k === state.section, () => enterSection(k), countOf(k));
}

function computeLayout() {
  const g = $('grid');
  if (state.home && !state.query && state.source) {
    const narrow = (g.clientWidth || window.innerWidth) < 560;
    state.cols = narrow ? 1 : 3; state.rows = narrow ? 3 : 1;
    g.style.setProperty('--cols', state.cols); g.style.setProperty('--rows', state.rows);
    return;
  }
  const w = g.clientWidth || window.innerWidth, h = g.clientHeight || window.innerHeight * 0.6;
  const poster = !state.home && state.section !== 'live' && !state.series;
  let cols = w < 480 ? 2 : w < 820 ? 3 : 4;
  if (poster) cols += w < 480 ? 1 : 2;
  const size = cfg().tileSize;
  if (size === 'large') cols = Math.max(2, cols - 1);
  if (size === 'small') cols += 1;
  const gap = parseFloat(getComputedStyle(g).columnGap) || 12;
  const tileW = (w - gap * (cols - 1)) / cols;
  const ratio = poster ? 1.45 : 0.56;
  const rows = Math.max(2, Math.min(5, Math.round((h + gap) / (tileW * ratio + gap)) || 2));
  state.cols = cols; state.rows = rows;
  g.style.setProperty('--cols', cols);
  g.style.setProperty('--rows', rows);
}

const perPage = () => state.cols * state.rows;
const pageCount = () => Math.max(1, Math.ceil(state.view.length / perPage()));

function tileFor(item, idxInView) {
  const b = document.createElement('button');
  b.className = 'tile';
  b.dataset.nav = '';
  b.dataset.idx = idxInView;
  const poster = item.kind !== 'live';
  if (poster && item.logo) b.classList.add('poster');
  const fav = state.favs.has(item.id) ? '<span class="fav">★</span>' : '';
  const num = item.kind === 'live' && item.num ? `<span class="num">${esc(item.num)}</span>` : '';
  b.innerHTML = `${num}${fav}${item.logo ? `<img loading="lazy" alt="" src="${esc(item.logo)}">` : `<span class="initials">${esc(initials(item.name))}</span>`}<span class="label">${esc(item.name)}</span>`;
  const img = b.querySelector('img');
  if (img) img.onerror = () => { b.classList.remove('poster'); img.replaceWith(Object.assign(document.createElement('span'), { className: 'initials', textContent: initials(item.name) })); };
  b.addEventListener('click', () => activate(idxInView));
  b.addEventListener('focus', () => updateFocusInfo(item));
  b.addEventListener('mouseenter', () => updateFocusInfo(item));
  b.addEventListener('contextmenu', (e) => { e.preventDefault(); toggleFavorite(item); });
  return b;
}

function renderGrid() {
  computeLayout();
  const g = $('grid');
  g.innerHTML = '';
  const empty = $('empty');
  if (!state.source) {
    empty.hidden = false;
    empty.innerHTML = '<div>Agrega tu primera lista M3U o cuenta Xtream Codes para empezar.</div><button class="btn" data-nav id="emptyAdd">Agregar lista</button>';
    $('emptyAdd').onclick = () => showWelcome(true);
    $('pgPrev').hidden = $('pgNext').hidden = true;
    $('pageInd').textContent = '';
    return;
  }
  g.classList.toggle('home', state.home && !state.query);
  if (state.home && !state.query) { renderHome(g); return; }
  if (!state.view.length && $('loader').hidden) {
    empty.hidden = false;
    empty.innerHTML = state.category === FAV ? '<div>No tienes favoritos aquí todavía.<br><small>Mantén pulsado (clic derecho) o usa ☆ en el reproductor para marcar uno.</small></div>' : '<div>No hay contenido para mostrar.</div>';
  } else empty.hidden = true;

  const pages = pageCount();
  state.page = Math.min(state.page, pages - 1);
  const start = state.page * perPage();
  const slice = state.view.slice(start, start + perPage());
  slice.forEach((it, i) => g.appendChild(tileFor(it, start + i)));
  if (state.view.length) for (let i = slice.length; i < perPage(); i++) {
    const e = document.createElement('div'); e.className = 'tile empty-slot'; g.appendChild(e);
  }
  $('pgPrev').hidden = state.page === 0;
  $('pgNext').hidden = state.page >= pages - 1;
  $('pageInd').textContent = pages > 1 ? `${state.page + 1} / ${pages}` : '';
}

function renderHome(g) {
  $('empty').hidden = true;
  $('pgPrev').hidden = $('pgNext').hidden = true;
  $('pageInd').textContent = '';
  for (const k of Object.keys(SECTIONS)) {
    const n = countOf(k);
    const b = document.createElement('button');
    b.className = 'tile home-tile';
    b.dataset.nav = '';
    b.dataset.home = k;
    b.innerHTML = `<span class="home-icon">${SECTION_ICONS[k]}</span><span class="home-name">${SECTIONS[k]}</span>
      <span class="home-count">${n ? `${n.toLocaleString(locale())} ${({ live: ['canal', 'canales'], movie: ['película', 'películas'], series: ['serie', 'series'] })[k][n === 1 ? 0 : 1]}` : 'Sin contenido en esta lista'}</span>`;
    b.onclick = () => enterSection(k);
    const info = () => { $('fiName').textContent = SECTIONS[k]; $('fiEpg').textContent = n ? `${[...new Set(state.items.filter((i) => i.kind === k).map((i) => i.group))].length} categorías` : ''; };
    b.addEventListener('focus', info);
    b.addEventListener('mouseenter', info);
    g.appendChild(b);
  }
}

function goPage(delta, focusPos = null) {
  const np = state.page + delta;
  if (np < 0 || np >= pageCount()) return false;
  state.page = np;
  renderGrid();
  const tiles = [...$('grid').querySelectorAll('.tile[data-idx]')];
  if (focusPos != null && tiles.length) tiles[Math.min(focusPos, tiles.length - 1)].focus();
  return true;
}

function focusFirstTile() { $('grid').querySelector('.tile[data-idx], .tile[data-home]')?.focus(); }
function focusItem(item) {
  const idx = state.view.indexOf(item);
  if (idx < 0) return focusFirstTile();
  state.page = Math.floor(idx / perPage());
  renderGrid();
  $('grid').querySelector(`.tile[data-idx="${idx}"]`)?.focus();
}

function updateFocusInfo(item) {
  if (!item) { const el = document.activeElement; item = el?.dataset?.idx != null ? state.view[+el.dataset.idx] : null; }
  $('fiName').textContent = item?.name || '';
  let extra = '';
  if (item?.kind === 'live') {
    const { now, next } = nowNext(item);
    if (now) extra = `Ahora: ${now.title} (${pTime(now.start)}–${pTime(now.stop)})${next ? ` · Luego: ${next.title}` : ''}`;
  } else if (item) extra = item.group || '';
  $('fiEpg').textContent = extra;
}

async function activate(idx) {
  const item = state.view[idx];
  if (!item) return;
  if (item.kind === 'series') {
    loader(true, 'Cargando episodios…');
    try {
      state.episodes = item.episodes || (state.xt ? await state.xt.episodes(item.seriesId) : []);
      state.series = item;
      state.query = '';
      $('tabs').hidden = true; $('crumb').hidden = false; $('crumbTitle').textContent = item.name;
      refresh(true); focusFirstTile();
    } catch (e) { toast(e.message); } finally { loader(false); }
    return;
  }
  openPlayer(item, state.view.filter((i) => i.kind === item.kind), state.view.filter((i) => i.kind === item.kind).indexOf(item));
}

function closeSeries(rerender = true) {
  if (!state.series) return;
  const s = state.series;
  state.series = null; state.episodes = [];
  $('tabs').hidden = false; $('crumb').hidden = true;
  if (rerender) { refresh(); focusItem(s); }
}

function toggleFavorite(item) {
  if (!state.source || !item) return;
  state.favs = store.toggleFav(state.source.id, item.id);
  toast(state.favs.has(item.id) ? `★ ${item.name} añadido a favoritos` : `${item.name} quitado de favoritos`, 2000);
  if (!$('playerView').hidden) updateFavBtn();
  else { const cur = document.activeElement?.dataset?.idx; refresh(); if (cur != null) $('grid').querySelector(`.tile[data-idx="${cur}"]`)?.focus(); }
}

/* ---------- categorías / búsqueda ---------- */
function renderCategories() {
  const box = $('catList');
  box.innerHTML = '';
  if (state.home) {
    for (const k of Object.keys(SECTIONS)) {
      const b = document.createElement('button');
      b.className = 'pill'; b.dataset.nav = '';
      b.innerHTML = `${SECTIONS[k]} <small class="count">${countOf(k)}</small>`;
      b.onclick = () => { closeSheets(false); enterSection(k); };
      box.appendChild(b);
    }
    return;
  }
  const groups = [...new Set(baseList().map((i) => i.cat))];
  const add = (val, label) => {
    const b = document.createElement('button');
    b.className = 'pill' + (state.category === val && !state.query ? ' active' : '');
    b.dataset.nav = '';
    b.textContent = label;
    b.onclick = () => { closeSheets(); selectCategory(val); focusFirstTile(); };
    box.appendChild(b);
  };
  add(ALL, 'Todos');
  add(FAV, '★ Favoritos');
  groups.forEach((g) => add(g, g));
}

let searchTimer;
function onSearchInput(e) {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => { state.query = e.target.value.trim(); refresh(true); }, 250);
}

/* ---------- paneles ---------- */
let sheetReturnFocus = null;
function openSheet(id, opts = {}) {
  closeSheets(false);
  sheetReturnFocus = document.activeElement;
  if (id === 'sourcesSheet') {
    const adding = opts.mode === 'add';
    $('sourcesTitle').textContent = adding ? (opts.type === 'xtream' ? 'Cuenta Xtream Codes' : 'Lista M3U') : 'Mis listas';
    $('sourcesManage').hidden = adding;
    $('sourcesSheet').querySelector('.seg').hidden = adding;
    $('formTitle').hidden = adding;
    renderSourceList(); fillForm(null);
    if (opts.type) setFormType(opts.type);
  }
  if (id === 'catSheet') renderCategories();
  if (id === 'subsSheet') renderSubsSheet();
  $('backdrop').hidden = false;
  $(id).hidden = false;
  if (id === 'searchSheet') { $('searchInput').value = state.query; $('searchInput').focus(); }
  else if (id !== 'sourcesSheet') $(id).querySelector('[data-nav]')?.focus();
  else if (opts.mode === 'add') {
    const f = $('sourceForm');
    (opts.type === 'xtream' ? f.elements.server : f.elements.url).focus();
  }
}
function openSheetId() { return [...document.querySelectorAll('.sheet')].find((s) => !s.hidden)?.id; }
function closeSheets(restore = true) {
  document.querySelectorAll('.sheet').forEach((s) => (s.hidden = true));
  $('backdrop').hidden = true;
  if (restore && sheetReturnFocus?.isConnected) sheetReturnFocus.focus();
}

/* ---------- reproductor ---------- */
const video = $('video');
const player = new Player(video, {
  onError: (m) => showPlayError(m),
  onStatus: (m) => { $('pStatus').hidden = !m; $('pStatus').textContent = m; },
  onInfo: () => refreshPlayerButtons(),
});
let osdTimer;

function openPlayer(item, list, index) {
  state.playList = list; state.playIndex = index;
  $('playerView').hidden = false;
  $('menu').inert = true;
  playCurrent();
}

function playCurrent() {
  const item = state.playList[state.playIndex];
  if (!item) return;
  $('pError').hidden = true;
  state.playing = item;
  $('pName').textContent = item.name;
  $('pGroup').textContent = item.group || '';
  $('pLogo').src = item.logo || '';
  $('pLogo').hidden = !item.logo;
  $('pSeekWrap').hidden = item.kind === 'live';
  $('playerView').classList.toggle('is-live', item.kind === 'live');
  $('pPrev').hidden = $('pNext').hidden = state.playList.length < 2;
  closePlayerMenu();
  if (!$('pListPanel').hidden) renderChannelList();
  video.playbackRate = 1;
  refreshPlayerButtons();
  updateFavBtn();
  renderPlayerEpg();
  store.saveLast({ itemId: item.id });
  if ('mediaSession' in navigator) navigator.mediaSession.metadata = new MediaMetadata({ title: item.name, artist: item.group, artwork: item.logo ? [{ src: item.logo }] : [] });
  player.play(item);
  showOsd();
}

/* ---------- diagnóstico cuando un canal no se reproduce ---------- */
const CODEC_TEST = {
  'H.264': 'video/mp4; codecs="avc1.42E01E"', 'H.265': 'video/mp4; codecs="hvc1.1.6.L93.B0"',
  'MPEG-2': null, 'MPEG-1': null, 'MPEG-4': null,
  AAC: 'audio/mp4; codecs="mp4a.40.2"', 'AAC-LATM': null, MP2: 'audio/mpeg', 'AC-3': 'audio/mp4; codecs="ac-3"', 'E-AC-3': 'audio/mp4; codecs="ec-3"',
};
const canPlay = (c) => { const t = CODEC_TEST[c]; try { return !!t && MediaSource.isTypeSupported(t); } catch { return false; } };

function explainProbe(r) {
  if (!r) return null;
  if (r.error) return r.error;
  if (r.status === 401 || r.status === 403) return `El servidor rechazó el canal (${r.status}). Suele pasar cuando la cuenta ya se está usando en otro equipo (TV, celular, VLC) o no incluye este canal.`;
  if (r.status === 404) return 'El canal no existe en el servidor (404). Puede que la lista esté desactualizada.';
  if (r.status >= 500) return `El servidor del canal falló (${r.status}). Suele ser un corte momentáneo del proveedor.`;
  if (r.status >= 400) return `El servidor respondió con error ${r.status}.`;
  if (r.kind === 'html' || r.kind === 'json') return 'El servidor respondió una página en lugar del video: el canal puede estar caído o la cuenta sin acceso a él.';
  if (r.kind === 'm3u8') {
    if (r.encrypted) return 'El canal está cifrado (DRM) y no se puede reproducir.';
    if (r.next) return explainProbe(r.next);
    return 'La lista del canal está vacía.';
  }
  if (r.kind === 'ts' && r.codecs) {
    const bad = [...r.codecs.video, ...r.codecs.audio].filter((c) => !canPlay(c));
    if (bad.length) return `El canal usa ${[...r.codecs.video.map((c) => 'video ' + c), ...r.codecs.audio.map((c) => 'audio ' + c)].join(' y ')}. Este navegador no reproduce ${bad.join(' ni ')}. Ábrelo en VLC (botón Copiar enlace) o en la app Android, que sí lo soportan.`;
    return 'El canal responde y su formato es compatible: puede ser un corte momentáneo. Prueba Reintentar.';
  }
  return null;
}

async function diagnose(item) {
  if (!proxyBase() || !item) return null;
  const urls = [item.url, altFormat(item.url)].filter(Boolean);
  let first = null;
  for (const u of urls) {
    try {
      const probeUrl = proxify(u, item).replace('/proxy?', '/proxy/probe?');
      const r = await (await fetch(probeUrl)).json();
      const why = explainProbe(r);
      first ||= why;
      if (r.status && r.status < 400 && why) return why;   // el formato que sí responde explica mejor
    } catch { /* sin diagnóstico */ }
  }
  return first;
}

async function showPlayError(msg) {
  const it = state.playing;
  $('pErrTitle').textContent = 'No se pudo reproducir';
  $('pErrText').textContent = redact(msg) + ' · Revisando el canal…';
  $('pError').hidden = false;
  showOsd();
  $('pRetry').focus();
  const why = await diagnose(it);
  if (state.playing !== it) return;
  $('pErrText').textContent = why || redact(msg);
}

function zap(delta) {
  if (state.playList.length < 2) return;
  state.playIndex = (state.playIndex + delta + state.playList.length) % state.playList.length;
  playCurrent();
}

function closePlayer() {
  player.destroy();
  closePlayerMenu(); $('pListPanel').hidden = true;
  if (!$('pInfo').hidden) toggleInfo();
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  $('playerView').hidden = true;
  $('menu').inert = false;
  const it = state.playing;
  state.playing = null;
  if (it) focusItem(it);
}

function updateFavBtn() {
  const on = state.playing && state.favs.has(state.playing.id);
  $('pFav').classList.toggle('on', !!on);
  $('pFav').dataset.tip = on ? 'Quitar de favoritos' : 'Agregar a favoritos';
}

/* ---------- controles del reproductor: menús, lista de canales, información ---------- */
const pv = () => $('playerView');
const playerPanelOpen = () => !$('pMenu').hidden || !$('pListPanel').hidden;

function refreshPlayerButtons() {
  $('pAudio').hidden = player.audioTracks().length < 2;
  $('pQuality').hidden = player.levels().length < 2;
  $('pPip').hidden = !document.pictureInPictureEnabled;
  if (!$('pInfo').hidden) renderInfo();
}

function openPlayerMenu(title, options, onPick, extra = []) {
  // options: [{label, active, value}] ; extra: [{label, run}] acciones al final
  $('pMenuTitle').textContent = title;
  const list = $('pMenuList');
  list.innerHTML = '';
  if (!options.length && !extra.length) list.innerHTML = '<p class="empty">No hay opciones para este canal.</p>';
  for (const o of options) {
    const b = document.createElement('button');
    b.className = o.active ? 'on' : ''; b.dataset.nav = '';
    b.innerHTML = `<span>${esc(o.label)}</span><i aria-hidden="true">✓</i>`;
    b.onclick = () => { onPick(o.value); closePlayerMenu(); showOsd(); };
    list.appendChild(b);
  }
  if (options.length && extra.length) list.insertAdjacentHTML('beforeend', '<div class="sep"></div>');
  for (const x of extra) {
    const b = document.createElement('button');
    b.dataset.nav = ''; b.innerHTML = `<span>${esc(x.label)}</span>`;
    b.onclick = () => { if (!x.keep) closePlayerMenu(); x.run(); };
    list.appendChild(b);
  }
  $('pMenu').hidden = false;
  showOsd();
  (list.querySelector('button.on') || list.querySelector('button'))?.focus();
}
function closePlayerMenu() { $('pMenu').hidden = true; }

function menuSubtitles() {
  const tracks = subs.listTracks(video);
  const opts = [{ label: 'Desactivados', active: tracks.every((t) => t.mode !== 'showing'), value: -1 },
    ...tracks.map((t, i) => ({ label: t.label || t.language || `Pista ${i + 1}`, active: t.mode === 'showing', value: i }))];
  const shift = subs.currentShift(video);
  const fmtShift = (x) => (x === 0 ? 'sin desfase' : `${x > 0 ? '+' : ''}${x.toFixed(1).replace('.', ',')} s`);
  const showing = tracks.some((t) => t.mode === 'showing');
  openPlayerMenu('Subtítulos', opts, (i) => subs.selectTrack(video, i < 0 ? null : tracks[i]), [
    ...(showing ? [
      { label: `Aparecen antes (−0,5 s) · ahora ${fmtShift(shift)}`, keep: true, run: () => { subs.shiftSubtitles(video, -0.5); menuSubtitles(); } },
      { label: 'Aparecen después (+0,5 s)', keep: true, run: () => { subs.shiftSubtitles(video, 0.5); menuSubtitles(); } },
    ] : []),
    { label: 'Buscar subtítulos (varias fuentes)…', run: () => openSheet('subsSheet') },
    { label: 'Cargar archivo .srt / .vtt…', run: () => $('subsFile').click() },
  ]);
}
function menuAudio() {
  openPlayerMenu('Audio', player.audioTracks().map((t) => ({ label: t.label, active: t.active, value: t.id })), (id) => player.setAudioTrack(id));
}
function menuQuality() {
  openPlayerMenu('Calidad', player.levels().map((l) => ({ label: l.label, active: l.active, value: l.id })), (id) => player.setLevel(id));
}
const ASPECTS = [['fit', 'Ajustar (sin recortar)'], ['fill', 'Rellenar pantalla'], ['stretch', 'Estirar'], ['zoom', 'Zoom']];
function menuAspect() {
  const cur = cfg().aspect;
  openPlayerMenu('Formato de pantalla', ASPECTS.map(([v, l]) => ({ label: l, active: v === cur, value: v })), (v) => {
    store.saveSettings({ aspect: v }); pv().dataset.aspect = v;
  });
}
function menuSpeed() {
  const cur = video.playbackRate;
  openPlayerMenu('Velocidad', [0.5, 0.75, 1, 1.25, 1.5, 2].map((r) => ({ label: r === 1 ? 'Normal' : `${r}×`, active: r === cur, value: r })), (r) => (video.playbackRate = r));
}

function renderChannelList() {
  const box = $('pListItems');
  const live = state.playing?.kind === 'live';
  $('pListTitle').textContent = live ? 'Canales' : state.series ? 'Episodios' : 'Lista';
  box.innerHTML = '';
  state.playList.forEach((it, i) => {
    const b = document.createElement('button');
    b.className = 'plist-item' + (i === state.playIndex ? ' current' : '');
    b.dataset.nav = '';
    const { now } = it.kind === 'live' ? nowNext(it) : {};
    b.innerHTML = `<span class="n">${it.num ?? i + 1}</span><span class="lg">${it.logo ? `<img src="${esc(it.logo)}" alt="" loading="lazy">` : esc(initials(it.name))}</span>
      <span class="t"><b>${esc(it.name)}</b><small>${esc(now ? `Ahora: ${now.title}` : it.group || '')}</small></span>`;
    b.querySelector('img')?.addEventListener('error', (e) => { e.target.replaceWith(document.createTextNode(initials(it.name))); });
    b.onclick = () => { state.playIndex = i; playCurrent(); renderChannelList(); };
    box.appendChild(b);
  });
}
function openChannelList() {
  closePlayerMenu();
  renderChannelList();
  $('pListPanel').hidden = false;
  showOsd();
  const cur = $('pListItems').querySelector('.current') || $('pListItems').querySelector('button');
  cur?.focus(); cur?.scrollIntoView({ block: 'center' });
}
function closeChannelList() { $('pListPanel').hidden = true; $('pList').focus(); }

let infoTimer;
function renderInfo() {
  const st = player.stats();
  const it = state.playing;
  if (it) st.Tipo = it.kind === 'live' ? 'TV en vivo' : it.season != null ? 'Episodio' : 'Película';
  $('pInfoList').innerHTML = Object.entries(st).map(([k, v]) => `<dt>${esc(k)}</dt><dd>${esc(v)}</dd>`).join('');
}
function toggleInfo() {
  const show = $('pInfo').hidden;
  $('pInfo').hidden = !show;
  clearInterval(infoTimer);
  if (show) { renderInfo(); infoTimer = setInterval(renderInfo, 1000); }
  $('pInfoBtn').classList.toggle('on', show);
}

async function copyForVlc() {
  const url = state.playing?.url || '';
  try { await navigator.clipboard.writeText(url); toast('Enlace copiado: en VLC usa Medio → Abrir ubicación de red y pégalo', 5000); }
  catch { prompt(t('Copia este enlace y pégalo en VLC (Medio → Abrir ubicación de red):'), url); }
}

function setVolume(v) {
  video.volume = Math.max(0, Math.min(1, v));
  if (video.volume > 0) video.muted = false;
}

function renderPlayerEpg() {
  const it = state.playing;
  const box = $('pEpg');
  if (!it || it.kind !== 'live') { box.innerHTML = ''; return; }
  const { now, next } = nowNext(it);
  if (!now) { box.innerHTML = next ? `<div class="next">Luego: ${esc(next.title)} · ${pTime(next.start)}</div>` : ''; return; }
  const pct = Math.max(0, Math.min(100, ((Date.now() - epgShift() - now.start) / (now.stop - now.start)) * 100));
  box.innerHTML = `<div class="now">${esc(now.title)} <small>${pTime(now.start)}–${pTime(now.stop)}</small></div>
    <div class="bar"><i style="width:${pct}%"></i></div>
    ${next ? `<div class="next">Luego: ${esc(next.title)} · ${pTime(next.start)}</div>` : ''}`;
}

const osdVisible = () => !$('playerView').classList.contains('idle');
function showOsd(focusCtl = false) {
  $('playerView').classList.remove('idle');
  if (focusCtl && !$('osd').contains(document.activeElement)) $('pPlay').focus();
  clearTimeout(osdTimer);
  osdTimer = setTimeout(hideOsd, 4500);
}
function hideOsd() {
  if (video.paused && video.currentTime > 0) return;
  if (openSheetId() || playerPanelOpen()) return;
  $('playerView').classList.add('idle');
  if ($('osd').contains(document.activeElement)) document.activeElement.blur();
}

const fmtDur = (s) => { s = Math.floor(s || 0); const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60; return (h ? h + ':' : '') + String(m).padStart(h ? 2 : 1, '0') + ':' + String(x).padStart(2, '0'); };
video.addEventListener('ended', () => {
  const it = state.playing;
  if (cfg().autoNext && it?.season != null && it?.episode != null && state.playIndex < state.playList.length - 1) {
    toast(`Siguiente: ${state.playList[state.playIndex + 1].name}`, 2500);
    zap(1);
  }
});
video.addEventListener('timeupdate', () => {
  if (!isFinite(video.duration)) return;
  if (document.activeElement !== $('pSeek')) $('pSeek').value = (video.currentTime / video.duration) * 1000 || 0;
  $('pTimeCur').textContent = fmtDur(video.currentTime);
  $('pTime').textContent = fmtDur(video.duration);
});
video.addEventListener('play', () => pv().classList.add('playing'));
video.addEventListener('pause', () => { pv().classList.remove('playing'); showOsd(); });
video.addEventListener('volumechange', () => {
  pv().classList.toggle('muted', video.muted || video.volume === 0);
  if (document.activeElement !== $('pVol')) $('pVol').value = Math.round((video.muted ? 0 : video.volume) * 100);
  store.saveSettings({ volume: video.volume, muted: video.muted });
});
document.addEventListener('fullscreenchange', () => pv().classList.toggle('fs', !!document.fullscreenElement));
$('pSeek').addEventListener('input', (e) => { if (isFinite(video.duration)) video.currentTime = (e.target.value / 1000) * video.duration; });

function toggleFullscreen() {
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  else $('playerView').requestFullscreen?.().then(() => screen.orientation?.lock?.('landscape').catch(() => {})).catch(() => {});
}

/* ---------- subtítulos ---------- */
function renderSubsSheet() {
  const box = $('subsTracks');
  box.innerHTML = '';
  const tracks = subs.listTracks(video);
  const add = (label, track) => {
    const b = document.createElement('button');
    const active = track ? track.mode === 'showing' : tracks.every((t) => t.mode !== 'showing');
    b.className = 'pill' + (active ? ' active' : '');
    b.dataset.nav = '';
    b.textContent = label;
    b.onclick = () => { subs.selectTrack(video, track); renderSubsSheet(); };
    box.appendChild(b);
  };
  add('Desactivados', null);
  tracks.forEach((t, i) => add(t.label || t.language || `Pista ${i + 1}`, t));
  $('subsQuery').value = state.playing ? subs.cleanTitle(state.playing.seriesName || state.playing.name) : '';
  $('subsResults').innerHTML = proxyBase() || store.getSettings().osApiKey ? '' : '<p class="hint">En esta vista sólo se puede buscar en OpenSubtitles con tu API key (Ajustes → Subtítulos).</p>';
}

async function searchSubs() {
  const box = $('subsResults');
  box.innerHTML = '<p class="hint">Buscando…</p>';
  try {
    const { results, errors } = await subs.searchSubtitles(state.playing || { name: '' }, $('subsQuery').value.trim());
    box.innerHTML = results.length ? '' : '<p class="hint">Sin resultados. Prueba con el título original (en inglés) o sin el año.</p>';
    results.slice(0, 60).forEach((r) => {
      const b = document.createElement('button');
      b.className = 'sub-result'; b.dataset.nav = '';
      const text = () => `<span class="lang">${esc((r.lang || '?').toUpperCase())}</span><span class="name">${esc(r.label)}</span><span class="src">${esc(r.source)}${r.downloads ? ` · ⬇ ${r.downloads}` : ''}</span>`;
      b.innerHTML = text();
      b.onclick = async () => {
        b.disabled = true; b.querySelector('.name').textContent = 'Descargando…';
        try { subs.attachVtt(video, await subs.downloadSubtitle(r), `${r.source}: ${r.label}`.slice(0, 48), r.lang); toast('Subtítulo cargado'); closeSheets(); }
        catch (e) { toast(`${r.source}: ${e.message}`, 5000); b.disabled = false; b.innerHTML = text(); }
      };
      box.appendChild(b);
    });
    const errs = Object.entries(errors || {});
    if (errs.length) box.insertAdjacentHTML('beforeend', `<p class="hint">${errs.map(([k, v]) => `${esc(k)}: ${esc(v)}`).join(' · ')}</p>`);
  } catch (e) { box.innerHTML = `<p class="error">${esc(e.message)}</p>`; }
}

/* ---------- teclado / mando ---------- */
function handleGridKey(e, dir) {
  const el = document.activeElement;
  if (!el?.classList?.contains('tile') || el.dataset.idx == null) return false;
  const idx = +el.dataset.idx, pos = idx - state.page * perPage();
  const col = pos % state.cols, row = Math.floor(pos / state.cols);
  const onPage = $('grid').querySelectorAll('.tile[data-idx]').length;
  const focusPos = (p) => $('grid').querySelectorAll('.tile[data-idx]')[p]?.focus();
  if (dir === 'right') {
    if (col < state.cols - 1 && pos + 1 < onPage) focusPos(pos + 1);
    else goPage(1, row * state.cols);
    return true;
  }
  if (dir === 'left') {
    if (col > 0) focusPos(pos - 1);
    else goPage(-1, row * state.cols + state.cols - 1);
    return true;
  }
  if (dir === 'down' && pos + state.cols < onPage) { focusPos(pos + state.cols); return true; }
  if (dir === 'down' && row < state.rows - 1 && Math.floor((onPage - 1) / state.cols) > row) { focusPos(onPage - 1); return true; }
  if (dir === 'up' && row > 0) { focusPos(pos - state.cols); return true; }
  return false; // salir de la cuadrícula: navegación espacial
}

document.addEventListener('keydown', (e) => {
  if (handleSettingsKey(e)) return;
  const dir = keyDir(e);
  const sheet = openSheetId();
  const inText = e.target.matches?.('input[type=text], input[type=search], input:not([type]), input[type=password]');

  if (sheet) {
    if (isBack(e) && !(inText && e.key === 'Backspace')) { e.preventDefault(); closeSheets(); return; }
    if (dir && !(inText && (dir === 'left' || dir === 'right'))) {
      if (dir === 'down' && sheet === 'searchSheet' && inText) { e.preventDefault(); closeSheets(false); focusFirstTile(); return; }
      e.preventDefault(); moveFocus(dir, $(sheet)); return;
    }
    if (isOk(e) && e.target.matches('.seg span, .file-btn')) { e.preventDefault(); e.target.click(); }
    return;
  }

  if (!$('playerView').hidden) {
    // Menú emergente o lista de canales abiertos: navegar dentro de ellos
    const panel = !$('pMenu').hidden ? $('pMenu') : !$('pListPanel').hidden ? $('pListPanel') : null;
    if (panel) {
      if (isBack(e) || (panel === $('pListPanel') && dir === 'right')) { e.preventDefault(); panel === $('pMenu') ? (closePlayerMenu(), showOsd(true)) : closeChannelList(); return; }
      if (dir === 'up' || dir === 'down') {
        e.preventDefault();
        const items = [...panel.querySelectorAll('[data-nav]')];
        const i = items.indexOf(document.activeElement);
        const n = items[Math.max(0, Math.min(items.length - 1, i + (dir === 'down' ? 1 : -1)))];
        n?.focus(); n?.scrollIntoView({ block: 'nearest' });
        return;
      }
      if (dir) { e.preventDefault(); return; }
      showOsd();
      return;
    }
    if (isBack(e)) {
      e.preventDefault();
      if (!$('pInfo').hidden) toggleInfo();
      else if (osdVisible() && $('osd').contains(document.activeElement)) hideOsd(); else closePlayer();
      return;
    }
    const live = state.playing?.kind === 'live';
    if (e.key === 'm' || e.key === 'AudioVolumeMute') { e.preventDefault(); video.muted = !video.muted; showOsd(); return; }
    if (e.key === 'i' || e.key === 'Info' || e.keyCode === 457) { e.preventDefault(); toggleInfo(); return; }
    if (e.key === 'l' || e.key === 'Guide' || e.keyCode === 458) { e.preventDefault(); openChannelList(); return; }
    if (e.key === '+' || e.key === 'AudioVolumeUp') { setVolume(video.volume + 0.1); showOsd(); return; }
    if (e.key === '-' || e.key === 'AudioVolumeDown') { setVolume(video.volume - 0.1); showOsd(); return; }
    if (live && !osdVisible() && dir === 'left') { e.preventDefault(); openChannelList(); return; }
    if (!osdVisible() || !$('osd').contains(document.activeElement)) {
      if (dir === 'up' || dir === 'down') { e.preventDefault(); if (live) zap(dir === 'up' ? -1 : 1); else showOsd(true); return; }
      if (dir === 'left' || dir === 'right') { e.preventDefault(); if (!live) video.currentTime += dir === 'left' ? -10 : 10; showOsd(); return; }
      if (isOk(e)) { e.preventDefault(); showOsd(true); return; }
    } else if (dir) {
      if ((e.target === $('pSeek') || e.target === $('pVol')) && (dir === 'left' || dir === 'right')) { showOsd(); return; }
      e.preventDefault(); moveFocus(dir, $('osd')); showOsd(); return;
    }
    if (e.key === ' ' || e.key === 'MediaPlayPause') { e.preventDefault(); video.paused ? video.play() : video.pause(); showOsd(); }
    if (e.key === 'PageUp' || e.keyCode === 427) zap(-1);
    if (e.key === 'PageDown' || e.keyCode === 428) zap(1);
    if (e.key === 'f') toggleFullscreen();
    showOsd();
    return;
  }

  if (welcomeOpen()) {
    if (isBack(e)) { if (state.source) { e.preventDefault(); showWelcome(false); } return; }
    if (dir) { e.preventDefault(); moveFocus(dir, $('welcome')); }
    return;
  }

  // Menú principal
  if (isBack(e)) {
    if (state.series) { e.preventDefault(); closeSeries(); }
    else if (state.sub) { e.preventDefault(); selectCategory(state.category); focusFirstTile(); }
    else if (state.query || state.category !== ALL) { e.preventDefault(); state.query = ''; selectCategory(ALL); focusFirstTile(); }
    else if (!state.home) { e.preventDefault(); goHome(); }
    return;
  }
  if (dir) {
    e.preventDefault();
    if (!handleGridKey(e, dir)) moveFocus(dir);
    return;
  }
  if (e.key === 'PageDown' || e.keyCode === 428) goPage(1, 0);
  if (e.key === 'PageUp' || e.keyCode === 427) goPage(-1, 0);
  if (e.key === '/' ) { e.preventDefault(); openSheet('searchSheet'); }
  if (e.key === 'f' || e.key === 'ColorF2Yellow' || e.keyCode === 405) {
    const el = document.activeElement;
    if (el?.dataset?.idx != null) toggleFavorite(state.view[+el.dataset.idx]);
  }
});

/* ---------- eventos ---------- */
$('btnSources').onclick = () => showWelcome(true);
document.querySelectorAll('[data-add]').forEach((b) => (b.onclick = () => startAdd(b.dataset.add)));
$('welcomeManage').onclick = () => openSheet('sourcesSheet', { mode: 'manage' });
$('welcomeBack').onclick = () => showWelcome(false);
$('sourceForm').elements.file.addEventListener('change', (e) => {
  const f = e.target.files[0];
  $('fileName').textContent = f ? `✓ ${f.name}` : '';
  if (f && !$('sourceForm').elements.name.value) $('sourceForm').elements.name.value = f.name.replace(/\.(m3u8?|txt)$/i, '');
});
$('btnSettings').onclick = () => openSettings();
$('welcomeSettings').onclick = () => openSettings();
$('btnCategories').onclick = () => state.source ? openSheet('catSheet') : showWelcome(true);
$('btnSearch').onclick = () => openSheet('searchSheet');
$('btnCrumbBack').onclick = () => closeSeries();
$('backdrop').onclick = () => closeSheets();
$('pgPrev').onclick = () => goPage(-1);
$('pgNext').onclick = () => goPage(1);
$('searchInput').addEventListener('input', onSearchInput);
$('searchInput').addEventListener('keydown', (e) => { if (isOk(e)) { closeSheets(false); focusFirstTile(); } });
$('sourceForm').addEventListener('submit', submitSource);
$('sourceForm').addEventListener('change', (e) => { if (e.target.name === 'type') setFormType(e.target.value); });
$('btnFormReset').onclick = () => fillForm(null);

$('pBack').onclick = closePlayer;
$('pRetry').onclick = () => playCurrent();
$('pCopy').onclick = copyForVlc;
$('pVlc').onclick = copyForVlc;
$('pRew').onclick = () => { video.currentTime = Math.max(0, video.currentTime - 10); showOsd(); };
$('pFwd').onclick = () => { video.currentTime += 10; showOsd(); };
$('pMute').onclick = () => { video.muted = !video.muted; if (!video.muted && video.volume === 0) video.volume = 0.5; };
$('pVol').addEventListener('input', (e) => setVolume(e.target.value / 100));
$('pList').onclick = openChannelList;
$('pListClose').onclick = closeChannelList;
$('pAudio').onclick = menuAudio;
$('pQuality').onclick = menuQuality;
$('pAspect').onclick = menuAspect;
$('pSpeed').onclick = menuSpeed;
$('pInfoBtn').onclick = toggleInfo;
$('pPip').onclick = async () => {
  try { document.pictureInPictureElement ? await document.exitPictureInPicture() : await video.requestPictureInPicture(); }
  catch { toast('Este navegador no permite imagen en imagen aquí'); }
};
video.volume = cfg().volume ?? 1; video.muted = !!cfg().muted;
$('pVol').value = Math.round((video.muted ? 0 : video.volume) * 100);
pv().classList.toggle('muted', video.muted);
pv().dataset.aspect = cfg().aspect || 'fit';
$('pPrev').onclick = () => zap(-1);
$('pNext').onclick = () => zap(1);
$('pPlay').onclick = () => (video.paused ? video.play() : video.pause());
$('pFav').onclick = () => toggleFavorite(state.playing);
$('pFull').onclick = toggleFullscreen;
$('pSubs').onclick = menuSubtitles;
$('subsSearch').onclick = searchSubs;
$('subsQuery').addEventListener('keydown', (e) => { if (isOk(e)) { e.preventDefault(); searchSubs(); } });
$('subsFile').addEventListener('change', async (e) => {
  const f = e.target.files[0];
  if (!f) return;
  subs.attachVtt(video, await subs.readLocalFile(f), f.name);
  toast('Subtítulo cargado'); closeSheets();
});
$('playerView').addEventListener('mousemove', () => showOsd());
$('playerView').addEventListener('click', (e) => {
  if (e.target !== video) return;
  if (playerPanelOpen()) { closePlayerMenu(); if (!$('pListPanel').hidden) $('pListPanel').hidden = true; return; }
  osdVisible() ? hideOsd() : showOsd();
});
video.addEventListener('dblclick', toggleFullscreen);

// Rueda y gestos para paginar
let wheelLock = 0;
$('stage').addEventListener('wheel', (e) => {
  if (Date.now() < wheelLock) return;
  const d = Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.deltaY;
  if (Math.abs(d) < 20) return;
  if (goPage(d > 0 ? 1 : -1)) wheelLock = Date.now() + 350;
}, { passive: true });
let touchX = null;
$('stage').addEventListener('touchstart', (e) => (touchX = e.touches[0].clientX), { passive: true });
$('stage').addEventListener('touchend', (e) => {
  if (touchX == null) return;
  const dx = e.changedTouches[0].clientX - touchX;
  if (Math.abs(dx) > 50) goPage(dx < 0 ? 1 : -1);
  touchX = null;
});

let resizeTimer;
window.addEventListener('resize', () => {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(() => {
    const first = state.page * perPage();
    renderGrid();
    state.page = Math.floor(first / perPage());
    renderGrid();
  }, 150);
});

setInterval(() => { tickClock(); if (!$('playerView').hidden) renderPlayerEpg(); }, 15000);

/* ---------- inicio ---------- */
const settingsCtx = {
  caps: null,
  state: () => state,
  showWelcome: () => showWelcome(true),
  refreshEpg: () => refreshEpg(),
  toast,
  apply: (key) => applySettings(key),
  clearEpgCache: async () => { for (const s of store.getSources()) await store.idbDel(`epg:${s.id}`).catch(() => {}); },
  clearFavorites: () => { if (!state.source) return; for (const id of [...state.favs]) store.toggleFav(state.source.id, id); state.favs = new Set(); refresh(); },
};
initSettings(settingsCtx);
applySettings();

// Pantalla de inicio: se muestra al menos ~1,5 s y se oculta cuando la lista ya cargó (máx. 8 s)
const splashStart = performance.now();
let splashGone = false;
function hideSplash() {
  if (splashGone) return; splashGone = true;
  const el = document.getElementById('splash'); if (!el) return;
  setTimeout(() => { el.classList.add('out'); setTimeout(() => el.remove(), 450); }, Math.max(0, 1500 - (performance.now() - splashStart)));
}
setTimeout(hideSplash, 8000);

(async function init() {
  // ¿Nos sirve server.js? Entonces el proxy está disponible en este mismo origen
  if (!IS_ARTIFACT) {
    try { const r = await fetch('/proxy/health', { cache: 'no-store' }); if (r.ok) window.__IPTV_PROXY__ = true; } catch {}
  }
  tickClock();
  getCaps().then((c) => { settingsCtx.caps = c; });
  if (IS_ARTIFACT && !store.getSources().some((s) => s.demo)) store.upsertSource(DEMO_SOURCE);
  const sources = store.getSources();
  const last = store.getLast();
  const src = sources.find((s) => s.id === last.sourceId) || sources[0] || (IS_ARTIFACT ? DEMO_SOURCE : null);
  const startup = cfg().startup;
  if ((IS_ARTIFACT && !last.sourceId) || !src || startup === 'welcome') { renderGrid(); showWelcome(true); }  // pantalla de listas
  else {
    await openSource(src);
    focusFirstTile();
    if (startup === 'lastChannel' && last.itemId) {           // continuar con el último canal visto
      const it = state.items.find((i) => i.id === last.itemId);
      if (it) openPlayer(it, state.items.filter((i) => i.kind === it.kind), state.items.filter((i) => i.kind === it.kind).indexOf(it));
    }
  }
  hideSplash();
  if (!IS_ARTIFACT && 'serviceWorker' in navigator && location.protocol !== 'file:') navigator.serviceWorker.register('sw.js').catch(() => {});
})().catch(() => hideSplash());
