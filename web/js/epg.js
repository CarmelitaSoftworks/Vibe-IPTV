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
const norm = (s) => (s || '').toLowerCase().replace(/[^a-z0-9]/g, '');

// Conserva sólo una ventana de tiempo útil para no llenar la memoria
export function parseXmltv(xml, now = Date.now(), pastMs = 3 * 3600e3, futureMs = 36 * 3600e3) {
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
    const desc = decode((/<desc[^>]*>([\s\S]*?)<\/desc>/.exec(m[2]) || [])[1] || '');
    if (!byId.has(ch)) byId.set(ch, []);
    byId.get(ch).push({ start, stop, title, desc });
  }
  for (const list of byId.values()) list.sort((a, b) => a.start - b.start);
  return new Epg(byId, nameToId);
}

export class Epg {
  constructor(byId = new Map(), nameToId = new Map()) { this.byId = byId; this.nameToId = nameToId; }
  listFor(item) {
    if (!item) return [];
    if (item.tvgId && this.byId.has(item.tvgId)) return this.byId.get(item.tvgId);
    for (const n of [item.tvgName, item.name]) {
      const id = this.nameToId.get(norm(n));
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
  const buf = await fetchSmart(url, 'buffer');
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
