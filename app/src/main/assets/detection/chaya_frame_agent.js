// Runs inside embedded frames (a player from another server), never in the page itself. It watches what the
// frame's own player already loaded and tells the page's detector about media by postMessage. It cannot reach the
// app: the page's detector holds the capability, and the app accepts a frame's report only for an address it saw
// the browser request. It makes no request of its own.
(function () {
  if (window.top === window || window.__chayaFrameAgent) return;
  window.__chayaFrameAgent = true;

  var reported = {};
  // Everything told so far, told again when the page's detector asks: it may not have been listening yet.
  var told = [];
  var MAX_REPORTS = 40;
  var reportCount = 0;
  // Bodies read to recognize a manifest: only small text responses, and only their first characters.
  var MAX_BODY_BYTES = 2 * 1024 * 1024;
  // Once this frame's player has loaded a stream manifest, the video and audio it fetches are that stream's pieces
  // (hls.js and the like fetch every piece themselves), whatever their addresses and types say.
  var sawManifest = false;

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

  // stream pieces are never whole files; mirrors MediaUrlClassifier.isSegment on the native side
  function isSegment(u) {
    try {
      var path = new URL(u, location.href).pathname;
      var name = path.substring(path.lastIndexOf('/') + 1).toLowerCase();
      if (/\.(ts|m4s|cmfv|cmfa|m4f)$/.test(name)) return true;
      return /(^|[-_.])(seg|segment|chunk|frag|fragment)[-_]?\d+/.test(name) ||
        /(^|[-_.])init([-_.]|$)/.test(name);
    } catch (e) { return false; }
  }

  function kindOfType(contentType) {
    var type = String(contentType || '').split(';')[0].trim().toLowerCase();
    if (type === 'application/vnd.apple.mpegurl' || type === 'application/x-mpegurl' || type === 'audio/mpegurl') return 'hls';
    if (type === 'application/dash+xml') return 'dash';
    if (type.indexOf('video/') === 0 || type.indexOf('audio/') === 0) return 'file';
    return null;
  }

  // Answers that are always a piece of a stream: MPEG-TS and the ISO segment types.
  function isPieceType(contentType) {
    var type = String(contentType || '').split(';')[0].trim().toLowerCase();
    return type === 'video/mp2t' || type === 'audio/mp2t' || type === 'video/iso.segment' ||
      type === 'audio/iso.segment';
  }

  // A playlist of pictures (the thumbnails a player shows while seeking), not something to watch.
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

  // Only text-like answers small enough to hold are read, the way a manifest is served; video bytes never are.
  function bodyWorthReading(contentType, length) {
    var type = String(contentType || '').split(';')[0].trim().toLowerCase();
    if (length && Number(length) > MAX_BODY_BYTES) return false;
    return type === '' || type.indexOf('text/') === 0 || type === 'application/octet-stream' ||
      type === 'binary/octet-stream' || type === 'application/json';
  }

  function report(rawUrl, tag, kind, type) {
    var url = absolute(rawUrl);
    if (!isHttp(url) || !kind || isSegment(url) || isPieceType(type)) return;
    if (kind === 'hls' || kind === 'dash') sawManifest = true;
    else if (sawManifest && (tag === 'fetch' || tag === 'xhr')) return;
    url = url.split('#')[0];
    if (reported[url] || reportCount >= MAX_REPORTS) return;
    reported[url] = true;
    reportCount += 1;
    var message = {
      chayaFrameMedia: 1,
      url: url,
      tag: tag || '',
      kind: kind,
      type: type || '',
      frame: location.href.split('#')[0]
    };
    told.push(message);
    tell(message);
  }

  function tell(message) {
    try { window.top.postMessage(message, '*'); } catch (e) {}
  }

  // The page's detector asks once it is listening; the question is passed on to frames inside this one.
  window.addEventListener('message', function (event) {
    var d = event.data;
    if (!d || d.chayaFrameAsk !== 1 || event.source !== window.parent) return;
    for (var i = 0; i < told.length; i++) tell(told[i]);
    for (var f = 0; f < window.frames.length; f++) {
      try { window.frames[f].postMessage({ chayaFrameAsk: 1 }, '*'); } catch (e) {}
    }
  });

  function scanRoot(root) {
    if (!root || !root.querySelectorAll) return;
    var els = root.querySelectorAll('video, audio, source[src]');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      var src = el.currentSrc || el.src || el.getAttribute('src');
      if (src && isHttp(absolute(src))) {
        var typeAttr = el.getAttribute('type') || '';
        report(src, el.tagName.toLowerCase(), extensionKind(src) || kindOfType(typeAttr) || 'file', typeAttr);
      }
      if (el.shadowRoot) scanRoot(el.shadowRoot);
    }
  }

  var scanTimer = null;
  function scheduleScan() {
    if (scanTimer) return;
    scanTimer = setTimeout(function () {
      scanTimer = null;
      try { scanRoot(document); } catch (e) {}
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
  ['play', 'loadedmetadata'].forEach(function (type) {
    document.addEventListener(type, scheduleScan, true);
  });

  // Reports an HLS playlist once its text is known, unless it lists only pictures; one that cannot be read is
  // reported as it is.
  function reportPlaylist(url, tag, type, textPromise) {
    sawManifest = true;
    textPromise.then(function (text) {
      if (!isImagePlaylist(text)) report(url, tag, 'hls', type);
    }, function () { report(url, tag, 'hls', type); });
  }

  if (window.fetch) {
    var originalFetch = window.fetch;
    window.fetch = function (input, init) {
      var requestUrl = null;
      try { requestUrl = absolute((input && input.url) ? input.url : String(input)); } catch (e) {}
      var byName = requestUrl && extensionKind(requestUrl);
      // An HLS playlist waits for its answer: one of pictures is left out.
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
      try { this.__chayaFrameUrl = absolute(url); } catch (e) {}
      return originalOpen.apply(this, arguments);
    };
    var originalSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function () {
      var xhr = this;
      var byName = xhr.__chayaFrameUrl && extensionKind(xhr.__chayaFrameUrl);
      if (byName && byName !== 'hls') {
        report(xhr.__chayaFrameUrl, 'xhr', byName, '');
      } else {
        if (byName === 'hls') sawManifest = true;
        var onLoadEnd = function () {
          xhr.removeEventListener('loadend', onLoadEnd);
          try {
            if (xhr.status < 200 || xhr.status >= 300) return;
            var url = xhr.responseURL || xhr.__chayaFrameUrl;
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
})();
