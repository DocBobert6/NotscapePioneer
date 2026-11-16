"use strict";
/* Alpha.66 scratch harness — validates the MAIN-world re-test
 * relay: main-world.js sets a window flag + dispatches a
 * CustomEvent (zero extension API calls — MAIN world has
 * none), and probe.js relays it to the app as
 * probe-main-world-ok. NOT shipped; the committed tests are
 * MainWorldExperimentTest.kt (Kotlin verdict state machine +
 * the same shipped-asset shape, so the two copies must
 * agree). Run: node alpha66-verify/main-world-harness.js
 * — exits non-zero on any failure. */
const fs = require("fs");
const path = require("path");
const MAIN = path.join(__dirname, "..", "app", "src", "main", "assets",
  "extensions", "pioneer-main-world", "main-world.js");
const MAIN_MANIFEST = path.join(__dirname, "..", "app", "src", "main", "assets",
  "extensions", "pioneer-main-world", "manifest.json");
const PROBE = path.join(__dirname, "..", "app", "src", "main", "assets",
  "extensions", "pioneer-probe", "probe.js");

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
function freshRequire(p) {
  delete require.cache[require.resolve(p)];
  return require(p);
}

/* ---- main-world.js: static checks ---- */
const mainJs = fs.readFileSync(MAIN, "utf8");
check("main-world.js has zero browser. references",
  (mainJs.match(/browser\./g) || []).length, 0);
check("main-world.js never sends the native message itself",
  mainJs.includes("probe-main-world-ok"), false);
check("main-world.js sets the window flag",
  mainJs.includes("__pioneerMainWorldProbe"), true);
check("main-world.js dispatches the CustomEvent",
  mainJs.includes('CustomEvent("pioneer-main-world-probe"'), true);
check("main-world.js keeps the wrappedJSObject discriminator",
  mainJs.includes('typeof window.wrappedJSObject === "undefined"'), true);
check("main-world.js keeps the this-is-window discriminator",
  mainJs.includes("this === window"), true);
check("main-world.js has no fetch(", mainJs.includes("fetch("), false);
check("main-world.js has no XMLHttpRequest",
  mainJs.includes("XMLHttpRequest"), false);

/* ---- main-world.js manifest ---- */
const manifest = JSON.parse(fs.readFileSync(MAIN_MANIFEST, "utf8"));
check("manifest asks for MAIN world",
  manifest.content_scripts[0].world, "MAIN");
check("manifest runs at document_start",
  manifest.content_scripts[0].run_at, "document_start");
check("manifest carries the host permission",
  manifest.permissions.includes("*://*/*"), true);

/* ---- main-world.js: MAIN-world behaviour ---- */
let dispatched = [];
global.CustomEvent = function (type, opts) {
  this.type = type;
  this.detail = (opts && opts.detail) || null;
};
global.dispatchEvent = function (ev) { dispatched.push(ev); return true; };
global.window = global; // top-level `this` inside the IIFE is the
// global object in sloppy mode, so this is the MAIN-world shape:
// this === window, and wrappedJSObject is undefined on it.
dispatched = [];
delete global.__pioneerMainWorldProbe;
freshRequire(MAIN);
check("MAIN world: flag set, mainWorld true",
  global.__pioneerMainWorldProbe, { mainWorld: true });
check("MAIN world: one CustomEvent dispatched",
  dispatched.map(function (e) { return [e.type, e.detail]; }),
  [["pioneer-main-world-probe", { mainWorld: true }]]);

/* ---- main-world.js: ISOLATED-world behaviour ---- */
dispatched = [];
const isoWindow = {
  wrappedJSObject: {},
  dispatchEvent: function (ev) { dispatched.push(ev); return true; }
};
global.window = isoWindow;
delete global.__pioneerMainWorldProbe;
freshRequire(MAIN);
check("ISOLATED world: flag set, mainWorld false",
  isoWindow.__pioneerMainWorldProbe, { mainWorld: false });
check("ISOLATED world: event detail is false",
  dispatched.map(function (e) { return [e.type, e.detail]; }),
  [["pioneer-main-world-probe", { mainWorld: false }]]);

/* ---- probe.js relay ---- */
function makeProbeStubs(presetFlag, topIsSelf) {
  const sent = [];
  const listeners = {};
  const win = {
    addEventListener: function (type, fn) {
      (listeners[type] = listeners[type] || []).push(fn);
    },
    dispatchEvent: function () { return true; }
  };
  win.top = topIsSelf ? win : {};
  if (presetFlag !== undefined) win.__pioneerMainWorldProbe = presetFlag;
  global.window = win;
  global.location = {
    hostname: "example.com",
    href: "https://example.com/"
  };
  global.document = {
    addEventListener: function () {},
    querySelectorAll: function () { return []; },
    querySelector: function () { return null; },
    documentElement: { style: {}, appendChild: function () {} },
    hidden: false
  };
  global.setTimeout = function () { return 0; };
  global.clearTimeout = function () {};
  global.browser = {
    runtime: {
      sendNativeMessage: function (app, msg) {
        sent.push(msg);
        return {
          then: function (cb) {
            try { cb(null); } catch (e) {}
            return { catch: function () {} };
          },
          catch: function () {}
        };
      },
      connectNative: function () { throw new Error("no port in harness"); }
    }
  };
  return { sent: sent, listeners: listeners, win: win };
}
function relayMessages(sent) {
  return sent.filter(function (m) { return m.type === "probe-main-world-ok"; });
}

/* Flag path: preset before load, relayed once at init. */
let s = makeProbeStubs({ mainWorld: true }, true);
freshRequire(PROBE);
check("probe flag path relays mainWorld true once",
  relayMessages(s.sent), [{ type: "probe-main-world-ok", mainWorld: true }]);

/* Event path: no flag; the listener relays on dispatch. */
s = makeProbeStubs(undefined, true);
freshRequire(PROBE);
check("probe event path: no relay before any event",
  relayMessages(s.sent), []);
check("probe event path: listener installed",
  (s.listeners["pioneer-main-world-probe"] || []).length, 1);
s.listeners["pioneer-main-world-probe"][0]({ detail: { mainWorld: true } });
check("probe event path relays mainWorld true once",
  relayMessages(s.sent), [{ type: "probe-main-world-ok", mainWorld: true }]);

/* Once-only: flag at init + two events = one relay total. */
s = makeProbeStubs({ mainWorld: true }, true);
freshRequire(PROBE);
s.listeners["pioneer-main-world-probe"][0]({ detail: { mainWorld: true } });
s.listeners["pioneer-main-world-probe"][0]({ detail: { mainWorld: false } });
check("probe relay is at most once per page load",
  relayMessages(s.sent).length, 1);
check("probe relay keeps the first result",
  relayMessages(s.sent)[0].mainWorld, true);

/* Malformed event: no boolean in detail relays false. */
s = makeProbeStubs(undefined, true);
freshRequire(PROBE);
s.listeners["pioneer-main-world-probe"][0]({ detail: {} });
check("probe malformed event relays false (never a false accept)",
  relayMessages(s.sent), [{ type: "probe-main-world-ok", mainWorld: false }]);

/* Subframe: no listener, no flag read, no relay. */
s = makeProbeStubs({ mainWorld: true }, false);
freshRequire(PROBE);
check("probe subframe installs no relay listener",
  (s.listeners["pioneer-main-world-probe"] || []).length, 0);
check("probe subframe sends no relay",
  relayMessages(s.sent), []);

console.log(checks + " checks, " + failures + " failures");
process.exit(failures ? 1 : 0);
