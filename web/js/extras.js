// Guía en grilla, TV en diferido, fichas de películas/series y respaldo (versión web).
// Grid guide, catch-up TV, movie/series info pages and backup (web version).
import * as store from './storage.js';
import { t } from './i18n.js';
import { moveFocus } from './nav.js';
import { searchGuide } from './epg.js';

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
      <button class="pill" data-nav id="gFind">🔍 ${esc(t('Buscar programa'))}</button>
      <b class="guide-clock">${ctx.fmtTime(now)}</b>
    </header>
    <div class="g-search" id="gSearch" hidden><input type="search" id="gQuery" placeholder="${esc(t('Programa, equipo o tema (ej. colo colo, noticias)'))}"><div id="gHits"></div></div>
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
  // Buscar un programa por su nombre en toda la guía (todos los canales en vivo)
  const gq = el.querySelector('#gQuery'), hits = el.querySelector('#gHits');
  el.querySelector('#gFind').onclick = () => { el.querySelector('#gSearch').hidden = false; gq.focus(); };
  let qTimer;
  gq.oninput = () => {
    clearTimeout(qTimer);
    qTimer = setTimeout(() => {
      hits.innerHTML = '';
      if (gq.value.trim().length < 2) return;
      const tNow = Date.now() - sh;
      const out = searchGuide(ctx.epg, ctx.allLive || ctx.channels, gq.value, tNow - 86400e3, tNow);
      if (!out.length) hits.innerHTML = `<small>${esc(t('Sin resultados en la guía cargada.'))}</small>`;
      for (const [ch, p] of out.slice(0, 80)) {
        const s = p.start + sh, e2 = p.stop + sh, nowT = Date.now();
        const tag = s <= nowT && e2 > nowT ? t('▶ AHORA') : e2 <= nowT ? '⏪' : new Date(s).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric' });
        const b = document.createElement('button');
        b.className = 'pill'; b.dataset.nav = '';
        b.textContent = `${tag} ${ctx.fmtTime(s)} · ${p.title} — ${ch.name}`;
        b.onclick = () => {
          const list = ctx.channels.includes(ch) ? ctx.channels : [ch];
          if (s <= nowT && e2 > nowT) { close(); ctx.play(ch, list); return; }
          if (e2 <= nowT && catchupUrl(ch, { ...p, start: s, stop: e2 }, nowT)) { close(); ctx.playCatchup(ch, { ...p, start: s, stop: e2 }); return; }
          ctx.toast(e2 <= nowT ? t('Este canal no tiene TV en diferido.') : `${p.title} · ${ch.name} · ${ctx.fmtTime(s)}`);
        };
        hits.appendChild(b);
      }
    }, 300);
  };
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

/* ---------- Respaldo ---------- */
export async function exportBackup() {
  const prefs = {};
  for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); if (k.startsWith('iptv.')) prefs[k] = localStorage.getItem(k); }
  const files = {};
  for (const s of store.getSources()) { try { const txt = await store.idbGet(`m3u:${s.id}`); if (txt) files[s.id] = txt; } catch { /* sin archivo */ } }
  const blob = new Blob([JSON.stringify({ app: 'vibe-iptv-web', version: 1, at: Date.now(), prefs, files })], { type: 'application/json' });
  await saveFile(blob, `vibe-iptv-respaldo-${new Date().toISOString().slice(0, 10).replace(/-/g, '')}.json`);
}

/**
 * Guarda un archivo. En iPhone/iPad, con la app agregada a la pantalla de inicio, los enlaces de descarga no
 * funcionan: se usa "Compartir" (Guardar en Archivos, AirDrop, Mail…) cuando el navegador lo permite.
 */
export async function saveFile(blob, name) {
  const ios = /iP(hone|od|ad)/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  if (ios && navigator.canShare) {
    try {
      const file = new File([blob], name, { type: blob.type || 'application/octet-stream' });
      if (navigator.canShare({ files: [file] })) { await navigator.share({ files: [file], title: name }); return; }
    } catch (e) { if (e?.name === 'AbortError') return; }
  }
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = name;
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 5000);
}
export function importBackup() {
  return new Promise((resolve, reject) => {
    const ios = /iP(hone|od|ad)/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
    const input = Object.assign(document.createElement('input'), { type: 'file', ...(ios ? {} : { accept: '.json,application/json' }) });
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
