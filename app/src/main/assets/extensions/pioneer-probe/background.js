/* Pioneer Probe — Phase 3 background script.
 *
 * Two jobs, one listener:
 *
 *  1. OBSERVE (`media-net`): every request whose URL looks like
 *     media (Mariner's MediaLog stream regex) is reported to the
 *     app with its tab id and page host. This is the only route
 *     that closes the MSE/YouTube gap: the DOM probe sees
 *     `blob:` URLs only, while this observer sees the real
 *     `.m3u8` / `.mpd` manifests and media segments the page
 *     fetches (API doc §10 — Mozilla's own webcompat add-on
 *     proves `webRequest` fires in GeckoView, bug 1728461).
 *     Direct-URL recovery for MSE playback is impossible in
 *     principle — no URL exists for an MSE stream — and the
 *     observed segment URLs are transient and signed. They are
 *     reported as-is, pass-through only; the app never re-fetches
 *     them without the page's cookies. Do not pretend otherwise.
 *     Response-header classification (alpha.47): the URL-only
 *     observer never matches extension-less media URLs
 *     (YouTube googlevideo.com/videoplayback, Twitch /
 *     Dailymotion CDN URLs). `onHeadersReceived` sees the
 *     response Content-Type, which catches them — the MIME
 *     decides when one is known, and a non-media MIME vetoes
 *     even a media-looking URL (a .mp4 served as text/html is
 *     an error page, not media).
 *
 *  2. BLOCK (Mariner's lists, second layer after uBlock Origin):
 *     `webRequestBlocking` cancels requests the compiled rule set
 *     matches. The Kotlin `FilterEngine` is the source of truth;
 *     this file is a JS mirror of its `blockCategory` /
 *     `isDocumentBlocked` over the rule set the app pushes via
 *     the `rules-update` native message. The two matchers must
 *     agree on the fixed sample-URL table (scorecard row A).
 *
 *  Batch 4: per-site image blocking (`blockImagesHosts` —
 *  `SiteSetting.blockImages`) cancels image requests on the
 *  page host, and the referer policy (`refererPolicy` —
 *  "" / "origin" / "never") rewrites the Referer header in
 *  `onBeforeSendHeaders`. Neither is a list rule; both ride
 *  the same `rules-update` push.
 *
 *  POPUPS (`$document` / `$popup`, Beta.63 #64): main-frame loads
 *  are NEVER cancelled here. `webRequest` carries no user-gesture
 *  signal, so a main-frame cancellation could not tell a
 *  deliberately tapped link to a popunder host from a
 *  gesture-less popunder hop — and it blocked the tapped link
 *  silently, with no notice and no allow/deny recourse
 *  (watch-wrestling.eu, 2026-11-12). Mariner's guard only ever
 *  applied to gesture-less hops, so a tapped link never broke;
 *  Pioneer now matches it: the engine's `onLoadRequest` popunder
 *  guard has the gesture signal and enforces exactly that rule,
 *  capturing the hop into the popup queue + notice. Subresource
 *  blocking below is unchanged.
 *
 *  Rules arrive two ways: the app pushes `rules-update` on the
 *  native port this script opens at startup, and the script asks
 *  for the initial set with a `rules-request` native message
 *  (its reply IS the `rules-update` payload) so a background
 *  restart never waits for the app to notice it.
 *
 *  Tab attribution: `details.tabId` + `browser.tabs.get(tabId)`
 *  for the page host. An observation whose tab cannot be
 *  identified is still sent with an empty page host — and the
 *  APP drops it (never guesses). The app attributes by page
 *  host; see BlockingObserver.
 *
 *  GET/HEAD only: POST/PUT uploads and sign-ins are never
 *  blocked (Mariner Phase G repair, carried over). Non-http(s)
 *  URLs are never blocked or observed. Per-site exceptions
 *  (`disabledHosts`, from `SiteSetting.disableBlocking`) skip
 *  the page host entirely — but they do NOT disable uBlock
 *  Origin on that site; the app cannot toggle a per-site
 *  exception inside uBlock (design §10 gap 1).
 */
(function () {
  "use strict";

  var CAT_ADS = 0;
  var CAT_TRACKER = 1;
  var CAT_OTHER = 2;

  var STREAM_RE = /\.(m3u8|mpd|mp4|webm|mkv|mov|mp3|m4a|aac|ogg|opus|flac|wav|m4v|ts)(\?|#|$)/i;
  var AUDIO_RE = /\.(mp3|m4a|aac|ogg|opus|flac|wav)(\?|#|$)/i;

  /* Response-header classification (alpha.47): the MIME
   * companion to STREAM_RE. A response whose Content-Type
   * matches is media even when its URL has no media
   * extension. Parameters after ';' are stripped before
   * matching; the match is case-insensitive. `video/mp2t`
   * is covered by `video/`. Must agree with the Kotlin
   * copies in BlockingObserver and MediaLog. */
  var MEDIA_MIME_RE = /^(video\/|audio\/|application\/x-mpegurl|application\/dash\+xml)/i;

  /* Registrable-domain heuristic, mirroring FilterEngine's
   * (no-Guava) sameSite: last two labels, unless they form a
   * known multi-label public suffix, in which case three. The
   * list is the same one FilterEngine carries; keep them in
   * sync or $third-party verdicts drift between the matchers. */
  var MULTI_LABEL_SUFFIXES = {
    "ac.uk": 1, "co.uk": 1, "gov.uk": 1, "me.uk": 1, "net.uk": 1,
    "nhs.uk": 1, "org.uk": 1, "police.uk": 1, "sch.uk": 1,
    "asn.au": 1, "com.au": 1, "edu.au": 1, "gov.au": 1, "id.au": 1,
    "net.au": 1, "org.au": 1,
    "ac.nz": 1, "co.nz": 1, "geek.nz": 1, "govt.nz": 1, "net.nz": 1,
    "org.nz": 1, "school.nz": 1,
    "ac.jp": 1, "co.jp": 1, "go.jp": 1, "ne.jp": 1, "or.jp": 1,
    "ac.kr": 1, "co.kr": 1, "go.kr": 1, "ne.kr": 1, "or.kr": 1,
    "ac.in": 1, "co.in": 1, "firm.in": 1, "gen.in": 1, "gov.in": 1,
    "ind.in": 1, "net.in": 1, "org.in": 1,
    "com.br": 1, "gov.br": 1, "net.br": 1, "org.br": 1,
    "com.ar": 1, "com.co": 1, "com.mx": 1, "gob.mx": 1, "com.pe": 1,
    "com.ve": 1,
    "com.cn": 1, "gov.cn": 1, "net.cn": 1, "org.cn": 1,
    "com.tw": 1, "gov.tw": 1, "org.tw": 1,
    "com.hk": 1, "gov.hk": 1, "org.hk": 1,
    "com.sg": 1, "gov.sg": 1,
    "ac.za": 1, "co.za": 1, "gov.za": 1, "org.za": 1,
    "ac.il": 1, "co.il": 1, "gov.il": 1, "org.il": 1,
    "com.tr": 1, "gov.tr": 1, "org.tr": 1,
    "com.pl": 1, "com.ua": 1, "com.vn": 1, "com.ph": 1, "com.my": 1,
    "com.pk": 1, "com.ng": 1, "com.eg": 1, "com.sa": 1, "com.ae": 1,
    "com.qa": 1, "com.bd": 1, "com.np": 1, "com.lk": 1, "com.kh": 1,
    "com.mm": 1
  };

  var IPV4_RE = /^\d{1,3}(\.\d{1,3}){3}$/;

  function registrableDomain(host) {
    var h = String(host || "").toLowerCase().replace(/\.$/, "");
    if (IPV4_RE.test(h)) return h;
    var labels = h.split(".").filter(function (s) { return s.length > 0; });
    if (labels.length <= 2) return labels.join(".");
    var lastTwo = labels.slice(-2).join(".");
    if (MULTI_LABEL_SUFFIXES[lastTwo]) return labels.slice(-3).join(".");
    return lastTwo;
  }

  function sameSite(a, b) {
    var ha = String(a || "").toLowerCase().replace(/\.$/, "");
    var hb = String(b || "").toLowerCase().replace(/\.$/, "");
    if (ha === hb) return true;
    if (IPV4_RE.test(ha) || IPV4_RE.test(hb)) return false;
    return registrableDomain(ha) === registrableDomain(hb);
  }

  /* The compiled rule set, replaced wholesale on `rules-update`.
   * Empty until the first push — with no rules nothing is
   * blocked and nothing is reported as blocked (uBlock and ETP
   * still run; this layer simply has nothing to say yet). */
  var rules = {
    hosts: {},
    trackerHosts: {},
    docHosts: {},
    scoped: {},
    pathKeywords: [],
    exceptions: {},
    neverBlock: {},
    builtinHosts: {},
    disabledHosts: {},
    blockImagesHosts: {},
    userNet: []
  };

  function toSet(list) {
    var s = {};
    (list || []).forEach(function (v) { s[String(v).toLowerCase()] = true; });
    return s;
  }

  var flags = { blockAds: true, blockTrackers: true, refererPolicy: "" };

  function applyRules(r) {
    r = r || {};
    if (r.blockAds !== undefined) flags.blockAds = !!r.blockAds;
    if (r.blockTrackers !== undefined) flags.blockTrackers = !!r.blockTrackers;
    if (r.refererPolicy !== undefined) flags.refererPolicy = String(r.refererPolicy || "");
    r = r.rules || r;
    rules.hosts = toSet(r.hosts);
    rules.trackerHosts = toSet(r.trackerHosts);
    rules.docHosts = toSet(r.docHosts);
    rules.exceptions = toSet(r.exceptions);
    rules.neverBlock = toSet(r.neverBlock);
    rules.builtinHosts = toSet(r.builtinHosts);
    rules.disabledHosts = toSet(r.disabledHosts);
    rules.blockImagesHosts = toSet(r.blockImagesHosts);
    rules.pathKeywords = (r.pathKeywords || []).map(function (s) {
      return String(s).toLowerCase();
    });
    rules.scoped = {};
    (r.scoped || []).forEach(function (sr) {
      var key = String(sr.host || "").toLowerCase();
      if (!key) return;
      (rules.scoped[key] = rules.scoped[key] || []).push({
        pathPrefix: sr.pathPrefix != null ? String(sr.pathPrefix).toLowerCase() : null,
        domains: sr.domains ? sr.domains.map(function (d) { return String(d).toLowerCase(); }) : null,
        notDomains: sr.notDomains ? sr.notDomains.map(function (d) { return String(d).toLowerCase(); }) : null,
        exception: !!sr.exception,
        thirdParty: (sr.thirdParty === true || sr.thirdParty === false) ? sr.thirdParty : null,
        important: !!sr.important,
        category: (typeof sr.category === "number") ? sr.category : CAT_ADS,
        document: !!sr.document
      });
    });
    rules.userNet = (r.userNet || []).map(function (n) {
      return { kind: String(n.kind || ""), value: String(n.value || "").toLowerCase() };
    });
  }

  /* ---- host helpers (mirror FilterEngine) ---- */

  function matchesSet(set, host) {
    var h = String(host || "").toLowerCase();
    while (true) {
      if (set[h]) return true;
      var dot = h.indexOf(".");
      if (dot < 0) return false;
      h = h.substring(dot + 1);
      if (h.indexOf(".") < 0) return !!set[h];
    }
  }

  function hostChain(host) {
    var out = [];
    var h = String(host || "").toLowerCase();
    while (true) {
      out.push(h);
      var dot = h.indexOf(".");
      if (dot < 0) break;
      h = h.substring(dot + 1);
      if (h.indexOf(".") < 0) { out.push(h); break; }
    }
    return out;
  }

  function isThirdParty(reqHost, pageHost) {
    if (!pageHost || reqHost === pageHost) return false;
    return !sameSite(reqHost, pageHost);
  }

  function splitUrl(url) {
    try {
      var u = new URL(url);
      return {
        scheme: u.protocol.replace(":", "").toLowerCase(),
        host: u.hostname.toLowerCase(),
        path: (u.pathname || "").toLowerCase(),
        fullLower: String(url).toLowerCase()
      };
    } catch (e) {
      return { scheme: "", host: "", path: "", fullLower: String(url).toLowerCase() };
    }
  }

  function hostFromUrl(url) {
    try { return new URL(url).hostname.toLowerCase(); } catch (e) { return ""; }
  }

  function userNetMatches(rule, scheme, host, path) {
    if (!host) return false;
    if (rule.kind === "host") {
      return host === rule.value || host.endsWith("." + rule.value);
    }
    if (rule.kind === "url") {
      return (scheme + "://" + host + path) === rule.value;
    }
    return false;
  }

  function scopedRuleMatch(reqHost, path, pageHost, wantException, onlyImportant, onlyDocument) {
    var chain = hostChain(reqHost);
    for (var i = 0; i < chain.length; i++) {
      var list = rules.scoped[chain[i]];
      if (!list) continue;
      for (var j = 0; j < list.length; j++) {
        var r = list[j];
        if (r.exception !== wantException) continue;
        if (onlyImportant && !r.important) continue;
        if (onlyDocument && !r.document) continue;
        if (r.domains !== null &&
            (!pageHost || !matchesSet(toSet(r.domains), pageHost))) continue;
        if (r.notDomains !== null && pageHost &&
            matchesSet(toSet(r.notDomains), pageHost)) continue;
        if (r.thirdParty !== null &&
            r.thirdParty !== isThirdParty(reqHost, pageHost)) continue;
        if (r.pathPrefix !== null && path.indexOf(r.pathPrefix) !== 0) continue;
        return r;
      }
    }
    return null;
  }

  /* ---- the matcher (mirror FilterEngine.blockCategory) ---- */

  function blockCategory(url, isMainFrame, blockAds, blockTrackers, pageHost) {
    if (isMainFrame) return null;
    if (!blockAds && !blockTrackers) return null;
    var parts = splitUrl(url);
    var host = parts.host;
    if (!host) return null;
    if (blockAds) {
      for (var i = 0; i < rules.userNet.length; i++) {
        if (userNetMatches(rules.userNet[i], parts.scheme, host, parts.path)) {
          return CAT_OTHER;
        }
      }
    }
    if (matchesSet(rules.neverBlock, host)) return null;
    var path = parts.path;
    var page = String(pageHost || "").toLowerCase();
    var important = scopedRuleMatch(host, path, page, false, true, false);
    if (important) return important.category;
    if (matchesSet(rules.exceptions, host)) return null;
    if (scopedRuleMatch(host, path, page, true, false, false)) return null;
    if (blockTrackers && matchesSet(rules.builtinHosts, host)) return CAT_TRACKER;
    if ((blockAds || blockTrackers) && matchesSet(rules.hosts, host)) {
      return matchesSet(rules.trackerHosts, host) ? CAT_TRACKER : CAT_ADS;
    }
    var scoped = scopedRuleMatch(host, path, page, false, false, false);
    if (scoped) return scoped.category;
    if (blockAds) {
      var full = parts.fullLower;
      for (var k = 0; k < rules.pathKeywords.length; k++) {
        if (full.indexOf(rules.pathKeywords[k]) >= 0) return CAT_ADS;
      }
    }
    return null;
  }

  function isDocumentHost(host) {
    var h = String(host || "").toLowerCase();
    if (!h) return false;
    if (matchesSet(rules.neverBlock, h)) return false;
    if (matchesSet(rules.exceptions, h)) return false;
    return matchesSet(rules.docHosts, h);
  }

  /* ---- $document / $popup (mirror FilterEngine.isDocumentBlocked) ---- */

  function documentCategory(url, pageHost) {
    var parts = splitUrl(url);
    var host = parts.host;
    if (!host) return null;
    if (matchesSet(rules.neverBlock, host)) return null;
    var path = parts.path;
    var page = String(pageHost || "").toLowerCase();
    var important = scopedRuleMatch(host, path, page, false, true, true);
    if (important) return important.category;
    if (matchesSet(rules.exceptions, host)) return null;
    if (scopedRuleMatch(host, path, page, true, false, true)) return null;
    if (isDocumentHost(host)) return CAT_ADS;
    var scoped = scopedRuleMatch(host, path, page, false, false, true);
    if (scoped) return scoped.category;
    return null;
  }

  function isDocumentBlocked(url, pageHost) {
    return documentCategory(url, pageHost) !== null;
  }

  /* "video" / "audio" for a response MIME, null when the
   * MIME is not media. */
  function mediaKindForMime(mime) {
    if (!mime) return null;
    var type = String(mime).split(";")[0].trim().toLowerCase();
    if (!type) return null;
    if (!MEDIA_MIME_RE.test(type)) return null;
    return type.indexOf("audio/") === 0 ? "audio" : "video";
  }

  /* Segment noise (alpha.50, gap plan row A-3):
   * observations that are fragments of a manifest stream,
   * not media in their own right — each one became a
   * media-sheet row of its own. A manifest (.m3u8 / .mpd)
   * is never noise, even if its path happens to carry a
   * noise-shaped token. Must agree with the Kotlin copies
   * in BlockingObserver and MediaLog, and with the probe.js
   * copy. alpha.57 adds the init-segment tokens and applies
   * the filter in the DOM half too (probe.js add(),
   * MediaLog.noteDom); the v3 audit (rec 2) widens the token
   * to _init. / -init. + mp4 or m4s, plus a bare init.mp4
   * / init.m4s path segment. */
  var MANIFEST_URL_RE = /\.(m3u8|mpd)(\?|#|$)/i;
  var SEGMENT_NOISE_RE = /\.m4s(\?|#|$)|segment-|fragment|bytes=|[_-]init\.(mp4|m4s)(\?|#|$)|\/init\.(mp4|m4s)(\?|#|$)/i;
  function isSegmentNoise(url) {
    if (!url) return false;
    var lower = String(url).toLowerCase();
    if (MANIFEST_URL_RE.test(lower)) return false;
    return SEGMENT_NOISE_RE.test(lower);
  }

  /* True for a manifest observation: a manifest URL, or a
   * manifest MIME. The size floor (below) never applies to
   * one: HLS / DASH manifests are routinely a few KB, and
   * flooring them would break the detection the floor
   * exists to clean up. */
  /* InnerTube manifest shape (alpha.65): see
   * MediaLog's copy — extension-less manifests on
   * manifest.googlevideo.com. Must agree with it and
   * with BlockingObserver's copy. */
  function isManifest(url, mime) {
    var lower = String(url || "").toLowerCase();
    if (MANIFEST_URL_RE.test(lower)) return true;
    if (lower.indexOf("manifest.googlevideo.com/api/manifest/hls_playlist/") >= 0 ||
        lower.indexOf("manifest.googlevideo.com/api/manifest/dash/") >= 0) return true;
    if (mime) {
      var type = String(mime).split(";")[0].trim().toLowerCase();
      if (type === "application/x-mpegurl" ||
          type === "application/dash+xml") return true;
    }
    return false;
  }

  /* 10 KB size floor (alpha.50): an observation whose size
   * is known and below the floor is not media — tiny junk
   * (a tracking pixel served as video/*, a 1 KB error
   * page) never becomes a sheet row. No size known, no
   * floor: onBeforeRequest carries no size and keeps
   * today's behaviour; an unparseable size is treated as
   * unknown. */
  var MEDIA_SIZE_FLOOR_BYTES = 10240;

  /* MIME-authoritative classification: when a response MIME
   * is known (onHeadersReceived) the MIME decides — a media
   * MIME reports, a non-media MIME vetoes even a
   * media-looking URL. With no MIME (onBeforeRequest) the
   * URL regex decides, exactly as before. Segment noise is
   * never media, and the size floor applies where a size is
   * known (alpha.50). */
  function mediaKindFor(url, mime, contentLength) {
    if (isSegmentNoise(url)) return null;
    if (contentLength !== null && contentLength !== undefined &&
        String(contentLength).length > 0 && !isManifest(url, mime)) {
      var size = Number(contentLength);
      if (isFinite(size) && size < MEDIA_SIZE_FLOOR_BYTES) return null;
    }
    if (mime !== null && mime !== undefined && String(mime).length > 0) {
      return mediaKindForMime(mime);
    }
    if (!url || url.indexOf("blob:") === 0) return null;
    if (!STREAM_RE.test(url)) return null;
    return AUDIO_RE.test(url.toLowerCase()) ? "audio" : "video";
  }

  /* Cookies wiring (alpha.64): the download job asks for
   * ONE host's cookies so it can replay them host-scoped.
   * The app sends {type:"cookies-request", id, url} on the
   * native port; the answer is {type:"cookies-response",
   * id, url, cookies:[{name, value}]} on the same port.
   * The default store only (no storeId) — alpha.63 proved
   * the tab's cookies live there. VALUES ARE INCLUDED
   * HERE — replay needs them — and this is the one path
   * where values leave the background script: the reply
   * goes straight to the job, never to a log, a status
   * line, or Diagnostics, and never to disk. An absent
   * API or a throwing getAll answers {cookies:[], error}
   * instead of failing the job. Never throws. */
  function handleCookiesRequest(msg) {
    var id = String((msg && msg.id) || "");
    var url = String((msg && msg.url) || "");
    if (typeof browser === "undefined" || !browser.cookies ||
        typeof browser.cookies.getAll !== "function") {
      postNative({
        type: "cookies-response", id: id, url: url,
        cookies: [], error: "unavailable"
      });
      return;
    }
    try {
      Promise.resolve(browser.cookies.getAll({ url: url })).then(function (cs) {
        var out = [];
        for (var i = 0; i < (cs || []).length; i++) {
          var c = cs[i] || {};
          if (c.name) out.push({ name: String(c.name), value: String(c.value || "") });
        }
        postNative({ type: "cookies-response", id: id, url: url, cookies: out });
      }, function (e) {
        postNative({
          type: "cookies-response", id: id, url: url,
          cookies: [], error: (e && e.name) || "Error"
        });
      });
    } catch (e) {
      postNative({
        type: "cookies-response", id: id, url: url,
        cookies: [], error: (e && e.name) || "Error"
      });
    }
  }

  /* ---- native messaging: the background port ---- */

  /* Alpha.61: every background-script report goes out on
   * the native port opened by connectPort() below — never
   * on the one-shot native-message call. Device evidence
   * (alpha.60) showed background one-shot reports never
   * arrive (the headers-state report, sent once
   * unconditionally at startup, never reached
   * Diagnostics), while the content script's one-shot
   * reports arrive fine — the session-less background
   * messages have no per-tab delegate to land on. The
   * port is the channel that does reach the app for the
   * background script (the app's onConnect fires for it,
   * and the app pushes rules-update on it), so all
   * background sends use it. The one-shot call is NOT
   * kept as a fallback: it is the silently-losing
   * channel, and falling back to it would reintroduce
   * exactly the silent loss this alpha removes. When no
   * port is live yet, reports queue (bounded, 64) and
   * flush on connect; a post on a dead port clears the
   * reference and re-queues, so nothing is silently
   * lost. */
  var nativePort = null;
  var nativePortOutbox = [];
  var nativePortOutboxCap = 64;

  /* Post one message on the live port. True when posted;
   * false when there is no live port. Never throws and
   * never connects — the caller decides what to do. */
  function tryPost(msg) {
    if (!nativePort) return false;
    try {
      nativePort.postMessage(msg);
      return true;
    } catch (e) {
      nativePort = null;
      return false;
    }
  }

  function flushOutbox() {
    while (nativePortOutbox.length) {
      if (!tryPost(nativePortOutbox[0])) return;
      nativePortOutbox.shift();
    }
  }

  /* The one sender every background report uses. Posts
   * on the live port when there is one; otherwise queues
   * (oldest dropped past the cap — probe reports are
   * cumulative, so the freshest supersedes) and tries to
   * connect. Never blocks, never waits on a reply. */
  function postNative(msg) {
    if (tryPost(msg)) return;
    if (nativePortOutbox.length >= nativePortOutboxCap) {
      nativePortOutbox.shift();
    }
    nativePortOutbox.push(msg);
    connectPort();
  }

  function pageHostForTab(tabId, fallbackHost) {
    if (typeof browser === "undefined" || !browser.tabs || !browser.tabs.get ||
        tabId === null || tabId === undefined || tabId < 0) {
      return Promise.resolve(fallbackHost || "");
    }
    return browser.tabs.get(tabId).then(function (tab) {
      return hostFromUrl(tab.url || "") || fallbackHost || "";
    }).catch(function () { return fallbackHost || ""; });
  }

  function reportObservation(type, url, kind, category, tabId, pageHost, mime, contentLength) {
    var msg = { type: type, url: url, tabId: tabId, pageHost: pageHost || "" };
    if (kind) msg.kind = kind;
    if (category !== null && category !== undefined) msg.category = category;
    if (mime) msg.mime = mime;
    if (contentLength) msg.contentLength = contentLength;
    postNative(msg);
  }

  /* ---- the webRequest listener ---- */

  function onBeforeRequest(details) {
    var url = details.url || "";
    if (!url || (url.indexOf("http://") !== 0 && url.indexOf("https://") !== 0)) {
      return {};
    }
    var method = (details.method || "GET").toUpperCase();
    var isMainFrame = details.type === "main_frame";
    /* The page this request belongs to: originUrl first
     * (synchronous — the blocking decision cannot wait for
     * browser.tabs.get), documentUrl next, "" for a top-level
     * navigation with no opener. */
    var pageHost = hostFromUrl(details.originUrl || details.documentUrl || "");
    var originHost = hostFromUrl(details.originUrl || "");
    var urlHost = hostFromUrl(url);

    /* Media observation runs for every request, blocked or not:
     * a segment URL is still a sighting even when some other
     * rule would cancel it. Attribution is async; the report
     * goes out once the tab's host is known (or the fallback). */
    var kind = mediaKindFor(url);
    if (kind) {
      pageHostForTab(details.tabId, pageHost).then(function (host) {
        reportObservation("media-net", url, kind, null, details.tabId, host);
      });
    }

    /* GET/HEAD only — POST/PUT uploads and sign-ins are never
     * blocked (Mariner Phase G repair). */
    if (method !== "GET" && method !== "HEAD") return {};

    /* Per-site exception: blocking is off for this page host.
     * For a main-frame hop the "page" is the opener
     * (originUrl); if either the opener or the target host is
     * excepted, the load stands. uBlock is NOT disabled by
     * this — only this layer, ETP and cosmetics. */
    if (matchesSet(rules.disabledHosts, pageHost) ||
        (isMainFrame && matchesSet(rules.disabledHosts, urlHost))) {
      return {};
    }

    if (isMainFrame) {
      /* Main-frame loads are NEVER cancelled here: this layer
       * has no user-gesture signal, so a $document cancel
       * blocked deliberately tapped links too, silently (no
       * notice, no allow/deny). Mariner's popunder guard
       * applies only to gesture-less hops; the engine's
       * onLoadRequest guard has the gesture signal and
       * enforces exactly that rule. A tapped link to a
       * $document host therefore loads, as in Mariner. */
      return {};
    }

    /* Batch 4: per-site image blocking — an image request
     * on a host whose SiteSetting.blockImages is on is
     * cancelled. The page host (originUrl/documentUrl) is
     * the site the user blocked images on, not the image's
     * own host. */
    if (details.type === "image" &&
        matchesSet(rules.blockImagesHosts, pageHost)) {
      return { cancel: true };
    }

    /* Subresource blocking: Mariner's lists, second layer. The
     * toggles live in the app; the background script blocks
     * when EITHER is on (the Kotlin side applies the same
     * flags per request — blockAds gates user rules and
     * pathKeywords, blockTrackers gates the builtin set). */
    var category = blockCategory(url, false, flags.blockAds, flags.blockTrackers, pageHost);
    if (category !== null) {
      pageHostForTab(details.tabId, pageHost).then(function (host) {
        reportObservation("blocked", url, null, category, details.tabId, host);
      });
      return { cancel: true };
    }
    return {};
  }

  /* Referer policy (Batch 4): "never" strips the Referer
   *  header, "origin" truncates it to scheme://host/.
   *  "Default" (empty) leaves it alone. */
  function onBeforeSendHeaders(details) {
    var policy = flags.refererPolicy || "";
    if (!policy) return {};
    var headers = details.requestHeaders || [];
    for (var i = 0; i < headers.length; i++) {
      if (headers[i].name.toLowerCase() === "referer") {
        if (policy === "never") {
          headers.splice(i, 1);
        } else if (policy === "origin") {
          try {
            var u = new URL(details.url);
            headers[i].value = u.protocol + "//" + u.host + "/";
          } catch (e) {
            headers.splice(i, 1);
          }
        }
        break;
      }
    }
    return { requestHeaders: headers };
  }

  /* Response-header observation (alpha.47): the
   * onBeforeRequest observer is URL-only, so extension-less
   * media URLs never match. onHeadersReceived sees the
   * response Content-Type (and Content-Length), which
   * catches them. The report is the same `media-net`
   * message with two new fields: `mime` and
   * `contentLength`. MediaLog dedupes by URL, so a URL
   * already reported by onBeforeRequest is not listed
   * twice. Observation only — nothing is cancelled or
   * modified here. */
  function onHeadersReceived(details) {
    var url = details.url || "";
    if (!url || (url.indexOf("http://") !== 0 && url.indexOf("https://") !== 0)) {
      return {};
    }
    var headers = details.responseHeaders || [];
    var contentType = "";
    var contentLength = "";
    for (var i = 0; i < headers.length; i++) {
      var name = String(headers[i].name || "").toLowerCase();
      if (name === "content-type") contentType = String(headers[i].value || "");
      else if (name === "content-length") contentLength = String(headers[i].value || "");
    }
    var kind = mediaKindFor(url, contentType, contentLength);
    if (!kind) return {};
    var pageHost = hostFromUrl(details.originUrl || details.documentUrl || "");
    pageHostForTab(details.tabId, pageHost).then(function (host) {
      reportObservation("media-net", url, kind, null, details.tabId, host, contentType, contentLength);
    });
    return {};
  }

  /* ---- startup: native port + initial rules ---- */

  /* Re-ask for the rule set on the port. Fire-and-forget:
   * the app answers with a `rules-update` port message
   * (and also pushes one at connect), but no reply is
   * ever waited on — if none comes, the defaults stand
   * and the next app-side push delivers the rules, so a
   * missing reply can never stall this script. Called
   * from connectPort() on every (re)connection, never
   * directly. */
  function requestRules() {
    tryPost({ type: "rules-request" });
  }

  /* Open the native port and keep it: every background
   * report goes out on it (see postNative). One
   * handshake per connection, posted on the live port
   * itself, then the queued reports flush, then the
   * rules are re-asked (the app also pushes them at
   * connect — this request is a re-ask only).
   * Idempotent: a live port is never re-opened. A
   * disconnect clears the reference, so the next
   * report reconnects, re-handshakes and re-asks. */
  function connectPort() {
    if (nativePort) return;
    try {
      if (typeof browser !== "undefined" && browser.runtime &&
          browser.runtime.connectNative) {
        var port = browser.runtime.connectNative("pioneer");
        nativePort = port;
        port.onMessage.addListener(function (msg) {
          if (msg && msg.type === "rules-update" && msg.rules) {
            applyRules(msg);
            // Alpha.62: re-post the handshake here. The
            // first handshake goes out inside connectPort,
            // but a handshake posted before the app-side
            // Port exists is dropped (GeckoView creates
            // the app Port only once the Connect reaches
            // a registered delegate), so a rules-update —
            // which the app only pushes after onConnect —
            // proves the port is live, and this re-post is
            // guaranteed to land. Idempotent: the app just
            // sets the background state true.
            tryPost({ type: "background-alive" });
          } else if (msg && msg.type === "cookies-request" &&
              msg.id && msg.url) {
            // Alpha.64: the download job asks for one
            // host's cookies; the reply goes back on this
            // same port. No flag gates it — this is
            // production wiring, not an experiment.
            handleCookiesRequest(msg);
          }
        });
        if (port.onDisconnect && port.onDisconnect.addListener) {
          port.onDisconnect.addListener(function () {
            if (nativePort === port) nativePort = null;
          });
        }
        if (!tryPost({ type: "background-alive" })) return;
        flushOutbox();
        requestRules();
      }
    } catch (e) {
      nativePort = null;
    }
  }

  if (typeof browser !== "undefined" && browser.webRequest &&
      browser.webRequest.onBeforeRequest) {
    browser.webRequest.onBeforeRequest.addListener(
      onBeforeRequest,
      { urls: ["<all_urls>"] },
      ["blocking"]
    );
    connectPort();
  }

  if (typeof browser !== "undefined" && browser.webRequest &&
      browser.webRequest.onBeforeSendHeaders) {
    browser.webRequest.onBeforeSendHeaders.addListener(
      onBeforeSendHeaders,
      { urls: ["<all_urls>"] },
      ["blocking", "requestHeaders"]
    );
  }

  /* Response-header observation, guarded exactly like
   * onBeforeRequest above: if GeckoView does not expose
   * onHeadersReceived, nothing changes and the probe keeps
   * working on URL-only observations. The registration is
   * wrapped so a rejected listener cannot take down the
   * state report below it. The app is told the outcome
   * either way (headers-state), so Diagnostics can show
   * `webRequest onHeadersReceived: on/off · N MIME hits`. */
  var headersReceivedOn = false;
  if (typeof browser !== "undefined" && browser.webRequest &&
      browser.webRequest.onHeadersReceived) {
    try {
      browser.webRequest.onHeadersReceived.addListener(
        onHeadersReceived,
        { urls: ["<all_urls>"] },
        ["responseHeaders"]
      );
      headersReceivedOn = true;
    } catch (e) { /* listener rejected; probe keeps working URL-only */ }
  }
  postNative({ type: "headers-state", on: headersReceivedOn });

  /* Node test harness: the matcher is exported without the
   * browser wiring, so the Kotlin↔JS agreement check can run
   * it against the same compiled rule set and sample table. */
  if (typeof module !== "undefined" && module.exports) {
    module.exports = {
      applyRules: applyRules,
      blockCategory: blockCategory,
      isDocumentBlocked: isDocumentBlocked,
      documentCategory: documentCategory,
      mediaKindFor: mediaKindFor,
      mediaKindForMime: mediaKindForMime,
      isSegmentNoise: isSegmentNoise,
      handleCookiesRequest: handleCookiesRequest,
      onBeforeSendHeaders: onBeforeSendHeaders,
      isManifest: isManifest,
      sameSite: sameSite,
      registrableDomain: registrableDomain,
      CAT_ADS: CAT_ADS,
      CAT_TRACKER: CAT_TRACKER,
      CAT_OTHER: CAT_OTHER
    };
  }
})();
