package io.olcos.app.core

/**
 * How the MTS Link sheet and its offscreen replay view present themselves to
 * my.mts-link.ru: as a desktop browser. One user agent for both (the site must
 * see the same client in each), the script that hides the touch screen from
 * the page, and the two decisions the sheet's web client makes about what the
 * site asks for. Pure: the web view wiring is in WebScreens.kt and
 * MtsLinkReplay.kt. Kotlin raw strings interpolate a dollar sign, so the
 * script uses none.
 */
object MtsLinkDesktop {
    /** Desktop Chrome on Windows; kept in this one place (the VK / WB sheets keep RoomServices.BROWSER_UA). */
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

    /**
     * Hides the touch screen from the page: `navigator.maxTouchPoints` is 0,
     * `navigator.platform` is "Win32", the `ontouchstart` / `ontouchmove` /
     * `ontouchend` handlers are removed from the window, `Window.prototype`,
     * `Document.prototype` and `HTMLElement.prototype` where they can be (so
     * `'ontouchstart' in window` is false), and `matchMedia` answers the
     * pointer / hover queries as a mouse would: coarse and `hover: none` false,
     * fine and `hover: hover` true. Only a query that is exactly one of those
     * (with an optional leading `screen and`) is answered; any other goes to
     * the real `matchMedia`. Idempotent per document; every step is in its own
     * try/catch, so nothing here can break the page.
     */
    const val SHIM = """
(function() {
  try {
    if (window.__olcosDesktop) return;
    window.__olcosDesktop = true;
  } catch (e) { return; }
  var fixed = function(proto, name, value) {
    try { Object.defineProperty(proto, name, {get: function() { return value; }, set: undefined, enumerable: true, configurable: true}); } catch (e) {}
  };
  try { fixed(Navigator.prototype, 'maxTouchPoints', 0); } catch (e) {}
  try { fixed(Navigator.prototype, 'platform', 'Win32'); } catch (e) {}
  ['ontouchstart', 'ontouchmove', 'ontouchend'].forEach(function(name) {
    var targets = [];
    try { targets.push(window); } catch (e) {}
    try { targets.push(Window.prototype); } catch (e) {}
    try { targets.push(Document.prototype); } catch (e) {}
    try { targets.push(HTMLElement.prototype); } catch (e) {}
    targets.forEach(function(target) {
      try {
        if (!Object.prototype.hasOwnProperty.call(target, name)) return;
        var d = Object.getOwnPropertyDescriptor(target, name);
        if (d && d.configurable) delete target[name];
      } catch (e) {}
    });
  });
  try {
    var real = window.matchMedia;
    if (typeof real === 'function') {
      var answers = {
        '(pointer:coarse)': false, '(any-pointer:coarse)': false, '(hover:none)': false, '(any-hover:none)': false,
        '(pointer:fine)': true, '(any-pointer:fine)': true, '(hover:hover)': true, '(any-hover:hover)': true
      };
      window.matchMedia = function matchMedia(query) {
        try {
          var key = String(query).toLowerCase().replace(/\s+/g, '').replace(/^(?:onlyscreenand|screenand)/, '');
          if (Object.prototype.hasOwnProperty.call(answers, key)) {
            var list = real.call(window, answers[key] ? 'all' : 'not all');
            var text = String(query);
            try { Object.defineProperty(list, 'media', {get: function() { return text; }, configurable: true}); } catch (e) {}
            return list;
          }
        } catch (e) {}
        return real.apply(window, arguments);
      };
    }
  } catch (e) {}
  try {
    var scalable = function(content) {
      var kept = String(content || '').split(/[,;]/).map(function(part) { return part.trim(); }).filter(function(part) {
        var key = part.split('=')[0].trim().toLowerCase();
        return part !== '' && key !== 'user-scalable' && key !== 'maximum-scale' && key !== 'minimum-scale';
      });
      return kept.concat(['user-scalable=yes', 'maximum-scale=10']).join(', ');
    };
    var unlock = function() {
      try {
        var metas = document.querySelectorAll('meta[name=viewport]');
        for (var i = 0; i < metas.length; i++) {
          var wanted = scalable(metas[i].getAttribute('content'));
          if (metas[i].getAttribute('content') !== wanted) metas[i].setAttribute('content', wanted);
        }
      } catch (e) {}
    };
    unlock();
    new MutationObserver(unlock).observe(document.documentElement || document, {childList: true, subtree: true, attributes: true, attributeFilter: ['content']});
  } catch (e) {}
})();
"""

    /**
     * PURE: a page viewport's content with its scaling locks taken out
     * (`user-scalable`, `maximum-scale`, `minimum-scale`) and pinching
     * allowed up to 10×; the width and the initial scale stay, so the page
     * keeps its layout. [SHIM] does the same in the page.
     */
    fun scalableViewport(content: String): String =
        (content.split(',', ';').map { it.trim() }.filter { part ->
            val key = part.substringBefore('=').trim().lowercase()
            part.isNotEmpty() && key != "user-scalable" && key != "maximum-scale" && key != "minimum-scale"
        } + listOf("user-scalable=yes", "maximum-scale=10")).joinToString(", ")

    /** What the sheet's address line shows: the host the page is on, and whether it came over https. */
    data class Address(val host: String, val secure: Boolean)

    /** PURE: the address line for [url]; null for a page with no web host (`about:blank`). */
    fun address(url: String): Address? {
        val text = url.trim()
        val scheme = schemeOf(text) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = hostOf(text).ifEmpty { return null }
        return Address(host, secure = scheme == "https")
    }

    /** Schemes a page uses inside itself; these are not "links the web view cannot open". */
    private val inPageSchemes = setOf("http", "https", "about", "blob", "data", "javascript")

    /**
     * PURE: why the sheet must not follow [url], or null when it may. A scheme
     * other than http(s) (`mtslink:`, `intent:`, `itms-apps:`, `tel:`, ...)
     * answers with the scheme; an http(s) link to an app store answers
     * "app-store". The in-page schemes (`about:`, `blob:`, `data:`,
     * `javascript:`) are not blocked.
     */
    fun blockedLinkKind(url: String): String? {
        val text = url.trim()
        val scheme = schemeOf(text) ?: return null
        if (scheme !in inPageSchemes) return scheme
        if (scheme != "http" && scheme != "https") return null
        val host = hostOf(text)
        val path = text.substringAfter("://", "").let { rest ->
            val slash = rest.indexOf('/')
            if (slash < 0) "" else rest.substring(slash).substringBefore('?').substringBefore('#')
        }
        return if (isStore(host, path)) "app-store" else null
    }

    /** PURE: [url] as the log keeps it: scheme, host and path, never the query or the fragment; a non-web scheme is the scheme alone. */
    fun logTarget(url: String): String {
        val text = url.trim()
        val scheme = schemeOf(text) ?: return text.substringBefore('?').substringBefore('#').take(200)
        if (scheme != "http" && scheme != "https") return "$scheme:"
        return text.substringBefore('?').substringBefore('#').take(300)
    }

    private fun schemeOf(text: String): String? {
        val colon = text.indexOf(':')
        if (colon <= 0) return null
        val head = text.substring(0, colon)
        if (!head[0].isLetter() || !head.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
        return head.lowercase()
    }

    private fun hostOf(text: String): String {
        val rest = text.substringAfter("://", "")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        return authority.substringAfterLast('@').substringBefore(':').lowercase()
    }

    private fun isStore(host: String, path: String): Boolean = when {
        host == "play.google.com" -> path.startsWith("/store")
        host == "market.android.com" || host == "apps.apple.com" || host == "itunes.apple.com" -> true
        host == "appgallery.huawei.com" || host == "galaxystore.samsung.com" || host == "apps.rustore.ru" -> true
        else -> false
    }
}
