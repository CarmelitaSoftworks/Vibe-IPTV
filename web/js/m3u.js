// Parser de listas M3U / M3U8 extendidas (#EXTINF con atributos tvg-*)

function parseAttrs(str) {
  const attrs = {};
  const re = /([\w-]+)\s*=\s*"([^"]*)"/g;
  let m;
  while ((m = re.exec(str))) attrs[m[1].toLowerCase()] = m[2];
  return attrs;
}

// La coma que separa atributos del título es la primera fuera de comillas
function titleComma(line) {
  let inQ = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (c === '"') inQ = !inQ;
    else if (c === ',' && !inQ) return i;
  }
  return -1;
}

// Patrones de episodio: S01E02, S1 E2, T1E2, 1x02
export const EP_RE = /^(.*?)[\s._\-|:]*(?:S(\d{1,2})\s*E(\d{1,3})|T(\d{1,2})\s*E(\d{1,3})|\b(\d{1,2})x(\d{2,3}))\b(.*)$/i;
const VOD_EXT = /\.(mp4|mkv|avi|mov|m4v|wmv|flv|webm|mpg|mpeg)$/i;
const SERIES_GROUP = /\b(series?|temporadas?|seasons?|novelas?|tv ?shows?|animes?)\b/i;
const MOVIE_GROUP = /\b(pel[ií]culas?|movies?|vod|cine|films?|estrenos|peliculas)\b/i;

// Clasifica una entrada: 'live' (TV en vivo), 'movie' (película) o 'episode' (episodio de serie)
export function classify(url, group = '', name = '') {
  const path = url.split('?')[0];
  if (/\/series\//i.test(path)) return 'episode';
  if (/\/movie\//i.test(path)) return 'movie';
  if (EP_RE.test(name) && (SERIES_GROUP.test(group) || VOD_EXT.test(path) || !/\.m3u8$|\.ts$/i.test(path))) return 'episode';
  if (SERIES_GROUP.test(group)) return 'episode';
  if (MOVIE_GROUP.test(group)) return 'movie';
  if (VOD_EXT.test(path)) return 'movie';
  return 'live';
}
export const detectKind = (url) => (classify(url) === 'live' ? 'live' : 'movie');

const normKey = (s) => s.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9]+/g, ' ').trim();

// Agrupa los episodios sueltos en series (cada serie contiene sus episodios)
export function groupSeries(items) {
  const out = [];
  const map = new Map();
  for (const it of items) {
    if (it.kind !== 'episode') { out.push(it); continue; }
    const m = EP_RE.exec(it.name);
    const title = ((m && m[1]) || it.name).replace(/[\s\-|:.]+$/, '').trim() || it.name;
    const season = m ? +(m[2] || m[4] || m[6]) : 1;
    const episode = m ? +(m[3] || m[5] || m[7]) : 0;
    const key = normKey(title) + '|' + it.group;
    let s = map.get(key);
    if (!s) {
      s = { id: 's' + hash(key), kind: 'series', name: title, logo: it.logo, group: it.group, url: '', episodes: [] };
      map.set(key, s);
      out.push(s);
    }
    const rest = m ? (m[8] || '').replace(/^[\s\-|:.]+/, '').trim() : '';
    s.episodes.push({
      ...it, kind: 'movie', season, episode, seriesName: title,
      name: m ? `T${season} E${episode}${rest ? ' · ' + rest : ''}` : it.name,
      group: `Temporada ${season}`,
    });
  }
  for (const s of map.values()) s.episodes.sort((a, b) => a.season - b.season || a.episode - b.episode);
  return out;
}

// "CHILE | DEPORTES", "Películas » Acción", "VOD / Terror", "LATINO - Noticias" → categoría y subcategoría.
// Si la primera parte sólo repite la sección ("Películas", "Series", "VOD"…), se descarta.
const SECTION_WORDS = /^(pel[ií]culas?|movies?|vod|series?|tv( en vivo)?|en vivo|live|canales?|channels?)$/i;
export function splitGroup(group) {
  let parts = String(group || '').split(/\s*(?:\||›|»|\/|::|\s-\s)\s*/).map((p) => p.trim()).filter(Boolean);
  if (parts.length > 1 && SECTION_WORDS.test(parts[0])) parts = parts.slice(1);
  return { cat: parts[0] || 'Sin categoría', sub: parts.slice(1).join(' · ') };
}

export function hash(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
  return (h >>> 0).toString(36);
}

export function parseM3U(text) {
  const lines = text.split(/\r?\n/);
  let header = {};
  const items = [];
  const seen = new Map();
  let cur = null;
  let opts = {};

  for (const raw of lines) {
    const line = raw.trim();
    if (!line) continue;
    if (line.startsWith('#EXTM3U')) { header = parseAttrs(line); continue; }
    if (line.startsWith('#EXTINF')) {
      const idx = titleComma(line);
      const meta = idx >= 0 ? line.slice(0, idx) : line;
      const title = idx >= 0 ? line.slice(idx + 1).trim() : '';
      const a = parseAttrs(meta);
      cur = {
        name: title || a['tvg-name'] || 'Sin nombre',
        logo: a['tvg-logo'] || '',
        group: a['group-title'] || '',
        tvgId: a['tvg-id'] || '',
        tvgName: a['tvg-name'] || '',
      };
      continue;
    }
    if (line.startsWith('#EXTGRP:')) { if (cur && !cur.group) cur.group = line.slice(8).trim(); continue; }
    if (line.startsWith('#EXTVLCOPT:')) {
      const [k, ...v] = line.slice(11).split('=');
      const val = v.join('=');
      if (/user-agent/i.test(k)) opts.userAgent = val;
      if (/referr?er/i.test(k)) opts.referrer = val;
      continue;
    }
    if (line.startsWith('#')) continue;

    const url = line;
    const base = cur || { name: url.split('/').pop() || url, logo: '', group: '', tvgId: '', tvgName: '' };
    let id = 'm' + hash(url);
    const n = seen.get(id) || 0;
    seen.set(id, n + 1);
    if (n) id += '_' + n;
    items.push({
      id, kind: classify(url, base.group, base.name), url,
      name: base.name, logo: base.logo,
      group: base.group || 'Sin categoría',
      tvgId: base.tvgId, tvgName: base.tvgName,
      userAgent: opts.userAgent || '', referrer: opts.referrer || '',
    });
    cur = null;
    opts = {};
  }
  return { epgUrl: (header['x-tvg-url'] || header['url-tvg'] || '').trim(), items: groupSeries(items) };
}
