# Notscape Pioneer

Notscape Pioneer is Mariner on Gecko — the same app as Notscape Mariner,
with complete parity as the goal, running Mozilla's Gecko engine via
GeckoView instead of the Android System WebView. Extension and add-on
support, with ad blocking (uBlock Origin first), is the biggest thing
Gecko makes possible.

Local-only and private, stated positively. No account, no sync.

## Status: Phase 3 blocking, downloads and media (alpha)

Gate 1 passed on Robert's Pixel 10a (uBlock installs + blocks, Widevine
plays, Cast finds 8 routes, cold start 1491ms). Phase 2 is a browser
Robert can genuinely browse with: tabs, navigation, history, find,
per-site zoom, private tabs, start page, bookmarks, the full menu and
Customize. Phase 3 adds the motivating features: uBlock Origin plus
Mariner's seven filter lists and per-site exceptions, strict popup
blocking, the full downloads stack, media detection with the pop-up
player, fullscreen video, permissions and the file chooser. No reader,
no vault fill yet — those arrive with Phase 4.

- Package: `net.nspioneer.browser`
- Version: `0.1.0-alpha.4` (versionCode 4), debug-signed only
- Engine: GeckoView **159.0.20261102144711** (stable, Firefox 159) —
  pinned, never nightly or beta. arm64-v8a only, minSdk 26.
  Toolchain: AGP 9.4.1 / Kotlin 2.3.21 / Gradle 9.8.1 / compileSdk 36
  (targetSdk 36) — GeckoView 158+ requires compileSdk 37+ and AGP
  9.1.0+, so the Mariner-era compileSdk 36 / AGP 8.13.2 toolchain
  cannot build any current stable GeckoView. The android-37 platform
  itself is not published to Google's public SDK repository yet
  (2026-11-12), so the spike compiles against SDK 36 with the AAR
  metadata check disabled; re-enable it when the platform ships.
  See `app/build.gradle.kts`.
- CI is the compile path (GitHub Actions, `Android CI` workflow).
  Local Gradle does not run in this VM.

## What Phase 2 does

- Tabs backed by one `GeckoSession` each under one `GeckoRuntime`,
  with Enhanced Tracking Protection set to Strict (changeable in
  Settings, applies at once). Tab session restores by URL on launch.
- Private tabs use Gecko private sessions; they are memory-only and
  never recorded.
- History, bookmarks (nested folders, HTML import/export), favourites,
  start page with most-visited tiles — all in a fresh Pioneer-only
  profile. No shared database with Mariner, no sync.
- Find in page, per-site text zoom (via the probe extension's content
  script — GeckoView has no per-session zoom API), per-site desktop
  mode, full menu with Customize.
- Installs uBlock Origin from AMO behind a real install prompt
  (name + permissions, Install / Later) — never auto-approved.
- Bundles its own `pioneer-probe` WebExtension
  (`app/src/main/assets/extensions/pioneer-probe/`), which reports
  media URLs and Widevine EME status per page to the app, and applies
  per-site text zoom.
- Probes Cast route discovery only (Default Media Receiver) — no
  Cast session is ever launched.
- A Diagnostics screen (and logcat tag `PioneerSpike`) shows:
  install/run status, APK size, process PSS, cold-start time to
  first composite, uBlock/probe status, Cast routes found, and the
  media URLs the probe reported — with a Copy status button.

## On-device checklist (fill in on Robert's Pixel 10a)

| Check | Result |
|---|---|
| App installs and launches | |
| The Verge renders, tabs switch | |
| APK size (MB, from panel) | |
| PSS after load (MB, from panel) | |
| Cold start to first composite (ms, from panel) | |
| uBlock installs from AMO (logcat: "uBlock: INSTALLED") | |
| Ads blocked on theverge.com vs Mariner | |
| DRM test page plays protected video (demo.castlabs.com) | |
| EME status in panel on the DRM page | |
| Cast routes found on home Wi-Fi (count/names in panel) | |
| Media probe lists video URLs on a video page | |
| Media probe vs Mariner finder, same pages | |

## Build

```sh
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Licences

- This app: GPL-3.0 (see `LICENSE`).
- GeckoView: Mozilla Public License 2.0.
- uBlock Origin is installed at runtime from addons.mozilla.org; it
  is not distributed in this repository or APK.
