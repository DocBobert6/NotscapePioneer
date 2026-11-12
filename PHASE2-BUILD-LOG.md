# Notscape Pioneer — Phase 2 Build Log (shell parity)

Gate 1 GO given 2026-11-11 21:39 ("Begin"). Phase 2 scope (plan):
tabs, navigation, history, find, zoom, private tabs, start page,
bookmarks, the full menu and Customize. No media, no reader, no vault
fill yet. A browser Robert can genuinely browse with.

## Design decision (read first)

Mariner's `BrowserScreen.kt` is ONE 6,674-line composable with vault
fill, login capture, media detection, downloads and reader hooks
interleaved, and `Store.kt` is a Room+JSON hybrid entangled with vault
crypto, downloads, books and playlists. A line-for-line port of those
two files is not a reliable single build. Phase 2 therefore builds a
**clean Pioneer shell written for Gecko**, reusing Mariner's
engine-independent code where it is clean (theme, menu system, start
page, library screens, Frost kit) and taking Mariner's BEHAVIOUR as
the spec everywhere else. Full reasoning: `PHASE2-DESIGN.md`.

## Research findings that changed the design (PHASE2-GECKOVIEW-API.md)

All 9 items verified against Mozilla's GeckoView Javadoc
(mozilla-central 162) and searchfox source, not memory.

- **Find in page — exists**: `session.getFinder()`,
  `find(String, flags)`, `clear()`; `FinderResult` carries
  found/wrapped/current (1-based)/total (-1 if unknown).
- **Text zoom — NO per-session API.** Only runtime-global
  `setFontSizeFactor`. Per-site zoom goes through the probe
  extension's content script (documentElement font-size scaling
  on a "zoom" native message).
- **Session save/restore — exists** (`onSessionStateChange` +
  `SessionState.toString/fromString` + `flushSessionState()`),
  but Phase 2 keeps URL-based tab restore; full state restore is
  a later improvement.
- **Private sessions — construction-time only**
  (`GeckoSessionSettings.Builder().usePrivateMode(true)`).
- **Desktop site — per-session, live-mutable**
  (`setUserAgentMode(DESKTOP)` + `setViewportMode(DESKTOP)`,
  then reload; viewport alone still redirects to m.*).
- **Long-press — exists**: `ContentDelegate.onContextMenu`
  with `linkUri`.
- **ETP at runtime — YES, changeable**
  (`runtime.settings.contentBlocking.setEnhancedTrackingProtectionLevel`).
- **`evaluateJavascript` — DOES NOT EXIST.** No `evaluate*`
  anywhere in `GeckoSession`; Mozilla: "the only supported way
  is to write a built-in WebExtension". All page JS in Pioneer
  goes through the probe extension. `evaluateJs` was removed
  from the Engine interface for this reason.
- **`onNewSession`** returns a newly-created session; never
  call `load` inside it. `onLocationChange` is the URL-bar
  signal; `onLoadRequest` is top-level-only.

## What is in (0.1.0-alpha.3)

- Engine interface over GeckoSession (`engine/`): tabs, navigation,
  history callbacks, find (SessionFinder), per-site zoom (probe
  content script), per-site desktop mode, private sessions,
  `window.open` tabs via `onTabCreated`, long-press links,
  runtime ETP changes, uBlock install behind a real prompt
  (never auto-approved), probe extension, Cast route discovery
  (deduped — Gate 1 watch item fixed), Copy status.
- Fresh Pioneer-only store (`data/`): bookmarks + nested folders,
  history, favourites, per-site settings, tab session, settings —
  JSON files, no Room/KSP. Netscape bookmark HTML import/export
  in Mariner's exact format.
- Shell (`ui/` + `MainActivity.kt`): tab switcher, address bar
  with history/bookmark suggestions, start page (most-visited
  tiles, favourites), find bar, full menu + Customize sheet,
  focused Settings, Licences (GeckoView MPL 2.0 notice),
  Diagnostics with Copy status, ACTION_VIEW links from other apps.

## CI runs (Android CI, push to main)

| Run | Commit | Result | Cause |
|---|---|---|---|
| 43145678294 | b36d8889 | failure | Phase 2 landing: `material-icons-core` dependency missing (carried shell files import `androidx.compose.material.icons`) + two missing imports (`Arrangement` in SettingsScreen, `Text` in AppTheme) — 67 errors, one root cause |
| **43146138432** | **0d972679** | **success** | **Green. Artifact `pioneer-alpha-apk`.** Fix commit: added `material-icons-core` (BOM-managed) + the two imports |

## Verified APK (run 43146138432, artifact 13911666900)

- GitHub artifact `pioneer-alpha-apk`: zip 111,299,501 bytes
  (exact match on download; real zip, not the HTML placeholder).
- Local copy: `~/workspace/notscape-pioneer/artifacts/alpha3/app-debug.apk`
  (zip beside it: `pioneer-alpha3-apk.zip`).
- **APK: 211,311,019 bytes** (~201.5 MiB), Android package,
  debug-signed (v1 + v2 verified with androguard).
- SHA-256: `491df041f96bdab7c58514f37163b6c454d4f76fe0c40736686cddbb015bfd73`
- package `net.nspioneer.browser`, versionName `0.1.0-alpha.3`,
  versionCode 3, minSdk 26, targetSdk 36, app name "Notscape Pioneer".
- ABIs: **arm64-v8a only** (14 native libs).
- Engine present: `lib/arm64-v8a/libxul.so` 154,257,040 bytes,
  `assets/omni.ja`, probe extension assets present.
- Robert's wagon icon at all densities.
- Dex confirms the Phase 2 shell is in the build: "Copy status",
  "Desktop site", "Tracking protection", "zoom-query" (probe zoom
  mechanism), "installUBlock", "GeckoEngine" (61 refs),
  "PioneerStore" (222 refs), "pioneer-store" (2 refs).

## Known gaps (not hidden)

- Favicons are letter tiles (no fetching in Phase 2).
- Tab thumbnails: none (title + URL rows).
- uBlock Remove: no Engine uninstall method yet.
- First-load-failed-then-retry (Gate 1 watch item): mitigated
  in the engine (session opened before first load; load() guards
  `isOpen` and sets active/focused before loadUri) — exact cause
  unproven without a device; stays a known issue if it recurs.
- Zoom depends on the probe extension's `connectNative` port;
  if the port fails, zoom applies on next navigation instead.
- ETP/desktop-mode reload behaviour on device is unverified
  (reload done anyway, per the safe pattern).
- Custom wallpaper photo: not in Phase 2 (theme-following
  colours only). Close-duplicate-tabs: not included.
- Out of scope by design (later phases): downloads, media
  finder/player, fullscreen video, reader, TTS, vault fill,
  cast session, games, updater.
