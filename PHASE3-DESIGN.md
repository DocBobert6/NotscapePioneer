# Notscape Pioneer — Phase 3 Design (blocking, downloads, media, permissions)

**Version:** 0.1.0-alpha.4, versionCode 4 (the coordinator bumps at
integration — workers do not touch the version).
**Scope:** blocking (uBlock Origin + Mariner's lists + per-site
exceptions), the full downloads stack, media detection + pop-up player,
fullscreen video, permissions and file chooser.
**Gate 3** is a side-by-side parity scorecard vs WebView Mariner (§9).
**Status:** design only. No implementation code in this document; workers
implement from it.

## Scope restated (what Phase 3 is and is not)

**In:** everything in the scope line above, plus the exact-UI port of
Mariner's video player (`PlayerActivity`), downloads screens, permission
prompts and file-chooser flow — see §8. The player UI must match Mariner
**exactly**: layout, controls, gestures, and the floating pill (Robert,
2026-11-11 22:44). Same for the downloads UI, permission prompts and file
chooser: match Mariner, do not redesign.

**Out (do not build, do not fake):** reader / TTS / vault fill / Cast
session launch (Phase 4), games, updater, EPUB auto-shelf, in-app PDF
viewer (PDFs download as files in Phase 3 — §8.4). The Cast button is
rendered in the player and the media sheet exactly as in Mariner, but
tapping it is Phase 4 (§10).

**Not in Phase 3 scope — separate post-Phase 3 workstream (§11):** the
audit of the already-built Phase 1–2 shell against Mariner and the
corrections to it. Those corrections land as their own commits sequenced
**after** Phase 3, specifically to avoid colliding with the Phase 3 builder
on the same branch (Robert, 2026-11-11 22:43). Phase 3 workers do not touch
the shell audit, the address-bar Top/Bottom setting (`barPosition`,
default bottom — known queued correction), or any other Phase 1–2 shell
difference. What Phase 3 itself must do is match Mariner exactly on **its
own** screens.

## Why this shape (read before coding)

Phase 2 established the shape: a clean Pioneer shell written for Gecko,
**not** a line-for-line Mariner port — Mariner's `BrowserScreen.kt` is one
6,674-line composable and its `Store.kt` is Room+JSON entangled with vault
crypto. Behaviour parity is the goal; code identity is not. **That
decision is refined, not reversed, by the UI bar:** Robert's bar
(2026-11-11 22:41) is that Pioneer's UI must be **identical to Mariner's
for everything**. For Phase 3 that means: the *behaviour and logic* are a
clean Gecko-native implementation, but the *visible UI* of Phase 3 screens
is Mariner's UI carried over — same composables, same strings, same
gestures, same FrostKit capsule/pill treatment. Where Mariner's UI code
can be carried over verbatim (the player, the dialogs, the download
screens), carry it over. Where the engine underneath differs (GeckoView
delegates instead of WebView callbacks), write the Gecko side fresh and
keep the Mariner UI on top of it.

Three facts drive the rest of the design:

1. **GeckoView has no `shouldInterceptRequest` and no
   `evaluateJavascript`.** Mariner's blocking lives in
   `shouldInterceptRequest` and its cosmetics in `evaluateJavascript`.
   In Pioneer both must live in the probe extension: blocking in a
   background-script `webRequestBlocking` observer, cosmetics in a
   content script. That is the single biggest architectural fact of this
   phase (API doc §10, §11).
2. **The probe sees 0 URLs on YouTube (Gate 1).** The DOM probe is blind
   to MSE/blob streams. Only a background-script `webRequest` observer
   can close that gap (§8.3) — and even it cannot recover a *direct* URL
   for MSE playback, because no such URL exists. The design states this
   bluntly instead of promising it away.
3. **Downloads are an app-side stack, not an engine feature.**
   `onExternalResponse` only *reports* a download; everything after that
   (naming, routing, workers, notifications, screens) is Mariner's
   `Downloads.kt` ported to run on Pioneer's store, plus the manifest and
   dependency pieces Pioneer does not have yet (WorkManager, Media3,
   notification permission, foreground service).
   Blob/data: downloads are the exception to re-fetching: a
   blob: URL only exists inside the page, so the engine
   stages the response body to disk at onExternalResponse
   time and the download writes those bytes straight to the
   destination (alpha.27) — no probe, no DownloadManager.

## Rules for every worker on this phase

- Work only in `~/workspace/notscape-pioneer/`. Mariner at
  `~/workspace/notscape-mariner-rebuild/app-stage` is **READ-ONLY** — never
  edit it, never push to it. Read it, copy UI code out of it, leave it
  alone.
- **Never push.** The coordinator integrates via
  `~/workspace/notscape-pioneer-push.py`. Do not push Pioneer anywhere.
- Debug signing only. No release keystore exists for this app; do not
  create one, do not ask for one.
- **Public-presentation rule:** Pioneer is presented as an original
  project. Local-only / private. No mention of recovery, rebuild, 2.0.8,
  lost source, Cloak, or account/sync removal — in code, comments,
  strings, or docs.
- Package `net.nspioneer.browser`. Fresh empty profile. No shared DB with
  Mariner. No sync. Only Robert-approved extensions by name; uBlock Origin
  first.
- **File map (§4) is a hard boundary.** Do not edit outside your set. If
  two workers need the same file, the owner listed there edits it and the
  other worker's need is specified exactly in this document. A shared
  contract lives in this document, not in a shared file.
- **Stop the line on a red CI build.** Fix it and verify green before
  anything else lands. Do not push the next thing on top of an unverified
  tree.
- **Do not claim verification you do not have.** A worker reports what CI
  actually ran green and what was actually checked on device; "should
  work" is not a result. Device-owed items go in the scorecard (§9), not
  in a commit message.
- **Material You is already in Pioneer — do not rebuild it.** Phase 2
  carried Mariner's `AppTheme` over (`materialyou` /
  `materialyou-light` / `materialyou-dark`, dynamic palette on Android
  12+); Robert confirmed it on device 2026-11-11 22:46. Phase 3 adds a
  **verification item** (§9, row M): the integration pass must verify the
  dynamic palette applies in the Phase 3 build, and fix it as part of
  Phase 3 **only if it is broken**.
- **Inventory, not eyeballs (lesson, 2026-11-11 22:47).** The
  address-bar-position miss proves a clean-shell rebuild drops things
  silently. Every worker inventories the Mariner setting keys, menu rows
  and features in their area (§8 lists them) and carries each one over or
  records it as dropped with a reason. Gate 3 includes a full inventory
  diff (§9, row L).
- **Never invent a measurement method that cannot actually run** (§9).
  If a row cannot be measured on CI or on Robert's Pixel 10a, it is
  recorded as unmeasurable, not measured by hand-waving.

## File map (ownership — do not edit outside your set)

Three workers. No two workers share a file. Where one worker's feature
needs a line in another worker's file, the call site is named here and
the owning worker makes the edit.

### Worker A — blocking (uBlock + Mariner's lists + per-site exceptions)

| File | What |
|---|---|
| `app/src/main/java/net/nspioneer/browser/engine/FilterEngine.kt` | **New.** Pure-Kotlin port of Mariner's `FilterEngine` + `UserBlockRules`: list download/refresh/storage, `blockCategory`, `isDocumentBlocked`, `cosmeticCss`, user net/cosmetic rules. No Android imports except `Context` for storage paths. Unit-testable. |
| `app/src/main/java/net/nspioneer/browser/engine/BlockingObserver.kt` | **New.** Owns the native-message side of the probe's blocking bridge: receives `media-net` observations, serves `rules-update` (pushes the compiled rule set to the background script), serves `cosmetic-query`, keeps the per-tab blocked count. |
| `app/src/main/assets/extensions/pioneer-probe/background.js` | **New.** Background script: `webRequest` + `webRequestBlocking` observer (MSE/manifest/segment URLs to the app; cancel Mariner-list matches), rule set received via the `rules-update` native message (§7). |
| `app/src/main/assets/extensions/pioneer-probe/blocking.js` | **New.** Content script: cosmetic injection (`cosmeticCss` result applied at `document_idle`), `$generichide` host check. |
| `app/src/main/assets/extensions/pioneer-probe/manifest.json` | **Owner for this phase.** Adds `background.js`, `blocking.js`, the `"webRequest"` + `"webRequestBlocking"` permissions. (Worker C's probe changes go through A as manifest edits — see below.) |
| `app/src/main/java/net/nspioneer/browser/ui/BlockingSettings.kt` | **New.** Settings UI for the lists (enable/disable per list, custom list URL, custom ABP text with 1-based bad-line reporting, refresh, session blocked count + `blockedHistory()`). |
| `app/src/test/…/FilterEngineTest.kt`, `UserBlockRulesTest.kt` | **New.** Matcher verdicts on the fixed sample-URL table (§9 row A). |

### Worker B — downloads + permissions + file chooser (+ engine/store spine)

| File | What |
|---|---|
| `app/src/main/java/net/nspioneer/browser/engine/Engine.kt` | **Owner for this phase.** The interface amendments in §5 (exact text). All three workers code against the text in this document; only B edits the file. |
| `app/src/main/java/net/nspioneer/browser/engine/GeckoEngine.kt` | **Owner for this phase.** Delegate wiring only: registers B's `PermissionDelegate`/`PromptDelegate`/`ContentDelegate` (external response, fullscreen) and C's `MediaSessionHandler` via its `register()` function. No behaviour of A's or C's lives here. |
| `app/src/main/java/net/nspioneer/browser/engine/PermissionDelegate.kt` | **New.** `PermissionDelegate` implementation (content / Android / media permissions) + `PermPrompt` flow + `grantWithOsCheck`. |
| `app/src/main/java/net/nspioneer/browser/engine/PromptDelegate.kt` | **New.** `PromptDelegate` implementation: file chooser (`onFilePrompt`) + popup prompt (`onPopupPrompt`) + fullscreen is in `GeckoEngine` (it is a `ContentDelegate` method). |
| `app/src/main/java/net/nspioneer/browser/engine/Fullscreen.kt` | **New.** The fullscreen container behaviour: system-bars hide/restore + `MATCH_PARENT` container, called from `GeckoEngine.onFullScreen`. |
| `app/src/main/java/net/nspioneer/browser/media/Downloads.kt` | **New.** Port of Mariner's `Downloads.kt` (start/routing/engines/naming/notifications), adapted to `PioneerStore` + `WebResponse`-derived input. |
| `app/src/main/java/net/nspioneer/browser/media/DownloadWorkers.kt` | **New.** `HlsDownloadWorker`, `DashDownloadWorker`, `SegmentedDownloadWorker`, `MergeDownloadWorker` (WorkManager `CoroutineWorker`s, foreground-service progress). One file; the four workers share the staging/journal helpers. |
| `app/src/main/java/net/nspioneer/browser/media/DownloadNotifAsk.kt` | **New.** Port of Mariner's one-time POST_NOTIFICATIONS ask (key `downloadNotifAsked`). |
| `app/src/main/java/net/nspioneer/browser/data/PioneerStore.kt` | **Owner for this phase.** The amendments in §6 (`DownloadRecord`, site-permission / autoplay helpers, private-downloads gate). A and C need **no** Store file change: their settings go through the generic `setting()` / `putSetting()` map. |
| `app/src/main/java/net/nspioneer/browser/ui/LibraryScreens.kt` | **Owner for this phase.** Carry Mariner's `DownloadsScreen` + `LibraryScreen` over exactly (§8.4). |
| `app/src/main/java/net/nspioneer/browser/ui/BrowserScreen.kt` | **Owner for this phase.** Download confirm dialog, permission dialog, file-chooser launcher, popup notice. C's media UI arrives as new files (`MediaSheet.kt`, `MediaToolbarButton.kt`) wired in at the two call sites named in §8.3; C never edits this file. |
| `app/src/main/java/net/nspioneer/browser/ui/BrowserChrome.kt` | **Owner for this phase.** Adds the Downloads menu row + Downloads navigation. C's toolbar media button is added here by B at the call site named in §8.3. |
| `app/build.gradle.kts` | **Owner for this phase.** Adds WorkManager `2.10.0` and the Media3 `1.9.4` set (§8.4) that C declares. Version bump to `0.1.0-alpha.4` / code 4 is the **coordinator's**, not B's. |
| `app/src/main/AndroidManifest.xml` | **Owner for this phase.** `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`, `SystemForegroundService` (`dataSync`), `PlayerActivity` declaration (§8.5), `CAMERA` / `RECORD_AUDIO` / `ACCESS_FINE_LOCATION` (runtime-requested; declare them). |

### Worker C — media detection + pop-up player + fullscreen video (player)

| File | What |
|---|---|
| `app/src/main/java/net/nspioneer/browser/media/PlayerActivity.kt` | **New.** Exact port of Mariner's `PlayerActivity.kt` (§8.5) — layout, controls, gestures, floating pill, queue dialog, lock cover. Same package path `media/`, same intent extras. |
| `app/src/main/java/net/nspioneer/browser/media/MediaLog.kt` | **New.** Per-tab media log (Mariner's `MediaLog.inspect` equivalent): stream-regex match, dedupe per tab, kind audio/video, label. Fed by the probe DOM scan (existing `media-probe`) and by A's `media-net` observations. |
| `app/src/main/java/net/nspioneer/browser/media/VideoPopup.kt` | **New.** Native side of the pop-up player: watcher state, `allowCheck` anti-spoof, per-site opt-out key `videoPopupOff:$host`, tap → `PlayerActivity.launch`, long-press → `runMediaScan()`. The JS watcher lives in `probe.js`. |
| `app/src/main/java/net/nspioneer/browser/engine/MediaSessionHandler.kt` | **New.** `MediaSession.Delegate` implementation (now-playing signal only, §8.3) with a `register(session)` function B calls from `GeckoEngine`. |
| `app/src/main/java/net/nspioneer/browser/ui/MediaSheet.kt` | **New.** "Media on this page (N)" sheet (FrostSheet, max 60% height) + merge checkboxes + footer opt-out toggle + empty state, exactly as Mariner (§8.3). |
| `app/src/main/java/net/nspioneer/browser/ui/MediaToolbarButton.kt` | **New.** The toolbar media button composable (visible only when `MediaLog` non-empty and not the start page; long-press plays first video). B wires it into `BrowserChrome.kt` at the named call site. |
| `app/src/main/assets/extensions/pioneer-probe/probe.js` | **Owner for this phase.** Adds the VideoPopup watcher JS + keeps the media scan + zoom. (Manifest edits for the probe go through A; C does not edit `manifest.json`.) |
| `app/src/main/java/net/nspioneer/browser/ui/FullscreenPlayer.kt` | **New, only if needed.** Player-side fullscreen/PiP helpers that do not belong in `PlayerActivity.kt` itself. Prefer putting them in `PlayerActivity.kt`; create this file only if the player file would otherwise exceed Mariner's structure. |

**Shared contracts (in this document, not in a file):** the Engine
interface text (§5), the Store keys (§6), the probe native-message types
(§7). If a contract proves wrong during implementation, the worker says
so in its report and the coordinator amends this document — the worker
does not silently renegotiate the contract with another worker's file.

## Engine interface (ENGINE implements, SHELL consumes — exact)

Additions to `engine/Engine.kt` (owner: Worker B). Existing members are
unchanged. Style follows Phase 2: every member carries the comment that
says what it does and what it must not do.

```kotlin
// ---- Phase 3: blocking ----
// Per-site exception state for ONE host. disableBlocking is the
// PioneerStore SiteSetting flag (JSON key "nb", already exists);
// setBlockingEnabled applies it to every layer at once: ETP exception
// via StorageController.setPermission(PERMISSION_TRACKING, VALUE_ALLOW),
// Mariner-lists bypass in the probe, and the cosmetic bypass. uBlock
// Origin is NOT toggled by this call — the app cannot toggle a
// per-site exception inside uBlock; see §10 (known gap). Do not pretend
// otherwise in the UI: the site-info row says what it disables.
fun setBlockingEnabled(host: String, enabled: Boolean)
fun refreshFilterLists()              // re-download + re-parse the 7 lists
                                      // (FilterEngine.refreshAsync equivalent)
val filterListsState: State<String>   // "7 lists • N rules • updated …" |
                                      // "updating…" | "failed: …" — for Settings
fun uninstallUBlock()                 // WebExtensionController.uninstall;
                                      // uBlockState reports the result
// ---- Phase 3: downloads ----
// The engine reports an unhandleable response; the SHELL shows Mariner's
// confirm dialog and calls Downloads.start. The engine NEVER shows UI.
data class ExternalDownload(
    val url: String,
    val contentType: String?,         // from response headers, may be null
    val contentLength: Long,          // -1 when unknown
    val suggestedName: String?,       // parsed Content-Disposition, may be null
    val statusCode: Int,
    val isSecure: Boolean,
)
var onExternalDownload: ((tab: EngineTab, download: ExternalDownload) -> Unit)?
// ---- Phase 3: media detection ----
data class DetectedMedia(
    val url: String,
    val kind: String,                 // "video" | "audio" | "page"
    val label: String,                // display label, ≤ 80 chars
    val source: String,               // "dom" | "net" | "session"
)
// Per-tab detected media, distinct by URL (Mariner: dom + net merged).
// The SHELL reads this for the toolbar button and the sheet.
val detectedMedia: State<List<DetectedMedia>>   // on EngineTab
fun runMediaScan()                              // on EngineTab: ask the probe
    // for a fresh DOM scan now (toolbar button / long-press path).
    // The background webRequest observer feeds detectedMedia continuously;
    // runMediaScan only re-runs the DOM half.
var onVideoPopupTap: ((url: String) -> Unit)?        // on EngineTab
var onVideoPopupLongPress: ((url: String) -> Unit)?  // on EngineTab
// ---- Phase 3: fullscreen ----
val isFullscreen: State<Boolean>   // on EngineTab; true while a page is
    // in fullscreen (ContentDelegate.onFullScreen). The SHELL hides the
    // browser chrome; Fullscreen.kt hides the system bars.
// ---- Phase 3: permissions + file chooser ----
// Mariner's PermPrompt, ported verbatim in shape (BrowserScreen.kt:430+).
// onGrant receives the resources actually granted (after the OS check);
// onDeny is called when the user dismisses or denies. The ENGINE builds
// the prompt; the SHELL shows Mariner's dialog and calls back.
data class PermPrompt(
    val origin: String,
    val host: String,
    val resources: List<String>,      // "camera" | "microphone" | "geolocation"
    val onGrant: (granted: List<String>) -> Unit,
    val onDeny: () -> Unit,
)
var onPermissionPrompt: ((prompt: PermPrompt) -> Unit)?   // on Engine
// The SHELL calls this after the user answers Mariner's dialog; the
// ENGINE persists the decision (PioneerStore + StorageController) and
// completes the Gecko request. Do not answer the Gecko request from the
// SHELL — the request object lives in the engine.
fun answerPermissionPrompt(prompt: PermPrompt, allow: Boolean)
// File chooser. type is "single" | "multiple" | "folder". mimeTypes is
// the input's accept list (may be empty). capture is 0 NONE / 1 ANY /
// 2 USER / 3 ENVIRONMENT. onResult(null) means cancelled / failed —
// the engine then answers the Gecko prompt with null. The SHELL must
// cancel any pending callback with null FIRST (Mariner rule, §8.7) —
// the engine enforces it by completing the previous prompt with null
// before delivering a new one.
var onFilePrompt: ((type: String, mimeTypes: List<String>, capture: Int,
                    onResult: (uris: List<android.net.Uri>?) -> Unit) -> Unit)?
// Popup request (no user gesture). targetUri may be null. The SHELL
// applies Mariner's popupStrict model (§8.8) and answers via
// answerPopupPrompt. A real new tab (gesture / target=_blank) still
// arrives as onTabCreated (Phase 2), not here.
var onPopupPrompt: ((targetUri: String?) -> Unit)?        // on Engine
fun answerPopupPrompt(allow: Boolean)
```

`EngineTab` additions in full (append to the existing interface, after
`onCrash`):

```kotlin
val detectedMedia: State<List<DetectedMedia>>
fun runMediaScan()
var onVideoPopupTap: ((url: String) -> Unit)?
var onVideoPopupLongPress: ((url: String) -> Unit)?
val isFullscreen: State<Boolean>
```

`Engine` additions in full (append after `close()`):

```kotlin
fun setBlockingEnabled(host: String, enabled: Boolean)
fun refreshFilterLists()
val filterListsState: State<String>
fun uninstallUBlock()
var onExternalDownload: ((tab: EngineTab, download: ExternalDownload) -> Unit)?
var onPermissionPrompt: ((prompt: PermPrompt) -> Unit)?
fun answerPermissionPrompt(prompt: PermPrompt, allow: Boolean)
var onFilePrompt: ((type: String, mimeTypes: List<String>, capture: Int,
                    onResult: (uris: List<android.net.Uri>?) -> Unit) -> Unit)?
var onPopupPrompt: ((targetUri: String?) -> Unit)?
fun answerPopupPrompt(allow: Boolean)
```

## PioneerStore API (STORE implements, SHELL consumes — exact)

Additions to `data/PioneerStore.kt` (owner: Worker B). Storage stays
JSON files under `filesDir/pioneer-store/`; new file `downloads.json`
for the records. `SiteSetting` already has `disableBlocking` (JSON key
`"nb"`) — no change there.

```kotlin
// ---- Phase 3: downloads (Mariner Store.kt:23-47, ported verbatim) ----
data class DownloadRecord(
    val id: Long, val title: String, val url: String, val fileName: String,
    var dmId: Long, var status: String, val addedAt: Long,
    // True for media downloads (the offline library); plain file/image
    // downloads go to the Downloads folder and stay out of the library.
    val media: Boolean = false,
    // Download progress 0–100, or -1 when unknown.
    var progress: Int = -1,
    // Where a finished download actually lives: a MediaStore content://
    // URI for public downloads on modern Android, or an absolute path
    // as a fallback. "" while the legacy location (setting-based file
    // path) is the only record.
    var contentUri: String = "",
    // True when the download started from a private tab: the record and
    // its file are wiped with the private profile and never shown in the
    // Downloads or Offline lists.
    val isPrivate: Boolean = false,
) {
    // Transient size/speed detail, in the class body so it stays out of
    // equals, copy and the persisted document. -1 means unknown.
    var bytesDownloaded: Long = -1
    var totalBytes: Long = -1
    var speedBps: Long = -1
}

val downloads: SnapshotStateList<DownloadRecord>
fun downloadsSnapshot(): List<DownloadRecord>
fun addDownload(rec: DownloadRecord)
fun updateDownload(rec: DownloadRecord)   // matched by id; persists
fun removeDownload(id: Long)              // record only — files stay
    // (Mariner rule: multi-select delete removes records, never files)
fun clearCompletedDownloads(media: Boolean)

// ---- Phase 3: site permissions + autoplay (Mariner Store.kt:1135-1149) ----
// Decision strings: "allow" | "deny" | "ask" (unset). Stored as
// setting("siteperm:$host:$resource"). The engine ALSO mirrors content
// decisions into Gecko's StorageController (§5b of the API doc); this
// store is the source of truth for what Settings shows, because
// StorageController.getPermissions is unreliable in private mode
// (bug 1723280).
fun sitePermission(host: String, resource: String): String
fun putSitePermission(host: String, resource: String, decision: String)
// Autoplay: "" (unset → global) | "allow" | "block" | "muted".
// Stored as setting("siteautoplay:$host").
fun siteAutoplay(host: String): String
fun putSiteAutoplay(host: String, mode: String)

// ---- Phase 3: private-downloads gate (Mariner LibraryScreens.kt:2219+) ----
val privateDownloadsGate: PrivateDownloadsGate
class PrivateDownloadsGate internal constructor() {
    val unlocked: Boolean          // false until the user confirms once
    fun confirm()                  // "Show private downloads?" → show
    fun hide()                     // hide again
}
```

**Setting keys carried over from Mariner — same strings, same defaults
(inventory §9 row L checks each one):**

| Key | Default | Meaning |
|---|---|---|
| `blockAds` | `"true"` | Mariner-lists ad blocking on |
| `blockTrackers` | `"true"` | Mariner-lists tracker blocking on |
| `downloadLocation` | `"public"` | `public` = phone Downloads folder; `app` = app folder |
| `confirmDownloads` | `"true"` | show the "Download file" dialog |
| `wifiOnlyDownloads` | `"false"` | queue until Wi-Fi |
| `segmentedDownloads` | `"true"` | multi-connection segmented engine when eligible |
| `autoplay` | `"allow"` | global autoplay: `allow` / `block` / `muted` |
| `playerScale` | `"Fit"` | `Fit` / `Stretch` / `Fill` / `Crop` |
| `playerSpeed` | `"1.0"` | persisted playback speed |
| `playerSkipSec` | `"10"` | skip interval seconds (Robert's ladder) |
| `playerBrightness` | `"system"` | `"system"` or 0.05–1.0 |
| `playerVolume` | (player default) | 0.0–1.0, persisted |
| `downloadNotifAsked` | (unset) | POST_NOTIFICATIONS one-time ask done |
| `dlerror:<id>` | `""` | per-record error label |
| `dldowngrade:<id>` | `""` | `"1"` = continued via system downloader |
| `videoPopupOff:<host>` | (unset) | per-site pop-up player opt-out |
| `userBlockNet` | `""` | user net rules (tab-separated, §8.1) |
| `userBlockCosmetic` | `""` | user cosmetic rules (tab-separated, §8.1) |

## Probe extension amendments (native-message contract — exact)

The probe is `pioneer-probe@pioneer` (MV2, `document_idle`). Existing
messages (Phase 2): `zoom-query` (content → app, app replies with the
stored factor) and `zoom` (app → content, `{type:"zoom",
factor}`), `media-probe` (content → app, DOM scan results). Phase 3
adds:

| Message | Direction | Payload / behaviour |
|---|---|---|
| `media-net` | background → app | `{type:"media-net", url, kind}` — a `webRequest` observation of a media URL (`.m3u8` / `.mpd` / segment / direct media). `kind` is `"video"` / `"audio"` by extension, same mapping as Mariner's `MediaLog`. **Tab attribution is a builder verification item** (§7 of the API doc, §10): the background script must identify the tab (extension `tabs` API); until that is proven, the app attributes the URL to the tab whose current host matches the URL's host, falling back to the active tab. Do not silently attribute to the wrong tab — drop the observation if it cannot be attributed. |
| `rules-update` | app → background | `{type:"rules-update", rules:{hosts:[…], scoped:[…], pathKeywords:[…], exceptions:[…], neverBlock:[…]}}` — the compiled Mariner-lists rule set (Worker A generates it from `FilterEngine`). The background script replaces its in-memory set on receipt. Sent on list refresh and on extension start. |
| `cosmetic-query` | content → app | `{type:"cosmetic-query", host}` — the content script asks for the cosmetic CSS for its host. App replies with `{type:"cosmetic", css}` (the `cosmeticCss(host)` result, ≤ 200,000 chars). Mirrors the `zoom-query` pattern. |
| `cosmetic` | app → content | `{type:"cosmetic", css}` — reply to `cosmetic-query`. The content script injects it as a `__ns_cos` style element (same element id as Mariner). |
| `video-popup-tap` | content → app | `{type:"video-popup-tap", url}` — the 40px ▶ button was tapped. App runs the `allowCheck` (§8.3) then launches the player. |
| `video-popup-longpress` | content → app | `{type:"video-popup-longpress", url}` — long-press on the ▶ button. App opens the media sheet (`runMediaScan()`). |

**Manifest changes (owner: Worker A):** add a `background` section with
`background.js`; add `blocking.js` to `content_scripts` (same
`<all_urls>` match, `document_idle`); add `"webRequest"` and
`"webRequestBlocking"` to `permissions`. `webRequestBlocking` is what
lets the background script *cancel* Mariner-list matches — without it
the lists can only observe, not block. (uBlock Origin uses the same
blocking API; that is why it works on Firefox for Android.)

AMENDED at integration (2026-11-12, Worker A — implemented, recorded here): new native messages `blocked` (background→app cancellation report) and `rules-request` (background asks for the initial rules; the reply IS `rules-update`); `rules-update` payload gains `disabledHosts`, `userNet`, `trackerHosts`, `docHosts`, `builtinHosts`, `blockAds`, `blockTrackers` beside the five named keys. Manifest also gains the `"tabs"` permission (needed for `browser.tabs.get` tab attribution). Media-net attribution stands as built per this section: host match, falling back to the active tab, dropped when unattributable — the delegate routes `media-net` through `noteNetMedia` (one sink); `BlockingObserver`'s own `media-net` branch is unreachable by design, not a bug.

## Shell behaviour spec (SHELL worker — Mariner behaviour is the spec)

Mariner sources cited by path are under
`~/workspace/notscape-mariner-rebuild/app-stage/app/src/main/java/net/notscape/browser/`.
**Port** = carry the behaviour over (code where it can be carried, fresh
Gecko code where the engine differs). **Adapt** = same behaviour, different
mechanism, reason stated. **Drop** = do not build, reason stated.

### 8.1 Blocking — uBlock Origin + Mariner's lists + per-site exceptions

**Layering (the decision — read first).** Three layers, in order:
1. **uBlock Origin (primary).** The extension, MV2 blocking
   `webRequest`. Installed from AMO, as Phase 2. It is the blocker
   Robert asked for first.
2. **Mariner's lists (second layer).** Mariner's own 7 lists + built-in
   host set + user rules, enforced by the probe's background-script
   `webRequestBlocking` observer. They exist because Mariner has no
   extension support and they are its blocker; in Pioneer they run
   **alongside** uBlock as coverage for what uBlock's lists miss and as
   the layer the app itself controls (per-site exceptions, custom lists,
   user rules).
3. **ETP Strict (third layer).** Already on in Phase 2 (runtime Strict).
   It is not a blocker for this phase's purposes; it is the cookie /
   tracker baseline underneath.

**PORT — the lists (Mariner `browser/WebTools.kt`, `FilterEngine`).**
The 7 lists, in this order — the indices matter, because a new list is
appended last so cached indices never shift:

| # | List | URL |
|---|---|---|
| 0 | EasyList | `https://easylist.to/easylist/easylist.txt` |
| 1 | EasyPrivacy | `https://easylist.to/easylist/easyprivacy.txt` |
| 2 | StevenBlack hosts | `https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts` |
| 3 | AdGuard Tracking Protection | `filters.adtidy.org/extension/ublock/filters/3.txt` |
| 4 | AdGuard Mobile Ads | `filters/11.txt` |
| 5 | uBlock Origin filters | `uAssets/master/filters/filters.txt` |
| 6 | AdGuard Base | `filters/2.txt` |

Plus the built-in host set (~150 domains: doubleclick,
googlesyndication, analytics, taboola/outbrain, propeller/popads/popcash,
applovin/unityads, fingerprintjs, coin miners, etc.) and `pathKeywords`
(`/ads/`, `/adservice`, `/pagead/`, `/gtag/js`, `google-analytics`,
`/analytics.js`, `/beacon`, `/pixel.gif`, `prebid`, `adsbygoogle`).
Storage, verbatim: `filesDir/filters/list<i>.txt` + `.bak` rollback,
`compiled.bin` cache (magic/versioned, fingerprint = every list file
length+mtime, `configHash()` over disabled indices + custom URL + custom
ABP text); sanity guards (candidate must parse non-empty, not collapse vs
current by >2x/4x; HTML/captive-portal bodies rejected by length >50,000
+ `||` / `0.0.0.0` checks); writes via staging file + rename.
Refresh: `initAsync` at startup, `refreshAsync` from Settings,
`reloadLists` re-parses from disk dropping the compiled cache.

**PORT — the matcher (`blockCategory`, exact decision order).**
`blockCategory(uri, isForMainFrame, blockAds, blockTrackers, pageHost):
Int?` — main-frame never blocked; user net rules first (CAT_OTHER=2);
`neverBlockHosts`; `$important` scoped rules; `exceptionHosts`; scoped
exceptions; builtin tracker hosts (CAT_TRACKER=1); `listHosts` (CAT_ADS=0,
or CAT_TRACKER if also in trackerHosts); scoped rules; pathKeywords
(CAT_ADS). Categories: CAT_ADS=0, CAT_TRACKER=1, CAT_OTHER=2. Scoped
rules honour `$domain=` / `$domain=~`, path prefix, `$third-party`,
`$important`, `$document` / `$popup`. `isDocumentBlocked(uri, pageHost)`
for `$document` / `$popup` popunder guard. `isDocumentHost(host)` for
pure `||host^$document/$popup` rules. Settings keys: `blockAds`
(default `"true"`), `blockTrackers` (default `"true"`), disabled list
indices parsed by `parseDisabledFilterLists("1,3")`, custom list URL,
custom ABP text (`validateAbpRules` returns 1-based bad line numbers).

**PORT — user rules (`UserBlockRules.kt`).** Tab-separated keys
`userBlockNet` / `userBlockCosmetic`. Net rules `net\tHOST\t<host>` /
`net\tURL\t<scheme://host/path>` (query/fragment stripped at save);
cosmetic `cos\t<page host>\t<selector>` (exact host only);
`isSafeSelector` (no `{};<>` newline tab). Net rules never block
main-frame. `netMatches` / `selectorsForHost` pure and unit-testable.

**ADAPT — enforcement point.** Mariner enforces in
`WebViewClient.shouldInterceptRequest` (GET/HEAD only — POST/PUT uploads
and sign-ins are never blocked; that Phase G repair carries over).
GeckoView has no such hook. Enforcement moves to the probe background
script: `webRequest.onBeforeRequest` with `webRequestBlocking`,
returning `{cancel: true}` for matches. The Kotlin `FilterEngine`
remains the source of truth (unit tests, Settings UI, rule generation);
the background script runs a JS mirror of `blockCategory` over the rule
set the app pushes via `rules-update` (§7). **The two matchers must agree:**
the scorecard (§9 row A) checks both against the same sample-URL table.
Per-tab blocked count resets on navigation; the session count +
`blockedHistory()` show in Settings. `FilterEngine.noteBlockedFor(tabId,
category)` is fed by the background script's reports (via native
messaging) and by ETP's `onContentBlocked` (API doc §6, telemetry only).

**ADAPT — cosmetics.** Mariner's `cosmeticCss(pageHost)` — domain
selectors first, then user, then generic; `$generichide` suppresses
generic only; `#@#` exceptions subtract; 200,000-char budget, selectors
chunked 25 per rule (`{display:none!important}`) — is ported verbatim
as a pure function. Injection is adapted: Mariner injects at document
start via `evaluateJavascript` on `onPageStarted` / `onPageCommitVisible`
/ `onPageFinished`; Pioneer has no JS eval, so the probe content script
asks for the CSS (`cosmetic-query`) and injects it at `document_idle`
as the same `__ns_cos` style element, skipping `$generichide` hosts by
walking `location.hostname` up parent domains. **Known consequence:**
ads may flash before `document_idle` — recorded in §10, not hidden.

**PORT — per-site exceptions.** `SiteSetting.disableBlocking` (JSON key
`"nb"`, already in `PioneerStore`). Checked at every enforcement point
and in cosmetic injection and the popunder guard. The site-info sheet
row "Ad blocking" toggles it. Applying it in Pioneer means: ETP
exception via `StorageController.setPermission(PERMISSION_TRACKING,
VALUE_ALLOW)` + Mariner-lists bypass (the `rules-update` set carries the
exception hosts; the background script skips them) + cosmetic bypass.
**It does NOT disable uBlock Origin on that site** — the app cannot
toggle a per-site exception inside uBlock (API doc §5a). The site-info
row must say what it disables; §10 records the gap.

### 8.2 Downloads — the stack (Mariner `media/Downloads.kt`, 4,184 lines)

**PORT — entry.** `WebView.setDownloadListener` becomes
`ContentDelegate.onExternalResponse` (§5 of the API doc). The engine
parses `WebResponse.headers` into `ExternalDownload` (§5) and the shell
shows Mariner's dialog. PDF (`application/pdf` or `.pdf` URL) is
**adapted**: Mariner diverts PDFs to its in-app PDF viewer and they never
enter the download flow; Pioneer has no PDF viewer in Phase 3, so PDFs
**download as files** through the normal flow. The viewer is a later
phase, not a Phase 3 fake.

**PORT — the confirm dialog (Mariner `browser/BrowserScreen.kt:5883`,
FrostDialog "Download file").** Exact UI: URL (2 lines, ellipsis,
`bodySmall`, `onSurfaceVariant`); "File name" `OutlinedTextField`
(single line); "Save to" label; "✓ Downloads folder" / "✓ App folder"
`TextButton` rows (full width, start-aligned); private downloads: no
public choice, text "Private downloads always save to the app folder.";
"Quality" variant rows when HLS/DASH variants were fetched while the
dialog is up (default: highest variant); "Don't ask again" checkbox
(sets `confirmDownloads` to `"false"`). Confirm button "Download";
dismiss cancels. `beginDownload` / `beginMergeDownload` logic verbatim:
`confirmDownloads` default true → dialog (editable name, location
public/app; private always app); else `Downloads.start(...)` + toast
"Download started". Merge variant: same dialog; toast "Merging into one
MP4". A download from a link-opened tab's first load closes that tab
back to its opener (shell behaviour — carry it over).

**PORT — `Downloads.start` and the engines.** Filename resolution on a
background thread: HEAD request honours Content-Disposition + real
content type (an extension-less image URL still lands as e.g.
`photo.jpg`); HLS → `.ts`, DASH → `.mp4`; `sanitize()` (max 100 chars,
`[^A-Za-z0-9._-]`→`_`, blank→`"download"`); per-download rename override
keeps the engine extension; cookie snapshot at start (host-scoped,
replayed only to its own host); job bundle saved in the store;
`promoteQueued` gate. Routing (`routeNewDownload`): Wi-Fi-only +
metered → record stays `queued` ("Waiting for Wi-Fi"); DASH →
`DashDownloadWorker`; HLS → `HlsDownloadWorker` (segments fetched +
joined into one playable file; survives process death; exponential
backoff 30s); segmented-eligible plain downloads (setting
`segmentedDownloads` default true; probe: Range support + total size) →
`SegmentedDownloadWorker`; else Android `DownloadManager` (single
connection, system-managed). MAX_CONCURRENT_DOWNLOADS = 3, oldest-first
queue. Pause/resume only for resumable engines (hls/dash/seg — staging
files + journal kept); cancel; retry (an in-progress download after a
failed attempt is shown as retrying); `dlerror:<id>` error label;
`dldowngrade:<id>` = "Continued via system downloader".
**Dependencies (Worker B adds):** `androidx.work:work-runtime-ktx:2.10.0`
and the Media3 `1.9.4` set exactly as Mariner declares it
(`media3-exoplayer`, `media3-exoplayer-hls`, `media3-exoplayer-dash`,
`media3-ui`, `media3-common`, `media3-session`, `media3-transformer`).
Pioneer currently has neither WorkManager nor Media3.

**PORT — destinations.** Public =
`Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` via
MediaStore publish (`uniqueMediaStoreName`, `"(1)"` suffix on collision,
raw-file check as a second oracle since MediaStore is blind to
sideloaded files); app =
`getExternalFilesDir(DIRECTORY_MOVIES)/notscape/`. Media downloads
(`media=true`, from Find media) belong in the offline library; ordinary
file/image downloads go to the phone Downloads folder and stay out of
the library. Private downloads: notification title generic
("Downloading • N%"), never in public lists.

**PORT — notifications.** Per-record IDs (record id folded to 30 bits,
bit 30 separates progress/terminal); foreground-service progress notice
+ ordinary completion notice; POST_NOTIFICATIONS one-time ask
(`DownloadNotifAsk.kt`, key `downloadNotifAsked`, SDK 33+, flag set when
the dialog is launched whether granted or denied, never re-asked;
recovery via system app-notification settings; pre-33 always true).
**Manifest (Worker B adds):** `POST_NOTIFICATIONS`,
`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`,
`SystemForegroundService` with `foregroundServiceType="dataSync"`.
Pioneer's manifest today has only `INTERNET` +
`ACCESS_NETWORK_STATE`.

**DROP — EPUB auto-shelf.** Mariner shelves downloaded EPUBs into the
reading list (Phase E). Reader is Phase 4. Dropped for Phase 3 with this
reason recorded; EPUBs download as files like any other.

### 8.3 Media detection + pop-up player (Mariner `browser/WebTools.kt`, `VideoPopup.kt`)

**PORT — `MediaLog.inspect` (the net half).** Stream regex
`\.(m3u8|mpd|mp4|webm|mkv|mov|mp3|m4a|aac|ogg|opus|flac|wav|m4v|ts)(\?|#|$)`;
`blob:` excluded; deduped per tab; kind audio vs video by extension;
label "stream (HLS)" / "stream (DASH)". In Pioneer the observations come
from the background `webRequest` observer (`media-net`, §7), not from
`shouldInterceptRequest`. Same regex, same dedupe, same labels.

**PORT — `MediaDetect.JS` (the DOM half).** Scans `video`/`audio`
`currentSrc`/`src` + `<source>`, `a[href]` media links (label = link
text ≤ 80 chars), recurses into same-origin iframes, cross-origin
player iframes (> 200px wide, src matching
`player|video|embed|stream|watch`) as kind `page`, `og:video` /
`og:video:url` / `twitter:player:stream` meta. `parseDetectedMedia`
reclassifies embed-page URLs to kind `page` (`isEmbedPageUrl`:
youtube.com/embed/, youtube-nocookie.com/embed/, youtube.com/watch,
youtu.be/, player.vimeo.com/video/, dailymotion.com/embed/, twitch.tv +
/embed) — **embed pages are never offered to the player**. The DOM half
already exists in the Phase 2 probe; Worker C keeps it and adds the
`media-net` merge: `detected = (dom + net).distinctBy { url }`.

**The MSE / blob verdict (state it bluntly — do not soften it).**
The Gate 1 probe saw 0 URLs on every page including the YouTube watch
page, while the video played. That is expected, and it is structural:
- The probe content script sees `video.currentSrc = "blob:…"` — no
  usable URL. `performance` resource entries do not expose MSE segment
  URLs as playable media.
- Patching `window.MediaSource` / `SourceBuffer.appendBuffer` from a
  content script **does not affect page JS** (isolated world); main-world
  injection via an injected `<script>` element could patch them, but it
  yields opaque ArrayBuffers, not URLs, and page CSP may block inline
  scripts. **Not the route.**
- **The route that closes the gap is the background-script `webRequest`
  observer** (API doc §10): it sees every network request including
  XHR/fetch of `.m3u8` / `.mpd` manifests and media segments (e.g.
  googlevideo segment URLs) — the GeckoView equivalent of Mariner's
  `shouldInterceptRequest` sniffing.
- **Direct-URL recovery for MSE playback is impossible in principle:**
  no URL exists for an MSE stream. WebRequest-observed segment URLs are
  playable but transient and signed; the player must accept them as-is
  (pass-through, no re-fetch without the page's cookies — the download
  path snapshots cookies host-scoped for the same reason). YouTube
  watch-page detection therefore depends on the webRequest observer
  working in the probe; DOM probing alone cannot close it. If the
  observer does not fire on 159, the scorecard row fails honestly (§9).

**PORT — `MediaSession` (supplementary only).** `MediaSession.Delegate`
(API doc §7) gives title / play-pause state for pages using the Media
Session API. It is **not** a detector: since v83 `onMetadata` is not
dispatched for plain media elements. Worker C wires it as a
now-playing signal feeding `detectedMedia` with `source="session"` and
kind `"page"` where metadata exists — never as the sole source.

**PORT — the media sheet (Worker C, `ui/MediaSheet.kt`).**
Trigger: toolbar media button → `runMediaScan()` → sheet. The button is
visible only when `MediaLog.forTab(activeTab)` is non-empty and the tab
is not the start page. Long-press on the toolbar button plays the first
detected video (`playFirstDetectedVideo()`; toast "No video on this
page" if none). Sheet "Media on this page (N)" (`FrostSheet`, max 60%
screen height): per item Play, PiP (video only, launches the player
straight into PiP), Download, Cast (only if `castAvailable &&
CastHelper.isRemote(url)` — **tap is Phase 4**, §10), Add to playlist
(if allowed); merge checkboxes (select 2 → "Merge" → merge into one
MP4); footer per-site pop-up opt-out toggle. Empty state text, verbatim:
"No playable media was found on this page. If a video is playing, give
it a few seconds and try again — streams are spotted as the page loads
them."

**PORT — the pop-up player (`VideoPopup.kt` watcher).** The watcher JS
(document-start in Mariner; `document_idle` in the probe — same
adaptation as cosmetics) runs top frame only: largest playing `<video>`
(not paused, `currentTime > 0`, `readyState >= 2`, non-blob `currentSrc`,
≥ 160×90, in viewport); one 40px ▶ button top-right corner;
re-positions on scroll / resize / fullscreen / `play` / `pause` /
`ended` + 1s interval; hidden when the video pauses / leaves the
viewport / goes fullscreen; never pauses / mutes / seeks the page video;
never intercepts taps outside its box. Per-site opt-out key
`videoPopupOff:$host`; `window.__nsVideoPopupOff` set on navigation.
Bridge: `video-popup-tap` / `video-popup-longpress` native messages
(§7) replacing Mariner's `notscapeVideoPopup.onVideoTap` /
`onVideoLongPress`. `allowCheck(url, known)` anti-spoof, verbatim:
http(s) only, not blob, must equal a detector-seen entry with kind
`video`. Tap → `PlayerActivity.launch`; long-press → `runMediaScan()`
(open the sheet).

### 8.4 Downloads UI — the screens (Mariner `ui/LibraryScreens.kt`)

**PORT — `DownloadsScreen` ("Downloads") and `LibraryScreen`
("Offline library"), exactly.** Both are `SubScreen` pages. Shared
chrome, both screens: search `OutlinedTextField` ("Search downloads" /
"Search library"); "Select" / "Done" multi-select toggle;
"Delete records (N)" when a selection is non-empty (**records only,
files stay**); sort cycle newest → oldest → title ("Sort: newest" etc.);
"Refresh status" / "Refreshing…" (`Downloads.refresh`); "Clear
completed"; "Clear leftover files"; notification-off banner with
"Enable" → system app-notification settings.
**Rows** (`KitGroupedRow` in a `SettingsCard` under
`GroupTitle("Downloads")`): leading Download icon (or Checkbox when
selecting); title (`bodyLarge`, 1 line, ellipsis); supporting lines:
`"{status} • {progress}% • {fileName}"` (progress only when active and
≥ 0), `progressDetailLine` (size / speed / ETA), `dlerror` text in
`error` colour (failed or active), "Continued via system downloader"
when `dldowngrade=1`, `LinearProgressIndicator` when active;
"Waiting for Wi-Fi" when `isWaitingForWifi` and downloading/queued;
"Retrying…" (`R.string.download_status_retrying`) when downloading
after a failed attempt. Trailing actions: Pause (resumable engines
only), Resume (paused + resumable), Cancel (active), open (complete +
source exists), play offline / share / export (CreateDocument) for
media. **LibraryScreen shows media only** (`rec.media &&
!rec.isPrivate`); **DownloadsScreen shows non-media**
(`!rec.media && !rec.isPrivate`).
**Private downloads (both screens):** `GroupTitle("Private downloads")`,
gated by `privateDownloadsGate` — "Private downloads are hidden" +
confirm dialog "Show private downloads?" ("Private downloads are hidden
by default. Showing them lists files downloaded in private tabs, until
you hide them again.") + "Hide private downloads" button. Private
records never appear in the public lists.

### 8.5 Video player — exact port (Mariner `media/PlayerActivity.kt`, 2,351 lines)

**PORT the file. Do not redesign it.** Same package path (`media/`),
same intent extras (`queue_urls` / `queue_titles` / `start_pip`), same
`launch(context, url, title, referer?, privateSession=false,
startPip=false)` and `launchQueue(urls, titles, index, referer?)`.
Media3/ExoPlayer. The player is a `ComponentActivity` with no system
bars over video, ever. What follows is the inventory the worker checks
off line by line; the file itself is the spec.

- **Top-left capsule** (`playerCapsule`, `RoundedCornerShape(50)`,
  the solid-frost recipe — surface gradient, ink hairline, soft shadow;
  every capsule, pill and circle shares this chain):
  Close ("Close player"), PiP (`PictureInPictureAlt`, "Picture in
  picture"), Stop ("Stop playback") — 40dp `IconButton`s, 20/19dp icons.
  `AnimatedVisibility` fade in/out; visible when `controlsVisible &&
  playerError == null && !controlsLocked`.
- **Centre transport** — separate frosted circles (`FrostCircle`):
  Previous / Next 48dp (only when `urls.size > 1`); skip back / forward
  56dp with `SkipBackIcon(skipSec)` / `SkipForwardIcon(skipSec)`; play /
  pause 76dp. While buffering: the play button fills and empties in a
  steady 1600ms cycle (`primary` at 0.35 alpha — the address bar's
  loading fill, looping, because a buffer has no honest progress); the
  skip buttons are replaced by 56dp spacers (a seek into a stall cannot
  help; the spacer keeps the play button centred).
- **Bottom** — gradient scrim (transparent → black 0.55),
  `navigationBars` insets, 20dp sides, 30dp top, 12dp bottom. Above the
  options capsule, right-aligned capsule pill rows (one open at a time,
  selected item in `primary`): scale row (Fit / Stretch / Fill / Crop),
  quality row ("Auto" + tracks, horizontally scrollable), audio row
  (track labels), text row ("Load subtitles…" + "Off" + tracks).
- **Options capsule** — host (`bodySmall`, white 0.65) + title
  (`titleMedium`, SemiBold, white, 1 line, ellipsis) on the left; the
  capsule on the right holds, in order: Listen mode (`Headphones`,
  "Listen mode" / "Show video"), Audio track (`Audiotrack`, only when
  > 1 track), Video quality (`HighQuality`, only when > 1 track/quality
  and not listen mode), Queue (`QueueMusic`, only when > 1 URL),
  Subtitles (only when text tracks exist), Lock (locks controls — the
  lock cover swallows every gesture except the unlock button:
  `LockOpen` 48dp + "Tap to unlock"), Cast (MediaRouteButton via
  `CastHelper.createMediaRouteButton` + `CastButtonFactory`, only when
  `castAvailable` — **tap is Phase 4**, §10), Video scaling
  (`AspectRatio`), Speed `TextButton` cycling 0.5 / 0.75 / 1.0 / 1.25 /
  1.5 / 2.0× (label `"1×"` / `"1.5×"`, persisted `playerSpeed`), Sleep
  `TextButton` cycling 0 / 10 / 20 / 30 / 60 min (label `"Sleep"` /
  `"30m"`, `primary` when set; a `LaunchedEffect` pauses after the delay).
- **The floating pill — the scrubber.** Portrait: scrubber + both times
  ride in **one floating pill** (`fillMaxWidth().playerCapsule()`,
  14dp/2dp padding) — current time, `Slider` (11dp round thumb, 4dp
  track), remaining `"−mm:ss"`. Landscape: the same row floats on the
  scrim and stays white. Scrub seeks **on drag end only**
  (`sliderScrub` preview while dragging — the old code seeked on every
  tick and the player rebuffered under the drag).
- **Brightness + volume slider pills** (same pill treatment as the
  scrubber in portrait, bare rows in landscape): "Brightness" 76dp label
  + `Slider` 0.05–1 + "System" / "System ✓" `TextButton`
  (`playerBrightness`, `"system"` default; the window's
  `screenBrightness`, which overrides device auto-brightness only while
  this player is open); "Volume" + `Slider` 0–1 (`playerVolume`,
  `activePlayer.volume`) + `"NN%"`.
- **Gestures (four `pointerInput` layers, in this order — do not merge
  them):** (1) tap detector — `onPress` hold-to-scan armed after 480ms
  (left half −1, right half +1; it lives in the SAME tap detector as the
  tap — a separate `pointerInput` swallowed plain taps, Phase G repair);
  `onDoubleTap` — centre while zoomed resets the zoom ("Zoom reset"),
  left −10s, right +10s (**fixed 10s, not `skipSec`**); `onTap` toggles
  controls. (2) drag — zoomed in: one-finger drag pans the picture
  (clamped); otherwise vertical drag (|dy| > |dx|, > 24px): left half
  brightness (window), right half device volume (`STREAM_MUSIC`), label
  "Brightness NN%" / "Volume NN%"; horizontal drag > 60px: seek preview
  (±120s across the full width), committed on drag end. (3) pinch
  tracking via `awaitPointerEventScope` (≥ 2 pressed pointers =
  `transformActive` — `detectTransformGestures` has no start/end
  callbacks). (4) `detectTransformGestures` — pinch zoom 1–3× + pan
  (clamped), disabled in listen mode; `pokeControls()` on pinch.
  Gesture label: volume/brightness → slim level pill top-right (18dp
  icon + 72×4dp bar); anything else → centred white `headlineSmall` on
  black 0.6.
- **Scale:** `ScaleMode` Fit / Stretch / Fill / Crop (persisted
  `playerScale`, default Fit); Crop base scale 1.25×; pinch scale 1–3×;
  effective scale = base × pinch; Fill/Crop → `RESIZE_MODE_ZOOM`,
  Stretch → `RESIZE_MODE_FILL`, Fit → `RESIZE_MODE_FIT`; zoom resets on
  track change; listen mode resets zoom and hides the scale row.
- **Queue:** material3 `AlertDialog` with a `LazyColumn`, "Close"
  `TextButton` (Mariner lines 978–1009 — port the dialog, not a fresh
  sheet).
- **Subtitles:** VTT/TTML, sibling discovery, stored under
  `filesDir/subs`; subtitle picker launcher `arrayOf("*/*")`; toast
  "Could not load that subtitle file" on failure.
- **PiP:** Android `enterPictureInPictureMode(PictureInPictureParams…)`
  via `onEnterPip`; `startPip` intent extra launches straight into PiP.
  (GeckoView's own PiP API is v160 — not available; see API doc §8.)
- **Player settings keys** (all via `PioneerStore.setting()` /
  `putSetting()`): `playerScale`, `playerSpeed`, `playerSkipSec`,
  `playerBrightness`, `playerVolume`. No Store file change for the
  player.
- **Cast button:** rendered exactly as in Mariner (position, icon,
  gating on `castAvailable`). Its tap target is Phase 4 — in Phase 3 it
  is present but does not launch a session (§10). The worker does not
  decide this alone: the choice (present-but-deferred vs hidden until
  Phase 4) is recorded at integration by the coordinator.

### 8.6 Fullscreen video (in-page)

**PORT — Mariner `WebChromeClient.onShowCustomView` behaviour, ADAPTED
to `ContentDelegate.onFullScreen` (API doc §2).** On enter: the page's
fullscreen element stays inside the `GeckoView`; the app hides the
system bars (`setSystemBarsHidden(activity, true)`), hides the browser
chrome, and sets `EngineTab.isFullscreen = true`. On exit
(`onFullScreen(session, false)`): bars restored, chrome restored,
`isFullscreen = false`. There is no custom-view object to add/remove
(unlike WebView) — the worker must not go looking for one. The pop-up
player's watcher hides its ▶ button while a video is fullscreen (§8.3).

### 8.7 Permissions (Mariner `browser/BrowserScreen.kt:3078-3160`, `:522-596`, `:5841`)

**PORT — the dialog (FrostDialog, `BrowserScreen.kt:5841`), exactly.**
`onDismissRequest` = deny (a pending permission request must never hang
waiting for an answer — dismissing without answering denies).
Title: `"Allow $host to use"`. Text: the resources joined with `", "`,
mapped camera → `"your camera"`, microphone → `"your microphone"`,
anything else → `"your location"`, + `"?"`. Confirm "Allow": stores
`putSitePermission(host, res, "allow")` for each resource, then
`grantWithOsCheck`. Dismiss "Deny": stores `"deny"` for each, calls
`onDeny`.

**PORT — the decision flow.** Camera/mic arrive as
`onMediaPermissionRequest`; geolocation arrives as a content permission
(`PERMISSION_GEOLOCATION` — API doc §5a; there is no separate
geolocation callback in GeckoView). Wanted resources map
camera/microphone; anything else → deny. Stored per-site decisions
`siteperm:$host:$resource` = `"allow"` / `"deny"` / `"ask"` (unset).
Mixed allow/ask: ask only about the undecided ones; denying still grants
the decided ones. `grantWithOsCheck(prompt, resources)`: missing Android
permissions first (CAMERA / RECORD_AUDIO / ACCESS_FINE_LOCATION via a
`RequestMultiplePermissions` launcher); granted → `prompt.onGrant`;
the launcher's result handling is ported verbatim from Mariner
(`pendingPermGrant` + the result callback at `BrowserScreen.kt:5867` —
the worker reads it, does not invent partial-grant behaviour).
Geolocation: allow → `grantWithOsCheck` then grant with retain flag
always `false`; deny → deny; undecided → the same dialog. **An
unanswered media-permission request must be answered** (grant or reject)
or the page's `getUserMedia` promise hangs (API doc §5d); the engine
answers `reject()` on tab close / navigation if a prompt is outstanding.
**OS-permission denials are not stored by GeckoView** — the app stores
them itself, as Mariner does.

**PORT — autoplay.** Global setting `autoplay` (default `"allow"`),
per-site `siteautoplay:$host` (`"allow"` / `"block"` / `"muted"` / `""`
unset → global). `"block"` → media must wait for a user gesture;
`"muted"` → media starts muted until first pointerdown/keydown. In
GeckoView this maps onto the autoplay content permissions
(`PERMISSION_AUTOPLAY_AUDIBLE` / `PERMISSION_AUTOPLAY_INAUDIBLE`,
API doc §5a) with the global setting as the default answer and the
per-site value overriding it; the `"muted"` mode is implemented by the
probe (mute all media until first pointerdown/keydown), since there is
no Gecko-side "start muted" switch. The worker verifies the exact
mapping against the 159 runtime and records any deviation.

**DROP — web notifications.** Mariner has no web-notification
permission handling (WebView doesn't support the Notification API
prompts). In Pioneer `PERMISSION_DESKTOP_NOTIFICATION` arrives via
`PermissionDelegate`; Phase 3 **denies it by default with no UI**.
Adding a prompt would be new scope, not parity. Recorded here so the
worker does not invent one.

### 8.8 File chooser

**PORT — Mariner's behaviour, which has no custom UI.** Mariner:
`onShowFileChooser` cancels any pending callback with `null` first,
`fileChooserParams.createIntent()` → ActivityResult launcher; null
intent or launch failure → callback `null`, return false; else return
true. Pioneer (`PromptDelegate.onFilePrompt`, API doc §3): same rules —
cancel the pending prompt with null first; single / multiple / folder
(`Type.FOLDER`, v138) via the matching ActivityResult contract
(`GetContent` / `GetMultipleContents` / `OpenDocumentTree` as the
worker judges from the `Type`); null or launch failure → answer null.
**The `content://` trap (API doc §3):** confirm with a `file://` URI —
copy a picked `content://` file to a temp file first, because the
prompt's file resolution returns null for SAF URIs without a `_data`
column. Verify on device (§9 row I). Capture (`capture` 0–3) is passed
through to the intent where the contract supports it; if a capture mode
has no Android equivalent, the worker records it rather than faking
one.

### 8.9 Popups

**REBUILT (alpha.13, Robert agreed 2026-11-12) — one popup controller,
`engine/PopupController.kt`.** The old design split the decision across
four pieces (`expectPopupSession` boolean, PromptDelegate pending fields,
`handlePopupPrompt` in the shell, popunder-guard capture); the rebuild
replaces all four with a single per-source-tab state machine (pending →
allowed / denied / captured) holding the Gecko request, the target URI,
and the source tab together until answered. No global boolean, no
cross-tab mis-marking.

- **Decision (items 1+2):** `onPopupPrompt` maps the session to its
  source tab and evaluates `allowPopups` / `blockPopups` for that tab's
  host, never `currentTab`'s. `popupStrict` (default true). Allowed →
  a real new tab (`isPopupWin=true`, created by the engine as in Phase
  2's `onNewSession` path). Denied → captured, never loaded.
- **Allowed popup (items 3+4):** once Gecko opens the returned session,
  the app sets it active and focused, binds the view on the explicit
  `sessionOpened` signal, and switches to it. The GeckoView 159 API
  evidence does not settle whether Gecko loads the `onNewSession` URI
  into the returned session (API doc §9 is silent; only a code comment
  claimed it), so the app loads the URI itself after Gecko's open, ONLY
  when the session is still blank — correct whether or not Gecko loads
  it. While the load is pending the tab shows a loading state, never
  the start page. The device check (Copy status after tapping Full Show
  on watch-wrestling.eu) is the verification: discriminator lines
  "tab M: window.open -> tab N", "tab N: loading <url>", "load
  OK/FAILED", plus "tab N: Gecko did not load the new-session URI —
  loading it" when the fallback fires.
- **Denied popup (item 5):** captured from two sources — `prompt.targetUri`
  AND the popunder guard's capture of the opener's next gesture-less hop
  (covers target-less `window.open()`). Blocked URLs collect into the
  controller's queue (max 5) and a notice is shown, unless per-site
  `blockPopups` ("Always deny" silences completely). "Open once" /
  "Open all" work off the captured list.
- **Popunder guard (item 6):** Mariner's rule (gesture-less main-frame
  hop to a different host covered by `FilterEngine.isDocumentBlocked` →
  captured as a blocked popup), implemented in `onLoadRequest` — the
  earlier "approximation" note was stale: the implementation already
  used `onLoadRequest`, not `onLocationChange` + probe observation.
  Two blind spots fixed: the "here" host falls back to the opener
  tab's host for a fresh popup tab (its own URL is blank), so redirect
  hops inside a popup tab's load chain are capturable (deny + capture
  with notice, not silent deny).
- **Bar and view (item 7):** the address bar follows the *bound*
  session's URL (`boundTabId`, set only on a successful bind, never
  reset), not the active tab's recorded URL; the view re-binds
  deterministically on the `sessionOpened` signal.
- **Self-closing popup (item 8):** `onCloseWindow` still closes only
  popup tabs, and now leaves a "Popup closed" status line + a brief
  toast when it was the selected tab. Engine-closed tabs never show
  the "Tab closed / Undo" bar.
- **Timers (item 9):** the "Tab closed" snackbar has an explicit Short
  duration and is single-shot (no second bar queues while one is
  showing). The popup notice's 12s auto-dismiss hides only — it no
  longer clears the captured queue; the queue clears on the user's own
  dismiss or on opening the URLs.
- **Tests (item 10):** `PopupControllerTest` covers the decision matrix
  (strict off allows; strict on default DENY_CAPTURE; strict on +
  whitelisted allows; strict on + blacklisted DENY_SILENT; allow beats
  block) and capture rules (order, dedupe, blank/about:blank ignored,
  cap 5).

## Gate 3 scorecard (side-by-side parity vs WebView Mariner)

Gate 3 is a scorecard, not a vibe check. Every row names the exact test
pages / procedure, the measurement method, and whether it is
CI-measurable or device-owed (Robert's Pixel 10a, the same pages in both
browsers, back to back). A row is **PASS** only when the measurement was
actually run and the numbers match; anything else is **OWED** or
**FAIL** — never "probably fine". The scorecard is filled in at Gate 3
by whoever runs it (Pip verifies from evidence, not from a worker's
word).

| Row | What is compared | Test pages / procedure | Measurement | CI / device |
|---|---|---|---|---|
| A | Blocking: lists + matcher | Fixed sample-URL table checked into the repo (ads, trackers, first-party, `$important`, `$domain`, `$document`, exceptions, user rules). Run Mariner's `FilterEngine` verdicts (from the Mariner source) and Pioneer's Kotlin verdicts, and the probe background script's JS verdicts. | Verdict per URL (block category / allow) must agree across all three. List parse counts (rules per list) reported per list. | **CI** — Kotlin unit tests (`FilterEngineTest`, `UserBlockRulesTest`). JS verdicts: checked by the builder against the same table in the probe's test page; if Node is available at integration the JS matcher is run in CI too — the coordinator decides, the worker does not invent a runner. |
| B | Blocking: live pages | Same ad-heavy pages in Mariner and Pioneer, back to back on the Pixel 10a (Robert's own regular pages + a news homepage + a video site). | Per-tab blocked count from the site-info sheet + visible ads (screenshot pair). Counts must be in the same band (exact equality is not expected — uBlock + lists + ETP overlap differently); any page where Pioneer shows ads Mariner blocks is a FAIL with the URL recorded. | **Device-owed** |
| C | Blocking: per-site exception | One ad-heavy page; toggle "Ad blocking" off in the site-info sheet in both browsers; reload. | Ads/trackers load again (ETP + lists off). uBlock keeps blocking in Pioneer — **recorded as the known gap (§10)**, not counted as a fail of this row; the row passes when the ETP + lists half behaves. | **Device-owed** |
| D | Finder: direct + HLS + DASH | Direct-mp4 test (`flower.mp4` — known good from Gate 1: probe saw 1 URL), an HLS test stream page, a DASH test stream page, a plain `<video>` page. | Media sheet lists the URL with the right kind/label in both browsers; Play opens the player; Download starts the right engine. | **Device-owed** |
| E | Finder: YouTube / MSE | YouTube watch page (a video Robert picks) in both browsers. | Pioneer media sheet lists a playable entry (via the `webRequest` observer) where Mariner lists one. If the observer does not fire on 159, this row is FAIL with the cause recorded — DOM-only detection does not pass this row. | **Device-owed** |
| F | Downloads: unit behaviour | Filename sanitise / resolve table, routing-decision table (Wi-Fi-only, DASH, HLS, segmented-eligible, plain), `sanitize()` edge cases. | Unit tests assert the same outputs as Mariner's functions on the same inputs. | **CI** |
| G | Downloads: live | One plain file, one image, one HLS stream, one DASH stream, one merge (video+audio) — public location and app location, pause/resume/cancel each once, one Wi-Fi-only queued download. | File lands in the right place with the right name and plays; Downloads screen rows show the right status / % / size-speed-ETA / error text; notifications show progress then completion; private download stays out of the public lists with a generic notification title. | **Device-owed** |
| H | Player UI: exact match | Same video in Mariner's player and Pioneer's player, side-by-side screenshots: top capsule, centre transport, options capsule + each pill row (scale / quality / audio / subtitles), portrait scrubber pill, brightness + volume pills, lock cover, queue dialog. Then the gesture set: double-tap ±10s, centre double-tap zoom reset, hold-to-scan, drag seek / brightness / volume, pinch zoom 1–3× + pan. Speed cycle 0.5→2.0×, sleep cycle 0→60m, listen mode, PiP enter. | Screenshot pairs must match (layout, controls, labels, icons). Gestures must produce the same result in both. Any difference is listed, not waved through. | **Device-owed** |
| I | File chooser | A page with `<input type=file>`, one with `multiple`, one with `accept="image/*"`, one folder upload. Pick a file from the device (including a SAF `content://` pick, e.g. from Downloads). | The picked file arrives in the page (or the upload completes) in both browsers. The `content://` pick is the trap row (API doc §3) — if it returns null in Pioneer, FAIL. | **Device-owed** |
| J | Permissions | `https://permission.site` (camera, microphone, geolocation) + an autoplay test page, in both browsers. Answer allow / deny / dismiss per prompt; revisit the site; check the stored decision is honoured; deny the OS permission once and confirm the site is not granted. | Dialog text matches Mariner's ("Allow $host to use" + resource words); decisions persist across a restart; OS denial never grants the site. | **Device-owed** |
| K | Popups | A popup-test page (user-gesture popup → new tab; scripted popup → blocked + notice; popunder hop → blocked) + watch-wrestling.eu Full Show tap. | New tab opens for the gesture popup **with content** (loading state, never the start page; bar shows the bound session's URL); blocked popups collect (max 5) with the notice; `blockPopups` silences; self-closing popup leaves a "Popup closed" trace. Copy-status discriminator lines are the evidence: "tab M: window.open -> tab N", "tab N: loading <url>", "load OK/FAILED", plus "tab N: Gecko did not load the new-session URI — loading it" if the item-4 fallback fires. | **Device-owed** |
| L | Inventory diff (the 22:47 lesson) | **From the code, not from eyeballing:** every Mariner setting key, menu row and feature in Phase 3 scope, checked against Pioneer. | A written list: carried over / adapted (with reason) / dropped (with reason) / missing. Any missing item is a FAIL of this row until it is carried over or dropped with a recorded reason. | **CI-adjacent** — produced by reading code, verified at Gate 3 from the diff. |
| M | Material You | Same wallpaper on the Pixel 10a; open the player, a dialog, and a sheet in both browsers. | Dynamic palette applies (colours follow the wallpaper) in the Phase 3 build exactly as in Mariner. **Fix only if broken** — this row is a check, not a rebuild. | **Device-owed** |
| N | Fullscreen video | An in-page video with a fullscreen button, in both browsers. | Enter: system bars hide, chrome hides, video fills the screen. Exit: everything restores. The pop-up ▶ button hides while fullscreen. | **Device-owed** |

**Rows that are CI-measurable:** A (Kotlin half), F. Everything else is
device-owed on Robert's Pixel 10a. That is by design: blocking,
downloads, media, permissions and the player are all behaviours that
only exist on a real phone against real pages. **Do not invent a CI
substitute for a device row** (e.g. do not claim a screenshot pair from
an emulator is a Pixel 10a result).

## Known gaps to record (do not hide them)

1. **Per-site "disable blocking" does not disable uBlock Origin.**
   The app cannot toggle a per-site exception inside uBlock. The toggle
   disables ETP (via `PERMISSION_TRACKING`) + Mariner's lists + cosmetics
   only. The site-info row must say so.
2. **Cosmetics inject at `document_idle`, not document start.** Ads may
   flash before the sheet applies. Mariner injects at document start.
3. **MSE direct-URL recovery is impossible in principle.** There is no
   URL for an MSE stream. The `webRequest` observer recovers manifest /
   segment URLs, which are transient and signed — playable, but not
   stable identifiers. The player and the downloader must treat them as
   pass-through.
4. **Picture-in-Picture is not in GeckoView 159** (added v160,
   experimental — API doc §8). The player uses Android PiP, as Mariner
   does. Do not write code against the v160 API.
5. **FilePrompt `content://` trap** (API doc §3): the prompt's file
   resolution returns null for SAF URIs without a `_data` column. The
   chooser copies to a temp file and confirms with `file://`. Verify on
   device (scorecard row I).
6. **v160 content-disposition validation is absent on 159** (API doc
   §1a): the app parses and sanitises the raw server header itself.
7. **`WebResponse` has no `filename` / `contentLength` / `contentType`
   fields** (API doc §1a): parsed from headers. The old
   `WebResponseInfo` overload was removed in v86.
8. **PDF viewer deferred.** PDFs download as files in Phase 3; Mariner's
   in-app PDF viewer is a later phase.
9. **Cast session launch is Phase 4.** The Cast button is rendered in
   the player and the media sheet exactly as in Mariner; tapping it does
   not launch a session in Phase 3. The coordinator records at
   integration whether it stays present-but-deferred or hidden until
   Phase 4.
10. **Web-notification permission is denied by default with no UI**
    (§8.7). Mariner has no such prompt; adding one would be new scope.
11. **`webRequest` tab attribution is a builder verification item.**
    Until the background script proves it can identify the tab an
    observation belongs to, unattributable observations are dropped, not
    guessed (§7).
12. **EPUB auto-shelf dropped** (reader is Phase 4). EPUBs download as
    files.
13. **Per-session text zoom does not exist in GeckoView** (Phase 2).
    The probe content script applies it; the runtime-global fallback is
    not per-site and must be reported as a gap if used.
14. **Cookie Banner Handling was removed in GeckoView v154** (API doc
    §6). Nothing to port; Mariner has none either.
15. **No `evaluateJavascript`.** All JS runs through the extension.
    Anything in Mariner that relied on `evaluateJavascript` timing
    (cosmetics, mute-until-gesture) is adapted to the probe and the
    adaptation is stated in §8, not assumed equivalent.

## Post-Phase 3 workstream (NOT Phase 3 scope — record, do not build)

Decided 2026-11-12 (alpha.10 fix round, Robert agreed to all 20
recommendations): **reading list, user scripts and playlists are
Phase 4** — reader + bookshelf for the reading list, the reader
workstream for user scripts, player work for playlists. They are
not built in alpha.10 and are not gaps in Phase 3. **MSE / blob
finder blindness is a documented known Gecko limit, not a gap:**
direct-URL recovery for MSE playback is impossible in principle
(§8 verdict above — no URL exists for an MSE stream); the
webRequest observer recovers manifest / segment URLs only.

The Phase 1–2 shell UI audit + corrections (Robert, 2026-11-11 22:43):
audit what is already built (Phases 1–2 shell) against Mariner, list the
differences, correct them as standalone commits sequenced **after**
Phase 3 lands. Known items already queued there: the address-bar
Top/Bottom setting (`barPosition`, default bottom — Pioneer has no
bar-position setting yet), and any other shell difference the audit
finds. The audit is a **full inventory diff** (every Mariner setting key,
menu row and feature checked against Pioneer from the code), not just an
eyeballed UI pass (lesson, 2026-11-11 22:47). Phase 3 workers do not
start it, do not fold it into Phase 3 commits, and do not let it collide
with the Phase 3 builder on the same branch.

## Researcher brief (separate document — see PHASE3-GECKOVIEW-API.md)

All GeckoView API claims in this design are verified in
`PHASE3-GECKOVIEW-API.md` (same directory): one line per API — exact
signature, since which version, doc/source link, and whether it exists —
including the entries for APIs that do **not** exist in 159
(`evaluateJavascript`, per-session text zoom, PiP, the extension store,
the removed `WebResponseInfo` overload). If implementation contradicts
the API document, the API document is amended first and the worker
reports the contradiction — the code does not silently diverge from it.
