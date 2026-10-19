// Reconoce los datos de una lista en el texto leído de una captura de pantalla
// (mensaje del proveedor, correo, panel, etc.). Recognizes playlist details in the text read
// from a screenshot. La misma lógica está en Android: data/ScreenshotParser.kt
//
// Devuelve / returns:
//   { type: 'xtream' | 'm3u' | null, server, username, password, url, epgUrl, name, found: [...] }

const LABELS = {
  server: /^(?:url\s+(?:del\s+)?servidor|server\s*url|xtream\s*(?:url|server)|portal(?:\s*url)?|servidor|server|host(?:name)?|dns|direcci[oó0]n|address|dominio|domain)\b/i,
  username: /^(?:nombre\s+de\s+usuario|user\s*name|username|usuario|user|login|usr|id\s+de\s+usuario)\b/i,
  password: /^(?:contrase\S{0,3}a|password|passw(?:or)?d|pass|clave|pwd|senha|pin)\b/i,
  port: /^(?:puerto|port|porta)\b/i,
  m3u: /^(?:m3u(?:8)?(?:\s*(?:url|link|enlace))?|(?:url|link|enlace)\s*(?:de\s+la\s+)?(?:m3u8?|lista|playlist)|lista(?:\s+m3u8?)?|playlist(?:\s*url)?)\b/i,
  epg: /^(?:epg(?:\s*url)?|xmltv(?:\s*url)?|gu[ií]a(?:\s+epg)?|tv\s*guide|guide)\b/i,
  name: /^(?:proveedor|provider|servicio|service|nombre\s+de\s+la\s+lista|playlist\s+name)\b/i,
};
// Viñetas, emojis y numeración al inicio de la línea ("• Usuario:", "» User", "1) Pass", "✅ URL")
const BULLETS = /^(?:[\s"'•·*\-–—>»›◦‣⁃●○■□▪▫►▶→⇒✓✔☑✅❇✳⭐🔹🔸🔷🔶📺📡🔑🔐👤🌐\u{1F300}-\u{1FAFF}\uFE0F]|\d{1,2}[.)]\s)+/u;
const SEP = /^\s*(?:[:：=]|-{1,2}>?|–|—|→|»)?\s*/;
const HOST = /^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}$|^\d{1,3}(?:\.\d{1,3}){3}$|^localhost$/i;

/** Arregla errores típicos de la lectura (espacios dentro de http://, ";" en vez de ":"). */
export function cleanOcrText(text) {
  return String(text || '')
    .replace(/[   ]/g, ' ')
    .replace(/[“”«»]/g, '"').replace(/[‘’]/g, "'")
    .replace(/\bh\s*t\s*t\s*p\s*(s?)\s*[:;.,]?\s*[\/\\|l]\s*[\/\\|l]\s*/gi, (_m, s) => `http${s.toLowerCase()}://`)
    .replace(/(https?:\/\/[^\s]*?)\s+([.:\/])\s*(?=[\w])/gi, '$1$2')     // "dominio .com" → "dominio.com"
    .replace(/\r/g, '');
}

function stripValue(v) {
  return v.replace(/^[\s"'`([{<•·*]+/, '').replace(/[\s"'`)\]}>.,;•·*]+$/, '');
}

function asServer(v, port) {
  let s = stripValue(v).split(/\s+/)[0] || '';
  if (!s) return '';
  if (!/^https?:\/\//i.test(s)) s = 'http://' + s;
  let u;
  try { u = new URL(s); } catch { return ''; }
  if (!HOST.test(u.hostname)) return '';
  if (port && !u.port && /^\d{2,5}$/.test(port)) u.port = port;
  const path = u.pathname.replace(/\/(?:player_api|get|panel_api|xmltv)\.php$/i, '').replace(/\/c\/?$/i, '').replace(/\/+$/, '');
  return `${u.protocol}//${u.host}${path}`;
}

const firstToken = (v) => stripValue(v).split(/\s+/)[0] || '';

/** Busca los datos en el texto. */
export function parseListDetails(rawText) {
  const text = cleanOcrText(rawText);
  const lines = text.split('\n').map((l) => l.trim()).filter(Boolean);
  const out = { type: null, server: '', username: '', password: '', url: '', epgUrl: '', name: '', found: [] };
  let port = '';

  // 1. Enlaces completos: get.php?username=..&password=.. trae todo
  const urls = [...text.matchAll(/https?:\/\/[^\s"'<>]+/gi)].map((m) => m[0].replace(/[),.;]+$/, ''));
  for (const raw of urls) {
    let u; try { u = new URL(raw); } catch { continue; }
    if (!HOST.test(u.hostname)) continue;
    const q = u.searchParams;
    const user = q.get('username') || q.get('user') || '';
    const pass = q.get('password') || q.get('pass') || '';
    const isEpg = /xmltv\.php|\.xml(?:\.gz)?$|epg/i.test(u.pathname);
    if (isEpg) { out.epgUrl ||= raw; continue; }
    if (user && pass && !out.username) { out.username = user; out.password = pass; out.server = asServer(raw); }
    if (/get\.php|\.m3u8?$|type=m3u|output=(?:ts|m3u8|hls)/i.test(u.pathname + u.search)) out.url ||= raw;
    else if (!out.server) out.server = asServer(raw);
  }

  // 2. Campos con etiqueta: "Usuario: juan" o la etiqueta en una línea y el valor en la siguiente
  const isLabelOnly = (l) => Object.values(LABELS).some((re) => { const m = re.exec(l); return m && !stripValue(l.slice(m[0].length).replace(SEP, '')); });
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].replace(BULLETS, '');
    for (const [key, re] of Object.entries(LABELS)) {
      const m = re.exec(line);
      if (!m) continue;
      let value = line.slice(m[0].length).replace(SEP, '').trim();
      if (!stripValue(value) && lines[i + 1] && !isLabelOnly(lines[i + 1])) value = lines[i + 1];
      if (!stripValue(value)) continue;
      if (key === 'server' && !out.server) out.server = asServer(value);
      else if (key === 'username' && !out.username) out.username = firstToken(value);
      else if (key === 'password' && !out.password) out.password = firstToken(value);
      else if (key === 'port') port = (value.match(/\d{2,5}/) || [''])[0];
      else if (key === 'm3u' && !out.url && /^https?:\/\//i.test(stripValue(value))) out.url = firstToken(value);
      else if (key === 'epg' && !out.epgUrl && /^https?:\/\//i.test(stripValue(value))) out.epgUrl = firstToken(value);
      else if (key === 'name' && !out.name) out.name = stripValue(value).slice(0, 40);
      break;
    }
  }
  if (port && out.server) out.server = asServer(out.server, port);

  // 3. Un dominio suelto (sin etiqueta ni http) cuando ya hay usuario y contraseña
  if (!out.server && out.username && out.password) {
    const bare = text.match(/\b(?:[a-z0-9-]+\.)+[a-z]{2,}(?::\d{2,5})?\b/i);
    if (bare) out.server = asServer(bare[0], port);
  }

  if (out.server && out.username && out.password) out.type = 'xtream';
  else if (out.url) out.type = 'm3u';
  for (const k of ['server', 'username', 'password', 'url', 'epgUrl', 'name']) if (out[k]) out.found.push(k);
  return out;
}
