/* Pioneer Probe — Phase 2 content script, extended in Phase 3.
 *
 * Runs on every page (document_idle, plus re-scans at +2s and +6s for
 * lazy-loaded players). Reports media element / resource URLs and, on
 * DRM-ish pages only, Widevine EME availability to the app via native
 * messaging. It also applies per-site text zoom on the app's native
 * messages (see the zoom block below), reduced motion and
 * autoplay mute on the app's native messages (Batch 4,
 * below) — those are its only page changes besides the
 * video pop-up button (Phase 3, below).
 *
 * Media scan (Mariner MediaDetect.JS, carried over): video/audio
 * currentSrc/src + <source>, a[href] media links (label = link text
 * <= 80 chars), same-origin iframe recursion, cross-origin player
 * iframes (> 200px wide, src matching player|video|embed|stream|
 * watch) as kind "page", og:video / twitter:player:stream meta.
 * Embed-page URLs are reclassified to kind "page" by the app
 * (MediaLog) and are never offered to the player. Performance
 * resource entries are also reported (direct media the DOM
 * scan cannot see).
 *
 * Video pop-up watcher (Mariner VideoPopup.WATCHER_JS, adapted):
 * top frame only, largest playing <video>, one 40px button at its
 * top-right corner. Tap / long-press report as native messages
 * (video-popup-tap / video-popup-longpress, design §7) instead of
 * Mariner's notscapeVideoPopup JS bridge, which GeckoView has no
 * equivalent for. The per-site opt-out (videoPopupOff:$host) is
 * asked of the app once per page load (video-popup-query); the
 * watcher hides while the video is fullscreen, paused, or out of
 * the viewport, and never pauses / mutes / seeks the page video.
 * The 1 s position poller (alpha.42, audit E-A4) only runs while
 * a video is placed: place() starts it when it shows the button
 * and stops it when it hides it, so a page with no playing video
 * costs zero timer wakeups. The play / pause / scroll / resize /
 * fullscreen / visibility listeners re-arm it through place().
 */
(function () {
  "use strict";

  var MEDIA_RE = /\.(m3u8|mpd|mp4|webm|m4s|mp3|aac|ogg|oga|wav|ts)(\?|#|$)/i;
  var DRM_RE = /drm|shaka|bitmovin|castlabs|widevine|eme/i;

  /* Segment noise (alpha.50, alpha.57): the DOM scan's
   * resource-entries loop reports every fetched segment,
   * and each one became a media-sheet row of its own.
   * Token-for-token the same set as background.js
   * isSegmentNoise and the Kotlin copies in MediaLog and
   * BlockingObserver: .m4s segments, segment- / fragment /
   * bytes= URLs, and HLS / DASH init segments
   * (_init. / -init. followed by mp4 or m4s, plus a bare
   * init.mp4 / init.m4s path segment — v3 audit rec 2).
   * A manifest (.m3u8 / .mpd) is never noise. */
  var MANIFEST_URL_RE = /\.(m3u8|mpd)(\?|#|$)/i;
  var SEGMENT_NOISE_RE = /\.m4s(\?|#|$)|segment-|fragment|bytes=|[_-]init\.(mp4|m4s)(\?|#|$)|\/init\.(mp4|m4s)(\?|#|$)/i;
  function isSegmentNoise(url) {
    if (!url) return false;
    var lower = String(url).toLowerCase();
    if (MANIFEST_URL_RE.test(lower)) return false;
    return SEGMENT_NOISE_RE.test(lower);
  }

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
        // Integration (Phase 3): runMediaScan() posts
        // {"type":"media-scan"} — re-run the DOM scan now and
        // re-report via media-probe.
        if (msg && msg.type === "media-scan") report("scan");
        // Batch 2: scroll restore for a URL-only relaunch
        // restore (pre-alpha.6 saves). The port connects after
        // load, so one scroll lands; the delayed retry covers
        // a page whose layout is still settling.
        if (msg && msg.type === "scroll" && typeof msg.y === "number") {
          window.scrollTo(0, msg.y);
          setTimeout(function () { window.scrollTo(0, msg.y); }, 500);
        }
        // Batch 4: reduced motion + autoplay mute, pushed
        // live by the app (the script also asks for both on
        // load, below).
        if (msg && msg.type === "reduced-motion") applyReducedMotion(!!msg.on);
        if (msg && msg.type === "autoplay-muted") applyAutoplayMuted(!!msg.muted);
        // alpha.10: Save offline — the app asks for this
        // page's HTML over the port; the script answers with
        // a native message (outerHTML, capped at 2 MB — a
        // larger page saves truncated, with a marker).
        if (msg && msg.type === "page-html-request") {
          try {
            var html = document.documentElement.outerHTML || "";
            var truncated = false;
            if (html.length > 2000000) {
              html = html.slice(0, 2000000);
              truncated = true;
            }
            if (truncated) html += "\n<!-- notscape: truncated at 2 MB -->";
            browser.runtime.sendNativeMessage("pioneer", {
              type: "page-html", html: html
            }).catch(function () { /* shell not listening */ });
          } catch (e) { /* page not readable */ }
        }
      });
    }
  } catch (e) { /* live zoom push unavailable; zoom-query still applies */ }

  /* Reduced motion + autoplay mute (Batch 4): the app pushes
   * the current settings on the native port and the script
   * asks for them on load ("privacy-query" /
   * "autoplay-query") — the replies resolve the
   * sendNativeMessage promises, so a missed push is not
   * fatal. Reduced motion overrides matchMedia
   * prefers-reduced-motion and stills animations (Mariner's
   * DOCUMENT_START script, probe-shaped, document_idle —
   * an early animation may run once before it lands).
   * Autoplay mute mutes media until the first pointer or
   * key interaction, then stops re-muting for the page.
   */
  function applyReducedMotion(on) {
    try {
      if (on) {
        if (window.__nsReducedMotion) return;
        window.__nsReducedMotion = true;
        window.__nsOrigMatchMedia = window.matchMedia.bind(window);
        window.matchMedia = function (q) {
          var m = window.__nsOrigMatchMedia(q);
          if (typeof q === "string" &&
              q.indexOf("prefers-reduced-motion") >= 0) {
            return {
              matches: true, media: q, onchange: null,
              addListener: function () {},
              removeListener: function () {},
              addEventListener: function () {},
              removeEventListener: function () {},
              dispatchEvent: function () { return false; }
            };
          }
          return m;
        };
        var st = document.createElement("style");
        st.id = "notscape-reduced-motion";
        st.textContent = "*{animation-duration:0.01ms!important;" +
          "animation-iteration-count:1!important;" +
          "transition-duration:0.01ms!important;" +
          "scroll-behavior:auto!important}";
        (document.head || document.documentElement).appendChild(st);
      } else if (window.__nsReducedMotion) {
        window.__nsReducedMotion = false;
        if (window.__nsOrigMatchMedia) {
          window.matchMedia = window.__nsOrigMatchMedia;
        }
        var old = document.getElementById("notscape-reduced-motion");
        if (old) old.remove();
      }
    } catch (e) { /* best-effort */ }
  }

  var autoplayMuted = false;

  function applyAutoplayMuted(muted) {
    autoplayMuted = !!muted;
    try {
      document.querySelectorAll("video, audio").forEach(function (m) {
        if (autoplayMuted && !m.muted) {
          m.muted = true;
          m.__nsMutedByUs = true;
        }
      });
    } catch (e) { /* page not ready */ }
  }

  document.addEventListener("play", function (e) {
    if (autoplayMuted && e.target &&
        (e.target.tagName === "VIDEO" || e.target.tagName === "AUDIO")) {
      try {
        if (!e.target.muted) {
          e.target.muted = true;
          e.target.__nsMutedByUs = true;
        }
      } catch (err) { /* ignore */ }
    }
  }, true);

  ["pointerdown", "keydown"].forEach(function (ev) {
    document.addEventListener(ev, function () {
      if (!autoplayMuted) return;
      autoplayMuted = false;
      try {
        document.querySelectorAll("video, audio").forEach(function (m) {
          if (m.__nsMutedByUs) {
            m.muted = false;
            m.__nsMutedByUs = false;
          }
        });
      } catch (e) { /* ignore */ }
    }, true);
  });

  function queryPrivacy() {
    try {
      browser.runtime.sendNativeMessage("pioneer", { type: "privacy-query" })
        .then(function (reply) {
          if (reply && typeof reply.reducedMotion === "boolean") {
            applyReducedMotion(reply.reducedMotion);
          }
        })
        .catch(function () { /* default: motion on */ });
    } catch (e) { /* native bridge not ready */ }
  }

  function queryAutoplay() {
    try {
      browser.runtime.sendNativeMessage("pioneer", {
        type: "autoplay-query",
        host: location.hostname || ""
      }).then(function (reply) {
        if (reply && typeof reply.muted === "boolean") {
          applyAutoplayMuted(reply.muted);
        }
      }).catch(function () { /* default: not muted */ });
    } catch (e) { /* native bridge not ready */ }
  }

  function collect() {
    var out = [];
    var seen = {};
    function add(u, kind, label) {
      if (!u) return;
      try { u = new URL(u, document.baseURI).href; } catch (e) { return; }
      if (u.indexOf("blob:") === 0) return;
      // alpha.57: segment / init noise never reaches the
      // media sheet — only downloadable items do.
      if (isSegmentNoise(u)) return;
      if (seen[u]) return;
      seen[u] = 1;
      out.push({ url: u, kind: kind, label: label || "" });
    }
    function scan(doc) {
      try {
        doc.querySelectorAll("video").forEach(function (v) {
          add(v.currentSrc || v.src, "video", "video on page");
          v.querySelectorAll("source").forEach(function (s) { add(s.src, "video", ""); });
        });
        doc.querySelectorAll("audio").forEach(function (a) {
          add(a.currentSrc || a.src, "audio", "audio on page");
          a.querySelectorAll("source").forEach(function (s) { add(s.src, "audio", ""); });
        });
        doc.querySelectorAll("a[href]").forEach(function (a) {
          var h = a.href || "";
          if (/\.(mp4|webm|mkv|mov|m3u8|mpd|mp3|m4a|ogg|opus|flac|wav)(\?|#|$)/i.test(h)) {
            add(h, /\.(mp3|m4a|ogg|opus|flac|wav)(\?|#|$)/i.test(h) ? "audio" : "video", (a.textContent || "").trim().slice(0, 80));
          }
        });
        doc.querySelectorAll("iframe").forEach(function (f) {
          var src = f.src || "";
          try {
            var inner = f.contentDocument;
            if (inner) { scan(inner); return; }
          } catch (e) { /* cross-origin: fall through to the player check */ }
          if (src && (f.offsetWidth || 0) > 200 && /player|video|embed|stream|watch/i.test(src)) {
            add(src, "page", "embedded player");
          }
        });
      } catch (e) { /* a locked-down subtree must not kill the scan */ }
    }
    scan(document);
    var og = document.querySelector('meta[property="og:video"], meta[property="og:video:url"], meta[name="twitter:player:stream"]');
    if (og) {
      var ogu = og.getAttribute("content") || "";
      if (/\.(mp4|webm|mkv|mov|m3u8|mpd|mp3|m4a)(\?|#|$)/i.test(ogu)) add(ogu, "video", "featured");
      else if (ogu) add(ogu, "page", "featured video");
    }
    try {
      var resources = performance.getEntriesByType("resource");
      for (var k = 0; k < resources.length; k++) {
        if (MEDIA_RE.test(resources[k].name)) {
          var name = resources[k].name;
          add(name, /\.(mp3|m4a|aac|ogg|oga|wav)(\?|#|$)/i.test(name) ? "audio" : "video", "");
        }
      }
    } catch (e) { /* performance API unavailable */ }
    return out;
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

  /* ---- Video pop-up watcher (Phase 3) ---- */
  function installVideoPopup() {
    try {
      if (window.top !== window) return;
      if (window.__nsVideoPopupInstalled) return;
      window.__nsVideoPopupInstalled = true;
      window.__nsVideoPopupOff = false;
      // Per-site opt-out + the global button option, asked
      // once per page load. The app answers
      // {type:"video-popup-off", off: bool, showButton: bool};
      // until it answers the button is allowed (default
      // off = false, showButton = true). A reply from an
      // older build carries no showButton — the button stays
      // on, as before.
      window.__nsVideoPopupButton = true;
      try {
        browser.runtime.sendNativeMessage("pioneer", {
          type: "video-popup-query",
          host: location.hostname || ""
        }).then(function (reply) {
          if (reply && typeof reply.off === "boolean") {
            window.__nsVideoPopupOff = reply.off;
          }
          if (reply && typeof reply.showButton === "boolean") {
            window.__nsVideoPopupButton = reply.showButton;
          }
          if (reply) place();
        }).catch(function () { /* opt-out unknown — button stays */ });
      } catch (e) { /* native bridge not ready */ }
      var btn = null;
      var curUrl = null;
      var longPressTimer = null;
      var lastLongPressAt = 0;
      function sendTap(url) {
        try {
          browser.runtime.sendNativeMessage("pioneer", { type: "video-popup-tap", url: url });
        } catch (e) { /* native bridge not ready */ }
      }
      function sendLongPress(url) {
        lastLongPressAt = Date.now();
        try {
          browser.runtime.sendNativeMessage("pioneer", { type: "video-popup-longpress", url: url });
        } catch (e) { /* native bridge not ready */ }
      }
      function pick() {
        if (window.__nsVideoPopupOff) return null;
        // alpha.51: the global button option — when off, no
        // button on any video. The per-site opt-out above
        // stays as is. (The video long-press below is a
        // deliberate gesture, so it is not gated here.)
        if (window.__nsVideoPopupButton === false) return null;
        if (document.fullscreenElement) return null;
        var vids = document.querySelectorAll("video");
        var best = null;
        var bestArea = 0;
        for (var i = 0; i < vids.length; i++) {
          var v = vids[i];
          try {
            if (v.paused || v.currentTime <= 0 || v.readyState < 2) continue;
            var src = v.currentSrc || v.src || "";
            if (!src || src.indexOf("blob:") === 0) continue;
            var r = v.getBoundingClientRect();
            if (r.width < 160 || r.height < 90) continue;
            if (r.bottom <= 0 || r.top >= window.innerHeight ||
                r.right <= 0 || r.left >= window.innerWidth) continue;
            var area = r.width * r.height;
            if (area > bestArea) { bestArea = area; best = { r: r, src: src }; }
          } catch (e) { /* one bad element must not kill the pick */ }
        }
        return best;
      }
      function place() {
        try {
          // E-A4 (alpha.42): a hidden page never shows the pill,
          // and it must not keep the 1 s poller alive either.
          if (document.hidden) { if (btn) btn.style.display = "none"; stopPoll(); return false; }
          var found = pick();
          if (!found) { if (btn) btn.style.display = "none"; stopPoll(); return false; }
          if (!btn) {
            btn = document.createElement("div");
            btn.id = "notscape-video-popup";
            btn.textContent = "\u25B6";
            btn.style.cssText = "position:fixed;z-index:2147483647;width:40px;height:40px;" +
              "border-radius:20px;background:rgba(0,0,0,0.65);color:#fff;" +
              "font-size:18px;line-height:40px;text-align:center;" +
              "pointer-events:auto;cursor:pointer;user-select:none;" +
              "-webkit-user-select:none;touch-action:manipulation;display:none;";
            btn.addEventListener("click", function (e) {
              e.stopPropagation(); e.preventDefault();
              // A long-press already reported for this press
              // must not also fire a tap.
              if (Date.now() - lastLongPressAt < 1000) return;
              if (curUrl) sendTap(curUrl);
            });
            btn.addEventListener("pointerdown", function () {
              if (longPressTimer) clearTimeout(longPressTimer);
              longPressTimer = setTimeout(function () {
                longPressTimer = null;
                if (curUrl) sendLongPress(curUrl);
              }, 500);
            });
            ["pointerup", "pointercancel", "pointerleave"].forEach(function (ev) {
              btn.addEventListener(ev, function () {
                if (longPressTimer) { clearTimeout(longPressTimer); longPressTimer = null; }
              });
            });
            btn.addEventListener("contextmenu", function (e) {
              e.stopPropagation(); e.preventDefault();
              if (Date.now() - lastLongPressAt < 1000) return;
              if (curUrl) sendLongPress(curUrl);
            });
            document.documentElement.appendChild(btn);
          }
          curUrl = found.src;
          var top = Math.max(8, found.r.top + 8);
          var left = Math.min(window.innerWidth - 48, found.r.right - 48);
          btn.style.top = top + "px";
          btn.style.left = left + "px";
          btn.style.display = "block";
          // E-A4: a video is placed — the 1 s poller tracks it
          // (layout shifts move it without a scroll/resize event).
          startPoll();
          return true;
        } catch (e) { /* placement is best-effort */ }
        stopPoll();
        return false;
      }
      // E-A4 (alpha.42): the 1 s poller is managed, not free-running.
      // It only exists while a video is placed (startPoll is called
      // from place() on success, stopPoll on every hide); the event
      // listeners below re-arm it through place() when a video
      // starts, moves into view, or the tab becomes visible again.
      var pollTimer = null;
      function startPoll() {
        if (pollTimer !== null) return;
        pollTimer = setInterval(place, 1000);
      }
      function stopPoll() {
        if (pollTimer !== null) { clearInterval(pollTimer); pollTimer = null; }
      }
      document.addEventListener("play", place, true);
      document.addEventListener("playing", place, true);
      document.addEventListener("pause", place, true);
      document.addEventListener("ended", place, true);
      window.addEventListener("scroll", place, true);
      window.addEventListener("resize", place);
      document.addEventListener("fullscreenchange", place);
      document.addEventListener("visibilitychange", place);
      // alpha.51: long-press on the video element itself
      // opens the media sheet — the pill is not the only
      // way in. Document-level capture listeners, so videos
      // added after load are covered without per-element
      // bookkeeping; the pill is a separate element on top
      // of the video, so a press on it never reaches here
      // (its target is the pill div, not the video). The
      // gate mirrors pick() minus size/in-view: the video
      // is right under the finger, so only "playing, with
      // a real source" matters. Deliberate gesture, so it
      // is NOT gated on the per-site opt-out or the button
      // option — those govern the uninvited button, not a
      // user holding a video. The report reuses
      // video-popup-longpress and sendLongPress, so the
      // pill's tap-suppression (lastLongPressAt) and the
      // app's allowCheck both apply unchanged. A short tap
      // cancels the timer and does nothing — the page's own
      // controls keep working.
      var videoPressTimer = null;
      var videoPressUrl = null;
      function videoPressSrc(v) {
        try {
          if (!v || v.paused || v.currentTime <= 0 || v.readyState < 2) return null;
          var src = v.currentSrc || v.src || "";
          if (!src || src.indexOf("blob:") === 0) return null;
          return src;
        } catch (e) { return null; }
      }
      function cancelVideoPress() {
        if (videoPressTimer) { clearTimeout(videoPressTimer); videoPressTimer = null; }
        videoPressUrl = null;
      }
      document.addEventListener("pointerdown", function (e) {
        try {
          if (!e.target || e.target.tagName !== "VIDEO") return;
          var src = videoPressSrc(e.target);
          if (!src) return;
          videoPressUrl = src;
          if (videoPressTimer) clearTimeout(videoPressTimer);
          videoPressTimer = setTimeout(function () {
            videoPressTimer = null;
            if (videoPressUrl) sendLongPress(videoPressUrl);
            videoPressUrl = null;
          }, 500);
        } catch (err) { cancelVideoPress(); }
      }, true);
      ["pointerup", "pointercancel"].forEach(function (ev) {
        document.addEventListener(ev, cancelVideoPress, true);
      });
      document.addEventListener("contextmenu", function (e) {
        try {
          if (!e.target || e.target.tagName !== "VIDEO") return;
          var src = videoPressSrc(e.target);
          if (!src) return;
          e.stopPropagation(); e.preventDefault();
          if (Date.now() - lastLongPressAt < 1000) return;
          sendLongPress(src);
        } catch (err) { /* the page keeps its own menu */ }
      }, true);
      place();
    } catch (e) { /* a locked-down page simply never shows the button */ }
  }

  /* alpha.50: a video that gains its source or duration
   * after load (a lazy player, MSE attaching late) is
   * found without waiting for the +2s / +6s re-scans or
   * the 1 s pill poller: re-run the DOM scan when metadata
   * loads or the duration becomes known. Capture phase,
   * so it catches media in any subtree. */
  document.addEventListener("loadedmetadata", function () { report("loadedmetadata"); }, true);
  document.addEventListener("durationchange", function () { report("durationchange"); }, true);

  installVideoPopup();
  report("idle");
  queryZoom();
  queryPrivacy();
  queryAutoplay();
  setTimeout(function () { report("t+2s"); }, 2000);
  setTimeout(function () { report("t+6s"); }, 6000);
})();
