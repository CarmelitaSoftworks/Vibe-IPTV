// Guía de programación XMLTV (admite .xml y .xml.gz)
import { fetchSmart, proxyBase } from './net.js';

const ENT = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
function decode(s) {
  return s
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
    .replace(/&(#x[0-9a-f]+|#\d+|\w+);/gi, (m, e) => {
      if (e[0] === '#') return String.fromCodePoint(e[1] === 'x' || e[1] === 'X' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10));
      return ENT[e] ?? m;
    })
    .trim();
}

// "20261019120000 +0000" -> ms
export function parseXmltvTime(t) {
  const m = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*([+-]\d{2})?(\d{2})?/.exec((t || '').trim());
  if (!m) return NaN;
  const [, y, mo, d, h, mi, s = '00', oh = '+00', om = '00'] = m;
  const utc = Date.UTC(+y, +mo - 1, +d, +h, +mi, +s);
  const sign = oh[0] === '-' ? -1 : 1;
  return utc - sign * (Math.abs(+oh) * 60 + +om) * 60000;
}

const attr = (s, name) => (new RegExp(`${name}\\s*=\\s*"([^"]*)"`).exec(s) || [])[1] || '';
// Sin tildes ni signos: "Señal Uno" = "senal uno" (antes se borraban las letras con tilde y no calzaban)
const norm = (s) => (s || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]/g, '');
const COUNTRIES = new Set(('AR BO BR CA CL CO CR CU DE DO EC ES FR GB GT HN IT MX NI PA PE PR PT PY SV US UY VE ' +
  'UK USA LAT LATAM EU').split(' '));
const QUALITY = /(uhd|fhd|hd|sd|4k|8k|hevc|h265|h264|1080p?|720p?|2160p?|backup|bkp)+$/;
/** Nombre "suelto": sin país al principio ("CL: ", "|AR| ") ni calidad al final ("HD", "4K"). */
export function loose(s) {
  const raw = String(s || '').trim();
  // Sólo códigos de país reales: "CL: Canal 13" sí, "HBO | Family" o "TNT - Series" no
  const m = /^\W*([A-Za-z]{2,3})\W*?\s*[:|\]\-]+\s*/.exec(raw);
  const noCountry = (m && COUNTRIES.has(m[1].toUpperCase()) ? raw.slice(m[0].length) : raw) || raw;
  let n = norm(noCountry);
  for (let i = 0; i < 2; i++) n = n.replace(QUALITY, '');
  return n || norm(raw);
}

// Conserva sólo una ventana de tiempo útil para no llenar la memoria
// 24 h hacia atrás (para ver en diferido lo de hoy) y 48 h hacia adelante
export function parseXmltv(xml, now = Date.now(), pastMs = 24 * 3600e3, futureMs = 48 * 3600e3) {
  const byId = new Map();
  const nameToId = new Map();
  const chRe = /<channel\b([^>]*)>([\s\S]*?)<\/channel>/g;
  let m;
  while ((m = chRe.exec(xml))) {
    const id = decode(attr(m[1], 'id'));
    const dnRe = /<display-name[^>]*>([\s\S]*?)<\/display-name>/g;
    let d;
    while ((d = dnRe.exec(m[2]))) nameToId.set(norm(decode(d[1])), id);
  }
  const pRe = /<programme\b([^>]*)>([\s\S]*?)<\/programme>/g;
  while ((m = pRe.exec(xml))) {
    const start = parseXmltvTime(attr(m[1], 'start'));
    const stop = parseXmltvTime(attr(m[1], 'stop'));
    if (!(stop > now - pastMs && start < now + futureMs)) continue;
    const ch = decode(attr(m[1], 'channel'));
    const title = decode((/<title[^>]*>([\s\S]*?)<\/title>/.exec(m[2]) || [])[1] || '');
    const desc = decode((/<desc[^>]*>([\s\S]*?)<\/desc>/.exec(m[2]) || [])[1] || '').slice(0, 600);
    if (!byId.has(ch)) byId.set(ch, []);
    byId.get(ch).push({ start, stop, title, desc });
  }
  for (const list of byId.values()) list.sort((a, b) => a.start - b.start);
  return new Epg(byId, nameToId);
}

/** Para buscar: sin tildes, en minúsculas y con espacios en vez de signos. */
export const fold = (s) => String(s || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();

/**
 * Buscar un programa en toda la guía: todas las palabras, sin tildes ni mayúsculas; primero por título y después por
 * descripción. Lo que se está dando ahora va primero; después, por hora.
 */
export function searchGuide(epg, channels, query, from, now, limit = 80) {
  const words = fold(query).split(' ').filter((w) => w.length >= 2);
  if (!words.length) return [];
  const seen = new Set(), hits = [];
  for (const ch of channels) {
    for (const p of epg.listFor(ch)) {
      if (p.stop <= from) continue;
      const t = fold(p.title);
      const score = words.every((w) => t.includes(w)) ? 2 : p.desc && words.every((w) => fold(p.desc).includes(w)) ? 1 : 0;
      if (!score) continue;
      const k = `${ch.url}|${p.start}`;
      if (seen.has(k)) continue;
      seen.add(k); hits.push([ch, p, score]);
    }
    if (hits.length >= 2000) break;
  }
  const live = (p) => p.start <= now && p.stop > now;
  hits.sort((a, b) => (live(b[1]) - live(a[1])) || (b[2] - a[2]) || (a[1].start - b[1].start));
  return hits.slice(0, limit).map(([ch, p]) => [ch, p]);
}

export class Epg {
  constructor(byId = new Map(), nameToId = new Map()) { this.byId = byId; this.nameToId = nameToId; }
  /** Índices extra (se arman la primera vez): id sin mayúsculas y nombres sin país ni calidad. */
  index() {
    if (this._lower) return;
    this._lower = new Map([...this.byId.keys()].map((k) => [k.toLowerCase(), k]));
    this._loose = new Map();
    for (const [n, id] of this.nameToId) if (!this._loose.has(loose(n))) this._loose.set(loose(n), id);
    for (const id of this.byId.keys()) { const k = loose(id.replace(/\.[^.]*$/, '')); if (!this._loose.has(k)) this._loose.set(k, id); }
  }
  listFor(item) {
    if (!item) return [];
    if (item.tvgId && this.byId.has(item.tvgId)) return this.byId.get(item.tvgId);
    this.index();
    if (item.tvgId) { const id = this._lower.get(item.tvgId.toLowerCase()); if (id) return this.byId.get(id); }
    for (const n of [item.tvgName, item.name]) {
      const id = this.nameToId.get(norm(n));
      if (id && this.byId.has(id)) return this.byId.get(id);
    }
    for (const n of [item.tvgName, item.name, (item.tvgId || '').replace(/\.[^.]*$/, '')]) {
      if (!n) continue;
      const id = this._loose.get(loose(n));
      if (id && this.byId.has(id)) return this.byId.get(id);
    }
    return [];
  }
  nowNext(item, now = Date.now()) {
    const list = this.listFor(item);
    const i = list.findIndex((p) => p.start <= now && p.stop > now);
    if (i < 0) return { now: null, next: list.find((p) => p.start > now) || null };
    return { now: list[i], next: list[i + 1] || null };
  }
}

async function gunzipIfNeeded(buf, url) {
  const b = new Uint8Array(buf);
  if (b[0] === 0x1f && b[1] === 0x8b) {
    if ('DecompressionStream' in window) {
      const stream = new Blob([b]).stream().pipeThrough(new DecompressionStream('gzip'));
      return await new Response(stream).text();
    }
    // Navegador sin descompresión (Safari antes de iOS 16.4): la hace el servidor
    if (proxyBase() && url) {
      const r = await fetch(`${proxyBase()}/proxy/gunzip?${new URLSearchParams({ url })}`);
      if (r.ok) return await r.text();
    }
    throw new Error('Este navegador no puede abrir guías comprimidas (.gz): actualiza iOS o usa la guía sin comprimir');
  }
  return new TextDecoder('utf-8').decode(b);
}

async function loadOne(url) {
  // Un reintento si el servidor de la guía falla (suelen estar saturados a la hora en punto)
  const buf = await fetchSmart(url, 'buffer').catch(() => new Promise((r) => setTimeout(r, 3000)).then(() => fetchSmart(url, 'buffer')));
  return parseXmltv(await gunzipIfNeeded(buf, url));
}

// Admite varias guías separadas por coma o espacio; se combinan (la primera tiene prioridad)
export async function loadEpg(urls) {
  const list = String(urls).split(/[\s,]+/).filter(Boolean);
  const results = await Promise.allSettled(list.map(loadOne));
  const ok = results.filter((r) => r.status === 'fulfilled').map((r) => r.value);
  if (!ok.length) throw results[0]?.reason || new Error('Sin guía');
  const merged = new Epg();
  for (const e of ok) {
    for (const [id, progs] of e.byId) if (!merged.byId.has(id)) merged.byId.set(id, progs);
    for (const [n, id] of e.nameToId) if (!merged.nameToId.has(n)) merged.nameToId.set(n, id);
  }
  return merged;
}
