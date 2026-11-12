# Notscape Pioneer — Re-audit 2 (alpha.9 vs Mariner RC1 0.1.0)

**Date:** 2026-11-12. **Scope:** Pioneer 0.1.0-alpha.9 (head `c535c48b`) vs Mariner RC1 0.1.0.
**Method:** read-only, code-verified. Six auditors worked in parallel (settings keys; Settings rows +
menu rows; feature/behaviour inventory; corrections verification; popup system; floating UI +
screen-by-screen), each verifying from the current trees rather than copying the first audit's tables.
Two cross-worker conflicts were resolved by the coordinator with primary evidence (see §2.3, §5.4).
**Sources:** Mariner `~/workspace/notscape-mariner-rebuild/app-stage/app/src/main/java/net/notscape/browser/`;
Pioneer `~/workspace/notscape-pioneer/app/src/main/java/net/nspioneer/browser/`; first audit
`UI-AUDIT-REPORT.md`; `PHASE2-DESIGN.md`, `PHASE3-DESIGN.md`, `PHASE3-BUILD-LOG.md`.
**Caveat:** neither tree is a git checkout, so the head SHA `c535c48b` and per-batch SHAs could not be
verified from local history — all verdicts are from the current tree state. Nothing was edited,
committed, pushed, or messaged.

## 0. Verdict up front

The first audit's headline gaps are **almost all closed**. Batches 1–4 + Phase 3 landed as claimed:
floating bar with Bottom default, satellites, scroll-hide, swipe-on-pill, pull-to-refresh, popup panel,
app-mode exit, full tab grid with thumbnails, fetched favicons, exact history undo, wordmark fonts,
ChevronLeft back icon, Find "0 / 0" fix, FrostDialog uBlock prompt, restored menu icons, Material You
rows, most Settings rows, start-page wallpaper/pins/swatch, Bookmarks section removed, 8-row long-press
sheet, onboarding, desktop-mode restore, Downloads, MediaSheet, and a full PlayerActivity port.

**What the re-audit found that is new:**

1. **One real bug: `webDarkMode` is present but broken.** Settings writes lowercase
   `"auto"/"on"/"off"` (`ui/SettingsScreen.kt:502,511`) but both engine readers match only
   capitalised `"On"/"Off"` with default `"Auto"` (`engine/GeckoEngine.kt:308,576–582`). The
   user's On/Off choice never takes effect; it always falls through to SYSTEM. Verified by the
   coordinator directly, not just the worker. (Even fixed, Gecko only sets `preferredColorScheme` —
   it cannot force-darken pages.)
2. **Tab-close Undo is partial.** Undo exists for closes from the tab grid (`closeTabWithUndo`,
   `ui/BrowserScreen.kt:572`, wired at `:1763, :1907, :1969`) but NOT on the main close path
   (`MainActivity.kt:445` `closeTab`, used by `onCloseTab` at `:204`) — no snackbar, no undo there.
   Verified by the coordinator directly. The corrections worker and the features worker disagreed;
   both were half right.
3. **Robert's popup bug is explained, and it is a design defect, not a missing panel.** The
   notice exists and is wired — but only for scripted `window.open` and the popunder guard. The
   path that fires for a tapped link to a `$document` host (the wrestling-site pattern) is the
   probe extension's `webRequest` cancellation, which has no gesture signal, no notice, and no
   `allowPopups` exemption — and "Always allow" cannot fix it, because the same layer re-cancels
   the load. Full analysis in §3.
4. **No proven load-time Kotlin throw for the hard crash.** Ranked hypotheses in §3.5: the
   closed-session rebind after a popup `window.close()` is the strongest code-level candidate;
   a native libxul/content-process crash is the other live hypothesis. Both need logcat + device
   reproduction.
5. **Remaining in-scope gaps are now countable and small** (§4): the download pill is the only
   floating-UI gap in built scope; the video-player Settings page and the download Settings rows
   shipped without UI (keys read with defaults); Blocked-elements Manage, backup export/import,
   clear-all-data, Erase-everything/Renew, reading list, user scripts, playlists, and four menu
   actions (Save as PDF, Save offline, Screenshot, Scan QR) are missing; DASH SegmentTimeline is
   still unparsed; the finder is still blind to MSE/blob.

## 1. Counts — reconciled (different units, not different findings)

| Inventory | Total | Present | Differs | Missing (in-scope) | Missing (impossible, Gecko) | Missing (out-of-scope: Phase 4 / games / updater) |
|---|---|---|---|---|---|---|
| Setting key patterns | 147 | 65 | 3 | 3 | 2 | 28 Phase 4 + 42 games + 3 updater + 1 `showContinue` |
| Settings-screen rows | 133 | 85 | 8 | 28 | 3 | 9 (6 Phase 4 + 3 updater) |
| Menu rows | 34 | 18 (17 + Customize) | — | 10 | — | 4 Phase 4 + 2 present-elsewhere |
| Features / behaviours | 62 | 31 | 15 | 10 | — | 6 |
| Floating UI elements | 35 | 22 | 2 | 1 (download pill) | — | 5 Phase 4 + 4 games + 1 vault/Phase 4 |

Counting notes: key patterns collapse per-site/per-console/per-game families to one pattern each
(e.g. `sitezoom:$host` = 1); the first audit counted 140 with families collapsed differently, so
totals differ by method, not by finding. Rows count one user-facing row/control (multi-choice
groups count per choice where Mariner renders separate rows). Menu: Pioneer has 21 rows total
(17 Mariner rows + 4 Pioneer-only: Install uBlock Origin, Copy status, Diagnostics, Licences) vs
Mariner's 33 + Customize footer. The first audit's "7 missing" in-scope keys are now 3 by the
keys-worker's count — `barPosition`, `searchEngine`, `themeMode`, `uiFontCustomName` all landed.

## 2. Corrections verification (Batches 1–4 + Row F)

Verdicts are landed + **wired** (implementation + call site), verified in code. Full per-item
references are in the workers' reports; the table below is the summary of record.

### Batch 1 — floating bar (alpha.5, `76f3459a`): ALL LANDED + WIRED
- `barPosition` (Bottom default) + Settings rows — `ui/BrowserScreen.kt:368`
  (`store.setting("barPosition", "bottom") != "top"`); rows `ui/SettingsScreen.kt:454-464`;
  layout honours it (`:1404-1405`, `:1452-1460`).
- Floating frost capsule (12dp inset, 10dp edge) — `ui/BrowserScreen.kt:1038-1041, 1458`.
- Scroll-hide — `barHidden` `:373`, driver `:458-464` (hide past 80px, show on 6px up-scroll).
- Pill-fill progress — `:1104-1111`.
- Satellite circles, all five wired — Back/Forward `:1049-1075`, Stop `:1276-1279`, tab-count
  `:1290-1300` (long-press = new tab), Media `:1322`, Menu `:1329-1332`.
- Swipe-on-pill — `:1080-1100` (±90px, within-class). Pull-to-refresh — `:1363-1405`, indicator
  `:1412-1430`. Mic + site-info + Cancel — mic `:1233-1256`, site-info `:1164` → sheet `:2578-2682`,
  Cancel `:1271`.

### Batch 2 — navigation + tabs (alpha.6, `6d17aaf3` + `cfa90942`): ALL LANDED + WIRED
- Forward/Stop/Home wired; Home long-press opens in current class; `homeUrl()` reads `homePage`.
- Long-press Back/Forward history sheet — `historySheetDir`, sheet `:2067-2103`.
- Tab grid with thumbnails via `GeckoView.capturePixels()` (`:532-541`), search, grid/list toggle
  (`tabViewMode`), drag-reorder, swipe-to-close; recently-closed (cap 20, 30-day expiry,
  `data/PioneerStore.kt:835-880`); close-undo snackbar (grid closes only — see §2.3);
  per-class counts; close-duplicate/all per class; restore of desktop mode + scroll
  (`MainActivity.kt:575-576, 609-610`; no hardcoded `desktopMode = false` in save/restore).
- Private separation + explicit wipe on last private close — `engine/GeckoEngine.kt:416-442`
  (`wipePrivateData()` + `Downloads.wipePrivateDownloads`), startup wipe `:255-259`.
  **Device-confirmed:** Robert's 2026-11-12 paste shows `private: wiped private context`.

### Batch 3 — small diffs (alpha.7, `b18d55ae`): ALL LANDED + WIRED
- Fetched favicons (`ui/Favicon.kt`, `data/PioneerStore.kt:882-954`, `data/IcoDecoder.kt` + tests;
  GeckoView has no favicon delegate — Bug 1770989 still open — so `/favicon.ico` fetch + cache).
- Exact history-undo restore (`data/PioneerStore.kt:496-506`). Wordmark fonts (Cormorant Bold +
  Montserrat, byte-identical from Mariner). ChevronLeft back icon. Find "0 / 0" fix (unknown total
  renders `n / …`). uBlock prompt as FrostDialog (`MainActivity.kt:275-278`). Menu icons restored
  (Copy URL, Open in other browser, Tune). Stale Desktop comment fixed. Material You Settings rows
  present (`ui/SettingsScreen.kt:411-428`).

### Batch 4 — Settings + start page (alpha.8, `47014128` + `cccf7933`): LANDED + WIRED except §2.2
- All missing-now Settings rows verified present with row + engine wiring (web dark mode — see
  §2.1; web text size; UI font/scale/custom font; interface text size; immersive; restore-tabs;
  open-links-in-new-tab; search-engine choice + custom URL; HTTPS-only + exceptions; cookies/GPC/
  referer/autoplay; clear-data ranges + per-type toggles; delete-on-exit; per-site controls
  sub-screen; default browser; welcome-guide replay; reduced motion; force zoom; system captions;
  storage sizes + clear cache; home-page URL; home-button toggle; showFrequent).
- Start-page shortcuts pins + Edit, custom-photo wallpaper, banner swatch (palette-ktx).
- Long-press menu as FrostSheet + View image; **Download and Block this element present**
  (Block is image-only, a narrow honest version of Mariner's element picker).
- Onboarding/welcome flow; app mode + Add to Home screen row.
- **Robert's explicit item — start-page Bookmarks section REMOVED: VERIFIED.** `ui/StartPage.kt:74-77`
  comment records the removal; grep for `Bookmark` in `StartPage.kt` returns only that comment.
  Bookmarks feature/folders/screens/import/export untouched.

### Row F — download unit tests (alpha.9, `a38375c1` + `ccdae4bd`): LANDED
- `DownloadsTest.kt`: 40 `@Test`; `DownloadsStoreTest.kt`: 13 `@Test` (53 download tests; 86 total
  across five test files, 0 failed in CI run 43171105304). CI runs them
  (`.github/workflows/android.yml:51-52`, `./gradlew :app:testDebugUnitTest` before the APK build).
  Untestable as specified: `DownloadWorkers` has nothing pure (private CoroutineWorker extension,
  network/file-bound jobs) — row G stays device-verified.

### 2.1 Correction failure — `webDarkMode` present but broken (NEW BUG)
Settings writes lowercase `"auto"/"on"/"off"` (`ui/SettingsScreen.kt:502,511`) but both engine
readers match only capitalised `"On"/"Off"` with default `"Auto"`
(`engine/GeckoEngine.kt:308,576–582`). The user's On/Off choice never takes effect; it always
falls through to SYSTEM. **Fix:** normalise case at one end (write capitalised values, or match
case-insensitively in the readers). Even fixed, Gecko only sets `preferredColorScheme` — forced
darkening of page content remains impossible as specified.

### 2.2 Documented-impossible omissions (verified, with in-code evidence)
Session-only cookies, DOM storage toggle, per-site third-party cookies, forced web darkening —
no such APIs in the GeckoView 159 AAR (`ui/SettingsScreen.kt:106-110` header comment; the
corrections report's "impossible as specified" claim holds). Web dark mode as built is
prefers-color-scheme only, not forced darkening. HTTPS-only interstitial timing ("appears only on
second attempt") cannot be confirmed or refuted from code — device run needed.

### 2.3 Resolved conflict — tab-close Undo (coordinator-verified)
The corrections worker said close-undo "landed + wired" (`closeTabWithUndo`,
`ui/BrowserScreen.kt:572`, snackbar "Tab closed / Undo"); the features worker said "no undo after
closing a tab" (`MainActivity.kt:445-463`). Both were half right, verified by direct grep:
`closeTabWithUndo` exists and is wired to swipe-to-close (`:1763`) and tab-tile close buttons
(`:1907, :1969`), so **grid closes offer Undo; the main close path (`MainActivity.closeTab`,
used by `onCloseTab` at `:204`) does not**. Fix: route the main close path through the same
Undo snackbar.

### 2.4 Resolved conflict — private wipe (coordinator-verified)
The floating-UI worker reported "no explicit profile wipe on last close". That is wrong:
`wipePrivateData()` is called in `destroyTab` (`engine/GeckoEngine.kt:416-422`, comment "the last
private tab closing wipes the whole…") and at startup (`:255-259`), and Robert's device paste
confirms `private: wiped private context` on device. Verdict: **present + wired + device-confirmed**.

## 3. Popup system — the live bug, traced end to end

**Robert's report (2026-11-12 06:53, site watch-wrestling.eu):** clicking a link sometimes pops
up / opens the link in a new tab; Pioneer blocks it with **no allow/deny notice**; and the app
**closes immediately with no message when he opens the site** (hard crash on page load). All refs
below are from the code as read; no device run was possible from this audit.

### 3.1 Mariner popup flow (reference)
- **Capture — `onCreateWindow`**, `browser/BrowserScreen.kt:3209-3270`. Strict model only —
  `isUserGesture` is received but ignored: `allowed = !strict || store.siteSetting(pageHost).allowPopups`
  (3216-3218). Allowed → real new `TabState(…, isPopupWin = true)` (3225); blocked → a
  **sacrificial WebView** (JS off, 3243-3249) is handed to the transport so the popup's target URL
  can be captured by `shouldOverrideUrlLoading` (3251-3263) into `pendingPopupUrls` (max 5), and
  `showPopupNotice = true` unless `blockPopups` ("Always deny", 3240/3261/3265).
- **Queue state:** `pendingPopupUrls`, `capturedPopupWv`, `popupPageHost`, `clearPendingPopups()`
  (BrowserScreen.kt:711-727). `isPopupWin` in `browser/TabList.kt:121-124`; `onCloseWindow` closes
  only popup tabs (3202-3206).
- **Popunder guard** in `shouldOverrideUrlLoading` (2693-2719): main-frame, no gesture, different
  host, `FilterEngine.isDocumentBlocked(u, hereHost)` → capture as blocked popup + notice, return
  true. Typed addresses never reach it; tapped links carry a gesture, so only the swap trick is caught.
- **`$document`/`$popup` rules:** `browser/WebTools.kt` (Beta.63 #64, ~207, ~1435-1452) — pure
  `||host^$document/$popup` hosts are popunder networks; `FilterEngine.isDocumentBlocked /
  isDocumentHost` enforce them.
- **Per-site settings:** `data/Store.kt:64` — `SiteSetting(…, allowPopups, blockPopups, …)`,
  persisted as `"pop"` / `"bp"`.
- **Notice UI** (4277-4373): "Popup blocked on $host", first URL, **Open once / Open all, Always
  allow** (writes `allowPopups=true`), **Deny, Always deny** (writes `blockPopups=true`).
  Auto-dismiss ~12 s (1789-1794). Net effect: in Mariner a blocked popup is *always* surfaced
  with a remedy, even a gesture popup.

### 3.2 Pioneer popup flow — three disjoint paths, only two feed the notice
| Path | Trigger | Notice? | Code |
|---|---|---|---|
| 1. Scripted `window.open` | `onPopupPrompt` | **Yes** — queue max 5, notice unless `blockPopups` | `engine/PromptDelegate.kt:174-203` (single pending slot, previous unanswered result completed with `null` first); `GeckoEngine.kt:155-156` → `ui/BrowserScreen.kt:984` → `handlePopupPrompt` (`:885-903`); allow → `answerPopupPrompt(true)` → `PromptDelegate.answerPopup` (`:219-233`), arms `expectPopupSession` (`:179-182`); deny → queues target (max 5, skips `about:blank`), `popupPageHost = host`, `showPopupNotice = true` unless `blockPopups` (`:900-901`). `onNewSession` (`GeckoEngine.kt:1282-1303`) creates the tab, consuming the flag into `isPopupWin` (`:1295-1297`). `onCloseRequest` (`:1432-1439`) destroys only `isPopupWin` tabs. |
| 2. Popunder guard in `onLoadRequest` | Gesture-less main-frame hop to a `$document` host | **Yes, if the merge runs** — via a `SideEffect` recomposition poll, not an event callback | `GeckoEngine.kt:1234-1272` (`TARGET_WINDOW_CURRENT && !hasUserGesture && !isDirectNavigation`, different host, `isDocumentBlocked` → `popunderCaptures` max 5, `:191-192`, status line "popunder blocked"); shell merges into the notice queue in `SideEffect` (`ui/BrowserScreen.kt:1010-1023`). |
| 3. `webRequest` `$document` cancellation | **Any** main-frame load to a `$document`/`$popup` host — including tapped links | **No — never touches the notice** | `app/src/main/assets/extensions/pioneer-probe/background.js:380-470`, esp. 422-433: `onBeforeRequest` cancels every main-frame load to a `$document`/`$popup` host (`documentCategory(url, originHost) != null → {cancel: true}`). No user-gesture signal, no direct-navigation exemption, no popup-queue write, no notice. Only report is a `"blocked"` native message → `engine/BlockingObserver.kt:151-…` → `FilterEngine.noteBlockedFor(tabId, category)` — a **counter only** (`FilterEngine.kt:357-383`). If attribution fails (zero/ambiguous tabs on the host), the report is dropped with only a status line (`BlockingObserver.kt:188-191`). |

**Notice UI — built and wired (for Paths 1–2).** `ui/BrowserScreen.kt:1524-1630`: same buttons
as Mariner (Open once / Open all, Always allow, Deny, Always deny), max 5, 12 s auto-dismiss
that also clears the queue (`:907-913`). It sits as a direct child of the full-size `Box`, after
the GeckoView — nothing in code hides or overlays it. It survives navigation/tab switch (no reset
on tab change; `remember` state, no `key(tab)` — `MainActivity.kt:196`), but is lost if the user
leaves the browser screen and by Back (`:850`) or the 12 s timer.

**Per-site `allowPopups`/`blockPopups` — verified WIRED, not dead.** `data/PioneerStore.kt:61-62`
(fields), `:540`/`555` (`siteSetting`/`putSiteSetting`); read in
`PromptDelegate.popupAllowedNow()` (`:209-213`), `handlePopupPrompt` (`:888/900`), site-info
three-state row (`ui/BrowserScreen.kt:2611-2643`, `ui/SettingsScreen.kt:1576-1598`); global
`popupStrict` toggle (`SettingsScreen.kt:771-784`, `BrowserScreen.kt:2660-2673`). Two
dead/duplicated spots: `PromptDelegate.popupsSilenced()` (`:237-241`) is **never called** —
silencing is duplicated inline at `BrowserScreen.kt:900`; and the strict decision is computed in
three places with three different hosts (delegate uses engine `currentTab`, shell uses active-tab
`pageHost` at `BrowserScreen.kt:326/650`), which can disagree when current tab ≠ active tab.

### 3.3 The missing-notice finding — why Robert sees nothing
For a tapped link or gesture popup whose target is a `$document` host (exactly the
wrestling-site pattern: click → new tab / popup → blocked, no notice):

1. Gesture `window.open` / `target=_blank` never reaches `onPopupPrompt` (Gecko allows gesture
   popups) — it goes straight to `onNewSession`, which creates a real tab. Gecko then loads the
   URL into that tab and **background.js cancels the main-frame load**. The tab stays
   `about:blank` → `tab.url == ""` → `isHome` (`BrowserScreen.kt:327`) → start page overlays.
   User sees: tap → blank new tab, nothing else.
2. Same-tab tapped link to a `$document` host: `onLoadRequest` allows it (`hasUserGesture` skips
   the popunder guard), then background.js cancels it — the navigation silently dies, the page
   just stays.
3. The cancellation is not even visible in diagnostics: no status-panel line names the cancelled
   URL (only the counter increments), so "Copy status" shows nothing about it.

**Compounding defect — the notice's remedy is defeated by Path 3.** "Open once" / "Always allow"
open the queued URL via `openPendingPopups()` → `onOpenUrlInNewTab` → `MainActivity.createTab(url)`
→ `tab.load(url)` → `loadUri` → background.js cancels it again, because the `rules-update`
payload (`BlockingObserver.kt:rulesUpdateJson`) carries `disabledHosts` but **no `allowPopups`
exemption**, and `documentCategory` never consults `allowPopups`. So even after "Always allow", a
tapped link to a `$document` host stays blocked. The per-site popup allow and the `$document`
layer contradict each other; the `$document` layer always wins.

**Suppressors to check on device:** a prior "Always deny" on the host silences the notice by
design (`blockPopups`); the 12 s auto-dismiss clears the queue even if unseen; Path 2's notice
depends on the `SideEffect` merge re-running after the capture.

### 3.4 Exact user-visible behaviour per popup kind
| Kind | Pioneer behaviour (code-verified) |
|---|---|
| Gesture popup (`window.open` on tap, or `target=_blank` tap) to a normal host | New tab opens immediately via `onNewSession` — no prompt, no notice. Correct. |
| Gesture popup / tapped link to a `$document`/`$popup` host | New tab opens, then background.js cancels the load: **blank tab (start page), no notice, no allow/deny, no log line**. Same-tab tapped link: navigation silently dies, page stays. **This is Robert's report.** "Always allow" does not fix it (§3.3). |
| Scripted popup (`window.open`, no gesture) to a normal host | Blocked via `onPopupPrompt` → notice appears with Open once / Open all / Always allow / Deny / Always deny; queue max 5; auto-dismiss 12 s. Works as designed. |
| Scripted popup to a `$document` host | Notice appears (Path 1), but "Open once" opens a tab whose load background.js cancels again → blank tab. Notice promises a remedy it cannot deliver. |
| Popunder (gesture-less main-frame hop to a `$document` host) | Blocked in `onLoadRequest`, queued in `popunderCaptures`, notice via the `SideEffect` merge — *if* the merge recomposition runs. No notice is produced by the webRequest layer for the same hop if it arrives as a fresh main-frame load. |

### 3.5 Ranked crash candidates (load vs click tagged)
No smoking-gun Kotlin throw was found on the page-load path — the delegate bodies are heavily
`runCatching`/null-safe. Ranked by code evidence:

1. **Popup `window.close()` → closed session rebound to the GeckoView (CLICK / popup-close, can
   fire seconds after load).** `ContentDelegate.onCloseRequest` (`GeckoEngine.kt:1432-1439`) calls
   `engine.destroyTab(tab)`, which removes the tab from the **engine's** list and closes the
   session (`GeckoEngine.kt:404-418`) — but `MainActivity.tabs` still holds the tab and
   `activeTabIndex` may still point at it. `BrowserScreen`'s `AndroidView` update then calls
   `view.setSession(tab.session)` on every recomposition (`BrowserScreen.kt:1348-1351`) with a
   **closed** session. Whether `GeckoView.setSession` throws on a closed session needs
   verification against the pinned GeckoView 159 AAR / logcat — not establishable from Kotlin
   source alone — but this is the only load/popup-adjacent path found where a dead object is
   handed back to the view layer. Wrestling-site popups routinely `window.close()` themselves.
2. **Popup storm at load → tab-creation + persist churn (LOAD, weak).** Each `onNewSession`
   creates a tab, switches the active tab, and calls `persistTabSession()`
   (`MainActivity.kt:122-127`, `:565-583`). No throw found in that chain (`saveTabSession` reads
   only `url/title/sessionStateJson`), but it is the highest-frequency code on an ad-heavy load
   and the only place a load storm touches disk on the main thread. Needs logcat to implicate
   or clear.
3. **`expectPopupSession` single-boolean arming (CLICK, state bug, not a crash).**
   `GeckoEngine.kt:182` — one flag, not per-tab/per-prompt. If an allowed prompt never produces
   `onNewSession`, the flag stays armed and the next unrelated `target=_blank` tab is mis-marked
   `isPopupWin=true`, making it closeable by `window.close()` and eligible for candidate 1's path.
4. **Shared single-slot `PromptDelegate` across all tabs (LOAD/CLICK, safe but fragile).** One
   `PioneerPromptDelegate` instance serves every session (`GeckoEngine.kt:147-164`);
   `pendingPopupResult` is a single slot (170-172) and `pageHost()` reads engine `currentTab`.
   Rapid multi-tab popups cancel each other's results (guarded by `runCatching`), and a popup
   from a background tab can be decided against the wrong host. No crash path found.
5. **Session crash swallowed, not surfaced (LOAD).** `ContentDelegate.onCrash`
   (`GeckoEngine.kt:1442-1444`) invokes `tab.onCrash` — but `MainActivity.wireTab`
   (`MainActivity.kt:411-418`) **never sets `tab.onCrash`** (only `onNavigate`/`onLongPressTarget`).
   A Gecko renderer crash therefore leaves a dead tab with no UI. This is *not* an app-process
   death, so it does not by itself explain "app closes with no message" — but it means a renderer
   crash on this site would be invisible in-app, and only logcat distinguishes it.
6. **Ruled out on load (checked, no throw path):** `FilterEngine.isDocumentBlocked` /
   `scopedRuleMatch` / `matches` / `hostChain` (pure string ops, `FilterEngine.kt:1498-1521,
   1598-1626, 1646-1676, 1769-1800` — no regex, no exceptions); `blockCategory` is never called
   from Kotlin at runtime (only via `isBlocked`, called from tests); `cosmeticReply`
   (`BlockingObserver.kt:336-360`, ≤200 k chars, `JSONObject` only); `noteContentBlocked` /
   `noteBlockedFor` (atomics + `ConcurrentHashMap`, `FilterEngine.kt:316-383`); `MediaLog.inspect /
   noteDom` (`media/MediaLog.kt:95-144`, simple regex `containsMatchIn`, deduped); `probe.js` /
   `blocking.js` (JS errors cannot kill the app); `onLocationChange → applySiteJavaScript →
   reload()` (guarded by `jsAppliedValue`, `GeckoEngine.kt:1150-1165`); `store.addHistory`
   (synchronized, `PioneerStore.kt:468-479`); engine init / probe install / Cast probe (all
   `try/catch`/`runCatching`).

**Bottom line on the crash:** from Kotlin alone there is no proven load-time throw. The two live
hypotheses are (1) the closed-session rebind after a popup `window.close()`, and (2) a **native
libxul/content-process crash** (the site is ad/video-heavy; Gate 1 already logged "first-load-then-
retry failure twice"). Both require the device: `logcat` (`AndroidRuntime` FATAL + `PioneerSpike`
lines — note the `$document` cancels leave *no* status line, so absence of a popup line in
Copy-status proves nothing) and whether the crash reproduces with uBlock off / blocking off /
`popupStrict` off, and in Mariner on the same URL.

## 4. Still missing, grouped by severity

### S1 — User-visible, in built scope (fix next)
| # | Gap | Evidence | User-visible effect |
|---|---|---|---|
| 1 | **`webDarkMode` broken** — case mismatch (§2.1) | Settings writes lowercase; engine matches capitalised only (`ui/SettingsScreen.kt:502,511` vs `engine/GeckoEngine.kt:308,576–582`) | On/Off choice never takes effect |
| 2 | **Tab-close Undo missing on the main close path** (§2.3) | `MainActivity.kt:445` `closeTab` has no snackbar; grid closes do (`ui/BrowserScreen.kt:572`) | Closing the current tab cannot be one-tap undone |
| 3 | **Download pill missing** — only floating-UI gap in built scope | Mariner `browser/BrowserScreen.kt:3526-3567` (240dp frost pill, live progress); Pioneer `ui/BrowserScreen.kt:1030` comment says the pill "is NOT here"; `activeDownloadCount` is only a concurrency gate (`media/Downloads.kt:119,487`) | No in-browser download progress indicator |
| 4 | **No video-player Settings page** (7 rows) | Skip interval + subtitle font/size/colour/background/edge + import font; keys read with defaults (`media/PlayerActivity.kt:716, 2485-2527`); no Settings UI and no in-player editor found | Player options fixed at defaults; custom subtitle font cannot be named (`subFontCustomName` key missing) |
| 5 | **Download Settings rows missing as UI** (5 rows) | Location, segmented, ask-before, Wi-Fi-only, EPUB-to-bookshelf; keys read with defaults (`media/Downloads.kt:332,448,611`; `ui/BrowserScreen.kt:774,781`) | User cannot change download location/behaviour |
| 6 | **Blocked-elements Manage missing** | Mariner `ui/SettingsScreen.kt:1361-1376` + dialog 2144-2253 (per-rule Delete, Export, Import, Clear all); Pioneer shows only counts (`ui/BlockingSettings.kt:277-288`) | Rules cannot be managed/deleted/exported |
| 7 | **Backup export/import + clear-all-data missing** | Mariner `data/Store.kt:1351-1376` (`exportBackup`, `exportBackupEncrypted`, `importBackup`), `:1212` `clearAllData`; Pioneer: zero hits for `exportBackup\|importBackup\|clearAllData` | No way to move/backup data or fully reset |
| 8 | **Erase everything + Renew missing** | Mariner `ui/SettingsScreen.kt:1765-1777` (Erase everything), `:2089-2130` (Renew); Pioneer has narrower "Erase private data" (history + tab session only, `ui/SettingsScreen.kt:1336-1350`) | No full-device erase; no one-tap Renew |
| 9 | **Reading list entirely absent** | Mariner `data/Store.kt:588,610`, `ui/SettingsScreen.kt:359-366`; Pioneer: no `ReadingListScreen`, no `PioneerScreen` entry (`ui/Screen.kt:6-8`) | No read-later list (menu row + Settings page + screen all absent) |
| 10 | **User scripts missing — no phase assigned** | Mariner `browser/UserScripts.kt:27` (`SETTING_KEY = "userScripts"`); zero hits in Pioneer | No custom user-script feature at all; needs a phase decision |
| 11 | **Playlists gone** | Mariner `data/Store.kt:21-22`, `ui/LibraryScreens.kt:1077`; Pioneer has no playlist model/screen; `ui/MediaSheet.kt:75` `onAddToPlaylist` never passed (dead parameter) | "Add to playlist" absent |
| 12 | **Menu actions missing** (Save as PDF, Save offline, Screenshot, Scan QR) | Mariner `browser/BrowserChrome.kt:765-766, 781, 787, ~815`; Pioneer: no implementation, listed as omitted in `ui/BrowserChrome.kt:92` | Four menu actions absent (Save offline also means no OfflinePage model) |
| 13 | **DASH SegmentTimeline not parsed** | Mariner `media/DashManifest.kt:371`; Pioneer `media/Downloads.kt:1800-1889` handles SegmentTemplate + SegmentList only; `:1942-1944` expands `$Number$` until first 404 | DASH manifests carrying counts only in SegmentTimeline fail / fetch wrong segments |
| 14 | **Finder blind to MSE/blob** | Mariner combines network inspection + DOM scan (`browser/WebTools.kt:1636-1749`); Pioneer has both halves (`engine/BlockingObserver.kt:166-178`, `engine/Engine.kt:101-105`) but the probe sees direct URLs only; `engine/MediaSessionHandler.kt:8-10` notes `onMetadata` fires only for pages that expose it | Media sheet empty/short on YouTube-class sites |
| 15 | **"Erase private data" narrower** | Mariner closes all private tabs → wipe (`browser/BrowserScreen.kt:3466-3472`, `MainActivity.kt:623-628`); Pioneer dialog only clears history + empty tab session (`ui/SettingsScreen.kt:1336-1350`) | Open private tabs/data survive the tap (dialog text is honest about this) |
| 16 | **Per-site "disable blocking" does not disable uBlock** (known Gecko-worse, unchanged) | `engine/GeckoEngine.kt:501-514` disables ETP + lists + cosmetics only; status line `:514` says so | Ads still blocked on "unblocked" sites |
| 17 | **Cosmetics at `document_idle`** (known Gecko-worse, unchanged) | Mariner injects at document-start (`browser/WebTools.kt:107`); Pioneer `engine/FilterEngine.kt:41-42` | Ads flash before hiding |
| 18 | **No "Search settings" field** | Mariner `ui/SettingsScreen.kt:776`; Pioneer has none (single scrolling page, no hub) | Settings must be scrolled, not searched |
| 19 | **No diagnostic trace; no backup of start-page Continue** | Mariner `ui/SettingsScreen.kt:1778-1805` (trace start/stop/export); Pioneer none. Continue watching/reading absent (playback positions live only in `media/PlayerActivity.kt:403-450`, not the store) | No trace export; no Continue rows on start page |

### S2 — Phase 3 scope that shipped without UI (keys read, no controls)
Video-player settings page (S1 #4), download Settings rows (S1 #5), `subFontCustomName`
(Mariner `ui/SettingsScreen.kt:3281`; siblings present, `media/PlayerActivity.kt:2488`).

### S3 — Dead / unwired in Pioneer (exists but does nothing)
1. **`SiteSetting.allowThirdPartyCookies`** — `data/PioneerStore.kt:63` defines it, `:1273`
   serialises it (`"c3"`), but a whole-tree grep finds **zero readers**. Per-site Settings rows
   (`ui/SettingsScreen.kt:1549-1679`) have no cookie toggle. Preserved (probably for settings
   compat) and silently does nothing.
2. **`ui/BrowserChrome.kt:90-93` header comment is stale.** Lists Downloads, Offline library, Add
   to Home screen, and Block an element as "omitted by design" — all four now exist. The rest of
   its list (Reader/Vault/Games/TTS/Playlists/Reading list/Bookshelf/Save as PDF/Save offline/
   Screenshot/Scan QR/Site blocking/Global ads) is still correctly absent.
3. **`ui/WelcomeScreen.kt:70-71` comment is misleading.** Says "no notifications ask (Pioneer has
   no download-notifications helper)" — the helper exists and is wired
   (`media/DownloadNotifAsk.kt:34,77`; `ui/LibraryScreens.kt:1720`; `media/Downloads.kt:1393-1429`).
   Only the *welcome-screen* ask is omitted.
4. **`ui/MediaSheet.kt:75` `onAddToPlaylist`** — declared, never passed by the only caller
   (`ui/BrowserScreen.kt:2553` passes only `onMerge`). Dead parameter, downstream of missing playlists.
5. **`PromptDelegate.popupsSilenced()` (`:237-241`) never called** — silencing duplicated inline at
   `ui/BrowserScreen.kt:900`. **Strict popup decision computed in three places with three different
   hosts** (delegate uses engine `currentTab`, shell uses active-tab `pageHost`) — can disagree when
   current tab ≠ active tab.
6. **`expectPopupSession` single-boolean arming** (`engine/GeckoEngine.kt:182`) — one flag, not
   per-tab/per-prompt; a prompt that never produces `onNewSession` leaves the next unrelated
   `target=_blank` tab mis-marked `isPopupWin=true`.
7. **Benign interface defaults, all overridden:** `engine/Extensions.kt:383`
   `reducedMotionEnabled() = false`, `:389` `autoplayMutedFor() = false`;
   `engine/BlockingObserver.kt:433-437` `blockAdsEnabled()/blockTrackersEnabled() = true`.
   `engine/GeckoEngine.kt:794-799,844,850` overrides all from the store — fallback defaults, not
   live behaviour.

### S4 — Documented-impossible as specified (omitted with evidence — NOT oversights)
Session-only cookies, DOM storage toggle, per-site third-party cookies, forced web darkening
(`ui/SettingsScreen.kt:106-110` header: no such GeckoView 159 APIs). Web dark mode as built is
prefers-color-scheme only.

### S5 — Out of scope (assigned phase — NOT missing)
- **Phase 4:** reader view + EPUB shelf + Continue reading (13 reader keys, 2 TTS keys), vault /
  form-fill (6 keys), Cast session launch (discovery present, session absent — no
  `CastHelper.loadUrl`/`sessionActive`), reading list / bookshelf (7 keys), `showReader`.
- **Games (last):** 42 keys — no `emu/` package; `ui/StartPage.kt:77-78` shortcuts are sites +
  Offline-library only.
- **Updater (late):** 3 keys (`autoUpdateCheck`, `updateChannel`, `lastUpdateCheck`); no update
  check, update pill, or Updates screen.
- **Genuinely stronger than Mariner in places:** full Gecko session-state tab restore (incl.
  history/scroll/form data); engine-native ETP Strict; uBlock Origin as an additional blocker;
  downloads engine is a faithful port (pause/resume/cancel/retry, Wi-Fi-only, private gating).

## 5. Inventory tables (condensed — full per-item evidence in the workers' reports)

### 5.1 Setting keys (147 patterns) — present list (65, condensed)
Present, same meaning & default: `themeMode`, `downloadLocation`, `wifiOnlyDownloads`, `blockAds`,
`immersive`, `httpsOnly`, `httpsExceptions`, `customFilterUrl`, `customAbpRules`, `blockTrackers`,
`autoplay`, `webTextZoom`, `tabViewMode`, `searchEngine`, `refererPolicy`, `gpc`, `deleteOnExit`,
`cookies`, `confirmDownloads`, `wallpaper`, `uiFontScale`, `uiFont`, `uiFontCustomName`,
`subSize`/`subFont`/`subEdge`/`subColor`/`subBg`, `showHomeButton`, `showFrequent`, `showFavorites`,
`showBanner`, `segmentedDownloads`, `restoreTabs`, `reducedMotion`, `popupStrict`, `playerSkipSec`,
`openLinksInNewTab`, `homePage`, `forceZoom`, `filterListsOff`, `customSearchUrl`,
`blockThirdPartyCookies`, `barPosition`, `blockedHistory`, `frequentHidden`, `menuHiddenRows` /
`menuRowOrder`, `userBlockNet` / `userBlockCosmetic`, `downloadNotifAsked`, `startShortcuts`,
`closedTabs`, `onboarded`, `playerVolume` / `playerSpeed` / `playerScale` / `playerBrightness`,
`sitezoom:$host`, `sitedesktop:$host`, `siteperm:$host:$resource`, `siteautoplay:$host`,
`videoPopupOff:$host`, `dljob:$id`, `dlerror:$id`, `dldowngrade:$id`, `dltombstone:$id`.
SiteSetting fields js/img/pop/nb/bp/c3 all present with identical names/export keys
(`data/PioneerStore.kt:60-63, 1268-1273`), stored in `site-settings.json`.
Pioneer-only keys (3): `trackingProtection` (`MainActivity.kt:136`, strict/basic/off),
`uBlockPrompted` (`MainActivity.kt:115,150,169`), `startPageTiles` (`ui/StartPage.kt:254`).

**Differs (3):** `webDarkMode` (case bug, §2.1); `sitezoom:$host` (Mariner int %, `0` = unset,
`data/Store.kt:1113`; Pioneer float factor, `1.0` = unset, `data/PioneerStore.kt:592-597` — a
carried-over `"150"` reads as 150.0×); `sitedesktop:$host` (Mariner tri-state null/true/false,
`data/Store.kt:1121`; Pioneer boolean, false removes the key, `data/PioneerStore.kt:605-609` —
a Mariner explicit `"false"` (force mobile) is lost). **Migration hazard:** decide whether a
shim is needed for any Mariner settings import, since stored values are unit-incompatible.

**Missing, in-scope (3):** `userScripts` (no phase assigned), `subFontCustomName` (Phase 3 player
scope), `sitereload:$host` (internal mechanism, not user-visible).
**Missing, impossible (2):** `sessionCookies`, `domStorage`.
**Missing, out-of-scope:** Phase 4 (28), games (42), updater (3), `showContinue` (1).

### 5.2 Settings rows (133) — 85 present, 8 differs, 40 missing
Present includes: all theme rows incl. Material You + variants, barPosition Bottom/Top, web text
size, UI font/scale/custom font, interface text size, immersive, restore-tabs, open-links-in-new-tab,
all search rows, blocking section (lists, custom list/rules, counts), HTTPS-only, autoplay, GPC,
referer, cookies, full per-site controls (JS, images, popups, ad blocking, plain HTTP, text zoom,
desktop, autoplay, camera/mic/location, clear data), clear-data ranges + per-type toggles,
delete-on-exit, storage sizes + clear cache, home page + home button, default browser, welcome-guide
replay, reduced motion, force zoom, system captions, About rows.
**Differs (8):** web dark mode (prefers-color-scheme only); Blocked-this-session (counts inline, no
dedicated Blocked page); per-site ad blocking (doesn't disable uBlock); per-site text zoom (±25%
steps vs integer %); per-site desktop (2-state vs 3-state); wallpaper replace (no crop/framing
dialog); About "Licences" instead of "Third-party notices"; start-page banner moved to Appearance.
**Missing, in-scope (28):** video-player settings ×7, download rows ×5, backup export/import/
encrypted ×3, storage areas ×3 (Books, Read-aloud voices, Library database), Blocked-elements
Manage ×1, Erase everything ×1, diagnostic trace ×1, Renew ×1, Reading list ×1, User scripts ×1,
start-page rows ×4 (Continue, Favorites section toggle, Pinned sites in Settings, Use start page),
Settings search field ×1 (counted in the 28 by the rows worker; see its report for the arithmetic).
**Missing, impossible (3):** session-only cookies, DOM storage, per-site third-party cookies.
**Missing, out-of-scope (9):** form-fill ×4, reader ×1, vault-export ×1 (Phase 4); updates ×3
(updater late).

### 5.3 Menu rows (34) — 18 present, 16 missing
Present (17 + Customize): New tab, New private tab, Reload, Share page, Copy page URL, Open in
other browser, Bookmark this page, Add to favorites, Find in page, Add to home screen, Desktop
site (label never shows "Mobile site"), Bookmarks, Favorites, History, Offline library, Downloads,
Settings, Customize menu. Pioneer-only: Install uBlock Origin, Copy status, Diagnostics, Licences
(+ per-site text-zoom card at the menu top, not a row).
**Missing, in-scope (10):** Read later, Media on this page (reachable via the bar's Media circle),
Block an element (exists via long-press only), Save as PDF, Save page offline, Scan QR code,
Screenshot, Reading list, Playlists, Bookshelf.
**Missing, out-of-scope (4):** Read this article, Games, Fill password from vault, Password vault
(Phase 4). **Present elsewhere, not in menu (2):** Site blocking, Ad blocking for all sites
(hidden by default in Mariner too; controls live in Pioneer's per-site panel / Blocking section).
Also missing: "Blocked on this page: N" header, update-available footer (updater late).

### 5.4 Features (62 rows) — 31 present, 15 differs, 10 missing, 6 out-of-scope
Present includes: tabs (new/duplicate/close/reorder/recently-closed/restore/private wipe),
navigation, home, reload, stop, find, per-site zoom, desktop site, history + undo, clear data,
delete-on-exit, bookmarks/favourites/HTML import-export, downloads (plain/HLS/segmented/merge/
pause-resume-cancel/Wi-Fi-only/private gating), media sheet, in-page video pop-up, fullscreen,
PiP, player resume positions, uBlock install, Mariner filter lists, per-site JS/block-images,
permissions, autoplay settings surface, file chooser (incl. SAF `content://` → temp file),
ETP + per-site exception, cookies, search engines, HTTPS-only, onboarding, app mode.
**Differs (15):** erase-private-data (narrower), start page (no Continue; dedicated settings
screen folded into Settings), DASH (SegmentTimeline), media detection (MSE/blob blind), media
sheet (Cast = picker only; no add-to-playlist), per-site disable-blocking (not uBlock), popunder
guard (stricter), cosmetics (`document_idle`), autoplay (probe-muting, not native gesture gate),
HTTPS-only (first-attempt interstitial miss, not re-derived), welcome (no notifications ask),
site-blocking/global-ads menu rows (moved to panels), desktop site (no explicit "unset"), per-site
zoom (0.25 steps).
**Missing (10):** tab-close Undo on main path, clear-all-data, backup export/import, playlists,
Save as PDF, Save offline, Screenshot, Scan QR, per-site third-party-cookie exception
(documented impossible), session-only cookies (documented impossible) — the last two counted here
as missing features though impossible as specified; DOM-storage toggle and forced web darkening
are the same class.
**Out-of-scope (6):** reader, TTS, vault fill, Cast session, games, updater.
**Not verified in this pass:** PlayerActivity track/quality/subtitle internals (needs a focused
diff — both files ~2,400 lines, substantially rewritten); mixed-content behaviour (Gecko default,
no app code); whether Pioneer's "block" autoplay mode truly blocks vs mutes; HTTPS-only
first-attempt miss (reported, not derived from code).

### 5.5 Floating UI (35) — 22 present, 2 differs, 11 missing
Present: floating address bar (Bottom default, scroll-hide), satellite circles, find typing +
match-count pills (fixed), suggestions card (near-identical), popup-blocked panel, pull-to-refresh
circle, app-mode exit button, tab-grid floating action bar (no update pill), undo snackbar,
Frost sheets/dialogs, Customize sheet, media card/sheet, all player pills (top capsule, transport
circles, level pill, options capsule, scale/quality/audio/subtitle rows, portrait scrubber/
brightness/volume pills, queue dialog as stock AlertDialog like Mariner).
**Differs (2):** tab-grid bar lacks the update pill (updater); Cast-device chooser is the stock
`MediaRouteButton`, no Mariner-style chooser dialog and no Cast session (Phase 4).
**Missing, in built scope (1):** download pill.
**Missing, Phase 4 (5):** vault field chip, reader pills/sheets (top, match bar, read-aloud strip,
transport, Chapters/Search/Typesetting/Voice). **Missing, games (4):** game chrome, on-screen
controls, card star, layout-editor pills.
Confirmed again by grep: no `FloatingMedia`/`MediaCard` composable and no `FloatingActionButton`
in Mariner — nothing to match there. Mic icon present but falls back to a "Voice search is not
available on this device" toast when no recogniser exists.

## 6. Shared-code diffs (package-normalised: `notscape`→`nspioneer`, `Notscape`→`Pioneer`)

| File | Verdict | Actual differences |
|---|---|---|
| `ui/FrostKit.kt` (180 vs 160 lines) | **Functionally identical** | Diff is comments only: Mariner's longer history notes (0.0.59/0.61/0.63 lessons) trimmed in Pioneer. Recipe identical: gradient `surface @0.97→@0.92`, 0.5dp hairline `@0.45`, `frostBar` shadow→clip→gradient→border, Pill 50 / Card 20dp / Sheet top 28dp / Dialog 28dp, solid `surfaceContainerLow` sheets. (First audit's "byte-identical" was overstated — package + comments differ; code does not.) |
| `ui/AppTheme.kt` (342 vs 312 lines) | **Near-identical + Batch 4 additions** | Same iOS-26 tokens, shape ladder, monotone ink accent, Material You modes, Cormorant Garamond Bold + Montserrat wordmark. Additions: `uiFontFile()` / `resolveUiFontFamily()` applying `uiFont`/`uiFontScale` to every Material text style. Cosmetic leftover: Pioneer still names the schemes `notscapeLightColors()` / `notscapeDarkColors()` (Mariner's name, unrenamed). Back icon is `ChevronLeft` both sides. |
| `data/BookmarkHtml.kt` vs Mariner `browser/BookmarkHtml.kt` | **Near-identical** | Same Netscape-format parse/export, deterministic folder-sorted output. Deliberate changes only: `export` takes `List<Bookmark>` instead of Mariner's `ExportItem` wrapper; `parse` keeps `pendingHref`/`pendingTitle` in locals instead of object-level vars (behaviour identical for a single parse). |
| `ui/BrowserChrome.kt` vs Mariner `browser/BrowserChrome.kt` (861 vs 1057 lines) | **Near-identical for find/suggestions; menu rewritten-down** | Find pills + suggestions card near-identical (only the "0/0" fix and `MenuRow` extraction). Menu is the Phase-2/4 set (21 rows, icons-core-only, nullable icon with blank glyph slot). Stale "Desktop omitted" comment is gone; per-site zoom card is Pioneer-only (keep). |
| `ui/StartPage.kt` vs Mariner `browser/StartPage.kt` (611 vs 997 lines) | **Substantially rewritten, feature-close** | 1054 differing lines: Mariner's Continue/update/game-callback machinery gone; Pioneer rebuilt the banner/tiles/pins/wallpaper/swatch around `PioneerStore`. Remaining gaps are Continue rows (Phase 3/4) and the update row (updater). |
| `ui/LibraryScreens.kt` vs Mariner (1880 vs 2639 lines) | **Substantially rewritten/ported, feature-present** | 1977 differing lines (store swap `AppStore`→`PioneerStore`, no Room, no playlists/offline-downloads internals of Mariner's). Folders, multi-select, undo, fetched favicons all present. |
| `media/PlayerActivity.kt` vs Mariner (2530 vs 2351 lines) | **Near-identical port** | 633 differing lines, mostly import order + Gecko adaptations: no cookies on playback, platform subtitle fonts, `playerCapsule` at the same 11 call sites, same queue-dialog exception, same `launch`/`launchQueue` signatures. |
| `ui/WelcomeScreen.kt` vs Mariner (476 vs 551 lines) | **Near-identical port** | 209 differing lines (package/store/engine adaptations); onboarding + default-browser flow present. |

## 7. Code-only vs device-check items

**Cannot be judged from code alone (needs device side-by-side):**
- Exact rendering equality of the carried files (colours, radii, elevation, type) — code is
  near-identical but pixels need both apps open on the same phone.
- Scroll-hide / swipe-on-pill / pull-to-refresh **feel** (thresholds, animation curves) — Pioneer
  uses Mariner's thresholds verbatim in code (80px/6px) but the touch-tracking implementation
  differs from Mariner's `PullToRefreshBox`.
- Thumbnail rendering quality (capturePixels crops/scaling) and fetched-favicon rendering (only
  `/favicon.ico` is tried; sites with nonstandard icon paths show the generic Public icon where
  Mariner shows the real one).
- Material You palette on device — rows exist now, but whether System theme yields the dynamic
  palette is still open (Robert confirmed Material You on device 2026-11-11; the code says only
  `materialyou*` modes yield it — worth asking which mode he used).
- Whether the download pill's absence is felt in practice, and whether the popup notice actually
  appears for Robert's watch-wrestling.eu tap path (§3.4).
- Player: background playback is absent by Gecko limitation, not by omission — device check can
  only confirm, not fix.
- HTTPS-only interstitial timing ("appears only on second attempt") — mechanism present, timing
  needs a device run.
- Autoplay "block" vs "muted" distinction (probe-muting enforcement) — not independently
  verifiable from code.
- Mixed-content behaviour (Gecko default, no app code) and PlayerActivity track/quality/subtitle
  internals (needs a focused diff).
- The hard crash on watch-wrestling.eu — needs logcat (`AndroidRuntime` FATAL + `PioneerSpike`
  lines; note the `$document` cancels leave *no* status line, so absence of a popup line in
  Copy-status proves nothing) and A/B reproduction (uBlock off / blocking off / `popupStrict`
  off; same URL in Mariner).

## 8. Method notes & provenance

- Six auditors ran in parallel, each read-only, each verifying from the current trees; the first
  audit's tables were used as pointers only, never copied unchecked. Two cross-worker conflicts
  (tab-close Undo; private wipe) were resolved by the coordinator with direct greps (§2.3, §2.4).
- Counting units differ by inventory (key patterns vs rows vs features vs floating elements) —
  totals differ by method, not by finding; §1 reconciles them.
- Commit SHAs could not be verified from the trees (no `.git` in either staging copy); head
  `c535c48b` is taken from the task brief. CI-green status of alpha.9 (run 43171105304) was
  established by the parent agent, not re-verified here.
- Workers: settings-keys inventory; Settings-rows + menu-rows inventory; feature/behaviour
  inventory; corrections verification (Batches 1–4 + Row F); popup-system deep-dive (incl. crash
  candidates); floating-UI + screen-by-screen re-check.
