// Lee el texto de una captura de pantalla con Tesseract.js (se ejecuta en el propio navegador:
// la imagen no se envía a ningún servidor; sólo se descarga el lector la primera vez).
// Reads the text of a screenshot with Tesseract.js, locally in the browser.
import { parseListDetails } from './ocr-parse.js';

// Copia local (servidor de Vibe IPTV con npm install) o, si no está, jsDelivr.
const CDN = {
  script: 'https://cdn.jsdelivr.net/npm/tesseract.js@5.1.1/dist/tesseract.min.js',
  workerPath: 'https://cdn.jsdelivr.net/npm/tesseract.js@5.1.1/dist/worker.min.js',
  corePath: 'https://cdn.jsdelivr.net/npm/tesseract.js-core@5.1.1',
  langPath: 'https://cdn.jsdelivr.net/npm/@tesseract.js-data/eng@1.0.0/4.0.0_best_int',
};
const LOCAL = { script: '/ocr/tesseract.min.js', workerPath: '/ocr/worker.min.js', corePath: '/ocr/core', langPath: '/ocr/lang' };
let loading = null;
let paths = null;

async function pickPaths() {
  if (paths) return paths;
  try {
    const r = await fetch('/proxy/caps', { cache: 'no-store' });
    if (r.ok && (await r.json()).ocr) return (paths = LOCAL);
  } catch { /* sin servidor local */ }
  return (paths = CDN);
}

async function loadTesseract() {
  if (window.Tesseract) return window.Tesseract;
  const p = await pickPaths();
  loading ||= new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = p.script; s.async = true;
    s.onload = () => (window.Tesseract ? resolve(window.Tesseract) : reject(new Error('No se pudo cargar el lector de texto')));
    s.onerror = () => { loading = null; reject(new Error('No se pudo cargar el lector de texto (¿sin conexión a Internet?)')); };
    document.head.appendChild(s);
  });
  return loading;
}

/** Agranda capturas pequeñas, pasa a gris y, si el fondo es oscuro (modo noche), invierte los colores. */
async function prepare(file) {
  const bmp = await createImageBitmap(file);
  const scale = Math.min(3, Math.max(1, 1600 / Math.max(bmp.width, 1)));
  const w = Math.round(bmp.width * scale), h = Math.round(bmp.height * scale);
  const probe = document.createElement('canvas'); probe.width = 32; probe.height = 32;
  const pc = probe.getContext('2d', { willReadFrequently: true });
  pc.drawImage(bmp, 0, 0, 32, 32);
  const px = pc.getImageData(0, 0, 32, 32).data;
  let lum = 0; for (let i = 0; i < px.length; i += 4) lum += 0.299 * px[i] + 0.587 * px[i + 1] + 0.114 * px[i + 2];
  const dark = lum / (px.length / 4) < 110;
  const c = document.createElement('canvas'); c.width = w; c.height = h;
  const ctx = c.getContext('2d');
  ctx.filter = `${dark ? 'invert(1) ' : ''}grayscale(1) contrast(1.35)`;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(bmp, 0, 0, w, h);
  bmp.close?.();
  return c;
}

/**
 * Lee la imagen y devuelve los datos encontrados ({ type, server, username, password, url, epgUrl, name, found, text }).
 * onProgress recibe un número de 0 a 1.
 */
export async function readScreenshot(file, onProgress) {
  if (!file || !/^image\//.test(file.type || 'image/')) throw new Error('El archivo no es una imagen');
  const [T, canvas] = await Promise.all([loadTesseract(), prepare(file)]);
  const p = await pickPaths();
  const worker = await T.createWorker('eng', 1, {
    workerPath: p.workerPath, corePath: p.corePath, langPath: p.langPath,
    logger: (m) => { if (m.status === 'recognizing text' && onProgress) onProgress(m.progress || 0); },
  });
  try {
    const { data } = await worker.recognize(canvas);
    return { ...parseListDetails(data.text || ''), text: data.text || '' };
  } finally {
    worker.terminate();
  }
}
