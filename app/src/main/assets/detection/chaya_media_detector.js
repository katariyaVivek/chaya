(function () {
  if (window.__chayaInjected) return;
  window.__chayaInjected = true;

  var reported = {};
  var scanTimer = null;

  // sends a direct media report only when the main document received its native capability
  function postMedia(url, tagName, typeAttr) {
    var capability = window.__chayaBridgeCapability;
    var bridge = window.ChayaBridge;
    if (!capability || !bridge || !bridge.onMediaDetectedWithType) return false;
    try {
      bridge.onMediaDetectedWithType(capability, url, tagName, typeAttr);
      return true;
    } catch (e) {
      return false;
    }
  }

  // sends an opt-in unknown endpoint only when the main document received its native capability
  function postMediaCandidate(url, tagName) {
    var capability = window.__chayaBridgeCapability;
    var bridge = window.ChayaBridge;
    if (!capability || !bridge || !bridge.onMediaCandidate) return false;
    try {
      bridge.onMediaCandidate(capability, url, tagName);
      return true;
    } catch (e) {
      return false;
    }
  }

  function isHttp(u) {
    return typeof u === 'string' && (u.indexOf('http://') === 0 || u.indexOf('https://') === 0);
  }

  function absolute(u) {
    try { return new URL(u, location.href).href; } catch (e) { return null; }
  }

  function looksLikeMedia(u) {
    try {
      var p = new URL(u, location.href).pathname.toLowerCase();
      return /\.(m3u8|mpd|mp4|m4v|webm|mov|mkv|avi|wmv|3gp|mp3|m4a|aac|ogg|oga|opus|wav|flac|mka|ts)$/.test(p);
    } catch (e) { return false; }
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

  function sendMedia(rawUrl, tagName, typeAttr) {
    if (!rawUrl) return;
    var url = absolute(rawUrl);
    if (!isHttp(url)) return;              // skip blob:, data:, about:, ...
    if (reported[url] || isSegment(url)) return;
    if (postMedia(url, tagName || '', typeAttr || '')) {
      reported[url] = true;
    }
  }

  // ---- page and player hints for ranking and naming (read-only DOM, no network) ----

  var watchedPlayers = [];
  var pageMetaTimer = null;
  var lastPageMetaJson = '';
  var AD_TOKEN = /(^|[\s_-])(ad|ads|advert|advertisement|adunit|adslot|adcontainer|preroll|midroll|postroll|sponsor|sponsored|promo|ima|vast|vpaid|outstream|dfp|gpt)([\s_-]|$)/i;

  function clip(value, max) {
    return typeof value === 'string' ? value.replace(/\s+/g, ' ').trim().slice(0, max) : '';
  }

  function httpUrlOrNull(value) {
    if (typeof value !== 'string' || !value) return null;
    var url = absolute(value);
    return isHttp(url) ? url : null;
  }

  // remembers each media element once and re-reports hints when its playback state changes
  function watchPlayer(el) {
    if (el.__chayaWatched) return;
    el.__chayaWatched = true;
    watchedPlayers.push(el);
    ['loadedmetadata', 'durationchange', 'play', 'playing', 'pause', 'emptied'].forEach(function (type) {
      el.addEventListener(type, schedulePageMeta);
    });
  }

  // walks out through shadow roots so ad slots wrapping custom players are still recognized
  function inAdContainer(el) {
    var node = el;
    for (var depth = 0; node && depth < 10; depth++) {
      if (node.nodeType === 1) {
        var cls = typeof node.className === 'string' ? node.className : (node.getAttribute('class') || '');
        if (AD_TOKEN.test(node.id || '') || AD_TOKEN.test(cls)) return true;
        if (node.hasAttribute('data-ad') || node.hasAttribute('data-ad-slot')) return true;
      }
      node = node.parentNode || node.host || null;
    }
    return false;
  }

  function titleHint(el) {
    var title = el.getAttribute('title') || el.getAttribute('aria-label') || el.getAttribute('data-title') || '';
    if (!title && el.closest) {
      var figure = el.closest('figure');
      var caption = figure && figure.querySelector('figcaption');
      if (caption) title = caption.textContent || '';
    }
    return clip(title, 200) || null;
  }

  function playerMeta(el) {
    var rect = el.getBoundingClientRect();
    var style = window.getComputedStyle ? window.getComputedStyle(el) : null;
    var visible = rect.width > 0 && rect.height > 0 && (!style ||
      (style.display !== 'none' && style.visibility !== 'hidden' && parseFloat(style.opacity || '1') > 0.05));
    var src = el.currentSrc || el.src || '';
    var duration = el.duration;
    return {
      src: httpUrlOrNull(src),
      blob: src.indexOf('blob:') === 0,
      audio: el.tagName === 'AUDIO',
      w: Math.round(rect.width),
      h: Math.round(rect.height),
      visible: visible,
      duration: (typeof duration === 'number' && isFinite(duration) && duration > 0) ? duration : null,
      vw: el.videoWidth || null,
      vh: el.videoHeight || null,
      playing: !el.paused && !el.ended,
      played: !!(el.played && el.played.length > 0),
      muted: !!el.muted,
      autoplay: !!el.autoplay,
      loop: !!el.loop,
      controls: !!el.controls,
      poster: httpUrlOrNull(el.getAttribute('poster') || ''),
      title: titleHint(el),
      ad: inAdContainer(el)
    };
  }

  function findVideoObject(node, depth) {
    if (!node || typeof node !== 'object' || depth > 5) return null;
    if (Array.isArray(node)) {
      for (var i = 0; i < node.length && i < 50; i++) {
        var found = findVideoObject(node[i], depth + 1);
        if (found) return found;
      }
      return null;
    }
    var type = node['@type'];
    if (type === 'VideoObject' || (Array.isArray(type) && type.indexOf('VideoObject') >= 0)) return node;
    return findVideoObject(node['@graph'], depth + 1) ||
      findVideoObject(node.video, depth + 1) ||
      findVideoObject(node.mainEntity, depth + 1);
  }

  function jsonLdVideo() {
    var scripts = document.querySelectorAll('script[type="application/ld+json"]');
    for (var i = 0; i < scripts.length && i < 10; i++) {
      try {
        var found = findVideoObject(JSON.parse(scripts[i].textContent || 'null'), 0);
        if (found) return found;
      } catch (e) {}
    }
    return null;
  }

  function metaContent(selector) {
    var node = document.querySelector(selector);
    return node ? (node.getAttribute('content') || '') : '';
  }

  function firstString(value) {
    if (typeof value === 'string') return value;
    if (Array.isArray(value)) {
      for (var i = 0; i < value.length; i++) {
        if (typeof value[i] === 'string') return value[i];
      }
    }
    if (value && typeof value === 'object' && typeof value.url === 'string') return value.url;
    return '';
  }

  function collectPageMeta() {
    var ld = jsonLdVideo();
    var videoUrls = [];
    ['meta[property="og:video:secure_url"]', 'meta[property="og:video:url"]',
      'meta[property="og:video"]', 'meta[name="twitter:player:stream"]'].forEach(function (selector) {
      var url = httpUrlOrNull(metaContent(selector));
      if (url && videoUrls.indexOf(url) < 0) videoUrls.push(url);
    });
    var ldUrl = ld && httpUrlOrNull(firstString(ld.contentUrl));
    if (ldUrl && videoUrls.indexOf(ldUrl) < 0) videoUrls.push(ldUrl);

    var players = [];
    for (var i = 0; i < watchedPlayers.length; i++) {
      if (watchedPlayers[i].isConnected === false) continue;
      try { players.push(playerMeta(watchedPlayers[i])); } catch (e) {}
    }
    players.sort(function (a, b) { return (b.w * b.h) - (a.w * a.h); });

    return {
      title: clip(document.title, 300),
      ogTitle: clip(metaContent('meta[property="og:title"]'), 300),
      siteName: clip(metaContent('meta[property="og:site_name"]'), 120),
      ogImage: httpUrlOrNull(metaContent('meta[property="og:image"]')),
      videoUrls: videoUrls.slice(0, 8),
      ldName: ld ? clip(firstString(ld.name), 300) : '',
      ldThumbnail: ld ? httpUrlOrNull(firstString(ld.thumbnailUrl)) : null,
      ldDuration: ld ? clip(firstString(ld.duration), 40) : '',
      players: players.slice(0, 8)
    };
  }

  // reports only from the capability-holding main document, and only when something changed
  function postPageMeta() {
    pageMetaTimer = null;
    var capability = window.__chayaBridgeCapability;
    var bridge = window.ChayaBridge;
    if (!capability || !bridge || !bridge.onPageMeta) return;
    try {
      var meta = collectPageMeta();
      var json = JSON.stringify(meta);
      if (json.length > 30000) {
        meta.players = meta.players.slice(0, 2);
        json = JSON.stringify(meta);
        if (json.length > 30000) return;
      }
      if (json === lastPageMetaJson) return;
      lastPageMetaJson = json;
      bridge.onPageMeta(capability, json);
    } catch (e) {}
  }

  // coalesces bursts of media events and DOM mutations into one report
  function schedulePageMeta() {
    if (pageMetaTimer) return;
    pageMetaTimer = setTimeout(postPageMeta, 750);
  }

  function scanRoot(root) {
    if (!root) return;

    // video/audio elements with a resolved source
    var mediaEls = root.querySelectorAll('video, audio');
    for (var i = 0; i < mediaEls.length; i++) {
      var el = mediaEls[i];
      watchPlayer(el);
      var src = el.currentSrc || el.src;
      if (src) {
        sendMedia(src, el.tagName, '');
      } else {
        // no src — check <source> children
        var sources = el.querySelectorAll('source');
        for (var j = 0; j < sources.length; j++) {
          if (sources[j].getAttribute('src')) {
            sendMedia(sources[j].getAttribute('src'), 'source', sources[j].getAttribute('type'));
          }
        }
      }
      // descend into shadow roots
      if (el.shadowRoot) scanRoot(el.shadowRoot);
    }

    // standalone <source> elements
    var allSources = root.querySelectorAll('source[src]');
    for (var k = 0; k < allSources.length; k++) {
      var s = allSources[k];
      var parent = s.parentElement;
      if (!parent || (parent.tagName !== 'VIDEO' && parent.tagName !== 'AUDIO')) {
        sendMedia(s.getAttribute('src'), 'source', s.getAttribute('type'));
      }
      if (s.shadowRoot) scanRoot(s.shadowRoot);
    }
  }

  function deepScan() {
    scanRoot(document);

    // Shadow DOM hosts anywhere in the tree
    var all = document.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
      if (all[i].shadowRoot) scanRoot(all[i].shadowRoot);
    }

    // Same-origin iframes only (cross-origin throws and is skipped)
    var frames = document.querySelectorAll('iframe');
    for (var f = 0; f < frames.length; f++) {
      try {
        var doc = frames[f].contentDocument;
        if (doc) scanRoot(doc);
      } catch (e) { /* cross-origin */ }
    }

    schedulePageMeta();
  }

  function scheduleScan() {
    if (scanTimer) return;
    scanTimer = setTimeout(function () {
      scanTimer = null;
      try { deepScan(); } catch (e) {}
    }, 200);
  }

  // waits for the document root before registering exactly one dynamic-media observer
  var isWatchingMutations = false;
  function observeMutations() {
    if (isWatchingMutations || !document.documentElement || !window.MutationObserver) return;
    isWatchingMutations = true;
    new MutationObserver(scheduleScan).observe(document.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['src']
    });
  }

  deepScan();
  observeMutations();
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () {
      try { deepScan(); } catch (e) {}
      observeMutations();
    }, { once: true });
  }

  // keeps native verification requests within the active document origin
  function isSameOrigin(u) {
    try { return new URL(u, location.href).origin === location.origin; } catch (e) { return false; }
  }

  // filters response metadata so JSON and document fetches never trigger native media checks
  function isMediaContentType(contentType) {
    var type = String(contentType || '').split(';')[0].trim().toLowerCase();
    return type.indexOf('video/') === 0 ||
      type.indexOf('audio/') === 0 ||
      type === 'application/vnd.apple.mpegurl' ||
      type === 'application/x-mpegurl' ||
      type === 'application/dash+xml' ||
      type === 'application/ogg' ||
      type === 'application/x-ogg';
  }

  // bounds queued and active verification traffic when a page makes many media-like requests
  var unknownCandidateCount = 0;
  var maxUnknownCandidates = 10;
  var queuedUnknownCandidates = [];
  var queuedUnknownUrls = {};
  var thoroughScanEnabled = false;

  // forwards a browser-confirmed unknown endpoint after the user has enabled deeper inspection
  function sendMediaCandidate(rawUrl, tagName) {
    if (!rawUrl) return;
    var url = absolute(rawUrl);
    if (!isHttp(url) || !isSameOrigin(url)) return;
    if (reported[url] || unknownCandidateCount >= maxUnknownCandidates) return;
    if (postMediaCandidate(url, tagName || '')) {
      reported[url] = true;
      unknownCandidateCount += 1;
    }
  }

  // remembers browser-confirmed endpoints until the user explicitly asks to inspect them
  function captureMediaCandidate(rawUrl, tagName) {
    if (!rawUrl) return;
    var url = absolute(rawUrl);
    if (!isHttp(url) || !isSameOrigin(url) || reported[url]) return;

    if (thoroughScanEnabled) {
      sendMediaCandidate(url, tagName || '');
      return;
    }

    if (queuedUnknownUrls[url] || queuedUnknownCandidates.length >= maxUnknownCandidates) return;
    queuedUnknownUrls[url] = true;
    queuedUnknownCandidates.push({ url: url, tagName: tagName || '' });
  }

  // turns the native opt-in into one bounded flush plus ongoing response inspection for this page
  window.__chayaScanMoreThoroughly = function () {
    thoroughScanEnabled = true;
    var candidates = queuedUnknownCandidates;
    queuedUnknownCandidates = [];
    queuedUnknownUrls = {};
    for (var i = 0; i < candidates.length; i++) {
      sendMediaCandidate(candidates[i].url, candidates[i].tagName);
    }
  }

  // observes fetch responses without replacing the caller's original promise
  if (window.fetch && !window.__chayaFetchHooked) {
    window.__chayaFetchHooked = true;
    var originalFetch = window.fetch;
    window.fetch = function (input, init) {
      var requestUrl = null;
      var shouldVerify = false;
      try {
        requestUrl = (input && input.url) ? input.url : String(input);
        if (looksLikeMedia(requestUrl)) {
          sendMedia(requestUrl, 'fetch', '');
        } else {
          var method = (init && init.method) || (input && input.method) || 'GET';
          shouldVerify = isSameOrigin(requestUrl) && String(method).toUpperCase() === 'GET';
        }
      } catch (e) {}

      var result = originalFetch.apply(this, arguments);
      if (shouldVerify && result && result.then) {
        result.then(function (response) {
          try {
            var responseUrl = response.url || requestUrl;
            if (isSameOrigin(responseUrl) &&
                isMediaContentType(response.headers && response.headers.get('Content-Type'))) {
              captureMediaCandidate(responseUrl, 'fetch');
            }
          } catch (e) {}
        }, function () {});
      }
      return result;
    };
  }

  // retains immediate extension detection and records unknown same-origin XHR response metadata
  if (window.XMLHttpRequest && !window.__chayaXhrHooked) {
    window.__chayaXhrHooked = true;
    var originalOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (method, url) {
      try {
        var requestUrl = absolute(url);
        this.__chayaRequestUrl = requestUrl;
        if (looksLikeMedia(requestUrl)) {
          sendMedia(requestUrl, 'xhr', '');
          this.__chayaVerifyCandidate = false;
        } else {
          this.__chayaVerifyCandidate = isSameOrigin(requestUrl) &&
            String(method).toUpperCase() === 'GET';
        }
      } catch (e) {}
      return originalOpen.apply(this, arguments);
    };

    // waits for XHR headers so unknown endpoints are verified only after browser evidence says media
    var originalSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function () {
      var xhr = this;
      if (xhr.__chayaVerifyCandidate) {
        var onLoadEnd = function () {
          xhr.removeEventListener('loadend', onLoadEnd);
          try {
            var responseUrl = xhr.responseURL || xhr.__chayaRequestUrl;
            if (xhr.status >= 200 && xhr.status < 300 &&
                isSameOrigin(responseUrl) &&
                isMediaContentType(xhr.getResponseHeader('Content-Type'))) {
              captureMediaCandidate(responseUrl, 'xhr');
            }
          } catch (e) {}
        };
        xhr.addEventListener('loadend', onLoadEnd);
      }
      return originalSend.apply(this, arguments);
    };
  }
})();
