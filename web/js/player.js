// Reproductor: HLS (hls.js), MPEG-TS en vivo (mpegts.js), archivos (nativo) y,
// si el formato no es compatible con el navegador, conversión al vuelo con ffmpeg
// en el servidor local (MPEG-2 → H.264, AC-3/MP2 → AAC), habitual en canales de cable.
import { candidates, markNeedsProxy, proxyBase, IOS } from './net.js';
import { getSettings } from './storage.js';

export { IOS };

function streamType(url) {
  const path = url.split('?')[0].toLowerCase();
  if (path.includes('.m3u8') || path.endsWith('.hls.txt') || /[?&](type|output)=(m3u8|hls)/i.test(url)) return 'hls';
  if (/\.(mp4|mkv|webm|mov|avi|m4v|mp3|aac)$/.test(path)) return 'file';
  // .ts o URL sin extensión (típico de canales IPTV) -> MPEG-TS
  return 'ts';
}

// En Xtream el mismo canal existe como .m3u8 y como .ts
export function altFormat(url) {
  const m = /^(.*\/live\/[^/]+\/[^/]+\/\d+)\.(m3u8|ts)(\?.*)?$/i.exec(url);
  return m ? `${m[1]}.${m[2].toLowerCase() === 'm3u8' ? 'ts' : 'm3u8'}${m[3] || ''}` : null;
}

function hlsError(d) {
  const code = d.response?.code;
  if (d.details === 'manifestLoadError' || d.details === 'manifestLoadTimeOut') {
    if (code === 401 || code === 403) return 'El servidor rechazó el canal (¿cuenta sin acceso o conexiones al máximo?)';
    if (code === 404) return 'El canal no existe en el servidor';
    if (code === 502) return 'El servidor del canal no responde';
    return `No se pudo abrir el canal${code ? ` (error ${code})` : ''}`;
  }
  if (d.details === 'manifestParsingError') return 'El servidor no entregó un canal válido';
  if (/bufferAddCodecError|bufferIncompatibleCodecsError|manifestIncompatibleCodecsError/.test(d.details)) return 'Este navegador no soporta el formato de video del canal';
  return `HLS: ${d.details}`;
}

// ¿Tiene el servidor local ffmpeg para convertir? (se pregunta una vez)
let capsPromise = null;
export function getCaps() {
  if (!proxyBase()) return Promise.resolve({ ffmpeg: false });
  capsPromise ||= fetch(`${proxyBase()}/proxy/caps`).then((r) => r.json()).catch(() => ({ ffmpeg: false }));
  return capsPromise;
}

/** iPhone/iPad: el servidor convierte a HLS (trozos de 2 s) y Safari lo reproduce de forma nativa. */
function hlsConvertUrl(url, item, live) {
  const p = new URLSearchParams({ url, live: live ? '1' : '0' });
  if (item.userAgent) p.set('ua', item.userAgent);
  if (item.referrer) p.set('ref', item.referrer);
  if (getSettings().convertQuality === 'balanced') p.set('q', 'balanced');
  return `${proxyBase()}/proxy/hls/start?${p}`;
}

function transcodeUrl(url, item, full = false) {
  const p = new URLSearchParams({ url });
  if (item.userAgent) p.set('ua', item.userAgent);
  if (item.referrer) p.set('ref', item.referrer);
  if (full) p.set('mode', 'full');
  if (getSettings().convertQuality === 'balanced') p.set('q', 'balanced');
  return `${proxyBase()}/proxy/transcode?${p}`;
}

// Búfer (Ajustes → Reproducción): segundos por adelantado (HLS), megas máximos y colchón inicial MPEG-TS (KB)
import { cushionSpeed, cushionTarget, SLOW, SLOWER } from './cushion.js';

const BUFFER = { fast: { hls: 12, mb: 40, stash: 192 }, balanced: { hls: 30, mb: 60, stash: 384 }, stable: { hls: 60, mb: 120, stash: 1024 } };

export class Player {
  constructor(video, { onError, onStatus, onInfo } = {}) {
    this.video = video;
    this.onError = onError || (() => {});
    this.onStatus = onStatus || (() => {});
    this.onInfo = onInfo || (() => {});   // cambia el motor, las pistas o la calidad
    this.engine = null;
    this.kind = '';                         // 'HLS' | 'MPEG-TS' | 'Archivo' | 'Convertido (ffmpeg)'
    this.token = 0;
    this.item = null;
    this.silentTimer = null;
  }

  destroy() {
    clearTimeout(this.silentTimer);
    clearInterval(this.cushionTimer);
    try { this.engine?.destroy(); } catch {}
    this.engine = null;
    this.kind = '';
    this.video.querySelectorAll('track[data-ext]').forEach((t) => t.remove());
    this.video.removeAttribute('src');
    this.video.load();
  }

  /** Reproduce un elemento. `opts.convert` fuerza la conversión con ffmpeg. */
  async play(item, opts = {}) {
    const token = ++this.token;
    this.item = item;
    const s = getSettings();
    const live = item.kind === 'live';
    const caps = await getCaps();
    const canConvert = caps.ffmpeg && s.convert !== 'never';

    if (canConvert && (opts.convert || (s.convert === 'always' && live))) {
      if (await this.tryConvert(item, token, live)) return;
    }

    // Intentos: el enlace tal cual y, en canales Xtream, el mismo canal en el otro formato (.m3u8 ↔ .ts)
    const variants = [item.url];
    const alt = altFormat(item.url);
    if (alt) variants.push(alt);
    // iPhone/iPad: Safari no reproduce MPEG-TS directo; primero el .m3u8 del canal y, si no hay, la conversión a HLS
    if (IOS) {
      if (alt && streamType(item.url) === 'ts') variants.reverse();
      if (!alt && streamType(item.url) === 'ts' && canConvert) { if (await this.tryConvert(item, token, live)) return; }
    }
    let lastErr = null;
    for (const src of variants) {
      const urls = candidates(src, item);
      const type = streamType(src);
      for (const url of urls) {
        if (token !== this.token) return;
        this.onStatus(src !== item.url ? 'Probando el otro formato del canal…' : urls.length > 1 && url !== urls[0] ? 'Reintentando vía proxy…' : 'Cargando…');
        try {
          await this.tryUrl(url, type, live);
          if (url !== urls[0]) markNeedsProxy(src);
          if (src !== item.url) item.url = src;   // recordar el formato que funcionó
          if (token !== this.token) return;
          this.onStatus('');
          if (canConvert && s.convert === 'auto') this.watchSilentAudio(item, token, live);
          return;
        } catch (e) { lastErr = e; }
      }
    }
    // Último recurso: convertir el formato con ffmpeg
    if (canConvert && token === this.token && await this.tryConvert(item, token, live)) return;
    if (token === this.token) {
      this.onStatus('');
      this.onError(lastErr?.message || 'No se pudo reproducir');
    }
  }

  async tryConvert(item, token, live) {
    if (token !== this.token) return false;
    this.onStatus(IOS ? 'Preparando el canal para iPhone…' : 'Convirtiendo el formato del canal…');
    try {
      if (IOS) await this.tryUrl(hlsConvertUrl(item.url, item, live), 'hls', live, 'Convertido para iPhone (ffmpeg → HLS)');
      else await this.tryUrl(transcodeUrl(item.url, item), 'ts', live, 'Convertido (ffmpeg)');
      if (token === this.token) this.onStatus('');
      return true;
    } catch { return false; }
  }

  // Chrome reproduce el video pero deja el audio AC-3 en silencio: si pasa, convertir
  watchSilentAudio(item, token, live) {
    clearTimeout(this.silentTimer);
    const v = this.video;
    // Safari (iPhone/iPad) no cuenta el audio decodificado en HLS nativo: siempre marca 0 y la app
    // reiniciaba el canal para "convertirlo" cada pocos segundos (parecía que nunca terminaba de cargar)
    if (IOS || !('webkitAudioDecodedByteCount' in v)) return;
    this.silentTimer = setTimeout(async () => {
      if (token !== this.token || v.paused || v.webkitAudioDecodedByteCount > 0 || v.currentTime < 2) return;
      this.onStatus('El audio del canal no es compatible: convirtiendo…');
      const t = ++this.token;
      if (!(await this.tryConvert(item, t, live))) this.onStatus('');
    }, 5000);
  }

  tryUrl(url, type, live, kindOverride) {
    this.destroy();
    const v = this.video;
    return new Promise((resolve, reject) => {
      let settled = false;
      const ok = () => { if (!settled) { settled = true; cleanup(); this.onInfo(); if (live) this.startCushion(); resolve(); } };
      const fail = (msg) => { if (!settled) { settled = true; cleanup(); reject(new Error(msg)); } };
      // La conversión a HLS para iPhone tarda más en entregar los primeros trozos
      const timer = setTimeout(() => fail('Tiempo de espera agotado'), /iPhone/.test(kindOverride || '') ? 50000 : 25000);
      const onPlaying = () => ok();
      const onErr = () => fail('Formato no soportado o error de red');
      const cleanup = () => { clearTimeout(timer); v.removeEventListener('playing', onPlaying); v.removeEventListener('error', onErr); };
      v.addEventListener('playing', onPlaying);
      v.addEventListener('error', onErr);

      const start = () => v.play().catch(() => {
        // iPhone bloquea el sonido sin un toque: se intenta en silencio y se avisa; si tampoco, el usuario pulsa play
        if (IOS && !v.muted) { v.muted = true; v.dataset.autoMuted = '1'; v.play().then(() => this.onStatus('Toca el video para activar el sonido 🔊')).catch(() => ok()); return; }
        ok();
      });

      if (type === 'hls') {
        // En iPhone/iPad, HLS nativo de Safari (más estable que hls.js allí, y con imagen en imagen y AirPlay)
        if (window.Hls && Hls.isSupported() && !(IOS && v.canPlayType('application/vnd.apple.mpegurl'))) {
          const B = BUFFER[getSettings().buffer] || BUFFER.balanced;
          const hls = new Hls({
            enableWorker: true, lowLatencyMode: false, renderTextTracksNatively: true, backBufferLength: 30,
            // Estabilidad en vivo: sin saltos al borde del directo ni aceleraciones para "alcanzarlo"
            liveSyncDurationCount: 4, liveMaxLatencyDurationCount: Infinity, maxLiveSyncPlaybackRate: 1,
            maxBufferLength: B.hls, maxMaxBufferLength: B.hls * 2, maxBufferSize: B.mb * 1e6,
            manifestLoadingMaxRetry: 3, levelLoadingMaxRetry: 6, fragLoadingMaxRetry: 6,
          });
          this.engine = hls; this.kind = kindOverride || 'HLS';
          hls.on(Hls.Events.ERROR, (_, d) => {
            if (!d.fatal) return;
            if (settled && d.type === Hls.ErrorTypes.NETWORK_ERROR) { hls.startLoad(); return; }      // corte momentáneo
            if (settled && d.type === Hls.ErrorTypes.MEDIA_ERROR) { hls.recoverMediaError(); return; }
            fail(hlsError(d));
          });
          hls.on(Hls.Events.MANIFEST_PARSED, start);
          for (const ev of [Hls.Events.AUDIO_TRACKS_UPDATED, Hls.Events.LEVEL_SWITCHED]) hls.on(ev, () => this.onInfo());
          hls.loadSource(url);
          hls.attachMedia(v);
        } else if (v.canPlayType('application/vnd.apple.mpegurl')) {
          this.kind = kindOverride || 'HLS'; v.src = url; start();
        } else fail('HLS no soportado en este navegador');
      } else if (type === 'ts') {
        if (IOS && !(window.mpegts && mpegts.getFeatureList().mseLivePlayback)) { fail('Safari en iPhone no reproduce MPEG-TS directo'); return; }
        if (window.mpegts && mpegts.getFeatureList().mseLivePlayback) {
          const p = mpegts.createPlayer({ type: 'mpegts', isLive: live, url }, {
            // Búfer antes de empezar y SIN "persecución de latencia": era lo que hacía saltar la imagen hacia adelante
            enableStashBuffer: true, stashInitialSize: (BUFFER[getSettings().buffer] || BUFFER.balanced).stash * 1024,
            liveBufferLatencyChasing: false, autoCleanupSourceBuffer: true,
            autoCleanupMaxBackwardDuration: 60, autoCleanupMinBackwardDuration: 30,
            lazyLoad: false, fixAudioTimestampGap: true,
          });
          this.engine = p; this.kind = kindOverride || 'MPEG-TS';
          let retries = 0;
          p.on(mpegts.Events.ERROR, (t, d) => {
            // Corte del directo: reconectar con calma (máx. 5 veces seguidas) en vez de reiniciar en bucle
            if (settled && live && retries < 5) {
              retries++;
              setTimeout(() => { if (this.engine === p) { try { p.unload(); p.load(); p.play(); } catch {} } }, 1000 * retries);
              return;
            }
            fail(`MPEG-TS: ${t} ${d || ''}`);
          });
          v.addEventListener('playing', () => { retries = 0; });
          p.attachMediaElement(v);
          p.load();
          start();
        } else { this.kind = kindOverride || 'MPEG-TS'; v.src = url; start(); }
      } else {
        this.kind = kindOverride || 'Archivo'; v.src = url; start();
      }
    });
  }

  /** Colchón en vivo (Ajustes → Reproducción): reproducir un poco más lento mientras quede poco guardado. */
  startCushion() {
    clearInterval(this.cushionTimer);
    const s = getSettings();
    if (s.liveCushion === false) return;
    const v = this.video;
    this.cushionTimer = setInterval(() => {
      if (v.paused || !v.buffered.length) return;
      if (![1, SLOW, SLOWER].includes(v.playbackRate)) return;     // el usuario eligió otra velocidad
      const ahead = v.buffered.end(v.buffered.length - 1) - v.currentTime;
      const next = cushionSpeed(ahead, cushionTarget(s.buffer), v.playbackRate);
      if (next !== v.playbackRate) { v.preservesPitch = true; v.playbackRate = next; }
    }, 1000);
  }

  // ---- Pistas de audio y calidad (sólo HLS informa varias) ----
  audioTracks() {
    if (this.engine && window.Hls && this.engine instanceof Hls) {
      return this.engine.audioTracks.map((t, i) => ({ id: i, label: t.name || t.lang || `Audio ${i + 1}`, active: i === this.engine.audioTrack }));
    }
    const at = this.video.audioTracks;
    if (at && at.length) return [...at].map((t, i) => ({ id: i, label: t.label || t.language || `Audio ${i + 1}`, active: t.enabled }));
    return [];
  }
  setAudioTrack(id) {
    if (this.engine && window.Hls && this.engine instanceof Hls) { this.engine.audioTrack = id; return; }
    const at = this.video.audioTracks;
    if (at) [...at].forEach((t, i) => (t.enabled = i === id));
  }
  levels() {
    if (!(this.engine && window.Hls && this.engine instanceof Hls) || this.engine.levels.length < 2) return [];
    const auto = this.engine.autoLevelEnabled;
    return [{ id: -1, label: 'Automática', active: auto },
      ...this.engine.levels.map((l, i) => ({ id: i, label: l.height ? `${l.height}p` : `${Math.round(l.bitrate / 1000)} kbps`, active: !auto && i === this.engine.currentLevel }))];
  }
  setLevel(id) { if (this.engine && window.Hls && this.engine instanceof Hls) this.engine.currentLevel = id; }

  /** Datos técnicos para el panel de información. */
  stats() {
    const v = this.video, out = { Motor: this.kind || '—' };
    if (v.videoWidth) out.Resolución = `${v.videoWidth}×${v.videoHeight}`;
    if (this.engine && window.Hls && this.engine instanceof Hls) {
      const l = this.engine.levels[this.engine.currentLevel];
      if (l?.bitrate) out.Bitrate = `${(l.bitrate / 1e6).toFixed(1)} Mb/s`;
      if (l?.videoCodec || l?.audioCodec) out.Códecs = [l.videoCodec, l.audioCodec].filter(Boolean).join(' · ');
    }
    if (this.engine && window.mpegts && this.engine.mediaInfo) {
      const mi = this.engine.mediaInfo;
      if (mi.videoCodec || mi.audioCodec) out.Códecs = [mi.videoCodec, mi.audioCodec].filter(Boolean).join(' · ');
      if (mi.fps) out.FPS = Math.round(mi.fps);
    }
    if (this.engine && window.mpegts && this.engine.statisticsInfo?.speed) out.Descarga = `${Math.round(this.engine.statisticsInfo.speed)} KB/s`;
    const q = v.getVideoPlaybackQuality?.();
    if (q) out['Cuadros perdidos'] = `${q.droppedVideoFrames} / ${q.totalVideoFrames}`;
    if (v.buffered.length) out.Búfer = `${Math.max(0, v.buffered.end(v.buffered.length - 1) - v.currentTime).toFixed(1)} s`;
    return out;
  }
}
