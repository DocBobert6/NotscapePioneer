#!/usr/bin/env python3
"""Alpha.63 commit 1 (JS half): cookies-API probe in background.js + manifest.

Each replacement re-reads the file fresh (never a stale snapshot),
asserts the anchor occurs exactly once, and writes. Run from the
staging root: python3 alpha63-patch-c1.py
"""
import sys

BG = "app/src/main/assets/extensions/pioneer-probe/background.js"
MF = "app/src/main/assets/extensions/pioneer-probe/manifest.json"


def replace_once(path, old, new, label):
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()
    n = text.count(old)
    if n != 1:
        print(f"FAIL {label}: anchor occurs {n} times (want 1)")
        sys.exit(1)
    text = text.replace(old, new)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"ok {label}")


# 1. flags: add cookiesApi (default OFF).
replace_once(
    BG,
    '  var flags = { blockAds: true, blockTrackers: true, refererPolicy: "", cookieCapture: false };',
    '  var flags = { blockAds: true, blockTrackers: true, refererPolicy: "", cookieCapture: false, cookiesApi: false };',
    "flags.cookiesApi",
)

# 2. Re-probe timer variable, beside the cookie-probe state.
replace_once(
    BG,
    "  var cookieProbe = freshCookieProbe();\n",
    "  var cookieProbe = freshCookieProbe();\n"
    "\n"
    "  /* Cookies-API experiment (alpha.63): the re-probe timer\n"
    "   * for a main-frame navigation — cookies can arrive late,\n"
    "   * so the probe runs again 10 s after the load. One timer\n"
    "   * at a time; the next navigation clears it. Null when no\n"
    "   * re-probe is pending. */\n"
    "  var cookiesApiTimer = null;\n",
    "cookiesApiTimer var",
)

# 3. applyRules: read r.cookiesApi; flip-off cancels the timer.
replace_once(
    BG,
    "      if (wasCapture && !flags.cookieCapture) cookieObs = freshCookieObs();\n"
    "    }\n",
    "      if (wasCapture && !flags.cookieCapture) cookieObs = freshCookieObs();\n"
    "    }\n"
    "    if (r.cookiesApi !== undefined) {\n"
    "      flags.cookiesApi = !!r.cookiesApi;\n"
    "      /* Flipping the experiment off cancels the pending\n"
    "       * re-probe, so a stale timer cannot probe after the\n"
    "       * flag went off (probeCookiesApi also checks the\n"
    "       * flag, so this is belt and braces). */\n"
    "      if (!flags.cookiesApi && cookiesApiTimer) {\n"
    "        clearTimeout(cookiesApiTimer);\n"
    "        cookiesApiTimer = null;\n"
    "      }\n"
    "    }\n",
    "applyRules.cookiesApi",
)

# 4. The probe itself, before the native-messaging section.
PROBE = """  /* Cookies-API experiment (alpha.63): does GeckoView
   * expose browser.cookies to the background script, and
   * do the tab's cookies live in the default store or a
   * per-session store? The API is present in GeckoView 160
   * (schema cookies.json + ext-cookies.js in the shipped
   * omni.ja, permission "cookies", no GeckoView gating),
   * and getAll returns HttpOnly cookies with values — but
   * it defaults to the default store (userContextId 0),
   * and Pioneer's tabs may use per-session context IDs.
   * So the probe queries the default store (no storeId)
   * AND every listed store by id, and reports per-store
   * counts + names. That is the one question this
   * experiment settles; nothing is wired into downloads.
   *
   * SECURITY — names only, NEVER values: the report
   * carries cookie NAMES (e.g. "SID, HSID, SSID") and
   * per-store counts. No cookie value is read into a
   * report, logged, stored, persisted, or sent anywhere.
   * A Diagnostics line showing names proves delivery
   * without ever touching a secret. */
  var COOKIES_API_MAX_STORES = 5;
  var COOKIES_API_MAX_NAMES = 8;

  /* Names of the cookies in one getAll result, deduped
   * and capped. Reads .name only — never .value. */
  function cookieNamesOf(cookies) {
    var names = [];
    var seen = {};
    for (var i = 0; i < (cookies || []).length; i++) {
      var name = String((cookies[i] || {}).name || "");
      if (name && !seen[name]) { seen[name] = 1; names.push(name); }
    }
    return names.slice(0, COOKIES_API_MAX_NAMES);
  }

  /* One probe run for pageUrl: default store + every
   * listed store. Reports {api, host, stores} on the
   * port, or {api: false} when browser.cookies is not
   * exposed here at all, or {api: true, error} when a
   * getAll call throws (e.g. a missing host permission).
   * A failing store listing falls back to the default
   * store only; a failing non-default store reports as
   * empty for that store; only a failing default-store
   * query is an error, because that is the query the
   * download job would make. Never throws. */
  function probeCookiesApi(pageUrl) {
    if (!flags.cookiesApi) return;
    var host = hostFromUrl(pageUrl || "");
    if (typeof browser === "undefined" || !browser.cookies ||
        typeof browser.cookies.getAll !== "function") {
      postNative({ type: "cookie-api-probe", api: false, host: host });
      return;
    }
    var storesPromise = null;
    try {
      storesPromise = (typeof browser.cookies.getAllCookieStores === "function")
        ? browser.cookies.getAllCookieStores()
        : Promise.resolve([]);
    } catch (e) {
      postNative({
        type: "cookie-api-probe", api: true, host: host,
        error: (e && e.name) || "Error"
      });
      return;
    }
    Promise.resolve(storesPromise).catch(function () { return []; }).then(function (stores) {
      var queries = [];
      queries.push(browser.cookies.getAll({ url: pageUrl }).then(function (cs) {
        return { storeId: "default", names: cookieNamesOf(cs) };
      }));
      (stores || []).forEach(function (s) {
        var id = String((s || {}).id || "");
        /* The default store is already queried above
         * (without a storeId); the private store throws
         * for an extension without private-browsing
         * access, so neither is queried again here. */
        if (!id || id === "firefox-default" || id === "firefox-private") return;
        queries.push(browser.cookies.getAll({ url: pageUrl, storeId: id }).then(function (cs) {
          return { storeId: id, names: cookieNamesOf(cs) };
        }, function () {
          return { storeId: id, names: [] };
        }));
      });
      return Promise.all(queries);
    }).then(function (results) {
      postNative({
        type: "cookie-api-probe", api: true, host: host,
        stores: (results || []).slice(0, COOKIES_API_MAX_STORES)
      });
    }).catch(function (e) {
      postNative({
        type: "cookie-api-probe", api: true, host: host,
        error: (e && e.name) || "Error"
      });
    });
  }

"""
replace_once(
    BG,
    "  /* ---- native messaging: the background port ---- */\n",
    PROBE + "  /* ---- native messaging: the background port ---- */\n",
    "probeCookiesApi fn",
)

# 5. Trigger: every main-frame navigation + a 10 s re-probe.
replace_once(
    BG,
    '    var kind = mediaKindFor(url);\n'
    '    if (kind) {\n'
    '      pageHostForTab(details.tabId, pageHost).then(function (host) {\n'
    '        reportObservation("media-net", url, kind, null, details.tabId, host);\n'
    '      });\n'
    '    }\n'
    '\n'
    '    /* GET/HEAD only — POST/PUT uploads and sign-ins are never\n',
    '    var kind = mediaKindFor(url);\n'
    '    if (kind) {\n'
    '      pageHostForTab(details.tabId, pageHost).then(function (host) {\n'
    '        reportObservation("media-net", url, kind, null, details.tabId, host);\n'
    '      });\n'
    '    }\n'
    '\n'
    '    /* Cookies-API experiment (alpha.63): on every main-frame\n'
    '     * navigation, ask the cookies API for this page\'s\n'
    '     * cookies — and again 10 s later, because cookies can\n'
    '     * arrive late. Runs for any method: a POST navigation\n'
    '     * is still a navigation, and the probe reads cookies,\n'
    '     * never the request body. */\n'
    '    if (isMainFrame && flags.cookiesApi) {\n'
    '      probeCookiesApi(url);\n'
    '      if (cookiesApiTimer) clearTimeout(cookiesApiTimer);\n'
    '      cookiesApiTimer = setTimeout(function () { probeCookiesApi(url); }, 10000);\n'
    '    }\n'
    '\n'
    '    /* GET/HEAD only — POST/PUT uploads and sign-ins are never\n',
    "main-frame trigger",
)

# 6. Node harness exports.
replace_once(
    BG,
    "      probeCookieListener: probeCookieListener,\n",
    "      probeCookieListener: probeCookieListener,\n"
    "      probeCookiesApi: probeCookiesApi,\n"
    "      cookieNamesOf: cookieNamesOf,\n",
    "harness exports",
)

# 7. Manifest: the cookies permission + host permission for
#    the queried URL (the cookies API needs both).
replace_once(
    MF,
    '"permissions":["nativeMessaging","nativeMessagingFromContent","geckoViewAddons","webRequest","webRequestBlocking","tabs"]',
    '"permissions":["nativeMessaging","nativeMessagingFromContent","geckoViewAddons","webRequest","webRequestBlocking","tabs","cookies","*://*/*"]',
    "manifest permissions",
)

print("all alpha.63 c1 JS patches applied")
