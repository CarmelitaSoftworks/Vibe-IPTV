// Búsqueda de subtítulos en varias fuentes a la vez (lo hace el servidor local:
// así no hay bloqueos CORS y se pueden abrir los .zip).
//   - OpenSubtitles sin clave (a través del complemento abierto de Stremio + Cinemeta para el código IMDb)
//   - OpenSubtitles con clave (API oficial, la misma base que el plugin VLSub de VLC)
//   - SubDL (clave gratis en subdl.com; mucho catálogo en español latino)
//   - Podnapisi (sin clave)
const zlib = require('zlib');

const UA = 'VibeIPTV v1.0';
const TIMEOUT = 12000;

const LANG3 = { es: ['spa', 'spl', 'ea'], en: ['eng'], pt: ['por', 'pob'], fr: ['fre', 'fra'], it: ['ita'], de: ['ger', 'deu'] };
const NAME = { stremio: 'OpenSubtitles', opensubtitles: 'OpenSubtitles (cuenta)', subdl: 'SubDL', podnapisi: 'Podnapisi' };

async function get(url, opts = {}) {
  let res;
  try {
    res = await fetch(url, { ...opts, headers: { 'User-Agent': UA, Accept: 'application/json', ...(opts.headers || {}) }, signal: AbortSignal.timeout(TIMEOUT) });
  } catch (e) {
    throw new Error(e.name === 'TimeoutError' ? 'no responde' : 'sin conexión con la fuente');
  }
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res;
}
const getJson = async (url, opts) => (await get(url, opts)).json();

// ---------- Proveedores ----------
// Código IMDb: primero con el título original que da el proveedor (Xtream), después con el nombre de la lista
async function imdbFor(q) {
  if (q.imdb) return q.imdb;
  const titles = [...new Set([...(q.alts || []), q.title].map((t) => String(t || '').trim()).filter((t) => t.length >= 2))];
  for (const t of titles) { const id = await imdbForTitle(t, q); if (id) return id; }
  return null;
}
async function imdbForTitle(title, q) {
  const type = q.type === 'series' ? 'series' : 'movie';
  const r = await getJson(`https://v3-cinemeta.strem.io/catalog/${type}/top/search=${encodeURIComponent(title)}.json`);
  const metas = r.metas || [];
  // Sólo un resultado que calce: mismo año, o (sin año) que comparta palabras con el título. Antes se tomaba el
  // primero aunque no tuviera nada que ver ("Pelicula 123" traía subtítulos de otra película)
  const yearOk = (m) => q.year && String(m.releaseInfo || m.year || '').startsWith(String(q.year));
  const m = metas.find((x) => yearOk(x) && sharesWords(title, x.name)) || metas.find(yearOk)
    || (!q.year && metas.find((x) => sharesWords(title, x.name)))
    || (!q.strict && metas[0]);                       // a mano: lo más parecido, tú eliges
  return m ? (m.imdb_id || m.id) : null;
}
const fold = (s) => String(s || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
const STOP = new Set(['el', 'la', 'los', 'las', 'de', 'del', 'the', 'of', 'a', 'and', 'y', 'en', 'un', 'una']);
/** ¿El nombre del catálogo trae las palabras importantes del título? Todas si son 1 o 2; si son más, 2 de cada 3. */
function sharesWords(title, name) {
  const w = fold(title).split(' ').filter((x) => x.length >= 2 && !STOP.has(x));
  if (!w.length) return false;
  const n = new Set(fold(name).split(' '));
  const hit = w.filter((x) => n.has(x)).length;
  return w.length <= 2 ? hit === w.length : hit * 3 >= w.length * 2;
}

// ---------- Huella del archivo (OpenSubtitles): reconoce la película aunque su nombre no sirva ----------
const MATCH = '✓ Calza con este archivo · ';
const CHUNK = 65536;
/** Tamaño + suma de los números de 8 bytes del principio y el final (sólo se piden esos 2 pedazos de 64 KB). */
function hashOf(size, head, tail) {
  let h = BigInt(size);
  for (const part of [head, tail]) for (let i = 0; i < CHUNK; i += 8) h = (h + part.readBigUInt64LE(i)) & 0xFFFFFFFFFFFFFFFFn;
  return h.toString(16).padStart(16, '0');
}
async function osHash(url, ua = '', ref = '') {
  const piece = async (range) => {
    const res = await fetch(url, { headers: { Range: `bytes=${range}`, 'User-Agent': ua || UA, ...(ref ? { Referer: ref } : {}) }, signal: AbortSignal.timeout(TIMEOUT) });
    if (res.status !== 206) { res.body?.cancel?.(); throw new Error(`sin pedazos (HTTP ${res.status})`); }
    const total = Number((res.headers.get('content-range') || '').split('/')[1]);
    const buf = Buffer.from(await res.arrayBuffer()).subarray(0, CHUNK);
    return { buf, total };
  };
  try {
    const a = await piece(`0-${CHUNK - 1}`);
    if (!(a.total >= CHUNK * 2) || a.buf.length < CHUNK) return null;
    const b = await piece(`${a.total - CHUNK}-${a.total - 1}`);
    if (b.buf.length < CHUNK) return null;
    return { hash: hashOf(a.total, a.buf, b.buf), size: a.total };
  } catch { return null; }
}

async function stremio(q) {
  const imdb = await imdbFor(q);
  if (!imdb) return [];
  const type = q.type === 'series' ? 'series' : 'movie';
  const id = type === 'series' && q.season ? `${imdb}:${q.season}:${q.episode || 1}` : imdb;
  const r = await getJson(`https://opensubtitles-v3.strem.io/subtitles/${type}/${id}.json`);
  const want = new Map(q.langs.flatMap((l) => (LANG3[l] || [l]).map((c) => [c, l])));
  return (r.subtitles || [])
    .filter((s) => want.has(String(s.lang).toLowerCase()))
    .map((s, i) => ({ provider: 'stremio', id: s.url, lang: want.get(String(s.lang).toLowerCase()), label: `Subtítulo ${i + 1}`, downloads: 0 }));
}

async function opensubtitles(q) {
  if (!q.osKey) throw new Error('falta la API key (Ajustes → Subtítulos)');
  // Con la huella del archivo se busca primero por ella: encuentra la película aunque el nombre no sirva
  if (q.hash) {
    const exact = (await opensubtitlesBy(q, { moviehash: q.hash }).catch(() => [])).filter((r) => r.label.startsWith(MATCH));
    if (exact.length) return [...exact, ...(await opensubtitlesBy(q, { query: (q.alts || [])[0] || q.title }).catch(() => [])).filter((r) => !exact.some((e) => e.id === r.id))];
  }
  return opensubtitlesBy(q, { query: (q.alts || [])[0] || q.title });
}

async function opensubtitlesBy(q, by) {
  const p = new URLSearchParams({ ...by, languages: q.langs.join(',') });
  if (q.season) p.set('season_number', q.season);
  if (q.episode) p.set('episode_number', q.episode);
  if (q.year) p.set('year', q.year);
  const r = await getJson(`https://api.opensubtitles.com/api/v1/subtitles?${p}`, { headers: { 'Api-Key': q.osKey } });
  return (r.data || []).flatMap((d) => (d.attributes.files || []).map((f) => {
    // Coincide con la huella del archivo: calza justo con ese video (mismo corte y velocidad)
    const exact = d.attributes.moviehash_match ? MATCH : '';
    return {
      provider: 'opensubtitles', id: String(f.file_id), lang: d.attributes.language,
      label: exact + (d.attributes.release || f.file_name || 'Subtítulo'), downloads: (d.attributes.download_count || 0) + (exact ? 1e6 : 0),
    };
  }));
}

// SubDL, API oficial (https://subdl.com/api-doc): por código IMDb si se encuentra, si no por título;
// con unpack=1 se baja sólo el episodio de los paquetes de temporada.
const subdlLang = (l) => (l === 'pt' ? 'BR_PT,PT' : l.toUpperCase());
function subdlLangCode(code, name) {
  const c = String(code || '').toLowerCase();
  if (c.length === 2) return c;
  if (/^(br|pt)/.test(c)) return 'pt';
  const n = String(name || '').toLowerCase().split(' ')[0];
  return { spanish: 'es', english: 'en', portuguese: 'pt', brazilian: 'pt', brazillian: 'pt', french: 'fr', italian: 'it', german: 'de' }[n] || c.slice(0, 2) || n.slice(0, 2);
}
async function subdlQuery(q, imdb) {
  const p = new URLSearchParams({ api_key: q.subdlKey, languages: q.langs.map(subdlLang).join(','), subs_per_page: '30',
    type: q.type === 'series' ? 'tv' : 'movie', hi: '1', releases: '1', unpack: '1', client: 'custom_integration' });
  if (imdb) p.set('imdb_id', imdb); else { p.set('film_name', (q.alts || [])[0] || q.title); if (q.year) p.set('year', q.year); }
  if (q.season) p.set('season_number', q.season);
  if (q.episode) p.set('episode_number', q.episode);
  try { return await getJson(`https://api.subdl.com/api/v1/subtitles?${p}`); }
  catch (e) {
    if (/HTTP 40[13]/.test(e.message)) throw new Error('API key inválida (revísala en Ajustes → Subtítulos)');
    if (/HTTP 429/.test(e.message)) throw new Error('límite diario de la API alcanzado');
    throw e;
  }
}
async function subdl(q) {
  if (!q.subdlKey) throw new Error('falta la API key (Ajustes → Subtítulos)');
  const imdb = await imdbFor(q).catch(() => null);
  let r = await subdlQuery(q, imdb);
  if (imdb && !(r.subtitles || []).length) r = await subdlQuery(q, null);
  if (r.status === false) { if (/can't find|not found/i.test(r.error || '')) return []; throw new Error(r.error || 'sin resultados'); }
  const ep = q.season && q.episode ? `S${String(q.season).padStart(2, '0')}E${String(q.episode).padStart(2, '0')}` : '';
  const out = (r.subtitles || []).map((s) => {
    const hi = s.hi ? ' · SDH' : '';
    const file = q.episode && (s.unpack_files || []).find((f) => Number(f.episode) === Number(q.episode) && (!q.season || !f.season || Number(f.season) === Number(q.season)));
    const url = file ? file.url : s.url;
    return {
      provider: 'subdl', id: ep ? `${url}#${ep}` : url, lang: subdlLangCode(s.language, s.lang),
      label: (file?.name || s.release_name || s.name || 'Subtítulo') + hi + (!file && s.full_season ? ' · temporada completa' : ''), downloads: 0,
    };
  });
  return out.filter((x, i) => out.findIndex((y) => y.id === x.id) === i);
}

/** Estado de la clave de SubDL (plan y cuota) para Ajustes. */
async function subdlAccount(key) {
  if (!key) throw new Error('Escribe primero la API key');
  let r;
  try { r = await getJson(`https://api.subdl.com/api/v1/me?api_key=${encodeURIComponent(key)}`, { headers: { 'x-api-key': key } }); }
  catch (e) { throw new Error(/HTTP 40[13]/.test(e.message) ? 'API key inválida' : `SubDL: ${e.message}`); }
  if (r.status === false) throw new Error(r.error || 'API key inválida');
  const d = r.data || r.user || r;
  const plan = d.plan || d.plan_name || d.tier || 'gratis';
  const used = d.requests_today ?? d.used_today ?? d.today_requests;
  const limit = d.daily_limit ?? d.requests_limit ?? d.limit;
  return `Clave válida · plan ${plan}` + (used != null ? ` · hoy ${used}${limit != null ? '/' + limit : ''} búsquedas` : '');
}

async function podnapisi(q) {
  const p = new URLSearchParams({ keywords: (q.alts || [])[0] || q.title, movie_type: q.type === 'series' ? 'tv-series' : 'movie' });
  for (const l of q.langs) p.append('language', l);
  if (q.season) p.set('seasons', q.season);
  if (q.episode) p.set('episodes', q.episode);
  if (q.year) p.set('year', q.year);
  const r = await getJson(`https://www.podnapisi.net/subtitles/search/advanced?${p}`);
  return (r.data || []).map((d) => ({
    provider: 'podnapisi', id: String(d.id || d.pid), lang: String(d.language || '').slice(0, 2),
    label: (d.custom_releases && d.custom_releases[0]) || d.release || d.movie?.title || 'Subtítulo', downloads: d.stats?.downloads || 0,
  }));
}

const PROVIDERS = { stremio, opensubtitles, subdl, podnapisi };

async function search(q) {
  const results = [];
  const errors = {};
  await Promise.all(Object.entries(PROVIDERS).map(async ([name, fn]) => {
    if (name === 'opensubtitles' && !q.osKey) return;       // sin clave se usa la vía de Stremio
    if (name === 'subdl' && !q.subdlKey) { errors[NAME.subdl] = 'agrega una API key gratis en Ajustes → Subtítulos'; return; }
    try { results.push(...(await fn(q))); } catch (e) { errors[NAME[name]] = e.message; }
  }));
  const order = (l) => { const i = q.langs.indexOf(l); return i < 0 ? 99 : i; };
  results.sort((a, b) => order(a.lang) - order(b.lang) || b.downloads - a.downloads);
  return { results: results.map((r) => ({ ...r, source: NAME[r.provider] })), errors };
}

// ---------- Descarga, zip, codificación y conversión a WebVTT ----------
function unzipBest(buf, episode = '') {
  const eocd = buf.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (eocd < 0) throw new Error('zip inválido');
  const count = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  const entries = [];
  for (let i = 0; i < count && buf.readUInt32LE(off) === 0x02014b50; i++) {
    const method = buf.readUInt16LE(off + 10);
    const csize = buf.readUInt32LE(off + 20);
    const nlen = buf.readUInt16LE(off + 28), xlen = buf.readUInt16LE(off + 30), clen = buf.readUInt16LE(off + 32);
    const local = buf.readUInt32LE(off + 42);
    const name = buf.slice(off + 46, off + 46 + nlen).toString('latin1');
    entries.push({ name, method, csize, local });
    off += 46 + nlen + xlen + clen;
  }
  const m = /S0*(\d+)E0*(\d+)/.exec(episode);
  const epRe = m ? new RegExp(`(s0*${m[1]}[ ._-]*e0*${m[2]}(?!\\d)|\\b${m[1]}[x×]0*${m[2]}(?!\\d))`, 'i') : null;
  const base = (n) => (/\.srt$/i.test(n) ? 0 : /\.vtt$/i.test(n) ? 1 : /\.(ass|ssa)$/i.test(n) ? 2 : 9);
  const rank = (n) => { const b = base(n); return b < 9 && epRe && !epRe.test(n) ? b + 3 : b; };
  const e = entries.filter((x) => rank(x.name) < 9).sort((a, b) => rank(a.name) - rank(b.name))[0];
  if (!e) throw new Error('el zip no trae subtítulos .srt/.vtt/.ass');
  const lnlen = buf.readUInt16LE(e.local + 26), lxlen = buf.readUInt16LE(e.local + 28);
  const data = buf.slice(e.local + 30 + lnlen + lxlen, e.local + 30 + lnlen + lxlen + e.csize);
  return { name: e.name, data: e.method === 8 ? zlib.inflateRawSync(data) : data };
}

// Muchos .srt en español vienen en Windows-1252 / Latin-1: sin esto salen "Ã±" y "Ã©"
function decodeText(buf) {
  if (buf[0] === 0xff && buf[1] === 0xfe) return new TextDecoder('utf-16le').decode(buf.slice(2));
  if (buf[0] === 0xfe && buf[1] === 0xff) return new TextDecoder('utf-16be').decode(buf.slice(2));
  try { return new TextDecoder('utf-8', { fatal: true }).decode(buf).replace(/^\uFEFF/, ''); }
  catch { return new TextDecoder('windows-1252').decode(buf); }
}

function srtToVtt(srt) {
  const body = srt.replace(/\r+/g, '').replace(/(\d\d:\d\d:\d\d),(\d\d\d)/g, '$1.$2').replace(/^\d+\s*\n(?=\d\d:\d\d)/gm, '');
  return body.trimStart().startsWith('WEBVTT') ? body : `WEBVTT\n\n${body.trim()}\n`;
}

function assToVtt(ass) {
  const t = (s) => { const m = /(\d+):(\d\d):(\d\d)[.:](\d\d)/.exec(s); return m ? `${m[1].padStart(2, '0')}:${m[2]}:${m[3]}.${m[4]}0` : '00:00:00.000'; };
  const cues = [];
  for (const line of ass.split(/\r?\n/)) {
    if (!line.startsWith('Dialogue:')) continue;
    const parts = line.slice(9).split(',');
    const text = parts.slice(9).join(',').replace(/\{[^}]*\}/g, '').replace(/\\N/gi, '\n').trim();
    if (text) cues.push(`${t(parts[1])} --> ${t(parts[2])}\n${text}`);
  }
  return `WEBVTT\n\n${cues.join('\n\n')}\n`;
}

async function download(provider, id, keys = {}) {
  let buf, name = '', episode = '';
  if (provider === 'stremio') buf = Buffer.from(await (await get(id, { headers: { Accept: '*/*' } })).arrayBuffer());
  else if (provider === 'opensubtitles') {
    const r = await getJson('https://api.opensubtitles.com/api/v1/download', {
      method: 'POST', headers: { 'Api-Key': keys.osKey || '', 'Content-Type': 'application/json' }, body: JSON.stringify({ file_id: Number(id) }),
    });
    if (!r.link) throw new Error(r.message || 'límite diario alcanzado');
    buf = Buffer.from(await (await get(r.link, { headers: { Accept: '*/*' } })).arrayBuffer());
  } else if (provider === 'subdl') {
    const [path, ep = ''] = String(id).split('#');
    episode = ep;
    buf = Buffer.from(await (await get(`https://dl.subdl.com${path.startsWith('/') ? path : '/' + path}`,
      { headers: { Accept: '*/*', ...(keys.subdlKey ? { 'x-api-key': keys.subdlKey } : {}) } })).arrayBuffer());
  }
  else if (provider === 'podnapisi') buf = Buffer.from(await (await get(`https://www.podnapisi.net/subtitles/${encodeURIComponent(id)}/download`, { headers: { Accept: '*/*' } })).arrayBuffer());
  else throw new Error('fuente desconocida');
  if (buf[0] === 0x50 && buf[1] === 0x4b) ({ name, data: buf } = unzipBest(buf, episode));
  else if (buf[0] === 0x1f && buf[1] === 0x8b) buf = zlib.gunzipSync(buf);
  const text = decodeText(buf);
  if (/\.(ass|ssa)$/i.test(name) || /^\[Script Info\]/m.test(text)) return assToVtt(text);
  return srtToVtt(text);
}

module.exports = { search, download, subdlAccount, decodeText, srtToVtt, assToVtt, unzipBest, osHash, hashOf };
