// Cliente para la API Xtream Codes (player_api.php)
import { fetchSmart } from './net.js';

export function normalizeServer(server) {
  let s = server.trim().replace(/\/+$/, '');
  if (!/^https?:\/\//i.test(s)) s = 'http://' + s;
  return s.replace(/\/(player_api|get)\.php.*$/i, '');
}

export class XtreamClient {
  constructor({ server, username, password }, liveFormat = 'm3u8') {
    this.base = normalizeServer(server);
    this.u = encodeURIComponent(username);
    this.p = encodeURIComponent(password);
    this.liveFormat = liveFormat;
  }

  api(action, extra = {}) {
    const q = new URLSearchParams({ username: decodeURIComponent(this.u), password: decodeURIComponent(this.p), ...extra });
    if (action) q.set('action', action);
    return fetchSmart(`${this.base}/player_api.php?${q}`, 'json');
  }

  // Variantes que se prueban si la dirección escrita no responde:
  // http en vez de https y los puertos más usados por Xtream.
  candidateBases() {
    const u = new URL(this.base);
    const out = [this.base];
    const add = (proto, port) => {
      const v = new URL(this.base); v.protocol = proto; v.port = port;
      const s = v.origin + v.pathname.replace(/\/+$/, '');
      if (!out.includes(s)) out.push(s);
    };
    if (u.protocol === 'https:') add('http:', u.port);
    if (!u.port) for (const p of ['8080', '80', '25461', '8000', '2095']) add('http:', p);
    return out;
  }

  async login() {
    const bases = this.candidateBases();
    let firstErr = null;
    for (const base of bases) {
      this.base = base;
      try {
        const info = await this.api();
        if (!info?.user_info) throw new Error('La dirección no corresponde a un servidor Xtream');
        if (Number(info.user_info.auth) !== 1) throw new Error('Usuario o contraseña incorrectos');
        if (info.user_info.status && !/active/i.test(info.user_info.status)) throw new Error(`La cuenta está ${String(info.user_info.status).toLowerCase()}`);
        this.workingBase = base;
        this.serverTz = info.server_info?.timezone || '';
        // Usar un formato que la cuenta permita (algunas sólo aceptan .ts)
        const allowed = (info.user_info.allowed_output_formats || []).map(String);
        if (allowed.length && !allowed.includes(this.liveFormat)) this.liveFormat = allowed.includes('m3u8') ? 'm3u8' : allowed.includes('ts') ? 'ts' : this.liveFormat;
        return info;
      } catch (e) {
        if (/contraseña|cuenta está/.test(e.message)) throw e; // el servidor respondió: no seguir probando
        firstErr ||= e;
      }
    }
    this.base = bases[0];
    throw firstErr;
  }

  liveUrl(id) { return `${this.base}/live/${this.u}/${this.p}/${id}.${this.liveFormat}`; }
  movieUrl(id, ext = 'mp4') { return `${this.base}/movie/${this.u}/${this.p}/${id}.${ext || 'mp4'}`; }
  episodeUrl(id, ext = 'mp4') { return `${this.base}/series/${this.u}/${this.p}/${id}.${ext || 'mp4'}`; }
  epgUrl() { return `${this.base}/xmltv.php?username=${this.u}&password=${this.p}`; }

  // Descarga todo y lo convierte al formato común de la app
  async loadAll() {
    const safe = (p) => p.catch(() => []);
    const [lc, ls, vc, vs, sc, ss] = await Promise.all([
      safe(this.api('get_live_categories')), safe(this.api('get_live_streams')),
      safe(this.api('get_vod_categories')), safe(this.api('get_vod_streams')),
      safe(this.api('get_series_categories')), safe(this.api('get_series')),
    ]);
    const catMap = (cats) => new Map((Array.isArray(cats) ? cats : []).map((c) => [String(c.category_id), c.category_name]));
    const lcm = catMap(lc), vcm = catMap(vc), scm = catMap(sc);
    const arr = (x) => (Array.isArray(x) ? x : []);
    const items = [];
    for (const s of arr(ls)) items.push({
      id: `l${s.stream_id}`, kind: 'live', name: s.name || 'Canal', logo: s.stream_icon || '',
      group: lcm.get(String(s.category_id)) || 'Sin categoría', tvgId: s.epg_channel_id || '', tvgName: s.name || '',
      url: this.liveUrl(s.stream_id), num: s.num,
      // TV en diferido (catch-up)
      archiveDays: String(s.tv_archive) === '1' ? Number(s.tv_archive_duration) || 0 : 0,
      catchup: String(s.tv_archive) === '1' ? `${this.base}/timeshift/${this.u}/${this.p}/{dur}/{start}/${s.stream_id}.ts` : '',
      catchupTz: this.serverTz || '',
    });
    for (const s of arr(vs)) items.push({
      id: `v${s.stream_id}`, kind: 'movie', name: s.name || 'Película', logo: s.stream_icon || '',
      group: vcm.get(String(s.category_id)) || 'Sin categoría', url: this.movieUrl(s.stream_id, s.container_extension),
    });
    for (const s of arr(ss)) items.push({
      id: `s${s.series_id}`, kind: 'series', name: s.name || 'Serie', logo: s.cover || '',
      group: scm.get(String(s.category_id)) || 'Sin categoría', seriesId: s.series_id, url: '',
    });
    return items;
  }

  async episodes(seriesId) {
    const info = await this.api('get_series_info', { series_id: seriesId });
    const out = [];
    let eps = info?.episodes || {};
    const groups = Array.isArray(eps) ? [eps.flat()] : Object.values(eps);
    for (const list of groups) for (const e of list || []) {
      const season = Number(e.season) || 0;
      out.push({
        id: `e${e.id}`, kind: 'movie', season, episode: Number(e.episode_num) || 0, seriesName: info?.info?.name || '',
        name: `T${season} E${e.episode_num} · ${e.title || ''}`.trim(),
        logo: e.info?.movie_image || info?.info?.cover || '', group: `Temporada ${season}`,
        url: this.episodeUrl(e.id, e.container_extension),
      });
    }
    return out.sort((a, b) => a.season - b.season || a.episode - b.episode);
  }
}
