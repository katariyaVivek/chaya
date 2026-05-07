(function () {
  if (window.__chayaInjected) return;
  window.__chayaInjected = true;

  var reported = {};

  function sendMedia(url, tagName) {
    if (!url || reported[url]) return;
    reported[url] = true;
    if (window.ChayaBridge && window.ChayaBridge.onMediaDetected) {
      window.ChayaBridge.onMediaDetected(url, tagName || "");
    }
  }

  function scan() {
    // VIDEO/AUDIO elements with a src attribute
    var mediaEls = document.querySelectorAll("video[src], audio[src]");
    for (var i = 0; i < mediaEls.length; i++) {
      sendMedia(mediaEls[i].currentSrc || mediaEls[i].src, mediaEls[i].tagName);
    }

    // VIDEO/AUDIO without src but with <source> children (e.g. video loaded via JS)
    var allMedia = document.querySelectorAll("video, audio");
    for (var i = 0; i < allMedia.length; i++) {
      if (!allMedia[i].src || allMedia[i].src === "") {
        var sources = allMedia[i].querySelectorAll("source");
        for (var j = 0; j < sources.length; j++) {
          if (sources[j].src) sendMedia(sources[j].src, "source");
        }
      }
    }

    // Standalone <source> elements not inside media parents
    var allSources = document.querySelectorAll("source[src]");
    for (var i = 0; i < allSources.length; i++) {
      var parent = allSources[i].parentElement;
      if (parent && parent.tagName !== "VIDEO" && parent.tagName !== "AUDIO") {
        sendMedia(allSources[i].src, "source");
      }
    }
  }

  scan();

  // Watch for dynamically added media
  var observer = new MutationObserver(function () {
    scan();
  });

  if (document.documentElement) {
    observer.observe(document.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ["src"],
    });
  }
})();
