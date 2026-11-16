"use strict";
/* Alpha.64 scratch harness — validates the cookies-request /
 * cookies-response round trip in background.js with a stubbed
 * browser global. NOT shipped; the committed tests are
 * CookieRequesterTest.kt (Kotlin half) plus shape pins.
 * Run: node alpha64-verify/cookies-wiring-harness.js
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

let timers = [];
global.setTimeout = (fn, ms) => {
  const id = timers.length;
  timers.push({ fn: fn, ms: ms, cleared: false });
  return id;
};
global.clearTimeout = (id) => {
  if (timers[id]) timers[id].cleared = true;
};

/* spec: { noApi, byHost, rejectHost, throwSync } — byHost maps
 * a URL host to [{name, value}] cookies. Values are present in
 * the mock so a leak is detectable. */
function makeCookies(spec) {
  return {
    getAll: (details) => {
      if (spec.throwSync) throw new Error("sync boom");
      const host = String(details.url || "").split("/")[2] || "";
      if (spec.rejectHost === host) {
        return Promise.reject(new Error("denied"));
      }
      return Promise.resolve(
        ((spec.byHost || {})[host] || []).map((c) => ({
          name: c.name, value: c.value
        }))
      );
    }
  };
}

function makeBrowser(spec) {
  const sent = [];
  const listeners = {};
  const made = { sent: sent, listeners: listeners, port: null };
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
        made.port = port;
        return port;
      }
    }
  };
  if (!spec.noApi) stub.cookies = makeCookies(spec);
  return { stub: stub, made: made };
}

function load(spec) {
  timers = [];
  const outer = makeBrowser(spec || {});
  global.browser = outer.stub;
  delete require.cache[require.resolve(BG)];
  const bg = require(BG);
  return { bg: bg, sent: outer.made.sent, listeners: outer.made.listeners, port: outer.made.port };
}

function ofType(sent, type) {
  return sent.filter((m) => m && m.type === type);
}

function secretHolders(sent) {
  return sent.filter((m) => JSON.stringify(m).indexOf("SECRET") !== -1);
}

async function main() {
  // ---- Values flow + id echo, end to end through the port ----
  {
    const { bg, sent, port } = load({
      byHost: {
        "media.example.com": [
          { name: "SID", value: "SECRET_SID" },
          { name: "HSID", value: "SECRET_HSID" }
        ],
        "other.example.org": [{ name: "X", value: "SECRET_X" }]
      }
    });
    port._onMessage({
      type: "cookies-request", id: "r1",
      url: "https://media.example.com/video.m3u8"
    });
    await tick(5);
    const resps = ofType(sent, "cookies-response");
    check("flow: one response", resps.length, 1);
    check("flow: id echoes", resps[0] && resps[0].id, "r1");
    check("flow: url echoes",
      resps[0] && resps[0].url, "https://media.example.com/video.m3u8");
    check("flow: values flow with names", resps[0] && resps[0].cookies, [
      { name: "SID", value: "SECRET_SID" },
      { name: "HSID", value: "SECRET_HSID" }
    ]);
    check("flow: no error field",
      resps[0] && ("error" in resps[0]), false);
    check("flow: host B's cookies never in the reply",
      JSON.stringify(resps[0]).indexOf("SECRET_X"), -1);
  }

  // ---- Host isolation: a request for B returns only B's cookies ----
  {
    const { bg, sent, port } = load({
      byHost: {
        "a.example.com": [{ name: "A1", value: "SECRET_A1" }],
        "b.example.com": [{ name: "B1", value: "SECRET_B1" }]
      }
    });
    port._onMessage({
      type: "cookies-request", id: "r2",
      url: "https://b.example.com/seg-1.ts"
    });
    await tick(5);
    const resps = ofType(sent, "cookies-response");
    check("isolation: one response", resps.length, 1);
    check("isolation: only host B's cookies", resps[0] && resps[0].cookies, [
      { name: "B1", value: "SECRET_B1" }
    ]);
  }

  // ---- API absent: error "unavailable", empty cookies ----
  {
    const { bg, sent, port } = load({ noApi: true });
    port._onMessage({
      type: "cookies-request", id: "r3",
      url: "https://media.example.com/video.m3u8"
    });
    await tick(5);
    const resps = ofType(sent, "cookies-response");
    check("absent: one response", resps.length, 1);
    check("absent: error is unavailable",
      resps[0] && resps[0].error, "unavailable");
    check("absent: cookies empty", resps[0] && resps[0].cookies, []);
  }

  // ---- getAll rejects: error name, empty cookies ----
  {
    const { bg, sent, port } = load({
      byHost: { "media.example.com": [{ name: "SID", value: "SECRET_SID" }] },
      rejectHost: "media.example.com"
    });
    port._onMessage({
      type: "cookies-request", id: "r4",
      url: "https://media.example.com/video.m3u8"
    });
    await tick(5);
    const resps = ofType(sent, "cookies-response");
    check("reject: one response", resps.length, 1);
    check("reject: error name carries through",
      resps[0] && resps[0].error, "Error");
    check("reject: cookies empty", resps[0] && resps[0].cookies, []);
  }

  // ---- getAll throws synchronously: error, empty cookies ----
  {
    const { bg, sent, port } = load({
      byHost: { "media.example.com": [{ name: "SID", value: "SECRET_SID" }] },
      throwSync: true
    });
    port._onMessage({
      type: "cookies-request", id: "r5",
      url: "https://media.example.com/video.m3u8"
    });
    await tick(5);
    const resps = ofType(sent, "cookies-response");
    check("throw: one response", resps.length, 1);
    check("throw: error name carries through",
      resps[0] && resps[0].error, "Error");
    check("throw: cookies empty", resps[0] && resps[0].cookies, []);
  }

  // ---- Malformed request: no id / no url → no response, no throw ----
  {
    const { bg, sent, port } = load({
      byHost: { "media.example.com": [{ name: "SID", value: "SECRET_SID" }] }
    });
    const before = ofType(sent, "cookies-response").length;
    port._onMessage({ type: "cookies-request", url: "https://media.example.com/x" });
    port._onMessage({ type: "cookies-request", id: "r6" });
    port._onMessage({ type: "cookies-request" });
    await tick(5);
    check("malformed: no response for a missing id/url",
      ofType(sent, "cookies-response").length, before);
  }

  // ---- Values leak nowhere else: only the cookies-response
  // carries a SECRET, and the rules-update branch still works ----
  {
    const { bg, sent, port } = load({
      byHost: { "media.example.com": [{ name: "SID", value: "SECRET_SID" }] }
    });
    port._onMessage({
      type: "cookies-request", id: "r7",
      url: "https://media.example.com/video.m3u8"
    });
    await tick(5);
    const holders = secretHolders(sent);
    check("leak: exactly one message carries a value",
      holders.length, 1);
    check("leak: that message is the cookies-response",
      holders[0] && holders[0].type, "cookies-response");
    const aliveBefore = ofType(sent, "background-alive").length;
    port._onMessage({
      type: "rules-update",
      rules: { hosts: [], trackerHosts: [], docHosts: [], scoped: [] }
    });
    await tick(3);
    check("rules: the rules-update branch still re-posts the handshake",
      ofType(sent, "background-alive").length > aliveBefore, true);
  }

  // ---- Direct handler call: same shape without the port ----
  {
    const { bg, sent } = load({
      byHost: { "cdn.example.net": [{ name: "TOK", value: "SECRET_TOK" }] }
    });
    bg.handleCookiesRequest({
      id: "r8", url: "https://cdn.example.net/init.mp4"
    });
    await tick(5);
    const resps = ofType(sent, "cookies-response");
    check("direct: one response", resps.length, 1);
    check("direct: values flow", resps[0] && resps[0].cookies, [
      { name: "TOK", value: "SECRET_TOK" }
    ]);
  }

  console.log("checks: " + checks + ", failures: " + failures);
  process.exit(failures ? 1 : 0);
}

main();
