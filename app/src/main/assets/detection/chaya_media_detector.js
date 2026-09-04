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

  function sendMedia(rawUrl, tagName, typeAttr) {
    if (!rawUrl) return;
    var url = absolute(rawUrl);
    if (!isHttp(url)) return;              // skip blob:, data:, about:, ...
    if (reported[url]) return;
    if (postMedia(url, tagName || '', typeAttr || '')) {
      reported[url] = true;
    }
  }

  function scanRoot(root) {
    if (!root) return;

    // video/audio elements with a resolved source
    var mediaEls = root.querySelectorAll('video, audio');
    for (var i = 0; i < mediaEls.length; i++) {
      var el = mediaEls[i];
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
