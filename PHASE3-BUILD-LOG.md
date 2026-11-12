# Notscape Pioneer — Phase 3 Build Log (blocking, downloads and media)

Gate 2 GO given 2026-11-11 22:38 ("On to phase 3"). Phase 3 scope (plan):
uBlock Origin plus Mariner's lists and per-site exceptions, the full
downloads stack, media detection and the pop-up player, fullscreen video,
permissions and file chooser. Measured against WebView Mariner on the same
pages, numbers reported plainly — including anywhere Gecko comes out worse.
Gate 3 is a parity scorecard Robert can read in two minutes.

Head commit: `ccdc9b3a` (CI: run unit tests in CI + this log) on top of
`a99070a4` (fix) on top of `1fad27af` (Phase 3) on top of `43a77b68`
(Phase 2, 0.1.0-alpha.3). Version: `0.1.0-alpha.4`, versionCode 4,
debug-signed only. No release keystore created.

## Design

- [PHASE3-DESIGN.md](PHASE3-DESIGN.md) (1,029 lines) + [PHASE3-GECKOVIEW-API.md](PHASE3-GECKOVIEW-API.md)
  (518 lines), written by the researcher before any implementation code.
  File map is a hard boundary: Worker A blocking, Worker B downloads +
  permissions + file chooser + engine/store spine, Worker C media +
  player. No two workers shared a file.
- Steering folded in during the phase: (1) UI must match Mariner
  EXACTLY on Phase 3 screens — PlayerActivity layout/controls/gestures/
  floating pill, downloads UI, permission prompts, file chooser (no
  redesign). (2) Material You is already in Pioneer (Phase 2 AppTheme)
  — verify only, fix only if broken. (3) The Phase 1–2 shell UI audit
  is a separate post-Phase 3 workstream; corrections as standalone
  commits after Phase 3 lands. (4) Mariner's STRICT POPUP BLOCKER named
  explicitly as Phase 3 scope (popunder guard + $document/$popup rules,
  per-site allowPopups/blockPopups with UI, captured-popup panel).
- `UI-AUDIT-REPORT.md` (616 lines, the Phase 1–2 shell audit vs Mariner
  RC1) exists in staging but was deliberately NOT pushed with Phase 3 —
  it is the audit record for the post-Phase 3 workstream, and several of
  its "missing — Phase 3" items are resolved by this commit. It travels
  with the audit corrections, not with Phase 3.

## Workers and files

- **Researcher:** design docs only; Mariner read-only; verified every
  GeckoView API against Mozilla sources. Key corrections: PiP is v160,
  not v161; `WebResponse` has no filename/contentLength/contentType
  (headers only); permissions live on `StorageController`; extension
  uninstall exists (v73); Cookie Banner Handling removed in v154.
- **Worker A — blocking:** `engine/FilterEngine.kt` (~2,200 lines,
  pure-Kotlin port of Mariner's FilterEngine + UserBlockRules),
  `engine/BlockingObserver.kt`, `assets/.../background.js` (webRequest +
  webRequestBlocking observer), `blocking.js` (cosmetic injection),
  `manifest.json` (owner; adds background/blocking + webRequest /
  webRequestBlocking / tabs permissions), `ui/BlockingSettings.kt`,
  `app/src/test/.../FilterEngineTest.kt` + `UserBlockRulesTest.kt`.
- **Worker B — downloads + permissions + file chooser + spine:**
  `engine/Engine.kt` (§5 amendments), `engine/GeckoEngine.kt`
  (delegate wiring + BlockingHost), `engine/PermissionDelegate.kt`,
  `engine/PromptDelegate.kt`, `engine/Fullscreen.kt`,
  `media/Downloads.kt` (2,363 lines, condensed faithful port of
  Mariner's 4,184-line file), `media/DownloadWorkers.kt` (HLS/DASH/
  Segmented/Merge CoroutineWorkers), `media/DownloadNotifAsk.kt`,
  `data/PioneerStore.kt` (§6 amendments), `ui/LibraryScreens.kt`
  (DownloadsScreen + LibraryScreen, exact), `ui/BrowserScreen.kt`
  (dialogs, file-chooser launchers, popup notice, site-info sheet,
  media call sites), `ui/BrowserChrome.kt` (Downloads menu row),
  `ui/Screen.kt` (Downloads/Library screens), `app/build.gradle.kts`
  (WorkManager 2.10.0, Media3 1.9.4 set, material-icons-extended,
  junit 4.13.2), `AndroidManifest.xml` (notifications, foreground
  service, PlayerActivity, camera/mic/location), `res/xml/file_paths.xml`
  (new — FileProvider share/open would crash without it).
- **Worker C — media + player:** `media/PlayerActivity.kt` (2,530
  lines, exact port of Mariner's 2,351-line player, §8.5 inventory
  checked off item by item), `media/MediaLog.kt`, `media/VideoPopup.kt`,
  `engine/MediaSessionHandler.kt`, `ui/MediaSheet.kt`,
  `ui/MediaToolbarButton.kt`, `media/CastHelper.kt` (supporting file,
  guarded MediaRouteButton construction), `assets/.../probe.js`
  (VideoPopup watcher + Mariner MediaDetect scan), `res/layout/
  player_view.xml` (Mariner's layout verbatim).
- **Coordinator integration (me):** MainActivity Downloads/Library
  branches (B never touched MainActivity); SettingsScreen wiring —
  BlockingSettingsContent section, Strict popup blocking toggle in
  Privacy, uBlock Remove row replacing the stale "not available in
  this phase" text; probe.js `media-scan` port handler (3 lines, C's
  file); §7 design-doc amendment (`blocked` / `rules-request` messages,
  `rules-update` payload gains, `tabs` permission); version bump to
  0.1.0-alpha.4 / code 4; README status + version; this log.

## CI runs (Android CI, push to main)

| Run | Commit | Result | Cause |
|---|---|---|---|
| 43155186361 | 1fad27af | failure | `compileDebugKotlin`: 33 errors in 4 files (see below) |
| 43156077397 | a99070a4 | **success** | fix commit (below) |
| 43156629347 | ccdc9b3a | **success** | workflow test step + this log; `Run unit tests` step passes in CI (row A now genuinely CI-measured) |

Fix commit `a99070a4` ("Phase 3 fix: CI compile errors …"):
- `DownloadWorkers.kt`: unqualified `Result` resolved to `kotlin.Result`
  — qualified to `ListenableWorker.Result`; `params` is private in
  `CoroutineWorker` — helper now takes `inputData: Data` +
  `runAttemptCount: Int` (public `ListenableWorker` members).
- `Downloads.kt`: missing `import kotlinx.coroutines.async` (`await`
  needed none — `Deferred.await()` is a member); transformer
  `EditedMediaItemSequence.Builder(items).build()` (verified via `javap`
  on the Media3 1.9.4 AAR — no public `List` constructor exists).
- `PlayerActivity.kt`: `private data class ParsedQuality` →
  `internal` (internal parsers exposed it).
- `BrowserScreen.kt`: `backHandler` referenced state declared after
  it — moved the block below `clearPendingPopups`; missing imports
  `androidx.compose.material3.Surface` and
  `androidx.compose.foundation.layout.width`.
- The `Regex("""(\w+)\s*=\s*"([^"]*)"""")` line (4-quote run) was
  verified against real kotlinc 1.9.24: it compiles and matches
  correctly — a content quote plus terminator, not an error. The
  naive brace scanner flags it; the interpolation-aware scanner
  (with the 4-quote rule) passes it.

## Verified APK

- GitHub artifact `pioneer-alpha-apk` (run 43156077397, artifact
  13915623759): zip 120,242,108 bytes — exact match, real zip, not the
  HTML placeholder. Local copy:
  `artifacts/alpha4/app-debug.apk`.
- **APK: 220,648,004 bytes** (~210.4 MiB), debug-signed (v1+v2),
  SHA-256 `4c36071eb575060d26bb6a6ed718d227c0348fb92a3d46aba6e47fa05ce0640b`.
- Verified by androguard + zip/dex inspection: package
  `net.nspioneer.browser`, versionName `0.1.0-alpha.4`, versionCode 4,
  minSdk 26, targetSdk 36, launcher `MainActivity`, **arm64-v8a only**
  (14 libs), engine present (`libxul.so`, `assets/omni.ja`), Robert's
  wagon icon at all densities, probe extension assets present.
- Dex confirms the Phase 3 shell is in the build: `FilterEngine` (141
  refs), `BlockingObserver` (17), `PlayerActivity` (547),
  `DownloadWorkers` (15), `HlsDownloadWorker` (4), `Copy status`,
  `installUBlock` (17), `GeckoEngine` (143), `PioneerStore` (324).
- Head build re-verified (run 43156629347, head `ccdc9b3a`, artifact
  13916222722): zip 120,242,106 bytes; APK 220,648,004 bytes (same size
  as the 43156077397 APK), SHA-256
  `ff7a90bfe8b6c6845502a270154c374ded680b83ebef5aa336a0e77f7c3a3d34`
  (differs from `4c36071e…` — debug-build signing timestamps; same
  code), package/version/code/minSdk/libxul all confirmed again.
- Size note: 220,648,004 bytes vs alpha.3's 211,311,019 (+9,336,985) —
  Media3 1.9.4 + WorkManager + the lists/player code. The engine is
  still the cost (libxul ~154 MB).

## Gate 3 scorecard (Pioneer alpha.4 vs WebView Mariner RC1 0.1.0)

PASS only when the measurement was actually run. Everything device-owed
is Robert's Pixel 10a, same pages in both browsers, back to back.

| Row | What | Result | Evidence / notes |
|---|---|---|---|
| A | Blocking: lists + matcher | **PASS (Kotlin + JS)** | JUnit 27/27 green (local kotlinc run; CI step added this phase — see below). Kotlin↔JS agreement 26/26 on the sample table + 8/8 on the $document table, every verdict also equalling the expected value. Real lists downloaded 2026-11-12 (824,717 lines): merged 572,197 hosts, 33,128 scoped, 13,580 generic, ruleCount 605,325; live document rules 3,268. `compiled.bin` round-trip verified. |
| B | Blocking: live pages | **OWED (device)** | Per-tab blocked count + visible ads, same pages back to back. |
| C | Blocking: per-site exception | **OWED (device)** | ETP + lists off must reload ads; uBlock keeps blocking in Pioneer — recorded gap, not a row fail. |
| D | Finder: direct + HLS + DASH | **OWED (device)** | flower.mp4 (Gate 1: probe saw 1 URL), HLS/DASH test streams, plain `<video>`. |
| E | Finder: YouTube / MSE | **OWED (device)** | Decisive row: needs the background `webRequest` observer to fire on 159. DOM-only does not pass. |
| F | Downloads: unit behaviour | **OWED** | No download unit tests were written in Phase 3. The helpers are `internal` and testable (`contentRangeTotal`, `encodeJobBundle`/`decodeJobBundle`, `engineFor`, `locationFor`, …) — defined follow-up, not a hidden gap. |
| G | Downloads: live | **OWED (device)** | Plain file, image, HLS, DASH, merge; pause/resume/cancel; Wi-Fi-only queue; private-download gating. |
| H | Player UI: exact match | **OWED (device)** | §8.5 inventory checked off in code (see Worker C report); screenshot + gesture pairs on device still owed. |
| I | File chooser | **OWED (device)** | `content://` SAF pick is the trap row (copy-to-temp + `file://` confirm implemented; device proof owed). |
| J | Permissions | **OWED (device)** | permission.site camera/mic/geolocation + autoplay; dialog text "Allow $host to use". |
| K | Popups | **OWED (device)** | Gesture popup → new tab; scripted → blocked + notice (max 5); popunder approximation quality recorded as measured. List side verified: 8/8 sample verdicts agree Kotlin↔JS. |
| L | Inventory diff | **PASS (code)** | From the code, not eyeballing: §8 + worker reports; Phase 3 scope items carried/adapted/dropped are listed in this log and the design doc. The full Mariner-vs-Pioneer inventory (incl. shell) lives in `UI-AUDIT-REPORT.md` (staging only — travels with the post-Phase 3 audit workstream). |
| M | Material You | **OWED (device)** | Verify-only check: dynamic palette on the same wallpaper, player/dialog/sheet. Fix only if broken. |
| N | Fullscreen video | **OWED (device)** | Bars hide, chrome hides, video fills; ▶ button hides while fullscreen. |

CI change this phase: the workflow gained a `testDebugUnitTest` step
(before the APK build) so row A's Kotlin tests run in CI on every push
— previously the workflow only built the APK and the tests ran only in
a local kotlinc check. Pushed as a follow-up commit after the fix run
went green; that run is the one that proves the step works.

## Where Gecko comes out worse (stated plainly)

- **No background playback.** No `PlaybackService` exists in Phase 3
  (not in any worker's file map); the player is a local ExoPlayer and
  playback stops with the activity. Needs a Phase 4 (or coordinator)
  decision.
- **Cookies dropped from playback.** Gecko cookies are not reachable
  through WebKit `CookieManager` and Pioneer has no WebView; referer +
  browser UA still ride along. Cookie-gated CDNs may 403 in the player
  while playing fine in-page. Cookie snapshot is always empty, so
  authenticated/signed media downloads may fail (plumbing kept
  host-scoped).
- **MSE direct-URL recovery is impossible in principle.** There is no
  URL for an MSE stream. The `webRequest` observer recovers manifest /
  segment URLs — transient, signed, pass-through only, not stable
  identifiers. If the observer does not fire on 159, row E fails
  honestly.
- **Popup blocking is stricter than Mariner.** `webRequest` carries no
  user-gesture signal, so a tapped link to a `$document` host is
  blocked the same as a popunder hop (Mariner's guard could tell them
  apart and never broke a tapped link). The captured-popup panel is the
  recourse. Coordinator-confirmed to stand for Phase 3.
- **Per-site "disable blocking" does not disable uBlock Origin.** The
  app cannot toggle a per-site exception inside uBlock; the toggle
  disables ETP + Mariner's lists + cosmetics only. The site-info row
  says so (`DISABLE_BLOCKING_NOTE`).
- **Cosmetics inject at `document_idle`, not document start.** Ads may
  flash before the sheet applies; Mariner injects at document start.
- **No per-site uBlock toggle; no uBlock per-site state at all.**
  Related: `syncUserRules` has no caller (custom ABP text IS wired
  through `setListConfig` + `reloadLists` → `onListsChanged` →
  `pushRulesUpdate`, so user rules do reach the background script;
  `syncUserRules` itself is dead code for the `userBlockNet` /
  `userBlockCosmetic` keys nothing writes).
- **DASH SegmentTimeline not parsed.** SegmentTemplate expansion probes
  to the first 404 (cap 20,000 segments); works for VOD, unverified
  against real manifests.
- **File-chooser `capture` attribute** falls back to the picker
  (Compose contracts have no capture path). **Site-info sheet drops
  vs Mariner:** JavaScript toggle (no per-site JS engine switch) and
  cookie count / "Clear data for this site" (no GeckoView cookie API).
- **PDF viewer deferred** (PDFs download as files); **EPUB auto-shelf
  dropped** (reader is Phase 4); **Cast session launch is Phase 4**
  (button rendered exactly as Mariner, tap does not launch); **PiP is
  not in GeckoView 159** (player uses Android PiP, as Mariner does);
  **Cookie Banner Handling removed in v154** (nothing to port);
  **v160 content-disposition validation absent on 159** (raw header
  parsed/sanitised); **HLS subtitle renditions not fetched**.
- **Media-net attribution:** host match, falling back to the active
  tab, dropped when unattributable (§7). The delegate routes
  `media-net` through `noteNetMedia` (one sink);
  `BlockingObserver`'s own `media-net` branch is unreachable by
  design, not a bug.
- **Subtitle fonts are platform typefaces only** (no bundled fonts);
  HLS/DASH variant parsing is inline minimal (label + bandwidth only);
  resume positions live in a private JSON file, not the store.
- **Cast button construction is guarded** (`createMediaRouteButton`
  returns null instead of crashing — Mariner's beta.52/55 crash was
  `MediaRouteButton` resolving AppCompat colours to #0 under a platform
  Material theme; a dedicated Cast theme is still owed).
- **First-load-failed-then-retry (Gate 1 watch item):** mitigated in
  alpha.3 (session opened before first load + `isOpen` guard) — still
  NOT proven on device; keep watching on alpha.4.
- **Per-site zoom** still depends on the probe extension's
  `connectNative` port (falls back to next-navigation if the port
  fails). **ETP/desktop-mode reload behaviour** still unverified on
  device.
- **`osGrantHandler` / `popunderCaptures` are `GeckoEngine` members,
  not on the `Engine` interface** — BrowserScreen reaches them via an
  `(engine as? GeckoEngine)` cast. Interface gap, noted for §5.
- **Favicons are letter tiles, no tab thumbnails** (unchanged from
  Phase 2 — cosmetic, out of scope).

## What Robert needs to test on his device (Gate 3)

Install `pioneer-alpha-apk` from run 43156077397 alongside Mariner and
work the scorecard rows B–E, G–K, M, N on the same pages in both
browsers, back to back (procedures in PHASE3-DESIGN.md §9): live-page
blocking counts + visible ads; per-site exception reload; finder on
direct/HLS/DASH pages and on a YouTube watch page (row E is the
decisive one — does the media sheet list a playable entry?); downloads
(plain, image, HLS, DASH, merge; pause/resume/cancel; Wi-Fi-only
queue; private-download gating); player screenshot + gesture pairs
(double-tap ±10s, hold-to-scan, drag seek/brightness/volume, pinch
zoom, speed/sleep cycles, listen mode, PiP enter); file chooser incl.
a SAF `content://` pick; permissions on permission.site; popups
(gesture → new tab, scripted → notice, popunder); Material You on the
same wallpaper; in-page fullscreen. Copy status from Diagnostics for
results. Cold start and PSS from the Diagnostics screen.

## Open / follow-up (not Phase 3 scope)

- Row F download unit tests (defined, small).
- `syncUserRules` dead code (harmless; remove or wire when user-rule
  keys are next touched).
- `osGrantHandler` / `popunderCaptures` on the `Engine` interface.
- Dedicated Cast theme (guarded construction hides the button instead
  of crashing until then).
- Background playback service (Phase 4 decision).
- Post-Phase 3 workstream: Phase 1–2 shell UI audit + corrections
  (standalone commits after Phase 3; `UI-AUDIT-REPORT.md` travels
  with it). Known queued item: address-bar Top/Bottom setting
  (`barPosition`, default bottom).
