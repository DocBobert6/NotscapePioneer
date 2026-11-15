// Alpha.52 — MAIN-world experiment (gap plan row A-2). This
// script runs in the pioneer-main-world extension, whose
// manifest asks for "world": "MAIN". It does exactly one thing:
// it reports whether it found itself in the page's own (MAIN)
// world. No XHR/fetch patching, no network interception, no
// page modification of any kind.
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
    browser.runtime.sendNativeMessage("pioneer", {
      type: "probe-main-world-ok",
      mainWorld: wrappedUndefined && thisIsWindow
    });
  } catch (e) { /* never throw into the page */ }
})();
