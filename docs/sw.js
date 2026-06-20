/**
 * Minimal service worker for MOAF.
 * Caches the app shell so it loads offline; falls back to cache on network failure
 * but always tries network first for the JSON data so changes are picked up.
 */
const CACHE_NAME = 'moaf-v10';
const SHELL = [
  './',
  './index.html',
  './app.js',
  './data-loader.js',
  './github-api.js',
  './views/factions.js',
  './views/npcs.js',
  './views/maps.js',
  './views/notes.js',
  './views/admin-panel.js',
  './styles/base.css',
  './styles/factions.css',
  './styles/npcs.css',
  './styles/maps.css',
  './styles/notes.css',
  './banner.png',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './manifest.json'
];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE_NAME).then(c => c.addAll(SHELL)).catch(() => {}));
  self.skipWaiting();
});

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(keys =>
    Promise.all(keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k)))
  ));
  self.clients.claim();
});

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  // Pass through API and PUT requests without caching
  if (e.request.method !== 'GET') return;
  if (url.hostname === 'api.github.com') return;

  // Network-first for JSON (campaign/notes data); cache on success
  if (url.pathname.endsWith('.json') || url.hostname === 'raw.githubusercontent.com') {
    e.respondWith(
      fetch(e.request)
        .then(r => {
          const copy = r.clone();
          caches.open(CACHE_NAME).then(c => c.put(e.request, copy)).catch(() => {});
          return r;
        })
        .catch(() => caches.match(e.request))
    );
    return;
  }
  // Network-first for code (JS/CSS/HTML) so updates appear without cache clearing.
  if (url.pathname.endsWith('.js') || url.pathname.endsWith('.css') ||
      url.pathname.endsWith('.html') || url.pathname === '/' || url.pathname.endsWith('/')) {
    e.respondWith(
      fetch(e.request)
        .then(r => {
          const copy = r.clone();
          caches.open(CACHE_NAME).then(c => c.put(e.request, copy)).catch(() => {});
          return r;
        })
        .catch(() => caches.match(e.request))
    );
    return;
  }
  // Cache-first only for images and other static assets
  e.respondWith(
    caches.match(e.request).then(r => r || fetch(e.request))
  );
});
