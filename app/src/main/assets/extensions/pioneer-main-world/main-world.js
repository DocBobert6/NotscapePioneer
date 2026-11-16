// Alpha.66 — MAIN-world experiment re-test (gap plan row A-2).
// This script runs in the pioneer-main-world extension, whose
// manifest asks for "world": "MAIN". It does exactly one thing:
// it reports whether it found itself in the page's own (MAIN)
// world. No XHR/fetch patching, no network interception, no
// page modification beyond the one flag and the one event below.
//
// Alpha.52's verdict was a FALSE NEGATIVE, and this rewrite
// is the correction. The old script reported its sentinel via
// the extension native-messaging API — but MAIN-world content
// scripts have NO extension APIs at all (Mozilla's Firefox
// 128 release notes), so the messaging object is undefined
// there, the call threw a ReferenceError the try/catch
// swallowed, and no sentinel ever arrived. The old manifest
// also ran at document_idle (real page-context work needs
// document_start) and carried no host permission. This script
// therefore makes NO extension API calls of any kind — it
// only writes to the page itself, which is what a page script
// can always do:
//   - a window flag: window.__pioneerMainWorldProbe
//   - a CustomEvent: "pioneer-main-world-probe", carrying the
//     same result in its detail.
// The ISOLATED-world probe.js listens for the event and reads
// the flag (it runs later, at document_idle, so the flag is
// the durable path and the event the live path), then relays
// the result to the app — the ISOLATED world HAS the APIs,
// the MAIN world does not.
//
// The discriminator is structural, not API-dependent:
//   - In the ISOLATED world the script runs in a sandbox whose
//     global is NOT the page window, and `window` is an Xray
//     view that still exposes `wrappedJSObject`.
//   - In the MAIN world the script IS a page script: top-level
//     `this` is the page window, and page scripts cannot see
//     `wrappedJSObject`.
// Both checks must agree it is MAIN world, so a disagreement
// reports isolated (conservative — never a false accept).
(function () {
  try {
    var wrappedUndefined = (typeof window.wrappedJSObject === "undefined");
    var thisIsWindow = (this === window);
    var mainWorld = wrappedUndefined && thisIsWindow;
    window.__pioneerMainWorldProbe = { mainWorld: mainWorld };
    window.dispatchEvent(new CustomEvent("pioneer-main-world-probe", {
      detail: { mainWorld: mainWorld }
    }));
  } catch (e) { /* never throw into the page */ }
})();
