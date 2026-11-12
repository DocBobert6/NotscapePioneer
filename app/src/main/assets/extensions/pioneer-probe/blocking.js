/* Pioneer Probe — Phase 3 cosmetic content script (`blocking.js`).
 *
 * Runs at `document_idle` on every page (same `<all_urls>` match
 * as `probe.js`). One job: ask the app for this host's cosmetic
 * CSS (`cosmetic-query`, mirroring the `zoom-query` pattern) and
 * inject it as the same `__ns_cos` style element Mariner used.
 *
 * `$generichide` host check (design §8.1): the reply carries the
 * `$generichide` host set (`hideOff`) and this script walks
 * `location.hostname` up its parent domains against it. When the
 * walk matches, generic hiding is off on this page: only the
 * precise half (domain + user selectors) is injected, never the
 * generic half. The app already excludes generic selectors from
 * `genericCss` on such hosts, so the two layers agree — this
 * walk is the enforcement point the design names, the app-side
 * split is the belt. Domain and user selectors are NEVER skipped
 * by `$generichide`; only generic hiding is suppressed.
 *
 * Known consequence (design §10 gap 2): injection happens at
 * `document_idle`, not document start — ads may flash before the
 * sheet applies. Mariner injected at document start via
 * `evaluateJavascript`, which does not exist in GeckoView.
 */
(function () {
  "use strict";

  var STYLE_ID = "__ns_cos";

  /* Walk `hostname` up its parent domains, longest first:
   * `a.b.example.com` → `a.b.example.com`, `b.example.com`,
   * `example.com` — the same parent-walking Mariner's matcher
   * uses, so a `$generichide` page scope covers its subdomains. */
  function isGenerichideHost(hostname, hideOff) {
    var h = String(hostname || "").toLowerCase();
    if (!h || !hideOff) return false;
    while (true) {
      if (hideOff.indexOf(h) >= 0) return true;
      var d = h.indexOf(".");
      if (d < 0) return false;
      h = h.substring(d + 1);
      if (h.indexOf(".") < 0) return hideOff.indexOf(h) >= 0;
    }
  }

  function inject(css) {
    if (!css) return;
    try {
      var old = document.getElementById(STYLE_ID);
      if (old && old.parentNode) old.parentNode.removeChild(old);
      var el = document.createElement("style");
      el.id = STYLE_ID;
      el.textContent = css;
      (document.documentElement || document).appendChild(el);
    } catch (e) { /* page not ready — a later load re-asks */ }
  }

  function queryCosmetic() {
    try {
      if (typeof browser === "undefined" || !browser.runtime ||
          !browser.runtime.sendNativeMessage) return;
      browser.runtime.sendNativeMessage("pioneer", {
        type: "cosmetic-query",
        host: location.hostname
      }).then(function (reply) {
        if (!reply || reply.type !== "cosmetic") return;
        /* Prefer the split fields: precise always, generic only
         * when this host is not $generichide-off. Fall back to
         * the combined `css` for an older app reply. */
        var precise = reply.preciseCss;
        var generic = reply.genericCss;
        if (typeof precise === "string" && typeof generic === "string") {
          var off = isGenerichideHost(location.hostname, reply.hideOff || []) ||
            !!reply.generichide;
          inject(precise + (off ? "" : generic));
        } else if (typeof reply.css === "string") {
          inject(reply.css);
        }
      }).catch(function () { /* no cosmetic CSS — page stands */ });
    } catch (e) { /* native bridge not ready */ }
  }

  queryCosmetic();

  /* Node test harness: the $generichide walk is exported so the
   * Kotlin↔JS agreement check can run it against the same host
   * table. */
  if (typeof module !== "undefined" && module.exports) {
    module.exports = { isGenerichideHost: isGenerichideHost };
  }
})();
