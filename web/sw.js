// Service worker mínimo: cachea sólo la interfaz (nunca streams ni listas)
const CACHE = 'vibe-shell-v2';
const SHELL = ['./', 'index.html', 'css/styles.css', 'js/app.js', 'js/m3u.js', 'js/xtream.js', 'js/epg.js',
  'js/player.js', 'js/net.js', 'js/nav.js', 'js/storage.js', 'js/subtitles.js', 'js/settings.js', 'js/demo.js',
  'vendor/hls.min.js', 'vendor/mpegts.js', 'icon.svg', 'manifest.webmanifest'];

self.addEventListener('install', (e) => e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting())));
self.addEventListener('activate', (e) => e.waitUntil(
  caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim())));

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin || url.pathname.startsWith('/proxy')) return;
  // Red primero, caché como respaldo sin conexión
  e.respondWith(fetch(e.request).then((r) => {
    const copy = r.clone();
    caches.open(CACHE).then((c) => c.put(e.request, copy));
    return r;
  }).catch(() => caches.match(e.request)));
});
