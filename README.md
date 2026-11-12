# Notscape Pioneer

Notscape Pioneer is Mariner on Gecko — the same app as Notscape Mariner,
with complete parity as the goal, running Mozilla's Gecko engine via
GeckoView instead of the Android System WebView. Extension and add-on
support, with ad blocking (uBlock Origin first), is the biggest thing
Gecko makes possible.

Local-only and private, stated positively. No account, no sync.

## Status: Phase 1 spike (alpha, throwaway)

This repository currently holds **only the Phase 1 spike**: a minimal
GeckoView browser — one `GeckoRuntime`, three `GeckoSession` tabs, an
address bar, nothing else. It is timeboxed and throwaway. Phase 2 (shell
parity) does not start until the spike report is written and Robert
decides go or no-go.

- Package: `net.nspioneer.browser`
- Version: `0.1.0-alpha.1` (versionCode 1), debug-signed only
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

## What the spike does

- Three tabs backed by three `GeckoSession`s under one `GeckoRuntime`,
  with Enhanced Tracking Protection set to Strict.
- Installs uBlock Origin at first launch from AMO
  (`WebExtensionController.install`, auto-approved install prompt —
  spike only; a real browser would show the prompt).
- Bundles its own `pioneer-probe` WebExtension
  (`app/src/main/assets/extensions/pioneer-probe/`), which reports
  media URLs and Widevine EME status per page to the app.
- Probes Cast route discovery only (Default Media Receiver) — no
  Cast session is ever launched.
- An on-screen spike panel (and logcat tag `PioneerSpike`) shows:
  install/run status, APK size, process PSS, cold-start time to
  first composite, uBlock/probe status, Cast routes found, and the
  media URLs the probe reported.

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
