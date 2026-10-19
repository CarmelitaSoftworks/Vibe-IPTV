// Guía en grilla, TV en diferido, fichas de películas/series y respaldo (versión web).
// Grid guide, catch-up TV, movie/series info pages and backup (web version).
import * as store from './storage.js';
import { t } from './i18n.js';
import { moveFocus } from './nav.js';

const HALF = 30 * 60e3;
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

/* ---------- overlay común: teclado y cierre ---------- */
function overlay(id, cls) {
  document.getElementById(id)?.remove();
  const el = document.createElement('section');
  el.id = id; el.className = cls; el.tabIndex = -1;
  document.body.appendChild(el);
  return el;
}
function trapKeys(el, onClose) {
  const h = (e) => {
    if (!document.body.contains(el)) { document.removeEventListener('keydown', h, true); return; }
    const k = e.key;
    if (k === 'Escape' || k === 'Backspace' || k === 'GoBack' || k === 'BrowserBack' || e.keyCode === 10009) {
      if (e.target.matches?.('input')) return;
      e.preventDefault(); e.stopImmediatePropagation(); close(); return;
    }
    const dir = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' }[k];
    if (dir) { e.preventDefault(); e.stopImmediatePropagation(); moveFocus(dir, el); return; }
    if (k === 'Enter' && e.target !== document.body) { e.stopImmediatePropagation(); return; }   // el clic lo hace el botón
    e.stopImmediatePropagation();
  };
  const close = () => { document.removeEventListener('keydown', h, true); el.remove(); onClose?.(); };
  document.addEventListener('keydown', h, true);
  return close;
}

/* ---------- TV en diferido (Xtream /timeshift) ---------- */
export function catchupUrl(item, prog, now = Date.now()) {
  if (!item.catchup || !item.archiveDays) return null;
  if (prog.start >= now || prog.start < now - item.archiveDays * 864e5) return null;
  let parts;
  try {
    parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
      timeZone: item.catchupTz || undefined, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
    }).formatToParts(new Date(prog.start)).map((p) => [p.type, p.value]));
  } catch { return null; }
  const start = `${parts.year}-${parts.month}-${parts.day}:${parts.hour}-${parts.minute}`;
  const dur = Math.max(1, Math.ceil((Math.min(prog.stop, now) - prog.start) / 60e3));
  return item.catchup.replace('{start}', start).replace('{dur}', dur);
}

/* ---------- Guía en grilla ---------- */
// ctx: { channels, epg, shift, fmtTime, play(item, list), playCatchup(item, prog), toast }
export function openGuide(ctx, onClose) {
  const el = overlay('guideView', 'guide-view');
  const close = trapKeys(el, onClose);
  const now = Date.now(), sh = ctx.shift;
  const winStart = Math.floor((now - 2 * 3600e3) / HALF) * HALF, winEnd = winStart + 14 * 3600e3;
  const pxMin = 4;
  const x = (ms) => ((ms - winStart) / 60e3) * pxMin;
  const totalW = x(winEnd);
  el.innerHTML = `
    <header class="guide-head">
      <button class="pill" data-nav id="gBack">‹ ${esc(t('Volver'))}</button>
      <div><h2>${esc(t('Guía de programación'))}</h2><small>${esc(t(`${ctx.channels.length} canales`))}</small></div>
      <b class="guide-clock">${ctx.fmtTime(now)}</b>
    </header>
    <div class="guide-info" id="gInfo"></div>
    <div class="guide-scroll" id="gScroll">
      <div class="guide-ruler" style="width:${totalW + 220}px"><span class="gc-spacer"></span>
        <div class="gr-times" style="width:${totalW}px">${Array.from({ length: 28 }, (_, i) => `<span style="left:${x(winStart + i * HALF)}px">${ctx.fmtTime(winStart + i * HALF)}</span>`).join('')}
          <i class="g-now" style="left:${x(now)}px"></i></div>
      </div>
      <div class="guide-rows" id="gRows" style="width:${totalW + 220}px"></div>
      <div id="gMore" class="guide-more"></div>
    </div>`;
  el.querySelector('#gBack').onclick = close;
  const rows = el.querySelector('#gRows'), info = el.querySelector('#gInfo');
  let rendered = 0;
  const renderBatch = () => {
    const end = Math.min(ctx.channels.length, rendered + 40);
    for (let i = rendered; i < end; i++) {
      const ch = ctx.channels[i];
      const progs = ctx.epg.listFor(ch).filter((p) => p.stop + sh > winStart && p.start + sh < winEnd);
      const row = document.createElement('div');
      row.className = 'g-row';
      row.innerHTML = `<div class="g-chan">${ch.logo ? `<img src="${esc(ch.logo)}" alt="" loading="lazy">` : ''}<span>${esc(ch.name)}${ch.archiveDays ? `<small>⏪ ${ch.archiveDays} d</small>` : ''}</span></div>
        <div class="g-progs" style="width:${totalW}px"><i class="g-now" style="left:${x(now)}px"></i></div>`;
      const box = row.querySelector('.g-progs');
      const add = (p, label, left, w, state, archive) => {
        const b = document.createElement('button');
        b.className = `g-prog ${state}${archive ? ' archive' : ''}`; b.dataset.nav = '';
        b.style.left = left + 'px'; b.style.width = Math.max(2, w - 3) + 'px';
        b.innerHTML = w > 28 ? `${archive ? '⏪ ' : ''}${esc(label)}` : '';
        b.onfocus = () => {
          b.scrollIntoView({ block: 'nearest', inline: 'nearest' });
          const when = p ? `${ctx.fmtTime(p.start + sh)}–${ctx.fmtTime(p.stop + sh)}` : '';
          const extra = state === 'now' ? t('▶ En emisión ahora · OK para ver') : archive ? t('⏪ Disponible en diferido · OK para verlo desde el inicio') : '';
          info.innerHTML = `<b>${esc(p?.title || t('Sin guía'))}</b> · ${esc(ch.name)} ${when ? '· ' + when : ''}<br><small>${esc([extra, p?.desc || ''].filter(Boolean).join(' · '))}</small>`;
        };
        b.onclick = () => {
          if (!p || state === 'now') { close(); ctx.play(ch, ctx.channels); return; }
          if (archive) { close(); ctx.playCatchup(ch, { ...p, start: p.start + sh, stop: p.stop + sh }); return; }
          ctx.toast(state === 'past' ? t('Este canal no tiene TV en diferido.') : `${p.title} · ${ctx.fmtTime(p.start + sh)}`);
        };
        box.appendChild(b);
        return b;
      };
      if (!progs.length) add(null, t('Sin guía · OK para ver el canal'), 0, totalW, 'none', false);
      for (const p of progs) {
        const s = Math.max(p.start + sh, winStart), e = Math.min(p.stop + sh, winEnd);
        const state = p.stop + sh <= now ? 'past' : p.start + sh <= now ? 'now' : 'future';
        const archive = state === 'past' && !!catchupUrl(ch, { ...p, start: p.start + sh, stop: p.stop + sh }, now);
        add(p, p.title, x(s), x(e) - x(s), state, archive);
      }
      rows.appendChild(row);
    }
    rendered = end;
    el.querySelector('#gMore').hidden = rendered >= ctx.channels.length;
  };
  renderBatch();
  // Cargar más canales al acercarse al final
  new IntersectionObserver((es) => { if (es.some((e) => e.isIntersecting) && rendered < ctx.channels.length) renderBatch(); }, { root: el.querySelector('#gScroll'), rootMargin: '600px' })
    .observe(el.querySelector('#gMore'));
  el.querySelector('#gScroll').scrollLeft = Math.max(0, x(now) - x(winStart + HALF) - 0);
  (rows.querySelector('.g-prog.now') || rows.querySelector('.g-prog'))?.focus();
}

/* ---------- Fichas ---------- */
const metaCache = new Map();
async function fetchMeta(item, xt, tmdbKey, lang) {
  const key = item.id + lang;
  if (metaCache.has(key)) return metaCache.get(key);
  let m = null;
  const pick = (o, ...ks) => ks.map((k) => o?.[k]).find((v) => v && v !== 'null') || '';
  try {
    if (xt && item.kind === 'movie' && /^v\d+/.test(item.id)) {
      const r = await xt.api('get_vod_info', { vod_id: item.id.slice(1) }); const o = r?.info;
      if (o) m = { title: pick(o, 'name', 'o_name'), year: String(pick(o, 'releasedate', 'release_date', 'year')).slice(0, 4), rating: pick(o, 'rating'), genres: pick(o, 'genre'), plot: pick(o, 'plot', 'description'), cast: pick(o, 'cast', 'actors'), director: pick(o, 'director'), runtime: pick(o, 'duration'), poster: pick(o, 'movie_image', 'cover_big'), backdrop: (o.backdrop_path || [])[0] || '', source: t('Servidor') };
    } else if (xt && item.kind === 'series' && item.seriesId) {
      const r = await xt.api('get_series_info', { series_id: item.seriesId }); const o = r?.info;
      if (o) m = { title: pick(o, 'name'), year: String(pick(o, 'releaseDate', 'release_date')).slice(0, 4), rating: pick(o, 'rating'), genres: pick(o, 'genre'), plot: pick(o, 'plot'), cast: pick(o, 'cast'), director: pick(o, 'director'), poster: pick(o, 'cover'), backdrop: (o.backdrop_path || [])[0] || '', source: t('Servidor') };
    }
  } catch { /* sin ficha del servidor */ }
  if ((!m || !m.plot) && tmdbKey) {
    try {
      const tv = item.kind === 'series' || !!item.seriesName;
      const title = (item.seriesName || item.name).replace(/^[A-Z]{2,3}\s*[|:-]\s*/, '').replace(/\(?\b(19|20)\d\d\b\)?/, '').replace(/\[.*?\]/g, '').trim();
      const year = (item.name.match(/\b(19|20)\d\d\b/) || [])[0];
      const bearer = tmdbKey.length > 60;
      const q = new URLSearchParams({ query: title, language: lang, include_adult: 'false' });
      if (year) q.set(tv ? 'first_air_date_year' : 'year', year);
      if (!bearer) q.set('api_key', tmdbKey);
      const r = await (await fetch(`https://api.themoviedb.org/3/search/${tv ? 'tv' : 'movie'}?${q}`, { headers: bearer ? { Authorization: `Bearer ${tmdbKey}` } : {} })).json();
      const o = r.results?.[0];
      if (o) {
        const tm = { title: o.title || o.name, year: (o.release_date || o.first_air_date || '').slice(0, 4), rating: o.vote_average ? `${o.vote_average.toFixed(1)}/10` : '', plot: o.overview,
          poster: o.poster_path ? `https://image.tmdb.org/t/p/w500${o.poster_path}` : '', backdrop: o.backdrop_path ? `https://image.tmdb.org/t/p/w780${o.backdrop_path}` : '', source: 'TMDB' };
        m = m ? { ...m, plot: tm.plot, rating: m.rating || tm.rating, backdrop: m.backdrop || tm.backdrop, source: `${m.source} · TMDB` } : tm;
      }
    } catch { /* sin TMDB */ }
  }
  metaCache.set(key, m);
  return m;
}

// ctx: { xt, tmdbKey, lang, resume, fav, play(), playFromStart(), toggleFav(), openEpisodes(), fmtDur }
export async function openDetails(item, ctx, onClose) {
  const el = overlay('detailsView', 'details-view');
  const close = trapKeys(el, onClose);
  const render = (m, loading) => {
    const r = ctx.resume;
    const poster = m?.poster || item.logo || '';
    const back = m?.backdrop || m?.poster || item.logo || '';
    const line = [m?.year, m?.runtime, m?.rating && `★ ${m.rating}`, m?.genres].filter(Boolean).join('  ·  ');
    el.innerHTML = `
      ${back ? `<div class="d-back" style="background-image:url('${esc(back)}')"></div>` : ''}<div class="d-shade"></div>
      <div class="d-body">
        ${poster ? `<img class="d-poster" src="${esc(poster)}" alt="">` : ''}
        <div class="d-text">
          <h2>${esc(m?.title || item.name)}</h2>
          ${line ? `<p class="d-line">${esc(line)}</p>` : ''}
          <div class="d-actions">
            ${item.kind === 'series' ? `<button class="btn" data-nav data-a="eps">${esc(t('▶ Ver episodios'))}</button>`
              : r ? `<button class="btn" data-nav data-a="play">${esc(t(`▶ Continuar desde ${ctx.fmtDur(r.pos / 1000)}`))}</button><button class="btn ghost" data-nav data-a="start">${esc(t('Desde el inicio'))}</button>`
              : `<button class="btn" data-nav data-a="play">${esc(t('▶ Ver'))}</button>`}
            <button class="btn ghost" data-nav data-a="fav">${esc(t(ctx.fav() ? '★ En favoritos' : '☆ Favorito'))}</button>
            <button class="btn ghost" data-nav data-a="close">${esc(t('Volver'))}</button>
          </div>
          ${loading ? `<p class="d-muted">${esc(t('Buscando la ficha…'))}</p>`
            : !m ? `<p class="d-muted">${esc(t(ctx.xt || ctx.tmdbKey ? 'No se encontró la ficha de este título.' : 'Sin ficha. Agrega una clave gratuita de TMDB en Ajustes → Fichas para ver sinopsis y puntuación.'))}</p>`
            : `${m.plot ? `<p class="d-plot">${esc(m.plot)}</p>` : ''}${m.director ? `<p class="d-meta"><b>${esc(t('Dirección: '))}</b>${esc(m.director)}</p>` : ''}${m.cast ? `<p class="d-meta"><b>${esc(t('Reparto: '))}</b>${esc(m.cast)}</p>` : ''}<p class="d-source">${esc(t(`Ficha: ${m.source}`))}</p>`}
        </div>
      </div>`;
    el.querySelectorAll('[data-a]').forEach((b) => (b.onclick = () => {
      const a = b.dataset.a;
      if (a === 'close') close();
      else if (a === 'play') { close(); ctx.play(); }
      else if (a === 'start') { close(); ctx.playFromStart(); }
      else if (a === 'eps') { close(); ctx.openEpisodes(); }
      else if (a === 'fav') { ctx.toggleFav(); render(m, loading); el.querySelector('[data-a="fav"]')?.focus(); }
    }));
    if (!el.contains(document.activeElement)) el.querySelector('[data-a]')?.focus();
  };
  render(null, true);
  const m = await fetchMeta(item, ctx.xt, ctx.tmdbKey, ctx.lang);
  if (document.body.contains(el)) { const f = document.activeElement?.dataset?.a; render(m, false); if (f) el.querySelector(`[data-a="${f}"]`)?.focus(); }
}

/* ---------- Respaldo ---------- */
export async function exportBackup() {
  const prefs = {};
  for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); if (k.startsWith('iptv.')) prefs[k] = localStorage.getItem(k); }
  const files = {};
  for (const s of store.getSources()) { try { const txt = await store.idbGet(`m3u:${s.id}`); if (txt) files[s.id] = txt; } catch { /* sin archivo */ } }
  const blob = new Blob([JSON.stringify({ app: 'vibe-iptv-web', version: 1, at: Date.now(), prefs, files })], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `vibe-iptv-respaldo-${new Date().toISOString().slice(0, 10).replace(/-/g, '')}.json`;
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 5000);
}
export function importBackup() {
  return new Promise((resolve, reject) => {
    const input = Object.assign(document.createElement('input'), { type: 'file', accept: '.json,application/json' });
    input.onchange = async () => {
      try {
        const o = JSON.parse(await input.files[0].text());
        if (o.app !== 'vibe-iptv-web') throw new Error(t('El archivo no es un respaldo de Vibe IPTV'));
        Object.keys(localStorage).filter((k) => k.startsWith('iptv.')).forEach((k) => localStorage.removeItem(k));
        for (const [k, v] of Object.entries(o.prefs || {})) if (k.startsWith('iptv.')) localStorage.setItem(k, v);
        for (const [id, txt] of Object.entries(o.files || {})) await store.idbSet(`m3u:${id}`, txt);
        resolve(store.getSources().length);
      } catch (e) { reject(e); }
    };
    input.click();
  });
}
