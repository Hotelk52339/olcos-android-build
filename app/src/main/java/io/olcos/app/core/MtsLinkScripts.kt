package io.olcos.app.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The scripts the app runs inside a my.mts-link.ru page. Kotlin raw strings
 * interpolate a dollar sign, so none of them uses one.
 */
object MtsLinkScripts {
    /**
     * The page hook, put into every document of the sign-in sheet (and of the
     * offscreen page a replay uses). Idempotent: running it again in the same
     * document does nothing.
     *
     * It wraps `fetch` and `XMLHttpRequest` and reports each call twice over
     * the `olcosCapture` bridge: `request(id, method, url, headers, body, copy)`
     * when it is made (the first five are what the sheet shows, and are scrubbed
     * natively; `copy` is a second, unscrubbed JSON copy for the template with
     * cookie / storage values already written as markers) and `response(id, status, text)` when it is answered (the first
     * 16 KB of the answer, read from a clone). A credential header's value is
     * replaced IN the page by where it comes from (`@cookie(NAME)`,
     * `@local(KEY)`, `@session(KEY)`, else `@seen(header-name)`), so only names
     * leave it. Also reported: every change of the page's address (`nav`,
     * polled twice a second) and, every 1.5 s, the meeting links visible in
     * the page (`links`). In the page's memory alone, `window.__olcosSeen`
     * keeps the latest value of each credential header the page itself sent,
     * by lowercase header name, for the replay.
     */
    const val HOOK = """
(function() {
  if (window.__olcosCaptureHooked) return;
  window.__olcosCaptureHooked = true;
  window.__olcosSeen = window.__olcosSeen || {};
  var bridge = function() { try { return window.olcosCapture || null; } catch (e) { return null; } };
  var nextId = 1;
  var CRED = /authorization|token|csrf|xsrf|auth|session|secret|api-?key/i;
  var SAFE = /^[A-Za-z0-9_.:-]{1,100}${'$'}/;
  var absolute = function(u) { try { return new URL(String(u), location.href).href; } catch (e) { return String(u || ''); } };
  var cookieMap = function() {
    var out = {};
    try {
      document.cookie.split(';').forEach(function(p) {
        var i = p.indexOf('=');
        if (i > 0) out[p.slice(0, i).trim()] = p.slice(i + 1).trim();
      });
    } catch (e) {}
    return out;
  };
  var same = function(a, b) { return a === b; };
  var provenance = function(name, value) {
    var v = String(value || '');
    var m = /^([A-Za-z]{1,12} )(.+)${'$'}/.exec(v);
    var variants = [['', v]];
    if (m) variants.push([m[1], m[2]]);
    for (var i = 0; i < variants.length; i++) {
      var prefix = variants[i][0], rest = variants[i][1];
      if (!rest) continue;
      var cookies = cookieMap();
      for (var c in cookies) if (SAFE.test(c) && same(cookies[c], rest)) return prefix + '@cookie(' + c + ')';
      try {
        for (var j = 0; j < localStorage.length; j++) {
          var lk = localStorage.key(j);
          if (SAFE.test(lk) && localStorage.getItem(lk) === rest) return prefix + '@local(' + lk + ')';
        }
      } catch (e) {}
      try {
        for (var k = 0; k < sessionStorage.length; k++) {
          var sk = sessionStorage.key(k);
          if (SAFE.test(sk) && sessionStorage.getItem(sk) === rest) return prefix + '@session(' + sk + ')';
        }
      } catch (e) {}
    }
    var n = String(name).toLowerCase();
    return '@seen(' + (SAFE.test(n) ? n : 'x') + ')';
  };
  var pairs = function(h) {
    var out = [];
    try {
      if (!h) return out;
      if (typeof h.forEach === 'function' && !Array.isArray(h)) { h.forEach(function(v, k) { out.push([String(k), String(v)]); }); return out; }
      if (Array.isArray(h)) { h.forEach(function(p) { if (p && p.length >= 2) out.push([String(p[0]), String(p[1])]); }); return out; }
      if (typeof h === 'object') { for (var k in h) if (Object.prototype.hasOwnProperty.call(h, k)) out.push([String(k), String(h[k])]); }
    } catch (e) {}
    return out;
  };
  var headerText = function(h) {
    var lines = [];
    pairs(h).forEach(function(p) {
      var name = p[0], value = p[1].replace(/[\r\n]+/g, ' ');
      if (CRED.test(name)) {
        window.__olcosSeen[name.toLowerCase()] = value;
        value = provenance(name, value);
      }
      lines.push(name + ': ' + value);
    });
    return lines.join('\n');
  };
  var bodyText = function(b) {
    try {
      if (b == null) return '';
      if (typeof b === 'string') return b;
      if (typeof URLSearchParams !== 'undefined' && b instanceof URLSearchParams) return b.toString();
      if (typeof FormData !== 'undefined' && b instanceof FormData) {
        var parts = [];
        b.forEach(function(v, k) { parts.push(k + '=' + (typeof v === 'string' ? v : '<file>')); });
        return parts.join('&');
      }
      if (typeof Blob !== 'undefined' && b instanceof Blob) return '<' + (b.type || 'binary') + ' ' + (b.size || 0) + '>';
      if (typeof b === 'object') return JSON.stringify(b);
      return String(b);
    } catch (e) {}
    return '';
  };
  // The second copy, for the template: the request as the page sent it, with the values that equal a cookie or a
  // storage item written as @cookie(NAME) / @local(KEY) / @session(KEY). Nothing else is changed in it.
  var LIMIT = 262144;
  var sourceValues = function() {
    var out = [];
    try { var c = cookieMap(); for (var k in c) if (SAFE.test(k) && c[k].length >= 8 && c[k].length <= 4096) out.push({kind: 'cookie', key: k, val: c[k]}); } catch (e) {}
    try {
      for (var i = 0; i < localStorage.length; i++) {
        var lk = localStorage.key(i), lv = localStorage.getItem(lk);
        if (lk && SAFE.test(lk) && lv && lv.length >= 8 && lv.length <= 4096) out.push({kind: 'local', key: lk, val: lv});
      }
    } catch (e) {}
    try {
      for (var j = 0; j < sessionStorage.length; j++) {
        var sk = sessionStorage.key(j), sv = sessionStorage.getItem(sk);
        if (sk && SAFE.test(sk) && sv && sv.length >= 8 && sv.length <= 4096) out.push({kind: 'session', key: sk, val: sv});
      }
    } catch (e) {}
    out.sort(function(a, b) { return b.val.length - a.val.length; });
    return out;
  };
  var markOf = function(s) { return '@' + s.kind + '(' + s.key + ')'; };
  var markString = function(text, srcs) {
    var t = String(text);
    for (var i = 0; i < srcs.length; i++) if (t === srcs[i].val) return markOf(srcs[i]);
    for (var j = 0; j < srcs.length; j++) {
      if (srcs[j].val.length >= 16 && t.indexOf(srcs[j].val) >= 0) t = t.split(srcs[j].val).join(markOf(srcs[j]));
    }
    return t;
  };
  var markJsonText = function(text, srcs) {
    var t = String(text);
    srcs.forEach(function(s) {
      var esc = JSON.stringify(s.val).slice(1, -1);
      if (t.indexOf('"' + esc + '"') >= 0) t = t.split('"' + esc + '"').join('"' + markOf(s) + '"');
      if (s.val.length >= 16 && t.indexOf(esc) >= 0) t = t.split(esc).join(markOf(s));
    });
    return t;
  };
  var markUrl = function(url, srcs) {
    var parts = String(url).split(/([\/?&=#])/);
    for (var i = 0; i < parts.length; i += 2) {
      var tok = parts[i];
      if (tok.length < 8) continue;
      var dec = tok;
      try { dec = decodeURIComponent(tok); } catch (e) {}
      for (var j = 0; j < srcs.length; j++) if (srcs[j].val === dec || srcs[j].val === tok) { parts[i] = markOf(srcs[j]); break; }
    }
    var out = parts.join('');
    srcs.forEach(function(s) {
      if (s.val.length < 16) return;
      [encodeURIComponent(s.val), s.val].forEach(function(v) { if (out.indexOf(v) >= 0) out = out.split(v).join(markOf(s)); });
    });
    return out;
  };
  var bodyCopy = function(b, srcs) {
    try {
      if (b == null) return {kind: 'none'};
      if (typeof b === 'string') {
        if (b.length > LIMIT) return {kind: 'unsupported', why: 'a body of more than 256 KB'};
        var isJson = false;
        try { JSON.parse(b); isJson = true; } catch (e) {}
        return {kind: 'text', json: isJson, text: isJson ? markJsonText(b, srcs) : markString(b, srcs)};
      }
      if (typeof URLSearchParams !== 'undefined' && b instanceof URLSearchParams) {
        var f = [];
        b.forEach(function(v, k) { f.push([k, markString(v, srcs)]); });
        return {kind: 'form', fields: f};
      }
      if (typeof FormData !== 'undefined' && b instanceof FormData) {
        var m = [], bad = '';
        b.forEach(function(v, k) { if (typeof v === 'string') m.push([k, markString(v, srcs)]); else bad = k; });
        if (bad) return {kind: 'unsupported', why: 'a file part (' + bad + ') in a multipart form'};
        return {kind: 'multipart', fields: m};
      }
      if (typeof Blob !== 'undefined' && b instanceof Blob) return {kind: 'unsupported', why: 'a Blob body'};
      if (b instanceof ArrayBuffer || (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView(b))) return {kind: 'unsupported', why: 'a binary (ArrayBuffer) body'};
    } catch (e) {}
    return {kind: 'unsupported', why: 'a body of an unsupported kind'};
  };
  var requestCopy = function(url, headers, body) {
    try {
      var srcs = sourceValues();
      var hs = [];
      pairs(headers).forEach(function(p) {
        var value = p[1].replace(/[\r\n]+/g, ' ');
        hs.push([p[0], CRED.test(p[0]) ? provenance(p[0], value) : markString(value, srcs)]);
      });
      var bc = bodyCopy(body, srcs);
      bc.url = markUrl(url, srcs);
      bc.headers = hs;
      return JSON.stringify(bc);
    } catch (e) { return ''; }
  };
  var reportRequest = function(id, method, url, headers, body, copy) {
    try { var b = bridge(); if (b) b.request(id, String(method || 'GET'), String(url || ''), String(headers || ''), String(body || ''), String(copy || '')); } catch (e) {}
  };
  var reportResponse = function(id, status, text) {
    try { var b = bridge(); if (b) b.response(id, Number(status) || 0, String(text || '').slice(0, 16384)); } catch (e) {}
  };

  var of = window.fetch;
  if (of) {
    window.fetch = function(input, init) {
      var id = 0;
      try {
        id = nextId++;
        var isReq = (typeof Request !== 'undefined' && input instanceof Request);
        var url = absolute(isReq ? input.url : input);
        var method = (init && init.method) || (isReq && input.method) || 'GET';
        var headers = (init && init.headers) || (isReq && input.headers) || null;
        var body = (init && init.body != null) ? init.body : '';
        reportRequest(id, method, url, headerText(headers), bodyText(body), requestCopy(url, headers, (init && init.body != null) ? init.body : null));
      } catch (e) {}
      var p = of.apply(this, arguments);
      try {
        if (id) {
          p.then(function(res) {
            try { res.clone().text().then(function(t) { reportResponse(id, res.status, t); }, function() { reportResponse(id, res.status, ''); }); }
            catch (e) { reportResponse(id, res.status, ''); }
          }, function() { reportResponse(id, 0, ''); });
        }
      } catch (e) {}
      return p;
    };
  }

  var origOpen = XMLHttpRequest.prototype.open;
  var origSend = XMLHttpRequest.prototype.send;
  var origSet = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.open = function(method, url) {
    try { this.__olcMethod = method; this.__olcUrl = absolute(url); this.__olcHeaders = []; } catch (e) {}
    return origOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
    try { if (this.__olcHeaders) this.__olcHeaders.push([String(name), String(value)]); } catch (e) {}
    return origSet.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function(body) {
    try {
      var self = this;
      var id = nextId++;
      reportRequest(id, self.__olcMethod || 'GET', self.__olcUrl || '', headerText(self.__olcHeaders || []), bodyText(body),
        requestCopy(self.__olcUrl || '', self.__olcHeaders || [], body == null ? null : body));
      self.addEventListener('loadend', function() {
        var t = '';
        try {
          if (self.responseType === '' || self.responseType === 'text') t = self.responseText;
          else if (self.responseType === 'json') t = JSON.stringify(self.response);
        } catch (e) {}
        reportResponse(id, self.status, t);
      });
    } catch (e) {}
    return origSend.apply(this, arguments);
  };

  if (bridge()) {
    var lastHref = '';
    var checkNav = function() {
      try {
        if (location.href !== lastHref) { lastHref = location.href; var b = bridge(); if (b) b.nav(lastHref); }
      } catch (e) {}
    };
    ['pushState', 'replaceState'].forEach(function(fn) {
      var orig = history[fn];
      if (orig) history[fn] = function() { var r = orig.apply(this, arguments); checkNav(); return r; };
    });
    window.addEventListener('popstate', checkNav);
    window.addEventListener('hashchange', checkNav);
    setInterval(checkNav, 500);
    checkNav();

    var lastLinks = '';
    var linkRe = /(?:https?:\/\/)?(?:my\.)?mts-link\.ru\/j\/[A-Za-z0-9_.-]{1,64}\/[0-9]{1,20}[^\s"'<>]{0,200}/gi;
    var scanLinks = function() {
      try {
        var found = {};
        var add = function(s) { var m = String(s || '').match(linkRe); if (m) m.forEach(function(x) { found[x] = 1; }); };
        document.querySelectorAll('a[href]').forEach(function(a) { add(a.href); });
        document.querySelectorAll('input,textarea').forEach(function(e) { add(e.value); });
        add(document.body ? document.body.innerText : '');
        var list = Object.keys(found).slice(0, 20).join('\n');
        if (list && list !== lastLinks) { lastLinks = list; var b = bridge(); if (b) b.links(list); }
      } catch (e) {}
    };
    setInterval(scanLinks, 1500);
    scanLinks();
  }
})();
"""

    private fun fieldsJson(fields: List<Pair<String, String>>) = buildJsonArray {
        fields.forEach { (k, v) -> add(buildJsonArray { add(JsonPrimitive(k)); add(JsonPrimitive(v)) }) }
    }

    /**
     * PURE: the script that hands native what the template's credential
     * headers need from the page: the cookies, storage keys and seen headers
     * it names (only those), and the page's address. It returns one JSON
     * string; [MtsLearn.parseCollected] reads it.
     */
    fun collectScript(needs: MtsLearn.Needs): String {
        val spec = buildJsonObject {
            put("cookies", buildJsonArray { needs.cookies.forEach { add(JsonPrimitive(it)) } })
            put("local", buildJsonArray { needs.local.forEach { add(JsonPrimitive(it)) } })
            put("session", buildJsonArray { needs.session.forEach { add(JsonPrimitive(it)) } })
            put("seen", buildJsonArray { needs.seen.forEach { add(JsonPrimitive(it.lowercase())) } })
        }.toString()
        return "(function(){var n=" + spec + ";var out={cookies:{},local:{},session:{},seen:{},href:String(location.href)};" +
            "try{document.cookie.split(';').forEach(function(p){var i=p.indexOf('=');if(i>0){var k=p.slice(0,i).trim();" +
            "if(n.cookies.indexOf(k)>=0)out.cookies[k]=p.slice(i+1).trim();}});}catch(e){}" +
            "n.local.forEach(function(k){try{var v=localStorage.getItem(k);if(v!==null)out.local[k]=v;}catch(e){}});" +
            "n.session.forEach(function(k){try{var v=sessionStorage.getItem(k);if(v!==null)out.session[k]=v;}catch(e){}});" +
            "n.seen.forEach(function(k){try{var v=(window.__olcosSeen||{})[k];if(v)out.seen[k]=v;}catch(e){}});" +
            "return JSON.stringify(out);})()"
    }

    /**
     * PURE: the script that sends the template's request from inside the
     * page, with the headers already rebuilt, cookies included
     * (`credentials: 'include'`), and hands the answer to the
     * `olcosReplay` bridge: `done(status, text)` (the first 64 KB) or
     * `failed(message)`. The body is built in the page as the SAME kind the
     * page sent: the string (its content type is among [headers] when the page
     * set one), a `URLSearchParams`, or a `FormData` with `append`, so the
     * browser sets the content type and the multipart boundary itself.
     */
    fun fetchScript(method: String, url: String, headers: List<Pair<String, String>>, body: MtsRequest.Body): String {
        val spec = buildJsonObject {
            put("method", method)
            put("url", url)
            when (body) {
                MtsRequest.Body.None -> put("kind", "none")
                is MtsRequest.Body.Text -> { put("kind", "text"); put("text", body.text) }
                is MtsRequest.Body.Form -> { put("kind", "form"); put("fields", fieldsJson(body.fields)) }
                is MtsRequest.Body.Multipart -> { put("kind", "multipart"); put("fields", fieldsJson(body.fields)) }
            }
            put("headers", buildJsonArray { headers.forEach { (k, v) -> add(buildJsonArray { add(JsonPrimitive(k)); add(JsonPrimitive(v)) }) } })
        }.toString()
        return "(function(){var s=" + spec + ";var h={};s.headers.forEach(function(p){h[p[0]]=p[1];});" +
            "var o={method:s.method,headers:h,credentials:'include'};" +
            "if(s.kind==='text')o.body=s.text;" +
            "else if(s.kind==='form')o.body=new URLSearchParams(s.fields);" +
            "else if(s.kind==='multipart'){var fd=new FormData();s.fields.forEach(function(p){fd.append(p[0],p[1]);});o.body=fd;}" +
            "fetch(s.url,o).then(function(r){return r.text().then(function(t){window.olcosReplay.done(r.status,String(t).slice(0,65536));});})" +
            ".catch(function(e){window.olcosReplay.failed(String((e&&e.message)||e));});})();"
    }
}
