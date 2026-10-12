/*
 * The notebook's service worker: what lets the installed app open with no signal.
 *
 * It keeps the app's own shell — the four pages and the hashed scripts, styles and
 * fonts they load — and nothing else. Your tasks and calendar are never cached here:
 * the API is another origin, which this worker does not touch, and the client keeps
 * its own last-seen pages (lib/offline.ts). Pages are fetched from the network first so
 * a deploy is picked up at once; the cache answers only when the network cannot.
 */
const CACHE = "dailybrief-shell-v1";
const PAGES = ["/", "/today", "/projects", "/settings"];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => Promise.allSettled(PAGES.map((page) => cache.add(page))))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

async function networkFirst(request, fallback) {
  const cache = await caches.open(CACHE);
  try {
    const response = await fetch(request);
    if (response.ok) cache.put(request, response.clone());
    return response;
  } catch (error) {
    const kept = (await cache.match(request)) ?? (fallback ? await cache.match(fallback) : undefined);
    if (kept) return kept;
    throw error;
  }
}

async function cacheFirst(request) {
  const cache = await caches.open(CACHE);
  const kept = await cache.match(request);
  if (kept) return kept;
  const response = await fetch(request);
  if (response.ok) cache.put(request, response.clone());
  return response;
}

self.addEventListener("fetch", (event) => {
  const { request } = event;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  // The OAuth return carries a one-time code; it is never kept.
  if (url.pathname.startsWith("/auth/")) return;

  if (request.mode === "navigate") {
    // A share or shortcut arrives as /?jot=1&text=…: the shell is the same page.
    const page = PAGES.includes(url.pathname) ? url.pathname : "/";
    event.respondWith(networkFirst(url.search ? new Request(page) : request, page).catch(() => caches.match("/")));
    return;
  }
  // Hashed build output never changes under one name.
  if (url.pathname.startsWith("/_next/static/") || url.pathname.startsWith("/icons/")) {
    event.respondWith(cacheFirst(request));
    return;
  }
  // Page-to-page navigation inside the app fetches a small payload per page.
  if (url.searchParams.has("_rsc")) event.respondWith(networkFirst(request));
});
