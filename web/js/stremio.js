// Extensiones de Stremio (formato abierto de complementos): cada una es una dirección que termina en manifest.json.
// Una lista de tipo "Extensiones Stremio" junta varias: sus catálogos se ven como categorías, sus series traen los
// episodios y, al elegir algo, se piden los videos a las extensiones que los entregan.
// Vibe IPTV sigue siendo un reproductor neutral: no trae extensiones ni recomienda ninguna. Sólo reproduce enlaces de
// video directos (http/https); los torrents no (ni se descargan ni se comparten).
import { fetchSmart } from './net.js';

export const PREFIX = 'stm:';

/** Lo que se puede pegar (stremio://…, con o sin manifest.json) → dirección base de la extensión. */
export function normalize(input) {
  let s = String(input || '').trim();
  if (/^stremio:\/\//i.test(s)) s = 'https://' + s.slice('stremio://'.length);
  if (!/^https?:\/\//i.test(s)) s = 'https://' + s;
  return s.split('?')[0].replace(/\/+$/, '').replace(/\/manifest\.json$/i, '').replace(/\/+$/, '');
}
/** Direcciones de una lista de extensiones (una por línea, o separadas por espacios o comas). */
export const urls = (text) => [...new Set(String(text || '').split(/[\s,]+/).filter(Boolean).map(normalize))];
export const itemId = (type, id) => `${PREFIX}${type}:${id}`;
/** [tipo, id de la extensión] de un elemento que vino de extensiones, o null. */
export function parse(id) {
  if (!String(id).startsWith(PREFIX)) return null;
  const rest = String(id).slice(PREFIX.length), i = rest.indexOf(':');
  return i > 0 ? [rest.slice(0, i), rest.slice(i + 1)] : null;
}
/** Elemento de extensión que todavía no tiene el enlace del video (se pide al elegirlo). */
export const needsStream = (item) => !!item && !item.url && !!parse(item.id) && item.kind !== 'series';

const getJson = (url) => fetchSmart(url, 'json');
const KIND = { movie: 'movie', series: 'series', tv: 'live', channel: 'live' };

export function parseManifest(base, o) {
  const types = o.types || [], prefixes = o.idPrefixes || null;
  const resources = {};
  for (const r of o.resources || []) {
    if (typeof r === 'string') resources[r] = { types: null, prefixes: null };
    else if (r && r.name) resources[r.name] = { types: r.types || null, prefixes: r.idPrefixes || null };
  }
  // Catálogos que exigen un dato extra (sólo búsqueda, sólo por género…) no se pueden listar enteros
  const catalogs = (o.catalogs || []).filter((c) => !(c.extraRequired || []).length && !(c.extra || []).some((e) => e.isRequired))
    .map((c) => ({ type: c.type, id: c.id, name: c.name || c.id }));
  return { base, name: o.name || new URL(base).host, catalogs, resources, types, prefixes };
}
/** ¿La extensión entrega este recurso (stream, meta, subtitles) para este tipo e id? */
export function serves(a, resource, type, id) {
  const r = a.resources[resource];
  if (!r) return false;
  const ts = r.types || a.types, ps = r.prefixes || a.prefixes;
  return (!ts.length || ts.includes(type)) && (!ps || !ps.length || ps.some((p) => id.startsWith(p)));
}

export async function manifest(url) {
  const base = normalize(url);
  return parseManifest(base, await getJson(`${base}/manifest.json`));
}

function itemFrom(m, catalogType, group) {
  const type = m.type || catalogType, kind = KIND[type];
  if (!kind || !m.id || !m.name) return null;
  const year = /^\d{4}/.exec(m.releaseInfo || '')?.[0];
  return {
    id: itemId(type, m.id), kind, url: '', group, logo: m.poster || m.logo || '',
    name: year && kind === 'movie' && !m.name.includes(year) ? `${m.name} (${year})` : m.name,
    ...(kind === 'series' ? { seriesName: m.name } : {}),
  };
}

/** Todos los catálogos (hasta 3 páginas cada uno). onError recibe "Extensión: motivo" de las que fallan. */
export async function loadAll(addons, onError = () => {}) {
  const jobs = addons.flatMap((a) => a.catalogs.map((c) => [a, c]));
  const lists = await Promise.all(jobs.map(async ([a, c]) => {
    try {
      const group = `${c.name} · ${a.name}`, out = [];
      let skip = 0;
      for (let page = 0; page < 3; page++) {
        const r = await getJson(skip ? `${a.base}/catalog/${c.type}/${c.id}/skip=${skip}.json` : `${a.base}/catalog/${c.type}/${c.id}.json`);
        const metas = r.metas || [];
        if (!metas.length) break;
        for (const m of metas) { const it = itemFrom(m, c.type, group); if (it) out.push(it); }
        skip += metas.length;
        if (metas.length < 50) break;
      }
      return out;
    } catch (e) { onError(`${a.name}: ${e.message}`); return []; }
  }));
  const seen = new Set();
  return lists.flat().filter((it) => { const k = `${it.id}|${it.group}`; if (seen.has(k)) return false; seen.add(k); return true; });
}

export async function episodes(addons, series) {
  const p = parse(series.id);
  if (!p) return [];
  const [type, id] = p;
  const a = addons.find((x) => serves(x, 'meta', type, id));
  if (!a) throw new Error('Ninguna de tus extensiones tiene la ficha de esta serie');
  const meta = (await getJson(`${a.base}/meta/${type}/${id}.json`)).meta || {};
  return (meta.videos || []).filter((v) => v.id).map((v) => {
    const s = Number(v.season) || 0, e = Number(v.episode ?? v.number) || 0, title = v.name || v.title || '';
    return {
      id: itemId(type, v.id), kind: 'movie', url: '', season: s, episode: e, seriesName: series.seriesName || series.name,
      name: s || e ? `T${s} E${e} · ${title}`.replace(/[ ·]+$/, '') : title || v.id,
      logo: v.thumbnail || series.logo || '', group: s ? `Temporada ${s}` : 'Especiales',
    };
  }).sort((x, y) => (x.season || 1e9) - (y.season || 1e9) || x.episode - y.episode);
}

/** Enlace magnet a partir del hash y los rastreadores ("tracker:udp://…" de Stremio). */
export function magnet(hash, sources = [], name = '') {
  const tr = [...new Set(sources.map((s) => String(s).replace(/^tracker:/, '')).filter((t) => /^(udp|https?|wss):\/\//.test(t)))];
  return `magnet:?xt=urn:btih:${String(hash).toLowerCase()}${name ? `&dn=${encodeURIComponent(name)}` : ''}${tr.map((t) => `&tr=${encodeURIComponent(t)}`).join('')}`;
}

/** Los enlaces de video de las extensiones. Los torrents sólo si se activaron (Ajustes); si no, se cuentan para avisar. */
export async function streams(addons, item, withTorrents = false) {
  const p = parse(item.id);
  if (!p) return { list: [], torrents: 0 };
  const [type, id] = p;
  let torrents = 0;
  const lists = await Promise.all(addons.filter((a) => serves(a, 'stream', type, id)).map(async (a) => {
    try {
      const r = await getJson(`${a.base}/stream/${type}/${id}.json`);
      return (r.streams || []).flatMap((s) => {
        if (s.infoHash && !/^https?:/i.test(s.url || '')) {
          if (!withTorrents) { torrents++; return []; }
          const title = [s.name, s.title || s.description].map((x) => String(x || '').replace(/\n/g, ' ').trim()).filter(Boolean).join(' · ');
          return [{ url: magnet(s.infoHash, s.sources || [], item.name) + (Number.isInteger(s.fileIdx) ? `&vibe_file=${s.fileIdx}` : ''), title: `🧲 ${title || 'Torrent'}`, addon: a.name, torrent: true }];
        }
        if (!/^https?:\/\//i.test(s.url || '')) return [];
        const h = s.behaviorHints?.proxyHeaders?.request || {};
        const hv = (n) => Object.entries(h).find(([k]) => k.toLowerCase() === n)?.[1] || '';
        const title = [s.name, s.title || s.description].map((x) => String(x || '').replace(/\n/g, ' ').trim()).filter(Boolean).join(' · ');
        return [{ url: s.url, title: title || 'Video', addon: a.name, userAgent: hv('user-agent'), referrer: hv('referer') }];
      });
    } catch { return []; }
  }));
  const seen = new Set();
  // Los enlaces directos primero (empiezan al tiro); los torrents después
  return { list: lists.flat().filter((s) => !seen.has(s.url) && seen.add(s.url)).sort((a, b) => (a.torrent ? 1 : 0) - (b.torrent ? 1 : 0)), torrents };
}

/** Subtítulos que entregan las extensiones (por id IMDb: "tt…" o "tt…:temporada:episodio"). */
export async function subtitles(addons, type, id) {
  const lists = await Promise.all(addons.filter((a) => serves(a, 'subtitles', type, id)).map(async (a) => {
    try { return ((await getJson(`${a.base}/subtitles/${type}/${id}.json`)).subtitles || []).filter((s) => /^https?:/.test(s.url || '')).map((s) => ({ url: s.url, lang: String(s.lang || '').toLowerCase(), addon: a.name })); }
    catch { return []; }
  }));
  return lists.flat();
}
