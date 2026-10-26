// Chaya for Windows' ad hider, as the phone's page script does it (AdBlocker.kt's CosmeticScript). It runs at
// the start of the page's top document and does nothing until the app first asks: then it notes the classes and
// ids the document holds, and keeps noting those added later.
//
// It hands the page nothing: there is no bridge into the app. The app calls window[KEY]('names') for what is new,
// works out which of the lists' rules apply, and calls window[KEY]('hide', doc, css) with them. KEY is a new random
// word for every page the app opens; doc names this document, so CSS worked out for one document never lands in
// the next.
(function () {
  var KEY = '__CHAYA_HIDE_KEY__';
  if (Object.prototype.hasOwnProperty.call(window, KEY)) return;

  // Taken before the page's own scripts run, so the page cannot change what they do for this script.
  var stringify = JSON.stringify;
  var DOC = Math.random().toString(36).slice(2) + Date.now().toString(36);
  var MAX_SEEN = 50000;
  var MAX_ASK = 4000;

  var seen = Object.create(null);
  var seenCount = 0;
  var pending = [];
  var watching = false;

  function note(name) {
    if (seen[name] || seenCount >= MAX_SEEN) return;
    seen[name] = 1;
    seenCount++;
    pending.push(name);
  }

  function noteElement(el) {
    if (typeof el.id === 'string' && el.id) note('#' + el.id);
    var list = el.classList;
    if (list) for (var i = 0; i < list.length; i++) note('.' + list[i]);
  }

  function scan(root) {
    noteElement(root);
    if (!root.querySelectorAll) return;
    var found = root.querySelectorAll('[id],[class]');
    for (var i = 0; i < found.length; i++) noteElement(found[i]);
  }

  function watch() {
    if (watching || !document.documentElement || !window.MutationObserver) return;
    watching = true;
    scan(document.documentElement);
    new MutationObserver(function (mutations) {
      for (var i = 0; i < mutations.length; i++) {
        var added = mutations[i].addedNodes;
        for (var j = 0; j < added.length; j++) if (added[j].nodeType === 1) scan(added[j]);
      }
    }).observe(document.documentElement, { childList: true, subtree: true });
  }

  function hide(css) {
    if (!document.documentElement) return;
    var style = document.createElement('style');
    style.textContent = css;
    (document.head || document.documentElement).appendChild(style);
  }

  Object.defineProperty(window, KEY, {
    enumerable: false,
    writable: false,
    configurable: false,
    value: function (action, doc, css) {
      if (action === 'names') {
        watch();
        return stringify({ doc: DOC, url: location.href, names: pending.splice(0, MAX_ASK) });
      }
      if (action === 'hide' && doc === DOC && typeof css === 'string') hide(css);
      return '';
    }
  });
})();
