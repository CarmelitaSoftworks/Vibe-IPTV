// Contenido de demostración (versión artefacto): videos de prueba publicados junto a la página
import { parseXmltv } from './epg.js';

const CHANNELS = [
  { id: 'noticias24.demo', name: 'Noticias 24', group: 'Noticias', color: '#1f6fb2', short: 'N24' },
  { id: 'deportes.demo', name: 'Deportes HD', group: 'Deportes', color: '#2e8b57', short: 'DEP' },
  { id: 'cineclasico.demo', name: 'Cine Clásico', group: 'Entretenimiento', color: '#8a3b2e', short: 'CINE' },
  { id: 'musica.demo', name: 'Música Pop', group: 'Entretenimiento', color: '#8e3fa8', short: 'POP' },
  { id: 'docu.demo', name: 'Documentales', group: 'Cultura', color: '#b07a1a', short: 'DOCU' },
  { id: 'infantil.demo', name: 'Infantil', group: 'Infantil', color: '#d0533f', short: 'KIDS' },
];
const MOVIES = [
  { name: 'La Cordillera (2023)', group: 'Drama', file: 'movie1' },
  { name: 'Valparaíso Nocturno (2021)', group: 'Acción', file: 'movie2' },
  { name: 'El Último Tren (2019)', group: 'Drama', file: 'movie3' },
];
const EPISODES = ['Historias del Sur S01E01 El regreso', 'Historias del Sur S01E02 La tormenta', 'Historias del Sur S01E03 El faro'];

function logo(text, color) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 90"><rect x="4" y="4" width="152" height="82" rx="18" fill="${color}"/><text x="80" y="57" font-family="Arial,Helvetica,sans-serif" font-size="26" font-weight="700" fill="#fff" text-anchor="middle">${text}</text></svg>`;
  return 'data:image/svg+xml;base64,' + btoa(svg);
}
function poster(title, color) {
  const t = title.replace(/\s*\(.*\)/, '');
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 290"><defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${color}"/><stop offset="1" stop-color="#111"/></linearGradient></defs><rect width="200" height="290" fill="url(#g)"/><circle cx="150" cy="70" r="38" fill="#fff" opacity=".15"/><path d="M0 230 L60 170 L110 210 L150 160 L200 210 V290 H0Z" fill="#000" opacity=".35"/><text x="16" y="250" font-family="Arial,Helvetica,sans-serif" font-size="20" font-weight="700" fill="#fff">${t}</text></svg>`;
  return 'data:image/svg+xml;base64,' + btoa(unescape(encodeURIComponent(svg)));
}

export function demoM3U() {
  const L = ['#EXTM3U'];
  CHANNELS.forEach((c, i) => {
    L.push(`#EXTINF:-1 tvg-id="${c.id}" tvg-logo="${logo(c.short, c.color)}" group-title="${c.group}",${c.name}`);
    L.push(`media/live${i + 1}/index.hls.txt`); // HLS (el visor sólo sirve ciertas extensiones)
  });
  MOVIES.forEach((m, i) => {
    L.push(`#EXTINF:-1 tvg-logo="${poster(m.name, ['#3d6b8f', '#7a2f3b', '#4f5d2f'][i])}" group-title="Películas | ${m.group}",${m.name}`);
    L.push(`media/${m.file}.mp4`);
  });
  EPISODES.forEach((e, i) => {
    L.push(`#EXTINF:-1 tvg-logo="${poster('Historias del Sur', '#2f5d6b')}" group-title="Series | Drama",${e}`);
    L.push(`media/ep${i + 1}.mp4`);
  });
  return L.join('\n');
}

const SHOWS = {
  'noticias24.demo': ['Noticiero matinal', 'Economía al día', 'Noticiero central', 'Reportajes', 'Noticias de la noche', 'Entrevista'],
  'deportes.demo': ['Resumen de la fecha', 'Fútbol en vivo', 'Tenis: cuartos de final', 'Debate deportivo', 'Básquetbol', 'Goles de la semana'],
  'cineclasico.demo': ['Ciclo de cine negro', 'Matiné familiar', 'Grandes directores', 'Western', 'Comedia clásica', 'Trasnoche'],
  'musica.demo': ['Top 20', 'Acústicos', 'Clips del recuerdo', 'En concierto', 'Novedades', 'Lista de éxitos'],
  'docu.demo': ['Patagonia salvaje', 'Océanos', 'Historia de Chile', 'Ciencia hoy', 'Volcanes', 'Ciudades del mundo'],
  'infantil.demo': ['Dibujos animados', 'Aprendo jugando', 'Cuentos', 'Aventuras', 'Manualidades', 'Música para niños'],
};

export function demoEpg(now = Date.now()) {
  const pad = (n) => String(n).padStart(2, '0');
  const t = (ms) => { const d = new Date(ms); return `${d.getUTCFullYear()}${pad(d.getUTCMonth() + 1)}${pad(d.getUTCDate())}${pad(d.getUTCHours())}${pad(d.getUTCMinutes())}00 +0000`; };
  const base = Math.floor(now / 3600e3) * 3600e3 - 3600e3;
  let xml = '<tv>';
  for (const c of CHANNELS) {
    xml += `<channel id="${c.id}"><display-name>${c.name}</display-name></channel>`;
    SHOWS[c.id].forEach((title, i) => {
      const s = base + i * 3600e3 * (c.id === 'cineclasico.demo' ? 2 : 1);
      const e = s + 3600e3 * (c.id === 'cineclasico.demo' ? 2 : 1);
      xml += `<programme start="${t(s)}" stop="${t(e)}" channel="${c.id}"><title>${title}</title></programme>`;
    });
  }
  return parseXmltv(xml + '</tv>', now);
}

export const DEMO_SOURCE = { id: 'demo', name: 'Demostración', type: 'm3u', url: '', demo: true };
