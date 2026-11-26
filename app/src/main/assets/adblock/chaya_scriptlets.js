/*
 * Chaya's scriptlets: small scripts the ad lists ask to run at the start of a page (`example.com##+js(name, args)`),
 * before the page's own scripts. They turn off an ad's property, stop a pop-under's window.open, and take the ads out
 * of a video player's data before the player reads it. Chaya's own code, written for the behaviour each scriptlet's
 * name stands for in uBlock Origin's lists.
 *
 * Runs in every frame at document start. It asks ChayaScriptlets (the app) which scriptlets this document's address
 * gets, as [[name, arg, ...], ...], and runs each; one that fails leaves the others and the page alone.
 *
 * Also loaded by Node for its tests (app/src/test/js): there it only exports the scriptlets.
 */
(function (root) {
  'use strict';

  // ---- Shared helpers -------------------------------------------------------------------------------------------

  /** A pattern from a list: /regex/flags, else plain text to look for; empty matches everything. */
  function patternOf(text) {
    text = text == null ? '' : String(text);
    if (text === '' || text === '*') return /^/;
    var m = /^\/(.+)\/([gimsu]*)$/.exec(text);
    if (m) {
      try { return new RegExp(m[1], m[2].replace('g', '')); } catch (e) { return null; }
    }
    return new RegExp(text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
  }

  /** A pattern that may start with `!`: matching is then reversed. */
  function needleOf(text) {
    text = text == null ? '' : String(text);
    var not = text.charAt(0) === '!';
    var pattern = patternOf(not ? text.slice(1) : text);
    return {
      matches: function (s) {
        if (pattern === null) return false;
        var hit = pattern.test(String(s));
        return not ? !hit : hit;
      },
    };
  }

  var NOT_A_VALUE = {};
  function noop() {}

  /** The value a list names for set-constant: undefined, true, noopFunc, a small number... */
  function valueOf(text) {
    switch (text) {
      case 'undefined': return undefined;
      case 'null': return null;
      case 'true': return true;
      case 'false': return false;
      case 'noopFunc': return function () {};
      case 'trueFunc': return function () { return true; };
      case 'falseFunc': return function () { return false; };
      case 'throwFunc': return function () { throw new TypeError(); };
      case 'emptyArr': return [];
      case 'emptyObj': return {};
      case "''": case '""': case 'emptyStr': case '': return '';
      case 'noopPromiseResolve': return function () { return Promise.resolve(); };
      case 'noopPromiseReject': return function () { return Promise.reject(); };
      case 'yes': return 'yes';
      case 'no': return 'no';
    }
    if (/^-?\d+(\.\d+)?$/.test(text)) {
      var n = Number(text);
      if (Math.abs(n) <= 0x7fff) return n;
    }
    return NOT_A_VALUE;
  }

  function isObject(v) { return v !== null && (typeof v === 'object' || typeof v === 'function'); }

  /**
   * Puts [leaf] on the property at the end of [path] (a.b.c) under [owner], now and whenever a link of the path is
   * replaced later (`window.a = {...}` after this ran), so the page cannot step around it by assigning afresh.
   */
  function onPath(owner, path, leaf) {
    var dot = path.indexOf('.');
    if (dot < 0) { leaf(owner, path); return; }
    var prop = path.slice(0, dot);
    var rest = path.slice(dot + 1);
    var held;
    try { held = owner[prop]; } catch (e) { return; }
    if (isObject(held)) onPath(held, rest, leaf);
    var desc = Object.getOwnPropertyDescriptor(owner, prop);
    if (desc && !desc.configurable) return;
    // Another rule may already watch this link (two rules on ytInitialPlayerResponse): it keeps watching, through ours.
    var before = desc && (desc.get || desc.set) ? desc : null;
    try {
      Object.defineProperty(owner, prop, {
        configurable: true,
        enumerable: desc ? desc.enumerable : true,
        get: function () { return before && before.get ? before.get.call(this) : held; },
        set: function (v) {
          if (before && before.set) before.set.call(this, v);
          held = v;
          if (isObject(v)) onPath(v, rest, leaf);
        },
      });
    } catch (e) { /* not ours to change */ }
  }

  function abortError() { return new ReferenceError('Blocked by Chaya'); }

  /** The script running now, its text for an inline one, its address for one from a file. */
  function runningScriptText() {
    var s = typeof document !== 'undefined' ? document.currentScript : null;
    if (!s) return null;
    return s.src ? String(s.src) : String(s.textContent || '');
  }

  function fnText(fn) {
    try { return typeof fn === 'function' ? Function.prototype.toString.call(fn) : String(fn); } catch (e) { return ''; }
  }

  /** Runs [work] now and again whenever the page adds elements, until the page is long done. */
  function whilePageGrows(work) {
    if (typeof document === 'undefined') return;
    var run = function () { try { work(); } catch (e) {} };
    var observe = function () {
      run();
      if (typeof MutationObserver === 'undefined' || !document.documentElement) return;
      var timer = 0;
      var mo = new MutationObserver(function () {
        if (timer) return;
        timer = setTimeout(function () { timer = 0; run(); }, 50);
      });
      mo.observe(document.documentElement, { childList: true, subtree: true });
      setTimeout(function () { mo.disconnect(); }, 20000);
    };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', observe, { once: true });
    else observe();
  }

  /**
   * Deletes [paths] (a.b.c, `[]` for each item of an array, `*` for each key, `[-]` for the items of an array that
   * hold the rest of the path) from [obj], when every one of [needed] is there. Returns obj.
   */
  function prune(obj, paths, needed) {
    if (!isObject(obj)) return obj;
    if (needed.length && !needed.every(function (p) { return has(obj, p.split('.')); })) return obj;
    paths.forEach(function (p) { remove(obj, p.split('.')); });
    return obj;
  }

  function has(obj, parts) {
    if (!parts.length) return true;
    if (!isObject(obj)) return false;
    var head = parts[0], rest = parts.slice(1);
    if (head === '[]' || head === '*' || head === '[-]') {
      var keys = Array.isArray(obj) ? obj.map(function (_, i) { return i; }) : Object.keys(obj);
      return keys.some(function (k) { return has(obj[k], rest); });
    }
    return Object.prototype.hasOwnProperty.call(obj, head) && has(obj[head], rest);
  }

  function remove(obj, parts) {
    if (!isObject(obj) || !parts.length) return;
    var head = parts[0], rest = parts.slice(1);
    if (head === '[-]') {
      if (!Array.isArray(obj)) return;
      for (var i = obj.length - 1; i >= 0; i--) if (has(obj[i], rest)) obj.splice(i, 1);
      return;
    }
    if (head === '[]' || head === '*') {
      var keys = Array.isArray(obj) ? obj.map(function (_, i) { return i; }) : Object.keys(obj);
      keys.forEach(function (k) {
        if (rest.length) remove(obj[k], rest);
        else delete obj[k];
      });
      return;
    }
    if (!Object.prototype.hasOwnProperty.call(obj, head)) return;
    if (rest.length) remove(obj[head], rest);
    else delete obj[head];
  }

  function wordsOf(text) { return String(text || '').split(/\s+/).filter(Boolean); }

  /**
   * Which requests a rule means: `url:/pattern/ method:POST`, or bare text for the address. Every key named must
   * match. [request] has url, method and anything else the page gave.
   */
  function requestMatcher(text) {
    var wanted = [];
    var re = /(\w+):((?:\/(?:[^\/\\]|\\.)*\/[gimsu]*)|\S+)|(\S+)/g;
    var m;
    while ((m = re.exec(String(text || ''))) !== null) {
      if (m[3] !== undefined) wanted.push(['url', needleOf(m[3])]);
      else wanted.push([m[1], needleOf(m[2])]);
    }
    return function (request) {
      return wanted.every(function (w) {
        var v = request[w[0]];
        return v !== undefined && w[1].matches(v);
      });
    };
  }

  /** The `propsToMatch` value among a scriptlet's extra `name, value` arguments. */
  function extraArg(args, name) {
    for (var i = 0; i + 1 < args.length; i += 1) if (args[i] === name) return args[i + 1];
    return '';
  }

  function requestOf(input, init) {
    var url = '', method = 'GET';
    try {
      if (typeof input === 'string') url = input;
      else if (input && input.url) { url = input.url; method = input.method || method; }
      else url = String(input);
      if (init && init.method) method = init.method;
    } catch (e) {}
    return { url: url, method: String(method).toUpperCase() };
  }

  // ---- The scriptlets -------------------------------------------------------------------------------------------

  var S = {};

  /** set-constant: the property at [path] reads as [value], whatever the page assigns. */
  S['set-constant'] = function (path, raw) {
    if (!path) return;
    var value = valueOf(raw);
    if (value === NOT_A_VALUE) return;
    onPath(root, String(path), function (owner, prop) {
      var desc = Object.getOwnPropertyDescriptor(owner, prop);
      if (desc && !desc.configurable) return;
      try {
        Object.defineProperty(owner, prop, {
          configurable: true,
          enumerable: desc ? desc.enumerable : true,
          get: function () { return value; },
          set: noop,
        });
      } catch (e) {}
    });
  };

  /** abort-on-property-read: reading the property at [path] stops the script that reads it. */
  S['abort-on-property-read'] = function (path) {
    if (!path) return;
    onPath(root, String(path), function (owner, prop) {
      try {
        Object.defineProperty(owner, prop, {
          configurable: true,
          get: function () { throw abortError(); },
          set: noop,
        });
      } catch (e) {}
    });
  };

  /** abort-on-property-write: assigning the property at [path] stops the script that assigns it. */
  S['abort-on-property-write'] = function (path) {
    if (!path) return;
    onPath(root, String(path), function (owner, prop) {
      var held;
      try { held = owner[prop]; } catch (e) {}
      try {
        Object.defineProperty(owner, prop, {
          configurable: true,
          get: function () { return held; },
          set: function () { throw abortError(); },
        });
      } catch (e) {}
    });
  };

  /** abort-current-script: touching the property at [path] stops the running script when its text matches [needle]. */
  S['abort-current-script'] = function (path, needle) {
    if (!path) return;
    var wanted = needleOf(needle);
    onPath(root, String(path), function (owner, prop) {
      var desc = Object.getOwnPropertyDescriptor(owner, prop);
      if (desc && !desc.configurable) return;
      var held;
      try { held = owner[prop]; } catch (e) {}
      var check = function () {
        var text = runningScriptText();
        if (text !== null && wanted.matches(text)) throw abortError();
      };
      try {
        Object.defineProperty(owner, prop, {
          configurable: true,
          get: function () { check(); return desc && desc.get ? desc.get.call(this) : held; },
          set: function (v) { check(); if (desc && desc.set) desc.set.call(this, v); else held = v; },
        });
      } catch (e) {}
    });
  };

  /** addEventListener-defuser: listeners for [type] whose code matches [pattern] are never added. */
  S['addEventListener-defuser'] = function (type, pattern) {
    if (typeof EventTarget === 'undefined') return;
    var wantType = needleOf(type), wantCode = needleOf(pattern);
    var original = EventTarget.prototype.addEventListener;
    EventTarget.prototype.addEventListener = function (t, listener) {
      var code = listener && typeof listener === 'object' ? fnText(listener.handleEvent) : fnText(listener);
      if (wantType.matches(t) && wantCode.matches(code)) return undefined;
      return original.apply(this, arguments);
    };
  };

  function timerDefuser(name) {
    return function (pattern, delay) {
      var original = root[name];
      if (typeof original !== 'function') return;
      var wantCode = needleOf(pattern);
      var wantDelay = delay === undefined || delay === '' ? null : needleOf('/^' + String(delay).replace(/^!/, '') + '$/');
      var delayNot = String(delay || '').charAt(0) === '!';
      root[name] = function (handler, ms) {
        var delayHit = wantDelay === null || (wantDelay.matches(String(ms === undefined ? 0 : ms)) !== delayNot);
        if (delayHit && wantCode.matches(fnText(handler))) return original.call(this, noop, ms);
        return original.apply(this, arguments);
      };
    };
  }
  /** no-setTimeout-if: a timer whose code matches [pattern] (and whose delay is [delay]) does nothing. */
  S['no-setTimeout-if'] = timerDefuser('setTimeout');
  /** no-setInterval-if: the same for repeating timers. */
  S['no-setInterval-if'] = timerDefuser('setInterval');

  function timerBooster(name) {
    return function (pattern, delay, boost) {
      var original = root[name];
      if (typeof original !== 'function') return;
      var wantCode = needleOf(pattern);
      var wantDelay = delay === undefined || delay === '' || delay === '*' ? null : Number(delay || 1000);
      var factor = Number(boost);
      if (!(factor > 0 && factor < 50)) factor = 0.05;
      root[name] = function (handler, ms) {
        if (wantCode.matches(fnText(handler)) && (wantDelay === null || Number(ms) === wantDelay)) {
          var args = Array.prototype.slice.call(arguments);
          args[1] = Number(ms || 0) * factor;
          return original.apply(this, args);
        }
        return original.apply(this, arguments);
      };
    };
  }
  /** nano-setTimeout-booster: a countdown whose code matches runs [boost] times as long (fast by default). */
  S['nano-setTimeout-booster'] = timerBooster('setTimeout');
  S['nano-setInterval-booster'] = timerBooster('setInterval');

  /** no-window-open-if: window.open for an address matching [pattern] opens nothing (pop-ups, pop-unders). */
  S['no-window-open-if'] = function (pattern) {
    var original = root.open;
    if (typeof original !== 'function') return;
    var wanted = needleOf(pattern);
    root.open = function (url) {
      if (wanted.matches(url === undefined ? '' : String(url))) return null;
      return original.apply(this, arguments);
    };
  };

  /** json-prune: data the page reads with JSON.parse or Response.json loses [paths] (when [needed] are there). */
  S['json-prune'] = function (paths, needed) {
    var drop = wordsOf(paths), need = wordsOf(needed);
    if (!drop.length) return;
    var parse = JSON.parse;
    JSON.parse = function () {
      return prune(parse.apply(this, arguments), drop, need);
    };
    if (typeof Response !== 'undefined' && Response.prototype.json) {
      var json = Response.prototype.json;
      Response.prototype.json = function () {
        return json.apply(this, arguments).then(function (o) { return prune(o, drop, need); });
      };
    }
  };

  /** json-prune-fetch-response: answers to fetch() for requests matching propsToMatch lose [paths]. */
  S['json-prune-fetch-response'] = function (paths, needed) {
    var drop = wordsOf(paths), need = wordsOf(needed);
    if (!drop.length || typeof root.fetch !== 'function' || typeof Response === 'undefined') return;
    var matches = requestMatcher(extraArg(Array.prototype.slice.call(arguments, 2), 'propsToMatch'));
    var original = root.fetch;
    root.fetch = function (input, init) {
      var request = requestOf(input, init);
      var answer = original.apply(this, arguments);
      if (!matches(request)) return answer;
      return answer.then(function (response) {
        return response.clone().text().then(function (text) {
          var data;
          try { data = JSON.parse(text); } catch (e) { return response; }
          var pruned = new Response(JSON.stringify(prune(data, drop, need)), {
            status: response.status, statusText: response.statusText, headers: response.headers,
          });
          try {
            Object.defineProperty(pruned, 'url', { value: response.url });
            Object.defineProperty(pruned, 'type', { value: response.type });
          } catch (e) {}
          return pruned;
        }, function () { return response; });
      });
    };
  };

  /** json-prune-xhr-response: answers to XMLHttpRequest for requests matching propsToMatch lose [paths]. */
  S['json-prune-xhr-response'] = function (paths, needed) {
    var drop = wordsOf(paths), need = wordsOf(needed);
    var X = root.XMLHttpRequest;
    if (!drop.length || typeof X !== 'function') return;
    var matches = requestMatcher(extraArg(Array.prototype.slice.call(arguments, 2), 'propsToMatch'));
    var open = X.prototype.open;
    X.prototype.open = function (method, url) {
      var xhr = this;
      if (matches({ url: String(url), method: String(method).toUpperCase() })) {
        var cache = null;
        var pruned = function (raw) {
          if (cache !== null) return cache;
          var data = typeof raw === 'string' ? (function () { try { return JSON.parse(raw); } catch (e) { return null; } })() : raw;
          if (data === null) return raw;
          prune(data, drop, need);
          cache = typeof raw === 'string' ? JSON.stringify(data) : data;
          return cache;
        };
        var proto = X.prototype;
        ['response', 'responseText'].forEach(function (name) {
          var desc = Object.getOwnPropertyDescriptor(proto, name);
          if (!desc || !desc.get) return;
          try {
            Object.defineProperty(xhr, name, {
              configurable: true,
              get: function () {
                var raw = desc.get.call(xhr);
                if (xhr.readyState !== 4) return raw;
                if (name === 'response' && xhr.responseType !== '' && xhr.responseType !== 'text' && xhr.responseType !== 'json') return raw;
                return pruned(raw);
              },
            });
          } catch (e) {}
        });
      }
      return open.apply(this, arguments);
    };
  };

  /** no-fetch-if: fetch() for a request matching [props] gets an empty answer, without reaching the network. */
  S['no-fetch-if'] = function (props) {
    if (typeof root.fetch !== 'function' || typeof Response === 'undefined') return;
    var matches = requestMatcher(props);
    var original = root.fetch;
    root.fetch = function (input, init) {
      if (matches(requestOf(input, init))) return Promise.resolve(new Response('', { status: 200, statusText: 'OK' }));
      return original.apply(this, arguments);
    };
  };

  /** no-xhr-if: XMLHttpRequest for a request matching [props] gets an empty answer, without reaching the network. */
  S['no-xhr-if'] = function (props) {
    var X = root.XMLHttpRequest;
    if (typeof X !== 'function') return;
    var matches = requestMatcher(props);
    var open = X.prototype.open, send = X.prototype.send;
    var blocked = typeof WeakSet === 'function' ? new WeakSet() : null;
    X.prototype.open = function (method, url) {
      if (blocked && matches({ url: String(url), method: String(method).toUpperCase() })) blocked.add(this);
      return open.apply(this, arguments);
    };
    X.prototype.send = function () {
      if (!blocked || !blocked.has(this)) return send.apply(this, arguments);
      var xhr = this;
      var fake = { readyState: 4, status: 200, statusText: 'OK', response: '', responseText: '' };
      Object.keys(fake).forEach(function (k) {
        try { Object.defineProperty(xhr, k, { configurable: true, value: fake[k] }); } catch (e) {}
      });
      setTimeout(function () {
        ['readystatechange', 'load', 'loadend'].forEach(function (type) {
          try { xhr.dispatchEvent(new Event(type)); } catch (e) {}
        });
      }, 1);
      return undefined;
    };
  };

  /** remove-attr: the attributes in [attrs] (a|b) are taken off what [selector] finds, as the page grows. */
  S['remove-attr'] = function (attrs, selector) {
    var names = String(attrs || '').split('|').filter(Boolean);
    if (!names.length) return;
    var sel = selector || names.map(function (n) { return '[' + n + ']'; }).join(',');
    whilePageGrows(function () {
      var found = document.querySelectorAll(sel);
      for (var i = 0; i < found.length; i++) names.forEach(function (n) { found[i].removeAttribute(n); });
    });
  };

  /** remove-class: the classes in [classes] (a|b) are taken off what [selector] finds, as the page grows. */
  S['remove-class'] = function (classes, selector) {
    var names = String(classes || '').split('|').filter(Boolean);
    if (!names.length) return;
    var sel = selector || names.map(function (n) { return '.' + n; }).join(',');
    whilePageGrows(function () {
      var found = document.querySelectorAll(sel);
      for (var i = 0; i < found.length; i++) names.forEach(function (n) { found[i].classList.remove(n); });
    });
  };

  /** remove-node-text: elements named [nodeName] (script, #text...) whose text matches [pattern] are emptied as they arrive. */
  S['remove-node-text'] = function (nodeName, pattern) {
    if (typeof MutationObserver === 'undefined' || typeof document === 'undefined') return;
    var wantName = needleOf('/^' + String(nodeName || '').replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$/i');
    var wantText = needleOf(pattern);
    var check = function (node) {
      if (wantName.matches(node.nodeName) && wantText.matches(node.textContent || '')) node.textContent = '';
    };
    new MutationObserver(function (mutations) {
      for (var i = 0; i < mutations.length; i++) {
        var added = mutations[i].addedNodes;
        for (var j = 0; j < added.length; j++) check(added[j]);
      }
    }).observe(document, { childList: true, subtree: true });
  };

  /** noeval-if: eval() of code matching [pattern] does nothing (everything for plain noeval). */
  S['noeval-if'] = function (pattern) {
    var original = root.eval;
    if (typeof original !== 'function') return;
    var wanted = needleOf(pattern);
    root.eval = function (code) {
      if (wanted.matches(String(code))) return undefined;
      return original.apply(this, arguments);
    };
  };

  // ---- Start ----------------------------------------------------------------------------------------------------

  // Node, for the tests: no window. (A page may define a global `module` of its own, so that cannot tell.)
  if (typeof window === 'undefined' && typeof module !== 'undefined' && module.exports) {
    module.exports = { scriptlets: S, prune: prune, requestMatcher: requestMatcher, valueOf: valueOf };
    return;
  }
  if (root.__chayaScriptlets) return;
  try { Object.defineProperty(root, '__chayaScriptlets', { value: true }); } catch (e) { return; }
  var bridge = root.ChayaScriptlets;
  if (!bridge || typeof bridge.forPage !== 'function') return;
  var calls;
  try { calls = JSON.parse(bridge.forPage(String(root.location && root.location.href))); } catch (e) { return; }
  if (!Array.isArray(calls)) return;
  for (var i = 0; i < calls.length; i++) {
    var call = calls[i];
    if (!Array.isArray(call)) continue;
    var run = S[call[0]];
    if (typeof run !== 'function') continue;
    try { run.apply(null, call.slice(1)); } catch (e) { /* one rule failing leaves the rest */ }
  }
})(typeof window !== 'undefined' ? window : globalThis);
