// Subtítulos: OpenSubtitles (la misma base que usa el plugin VLSub de VLC),
// archivos .srt/.vtt externos y conversión SRT -> WebVTT.
import { getSettings } from './storage.js';
import { proxyBase } from './net.js';

const OS_API = 'https://api.opensubtitles.com/api/v1';
const APP_UA = 'VibeIPTVWeb v1.0';
let osToken = null;

export function srtToVtt(srt) {
  const body = srt
    .replace(/^\uFEFF/, '')
    .replace(/\r+/g, '')
    .replace(/(\d\d:\d\d:\d\d),(\d\d\d)/g, '$1.$2')
    .replace(/^\d+\s*\n(?=\d\d:\d\d)/gm, '');
  return body.trimStart().startsWith('WEBVTT') ? body : `WEBVTT\n\n${body.trim()}\n`;
}

async function osFetch(path, { method = 'GET', body } = {}) {
  const s = getSettings();
  if (!s.osApiKey) throw new Error('Configura tu API key de OpenSubtitles en Ajustes');
  const headers = { 'Api-Key': s.osApiKey, 'X-User-Agent': APP_UA, Accept: 'application/json' };
  if (osToken) headers.Authorization = `Bearer ${osToken}`;
  if (body) headers['Content-Type'] = 'application/json';
  const url = OS_API + path;
  const opts = { method, headers, body: body ? JSON.stringify(body) : undefined };
  // Con proxy, éste convierte X-User-Agent en User-Agent (el navegador no deja fijarlo)
  const p = proxyBase();
  const res = await fetch(p ? `${p}/proxy?${new URLSearchParams({ url })}` : url, opts);
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.message || data.errors?.join(', ') || `OpenSubtitles HTTP ${res.status}`);
  return data;
}

async function ensureLogin() {
  const s = getSettings();
  if (osToken || !s.osUser || !s.osPass) return;
  const r = await osFetch('/login', { method: 'POST', body: { username: s.osUser, password: s.osPass } });
  osToken = r.token;
}

// Limpia el nombre de un canal/película para buscar ("ES| Matrix (1999) [4K]" -> "Matrix")
export function cleanTitle(name) {
  return name
    .replace(/^[A-Z]{2,3}\s*[|:\-]\s*/, '')
    .replace(/\[[^\]]*\]|\([^)]*\)/g, ' ')
    .replace(/\b(4k|fhd|uhd|hd|sd|1080p|720p|multi|lat|cast|sub|vose)\b/gi, ' ')
    .replace(/T\d+\s*E\d+.*$/i, '')
    .replace(/\s+/g, ' ')
    .trim();
}

const yearOf = (name) => (/\b(19[3-9]\d|20[0-4]\d)\b/.exec(name || '') || [])[1] || '';

/**
 * Busca en varias fuentes a la vez a través del servidor local (OpenSubtitles, SubDL, Podnapisi).
 * Sin servidor (vista previa) sólo queda OpenSubtitles con clave.
 * Devuelve { results: [{provider, source, id, lang, label, downloads}], errors: {fuente: motivo} }.
 */
export async function searchSubtitles(item, query) {
  const s = getSettings();
  const title = query || cleanTitle(item.seriesName || item.name);
  if (proxyBase()) {
    const p = new URLSearchParams({
      title, langs: (s.subLangs || 'es,en').replace(/\s+/g, ''),
      type: item.seriesName || item.season ? 'series' : 'movie',
      season: item.season || '', episode: item.episode || '', year: yearOf(item.name),
      osKey: s.osApiKey || '', subdlKey: s.subdlKey || '',
    });
    const res = await fetch(`${proxyBase()}/proxy/subs/search?${p}`);
    if (!res.ok) throw new Error(decodeURIComponent(res.headers.get('x-proxy-error') || `error ${res.status}`));
    return res.json();
  }
  return { results: (await searchOpenSubtitlesDirect(item, title)).map((r) => ({ ...r, provider: 'os-direct', source: 'OpenSubtitles', id: r.fileId })), errors: {} };
}

export async function downloadSubtitle(r) {
  if (r.provider === 'os-direct') return downloadOpenSubtitlesDirect(r.id);
  const p = new URLSearchParams({ provider: r.provider, id: r.id, osKey: getSettings().osApiKey || '', subdlKey: getSettings().subdlKey || '' });
  const res = await fetch(`${proxyBase()}/proxy/subs/get?${p}`);
  if (!res.ok) throw new Error(decodeURIComponent(res.headers.get('x-proxy-error') || `error ${res.status}`));
  return res.text();
}

async function searchOpenSubtitlesDirect(item, title) {
  await ensureLogin().catch(() => {});
  const langs = (getSettings().subLangs || 'es,en').replace(/\s+/g, '').toLowerCase();
  const q = new URLSearchParams({ query: title, languages: langs });
  if (item.season) q.set('season_number', item.season);
  if (item.episode) q.set('episode_number', item.episode);
  const r = await osFetch(`/subtitles?${q}`);
  return (r.data || []).flatMap((d) => (d.attributes.files || []).map((f) => ({
    fileId: f.file_id,
    label: `[${d.attributes.language}] ${d.attributes.release || f.file_name || ''}`.trim(),
    lang: d.attributes.language,
    downloads: d.attributes.download_count || 0,
  }))).sort((a, b) => b.downloads - a.downloads);
}

async function downloadOpenSubtitlesDirect(fileId) {
  await ensureLogin().catch(() => {});
  const r = await osFetch('/download', { method: 'POST', body: { file_id: fileId } });
  let res;
  try { res = await fetch(r.link); }
  catch { res = await fetch(`${proxyBase()}/proxy?${new URLSearchParams({ url: r.link })}`); }
  return srtToVtt(decodeBytes(await res.arrayBuffer()));
}

// Los .srt en español suelen venir en Windows-1252: se detecta para no mostrar "Ã±"
export function decodeBytes(buf) {
  const b = new Uint8Array(buf);
  if (b[0] === 0xff && b[1] === 0xfe) return new TextDecoder('utf-16le').decode(b.subarray(2));
  try { return new TextDecoder('utf-8', { fatal: true }).decode(b).replace(/^\uFEFF/, ''); }
  catch { return new TextDecoder('windows-1252').decode(b); }
}

export async function readLocalFile(file) {
  const text = decodeBytes(await file.arrayBuffer());
  return file.name.toLowerCase().endsWith('.vtt') ? text : srtToVtt(text);
}

/** Corre los subtítulos visibles `delta` segundos (positivo = aparecen más tarde). Devuelve el desfase total. */
export function shiftSubtitles(video, delta) {
  let total = 0;
  for (const t of video.textTracks) {
    if (t.mode !== 'showing' || !t.cues) continue;
    for (const c of t.cues) { c.startTime = Math.max(0, c.startTime + delta); c.endTime = Math.max(0, c.endTime + delta); }
    t._shift = (t._shift || 0) + delta;
    total = t._shift;
  }
  return total;
}
export function currentShift(video) {
  for (const t of video.textTracks) if (t.mode === 'showing') return t._shift || 0;
  return 0;
}

// Añade un VTT al <video> y lo activa
export function attachVtt(video, vtt, label = 'Externo', lang = 'es') {
  video.querySelectorAll('track[data-ext]').forEach((t) => { URL.revokeObjectURL(t.src); t.remove(); });
  const track = document.createElement('track');
  track.kind = 'subtitles';
  track.label = label;
  track.srclang = lang;
  track.dataset.ext = '1';
  track.src = URL.createObjectURL(new Blob([vtt], { type: 'text/vtt' }));
  video.appendChild(track);
  track.addEventListener('load', () => selectTrack(video, track.track));
  track.track.mode = 'showing';
  selectTrack(video, track.track);
}

export function selectTrack(video, chosen) {
  for (const t of video.textTracks) t.mode = t === chosen ? 'showing' : 'disabled';
}

export function listTracks(video) {
  return [...video.textTracks].filter((t) => t.kind === 'subtitles' || t.kind === 'captions');
}
