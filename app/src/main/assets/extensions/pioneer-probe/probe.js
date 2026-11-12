/* Pioneer Probe — Phase 1 spike content script.
 *
 * Runs on every page (document_idle, plus re-scans at +2s and +6s for
 * lazy-loaded players). Reports media element / resource URLs and, on
 * DRM-ish pages only, Widevine EME availability to the app via native
 * messaging. It never modifies the page.
 */
(function () {
  "use strict";

  var MEDIA_RE = /\.(m3u8|mpd|mp4|webm|m4s|mp3|aac|ogg|oga|wav|ts)(\?|#|$)/i;
  var DRM_RE = /drm|shaka|bitmovin|castlabs|widevine|eme/i;

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
  setTimeout(function () { report("t+2s"); }, 2000);
  setTimeout(function () { report("t+6s"); }, 6000);
})();
