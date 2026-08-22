(function () {
  if (window.__chayaInjected) return;
  window.__chayaInjected = true;

  var reported = {};
  var scanTimer = null;

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
    reported[url] = true;
    if (window.ChayaBridge && window.ChayaBridge.onMediaDetectedWithType) {
      window.ChayaBridge.onMediaDetectedWithType(url, tagName || '', typeAttr || '');
    } else if (window.ChayaBridge && window.ChayaBridge.onMediaDetected) {
      window.ChayaBridge.onMediaDetected(url, tagName || '');
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

  deepScan();

  // Watch for dynamically added media / attribute changes
  if (document.documentElement && window.MutationObserver) {
    new MutationObserver(scheduleScan).observe(document.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['src']
    });
  }

  // ---- fetch/XHR sniffing (catches media URLs the DOM never shows) ----

  if (window.fetch && !window.__chayaFetchHooked) {
    window.__chayaFetchHooked = true;
    var originalFetch = window.fetch;
    window.fetch = function (input) {
      try {
        var u = (input && input.url) ? input.url : String(input);
        if (looksLikeMedia(u)) sendMedia(u, 'fetch', '');
      } catch (e) {}
      return originalFetch.apply(this, arguments);
    };
  }

  if (window.XMLHttpRequest && !window.__chayaXhrHooked) {
    window.__chayaXhrHooked = true;
    var originalOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (method, url) {
      try {
        if (looksLikeMedia(url)) sendMedia(url, 'xhr', '');
      } catch (e) {}
      return originalOpen.apply(this, arguments);
    };
  }
})();
