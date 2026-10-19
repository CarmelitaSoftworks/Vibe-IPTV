// Red: decide cuándo pasar por el proxy local (CORS / contenido mixto http en página https)
import { getSettings } from './storage.js';

export function proxyBase() {
  const s = getSettings();
  if (s.proxyUrl) return s.proxyUrl.replace(/\/+$/, '');
  // Si la app la sirve nuestro propio server.js, el proxy está en el mismo origen
  if (window.__IPTV_PROXY__) return location.origin;
  return '';
}

export function proxify(url, item = {}) {
  const base = proxyBase();
  if (!base) return url;
  const p = new URLSearchParams({ url });
  if (item.userAgent) p.set('ua', item.userAgent);
  if (item.referrer) p.set('ref', item.referrer);
  return `${base}/proxy?${p}`;
}

function isMixed(url) {
  return location.protocol === 'https:' && url.startsWith('http:');
}

// Servidores que ya sabemos que necesitan proxy (se recuerda durante la sesión)
const needsProxy = new Set();
const hostOf = (u) => { try { return new URL(u).host; } catch { return ''; } };
export function markNeedsProxy(url) { needsProxy.add(hostOf(url)); }

// Lista ordenada de URLs a intentar según el modo de proxy
export function candidates(url, item = {}) {
  const mode = getSettings().proxyMode; // auto | always | never
  const hasProxy = !!proxyBase();
  if (!hasProxy || mode === 'never') return [url];
  if (mode === 'always' || isMixed(url) || needsProxy.has(hostOf(url))) return [proxify(url, item)];
  return [url, proxify(url, item)];
}

async function fetchOnce(url, as) {
  const res = await fetch(url);
  if (!res.ok) {
    const why = res.headers.get('x-proxy-error');
    throw new Error(why ? decodeURIComponent(why) : `el servidor respondió con error ${res.status}`);
  }
  return as === 'json' ? res.json() : as === 'buffer' ? res.arrayBuffer() : res.text();
}

export async function fetchSmart(url, as = 'text') {
  let lastErr;
  const list = candidates(url);
  for (const u of list) {
    try {
      const r = await fetchOnce(u, as);
      if (u !== list[0]) markNeedsProxy(url);
      return r;
    } catch (e) { lastErr = e; }
  }
  if (window.__IPTV_ARTIFACT__) {
    throw new Error(`No se pudo conectar con ${hostOf(url) || 'el servidor'}: esta vista previa no puede abrir servidores externos. Prueba tu lista en la app web local o en Android.`);
  }
  const hint = proxyBase() ? '' : ' Posible bloqueo del navegador (CORS): abre la app con "node server/server.js".';
  throw new Error(`No se pudo conectar con ${hostOf(url) || 'el servidor'}: ${lastErr?.message || lastErr}.${hint}`);
}

/** Oculta usuario y contraseña en cualquier URL que se vaya a mostrar. */
export function redact(text) {
  return String(text)
    .replace(/(username|password|user|pass)=([^&\s"']+)/gi, '$1=•••')
    .replace(/\/(live|movie|series)\/[^/\s]+\/[^/\s]+\//gi, '/$1/•••/•••/');
}
