/* Pioneer Probe — Phase 2 content script.
 *
 * Runs on every page (document_idle, plus re-scans at +2s and +6s for
 * lazy-loaded players). Reports media element / resource URLs and, on
 * DRM-ish pages only, Widevine EME availability to the app via native
 * messaging. It also applies per-site text zoom on the app's native
 * messages (see the zoom block below) — that is its only page change.
 */
(function () {
  "use strict";

  var MEDIA_RE = /\.(m3u8|mpd|mp4|webm|m4s|mp3|aac|ogg|oga|wav|ts)(\?|#|$)/i;
  var DRM_RE = /drm|shaka|bitmovin|castlabs|widevine|eme/i;

  /* Text zoom (Phase 2): per-site, via the app. There is no
   * per-session text-zoom API in GeckoView and no JS-eval API, so
   * the app pushes the factor over a native port and the script
   * applies documentElement font-size scaling. A port only exists
   * while this page is loaded, so the script also asks the app for
   * its tab's stored factor after every (re)load ("zoom-query") —
   * the app's reply resolves that sendNativeMessage promise.
   * Everything here is guarded: if the port or the query fails,
   * the media probe below still runs.
   */
  var currentZoom = 1.0;

  function applyZoom(factor) {
    var f = Number(factor);
    if (!isFinite(f) || f <= 0) return;
    currentZoom = f;
    try {
      document.documentElement.style.fontSize = (f * 100) + "%";
    } catch (e) { /* page not ready */ }
  }

  function queryZoom() {
    try {
      browser.runtime.sendNativeMessage("pioneer", { type: "zoom-query" })
        .then(function (reply) {
          if (reply && reply.factor) applyZoom(reply.factor);
        })
        .catch(function () { /* no stored factor — stay at 100% */ });
    } catch (e) { /* native bridge not ready */ }
  }

  try {
    if (browser.runtime && browser.runtime.connectNative) {
      var zoomPort = browser.runtime.connectNative("pioneer");
      zoomPort.onMessage.addListener(function (msg) {
        if (msg && msg.type === "zoom") applyZoom(msg.factor);
      });
    }
  } catch (e) { /* live zoom push unavailable; zoom-query still applies */ }

  function collect() {
    var items = [];
    var seen = {};
    function add(url, kind) {
      if (!url || seen[url]) return;
      seen[url] = true;
      items.push({ kind: kind, url: url });
    }
    var media = document.querySelectorAll("video, audio");
    for (var i = 0; i < media.length; i++) {
      var el = media[i];
      if (el.currentSrc) add(el.currentSrc, el.tagName.toLowerCase());
      var sources = el.querySelectorAll("source");
      for (var j = 0; j < sources.length; j++) {
        if (sources[j].src) add(sources[j].src, "source");
      }
    }
    try {
      var resources = performance.getEntriesByType("resource");
      for (var k = 0; k < resources.length; k++) {
        if (MEDIA_RE.test(resources[k].name)) add(resources[k].name, "resource");
      }
    } catch (e) { /* performance API unavailable */ }
    return items;
  }

  function emeProbe() {
    if (!DRM_RE.test(location.href)) return Promise.resolve(null);
    if (!("requestMediaKeySystemAccess" in navigator)) {
      return Promise.resolve({ supported: false });
    }
    return navigator.requestMediaKeySystemAccess("com.widevine.alpha", [{
      initDataTypes: ["cenc"],
      videoCapabilities: [{ contentType: 'video/mp4; codecs="avc1.42E01E"' }]
    }]).then(function () {
      return { supported: true, widevine: "granted" };
    }).catch(function (err) {
      return { supported: true, widevine: "denied:" + err.name };
    });
  }

  function report(phase) {
    var payload = {
      type: "media-probe",
      phase: phase,
      url: location.href,
      title: document.title || "",
      items: collect()
    };
    emeProbe().then(function (eme) {
      payload.eme = eme;
      try {
        browser.runtime.sendNativeMessage("pioneer", payload);
      } catch (e) { /* native bridge not ready */ }
    });
  }

  report("idle");
  queryZoom();
  setTimeout(function () { report("t+2s"); }, 2000);
  setTimeout(function () { report("t+6s"); }, 6000);
})();
