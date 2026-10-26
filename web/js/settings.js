// Pantalla de Ajustes (dos columnas, inspirada en OwnTV): secciones a la izquierda,
// filas con su valor a la derecha, búsqueda y sección "Rápido" con interruptores.
import { t } from './i18n.js';
import * as store from './storage.js';
import { moveFocus, keyDir, isBack, isOk } from './nav.js';

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const ICONS = {
  quick: '<path d="M13 2 4 14h7l-1 8 9-12h-7z"/>',
  sources: '<path d="M4 6h16M4 12h16M4 18h10"/><circle cx="19" cy="18" r="2"/>',
  look: '<circle cx="12" cy="12" r="9"/><path d="M12 3a9 9 0 0 0 0 18z" fill="currentColor"/>',
  play: '<rect x="3" y="5" width="18" height="14" rx="3"/><path d="m10 9 5 3-5 3z" fill="currentColor"/>',
  subs: '<rect x="3" y="5" width="18" height="14" rx="3"/><path d="M7 15h4M13 15h4M7 11h10"/>',
  network: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/>',
  startup: '<path d="M12 3v9M6.3 7.3a8 8 0 1 0 11.4 0"/>',
  data: '<ellipse cx="12" cy="6" rx="8" ry="3"/><path d="M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/>',
  about: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7.5v.5"/>',
};
const icon = (k) => `<svg viewBox="0 0 24 24" aria-hidden="true">${ICONS[k]}</svg>`;

const ACCENTS = [['celeste', 'Celeste'], ['verde', 'Verde'], ['naranja', 'Naranja'], ['rosa', 'Rosa'], ['violeta', 'Violeta']];
const OFFSETS = [-3, -2, -1, 0, 1, 2, 3].map((h) => [h, h === 0 ? 'Sin desfase' : `${h > 0 ? '+' : ''}${h} h`]);

let subdlStatus = '';
let ctx = null;          // funciones de la app (guía, listas, avisos…)
let current = 'quick';   // sección abierta
let lastSection = 'quick';
let returnFocus = null;

function sections() {
  const st = ctx.state();
  const nSources = store.getSources().length;
  return [
    { id: 'quick', name: 'Rápido', rows: [
      { type: 'toggle', key: 'showNumbers', label: 'Números de canal', desc: 'Muestra el número en cada mosaico de TV en vivo' },
      { type: 'toggle', key: 'showEpgInfo', label: 'Guía bajo los mosaicos', desc: 'Programa actual y siguiente del canal enfocado' },
      { type: 'toggle', key: 'autoNext', label: 'Siguiente episodio automático', desc: 'Al terminar un episodio empieza el siguiente' },
      { type: 'toggle', key: 'animations', label: 'Animaciones', desc: 'Zoom de mosaicos y transiciones' },
    ] },
    { id: 'sources', name: 'Listas y guía', rows: [
      { type: 'action', label: 'Mis listas', value: () => `${nSources} ${nSources === 1 ? 'guardada' : 'guardadas'}`, arrow: true, run: () => { closeSettings(false); ctx.showWelcome(); } },
      { type: 'action', label: 'Actualizar guía ahora', value: () => st.epgStatus || (st.source ? 'Sin guía cargada' : 'Abre una lista primero'), run: () => { ctx.refreshEpg(); setTimeout(renderPanel, 50); } },
      { type: 'choice', key: 'epgEvery', label: 'Actualización automática de la guía', desc: 'Además de al añadir una lista y al abrir la app', options: [[4, 'Cada 4 horas'], [12, 'Cada 12 horas'], [24, 'Una vez al día'], [0, 'Sólo al abrir la app']] },
      { type: 'choice', key: 'epgOffset', label: 'Desfase de la guía', desc: 'Corrige guías que muestran la hora corrida', options: OFFSETS },
    ] },
    { id: 'look', name: 'Apariencia', rows: [
      { type: 'choice', key: 'lang', label: 'Idioma', desc: 'Idioma de la app', options: [['auto', 'Automático (idioma del sistema)'], ['es', 'Español'], ['en', 'English']] },
      { type: 'choice', key: 'theme', label: 'Tema', options: [['light', 'Claro'], ['dark', 'Oscuro'], ['system', 'Según el sistema'], ['metro', 'Mosaicos (gris y verde, esquinas rectas)'], ['wave', 'Ondas (violeta)'], ['pulse', 'Pulso (estilo consola: fila de íconos y fondo grande, azul)'], ['horizon', 'Horizonte (estilo consola: mosaicos y destacado, verde)'], ['desk', 'Escritorio (estilo escritorio de 2001: ventana azul, panel lateral y barra de tareas)']] },
      { type: 'choice', key: 'accent', label: 'Color de acento', options: ACCENTS },
      { type: 'choice', key: 'tileSize', label: 'Tamaño de los mosaicos', options: [['normal', 'Normal'], ['large', 'Grande'], ['small', 'Compacto']] },
      { type: 'toggle', key: 'animations', label: 'Animaciones' },
    ] },
    { id: 'play', name: 'Reproducción', rows: [
      { type: 'choice', key: 'hudScale', label: 'Tamaño de los controles del reproductor', desc: 'Botones, textos y paneles del reproductor (HUD)', options: [['0.8', '80 %'], ['0.9', '90 %'], ['1', '100 % (normal)'], ['1.1', '110 %'], ['1.25', '125 %'], ['1.4', '140 %']] },
      { type: 'choice', key: 'convert', label: 'Convertir formatos no compatibles', desc: 'Para canales con audio AC-3 / MP2 o video MPEG-2 (habitual en TV de cable)', options: [['auto', 'Automático, sólo si hace falta'], ['always', 'Siempre en TV en vivo'], ['never', 'Nunca']] },
      { type: 'choice', key: 'convertQuality', label: 'Calidad de la conversión', desc: 'Sólo cuando hay que convertir el video', options: [['fast', 'Rápida (usa menos el PC)'], ['balanced', 'Mejor calidad']] },
      { type: 'info', label: 'Conversión (ffmpeg)', value: () => ctx.caps?.ffmpeg ? 'Disponible' : 'No instalada: abre la app con iniciar.bat' },
      { type: 'choice', key: 'buffer', label: 'Búfer', desc: 'Cuánto video se carga por adelantado', options: [['fast', 'Rápido: cambia de canal antes'], ['balanced', 'Equilibrado (recomendado)'], ['stable', 'Estable: para proveedores que se cortan']] },
      { type: 'toggle', key: 'liveCushion', label: 'Colchón en vivo', desc: 'Si un canal en vivo queda con poco video guardado, se reproduce un poco más lento (no se nota) hasta rehacer la reserva. Así aguanta mejor los cortes, a cambio de ir unos segundos más atrás del directo' },
      { type: 'choice', key: 'liveFormat', label: 'Formato de canales en vivo (Xtream)', options: [['m3u8', 'HLS (.m3u8) — recomendado'], ['ts', 'MPEG-TS (.ts)']] },
      { type: 'toggle', key: 'autoNext', label: 'Siguiente episodio automático' },
    ] },
    { id: 'subs', name: 'Subtítulos', rows: [
      { type: 'choice', key: 'subSize', label: 'Tamaño del texto', options: [['small', 'Pequeño'], ['normal', 'Normal'], ['large', 'Grande'], ['xlarge', 'Muy grande']] },
      { type: 'text', key: 'subLangs', label: 'Idiomas preferidos', desc: 'Códigos separados por coma, ej: es,en' },
      { type: 'info', label: 'Fuentes', value: () => 'OpenSubtitles · SubDL · Podnapisi' },
      { type: 'text', key: 'osApiKey', label: 'API key de OpenSubtitles (opcional)', desc: 'Sin clave igual se busca en OpenSubtitles; con clave hay más resultados', secret: true },
      { type: 'toggle', key: 'autoSubs', label: 'Subtítulos automáticos', desc: 'En películas y series: usa la pista en tu idioma o busca y carga una sola (sin clave ni cuenta)' },
      { type: 'text', key: 'subdlKey', label: 'API key de SubDL', desc: 'Gratis en subdl.com: mucho catálogo en español latino', secret: true },
      { type: 'action', label: 'Probar la clave de SubDL', desc: 'Comprueba la clave y muestra tu plan · consíguela gratis en subdl.com/panel/api',
        value: () => subdlStatus || (store.getSettings().subdlKey ? 'Probar' : 'Sin clave'),
        run: () => { subdlStatus = 'Comprobando…'; renderPanel(); ctx.checkSubdl().then((m) => { subdlStatus = m; renderPanel(); }); } },
      { type: 'text', key: 'osUser', label: 'Usuario de OpenSubtitles', desc: 'Opcional: más descargas diarias' },
      { type: 'text', key: 'osPass', label: 'Contraseña de OpenSubtitles', secret: true },
    ] },
    { id: 'network', name: 'Red', rows: [
      { type: 'choice', key: 'proxyMode', label: 'Proxy', desc: 'Para servidores que bloquean el navegador (CORS) o usan http://', options: [['auto', 'Automático'], ['always', 'Siempre'], ['never', 'Nunca']] },
      { type: 'text', key: 'proxyUrl', label: 'Dirección del proxy', desc: 'Vacío = el mismo servidor que abre la app', placeholder: 'http://192.168.1.10:8080' },
    ] },
    { id: 'startup', name: 'Inicio de la app', rows: [
      { type: 'choice', key: 'startup', label: 'Al abrir la app', options: [['home', 'Inicio de la última lista'], ['welcome', 'Pantalla de mis listas'], ['lastChannel', 'Último canal visto']] },
    ] },
    { id: 'parental', name: 'Control parental', rows: [
      { type: 'text', key: 'parentalPin', label: 'PIN de control parental', desc: '4 dígitos. Con PIN, las categorías para adultos (XXX, Adultos, +18…) quedan ocultas. Vacío = desactivado.', secret: true },
      { type: 'info', label: 'Contenido para adultos', value: () => { const s = st; return !store.getSettings().parentalPin ? 'Visible (sin PIN)' : s.adultUnlocked ? 'Desbloqueado' : `Oculto (${s.hiddenAdult || 0})`; } },
    ] },
    { id: 'data', name: 'Datos', rows: [
      { type: 'action', label: 'Exportar respaldo', desc: 'Listas (con contraseñas), favoritos, Seguir viendo y ajustes en un archivo', arrow: true, run: () => ctx.exportBackup() },
      { type: 'action', label: 'Importar respaldo', desc: 'Reemplaza lo guardado en este equipo por el del archivo', arrow: true, run: () => ctx.importBackup() },
      { type: 'danger', label: 'Borrar "Seguir viendo"', desc: 'Olvida dónde quedaste en películas y series de esta lista', run: () => { if (!st.source) return ctx.toast('Abre una lista primero'); ctx.clearResume(); ctx.toast('"Seguir viendo" borrado'); } },
      { type: 'danger', label: 'Borrar guías guardadas', desc: 'Se vuelven a descargar al abrir cada lista', run: async () => { await ctx.clearEpgCache(); ctx.toast('Guías guardadas borradas'); } },
      { type: 'danger', label: 'Borrar favoritos de esta lista', run: () => { if (!st.source) return ctx.toast('Abre una lista primero'); ctx.clearFavorites(); ctx.toast('Favoritos borrados'); } },
      { type: 'danger', label: 'Restablecer ajustes', desc: 'Vuelve a los valores de fábrica (no borra tus listas)', run: () => { store.resetSettings(); ctx.apply(); ctx.toast('Ajustes restablecidos'); renderAll(); } },
      { type: 'danger', label: 'Borrar todo de este navegador', desc: 'Listas, cuentas, favoritos, guías y ajustes guardados aquí. La app queda como recién instalada', run: async () => {
        if (!confirm('¿Borrar todas las listas y datos guardados en este navegador?')) return;
        try { Object.keys(localStorage).filter((k) => k.startsWith('iptv.')).forEach((k) => localStorage.removeItem(k)); } catch {}
        try { indexedDB.deleteDatabase('iptv'); } catch {}
        location.reload();
      } },
    ] },
    { id: 'about', name: 'Acerca de', rows: [
      { type: 'info', label: 'Vibe IPTV', value: () => 'Versión 1.0' },
      { type: 'action', label: 'Guardar registro', desc: 'Para reportar un problema: errores recientes, sin usuarios ni contraseñas', arrow: true, run: () => ctx.saveLog() },
      { type: 'info', label: 'Desarrollado por', value: () => 'Carmelita Softworks' },
      { type: 'info', label: 'Licencia', value: () => 'Código abierto · GPLv3' },
      { type: 'info', label: 'Reproducción', value: () => 'hls.js · mpegts.js · ffmpeg' },
      { type: 'info', label: 'Guía', value: () => 'XMLTV (compatible con iptv-org/epg)' },
      { type: 'info', label: 'Subtítulos', value: () => 'OpenSubtitles · SubDL · Podnapisi' },
    ] },
  ];
}

function valueText(row, s) {
  if (row.type === 'toggle') return '';
  if (row.type === 'choice') return (row.options.find(([v]) => String(v) === String(s[row.key])) || [, String(s[row.key])])[1];
  if (row.type === 'text') return row.secret ? (s[row.key] ? '••••••' : 'Sin configurar') : (s[row.key] || 'Sin configurar');
  return row.value ? row.value() : '';
}

function rowHtml(row, s, i, sectionId) {
  const on = row.type === 'toggle' && !!s[row.key];
  const val = valueText(row, s);
  const arrow = row.arrow || row.type === 'choice' || row.type === 'text';
  return `<button class="set-row ${row.type}" data-nav data-row="${sectionId}:${i}" ${row.type === 'info' ? 'tabindex="-1"' : ''}>
    <span class="set-label"><b>${esc(row.label)}</b>${row.desc ? `<small>${esc(row.desc)}</small>` : ''}</span>
    ${row.type === 'toggle' ? `<span class="switch ${on ? 'on' : ''}" role="switch" aria-checked="${on}"><i></i></span>` : `<span class="set-value">${esc(val)}</span>`}
    ${arrow ? '<span class="chev" aria-hidden="true">›</span>' : ''}
  </button>`;
}

function renderNav() {
  const s = sections();
  $('setNav').innerHTML = s.map((sec) => `<button class="set-sec ${sec.id === current ? 'active' : ''}" data-nav data-sec="${sec.id}">
      <span class="sec-icon">${icon(sec.id)}</span><span class="sec-name">${sec.name}</span><span class="sec-count">${sec.rows.length}</span>
    </button>`).join('');
  $('setNav').querySelectorAll('[data-sec]').forEach((b) => {
    b.onclick = () => { current = b.dataset.sec; lastSection = current; $('setSearch').value = ''; renderAll(); $('setPanel').querySelector('[data-row]')?.focus(); };
    b.onfocus = () => { if (current !== b.dataset.sec && !$('setSearch').value) { current = b.dataset.sec; lastSection = current; renderPanel(); markNav(); } };
  });
}
function markNav() { $('setNav').querySelectorAll('[data-sec]').forEach((b) => b.classList.toggle('active', b.dataset.sec === current)); }

function renderPanel() {
  const s = store.getSettings();
  const all = sections();
  const q = $('setSearch').value.trim().toLowerCase();
  let html = '';
  if (q) {
    for (const sec of all) {
      const rows = sec.rows.map((r, i) => [r, i]).filter(([r]) => [r.label, r.desc || '', t(r.label), t(r.desc || '')].join(' ').toLowerCase().includes(q));
      if (rows.length) html += `<h3 class="set-group">${sec.name}</h3>` + rows.map(([r, i]) => rowHtml(r, s, i, sec.id)).join('');
    }
    if (!html) html = `<p class="set-empty">No hay ajustes que coincidan con “${esc(q)}”.</p>`;
  } else {
    const sec = all.find((x) => x.id === current) || all[0];
    html = `<h2 class="set-title">${icon(sec.id)} ${sec.name}</h2>` + sec.rows.map((r, i) => rowHtml(r, s, i, sec.id)).join('');
  }
  $('setPanel').innerHTML = html;
  $('setPanel').querySelectorAll('[data-row]').forEach((b) => (b.onclick = () => activateRow(b.dataset.row)));
}
const renderAll = () => { renderNav(); renderPanel(); };

function findRow(ref) {
  const [secId, i] = ref.split(':');
  return sections().find((x) => x.id === secId)?.rows[+i];
}

function save(key, value) {
  store.saveSettings({ [key]: value });
  ctx.apply(key);
}

function activateRow(ref) {
  const row = findRow(ref);
  if (!row) return;
  const s = store.getSettings();
  if (row.type === 'toggle') { save(row.key, !s[row.key]); refocus(ref); return; }
  if (row.type === 'action') { row.run(); refocus(ref); return; }
  if (row.type === 'danger') {
    const el = $('setPanel').querySelector(`[data-row="${ref}"]`);
    if (el.dataset.sure !== '1') {  // confirmación en dos pasos (sin diálogos del navegador)
      el.dataset.sure = '1';
      el.querySelector('.set-label b').textContent = `${row.label}: pulsa otra vez para confirmar`;
      setTimeout(() => { if (el.isConnected && el.dataset.sure === '1') { el.dataset.sure = ''; el.querySelector('.set-label b').textContent = row.label; } }, 3500);
      return;
    }
    Promise.resolve(row.run()).then(() => refocus(ref));
    return;
  }
  if (row.type === 'choice') return openPicker(row, ref);
  if (row.type === 'text') return openText(row, ref);
}

function refocus(ref) { renderAll(); $('setPanel').querySelector(`[data-row="${ref}"]`)?.focus(); }

// ---- Ventanas para elegir opción o escribir un valor ----
function openPicker(row, ref) {
  const s = store.getSettings();
  $('pickTitle').textContent = row.label;
  $('pickHint').textContent = row.desc || '';
  $('pickHint').hidden = !row.desc;
  $('pickBody').innerHTML = `<div class="pick-list">${row.options.map(([v, l]) =>
    `<button class="pick-opt ${String(v) === String(s[row.key]) ? 'on' : ''}" data-nav data-v="${esc(v)}"><span>${esc(l)}</span><i aria-hidden="true">✓</i></button>`).join('')}</div>`;
  $('pickBody').querySelectorAll('[data-v]').forEach((b) => (b.onclick = () => {
    const opt = row.options.find(([v]) => String(v) === b.dataset.v);
    save(row.key, opt[0]);
    closePick(); refocus(ref);
  }));
  showPick();
  ($('pickBody').querySelector('.pick-opt.on') || $('pickBody').querySelector('.pick-opt'))?.focus();
}

function openText(row, ref) {
  const s = store.getSettings();
  $('pickTitle').textContent = row.label;
  $('pickHint').textContent = row.desc || '';
  $('pickHint').hidden = !row.desc;
  $('pickBody').innerHTML = `<form class="pick-form" id="pickForm">
      <input class="field" id="pickInput" data-nav autocomplete="off" type="${row.secret ? 'password' : 'text'}" placeholder="${esc(row.placeholder || '')}" value="${esc(s[row.key] || '')}">
      <div class="actions"><button type="button" class="btn ghost" data-nav id="pickCancel">Cancelar</button><button type="submit" class="btn" data-nav>Guardar</button></div>
    </form>`;
  $('pickForm').onsubmit = (e) => { e.preventDefault(); save(row.key, $('pickInput').value.trim()); closePick(); refocus(ref); };
  $('pickCancel').onclick = () => { closePick(); refocus(ref); };
  showPick();
  $('pickInput').focus();
}

const pickOpen = () => !$('pickSheet').hidden;
function showPick() { $('pickSheet').hidden = false; $('pickBackdrop').hidden = false; }
function closePick() { $('pickSheet').hidden = true; $('pickBackdrop').hidden = true; }

// ---- Abrir / cerrar ----
export const settingsOpen = () => !$('settingsView').hidden;

export function openSettings(section) {
  returnFocus = document.activeElement;
  current = section || lastSection;
  $('setSearch').value = '';
  $('settingsView').hidden = false;
  $('menu').inert = true;
  $('welcome').inert = true;
  renderAll();
  $('setNav').querySelector(`[data-sec="${current}"]`)?.focus();
}

export function closeSettings(restore = true) {
  closePick();
  $('settingsView').hidden = true;
  $('menu').inert = !$('welcome').hidden;
  $('welcome').inert = false;
  if (restore && returnFocus?.isConnected) returnFocus.focus();
}

/** Teclas cuando Ajustes está abierto. Devuelve true si la consumió. */
export function handleSettingsKey(e) {
  if (!settingsOpen()) return false;
  const dir = keyDir(e);
  const inText = e.target.matches?.('input');
  if (pickOpen()) {
    if (isBack(e) && !(inText && e.key === 'Backspace')) { e.preventDefault(); closePick(); $('setPanel').querySelector('[data-row]:focus, [data-row]')?.focus(); return true; }
    if (dir && !(inText && (dir === 'left' || dir === 'right'))) { e.preventDefault(); moveFocus(dir, $('pickSheet')); }
    return true;
  }
  if (isBack(e) && !(inText && e.key === 'Backspace')) {
    e.preventDefault();
    if ($('setPanel').contains(document.activeElement)) $('setNav').querySelector(`[data-sec="${current}"]`)?.focus() || $('setNav').querySelector('.active')?.focus();
    else closeSettings();
    return true;
  }
  if (inText && dir === 'down') { e.preventDefault(); $('setPanel').querySelector('[data-row]')?.focus(); return true; }
  if (inText) return true;
  if (dir) {
    e.preventDefault();
    const inNav = $('setNav').contains(document.activeElement);
    const inPanel = $('setPanel').contains(document.activeElement);
    if (inNav && dir === 'right') { $('setPanel').querySelector('[data-row]')?.focus(); return true; }
    if (inPanel && dir === 'left') { ($('setNav').querySelector('.active') || $('setNav').querySelector('[data-sec]'))?.focus(); return true; }
    if (inNav || inPanel) {
      const list = [...(inNav ? $('setNav') : $('setPanel')).querySelectorAll('[data-nav]:not([tabindex="-1"])')];
      const i = list.indexOf(document.activeElement);
      if (dir === 'down' && i < list.length - 1) list[i + 1].focus();
      else if (dir === 'up' && i > 0) list[i - 1].focus();
      else if (dir === 'up') $('setSearch').focus();
      document.activeElement.scrollIntoView({ block: 'nearest' });
      return true;
    }
    moveFocus(dir, $('settingsView'));
    return true;
  }
  if (isOk(e) && e.target.matches('.switch')) { e.preventDefault(); e.target.closest('[data-row]')?.click(); }
  return true;
}

/** Vuelve a dibujar los valores (p. ej. cuando termina de actualizarse la guía). */
export function refreshSettings() { if (settingsOpen() && !pickOpen()) { const f = document.activeElement?.dataset?.row; renderPanel(); if (f) $('setPanel').querySelector(`[data-row="${f}"]`)?.focus(); } }

export function initSettings(appCtx) {
  ctx = appCtx;
  $('setBack').onclick = () => closeSettings();
  $('pickBackdrop').onclick = () => closePick();
  $('setSearch').addEventListener('input', () => renderPanel());
}
