# Notscape Pioneer — Phase 2 Design (shell parity)

Gate 1 GO given 2026-11-11 21:39 ("Begin"). Phase 2 scope (plan): tabs,
navigation, history, find, zoom, private tabs, start page, bookmarks, the
full menu and Customize. No media, no reader, no vault fill yet. A browser
Robert can genuinely browse with. Gate 2: first signed alpha installed
alongside Mariner; Robert browses and judges rendering and speed.

## Why this shape (read before coding)

Mariner's `BrowserScreen.kt` is ONE 6,674-line composable with vault fill,
login capture, media detection, downloads and reader hooks interleaved, and
`Store.kt` is a Room+JSON hybrid entangled with vault crypto, downloads,
books and playlists. A line-for-line port of those two files is not a
reliable single build. Phase 2 therefore builds a **clean Pioneer shell
written for Gecko**, reusing Mariner's engine-independent code where it is
clean (theme, menu system, start page, library screens) and taking
Mariner's BEHAVIOUR as the spec everywhere else. This is the documented
design decision for Phase 2. The plan's "shared code copied at fork time"
aspiration is honoured where the code is actually shareable; where it is
not, behaviour parity is the goal, not code identity.

## Rules for every worker on this phase

- Work ONLY in `~/workspace/notscape-pioneer/` (staging). Mariner at
  `~/workspace/notscape-mariner-rebuild/app-stage` is READ-ONLY — never
  edit it, never push to it. Do NOT touch the NotscapeMariner repo.
- NEVER push. The coordinator integrates and pushes (script
  `~/workspace/notscape-pioneer-push.py`, git-database API base_tree
  route). Never run `git` in the staging dir (it is not a git repo).
- NEVER change the interfaces in this doc unilaterally. If an interface
  is unworkable, report it to the coordinator with the exact problem.
- Do NOT build Phase 3 (media/downloads/player/fullscreen/permissions),
  Phase 4 (reader/TTS/vault fill/cast session), games, or the updater.
- Do NOT create a release keystore. Debug signing only (as the spike).
- Version for this phase: `0.1.0-alpha.3`, versionCode 3 (coordinator
  bumps at integration).
- Package `net.nspioneer.browser` everywhere. Fresh empty profile. No
  shared database with Mariner. No sync. Only Robert-approved extensions
  by name (uBlock Origin first), no general store.
- Public-presentation rule (public surfaces only): original project,
  local-only and private, stated positively. No mention of recovery,
  rebuild, 2.0.8, lost source, Cloak, or account/sync removal.
- Keep the spike's diagnostics: status lines + media lines + Copy status
  button (Robert uses it to send results), probe extension, Cast route
  discovery (probe only, no session launch in Phase 2).

## File map (ownership — do not edit outside your set)

- `engine/Engine.kt` + `engine/GeckoEngine.kt` + `engine/Extensions.kt`
  — ENGINE worker. Built from the spike's `MainActivity.kt` core.
- `engine/StatusPanel.kt` — ENGINE worker (status lines, copy text).
- `data/PioneerStore.kt` + `data/BookmarkHtml.kt` — STORE worker.
- `ui/BrowserScreen.kt` (new clean shell), `ui/StartPage.kt`,
  `ui/LibraryScreens.kt`, `ui/SettingsScreen.kt` (new focused),
  `ui/AppTheme.kt`, `ui/BrowserChrome.kt`, `ui/LicencesScreen.kt`
  (new) — SHELL worker. `MainActivity.kt` (rewrite) — SHELL worker.
- `app/build.gradle.kts`, `.github/workflows/android.yml`, `README.md`,
  `PHASE2-BUILD-LOG.md` — COORDINATOR only.

## Engine interface (ENGINE implements, SHELL consumes — exact)

```kotlin
package net.nspioneer.browser.engine

data class FindResult(val ordinal: Int, val count: Int, val found: Boolean)

interface EngineTab {
    val id: Int
    val isPrivate: Boolean
    val session: GeckoSession            // the one type leak: the shell's
                                         // AndroidView must display it.
    val url: State<String>               // "" while blank/home
    val title: State<String>
    val canGoBack: State<Boolean>
    val canGoForward: State<Boolean>
    val isLoading: State<Boolean>
    val progress: State<Int>             // 0..100
    val findResult: State<FindResult?>
    fun load(url: String)
    fun reload()
    fun stop()
    fun goBack()
    fun goForward()
    fun find(query: String)              // empty query clears the find
    fun findNext(forward: Boolean)
    fun clearFind()
    fun setTextZoom(factor: Float)       // 1.0f = 100%; per-site zoom.
        // AMENDED (research 2026-11-12, PHASE2-GECKOVIEW-API.md §2):
        // there is NO per-session text-zoom API in GeckoView. Implement
        // via the probe extension: a content script that applies the
        // factor (documentElement font-size scaling) on a "zoom" native
        // message. If the content-script route proves unworkable, fall
        // back to runtime-global GeckoRuntimeSettings.setFontSizeFactor
        // (NOT per-site — report it as a gap, do not pretend otherwise).
    val isDesktopMode: State<Boolean>
    fun setDesktopMode(desktop: Boolean)  // UA mode + viewport mode
        // (both — viewport alone still redirects to m.*), then reload.
        // Research §5: session.settings.setUserAgentMode(DESKTOP) and
        // setViewportMode(DESKTOP).
    fun close()
    var onNavigate: ((url: String, title: String) -> Unit)?  // committed
        // navigation, for history. Fire once per committed load, not per
        // subframe/redirect repeat. Skip about:blank and non-http(s).
    // NOTE (amended 2026-11-12): target=_blank / window.open arrives
    // as NavigationDelegate.onNewSession, which must RETURN the new
    // session synchronously — so the ENGINE creates the tab itself and
    // reports it via Engine.onTabCreated (below). There is no
    // onNewTabRequested on the tab; the shell opens long-press "new
    // tab" links itself via engine.createTab + load.
    var onLongPressLink: ((url: String) -> Unit)?  // if GeckoView exposes
        // a long-press/context hook; otherwise leave null (researcher
        // decides — see PHASE2-GECKOVIEW-API.md).
    var onCrash: (() -> Unit)?
}

interface Engine {
    fun createTab(isPrivate: Boolean): EngineTab
    fun destroyTab(tab: EngineTab)
    var onTabCreated: ((EngineTab) -> Unit)?  // fired when the engine
        // creates a tab itself (onNewSession / window.open): the shell
        // adds it to its tab list and switches to it.
    // uBlock Origin: install goes through onExtensionInstallPrompt —
    // the shell shows a real dialog (name + permissions, Install /
    // Later). NEVER auto-approve (the spike's auto-approve was
    // spike-only). installUBlock() checks first: if already installed
    // it just reports the version in uBlockState.
    var onExtensionInstallPrompt: ((name: String, permissions: List<String>,
        decide: (allow: Boolean) -> Unit) -> Unit)?
    fun installUBlock()
    val uBlockState: State<String>  // "not installed" | "installing…" |
        // "installed 1.76.0" | "failed: …" | "declined — retry from menu"
    fun setTrackingProtection(level: String)  // "strict" | "basic" |
        // "off" -> EtpLevel STRICT / DEFAULT / NONE via
        // runtime.settings.contentBlocking (research §7, changeable at
        // runtime); reload the current tab afterwards to be safe.
    val castRoutes: State<String>   // "cast: N route(s) — names", deduped:
        // only update when the route set actually changes (Gate 1 watch
        // item: the spike spammed the same line repeatedly).
    val statusLines: SnapshotStateList<String>
    val mediaLines: SnapshotStateList<String>
    fun copyStatusText(): String    // header + status + media probe,
        // same text the spike's Copy status button produced.
    fun refreshStats()              // recomputes the "stats: APK … | PSS …"
        // line (cold start, PSS, APK size).
    fun close()
}
```

Engine behaviour notes (amended 2026-11-12 from PHASE2-GECKOVIEW-API.md):
- There is NO `evaluateJavascript` on GeckoSession (research §8) —
  Mozilla: "the only supported way is to write a built-in
  WebExtension". All JavaScript in Pioneer goes through the probe
  extension (native messaging + content script). `evaluateJs` was
  REMOVED from the EngineTab interface for this reason.
- Find uses `session.getFinder()` (research §1); `FinderResult.total`
  may be -1 (unknown) — the FindBar must render that.
- Session save/restore exists via `onSessionStateChange` +
  `SessionState.toString/fromString` + `flushSessionState()` (§3),
  but Phase 2 keeps URL-based tab restore (store TabSnapshot) — full
  session-state restore is a later improvement, not Phase 2 scope.
- Private sessions are construction-time only (§4):
  `GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(true)
  .build())`.
- `onNewSession` (§9): return a newly-created, referenced session;
  never call `load` inside it; null means `window.open()` fails.
- Top-level vs subframe: `onLocationChange` is the URL-bar signal;
  `onLoadRequest` is top-level-only (pre-commit).
- One `GeckoRuntime` (ETP Strict, as the spike), one `GeckoSession`
  per tab. Private tabs use Gecko private sessions (researcher:
  exact constructor/settings flag). Private tabs are memory-only:
  never persisted, never in history, wiped when the last private tab
  closes (match Mariner's rule).
- Delegates: navigation (location, canGoBack/Forward, new session),
  progress (start/stop/progress), content (title, first composite for
  cold start, fullscreen, crash), permission (spike posture: allow
  media-key-system, autoplay, persistent storage; deny camera/mic/
  location outright — same as the spike).
- History: fire `onNavigate` from `onLocationChange` only for the
  tab's committed top-level load. Dedupe: do not fire twice for the
  same URL in a row.
- Watch item (Gate 1): a fresh page failed its first load twice
  (youtube.com, flower.mp4) then loaded fine on retry. Investigate
  if cheap (suspect: loadUri raced session open in the spike's
  onCreate; make sure a tab's session is fully open before the first
  loadUri). If not cheap, carry as a known issue with the finding
  written down.
- Keep the probe extension (built-in, media + EME reporting) and the
  media-URL sniffing from `onLoadRequest` exactly as the spike had
  them. They are diagnostics, not the Phase 3 finder.

## PioneerStore API (STORE implements, SHELL consumes — exact)

Fresh store. JSON files under `filesDir/pioneer-store/` (one file per
collection + one settings file). No Room, no KSP — the AGP 9 toolchain
has no Kotlin plugin and Room would need KSP; JSON keeps Phase 2
build risk low. Save debounced (≤500 ms after a mutation) and on
pause. Load at construction; an empty/missing file is an empty store
(fresh profile). `revision` (mutableStateOf Int) bumps on every
mutation so Compose recomposes; collections are SnapshotStateLists.

```kotlin
package net.nspioneer.browser.data

data class Bookmark(val id: Long, val title: String, val url: String,
    val createdAt: Long, val folder: String = "")
data class HistoryEntry(val id: Long, val title: String, val url: String,
    val visitedAt: Long)
data class SiteSetting(val host: String, val javaScript: Boolean = true,
    val blockImages: Boolean = false, val allowPopups: Boolean = false,
    val disableBlocking: Boolean = false, val blockPopups: Boolean = false,
    val allowThirdPartyCookies: Boolean = false)
data class TabSnapshot(val url: String, val title: String,
    val desktopMode: Boolean = false)

class PioneerStore private constructor(context: Context) {
    companion object { fun get(context: Context): PioneerStore }
    var revision by mutableStateOf(0)
    val bookmarks: SnapshotStateList<Bookmark>
    val bookmarkFolders: SnapshotStateList<String>
    val history: SnapshotStateList<HistoryEntry>
    val favourites: SnapshotStateList<Bookmark>
    val settings: MutableMap<String, String>
    val tabSession: MutableList<TabSnapshot>
    var tabSessionActive: Int
    fun setting(key: String, default: String): String
    fun putSetting(key: String, value: String)
    fun removeSetting(key: String)
    fun isBookmarked(url: String): Boolean
    fun toggleBookmark(title: String, url: String)
    fun removeBookmark(id: Long)
    fun updateBookmark(id: Long, title: String, url: String)
    fun restoreBookmarks(items: List<Bookmark>)
    fun importBookmarks(items: List<Triple<String, String, String>>): Int
    fun addBookmarkFolder(name: String, parent: String = "")
    fun renameBookmarkFolder(old: String, newSegment: String)
    fun deleteBookmarkFolder(name: String)
    fun setBookmarkFolder(id: Long, folder: String)
    fun addHistory(title: String, url: String)   // no-op for blank,
        // non-http(s), or same URL as the last entry
    fun clearHistory()
    fun removeHistory(id: Long)
    fun clearHistoryOlderThan(cutoffMs: Long)
    fun toggleFavourite(title: String, url: String)
    fun isFavourite(url: String): Boolean
    fun siteSetting(host: String): SiteSetting
    fun putSiteSetting(setting: SiteSetting)
    fun siteZoom(host: String): Float             // 1.0f default
    fun putSiteZoom(host: String, zoom: Float)
    fun siteDesktop(host: String): Boolean
    fun putSiteDesktop(host: String, desktop: Boolean)
    fun saveTabSession(tabs: List<TabSnapshot>, active: Int)
    fun loadFavicon(host: String): Bitmap?        // P2: return null
        // unless a cached file exists (no favicon fetching in P2 —
        // start-page tiles fall back to the title letter, as Mariner
        // already does). Record this as a known gap.
    fun exportBookmarksHtml(): String
    fun importBookmarksHtml(text: String): Int
}
```

Folder helpers (copy the pure functions from Mariner `Store.kt`
lines ~94–153: `folderParent`, `folderName`, `folderChild`,
`isFolderSegment`, `folderAncestors`, `folderChildren`,
`renameFolderPath`, `refileBookmarksAfterRename`) — they are pure and
unit-testable; keep their behaviour identical.

Bookmark HTML (`BookmarkHtml.kt`): Netscape format, same as Mariner
produces/consumes. Read Mariner's import/export first (in `Store.kt`
and `SettingsScreen.kt` — grep `exportBookmarks` / `importBookmarks`
/ `BOOKMARK` / `DL>` there) and match the format exactly, including
folder nesting (`<H3>` = folder, `<A HREF>` = bookmark) so a Mariner
export imports cleanly. `importBookmarksHtml` returns the count added
(dedupes by URL).

Settings keys used in Phase 2 (store is generic; these are the keys
the shell reads/writes — keep the Mariner key names where the carried
code already uses them): `themeMode` (system/light/dark),
`showBanner` (true/false), `wallpaper` (follow-theme only in P2 —
"custom" photo is NOT in P2), `menuOrder` / `menuHidden` (whatever
keys `BrowserMenuCustomizeSheet` uses — SHELL worker reads
`BrowserChrome.kt` and keeps them), `homePage` (start page; custom
home URL is NOT in P2), `searchEngine` (duckduckgo — fixed in P2,
shown as text), `trackingProtection` (strict/basic/off — see note
below), `startPageTiles` (true/false).

Tracking protection note: ETP level is set at GeckoRuntime creation.
If the researcher finds ETP can be changed live on the runtime, the
setting applies immediately; otherwise the Settings UI says "takes
effect after restart" and the engine reads the key at startup.

## Shell behaviour spec (SHELL worker — Mariner behaviour is the spec)

- **MainActivity**: owns one `Engine` + one `PioneerStore`. Screen
  navigation: Browser | Library (Bookmarks / History / Favourites) |
  Settings | Licences. Back: engine back if the tab can go back,
  else back to the start page, else close tab / exit (match Mariner's
  back chain: start page is the last step before leaving the tab).
- **Tabs**: `GeckoSession` per tab under the one runtime. Tab
  switcher: a sheet listing tabs (title + URL, close button, "New
  tab", "New private tab") — title rows, NO thumbnails in P2
  (record as a gap). New tab opens the start page. Duplicate tab,
  close-other-tabs, close-duplicate-tabs: include if cheap, else
  record as gaps. Session restore: on launch, restore non-private
  tabs from `store.tabSession` by URL (no session-state restore in
  P2); persist on pause and on tab change. Private tabs never
  persisted; closing the last private tab wipes the private profile
  (sessions closed, nothing written).
- **Start page** (carried `StartPage.kt`, adapted): most-visited
  tiles (top hosts by history count), favourites row, bookmarks quick
  access, banner. Drop the book / updates / console / game callbacks
  (default them to no-ops). Wallpaper follows the theme (light/dark
  colours only — no custom photo in P2).
- **Address bar**: URL entry + Go; typing a bare domain adds
  `https://`; anything else searches DuckDuckGo (same rule as the
  spike's `loadAddress`). Suggestions card (carried
  `SuggestionsCard` from `BrowserChrome.kt`) over history +
  bookmarks as you type.
- **Find in page** (carried `FindBar` from `BrowserChrome.kt`):
  find / find-next / find-previous, ordinal/count pills, Esc/close
  clears. Wired to `EngineTab.find/findNext/clearFind`.
- **Zoom**: per-site text zoom, stored per host
  (`store.siteZoom`); zoom controls in the menu (100% default,
  +/- steps, reset). Applied via `EngineTab.setTextZoom` when a tab
  navigates to a host with a stored zoom.
- **Desktop site**: per-site toggle (`store.siteDesktop`), applied
  on navigation IF the researcher finds a GeckoView user-agent
  override; if none exists, the menu row is omitted in P2 and
  recorded as a gap.
- **Menu** (carried `BrowserChrome.kt` menu system +
  `BrowserMenuCustomizeSheet`): rows for everything Phase 2 has —
  New tab, New private tab, Bookmarks, History, Favourites, Find in
  page, Share, Copy link, Add bookmark / Add favourite, Desktop
  site (if supported), Zoom, Settings, Licences, Install uBlock
  Origin (only while not installed), Copy status, Customize menu.
  OMITTED in P2 (arrive with their phases — say so in the report,
  do not fake them): Downloads, Reader view, Vault, Games, TTS.
  Menu order/hiding is user-customizable via the Customize sheet
  and persists in the store (same keys as Mariner).
- **Long-press link menu** (if the researcher finds a GeckoView
  long-press hook): Open in new tab, Open in background tab, Open
  in private tab, Copy link, Share. If no hook exists, omit and
  record as a gap.
- **Bookmarks**: add/remove/edit, nested folders (create/rename/
  delete/move), HTML import (file picker) + export (share/save).
  Library screens carried from Mariner `LibraryScreens.kt`
  (Bookmarks/History/Favourites only — drop Playlists/Downloads/
  ReadingList).
- **History**: uncapped per-visit recording (engine `onNavigate` →
  `store.addHistory`), History screen with clear + clear-older-than
  + per-entry delete. Private tabs never record.
- **Settings screen** (NEW, focused — do NOT carry Mariner's
  3,467-line `SettingsScreen.kt`): Appearance (theme), Start page
  (tiles on/off, banner on/off), Search (DuckDuckGo, fixed text),
  Privacy (tracking protection level, Clear history, Erase private
  data), Bookmarks (Import HTML, Export HTML), Extensions (uBlock
  Origin status + Install/Remove), About (version, GeckoView
  version, Licences). Every row must do something real; no dead
  rows.
- **Theme** (carried `AppTheme.kt`, adapted to `PioneerStore`):
  system/light/dark (+ wallpaper follows theme). Keep Mariner's
  colour schemes as they are — Pioneer should look like Mariner.
- **Licences screen** (new, simple): GeckoView MPL 2.0 notice,
  Pioneer GPL-3.0, list of bundled libraries. (Plan: licences-screen
  pattern carries across; a Phase 2 checklist item.)
- **uBlock prompt**: first launch (or menu Install row) → dialog
  "Install uBlock Origin?" with the permission list, Install /
  Later. Later = `uBlockState` "declined — retry from menu", menu
  row stays. Never auto-approve.
- **Diagnostics**: menu rows "Copy status" (copies immediately, as
  alpha.2 did) and "Diagnostics" (opens the status-panel view with
  status + media probe lines, scrollable, with its own Copy status
  button).

## Known gaps to record (do not hide them)

Favicons are letter tiles in P2 (no fetching). Tab thumbnails: none.
Desktop site: only if a Gecko UA override exists. Long-press menu:
only if a Gecko hook exists. First-load-failed-then-retry (Gate 1
watch item) if the engine worker cannot find it cheaply. ETP level
change may need a restart. Custom wallpaper photo: not in P2.
Downloads/reader/vault/games/TTS/updater: later phases, not gaps —
out of scope by design.

## Researcher brief (separate worker — see PHASE2-GECKOVIEW-API.md)

Verify against Mozilla's GeckoView docs/source (not memory): Finder
API (find/findAll/results), text zoom (per-session?), session
save/restore state API, private session construction, user-agent
override, long-press/context-menu hook, ETP change at runtime,
`evaluateJavascript` signature. One line per API: exact signature,
since which version, and a doc/source link. Flag anything that does
NOT exist so the design can drop it cleanly.
