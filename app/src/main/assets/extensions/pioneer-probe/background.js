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
 *  STRICT POPUP BLOCKER (`$document` / `$popup`, Beta.63 #64):
 *  main-frame loads to a `$document`-blocked host are cancelled
 *  here too (`isDocumentBlocked`), same as any other list match.
 *  CAVEAT, stated plainly: `webRequest` carries no user-gesture
 *  signal, so this cancellation cannot tell a deliberately tapped
 *  link to a popunder host from a gesture-less popunder hop —
 *  Mariner's guard could (it only ever applied to gesture-less
 *  hops, so a tapped link never broke). In Pioneer a tapped link
 *  to a `$document` host IS blocked (uBlock-style). That is the
 *  steering's explicit order, and it is stricter than Mariner;
 *  the gesture-aware approximation (Worker B, design §8.9) still
 *  runs from `onLocationChange` + these observations for the
 *  scorecard row K panel behaviour.
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

  function mediaKindFor(url) {
    if (!url || url.indexOf("blob:") === 0) return null;
    if (!STREAM_RE.test(url)) return null;
    return AUDIO_RE.test(url.toLowerCase()) ? "audio" : "video";
  }

  /* ---- native messaging ---- */

  function sendNative(msg) {
    try {
      if (typeof browser !== "undefined" && browser.runtime &&
          browser.runtime.sendNativeMessage) {
        browser.runtime.sendNativeMessage("pioneer", msg).catch(function () {});
      }
    } catch (e) { /* native bridge not ready */ }
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

  function reportObservation(type, url, kind, category, tabId, pageHost) {
    var msg = { type: type, url: url, tabId: tabId, pageHost: pageHost || "" };
    if (kind) msg.kind = kind;
    if (category !== null && category !== undefined) msg.category = category;
    sendNative(msg);
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
      /* STRICT POPUP BLOCKER: a main-frame load to a
       * $document/$popup host is cancelled. See the header
       * caveat — no gesture signal exists at this layer, so a
       * deliberately tapped link to such a host is blocked too. */
      var docCat = documentCategory(url, originHost);
      if (docCat !== null) {
        pageHostForTab(details.tabId, originHost).then(function (host) {
          reportObservation("blocked", url, null, docCat, details.tabId, host);
        });
        return { cancel: true };
      }
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

  /* ---- startup: native port + initial rules ---- */

  function requestRules() {
    try {
      if (typeof browser !== "undefined" && browser.runtime &&
          browser.runtime.sendNativeMessage) {
        browser.runtime.sendNativeMessage("pioneer", { type: "rules-request" })
          .then(function (reply) {
            if (reply && reply.type === "rules-update" && reply.rules) {
              applyRules(reply);
            }
          })
          .catch(function () { /* app answers on next push */ });
      }
    } catch (e) { /* native bridge not ready */ }
  }

  function connectPort() {
    try {
      if (typeof browser !== "undefined" && browser.runtime &&
          browser.runtime.connectNative) {
        var port = browser.runtime.connectNative("pioneer");
        port.onMessage.addListener(function (msg) {
          if (msg && msg.type === "rules-update" && msg.rules) {
            applyRules(msg);
          }
        });
      }
    } catch (e) { /* live push unavailable; rules-request still applies */ }
  }

  if (typeof browser !== "undefined" && browser.webRequest &&
      browser.webRequest.onBeforeRequest) {
    browser.webRequest.onBeforeRequest.addListener(
      onBeforeRequest,
      { urls: ["<all_urls>"] },
      ["blocking"]
    );
    connectPort();
    requestRules();
  }

  if (typeof browser !== "undefined" && browser.webRequest &&
      browser.webRequest.onBeforeSendHeaders) {
    browser.webRequest.onBeforeSendHeaders.addListener(
      onBeforeSendHeaders,
      { urls: ["<all_urls>"] },
      ["blocking", "requestHeaders"]
    );
  }

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
      sameSite: sameSite,
      registrableDomain: registrableDomain,
      CAT_ADS: CAT_ADS,
      CAT_TRACKER: CAT_TRACKER,
      CAT_OTHER: CAT_OTHER
    };
  }
})();
