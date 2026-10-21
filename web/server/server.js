#!/usr/bin/env node
// Servidor local sin dependencias: sirve la app web y hace de proxy para
// - evitar bloqueos CORS de los proveedores IPTV
// - reproducir enlaces http:// desde una página https://
// - reescribir listas HLS (.m3u8) para que los segmentos también pasen por el proxy
//
// Uso:  node server/server.js           (http://localhost:8080)
//       PORT=9000 HOST=0.0.0.0 node server/server.js   (accesible desde la red local / TV)
//       VIBE_PASSWORD=secreta HOST=0.0.0.0 node server/server.js   (pide contraseña)
// No lo expongas a Internet sin contraseña ni HTTPS: es un proxy. Ver docs/SERVIDOR-PROPIO.md

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const { URL } = require('url');
const net = require('net');
const tls = require('tls');
const { Transform, PassThrough } = require('stream');
const { spawn, spawnSync } = require('child_process');
const os = require('os');
const crypto = require('crypto');
const zlib = require('zlib');
const subtitles = require('./subtitles.js');

// ---- ffmpeg (opcional): convierte al vuelo formatos que el navegador no reproduce ----
function findFfmpeg() {
  const cands = [process.env.FFMPEG_PATH];
  try { cands.push(require('ffmpeg-static')); } catch { /* no instalado */ }
  cands.push(path.join(__dirname, '..', 'tools', process.platform === 'win32' ? 'ffmpeg.exe' : 'ffmpeg'), 'ffmpeg');
  for (const c of cands.filter(Boolean)) {
    try { if (spawnSync(c, ['-version'], { timeout: 8000 }).status === 0) return c; } catch { /* siguiente */ }
  }
  return null;
}
const FFMPEG = findFfmpeg();
let currentFfmpeg = null;   // una conversión a la vez (muchas cuentas permiten una sola conexión)

const PORT = Number(process.env.PORT) || 8080;
const HOST = (process.env.HOST || '').trim() || (process.argv.includes('--lan') ? '0.0.0.0' : '127.0.0.1');
const ROOT = path.resolve(__dirname, '..');
const DEFAULT_UA = process.env.IPTV_UA || 'VLC/3.0.21 LibVLC/3.0.21';
const MAX_REDIRECTS = 6;

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json', '.webmanifest': 'application/manifest+json',
  '.ico': 'image/x-icon', '.wasm': 'application/wasm', '.gz': 'application/octet-stream',
};

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, HEAD, OPTIONS',
  'Access-Control-Allow-Headers': 'Range, Content-Type, Api-Key, Authorization, X-User-Agent, Accept',
  'Access-Control-Expose-Headers': 'Content-Length, Content-Range, Accept-Ranges, Content-Type',
};

function proxyUrlFor(target, req, extra) {
  const p = new URLSearchParams({ url: target });
  if (extra.ua) p.set('ua', extra.ua);
  if (extra.ref) p.set('ref', extra.ref);
  return `/proxy?${p}`;
}

// Reescribe cada URI de una playlist HLS (líneas y atributos URI="...")
function rewriteM3u8(text, baseUrl, req, extra) {
  const abs = (u) => { try { return new URL(u, baseUrl).href; } catch { return u; } };
  return text.split(/\r?\n/).map((line) => {
    const t = line.trim();
    if (!t) return line;
    if (t.startsWith('#')) return line.replace(/URI="([^"]+)"/g, (_, u) => `URI="${proxyUrlFor(abs(u), req, extra)}"`);
    return proxyUrlFor(abs(t), req, extra);
  }).join('\n');
}

function isPlaylist(url, ctype) {
  return /mpegurl/i.test(ctype || '') || /\.m3u8?$/i.test(url.pathname);
}

function upstream(target, req, extra, body, redirects = 0) {
  return new Promise((resolve, reject) => {
    const url = new URL(target);
    if (!/^https?:$/.test(url.protocol)) return reject(new Error('Sólo http/https'));
    const lib = url.protocol === 'https:' ? https : http;
    const headers = {
      'User-Agent': extra.ua || req.headers['x-user-agent'] || DEFAULT_UA,
      Accept: req.headers.accept || '*/*',
      Connection: 'keep-alive',
    };
    if (extra.ref) { headers.Referer = extra.ref; try { headers.Origin = new URL(extra.ref).origin; } catch {} }
    for (const h of ['range', 'api-key', 'authorization', 'content-type']) if (req.headers[h]) headers[h] = req.headers[h];
    if (body) headers['content-length'] = Buffer.byteLength(body);
    const r = lib.request(url, {
      method: req.method === 'HEAD' ? 'HEAD' : req.method, headers, timeout: 12000,
      insecureHTTPParser: true,   // muchos paneles IPTV envían cabeceras mal formadas que VLC acepta
      autoSelectFamily: true,     // prueba IPv4 si el IPv6 del servidor no responde
      rejectUnauthorized: false,  // certificados vencidos o autofirmados, comunes en paneles IPTV
    }, (res) => {
      if ([301, 302, 303, 307, 308].includes(res.statusCode) && res.headers.location && redirects < MAX_REDIRECTS) {
        res.resume();
        const next = new URL(res.headers.location, url).href;
        const keepBody = res.statusCode === 307 || res.statusCode === 308;
        const nreq = keepBody ? req : { ...req, method: req.method === 'POST' ? 'GET' : req.method, headers: req.headers };
        return resolve(upstream(next, nreq, extra, keepBody ? body : null, redirects + 1));
      }
      res.finalUrl = url;
      resolve(res);
    });
    r.on('timeout', () => r.destroy(new Error('Tiempo de espera agotado')));
    r.on('error', (e) => {
      // Respuesta con formato HTTP inválido (habitual en paneles IPTV): reintentar con un lector tolerante
      if (/^HPE_/.test(e.code || '')) resolve(rawUpstream(target, req, extra, body, redirects));
      else reject(e);
    });
    if (body) r.write(body);
    r.end();
  });
}

// ---- Lector HTTP tolerante (para servidores que Node rechaza por cabeceras mal formadas) ----
class ChunkDecoder extends Transform {
  constructor() { super(); this.buf = Buffer.alloc(0); this.left = -1; this.done = false; }
  _transform(chunk, _enc, cb) {
    this.buf = Buffer.concat([this.buf, chunk]);
    while (!this.done) {
      if (this.left < 0) {
        const i = this.buf.indexOf('\n');
        if (i < 0) break;
        const size = parseInt(this.buf.slice(0, i).toString().trim().split(';')[0], 16);
        this.buf = this.buf.slice(i + 1);
        if (!size) { this.done = true; break; }
        this.left = size;
      }
      if (this.buf.length < this.left) { if (this.buf.length) { this.push(this.buf); this.left -= this.buf.length; this.buf = Buffer.alloc(0); } break; }
      this.push(this.buf.slice(0, this.left));
      this.buf = this.buf.slice(this.left);
      this.left = -1;
      const nl = this.buf.indexOf('\n');           // fin de línea tras el bloque
      if (nl < 0) { this.left = -2; break; }
      this.buf = this.buf.slice(nl + 1);
    }
    if (this.left === -2) { const nl = this.buf.indexOf('\n'); if (nl >= 0) { this.buf = this.buf.slice(nl + 1); this.left = -1; } }
    cb();
  }
}

function rawUpstream(target, req, extra, body, redirects = 0) {
  return new Promise((resolve, reject) => {
    const url = new URL(target);
    const https = url.protocol === 'https:';
    const port = Number(url.port) || (https ? 443 : 80);
    const sock = https
      ? tls.connect({ host: url.hostname, port, servername: url.hostname, rejectUnauthorized: false })
      : net.connect(port, url.hostname);
    sock.setTimeout(12000, () => sock.destroy(Object.assign(new Error('Tiempo de espera agotado'), { code: 'ETIMEDOUT' })));
    sock.on('error', reject);
    const lines = [
      `${req.method === 'HEAD' ? 'HEAD' : req.method} ${url.pathname}${url.search} HTTP/1.1`,
      `Host: ${url.host}`,
      `User-Agent: ${extra.ua || req.headers['x-user-agent'] || DEFAULT_UA}`,
      'Accept: */*', 'Connection: close',
    ];
    if (extra.ref) lines.push(`Referer: ${extra.ref}`);
    if (req.headers.range) lines.push(`Range: ${req.headers.range}`);
    for (const h of ['api-key', 'authorization', 'content-type']) if (req.headers[h]) lines.push(`${h}: ${req.headers[h]}`);
    if (body) lines.push(`Content-Length: ${body.length}`);
    sock.on(https ? 'secureConnect' : 'connect', () => { sock.write(lines.join('\r\n') + '\r\n\r\n'); if (body) sock.write(body); });

    let head = Buffer.alloc(0);
    const onData = (chunk) => {
      head = Buffer.concat([head, chunk]);
      let end = head.indexOf('\r\n\r\n'), skip = 4;
      const lf = head.indexOf('\n\n');
      if (lf >= 0 && (end < 0 || lf < end)) { end = lf; skip = 2; }
      if (end < 0) { if (head.length > 65536) sock.destroy(new Error('Cabeceras demasiado grandes')); return; }
      sock.removeListener('data', onData);
      sock.setTimeout(0);
      const [status, ...hdrs] = head.slice(0, end).toString('latin1').split(/\r?\n/);
      const statusCode = Number((/^HTTP\/\d(?:\.\d)?\s+(\d{3})/i.exec(status) || [])[1]) || 200;
      const headers = {};
      for (const l of hdrs) { const i = l.indexOf(':'); if (i > 0) headers[l.slice(0, i).trim().toLowerCase()] = l.slice(i + 1).trim(); }
      if ([301, 302, 303, 307, 308].includes(statusCode) && headers.location && redirects < MAX_REDIRECTS) {
        sock.destroy();
        return resolve(upstream(new URL(headers.location, url).href, req, extra, null, redirects + 1));
      }
      const out = new PassThrough();
      Object.assign(out, { statusCode, headers, finalUrl: url });
      const rest = head.slice(end + skip);
      if (/chunked/i.test(headers['transfer-encoding'] || '')) {
        delete headers['transfer-encoding']; delete headers['content-length'];
        const dec = new ChunkDecoder();
        dec.pipe(out);
        if (rest.length) dec.write(rest);
        sock.pipe(dec);
      } else {
        if (rest.length) out.write(rest);
        sock.pipe(out);
      }
      out.destroy = ((d) => (err) => { sock.destroy(); return d.call(out, err); })(out.destroy);
      resolve(out);
    };
    sock.on('data', onData);
  });
}

function readBody(req) {
  return new Promise((resolve) => {
    if (req.method !== 'POST') return resolve(null);
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks)));
  });
}

// Traduce errores de red a algo entendible
function explain(e) {
  const c = e.code || '';
  if (c === 'ENOTFOUND' || c === 'EAI_AGAIN') return 'No se encontró el dominio del servidor (revisa la dirección)';
  if (c === 'ECONNREFUSED') return 'El servidor rechazó la conexión (¿falta el puerto, por ejemplo :8080?)';
  if (c === 'ECONNRESET') return 'El servidor cortó la conexión (¿http en vez de https, o puerto equivocado?)';
  if (c === 'ETIMEDOUT' || /tiempo de espera|timeout/i.test(e.message)) return 'El servidor no responde (revisa la dirección y el puerto)';
  if (/EPROTO|SSL|TLS|certificate|CERT_/i.test(c + e.message)) return 'Error de conexión segura: prueba la dirección con http:// en vez de https://';
  if (/^HPE_/.test(c)) return 'El servidor envió una respuesta inválida';
  if (c === 'ENETUNREACH' || c === 'EHOSTUNREACH') return 'No hay ruta hacia el servidor (red o firewall)';
  return e.message;
}

// ---- Diagnóstico de un canal: formato, redirecciones y códecs (lee sólo el comienzo) ----
const STREAM_TYPES = {
  0x01: ['video', 'MPEG-1'], 0x02: ['video', 'MPEG-2'], 0x10: ['video', 'MPEG-4'], 0x1b: ['video', 'H.264'], 0x24: ['video', 'H.265'],
  0x03: ['audio', 'MP2'], 0x04: ['audio', 'MP2'], 0x0f: ['audio', 'AAC'], 0x11: ['audio', 'AAC-LATM'], 0x81: ['audio', 'AC-3'], 0x87: ['audio', 'E-AC-3'],
};

function tsCodecs(buf) {
  let pmtPid = -1;
  const out = { video: [], audio: [] };
  for (let i = buf.indexOf(0x47); i >= 0 && i + 188 <= buf.length; i += 188) {
    if (buf[i] !== 0x47) { const n = buf.indexOf(0x47, i + 1); if (n < 0) break; i = n - 188; continue; }
    const pid = ((buf[i + 1] & 0x1f) << 8) | buf[i + 2];
    if (!(buf[i + 1] & 0x40)) continue;                    // sólo inicio de sección
    const afc = (buf[i + 3] >> 4) & 3;
    let p = i + 4 + (afc & 2 ? 1 + buf[i + 4] : 0);
    if (p >= i + 188) continue;
    p += 1 + buf[p];                                       // pointer field
    const len = ((buf[p + 1] & 0x0f) << 8) | buf[p + 2];
    const end = Math.min(p + 3 + len - 4, i + 188);
    if (pid === 0 && buf[p] === 0x00) {
      for (let j = p + 8; j + 4 <= end; j += 4) {
        const prog = (buf[j] << 8) | buf[j + 1];
        if (prog) { pmtPid = ((buf[j + 2] & 0x1f) << 8) | buf[j + 3]; break; }
      }
    } else if (pid === pmtPid && buf[p] === 0x02) {
      const pil = ((buf[p + 10] & 0x0f) << 8) | buf[p + 11];
      for (let j = p + 12 + pil; j + 5 <= end;) {
        const type = buf[j];
        const esil = ((buf[j + 3] & 0x0f) << 8) | buf[j + 4];
        let info = STREAM_TYPES[type];
        if (type === 0x06) {                               // privado: buscar descriptor AC-3 / E-AC-3
          for (let d = j + 5; d < j + 5 + esil; d += 2 + buf[d + 1]) {
            if (buf[d] === 0x6a) info = ['audio', 'AC-3'];
            if (buf[d] === 0x7a) info = ['audio', 'E-AC-3'];
          }
        }
        if (info) out[info[0]].push(info[1]);
        j += 5 + esil;
      }
      return out;
    }
  }
  return null;
}

function readSome(up, max = 400 * 1024, ms = 6000) {
  return new Promise((resolve) => {
    const chunks = []; let size = 0; let done = false;
    const finish = () => { if (done) return; done = true; clearTimeout(t); up.destroy?.(); resolve(Buffer.concat(chunks)); };
    const t = setTimeout(finish, ms);
    up.on('data', (c) => { chunks.push(c); size += c.length; if (size >= max) finish(); });
    up.on('end', finish); up.on('error', finish);
  });
}

async function probe(target, extra, depth = 0) {
  const fakeReq = { method: 'GET', headers: {} };
  const r = { host: '', status: 0, contentType: '', kind: 'desconocido' };
  try { r.host = new URL(target).host; } catch {}
  let up;
  try { up = await upstream(target, fakeReq, extra, null); }
  catch (e) { r.error = explain(e); return r; }
  r.status = up.statusCode; r.contentType = up.headers['content-type'] || ''; r.finalHost = up.finalUrl.host;
  if (up.statusCode >= 400) { up.resume?.(); up.destroy?.(); return r; }
  const buf = await readSome(up);
  r.bytes = buf.length;
  const text = buf.slice(0, 2048).toString('utf8');
  if (text.trimStart().startsWith('#EXTM3U')) {
    r.kind = 'm3u8';
    const full = buf.toString('utf8');
    const lines = full.split(/\r?\n/).map((l) => l.trim());
    r.encrypted = /#EXT-X-KEY:METHOD=(?!NONE)/.test(full);
    const codecs = /CODECS="([^"]+)"/.exec(full);
    if (codecs) r.declaredCodecs = codecs[1];
    const next = lines.find((l) => l && !l.startsWith('#'));
    if (next && depth < 2) r.next = await probe(new URL(next, up.finalUrl).href, extra, depth + 1);
  } else if (buf[0] === 0x47 || buf.indexOf(Buffer.from([0x47])) >= 0 && buf[buf.indexOf(0x47) + 188] === 0x47) {
    r.kind = 'ts';
    r.codecs = tsCodecs(buf);
  } else if (buf.slice(4, 8).toString() === 'ftyp') r.kind = 'mp4';
  else if (/^\s*</.test(text)) r.kind = 'html';
  else if (/^\s*[{[]/.test(text)) r.kind = 'json';
  return r;
}

const leafProbe = (r) => { while (r && r.next) r = r.next; return r; };

/** Argumentos de entrada y códecs de ffmpeg: copia lo que el navegador ya soporta y convierte sólo lo necesario. */
async function ffmpegArgs(target, reqUrl) {
  const ua = reqUrl.searchParams.get('ua') || DEFAULT_UA;
  const ref = reqUrl.searchParams.get('ref') || '';
  const full = reqUrl.searchParams.get('mode') === 'full';
  const quality = reqUrl.searchParams.get('q') === 'balanced' ? 'veryfast' : 'ultrafast';
  let vcopy = false, acopy = false, codecs = null;
  if (!full) {
    const info = leafProbe(await probe(target, { ua: reqUrl.searchParams.get('ua') || '', ref }));
    codecs = info?.codecs || null;
    if (codecs) {
      vcopy = codecs.video.length > 0 && codecs.video.every((c) => c === 'H.264');
      acopy = codecs.audio.length > 0 && codecs.audio.every((c) => c === 'AAC');
    }
  }
  const args = ['-hide_banner', '-loglevel', 'error', '-nostdin', '-user_agent', ua];
  if (ref) args.push('-headers', `Referer: ${ref}\r\n`);
  if (/^https?:/i.test(target)) args.push('-reconnect', '1', '-reconnect_streamed', '1', '-reconnect_delay_max', '5');
  args.push('-fflags', '+genpts+discardcorrupt', '-i', target, '-map', '0:v:0?', '-map', '0:a:0?', '-sn', '-dn');
  args.push(...(vcopy ? ['-c:v', 'copy'] : [
    '-c:v', 'libx264', '-preset', quality, '-tune', 'zerolatency', '-crf', '23', '-g', '50', '-pix_fmt', 'yuv420p',
    '-vf', 'yadif=mode=0:deint=1,scale=-2:min(720\\,ih)',          // desentrelaza (TV de cable) y limita a 720p
  ]));
  args.push(...(acopy ? ['-c:a', 'copy'] : ['-c:a', 'aac', '-b:a', '160k', '-ac', '2']));
  const what = codecs ? `${[...codecs.video, ...codecs.audio].join('+')} → ${vcopy ? 'video copiado' : 'H.264'} / ${acopy ? 'audio copiado' : 'AAC'}` : 'conversión completa';
  return { args, what, vcopy };
}

async function handleTranscode(req, res, reqUrl) {
  if (!FFMPEG) {
    res.writeHead(501, { ...CORS, 'Content-Type': 'text/plain; charset=utf-8', 'X-Proxy-Error': encodeURIComponent('ffmpeg no está instalado') });
    return res.end('ffmpeg no está instalado');
  }
  const target = reqUrl.searchParams.get('url');
  if (!target) { res.writeHead(400, CORS); return res.end('Falta ?url='); }
  if (currentFfmpeg) { try { currentFfmpeg.kill('SIGKILL'); } catch {} currentFfmpeg = null; }
  stopHls();
  const { args, what } = await ffmpegArgs(target, reqUrl);
  args.push('-f', 'mpegts', '-muxdelay', '0', 'pipe:1');

  let host = ''; try { host = new URL(target).host; } catch {}
  console.log(`[ffmpeg] ${host}: ${what}`);
  const ff = spawn(FFMPEG, args, { stdio: ['ignore', 'pipe', 'pipe'] });
  currentFfmpeg = ff;
  let errText = '';
  ff.stderr.on('data', (d) => { errText = (errText + d).slice(-600); });
  res.writeHead(200, { ...CORS, 'Content-Type': 'video/mp2t', 'Cache-Control': 'no-store', 'X-Transcode': encodeURIComponent(what) });
  ff.stdout.pipe(res);
  const stop = () => { try { ff.kill('SIGKILL'); } catch {} if (currentFfmpeg === ff) currentFfmpeg = null; };
  req.on('close', stop);
  ff.on('close', (code) => {
    if (code && errText.trim()) console.warn(`[ffmpeg] ${host}: ${errText.trim().split('\n').pop()}`);
    if (currentFfmpeg === ff) currentFfmpeg = null;
    res.end();
  });
}

// ---- Conversión a HLS para iPhone y iPad ----
// Safari en iOS no reproduce MPEG-TS directo (no tiene MediaSource en iPhone): ffmpeg lo pasa a HLS en
// trozos de 2 s, que Safari reproduce de forma nativa. Una sesión a la vez; se apaga sola si nadie la pide.
let hlsSession = null;   // { id, key, dir, ff, last, exited }
const HLS_ROOT = path.join(os.tmpdir(), 'vibe-hls');
function stopHls() {
  if (!hlsSession) return;
  const s = hlsSession; hlsSession = null;
  try { s.ff.kill('SIGKILL'); } catch {}
  fs.rm(s.dir, { recursive: true, force: true }, () => {});
}
setInterval(() => { if (hlsSession && Date.now() - hlsSession.last > 45000) stopHls(); }, 10000).unref();

/** Lista HLS con las rutas completas de los trozos: así se puede servir en la misma dirección de inicio. */
function sessionPlaylist(s) {
  try {
    const text = fs.readFileSync(path.join(s.dir, 'index.m3u8'), 'utf8');
    return text.replace(/^(s\d{5}\.ts)\s*$/gm, `/proxy/hls/${s.id}/$1`);
  } catch { return ''; }
}

function sendPlaylist(res, s, what) {
  res.writeHead(200, { ...CORS, 'Content-Type': 'application/vnd.apple.mpegurl', 'Cache-Control': 'no-store', ...(what ? { 'X-Transcode': encodeURIComponent(what) } : {}) });
  res.end(sessionPlaylist(s));
}

function countSegments(file) {
  try { return (fs.readFileSync(file, 'utf8').match(/\.ts\s*$/gm) || []).length; } catch { return 0; }
}

async function handleHlsStart(req, res, reqUrl) {
  if (!FFMPEG) {
    res.writeHead(501, { ...CORS, 'Content-Type': 'text/plain; charset=utf-8', 'X-Proxy-Error': encodeURIComponent('ffmpeg no está instalado') });
    return res.end('ffmpeg no está instalado');
  }
  const target = reqUrl.searchParams.get('url');
  if (!target) { res.writeHead(400, CORS); return res.end('Falta ?url='); }
  const live = reqUrl.searchParams.get('live') !== '0';
  // Safari vuelve a pedir esta misma dirección cada pocos segundos para actualizar la lista en vivo:
  // si ya hay una conversión de este canal, se entrega su lista (antes se reiniciaba ffmpeg cada vez
  // y el video se quedaba cargando para siempre)
  const key = ['url', 'live', 'ua', 'ref', 'q', 'mode'].map((k) => reqUrl.searchParams.get(k) || '').join('|');
  if (hlsSession && hlsSession.key === key && !hlsSession.exited) {
    hlsSession.last = Date.now();
    const s = hlsSession, t0 = Date.now();
    while (!countSegments(path.join(s.dir, 'index.m3u8')) && hlsSession === s && Date.now() - t0 < 25000) await new Promise((r) => setTimeout(r, 250));
    if (hlsSession === s && countSegments(path.join(s.dir, 'index.m3u8'))) return sendPlaylist(res, s);
  }
  stopHls();
  if (currentFfmpeg) { try { currentFfmpeg.kill('SIGKILL'); } catch {} currentFfmpeg = null; }
  const { args, what, vcopy } = await ffmpegArgs(target, reqUrl);
  const id = crypto.randomBytes(8).toString('hex');
  const dir = path.join(HLS_ROOT, id);
  fs.mkdirSync(dir, { recursive: true });
  const index = path.join(dir, 'index.m3u8');
  // Video copiado: los trozos se cortan en los cuadros clave del canal (cada 2-5 s); con trozos de 2 s
  // la duración máxima cambiaba a mitad de la lista y Safari se quedaba cargando. Convertido: cuadro clave cada 2 s.
  if (!vcopy) args.push('-force_key_frames', 'expr:gte(t,n_forced*2)');
  args.push('-f', 'hls', '-hls_time', vcopy ? '4' : '2', '-hls_segment_filename', path.join(dir, 's%05d.ts'));
  // En vivo: la lista guarda unos 30 s y los trozos viejos se borran con margen (Safari a veces los pide tarde)
  if (live) args.push('-hls_list_size', vcopy ? '8' : '15', '-hls_delete_threshold', '6', '-hls_flags', 'delete_segments+independent_segments+omit_endlist');
  else args.push('-hls_list_size', '0', '-hls_playlist_type', 'event', '-hls_flags', 'independent_segments');
  args.push(index);
  let host = ''; try { host = new URL(target).host; } catch {}
  console.log(`[ffmpeg → HLS] ${host}: ${what}`);
  const ff = spawn(FFMPEG, args, { stdio: ['ignore', 'ignore', 'pipe'] });
  let errText = '', exited = false;
  ff.stderr.on('data', (d) => { errText = (errText + d).slice(-600); });
  hlsSession = { id, key, dir, ff, last: Date.now(), exited: false };
  const session = hlsSession;
  ff.on('close', () => { exited = true; session.exited = !live ? false : true; });
  // Esperar a que haya al menos 2 trozos listos (o el primero, si ffmpeg ya terminó: archivo corto)
  const t0 = Date.now();
  while (Date.now() - t0 < 25000) {
    const n = countSegments(index);
    if (n >= 2 || (exited && n >= 1)) break;
    if (exited || !hlsSession || hlsSession.id !== id) break;
    await new Promise((r) => setTimeout(r, 250));
  }
  if (!countSegments(index)) {
    if (hlsSession && hlsSession.id === id) stopHls();
    const why = errText.trim().split('\n').pop() || 'ffmpeg no entregó video';
    res.writeHead(502, { ...CORS, 'Content-Type': 'text/plain; charset=utf-8', 'X-Proxy-Error': encodeURIComponent(why) });
    return res.end(why);
  }
  sendPlaylist(res, session, what);
}

function handleHlsFile(req, res, reqUrl) {
  const m = /^\/proxy\/hls\/([a-f0-9]{16})\/(index\.m3u8|s\d{5}\.ts)$/.exec(reqUrl.pathname);
  if (!m || !hlsSession || hlsSession.id !== m[1]) { res.writeHead(404, CORS); return res.end(); }
  hlsSession.last = Date.now();
  const file = path.join(hlsSession.dir, m[2]);
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404, CORS); return res.end(); }
    const playlist = m[2].endsWith('.m3u8');
    res.writeHead(200, { ...CORS, 'Content-Type': playlist ? 'application/vnd.apple.mpegurl' : 'video/mp2t', 'Cache-Control': playlist ? 'no-store' : 'max-age=60' });
    res.end(data);
  });
}

// Guías .xml.gz para navegadores que no saben descomprimir (Safari antes de iOS 16.4)
async function handleGunzip(req, res, reqUrl) {
  const target = reqUrl.searchParams.get('url');
  if (!target) { res.writeHead(400, CORS); return res.end('Falta ?url='); }
  let up;
  try { up = await upstream(target, req, { ua: reqUrl.searchParams.get('ua') || '', ref: '' }); }
  catch (e) { res.writeHead(502, { ...CORS, 'X-Proxy-Error': encodeURIComponent(explain(e)) }); return res.end(); }
  if (up.statusCode >= 400) { res.writeHead(up.statusCode, CORS); up.resume(); return res.end(); }
  res.writeHead(200, { ...CORS, 'Content-Type': 'application/xml; charset=utf-8', 'Cache-Control': 'no-store' });
  const gz = zlib.createGunzip();
  gz.on('error', () => res.end());
  up.pipe(gz).pipe(res);
}

async function handleProxy(req, res, reqUrl) {
  if (reqUrl.pathname === '/proxy/caps') {
    res.writeHead(200, { ...CORS, 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ ffmpeg: !!FFMPEG, hls: !!FFMPEG, ocr: OCR_LOCAL }));
  }
  if (reqUrl.pathname === '/proxy/transcode') return handleTranscode(req, res, reqUrl);
  if (reqUrl.pathname === '/proxy/hls/start') return handleHlsStart(req, res, reqUrl);
  if (reqUrl.pathname === '/proxy/gunzip') return handleGunzip(req, res, reqUrl);
  if (reqUrl.pathname.startsWith('/proxy/hls/')) return handleHlsFile(req, res, reqUrl);
  if (reqUrl.pathname === '/proxy/subs/subdl-account') {
    try {
      const msg = await subtitles.subdlAccount(reqUrl.searchParams.get('key') || '');
      res.writeHead(200, { ...CORS, 'Content-Type': 'text/plain; charset=utf-8' }); return res.end(msg);
    } catch (e) { res.writeHead(200, { ...CORS, 'Content-Type': 'text/plain; charset=utf-8' }); return res.end(e.message); }
  }
  if (reqUrl.pathname === '/proxy/subs/search' || reqUrl.pathname === '/proxy/subs/get') {
    const q = Object.fromEntries(reqUrl.searchParams);
    try {
      if (reqUrl.pathname.endsWith('/search')) {
        const out = await subtitles.search({
          title: q.title || '', type: q.type || 'movie', season: q.season || '', episode: q.episode || '', year: q.year || '', imdb: q.imdb || '',
          langs: (q.langs || 'es,en').toLowerCase().split(/[\s,]+/).filter(Boolean), osKey: q.osKey || '', subdlKey: q.subdlKey || '',
        });
        console.log(`[subtítulos] "${q.title}": ${out.results.length} resultados${Object.keys(out.errors).length ? ' · ' + Object.entries(out.errors).map(([k, v]) => `${k}: ${v}`).join(' · ') : ''}`);
        res.writeHead(200, { ...CORS, 'Content-Type': 'application/json; charset=utf-8' });
        return res.end(JSON.stringify(out));
      }
      const vtt = await subtitles.download(q.provider, q.id, { osKey: q.osKey, subdlKey: q.subdlKey });
      res.writeHead(200, { ...CORS, 'Content-Type': 'text/vtt; charset=utf-8' });
      return res.end(vtt);
    } catch (e) {
      res.writeHead(502, { ...CORS, 'Content-Type': 'text/plain; charset=utf-8', 'X-Proxy-Error': encodeURIComponent(e.message) });
      return res.end(e.message);
    }
  }
  if (reqUrl.pathname === '/proxy/probe') {
    const target = reqUrl.searchParams.get('url');
    const extra = { ua: reqUrl.searchParams.get('ua') || '', ref: reqUrl.searchParams.get('ref') || '' };
    const result = target ? await probe(target, extra) : { error: 'Falta ?url=' };
    console.log(`[diagnóstico] ${JSON.stringify(result)}`);
    res.writeHead(200, { ...CORS, 'Content-Type': 'application/json; charset=utf-8' });
    return res.end(JSON.stringify(result));
  }
  if (reqUrl.pathname === '/proxy/health') { res.writeHead(200, { ...CORS, 'Content-Type': 'text/plain' }); return res.end('ok'); }
  const target = reqUrl.searchParams.get('url');
  if (!target) { res.writeHead(400, CORS); return res.end('Falta ?url='); }
  const extra = { ua: reqUrl.searchParams.get('ua') || '', ref: reqUrl.searchParams.get('ref') || '' };
  let up;
  try { up = await upstream(target, req, extra, await readBody(req)); }
  catch (e) {
    const msg = explain(e);
    let host = '';
    try { host = new URL(target).host; } catch {}
    console.error(`[proxy] ${host}: ${msg} (${e.code || e.message})`);
    res.writeHead(502, { ...CORS, 'Content-Type': 'text/plain; charset=utf-8', 'X-Proxy-Error': encodeURIComponent(msg) });
    return res.end(msg);
  }

  const ctype = up.headers['content-type'] || '';
  if (up.statusCode >= 400) console.warn(`[proxy] ${up.finalUrl.host}: el servidor respondió ${up.statusCode}`);
  if (up.statusCode < 400 && isPlaylist(up.finalUrl, ctype) && req.method === 'GET') {
    const chunks = [];
    up.on('data', (c) => chunks.push(c));
    up.on('end', () => {
      const text = Buffer.concat(chunks).toString('utf8');
      // Algunas "m3u8" son en realidad listas M3U de canales: se reescriben igual, sin problema
      const out = text.trimStart().startsWith('#EXTM3U') && /#EXT-X-/.test(text) ? rewriteM3u8(text, up.finalUrl.href, req, extra) : text;
      res.writeHead(up.statusCode, { ...CORS, 'Content-Type': 'application/vnd.apple.mpegurl', 'Cache-Control': 'no-cache' });
      res.end(out);
    });
    up.on('error', () => res.destroy());
    return;
  }
  const headers = { ...CORS };
  for (const h of ['content-type', 'content-length', 'content-range', 'accept-ranges', 'cache-control', 'last-modified', 'etag']) {
    if (up.headers[h]) headers[h] = up.headers[h];
  }
  res.writeHead(up.statusCode, headers);
  up.pipe(res);
  req.on('close', () => up.destroy());
}

// ---- Lector de texto para "Desde una captura" (opcional: npm install lo deja disponible sin Internet) ----
function ocrFile(p) {
  const nm = path.join(ROOT, 'node_modules');
  const name = path.basename(p);
  if (p === '/ocr/tesseract.min.js' || p === '/ocr/worker.min.js') return path.join(nm, 'tesseract.js', 'dist', name);
  if (p.startsWith('/ocr/core/') && /^tesseract-core[\w.-]*\.(?:js|wasm)$/.test(name)) return path.join(nm, 'tesseract.js-core', name);
  if (p === '/ocr/lang/eng.traineddata.gz') return path.join(nm, '@tesseract.js-data', 'eng', '4.0.0_best_int', name);
  return null;
}
const OCR_LOCAL = !!ocrFile('/ocr/tesseract.min.js') && fs.existsSync(ocrFile('/ocr/tesseract.min.js'))
  && fs.existsSync(path.join(ROOT, 'node_modules', 'tesseract.js-core')) && fs.existsSync(ocrFile('/ocr/lang/eng.traineddata.gz'));

function serveStatic(req, res, reqUrl) {
  if (reqUrl.pathname.startsWith('/ocr/')) {
    const f = ocrFile(reqUrl.pathname);
    if (!f) { res.writeHead(404); return res.end(); }
    return fs.stat(f, (err, st) => {
      if (err || !st.isFile()) { res.writeHead(404); return res.end(); }
      res.writeHead(200, { 'Content-Type': MIME[path.extname(f)] || 'application/octet-stream', 'Content-Length': st.size, 'Cache-Control': 'max-age=604800' });
      fs.createReadStream(f).pipe(res);
    });
  }
  let p = decodeURIComponent(reqUrl.pathname);
  if (p.endsWith('/')) p += 'index.html';
  const file = path.normalize(path.join(ROOT, p));
  if (!file.startsWith(ROOT) || file.includes(`${path.sep}server${path.sep}`)) { res.writeHead(403); return res.end(); }
  fs.stat(file, (err, st) => {
    if (err || !st.isFile()) { res.writeHead(404); return res.end('No encontrado'); }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream', 'Content-Length': st.size, 'Cache-Control': 'no-cache' });
    fs.createReadStream(file).pipe(res);
  });
}

// Contraseña opcional (VIBE_PASSWORD) para servidores propios accesibles fuera de casa.
// El navegador la pide una vez y la reenvía sola en cada petición (también en el video).
const PASSWORD = process.env.VIBE_PASSWORD || '';
function authorized(req) {
  if (!PASSWORD) return true;
  const m = /^Basic\s+(.+)$/i.exec(req.headers.authorization || '');
  if (!m) return false;
  const pass = Buffer.from(m[1], 'base64').toString('utf8').split(':').slice(1).join(':');
  const a = Buffer.from(pass), b = Buffer.from(PASSWORD);
  return a.length === b.length && require('crypto').timingSafeEqual(a, b);
}

const server = http.createServer((req, res) => {
  const reqUrl = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  if (req.method === 'OPTIONS') { res.writeHead(204, CORS); return res.end(); }
  if (!authorized(req) && reqUrl.pathname !== '/proxy/health') {
    res.writeHead(401, { 'WWW-Authenticate': 'Basic realm="Vibe IPTV", charset="UTF-8"', 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end('Vibe IPTV: se necesita la contraseña de acceso / access password required');
  }
  if (reqUrl.pathname === '/proxy' || reqUrl.pathname.startsWith('/proxy/')) return handleProxy(req, res, reqUrl);
  if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405); return res.end(); }
  serveStatic(req, res, reqUrl);
});

// Idioma de los mensajes de la consola: VIBE_LANG=es|en, o el del sistema.
const SYS_LANG = (process.env.VIBE_LANG || process.env.LANG || Intl.DateTimeFormat().resolvedOptions().locale || 'es').toLowerCase();
const L = (es, en) => (SYS_LANG.startsWith('es') ? es : en);

if (require.main === module) {
  server.on('error', (e) => {
    console.error(e.code === 'EADDRINUSE' ? L(`El puerto ${PORT} ya está en uso: ¿la app ya está abierta en otra ventana?`, `Port ${PORT} is already in use: is the app already open in another window?`) : e.message);
  });
  server.listen(PORT, HOST, () => {
    console.log(`Vibe IPTV ${L('en', 'at')} http://${HOST === '0.0.0.0' ? 'localhost' : HOST}:${PORT}`);
    if (HOST === '0.0.0.0') {
      const ips = Object.values(require('os').networkInterfaces()).flat().filter((n) => n && n.family === 'IPv4' && !n.internal).map((n) => n.address);
      console.log(L('Desde el TV o el celular (misma red WiFi):', 'From the TV or phone (same WiFi network):'));
      ips.forEach((ip) => console.log(`  http://${ip}:${PORT}`));
    }
    console.log(FFMPEG ? L('Conversión de formatos (ffmpeg): activa.', 'Format conversion (ffmpeg): active.')
      : L('Conversión de formatos (ffmpeg): no instalada. Los canales con audio AC-3 o video MPEG-2 no se verán en el navegador.',
        'Format conversion (ffmpeg): not installed. Channels with AC-3 audio or MPEG-2 video won\'t play in the browser.'));
    if (PASSWORD) console.log(L('Acceso protegido con contraseña (VIBE_PASSWORD).', 'Access protected with a password (VIBE_PASSWORD).'));
    else if (HOST !== '127.0.0.1' && HOST !== 'localhost') console.log(L('Aviso: sin contraseña. Úsalo sólo en tu red de casa o define VIBE_PASSWORD.', 'Warning: no password. Use it only on your home network or set VIBE_PASSWORD.'));
    console.log(L('Deja esta ventana abierta mientras usas la app.', 'Keep this window open while you use the app.'));
    if (process.argv.includes('--open')) {   // abre el navegador (usado por iniciar.bat)
      const url = `http://localhost:${PORT}`;
      const cmd = process.platform === 'win32' ? `start "" ${url}` : process.platform === 'darwin' ? `open ${url}` : `xdg-open ${url}`;
      require('child_process').exec(cmd, () => {});
    }
  });
}

module.exports = { server, rewriteM3u8, tsCodecs, authorized };
