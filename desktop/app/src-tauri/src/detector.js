// Chaya for Windows' media detector. It runs at the start of every document of the browser's page, frames
// included, as the phone's chaya_media_detector.js and chaya_frame_agent.js do between them. It looks only at what
// the page's players already load and name, and makes no request of its own.
//
// It hands the page nothing: there is no bridge into the app. The app reads what the page's document found by
// calling window[KEY]() (`browser::page_media`); a frame tells the page's document by postMessage, with a nonce
// this script alone knows. KEY and NONCE are new random words for every page the app opens. What a page reports is
// still the page's word, and the app checks all of it (`chaya-core`'s `found`).
(function () {
  var KEY = '__CHAYA_KEY__';
  var NONCE = '__CHAYA_NONCE__';
  if (Object.prototype.hasOwnProperty.call(window, KEY)) return;

  // Taken before the page's own scripts run, so the page cannot change what they do for this script.
  var stringify = JSON.stringify;
  var isTop = window.top === window;
  var MAX_ITEMS = 200;
  var MAX_BODY_BYTES = 2 * 1024 * 1024;

  var found = [];
  var foundUrls = {};
  // Once a player has loaded a stream's playlist, the video it fetches is that stream's pieces.
  var sawManifest = false;
  // What this frame told the page, told again when the page asks: it may not have been listening yet.
  var told = [];

  function absolute(u) {
    try { return new URL(u, location.href).href; } catch (e) { return null; }
  }

  function isHttp(u) {
    return typeof u === 'string' && (u.indexOf('http://') === 0 || u.indexOf('https://') === 0);
  }

  function extensionKind(u) {
    try {
      var p = new URL(u, location.href).pathname.toLowerCase();
      if (/\.m3u8$/.test(p)) return 'hls';
      if (/\.mpd$/.test(p)) return 'dash';
      if (/\.(mp4|m4v|webm|mov|mkv|mp3|m4a|aac|ogg|oga|opus|wav|flac)$/.test(p)) return 'file';
    } catch (e) {}
    return null;
  }

  function kindOfType(contentType) {
    var type = String(contentType || '').split(';')[0].trim().toLowerCase();
    if (type === 'application/vnd.apple.mpegurl' || type === 'application/x-mpegurl' || type === 'audio/mpegurl') return 'hls';
    if (type === 'application/dash+xml') return 'dash';
    if (type.indexOf('video/') === 0 || type.indexOf('audio/') === 0) return 'file';
    return null;
  }

  function isPieceType(contentType) {
    var type = String(contentType || '').split(';')[0].trim().toLowerCase();
    return type === 'video/mp2t' || type === 'audio/mp2t' || type === 'video/iso.segment' || type === 'audio/iso.segment';
  }

  // A playlist of the pictures a player shows while seeking, not something to watch.
  function isImagePlaylist(text) {
    var body = String(text || '');
    if (/#EXT-X-IMAGES-ONLY|#EXT-X-TILES/i.test(body)) return true;
    var uris = body.split(/\r?\n/).map(function (line) { return line.trim(); })
      .filter(function (line) { return line && line.charAt(0) !== '#'; });
    if (!uris.length) return false;
    for (var i = 0; i < uris.length; i++) {
      if (!/\.(jpe?g|png|webp|gif|avif)([?#]|$)/i.test(uris[i])) return false;
    }
    return true;
  }

  function kindOfBody(text) {
    var head = String(text || '').slice(0, 512).replace(/^﻿/, '').trim();
    if (head.indexOf('#EXTM3U') === 0) return 'hls';
    if (/^<\?xml[^>]*>\s*<MPD[\s>]/i.test(head) || /^<MPD[\s>]/i.test(head)) return 'dash';
    return null;
  }

  // Only small text-like answers are read, the way a playlist is served; video bytes never are.
  function bodyWorthReading(contentType, length) {
    var type = String(contentType || '').split(';')[0].trim().toLowerCase();
    if (length && Number(length) > MAX_BODY_BYTES) return false;
    return type === '' || type.indexOf('text/') === 0 || type === 'application/octet-stream' ||
      type === 'binary/octet-stream' || type === 'application/json';
  }

  function keep(item) {
    if (foundUrls[item.url] || found.length >= MAX_ITEMS) return;
    foundUrls[item.url] = true;
    found.push(item);
  }

  function report(rawUrl, how, kind, type) {
    var url = absolute(rawUrl);
    if (!isHttp(url) || !kind || isPieceType(type)) return;
    if (kind === 'hls' || kind === 'dash') sawManifest = true;
    else if (sawManifest && (how === 'fetch' || how === 'xhr' || how === 'resource')) return;
    var item = { url: url.split('#')[0], how: how, kind: kind, type: String(type || '').slice(0, 100), frame: '' };
    if (isTop) {
      keep(item);
    } else {
      item.frame = location.href.split('#')[0];
      if (told.length >= MAX_ITEMS) return;
      told.push(item);
      tell(item);
    }
  }

  function tell(item) {
    try { window.top.postMessage({ chaya: NONCE, item: item }, '*'); } catch (e) {}
  }

  window.addEventListener('message', function (event) {
    var d = event.data;
    if (!d || d.chaya !== NONCE) return;
    if (isTop && d.item && event.source !== window) {
      var it = d.item;
      if (typeof it.url !== 'string' || it.url.length > 4096 || !isHttp(it.url)) return;
      var frame = typeof it.frame === 'string' && isHttp(it.frame) ? it.frame : '';
      // The frame's word for where it is must agree with where the browser says the message came from.
      try { if (frame && new URL(frame).origin !== event.origin) frame = ''; } catch (e) { frame = ''; }
      keep({ url: it.url, how: String(it.how || '').slice(0, 20), kind: String(it.kind || '').slice(0, 10),
        type: String(it.type || '').slice(0, 100), frame: frame });
    } else if (!isTop && d.ask && event.source === window.parent) {
      for (var i = 0; i < told.length; i++) tell(told[i]);
      askFrames();
    }
  });

  function askFrames() {
    for (var f = 0; f < window.frames.length; f++) {
      try { window.frames[f].postMessage({ chaya: NONCE, ask: 1 }, '*'); } catch (e) {}
    }
  }

  // ── What the page's players show and fetch ──────────────────────────────────────────────

  var players = [];

  function scanRoot(root) {
    if (!root || !root.querySelectorAll) return;
    var els = root.querySelectorAll('video, audio, source[src]');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el.tagName !== 'SOURCE' && players.indexOf(el) < 0) players.push(el);
      var src = el.currentSrc || el.src || el.getAttribute('src');
      if (src && isHttp(absolute(src))) {
        var typeAttr = el.getAttribute('type') || '';
        report(src, el.tagName.toLowerCase(), extensionKind(src) || kindOfType(typeAttr) || 'file', typeAttr);
      }
      if (el.shadowRoot) scanRoot(el.shadowRoot);
    }
    var hosts = root.querySelectorAll('*');
    for (var h = 0; h < hosts.length && h < 5000; h++) {
      if (hosts[h].shadowRoot) scanRoot(hosts[h].shadowRoot);
    }
  }

  function scanMeta() {
    ['meta[property="og:video:secure_url"]', 'meta[property="og:video:url"]', 'meta[property="og:video"]',
      'meta[name="twitter:player:stream"]'].forEach(function (selector) {
      var node = document.querySelector(selector);
      var url = node && node.getAttribute('content');
      if (url) report(url, 'meta', extensionKind(url) || 'file', '');
    });
  }

  var scanTimer = null;
  function scheduleScan() {
    if (scanTimer) return;
    scanTimer = setTimeout(function () {
      scanTimer = null;
      try { scanRoot(document); if (isTop) scanMeta(); } catch (e) {}
    }, 300);
  }

  function watch() {
    if (!document.documentElement || !window.MutationObserver) return;
    new MutationObserver(scheduleScan).observe(document.documentElement, {
      childList: true, subtree: true, attributes: true, attributeFilter: ['src']
    });
    scheduleScan();
  }
  if (document.documentElement) watch();
  else document.addEventListener('DOMContentLoaded', watch, { once: true });
  ['play', 'loadedmetadata'].forEach(function (type) { document.addEventListener(type, scheduleScan, true); });
  if (isTop) {
    window.addEventListener('load', askFrames);
    askFrames();
  }

  // Everything the browser loaded for the page, as it reports it: a file by its name.
  try {
    new PerformanceObserver(function (list) {
      list.getEntries().forEach(function (entry) {
        var kind = extensionKind(entry.name);
        if (kind) report(entry.name, 'resource', kind, '');
      });
    }).observe({ type: 'resource', buffered: true });
  } catch (e) {}

  function reportPlaylist(url, how, type, textPromise) {
    sawManifest = true;
    textPromise.then(function (text) {
      if (!isImagePlaylist(text)) report(url, how, 'hls', type);
    }, function () { report(url, how, 'hls', type); });
  }

  if (window.fetch) {
    var originalFetch = window.fetch;
    window.fetch = function (input, init) {
      var requestUrl = null;
      try { requestUrl = absolute((input && input.url) ? input.url : String(input)); } catch (e) {}
      var byName = requestUrl && extensionKind(requestUrl);
      if (byName && byName !== 'hls') report(requestUrl, 'fetch', byName, '');
      var result = originalFetch.apply(this, arguments);
      if ((!byName || byName === 'hls') && result && result.then) {
        result.then(function (response) {
          try {
            var url = response.url || requestUrl;
            var type = response.headers && response.headers.get('Content-Type');
            var byType = byName || kindOfType(type);
            if (byType === 'hls') {
              reportPlaylist(url, 'fetch', type, response.clone().text());
            } else if (byType) {
              report(url, 'fetch', byType, type);
            } else if (response.ok && bodyWorthReading(type, response.headers.get('Content-Length'))) {
              response.clone().text().then(function (text) {
                var byBody = kindOfBody(text);
                if (byBody === 'hls' && isImagePlaylist(text)) return;
                if (byBody) report(url, 'fetch', byBody, '');
              }, function () {});
            }
          } catch (e) {}
        }, function () {});
      }
      return result;
    };
  }

  if (window.XMLHttpRequest) {
    var originalOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (method, url) {
      try { this.__chayaUrl = absolute(url); } catch (e) {}
      return originalOpen.apply(this, arguments);
    };
    var originalSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function () {
      var xhr = this;
      var byName = xhr.__chayaUrl && extensionKind(xhr.__chayaUrl);
      if (byName && byName !== 'hls') {
        report(xhr.__chayaUrl, 'xhr', byName, '');
      } else {
        if (byName === 'hls') sawManifest = true;
        var onLoadEnd = function () {
          xhr.removeEventListener('loadend', onLoadEnd);
          try {
            if (xhr.status < 200 || xhr.status >= 300) return;
            var url = xhr.responseURL || xhr.__chayaUrl;
            var type = xhr.getResponseHeader('Content-Type');
            var readable = xhr.responseType === '' || xhr.responseType === 'text';
            var kind = byName || kindOfType(type);
            if (!kind && readable) {
              kind = kindOfBody(xhr.responseText);
              type = '';
            }
            if (kind === 'hls' && readable && isImagePlaylist(xhr.responseText)) return;
            if (kind) report(url, 'xhr', kind, type);
          } catch (e) {}
        };
        xhr.addEventListener('loadend', onLoadEnd);
      }
      return originalSend.apply(this, arguments);
    };
  }

  // ── What the app reads ───────────────────────────────────────────────────────────────────

  function clip(value, max) {
    return typeof value === 'string' ? value.replace(/\s+/g, ' ').trim().slice(0, max) : '';
  }

  function metaContent(selector) {
    var node = document.querySelector(selector);
    return node ? (node.getAttribute('content') || '') : '';
  }

  function heading() {
    var shown = [];
    var h1s = document.querySelectorAll('h1');
    for (var i = 0; i < h1s.length; i++) {
      var r = h1s[i].getClientRects();
      if (r.length && (h1s[i].textContent || '').trim()) shown.push(h1s[i]);
    }
    return shown.length === 1 ? clip(shown[0].textContent, 200) : '';
  }

  function playerMeta(el) {
    var rect = el.getBoundingClientRect();
    var style = window.getComputedStyle(el);
    var src = el.currentSrc || el.src || '';
    return {
      src: isHttp(src) ? src : '',
      w: Math.round(rect.width),
      h: Math.round(rect.height),
      visible: rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden',
      playing: !el.paused && !el.ended
    };
  }

  if (isTop) {
    Object.defineProperty(window, KEY, {
      enumerable: false,
      writable: false,
      configurable: false,
      value: function () {
        var shown = [];
        for (var i = 0; i < players.length && shown.length < 16; i++) {
          try { if (players[i].isConnected) shown.push(playerMeta(players[i])); } catch (e) {}
        }
        return stringify({
          items: found,
          meta: {
            title: clip(document.title, 300),
            ogTitle: clip(metaContent('meta[property="og:title"]'), 300),
            siteName: clip(metaContent('meta[property="og:site_name"]'), 120),
            heading: heading(),
            players: shown
          }
        });
      }
    });
  } else {
    Object.defineProperty(window, KEY, { enumerable: false, writable: false, configurable: false, value: null });
  }
})();
