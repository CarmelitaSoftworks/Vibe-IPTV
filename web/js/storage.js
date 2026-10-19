// Persistencia local: listas guardadas, favoritos, ajustes y último canal
const K = {
  sources: 'iptv.sources',
  settings: 'iptv.settings',
  favs: (id) => `iptv.favs.${id}`,
  last: 'iptv.last',
};

function read(key, fallback) {
  try { const v = localStorage.getItem(key); return v ? JSON.parse(v) : fallback; }
  catch { return fallback; }
}
function write(key, val) {
  try { localStorage.setItem(key, JSON.stringify(val)); } catch { /* cuota llena o modo privado */ }
}

export const DEFAULT_SETTINGS = {
  // Red
  proxyMode: 'auto', proxyUrl: '',
  // Reproducción
  liveFormat: 'm3u8', autoNext: true,
  convert: 'auto', convertQuality: 'fast', volume: 1, muted: false, aspect: 'fit',
  // Rápido / apariencia
  showNumbers: true, showEpgInfo: true, animations: true,
  lang: 'auto', theme: 'light', accent: 'celeste', tileSize: 'normal',
  // Guía
  epgEvery: 4, epgOffset: 0,
  // Subtítulos
  osApiKey: '', osUser: '', osPass: '', subLangs: 'es,en', subSize: 'normal', subdlKey: '',
  // Inicio de la app
  startup: 'home',
  // Control parental: PIN ('' = desactivado)
  parentalPin: '',
};

export const getSettings = () => ({ ...DEFAULT_SETTINGS, ...read(K.settings, {}) });
export const saveSettings = (s) => write(K.settings, { ...getSettings(), ...s });
export const resetSettings = () => write(K.settings, {});

export const getSources = () => read(K.sources, []);
export const saveSources = (list) => write(K.sources, list);

export function upsertSource(src) {
  const list = getSources();
  const i = list.findIndex((s) => s.id === src.id);
  if (i >= 0) list[i] = src; else list.push(src);
  saveSources(list);
}
export function removeSource(id) {
  saveSources(getSources().filter((s) => s.id !== id));
  try { localStorage.removeItem(K.favs(id)); } catch {}
}

// "Seguir viendo": { itemId: { pos, dur, at, parent } } en milisegundos
export const getResume = (sourceId) => read(`iptv.pos.${sourceId}`, {});
export function saveResume(sourceId, map) {
  const top = Object.entries(map).sort((a, b) => b[1].at - a[1].at).slice(0, 200);
  write(`iptv.pos.${sourceId}`, Object.fromEntries(top));
}
export const clearResume = (sourceId) => { try { localStorage.removeItem(`iptv.pos.${sourceId}`); } catch {} };

export const getFavs = (sourceId) => new Set(read(K.favs(sourceId), []));
export function toggleFav(sourceId, itemId) {
  const s = getFavs(sourceId);
  s.has(itemId) ? s.delete(itemId) : s.add(itemId);
  write(K.favs(sourceId), [...s]);
  return s;
}

// IndexedDB para contenido grande (listas M3U subidas como archivo)
function idb() {
  return new Promise((res, rej) => {
    const r = indexedDB.open('iptv', 1);
    r.onupgradeneeded = () => r.result.createObjectStore('kv');
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
}
export async function idbSet(key, val) {
  const db = await idb();
  return new Promise((res, rej) => {
    const tx = db.transaction('kv', 'readwrite');
    tx.objectStore('kv').put(val, key);
    tx.oncomplete = () => res(); tx.onerror = () => rej(tx.error);
  });
}
export async function idbGet(key) {
  const db = await idb();
  return new Promise((res, rej) => {
    const r = db.transaction('kv').objectStore('kv').get(key);
    r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error);
  });
}
export async function idbDel(key) {
  const db = await idb();
  db.transaction('kv', 'readwrite').objectStore('kv').delete(key);
}

export const getLast = () => read(K.last, {});
export const saveLast = (v) => write(K.last, { ...getLast(), ...v });
