"use strict";
/* Alpha.63 scratch harness — validates the cookies-API probe
 * in background.js with a stubbed browser global.
 * NOT shipped; the committed tests are
 * CookieApiExperimentTest.kt (Kotlin half) plus shape pins.
 * Run: node alpha63-verify/cookies-api-harness.js
 * — exits non-zero on any failure. */
const path = require("path");
const BG = path.join(__dirname, "..", "app", "src", "main", "assets",
  "extensions", "pioneer-probe", "background.js");

let checks = 0, failures = 0;
function check(name, actual, expected) {
  checks++;
  const a = JSON.stringify(actual), e = JSON.stringify(expected);
  if (a === e) {
    console.log("ok   " + name);
  } else {
    failures++;
    console.log("FAIL " + name + " — got " + a + ", want " + e);
  }
}

function tick(n) {
  let p = Promise.resolve();
  for (let i = 0; i < n; i++) {
    p = p.then(() => new Promise((r) => setImmediate(r)));
  }
  return p;
}

/* setTimeout is captured, never run live: the harness
 * invokes recorded callbacks itself to simulate the
 * 10 s re-probe. Reset per load. */
let timers = [];
global.setTimeout = (fn, ms) => {
  const id = timers.length;
  timers.push({ fn: fn, ms: ms, cleared: false });
  return id;
};
global.clearTimeout = (id) => {
  if (timers[id]) timers[id].cleared = true;
};
function runTimers() {
  for (const t of timers) {
    if (!t.cleared) t.fn();
  }
}

/* spec: { noApi, list, byStore, listRejects, defaultRejects,
 * rejectStore } — byStore maps store key ("default" or a
 * store id) to [{name, value}] cookies. Values are present
 * in the mock so a leak is detectable. */
function makeCookies(spec) {
  return {
    getAllCookieStores: () => spec.listRejects
      ? Promise.reject(new Error("list failed"))
      : Promise.resolve((spec.list || []).map((s) => ({ id: s }))),
    getAll: (details) => {
      if (!details.storeId && spec.defaultRejects) {
        return Promise.reject(spec.defaultRejects);
      }
      if (details.storeId && spec.rejectStore === details.storeId) {
        return Promise.reject(new Error("store failed"));
      }
      const key = details.storeId || "default";
      return Promise.resolve((spec.byStore[key] || []).map((c) => ({
        name: c.name, value: c.value
      })));
    }
  };
}

function makeBrowser(spec) {
  const sent = [];
  const listeners = {};
  const stub = {
    webRequest: {
      onBeforeRequest: { addListener: (fn) => { listeners.onBeforeRequest = fn; } },
      onBeforeSendHeaders: { addListener: (fn) => { listeners.onBeforeSendHeaders = fn; } },
      onHeadersReceived: { addListener: (fn) => { listeners.onHeadersReceived = fn; } }
    },
    tabs: {
      get: () => {
        const p = {
          then: (cb) => { cb({ url: "https://www.youtube.com/" }); return p; },
          catch: () => p
        };
        return p;
      }
    },
    runtime: {
      sendNativeMessage: (name, msg) => {
        sent.push(msg);
        return {
          then: (cb) => { try { cb(null); } catch (e) {} return { catch: () => {} }; },
          catch: () => {}
        };
      },
      connectNative: () => {
        const port = {
          onMessage: { addListener: (fn) => { port._onMessage = fn; } },
          onDisconnect: { addListener: () => {} },
          postMessage: (msg) => { sent.push(msg); }
        };
        return port;
      }
    }
  };
  if (!spec.noApi) stub.cookies = makeCookies(spec);
  return { stub, sent, listeners };
}

function load(spec) {
  timers = [];
  const made = makeBrowser(spec || {});
  global.browser = made.stub;
  delete require.cache[require.resolve(BG)];
  const bg = require(BG);
  return { bg, sent: made.sent, listeners: made.listeners };
}

function ofType(sent, type) {
  return sent.filter((m) => m && m.type === type);
}

function details(url, opts) {
  const o = opts || {};
  return {
    url: url,
    originUrl: o.originUrl || "https://www.youtube.com/",
    documentUrl: o.documentUrl || "",
    type: o.type || "xmlhttprequest",
    tabId: 1,
    method: "GET",
    requestHeaders: o.headers || []
  };
}

const PAGE = "https://www.youtube.com/watch?v=mXI-acwMl4c";
const MAIN = { type: "main_frame" };

function noSecrets(sent) {
  return sent.every((m) => JSON.stringify(m).indexOf("SECRET") === -1);
}

async function main() {
  // ---- Flag OFF: no probe on a main-frame navigation ----
  {
    const { bg, sent, listeners } = load({
      list: ["firefox-default"],
      byStore: { "default": [{ name: "SID", value: "SECRET_SID" }] }
    });
    listeners.onBeforeRequest(details(PAGE, MAIN));
    await tick(5);
    check("off: no probe message",
      ofType(sent, "cookie-api-probe").length, 0);
    check("off: no timer set", timers.length, 0);
  }

  // ---- Flag ON: default-store names, no values leak ----
  {
    const { bg, sent, listeners } = load({
      list: ["firefox-default", "firefox-container-1"],
      byStore: {
        "default": [
          { name: "SID", value: "SECRET_SID" },
          { name: "HSID", value: "SECRET_HSID" }
        ],
        "firefox-container-1": []
      }
    });
    bg.applyRules({ cookiesApi: true });
    listeners.onBeforeRequest(details(PAGE, MAIN));
    await tick(5);
    const probes = ofType(sent, "cookie-api-probe");
    check("default: one probe message", probes.length, 1);
    check("default: api is true", probes[0] && probes[0].api, true);
    check("default: host is the page host",
      probes[0] && probes[0].host, "www.youtube.com");
    check("default: stores reported", probes[0] && probes[0].stores, [
      { storeId: "default", names: ["SID", "HSID"] },
      { storeId: "firefox-container-1", names: [] }
    ]);
    check("default: no error field",
      probes[0] && ("error" in probes[0]), false);
    check("default: no cookie value anywhere in any message",
      noSecrets(sent), true);
  }

  // ---- Session store only: names surface from the container ----
  {
    const { bg, sent, listeners } = load({
      list: ["firefox-default", "firefox-container-1"],
      byStore: {
        "default": [],
        "firefox-container-1": [{ name: "SSID", value: "SECRET_SSID" }]
      }
    });
    bg.applyRules({ cookiesApi: true });
    listeners.onBeforeRequest(details(PAGE, MAIN));
    await tick(5);
    const probes = ofType(sent, "cookie-api-probe");
    check("session: one probe message", probes.length, 1);
    check("session: default store empty",
      probes[0] && probes[0].stores[0], { storeId: "default", names: [] });
    check("session: container store carries the name",
      probes[0] && probes[0].stores[1],
      { storeId: "firefox-container-1", names: ["SSID"] });
    check("session: no cookie value anywhere", noSecrets(sent), true);
  }

  // ---- All empty: api true, every store zero ----
  {
    const { bg, sent, listeners } = load({
      list: ["firefox-default", "firefox-container-1"],
      byStore: { "default": [], "firefox-container-1": [] }
    });
    bg.applyRules({ cookiesApi: true });
    listeners.onBeforeRequest(details(PAGE, MAIN));
    await tick(5);
    const probes = ofType(sent, "cookie-api-probe");
    check("empty: one probe message", probes.length, 1);
    check("empty: api is true", probes[0] && probes[0].api, true);
    check("empty: both stores zero", probes[0] && probes[0].stores, [
      { storeId: "default", names: [] },
      { storeId: "firefox-container-1", names: [] }
    ]);
  }

  // ---- API absent: browser.cookies undefined ----
  {
    const { bg, sent, listeners } = load({ noApi: true });
    bg.applyRules({ cookiesApi: true });
    listeners.onBeforeRequest(details(PAGE, MAIN));
    await tick(5);
    const probes = ofType(sent, "cookie-api-probe");
    check("absent: one probe message", probes.length, 1);
    check("absent: api is false", probes[0] && probes[0].api, false);
    check("absent: host carried",
      probes[0] && probes[0].host, "www.youtube.com");
    check("absent: no stores key",
      probes[0] && ("stores" in probes[0]), false);
  }

  // ---- Default getAll throws: error reported, not zero names ----
  {
    const { bg, sent, listeners } = load({
      list: ["firefox-default"],
      byStore: { "default": [{ name: "SID", value: "SECRET_SID" }] },
      defaultRejects: (() => { const e = new Error("denied"); e.name = "SecurityError"; return e; })()
    });
    bg.applyRules({ cookiesApi: true });
    listeners.onBeforeRequest(details(PAGE, MAIN));
    await tick(5);
    const probes = ofType(sent, "cookie-api-probe");
    check("error: one probe message", probes.length, 1);
    check("error: api is true", probes[0] && probes[0].api, true);
    check("error: error name carried",
      probes[0] && probes[0].error, "SecurityError");
    check("error: no stores key",
      probes[0] && ("stores" in probes[0]), false);
    check("error: no cookie value anywhere", noSecrets(sent), true);
  }

  // ---- 10 s timer: re-probe fires once ----
  {
    const { bg, sent, listeners } = load({
      list: ["firefox-default"],
      byStore: { "default": [{ name: "SID", value: "SECRET_SID" }] }
    });
    bg.applyRules({ cookiesApi: true });
    listeners.onBeforeRequest(details(PAGE, MAIN));
    await tick(5);
    check("timer: one probe after nav",
      ofType(sent, "cookie-api-probe").length, 1);
    check("timer: exactly one timer set", timers.length, 1);
    check("timer: 10 s delay", timers[0] && timers[0].ms, 10000);
    runTimers();
    await tick(5);
    check("timer: second probe after the re-probe",
      ofType(sent, "cookie-api-probe").length, 2);
  }

  // ---- Flip off cancels the pending re-probe ----
  {
    const { bg, sent, listeners } = load({
      list: ["firefox-default"],
      byStore: { "default": [{ name: "SID", value: "SECRET_SID" }] }
    });
    bg.applyRules({ cookiesApi: true });
    listeners.onBeforeRequest(details(PAGE, MAIN));
    await tick(5);
    bg.applyRules({ cookiesApi: false });
    runTimers();
    await tick(5);
    check("flip-off: still one probe after the cancelled timer",
      ofType(sent, "cookie-api-probe").length, 1);
  }

  // ---- Caps: 8 names per store, 5 stores per report ----
  {
    const many = [];
    for (let i = 0; i < 10; i++) {
      many.push({ name: "C" + i, value: "SECRET_" + i });
    }
    const { bg, sent, listeners } = load({
      list: ["firefox-default", "c1", "c2", "c3", "c4", "c5", "c6"],
      byStore: {
        "default": many,
        "c1": [{ name: "A", value: "SECRET_A" }],
        "c2": [], "c3": [], "c4": [], "c5": [], "c6": []
      }
    });
    bg.applyRules({ cookiesApi: true });
    listeners.onBeforeRequest(details(PAGE, MAIN));
    await tick(5);
    const probes = ofType(sent, "cookie-api-probe");
    check("caps: one probe message", probes.length, 1);
    check("caps: at most 5 stores",
      probes[0] && probes[0].stores.length, 5);
    check("caps: first store is the default",
      probes[0] && probes[0].stores[0].storeId, "default");
    check("caps: names capped at 8",
      probes[0] && probes[0].stores[0].names,
      ["C0", "C1", "C2", "C3", "C4", "C5", "C6", "C7"]);
    check("caps: no cookie value anywhere", noSecrets(sent), true);
  }

  // ---- Store listing fails: default-only report, not an error ----
  {
    const { bg, sent, listeners } = load({
      listRejects: true,
      byStore: { "default": [{ name: "SID", value: "SECRET_SID" }] }
    });
    bg.applyRules({ cookiesApi: true });
    listeners.onBeforeRequest(details(PAGE, MAIN));
    await tick(5);
    const probes = ofType(sent, "cookie-api-probe");
    check("list-fail: one probe message", probes.length, 1);
    check("list-fail: api is true", probes[0] && probes[0].api, true);
    check("list-fail: default store only", probes[0] && probes[0].stores, [
      { storeId: "default", names: ["SID"] }
    ]);
    check("list-fail: no error field",
      probes[0] && ("error" in probes[0]), false);
  }

  // ---- One failing non-default store reports as empty ----
  {
    const { bg, sent, listeners } = load({
      list: ["firefox-default", "firefox-container-1"],
      byStore: { "default": [{ name: "SID", value: "SECRET_SID" }] },
      rejectStore: "firefox-container-1"
    });
    bg.applyRules({ cookiesApi: true });
    listeners.onBeforeRequest(details(PAGE, MAIN));
    await tick(5);
    const probes = ofType(sent, "cookie-api-probe");
    check("store-fail: one probe message", probes.length, 1);
    check("store-fail: api is true", probes[0] && probes[0].api, true);
    check("store-fail: failing store is empty", probes[0] && probes[0].stores, [
      { storeId: "default", names: ["SID"] },
      { storeId: "firefox-container-1", names: [] }
    ]);
    check("store-fail: no error field",
      probes[0] && ("error" in probes[0]), false);
  }

  // ---- Subresource requests never probe ----
  {
    const { bg, sent, listeners } = load({
      list: ["firefox-default"],
      byStore: { "default": [{ name: "SID", value: "SECRET_SID" }] }
    });
    bg.applyRules({ cookiesApi: true });
    for (let i = 0; i < 5; i++) {
      listeners.onBeforeRequest(details("https://www.youtube.com/api/stats", {}));
    }
    await tick(5);
    check("subresource: no probe message",
      ofType(sent, "cookie-api-probe").length, 0);
    check("subresource: no timer set", timers.length, 0);
  }

  console.log("\n" + checks + " checks, " + failures + " failures");
  process.exit(failures ? 1 : 0);
}

main();
