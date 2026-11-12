# Notscape Pioneer — UI audit + full inventory (Mariner RC1 vs Pioneer 0.1.0-alpha.3)

- **Date:** 2026-11-11/12. **Auditor:** read-only; nothing edited, committed, or pushed.
- **Reference:** Mariner RC1 tree `~/workspace/notscape-mariner-rebuild/app-stage` (BrowserScreen.kt 6,674 lines; SettingsScreen.kt 3,467 lines).
- **Subject:** Pioneer 0.1.0-alpha.3, staging `~/workspace/notscape-pioneer`, repo `DocBobert6/NotscapePioneer` head `43a77b68`.
- **Bar:** Robert's — Pioneer's UI must be IDENTICAL to Mariner's for everything, floating UI included.
- **Method:** six read-only auditors (Mariner floating-UI inventory; Pioneer UI inventory; shared-code diffs; settings-key grep; Settings-row enumeration; feature inventory) + direct verification of the headline miss (`barPosition`). Package renames (`net.notscape.browser` → `net.nspioneer.browser`) do not count as differences.

## Headline counts

| Inventory | Total | Present | Differs | Missing | Missing-now (already-built scope) |
|---|---|---|---|---|---|
| Setting keys | 140 | 7 | 3 | 130 | ~25 (rest: Phase 3/4, games, updater) |
| Settings-screen rows | 114 | 1 | 13 | 100 | ~30 (rest: Phase 3/4, games, updater) |
| Features/behaviours | 115 | 37 | 22 | 56 | 26 (+6 of 19 dropped menu rows) |
| Shared-code files audited | 8 | 1 identical (FrostKit) | 2 near-identical (AppTheme, BookmarkHtml) | 4 substantially rewritten, 1 no counterpart | — |
| Mariner floating elements | 35 | — | — | — | see §1 |

**Visible vs code-only:** the visible differences in already-built scope are dominated by the floating address bar + satellites (docked top bar instead), missing Forward/Stop/Home buttons, no pull-to-refresh / scroll-hide / swipe-on-pill, bare tab switcher (no thumbnails, no recently-closed, no undo), letter-tile favicons, default wordmark font, chevron→arrow back icon, and ~30 missing Settings rows. Code-only differences (no user-visible effect): BookmarkHtml internals, `pendingHref` locals, history-undo fresh timestamps (ordering changes — borderline visible), `MenuRow` extraction, `Trace.event` removal, section labels as plain strings, suggestion icon contentDescription.

## §1 — Floating UI: Mariner's 35 elements vs Pioneer

Shared recipe (both sides, `ui/FrostKit.kt` — **identical code**): solid-frost, no blur; `Frost.brush()` vertical gradient `surface @0.97 → @0.92`; hairline `outline @0.45`, 0.5dp; `frostBar(shape, elevation=6dp)` = shadow → clip → gradient → border; PillShape `RoundedCornerShape(50)`, CardShape 20dp, SheetShape top 28dp, DialogShape 28dp; sheet colour solid `surfaceContainerLow`; card colour `surfaceBright`.

| # | Mariner floating element | Mariner ref | Pioneer status | Pioneer ref / note |
|---|---|---|---|---|
| 1 | Floating address bar (main URL pill, bottom default, scroll-hides) | BrowserScreen.kt:3663-3664, 4386-4410 | **DIFFERS — docked top bar, no float, no scroll-hide, no bottom option** | ui/BrowserScreen.kt:231-272: plain M3 `TextField` in a top `Row`, first child of a `Column`; no `frostBar`, no shadow, no overlay |
| 2 | Address-bar satellite circles (Back/Forward pill, Stop, tab-count, Media circle, Menu circle) | BrowserScreen.kt:3676-3995 | **MISSING** (tab-count exists as a plain outlined button, not a frost circle; no Back/Forward/Stop/Media/Menu circles) | ui/BrowserScreen.kt:237-241 tab-count `OutlinedButton`; menu is a separate `IconButton` at :269-271 |
| 3 | Download pill (240dp frost pill hugging the bar) | BrowserScreen.kt:3526, 3567 | Missing — **Phase 3** (downloads) | — |
| 4 | Vault field chip ("Fill saved password"…) | BrowserScreen.kt:3613, 3637 | Missing — **Phase 4** (vault) | — |
| 5 | Find-in-page typing pill | BrowserChrome.kt:106 | **Present, identical code** | ui/BrowserChrome.kt:129-179, `frostBar(CircleShape)` |
| 6 | Find match-count/stepper pill | BrowserChrome.kt:151, 208 | **Present, identical code** | ui/BrowserChrome.kt:200-259 |
| 7 | Local suggestions card | BrowserChrome.kt:~251 | **Present, identical code** (one accessibility-label-only diff: "Reload"→"History") | ui/BrowserChrome.kt:262-331 |
| 8 | Popup-blocked panel | BrowserScreen.kt:4277, 4286 | **MISSING now** (no popup UI at all) | — |
| 9 | Pull-to-refresh indicator circle | BrowserScreen.kt:4168-4200 | **MISSING now** (no pull-to-refresh) | — |
| 10 | App-mode exit button (flat `surface @0.92` circle, not frost) | BrowserScreen.kt:4448-4470 | **MISSING now** (no app mode) | — |
| 11 | Tab-grid bottom action bar (frost pill, 8dp elevation) + update pill | BrowserScreen.kt:4984, 4590-4600 | **DIFFERS** — Pioneer switcher is a `FrostSheet` with text rows + plain buttons, no floating pill bar, no thumbnails, no update pill | ui/BrowserScreen.kt:332-422 |
| 12 | Undo snackbar (BottomCenter, 88dp clearance) | BrowserScreen.kt:5056-5060 | **MISSING now** for tab-close undo (snackbars exist in Library only) | ui/LibraryScreens.kt:830-836, 1043-1050 |
| 13 | Frost sheets: Vault fill / Form fill / long-press menu / Back-Forward history | BrowserScreen.kt:5352, 5401, 5671, 6460 | **Present in kind** (FrostSheet exists; Vault/Form-fill/Back-Forward sheets absent — Phase 4 / missing-now for Back-Forward) | ui/FrostKit.kt:121-133; long-press is a `FrostDialog`, not a sheet — see §2 |
| 14 | Frost dialogs (13 call sites) | BrowserScreen.kt:4206, 4240, 4532, 5065, 5105, 5474, 5581, 5732, 5841, 5883, 6055, 6551, 6612 | **Present in kind** (FrostDialog identical); the vault/media/download/permission dialogs are Phase 3/4 | ui/FrostKit.kt:140-159; used in Library/Settings/BrowserScreen |
| 15 | "Customize menu" sheet | BrowserChrome.kt:502-506 | **Present** | ui/BrowserChrome.kt:469-561 |
| 16 | Cast-device chooser dialog (plain Dialog, 16dp corners — non-Frost exception) | BrowserScreen.kt:~6500 | Missing — **Phase 4** (cast session) | — |
| 17 | Floating media card ("Media on this page" sheet + GroupedCard) | BrowserScreen.kt:6274, 6415 | Missing — **Phase 3** (media) | — |
| 18 | Player top capsule (Close/PiP/Stop) | PlayerActivity.kt:1360-1388 | Missing — **Phase 3** (player; must be identical per Robert 2026-11-11) | — |
| 19 | Player centre transport circles | PlayerActivity.kt:1391-1508 | Missing — **Phase 3** | — |
| 20 | Player volume/brightness level pill | PlayerActivity.kt:1255-1290 | Missing — **Phase 3** | — |
| 21 | Player bottom options capsule | PlayerActivity.kt:1511-1520, ~1756 | Missing — **Phase 3** | — |
| 22 | Player scale/quality/audio/subtitle option rows | PlayerActivity.kt:1529, 1569, 1644, 1676 | Missing — **Phase 3** | — |
| 23 | Portrait scrubber pill (the floating pill inset 20dp from the edges) | PlayerActivity.kt:1955-2010 | Missing — **Phase 3** | — |
| 24 | Portrait brightness pill | PlayerActivity.kt:2034-2075 | Missing — **Phase 3** | — |
| 25 | Portrait volume pill | PlayerActivity.kt:2093-2135 | Missing — **Phase 3** | — |
| 26 | Player queue dialog (stock AlertDialog — non-Frost exception) | PlayerActivity.kt:978 | Missing — **Phase 3** | — |
| 27 | Reader top pill | ReaderScreens.kt:2452-2466 | Missing — **Phase 4** (reader) | — |
| 28 | Reader match bar | ReaderScreens.kt:2637-2648 | Missing — **Phase 4** | — |
| 29 | Read-aloud status strip | ReaderScreens.kt:2691-2700 | Missing — **Phase 4** (TTS) | — |
| 30 | Reader transport pill (books) | ReaderScreens.kt:2786-2799 | Missing — **Phase 4** | — |
| 31 | Reader frost sheets (Chapters/Search/Typesetting/Voice) | ReaderScreens.kt:2894, 3081, 3199, 3597 | Missing — **Phase 4** | — |
| 32 | Game chrome top row (expand pill + title + action pills) | GameActivity.kt:1765-1930 | Missing — **games (no phase assigned)** | — |
| 33 | On-screen game controls (frost D-pad/buttons/pills) | GameActivity.kt:2661, 2706, 2747 | Missing — **games** | — |
| 34 | Game-card favourite star | GamesScreen.kt:3633 | Missing — **games** | — |
| 35 | Touch-layout editor pills | GameLayoutEditor.kt:336-355, 369 | Missing — **games** | — |

Also confirmed by grep: **no `FloatingMedia`/`MediaCard` composable exists in Mariner** — the "media card" is the `GroupedCard` inside the "Media on this page" `FrostSheet` (#17). **No FAB exists in Mariner** (zero hits for `FloatingActionButton`) — nothing to match there. Deliberately NOT floating in Mariner (do not "fix" toward floating): `KitPill` filter chips, `GroupedCard`/`PanelCard` in-flow cards, the browser menu page (full opaque page), the StartPage "Edit shortcuts" dialog (large centred card, elevation 0).

## §2 — Screen-by-screen UI comparison (already-built scope)

### Tab strip / switcher — DIFFERS (visible)
- Mariner: full-screen tab grid overlay (`BrowserScreen.kt:4573`), opaque gradient backdrop, thumbnails, grid/list toggle (`tabViewMode`), search, drag-reorder, floating pill action bar (8dp elevation, + / count / Done), update pill top-right, per-class grids (normal vs private separate), recently-closed.
- Pioneer: no persistent strip; a number in a small `OutlinedButton` top-left (`ui/BrowserScreen.kt:237-241`); tap opens a `FrostSheet` (`:332-422`) with text rows (title over URL), lock icon for private, close X; buttons New tab / New private tab / Duplicate / Close others. Header admits "NO thumbnails in Phase 2 (known gap)".
- **Visible differences:** no thumbnails, no grid, no search, no reorder, no recently-closed, no close-undo, tab count mixes private+normal, private/normal share one mixed list.
- **Fix:** port Mariner's tab grid (thumbnails via GeckoSession screenshots — needs engine support — else title rows in Mariner's grid layout as an interim, clearly marked), separate per-class grids, recently-closed list, close-undo snackbar, per-class count.

### Address bar — DIFFERS (visible, the headline gap)
- Mariner: floating frost capsule, 12dp side inset, 10dp from the edge, bottom by default (`barPosition`, default `bottom`), scroll-hides (slide+fade), swipe-on-pill switches tabs, home/lock/mic/clear-X inside, satellite circles beside it, loading progress fills the pill itself.
- Pioneer: docked top `TextField` in a `Row` (`ui/BrowserScreen.kt:231-272`), plain M3 filled style, no frost, no shadow, never moves, never hides, no bottom option, no swipe, no mic, no site-info icon, no Cancel button while typing (Back only).
- **Fix:** rebuild the bar as Mariner's floating capsule (reuse identical `FrostKit.frostBar`), honour `barPosition` (Bottom default — see §6), scroll-hide, swipe-on-pill, satellite circles (Back/Forward, Stop, tab-count, Media, Menu), pill-fill progress, mic + site-info.

### Start page — DIFFERS (visible)
- Mariner (`browser/StartPage.kt`, 997 lines): banner with swatch/artwork + wallpaper-derived colours, shortcuts pins row + Edit dialog, Continue watching/listening + Continue reading, custom-photo wallpaper, most-visited tiles with fetched favicons, update row.
- Pioneer (`ui/StartPage.kt`, 350 lines; carried + cut down): banner as plain `PanelCard` (fixed theme colours), Favourites grid, Frequently-visited grid, **added** Bookmarks section (latest 8 — Mariner has none). Flat light/dark colours only, no photo; letter-tile favicons only.
- **Fix:** restore shortcuts pins + Edit, Continue rows (Phase 3 media / Phase 4 reader feed them — do not fake empty rows), custom-photo wallpaper, banner swatch, fetched favicons; keep the Bookmarks section only if Robert wants it (it is Pioneer-only — confirm, do not assume).

### Menu + Customize — DIFFERS (visible)
- Mariner: full opaque menu page, 33 rows; Customize sheet with switches + reorder.
- Pioneer: full menu page, **18 rows**; Customize present with same keys (`menuHiddenRows`/`menuRowOrder`) and working hide/reorder/reset. Added Pioneer-only rows: Install uBlock Origin, Copy status, Diagnostics, Licences. Dropped 19: readArticle, media, blockElement, savePdf, saveOffline, scanQr, screenshot, addToHome, siteBlocking, globalAds, readingList, readLater, playlists, offlineLibrary, downloads, bookshelf, games, vaultFill, passwordVault — of these, blockElement, siteBlocking, globalAds, scanQr, screenshot, addToHome are **missing now** (no phase assigned); the rest are Phase 3/4/games.
- Small diffs: Copy page URL / Open in other browser lost their icons; "Customize menu" row icon Tune→Edit; per-site text-zoom card added (no Mariner counterpart — keep, it is Pioneer's zoom UI); Desktop site row shows ✓ (Mariner shows none — harmless, keep or match); update banner absent (updater scope); section labels plain strings instead of `stringResource` (code-only).
- **Fix:** restore the 6 missing-now rows when their behaviours land (blockElement/siteBlocking/globalAds need the Phase 3 blocking work; scanQr/screenshot/addToHome are small standalone additions); restore the two lost row icons and the Tune icon.

### Settings — DIFFERS (visible, largest gap in built scope)
- Mariner: 3,467 lines, 15 groups + sub-screens, 114 rows inventoried (§4).
- Pioneer: 393 lines, 6 sections, 17 rows. Present: theme System/Light/Dark (no Material You rows — values honoured if carried over, but not selectable), start-page tiles/banner toggles, DuckDuckGo fixed, tracking protection Strict/Basic/Off, Clear history, Erase private data, bookmark HTML import/export, uBlock row (no Remove), version, GeckoView version, Licences.
- **Fix:** restore the missing-now rows in §4 (bar position, web dark mode, web text size, UI font + scale + custom font, interface text size, immersive, restore-tabs toggle, open-links-in-new-tab, search-engine choice + custom URL, HTTPS-only, cookies/session/DOM/GPC/referer/autoplay, clear-data ranges + per-type toggles, delete-on-exit, per-site controls, default browser, welcome-guide replay, reduced motion, force zoom, system captions, storage sizes, clear cache). Phase 3/4 rows land with their phases.

### Licences — DIFFERS BY DESIGN (visible, not a gap to "fix" blindly)
- Mariner has no licences screen — only a `ThirdPartyNoticesDialog` (emulator cores/shaders/libraries).
- Pioneer has a full `LicencesScreen` (GPL-3.0, GeckoView MPL 2.0, bundled Android libraries + uBlock GPLv3). Correct content for a Gecko app; keep the screen, keep the content.

### Diagnostics — PIONEER-ONLY (keep)
- No Mariner counterpart. `DiagnosticsScreen` + Copy status button + status/media lines. Keep — Robert uses it to send results.

### Find bar — PRESENT, IDENTICAL CODE (one visible bug)
- `FindBar` + `SuggestionsCard` are byte-identical to Mariner's. **Bug:** unknown match total (`FinderResult.total == -1`) renders as "0 / 0" even when matches exist (`engine/GeckoEngine.kt:535-546`). Fix: render count-only / "…" when total is unknown.

### Private tabs — DIFFERS (visible)
- Mariner: separate per-class tab grids, private profile, wipe on last close.
- Pioneer: mixed list with lock icon only; no separate private start page/mode; private tabs not persisted (good) but no explicit profile wipe on last close (relies on Gecko session close).
- **Fix:** separate per-class switcher views; explicit wipe; private-mode indicator beyond the lock icon.

### Bookmarks / History / Favourites — NEAR-IDENTICAL (small visible diffs)
- The three carried screens are near-identical ports (folders, multi-select, delete-undo snackbars, edit/rename dialogs survive).
- **Diffs:** letter-tile favicons instead of fetched favicons (Mariner `store.loadFavicon(host)` + `Public` icon fallback); history undo re-adds with a fresh id/timestamp (ordering changes after undo — Mariner restores exactly); `GroupTitle`/`SettingsCard`/`InsetDivider` moved files (code-only).
- **Fix:** fetch/cache favicons; restore history exactly on undo.

### Long-press link menu — DIFFERS (visible)
- Mariner: 8 rows in a `FrostSheet` (link/image actions in a `GroupedCard`).
- Pioneer: 5 rows in a `FrostDialog` (Open new/background/private tab, Copy link, Share) — missing View image, Download, Block this element; presented as dialog not sheet; and conditional on the engine firing `onLongPressLink` (known gap if GeckoView exposes no hook).
- **Fix:** sheet presentation to match; add the 3 missing rows (Download + Block this element land with Phase 3).

### First-run / uBlock prompt — DIFFERS (visible, minor)
- Mariner: full WelcomeScreen + onboarding + default-browser prompt.
- Pioneer: no onboarding; only the uBlock prompt — and it is a **plain `AlertDialog`, not `FrostDialog`** (`MainActivity.kt:212-260`). No welcome guide, no default-browser request.
- **Fix:** make the uBlock prompt a `FrostDialog`; onboarding/default-browser are missing-now additions (welcome guide replay is a Settings row too).

## §3 — Settings keys: full table (140 keys)

Method: grepped Mariner for `setting("…")` / `putSetting("…")` / `removeSetting("…")` (195 read sites, 106 write sites) + const keys + literal key lists; per-site/per-game/per-console suffixed keys collapsed into one family row each. Pioneer verified by direct grep per key. Paths: M = `net/notscape/browser/`, P = `net/nspioneer/browser/`.

| key | area | Mariner ref | Pioneer | Pioneer ref | effect if missing/different |
|---|---|---|---|---|---|
| themeMode | appearance | browser/StartPage.kt:160 (default `system`) | differs | ui/AppTheme.kt:178, ui/SettingsScreen.kt:184 | Settings offers only System/Light/Dark; Material You values honoured if carried over but not selectable |
| wallpaper | appearance | browser/StartPage.kt:147 (default `auto`) | missing | absent | No start-page wallpaper choice |
| uiFont | appearance | ui/AppTheme.kt:171 (default `default`) | missing | absent | No UI font choice |
| uiFontScale | appearance | ui/AppTheme.kt:273 (default `1.0`) | missing | absent | No UI font scaling |
| uiFontCustomName | appearance | ui/SettingsScreen.kt:198 | missing | absent | No custom imported UI font |
| webDarkMode | appearance | browser/BrowserScreen.kt:2529 (default `Auto`) | missing | absent | No darkening of web pages |
| webTextZoom | appearance | browser/BrowserScreen.kt:1695 (default `100`) | missing | absent | No global web text zoom |
| reducedMotion | appearance | browser/BrowserScreen.kt:2361 (default `false`) | missing | absent | No reduced-motion option |
| immersive | appearance | MainActivity.kt:232 (default `false`) | missing | absent | No immersive/fullscreen mode |
| barPosition | bar position | browser/BrowserScreen.kt:3512 (default `bottom`); row ui/SettingsScreen.kt:927 | missing | absent | **Address bar fixed at top; no Bottom option (see §6)** |
| searchEngine | search | browser/BrowserScreen.kt:941 (default `duckduckgo`) | missing | absent | **Search locked to DuckDuckGo** (Pioneer ui/SettingsScreen.kt:230-236 "Fixed in this phase") |
| customSearchUrl | search | browser/BrowserScreen.kt:946 | missing | absent | No custom search URL |
| blockAds | privacy/blocking | browser/BrowserChrome.kt:630 (default `true`) | differs | ui/SettingsScreen.kt:237 (`trackingProtection`) | No Mariner-style ad-block toggle; partially covered by ETP + optional uBlock; no per-list/custom-rule control |
| blockTrackers | privacy/blocking | browser/BrowserScreen.kt:2268 (default `true`) | differs | ui/SettingsScreen.kt:237 (`trackingProtection`) | Folded into single ETP level; no separate tracker toggle |
| filterListsOff | privacy/blocking | MainActivity.kt:103 | missing | absent | Cannot disable individual filter lists (Phase 3) |
| customFilterUrl | privacy/blocking | MainActivity.kt:105 | missing | absent | No custom filter-list URL (Phase 3) |
| customAbpRules | privacy/blocking | MainActivity.kt:106 | missing | absent | No custom ABP rules (Phase 3) |
| userBlockNet | privacy/blocking | browser/UserBlockRules.kt:25 | missing | absent | No user network block rules (Phase 3) |
| userBlockCosmetic | privacy/blocking | browser/UserBlockRules.kt:26 | missing | absent | No user cosmetic (hide-element) rules (Phase 3) |
| userScripts | privacy/blocking | browser/UserScripts.kt:27 | missing | absent | No user scripts |
| httpsOnly | privacy/blocking | browser/BrowserScreen.kt:1003 (default `false`) | missing | absent | No HTTPS-only mode |
| httpsExceptions | privacy/blocking | data/Store.kt:70 | missing | absent | No per-site HTTPS exceptions |
| cookies | privacy/blocking | browser/BrowserScreen.kt:1785 (default `true`) | missing | absent | No cookie accept/reject control |
| sessionCookies | privacy/blocking | browser/BrowserScreen.kt:1740 (default `false`) | missing | absent | No session-only cookies option |
| blockThirdPartyCookies | privacy/blocking | browser/BrowserScreen.kt:1806 (default `true`) | missing | absent | No third-party-cookie blocking toggle |
| domStorage | privacy/blocking | browser/BrowserScreen.kt:2508 (default `true`) | missing | absent | No DOM-storage toggle |
| gpc | privacy/blocking | browser/BrowserScreen.kt:1028 (default `true`) | missing | absent | No Global Privacy Control signal |
| refererPolicy | privacy/blocking | browser/BrowserScreen.kt:2346 | missing | absent | No referrer-policy choice |
| popupStrict | privacy/blocking | browser/BrowserScreen.kt:3216 (default `true`) | missing | absent | No strict popup blocking toggle |
| autoplay | privacy/blocking | browser/BrowserScreen.kt:2306 (default `allow`) | missing | absent | No autoplay policy |
| blockedHistory | privacy/blocking | MainActivity.kt:198 | missing | absent | No blocked-count history (Phase 3) |
| videoPopupOff:$host | privacy/blocking | browser/VideoPopup.kt:24 | missing | absent | No per-site video-pop-up opt-out (Phase 3) |
| sitezoom:$host | per-site | data/Store.kt:1113 | present | data/PioneerStore.kt:470 | Same behaviour (unset = 100%) |
| sitedesktop:$host | per-site | data/Store.kt:1121 | present | data/PioneerStore.kt:482 | Same behaviour |
| siteperm:$host:$resource | per-site | data/Store.kt:1136 (default `ask`) | missing | absent | No per-site permission decisions (Phase 3) |
| siteautoplay:$host | per-site | data/Store.kt:1144 | missing | absent | No per-site autoplay override |
| sitereload:$host | per-site | data/Store.kt:1155 | missing | absent | No per-site reload flag |
| downloadLocation | downloads | browser/BrowserScreen.kt:613 (default `public`) | missing | absent | No download location choice (Phase 3) |
| confirmDownloads | downloads | browser/BrowserScreen.kt:606 (default `true`) | missing | absent | No download confirmation prompt (Phase 3) |
| wifiOnlyDownloads | downloads | media/Downloads.kt:310 (default `false`) | missing | absent | No Wi-Fi-only downloads (Phase 3) |
| segmentedDownloads | downloads | media/Downloads.kt:862 (default `true`) | missing | absent | No segmented/parallel downloads (Phase 3) |
| downloadNotifAsked | downloads | media/DownloadNotifAsk.kt:30 | missing | absent | No download-notification opt-in state (Phase 3) |
| dljob:$id | downloads | media/Downloads.kt:191 | missing | absent | No persisted download jobs / resume (Phase 3) |
| dlerror:$recId | downloads | media/Downloads.kt:334 | missing | absent | No per-download error state (Phase 3) |
| dldowngrade:$recId | downloads | media/Downloads.kt:335 | missing | absent | No segmented→single downgrade state (Phase 3) |
| dltombstone:$id | downloads | data/Store.kt:796 | missing | absent | No download tombstones (Phase 3) |
| epubAutoShelf | downloads | media/Downloads.kt:3688 (default `true`) | missing | absent | No auto-shelving of downloaded EPUBs (Phase 3/4) |
| playerSkipSec | media player | media/PlayerActivity.kt:420 (default `10`) | missing | absent | No media-player skip interval (Phase 3) |
| playerSpeed | media player | media/PlayerActivity.kt:390 (default `1.0`) | missing | absent | No playback speed (Phase 3) |
| playerScale | media player | media/PlayerActivity.kt:323 (default `Fit`) | missing | absent | No video scale/fit mode (Phase 3) |
| playerVolume | media player | media/PlayerActivity.kt:402 (default `1.0`) | missing | absent | No remembered player volume (Phase 3) |
| playerBrightness | media player | media/PlayerActivity.kt:407 (default `system`) | missing | absent | No player brightness control (Phase 3) |
| subFont | media player | media/PlayerActivity.kt:2324 (default `default`) | missing | absent | No subtitle font (Phase 3) |
| subFontCustomName | media player | ui/SettingsScreen.kt:3281 | missing | absent | No custom subtitle font (Phase 3) |
| subSize | media player | media/PlayerActivity.kt:2348 (default `100`) | missing | absent | No subtitle size (Phase 3) |
| subColor | media player | media/PlayerActivity.kt:2302 (default `white`) | missing | absent | No subtitle colour (Phase 3) |
| subBg | media player | media/PlayerActivity.kt:2312 (default `none`) | missing | absent | No subtitle background (Phase 3) |
| subEdge | media player | media/PlayerActivity.kt:2319 (default `outline`) | missing | absent | No subtitle edge style (Phase 3) |
| ttsEngine | media/TTS | tts/TtsEngine.kt:414 (default `kokoro`) | missing | absent | No TTS engine choice (Phase 4) |
| ttsSpeed | media/TTS | tts/TtsEngine.kt:442 (default `1.0`) | missing | absent | No TTS speed (Phase 4) |
| readerTheme | reader | MainActivity.kt:495 (default `white`) | missing | absent | No reader (Phase 4) |
| readerFont | reader | ui/ReaderScreens.kt:806 (default `opensans`) | missing | absent | No reader font (Phase 4) |
| readerFontCustomName | reader | ui/ReaderScreens.kt:903 | missing | absent | No custom reader font (Phase 4) |
| readerFontSize | reader | ui/ReaderScreens.kt:783 (default `18`) | missing | absent | No reader font size (Phase 4) |
| readerFontWeight | reader | ui/ReaderScreens.kt:794 (default `normal`) | missing | absent | No reader font weight (Phase 4) |
| readerLetterSpacing | reader | ui/ReaderScreens.kt:792 (default `0`) | missing | absent | No reader letter spacing (Phase 4) |
| readerLineMult | reader | ui/ReaderScreens.kt:784 (default `1.5`) | missing | absent | No reader line spacing (Phase 4) |
| readerParaSpacing | reader | ui/ReaderScreens.kt:790 (default `0.6`) | missing | absent | No reader paragraph spacing (Phase 4) |
| readerPadding | reader | ui/ReaderScreens.kt:785 (default `20`) | missing | absent | No reader margins (Phase 4) |
| readerAlign | reader | ui/ReaderScreens.kt:809 (default `default`) | missing | absent | No reader text alignment (Phase 4) |
| readerSpread | reader | ui/ReaderScreens.kt:1087 (default `false`) | missing | absent | No two-page spread mode (Phase 4) |
| readerBrightness | reader | ui/ReaderScreens.kt:800 (default `100`) | missing | absent | No reader brightness (Phase 4) |
| readerKeepAwake | reader | ui/ReaderScreens.kt:820 (default `false`) | missing | absent | No keep-awake while reading (Phase 4) |
| ribbons:$bookId | reader | ui/ReaderScreens.kt:2498 | missing | absent | No reader ribbons/bookmarks-in-book (Phase 4) |
| articleScroll:$articleKey | reader | ui/ReaderScreens.kt:4096 | missing | absent | No saved article scroll position (Phase 4) |
| shelfLargeTiles | reader | ui/ReaderScreens.kt:339 (default `false`) | missing | absent | No large-tile library shelf (Phase 4) |
| mobiMigrated | reader | ui/ReaderScreens.kt:351 (default `false`) | missing | absent | No MOBI migration state (Phase 4) |
| showReader | reader | browser/BrowserChrome.kt:713 (default `true`) | missing | absent | No "Read this article" menu row (Phase 4) |
| vaultPinVerifier | vault | vault/VaultPin.kt:29 | missing | absent | No password vault at all (Phase 4) |
| vaultNeverSave | vault | vault/SaveLogin.kt:433 | missing | absent | No never-save-sites list for logins (Phase 4) |
| vaultSubdomain:$id | vault | browser/BrowserScreen.kt:1099 (default `false`) | missing | absent | No per-login subdomain matching (Phase 4) |
| formFillNeverSave | vault | vault/FormFill.kt:42 | missing | absent | No never-save list for form fill (Phase 4) |
| formFillOfferAddresses | vault | vault/FormFill.kt:47 | missing | absent | No address autofill offers (Phase 4) |
| formFillOfferCards | vault | vault/FormFill.kt:48 | missing | absent | No card autofill offers (Phase 4) |
| homePage | start page | browser/BrowserScreen.kt:258 | missing | absent | No custom home page URL; Pioneer start page is fixed internal home state |
| showBanner | start page | browser/StartPage.kt:148 (default `true`) | present | ui/StartPage.kt:112, ui/SettingsScreen.kt:216 | Same behaviour |
| showFavorites | start page | browser/StartPage.kt:892 (default `true`) | present | ui/StartPage.kt:156 | Same behaviour |
| showFrequent | start page | browser/StartPage.kt:941 (default `true`) | missing | absent | No Frequently-visited section toggle (Pioneer has separate Pioneer-only `startPageTiles` instead) |
| showContinue | start page | browser/StartPage.kt:787 (default `true`) | missing | absent | No Continue-reading section (Phase 3/4) |
| showHomeButton | start page | browser/BrowserScreen.kt:3762 (default `true`) | missing | absent | No home-button visibility toggle |
| frequentHidden | start page | browser/StartPage.kt:290 | present | ui/StartPage.kt:134 | Same behaviour (hidden frequent hosts) |
| startShortcuts | start page | emu/StartShortcuts.kt:18 | missing | absent | No start-page game shortcuts (games) |
| restoreTabs | tabs/session | browser/BrowserScreen.kt:265 (default `true`) | missing | absent | No restore-tabs toggle (Pioneer restores unconditionally; behaviour exists, switch does not) |
| openLinksInNewTab | tabs/session | browser/BrowserScreen.kt:2668 (default `false`) | missing | absent | No open-links-in-new-tab option |
| tabViewMode | tabs/session | browser/BrowserScreen.kt:4662 (default `grid`) | missing | absent | No tab-switcher grid/list choice |
| deleteOnExit | tabs/session | MainActivity.kt:137 (default `false`) | missing | absent | No delete-on-exit wipe (Pioneer has manual "Erase private data" instead) |
| onboarded | tabs/session | MainActivity.kt:361 (default `false`) | missing | absent | No onboarding-completed flag (Pioneer has its own first-run uBlock prompt instead) |
| forceZoom | web/view | browser/BrowserScreen.kt:2285 (default `false`) | missing | absent | No force-zoom (pinch-zoom override) |
| updateChannel | updates | browser/UpdateCheck.kt:94 (default `beta`) | missing | absent | No update channel choice (updater) |
| autoUpdateCheck | updates | browser/UpdateCheck.kt:65 (default `true`) | missing | absent | No automatic update checks (updater) |
| lastUpdateCheck | updates | browser/UpdateCheck.kt:60 (default `0`) | missing | absent | No last-check timestamp (updater) |
| menuHiddenRows | menu | browser/BrowserChrome.kt:325 | present | ui/BrowserChrome.kt:423 | Same behaviour |
| menuRowOrder | menu | browser/BrowserChrome.kt:408 | present | ui/BrowserChrome.kt:391 | Same behaviour |
| gamesLibrary | games | emu/GameLibrary.kt:39 | missing | absent | No game library (games) |
| gameFavorites | games | emu/GameLibrary.kt:205 | missing | absent | No game favourites (games) |
| gameConsoleOrderHandheld | games | emu/GameOrder.kt:20 | missing | absent | No handheld console ordering (games) |
| gameConsoleOrderHome | games | emu/GameOrder.kt:21 | missing | absent | No home console ordering (games) |
| gamesView | games | ui/GamesScreen.kt:1220 (default `grid`) | missing | absent | No games grid/list view (games) |
| lastRomPath | games | emu/GameLibrary.kt:259 | missing | absent | No last-ROM memory (games) |
| lastRomName | games | emu/GameLibrary.kt:279 | missing | absent | No last-ROM name (games) |
| portraitLayoutVersion | games | emu/GameLayout.kt:40 | missing | absent | No portrait control-layout versioning (games) |
| gameBackHintShown | games | emu/GameActivity.kt:751 (default `false`) | missing | absent | No back-hint shown state (games) |
| gameControlsAlpha | games | emu/GameActivity.kt:379 (default `100`) | missing | absent | No control transparency (games) |
| gameHaptics | games | emu/GameActivity.kt:709 (default `Light`) | missing | absent | No game haptics strength (games) |
| gameShaders | games | emu/GameActivity.kt:316 (default `false`) | missing | absent | No shader toggle (games) |
| gameCurvature | games | ui/GamesScreen.kt:1932 (default `false`) | missing | absent | No screen-curvature toggle (games) |
| gameCurvatureAmount | games | ui/GamesScreen.kt:1938 (default `30`) | missing | absent | No curvature amount (games) |
| gameCurveOn:${game.id} | games | ui/GamesScreen.kt:3897 | missing | absent | No per-game curvature override (games) |
| gameCurveAmount:${game.id} | games | ui/GamesScreen.kt:3898 | missing | absent | No per-game curvature amount (games) |
| gameRoundOn:${game.id} | games | ui/GamesScreen.kt:3899 | missing | absent | No per-game rounded-corners toggle (games) |
| gameRoundAmount:${game.id} | games | ui/GamesScreen.kt:3900 (per-console variant emu/ShaderPresets.kt:177) | missing | absent | No per-game/per-console corner-rounding amount (games) |
| gameRoundCorners:$console | games | emu/ShaderPresets.kt:168 | missing | absent | No per-console rounded-corners toggle (games) |
| gameShaderPreset:${game.id} | games | ui/GamesScreen.kt:3776 | missing | absent | No per-game shader preset (games) |
| gameFastForward:$consoleKey | games | emu/GameActivity.kt:416 (default `false`) | missing | absent | No fast-forward toggle (games) |
| gameFastForwardSpeed:$consoleKey | games | emu/GameActivity.kt:424 (default `4`) | missing | absent | No fast-forward speed (games) |
| gameMuted:$consoleKey | games | emu/GameActivity.kt:435 (default `false`) | missing | absent | No per-console mute (games) |
| gameVolume:$consoleKey | games | emu/GameActivity.kt:441 (default `100`) | missing | absent | No per-console volume (games) |
| gameFpsCounter:$consoleKey | games | emu/GameActivity.kt:407 (default `false`) | missing | absent | No FPS counter (games) |
| gameIntegerScale:$consoleKey | games | emu/GameActivity.kt:458 (default `false`) | missing | absent | No integer scaling (games) |
| gameLayout:$console:$orient | games | emu/GameLayout.kt:40 | missing | absent | No saved touch-control layouts (games) |
| gamePs1Controller | games | emu/GameLayout.kt:45 (default `Dual`) | missing | absent | No PS1 controller variant (games) |
| gameScreens:ds:$orient | games | emu/DsLayout.kt:87 | missing | absent | No DS screen arrangement (games) |
| gameDsSwap:ds:$orient | games | emu/DsLayout.kt:90 | missing | absent | No DS screen swap (games) |
| gameDsPipCorner:ds | games | emu/DsLayout.kt:92 | missing | absent | No DS picture-in-picture corner (games) |
| gameDsUpperTap:ds | games | emu/DsLayout.kt:93 | missing | absent | No DS upper-screen tap mode (games) |
| gameDsLid:ds | games | emu/DsLayout.kt:94 | missing | absent | No DS lid state (games) |
| gameDsMic:ds | games | emu/DsLayout.kt:95 | missing | absent | No DS microphone mode (games) |
| gameDsForceStacked:ds | games | emu/DsLayout.kt:96 | missing | absent | No DS forced-stacked layout (games) |
| coreOpt:$console:$key | games | emu/CoreOptions.kt:965 | missing | absent | No per-core emulator options (games) |
| coreError:$system | games | emu/CoreDownloadManager.kt:200 | missing | absent | No core-download error state (games) |

**Keys counts: 140 total — 7 present, 3 differs, 130 missing.** Pioneer-only keys (no Mariner counterpart, excluded above): `trackingProtection` (ui/SettingsScreen.kt:237, default `strict`), `uBlockPrompted` (MainActivity.kt:97), `startPageTiles` (ui/SettingsScreen.kt:206). Cast has no Mariner setting key; Pioneer's cast is probe-only.

**5 most user-visible missing keys:** `searchEngine` (search locked to DuckDuckGo) · `barPosition` (bar fixed at top) · `homePage` (no custom home page) · `downloadLocation` (stands in for the whole downloads area, Phase 3) · `vaultPinVerifier` (stands in for the whole vault, Phase 4).

## §4 — Settings-screen rows: full table (114 rows)

Counting rule: one row per visually distinct control/group; a mutually-exclusive option set is one row; per-host/per-rule/per-day repeating rows count as one template row each. M = Mariner `ui/SettingsScreen.kt`, P = Pioneer `ui/SettingsScreen.kt` (393 lines, 17 rows total: Appearance 5, Search 1, Privacy 5, Bookmarks 2, Extensions 1, About 3).

| section | row | Mariner ref | Pioneer | Pioneer ref | effect if missing/different |
|---|---|---|---|---|---|
| Hub — Settings | Search settings field | M:776 | missing | absent | No way to find a setting by name |
| Hub — Settings | Appearance (hub row) | M:807 | differs | P:182 (inline section) | No hub navigation; theme section exists inline but System/Light/Dark only — no Material You rows |
| Hub — Settings | Browsing (hub row) | M:813 | missing | absent | No Browsing page at all |
| Hub — Settings | Search (hub row) | M:819 | differs | P:224 (Search section) | Section exists but engine fixed DuckDuckGo; no engine choice |
| Hub — Settings | Privacy & blocking (hub row) | M:825 | differs | P:235 (Privacy section) | Only tracking-protection levels + Clear history + Erase; no filter lists, cookies, HTTPS-only, etc. |
| Hub — Settings | Video player (hub row) | M:831 | missing | absent | No video-player settings page (Phase 3) |
| Hub — Settings | Data (hub row) | M:837 | differs | P:293, P:279/286 | Reduced to bookmark HTML import/export + history clear/erase; no backup, storage, or download settings |
| Hub — Settings | Form fill (hub row) | M:843 | missing | absent | No form-fill / vault settings (Phase 4) |
| Hub — Settings | User scripts (hub row) | M:849 | missing | absent | No user-script support |
| Hub — Settings | Favorites (hub row) | M:855 | missing | absent | No Favorites settings page |
| Hub — Settings | Reading list (hub row) | M:861 | missing | absent | No Reading-list settings page (Phase 4) |
| Hub — Settings | Accessibility (hub row) | M:867 | missing | absent | No Accessibility page |
| Hub — Start page | Start page options (wallpaper, banner, sections, homepage) | M:1100 | differs | P:204 (tiles), P:214 (banner) | Only tiles + banner toggles inline; no wallpaper, sections, or homepage config |
| Hub — Per-site controls | Per-site controls (hub row, opens panel) | M:1563 | missing | absent | No per-site settings at all |
| Per-site controls (panel) | Search sites | M:392 | missing | absent | No per-site panel to search |
| Per-site controls (panel) | JavaScript (per-site toggle) | M:442 | missing | absent | Cannot enable/disable JS per site |
| Per-site controls (panel) | Images (per-site toggle) | M:450 | missing | absent | Cannot block images per site |
| Per-site controls (panel) | Popups (per-site, Blocked / Allowed / Always denied) | M:478 | missing | absent | No per-site popup control |
| Per-site controls (panel) | Ad blocking (per-site toggle) | M:498 | missing | absent | Cannot disable blocking per site (Phase 3) |
| Per-site controls (panel) | Third-party cookies (per-site toggle) | M:506 | missing | absent | No per-site cookie exception |
| Per-site controls (panel) | Allow plain HTTP (per-site; only while HTTPS-only is on) | M:519 | missing | absent | No HTTPS-only exceptions (and no HTTPS-only mode) |
| Per-site controls (panel) | Text zoom (per-site, − / Default / + / Reset, 50–200%) | M:537 | missing | absent | No per-site text zoom |
| Per-site controls (panel) | Desktop site (per-site, On / Off / Default) | M:589 | missing | absent | No per-site desktop mode |
| Per-site controls (panel) | Autoplay (per-site, Blocked / Muted only / Allowed / Default) | M:630 | missing | absent | No per-site autoplay control |
| Per-site controls (panel) | Camera / Microphone / Location (per-site, Ask / Allowed / Denied) | M:648 | missing | absent | No per-site permission decisions (Phase 3) |
| Per-site controls (panel) | Clear data for this site | M:724 | missing | absent | Cannot clear one site's cookies/storage |
| Per-site controls (panel) | Show more sites (paginates host list +60) | M:733 | missing | absent | No per-site panel to paginate |
| Appearance | Theme: System / Light / Dark / Material You (+ Follow system / Light / Dark variants) | M:878-903 | differs | P:186, P:192, P:198 | System/Light/Dark exist; no Material You / dynamic-palette rows (values honoured if carried over) |
| Appearance | Address bar position: Bottom / Top | M:929, M:933 | missing | absent | Address bar position not configurable (§6) |
| Appearance | Darken web pages: Auto / On / Off | M:945-951 | missing | absent | No web-page darkening |
| Appearance | Web text size slider (50–200%) | M:972 | missing | absent | No global web text size |
| Appearance | Interface font: Default / Open Sans / Roboto / Inter / Custom | M:991 | missing | absent | No interface font choice |
| Appearance | Custom font: Replace font file | M:1036 | missing | absent | Cannot load a custom interface font |
| Appearance | Custom font: Remove | M:1043 | missing | absent | Cannot remove a custom interface font |
| Appearance | Interface text size: Compact / Default / Large / Extra large | M:1056 | missing | absent | No interface text scaling |
| Browsing | Immersive full screen | M:1117 | missing | absent | No immersive full-screen toggle |
| Browsing | Restore tabs on launch | M:1123 | missing | absent | Tab restore not configurable |
| Browsing | Open links in a new tab | M:1129 | missing | absent | Link-open behaviour not configurable |
| Search | Search engine: DuckDuckGo (No AI) / Startpage / Google / Bing / Custom | M:1140-1155 | differs | P:227 | Engine fixed to DuckDuckGo; cannot pick another or Custom |
| Search | Custom search URL field + Save search URL | M:1163, M:1185 | missing | absent | No custom search engine |
| Privacy — Blocking | Block ads (filter lists) | M:1194 | missing | absent | No built-in filter-list ad-blocking toggle (nearest cover is Pioneer's uBlock row — different mechanism) (Phase 3) |
| Privacy — Blocking | Block trackers | M:1200 | differs | P:249, P:259, P:269 | No on/off toggle; 3-level Tracking protection instead, applies at once + reloads tab |
| Privacy — Blocking | Filter-list status + Update now | M:1221 | missing | absent | No filter-list status or manual update (Phase 3) |
| Privacy — Blocking | Filter lists: EasyList / EasyPrivacy / StevenBlack hosts / AdGuard Tracking Protection / AdGuard Mobile Ads / uBlock Origin filters / AdGuard Base (per-list toggles) | M:1230 | missing | absent | No per-list control (Phase 3) |
| Privacy — Blocking | Custom list URL + Fetch custom list | M:1276, M:1288 | missing | absent | Cannot add a custom filter list (Phase 3) |
| Privacy — Blocking | Custom filter rules (one per line) + Save rules | M:1296, M:1324 | missing | absent | Cannot add custom filter rules (Phase 3) |
| Privacy — Blocking | Blocked this session → Blocked sub-screen | M:1339 | missing | absent | No blocked-count detail screen (Phase 3) |
| Privacy — Blocking | Blocked elements → Manage dialog | M:1361 | missing | absent | No element-picker rule management (Phase 3) |
| Privacy — Blocking | Strict popup blocking | M:1380 | missing | absent | No strict popup blocking toggle |
| Privacy — Cookies & storage | Accept cookies | M:1395 | missing | absent | Cookie acceptance not configurable |
| Privacy — Cookies & storage | Block third-party cookies | M:1404 | missing | absent | No third-party-cookie blocking |
| Privacy — Cookies & storage | Session-only cookies | M:1417 | missing | absent | Cookies cannot be made session-only |
| Privacy — Cookies & storage | DOM storage | M:1429 | missing | absent | DOM storage cannot be disabled |
| Privacy — Connection | HTTPS-only mode | M:1443 | missing | absent | No HTTPS-only mode |
| Privacy — Connection | Autoplay (global: Allowed / Blocked / Muted only) | M:1460 | missing | absent | No global autoplay control |
| Privacy — Connection | Send Global Privacy Control | M:1491 | missing | absent | No GPC header control |
| Privacy — Connection | Referer policy (Default / Origin only / Never) | M:1508 | missing | absent | No referer-policy control |
| Data — Storage | Storage sizes (per-area + Total) | M:2998 | missing | absent | No storage breakdown shown |
| Data — Storage | Clear cache (in StorageSection) | M:3052 | missing | absent | No cache clearing |
| Data — Downloads | Download location: Downloads folder / App folder (private) | M:1591, M:1595 | missing | absent | Download location not configurable (Phase 3) |
| Data — Downloads | Downloaded EPUBs go to bookshelf | M:1599 | missing | absent | No EPUB-to-bookshelf option (Phase 3/4) |
| Data — Downloads | Use segmented downloads | M:1604 | missing | absent | No segmented-download option (Phase 3) |
| Data — Downloads | Ask before downloading | M:1609 | missing | absent | No download confirmation option (Phase 3) |
| Data — Downloads | Wi-Fi only downloads | M:1614 | missing | absent | No Wi-Fi-only download option (Phase 3) |
| Data — Backup/import | Export backup (no vault) | M:1632 | missing | absent | No JSON backup export (Pioneer exports bookmarks HTML only) |
| Data — Backup/import | Import backup | M:1637 | missing | absent | No backup import |
| Data — Backup/import | Import bookmarks (HTML) | M:1642 | present | P:296 | Functionally identical; same Netscape-format HTML |
| Data — Backup/import | Export encrypted backup | M:1647 | missing | absent | No encrypted backup export |
| Data — Backup/import | Export vault… (opens Vault screen) | M:1652 | missing | absent | No vault export (Phase 4) |
| Data — Clear browsing data | Time range: Last hour / Last 24 hours / Last 7 days / All time | M:1676-1679 | missing | absent | Clearing is all-time only; no time range |
| Data — Clear browsing data | History toggle (with in-range count) | M:1688 | differs | P:279 | Pioneer "Clear history" clears all history with confirm; no range, no count, no per-type toggle |
| Data — Clear browsing data | Cookies toggle | M:1695 | missing | absent | Cookies cannot be cleared from Settings |
| Data — Clear browsing data | Cache toggle | M:1700 | missing | absent | Cache cannot be cleared |
| Data — Clear browsing data | Site data toggle | M:1705 | missing | absent | Site data cannot be cleared |
| Data — Clear browsing data | Clear browsing data button | M:1740 | differs | P:279, P:286 | Split into two narrower actions (Clear history; Erase private data); no ranged, per-type clear; no cookies/cache/site-data clear |
| Data — Clear browsing data | Delete browsing data on exit | M:1748 | missing | absent | No delete-on-exit |
| Data — Danger zone | Erase everything on this device | M:1774 | differs | P:286 | Pioneer "Erase private data" clears only history + saved tab session, keeps bookmarks/favourites; Mariner also deletes bookmarks, playlists, downloads list, vault entries, site settings, favourites, cookies, DOM storage and cache, and closes private tabs |
| Data — Diagnostic trace | Start trace / End trace | M:1791, M:1796 | missing | absent | No diagnostic trace |
| Data — Diagnostic trace | Export as .txt | M:1803 | missing | absent | No trace export |
| Form fill | Offer to save addresses | M:1812 | missing | absent | No address save prompts (Phase 4) |
| Form fill | Offer to save cards | M:1818 | missing | absent | No card save prompts (Phase 4) |
| Form fill | Manage addresses and cards (opens Vault) | M:1839 | missing | absent | No vault / address-and-card management (Phase 4) |
| Form fill | Never save addresses or cards for these sites (per-host Remove) | M:1860 | missing | absent | No never-save site list (Phase 4) |
| Video player | Skip interval: 5s–5min | M:3071 | missing | absent | No video-player settings at all (Phase 3) |
| Video player | Subtitle font (+ Import font file) | M:3367, M:3391 | missing | absent | No subtitle font choice (Phase 3) |
| Video player | Subtitle size: 75% / 100% / 125% / 150% / 200% | M:3401 | missing | absent | No subtitle size choice (Phase 3) |
| Video player | Subtitle colour | M:3412 | missing | absent | No subtitle colour choice (Phase 3) |
| Video player | Subtitle background | M:3431 | missing | absent | No subtitle background choice (Phase 3) |
| Video player | Subtitle edge: Outline / Drop shadow / None | M:3446 | missing | absent | No subtitle edge choice (Phase 3) |
| User scripts | Per-script row (name + pattern, enable switch, Edit, Delete) | M:3140 | missing | absent | No user scripts |
| User scripts | Add a script | M:3179 | missing | absent | Cannot add a user script |
| User scripts | Script editor: Name / Run on (match pattern) / JavaScript + Save | M:3210-3231 | missing | absent | No script editor |
| Accessibility | Reduced motion | M:1905 | missing | absent | No reduced-motion toggle |
| Accessibility | Force zoom | M:1928 | missing | absent | Pages that disable scaling cannot be forced zoomable |
| Accessibility | System caption settings (opens Android captioning settings) | M:1956 | missing | absent | No link to Android caption settings |
| Accessibility | Reader (show "Read this article" row in menu) | M:1974 | missing | absent | No reader toggle (Phase 4) |
| Hub — Updates | Check for updates | M:1997 | missing | absent | No in-app update check (updater) |
| Hub — Updates | Update channel: Stable / Beta | M:2019 | missing | absent | No update-channel choice (updater) |
| Hub — Updates | Automatic update checks | M:2032 | missing | absent | No automatic update checks (updater) |
| Hub — About | Version / Package / Local-only text | M:2046-2048 | differs | P:335 | Pioneer shows version name + code only; no "(beta)", no package name, no local-only privacy line |
| Hub — About | Set as default browser | M:2081 | missing | absent | Cannot request default-browser role from Settings |
| Hub — About | See the welcome guide again | M:2083 | missing | absent | Cannot replay the welcome guide |
| Hub — About | Third-party notices | M:2086 | differs | P:347 | Pioneer "Licences" opens a Licences screen; Mariner opens an in-Settings notices dialog |
| Hub — Session | Renew (clear cookies, storage, cache, history; close every tab) | M:2111 | missing | absent | No Renew; nearest Pioneer action (Erase private data) clears history + tab session only |
| Blocked sub-screen | Blocked this session: Ads / Trackers / Other / Total (counts) | M:2578-2584 | missing | absent | No blocked-count display (Phase 3) |
| Blocked sub-screen | Blocked history (per-day rows) | M:2593 | missing | absent | No blocked history (Phase 3) |
| Blocked-elements dialog | Per-rule row + Delete (network + cosmetic rules) | M:2189, M:2212 | missing | absent | Element-picker rules cannot be reviewed/deleted (Phase 3) |
| Blocked-elements dialog | Export block list | M:2231 | missing | absent | Cannot export block rules (Phase 3) |
| Blocked-elements dialog | Import block list (paste rules; unsafe cosmetic rules dropped) | M:2236 | missing | absent | Cannot import block rules (Phase 3) |
| Blocked-elements dialog | Clear all (blocked elements) | M:2248 | missing | absent | Cannot clear all block rules at once (Phase 3) |

**Rows counts: 114 total — 1 present, 13 differs, 100 missing.** Pioneer-only rows (no Mariner equivalent): Start page tiles (P:204), Start page banner (P:214), Tracking protection Strict/Basic/Off (P:249/259/269), Export bookmarks HTML (P:304), uBlock Origin Install / "Check again" with no Remove (P:316), GeckoView version (P:341). Favorites/Reading-list sub-screen internals are in other files and not counted here, only their hub rows.

## §5 — Features/behaviours: full table (115)

M = Mariner (`net/notscape/browser/`), P = Pioneer (`net/nspioneer/browser/`). Missing tagged **later phase** (Phase 3 = media/downloads/player/fullscreen/permissions; Phase 4 = reader/TTS/vault/cast; games; updater) or **missing now** (Phase 2 scope).

### Tabs
| feature | Mariner ref | Pioneer | Pioneer ref | effect |
|---|---|---|---|---|
| New tab (opens start page) | BrowserScreen.kt:1478 | present | MainActivity.kt:306-317; ui/BrowserScreen.kt:393-397 | — |
| Duplicate tab | BrowserScreen.kt:1560 | present | MainActivity.kt:345-348; ui/BrowserScreen.kt:414-417 | — |
| Close tab | BrowserScreen.kt:1501 | present | MainActivity.kt:330-342; ui/BrowserScreen.kt:388-390 | — |
| Close other tabs | BrowserScreen.kt:1588 | present | MainActivity.kt:350-360; ui/BrowserScreen.kt:421-424 | — |
| Close duplicate tabs | BrowserScreen.kt:1575, 4646-4656 | missing now | absent | No way to collapse repeated URLs |
| Close all (per class) | BrowserScreen.kt:1596, 4657-4660 | missing now | absent | No one-tap clear of all normal/private tabs |
| Restore tabs on relaunch | BrowserScreen.kt:271-290, 1626-1663 | differs | MainActivity.kt:375-398 | Restores by URL only; desktop mode hardcoded `false`, scroll/session state lost — every tab reloads fresh, desktop sites revert to mobile |
| Tab grid (thumbnails, grid/list, search, drag-reorder) | BrowserScreen.kt:363-401, 4626-4632, 4662-4675, 4862-4879 | differs | ui/BrowserScreen.kt:331-352 | Title+URL rows only, no thumbnails, no search, no grid/list toggle, no reorder |
| Tab count | BrowserScreen.kt:3953-3955 | differs | ui/BrowserScreen.kt:238-241 | Count is total `tabs.size`, mixing private + normal; Mariner counts per privacy class |
| Recently closed | BrowserScreen.kt:403, 4531-4571; TabList.kt:49-65; Store.kt:1250-1278 | missing now | absent | A closed tab is gone for good; no reopen list |
| Close-tab Undo snackbar | BrowserScreen.kt:1501-1518, 5047-5056 | missing now | absent | Accidental close cannot be undone |
| Private tabs: construction | PrivateProfile.kt:19; BrowserScreen.kt:2259 | present | engine/GeckoEngine.kt:121-124 (`usePrivateMode(true)`) | — |
| Private tabs: mixed vs separate list | BrowserScreen.kt:361, 4617 | differs | ui/BrowserScreen.kt:340-352 | One mixed sheet, lock icon only; Mariner keeps two separate grids |
| Private lock marker | BrowserScreen.kt:3792, 4840 | present | ui/BrowserScreen.kt:252, 372-377 | — |
| Private tabs: no history | BrowserScreen.kt:2953 | present | MainActivity.kt:296-298 | — |
| Private tabs: no persist + wipe on last close | BrowserScreen.kt:1530-1534; PrivateProfile.kt:39-41 | differs | MainActivity.kt:375-377; engine/GeckoEngine.kt:140-147 | Not persisted, but no explicit profile wipe on last private close — rests on Gecko session close alone |

### Navigation
| feature | Mariner ref | Pioneer | Pioneer ref | effect |
|---|---|---|---|---|
| Back | BrowserScreen.kt:3687, 3366 | present | ui/BrowserScreen.kt:192-204; engine/Engine.kt:23 | — |
| Forward | BrowserScreen.kt:3695-3707 | differs | engine/Engine.kt:16, 24; engine/GeckoEngine.kt:498 (engine only) | `goForward` exists but no forward button in the UI |
| Long-press Back/Forward history sheet | BrowserScreen.kt:408-411, 3682-3703 | missing now | absent | Cannot jump several steps back/forward |
| Reload | BrowserChrome.kt:651-655 | present | ui/BrowserChrome.kt:605-609 (menu row only) | — |
| Stop loading | BrowserScreen.kt:3916-3929 | differs | engine/Engine.kt:22; engine/GeckoEngine.kt:496 (engine only) | `stop()` exists but has no UI caller |
| Home button (+ custom homePage, long-press = home in new tab) | BrowserScreen.kt:3762-3788, 256-258 | missing now | absent | No Home button at all; no custom home URL setting |
| Pull-to-refresh | BrowserScreen.kt:2200-2230 | missing now | absent | Pulling down on a page does nothing |
| Scroll-hide address bar | BrowserScreen.kt:2604-2607, 4381-4387 | missing now | absent | Bar always visible, eating vertical space while scrolling |
| Swipe-on-pill tab switching | BrowserScreen.kt:3713-3730 | missing now | absent | No horizontal swipe on the address pill to change tabs |

### Address bar
| feature | Mariner ref | Pioneer | Pioneer ref | effect |
|---|---|---|---|---|
| Position Top/Bottom (default bottom) | BrowserScreen.kt:3512 | differs | ui/BrowserScreen.kt:236-275 (fixed top); :302, 315 (`barAtBottom = false` hardcoded) | Bar always at top; Bottom setting does not exist (§6) |
| Typing, Go, bare-domain → https://, else search | BrowserScreen.kt:3853-3856 | present | ui/BrowserScreen.kt:91-99, 168-173 | — |
| Suggestions (bookmarks/history only) | BrowserChrome.kt:249-283 | present | ui/BrowserScreen.kt:208-231 (capped at 6) | — |
| Mic / voice input | BrowserScreen.kt:3870-3899 (`RecognizerIntent`) | missing now | absent | No voice search in the address bar |
| Clear-X | BrowserScreen.kt:3900-3907 | present | ui/BrowserScreen.kt:256-265 | — |
| Cancel while typing | BrowserScreen.kt:3910-3911 | differs | ui/BrowserScreen.kt:201 (back handler cancels editing) | No on-screen Cancel button; only system Back exits typing |
| Site-info / lock icon | BrowserScreen.kt:3799-3812, 5090-5095 | missing now | absent | No site security info or per-site details from the bar |
| Long-press link menu | BrowserScreen.kt:5670-5721 (8 rows) | differs | ui/BrowserScreen.kt:449-502 (5 rows); engine/GeckoEngine.kt:399-410 | Only Open new/background/private tab, Copy link, Share — no View image, Download, Block this element |
| Long-press tab-count button = new tab | BrowserScreen.kt:3939-3943 | missing now | absent | Tab-count button only opens the switcher |

### Find, zoom, desktop site, reader, TTS
| feature | Mariner ref | Pioneer | Pioneer ref | effect |
|---|---|---|---|---|
| Find in page | BrowserChrome.kt:111-236; BrowserScreen.kt:916 | present | engine/Engine.kt:25-27; engine/GeckoEngine.kt:503-520 | — |
| Find ordinal / count | BrowserScreen.kt:916 | differs | engine/GeckoEngine.kt:535-546; ui/BrowserChrome.kt:190-205 | Unknown total (`-1`) renders as "0 / 0" even when matches exist |
| Per-site zoom (text-size steps) | Store.kt:1112-1115; BrowserScreen.kt:1695-1700 | differs | ui/BrowserChrome.kt:557 (`ZOOM_STEPS` 0.5–2.0); engine/GeckoEngine.kt:552-566 | Zoom via probe content script (font-size scaling), not native text zoom; no global default, no force-zoom for scaling-disabled pages |
| Desktop site (UA + viewport) | BrowserChrome.kt:859-862 | present | ui/BrowserChrome.kt:657-670; engine/GeckoEngine.kt:567-580 | (Pioneer's header comment saying Desktop is omitted is stale — the row is implemented) |
| Desktop mode persisted on relaunch | BrowserScreen.kt:276, 1563 | differs | MainActivity.kt:375 (`desktopMode = false` hardcoded in snapshot) | Desktop choice stored per site but dropped from saved session; resets on relaunch |
| Reader view | BrowserChrome.kt:716-729; ArticleExtractor.kt:33 | missing — Phase 4 | absent | No reader mode |
| Read later / reading list | BrowserChrome.kt:698-709, 903 | missing — Phase 4 | absent | No save-for-later list |
| TTS / read-aloud | tts/TtsEngine.kt:35-42; ReaderScreens.kt:2591-2731 | missing — Phase 4 | absent | No listen/read-aloud, voices, or sleep timer |

### History, bookmarks, favourites, start page
| feature | Mariner ref | Pioneer | Pioneer ref | effect |
|---|---|---|---|---|
| History recording | BrowserScreen.kt:2953 | present | MainActivity.kt:296-298 | — |
| History screen: clear, per-entry delete, clear-older-than | LibraryScreens.kt:844-889; Store.kt:537-563 | present | ui/LibraryScreens.kt:841-1031; data/PioneerStore.kt:407-424 | — |
| Bookmarks add / remove / edit | Store.kt:377-407 | present | data/PioneerStore.kt:219-282 | — |
| Bookmark folders (create/rename/delete/move) | Store.kt:94-153, 455-512 | present | data/PioneerStore.kt:65-126, 316-377 | — |
| Bookmark HTML import / export | BookmarkHtml.kt:10 | present | data/BookmarkHtml.kt:25-; data/PioneerStore.kt:526-532 | — |
| Favourites add / remove + screen | BrowserChrome.kt:688-696; LibraryScreens.kt:2374 | present | data/PioneerStore.kt:435-446; ui/LibraryScreens.kt:1054 | — |
| Start page banner | StartPage.kt:148 (`showBanner`) | present | ui/StartPage.kt:108, ~180-210 | — |
| Start page favourites row | StartPage.kt:90 | present | ui/StartPage.kt:216- (4-column grid) | — |
| Most-visited / Frequently visited tiles | StartPage.kt:293-309, 941-943 | present | ui/StartPage.kt:140-175, ~260- | — |
| Bookmarks quick access on start page | StartPage.kt:90 | present | ui/StartPage.kt:106 | Pioneer-only addition (Mariner has no bookmarks section on its start page) |
| Continue watching / Continue reading | StartPage.kt:272, 787-830 | missing — Phase 3 media / Phase 4 reader | absent | No resume rows for half-watched media or half-read articles |
| Shortcuts pins (launcher / game / console pins) | StartPage.kt:555-573; emu/StartShortcuts.kt | missing now | absent | Start page has no pinnable shortcut row |
| Custom wallpaper photo | StartPage.kt:147-216 (`wallpaper/custom.jpg`) | missing now | ui/StartPage.kt:118-126 (theme colours only) | Wallpaper is light/dark colour only; custom photo cannot be set |
| Update row ("Update available") | StartPage.kt:226; BrowserScreen.kt:4600-4608 | missing — updater | absent | No update notice on start page or tab grid |

### Downloads, media, player
| feature | Mariner ref | Pioneer | Pioneer ref | effect |
|---|---|---|---|---|
| Downloads stack (listener, queue, pause/resume/retry) | BrowserScreen.kt:2578, 597; media/Downloads.kt:317, 682-727 | missing — Phase 3 | absent | Nothing downloads; downloadable links have no handler |
| Media detection / finder | BrowserScreen.kt:2139 (`runMediaScan`) | missing — Phase 3 | absent (probe `mediaLines` is diagnostics only, engine/GeckoEngine.kt:282) | No "Media on this page" scan |
| Pop-up player (floating video) | BrowserScreen.kt:2157-2165; VideoPopup.kt:103-127 | missing — Phase 3 | absent | No floating video window |
| Fullscreen video | BrowserScreen.kt:3270-3286 (`onShowCustomView`); MainActivity.kt:860 | differs | engine/GeckoEngine.kt:390-391 (logs `fullscreen ON/OFF` only) | Fullscreen is only a status line; no fullscreen surface shown |
| Picture-in-picture | MainActivity.kt:795-807; BrowserScreen.kt:6366 | missing — Phase 3 | absent | Leaving the app during video does not drop into PiP |
| Playlists | LibraryScreens.kt:1077; MainActivity.kt:538 | missing — Phase 3 | absent | No playlists |
| Offline library (saved pages) | LibraryScreens.kt:1261; BrowserChrome.kt:911-912 | missing — Phase 3 | absent | No saved-offline pages |
| Downloads screen | LibraryScreens.kt:1677; MainActivity.kt:574 | missing — Phase 3 | absent | No downloads list |
| PlayerActivity / background playback | media/PlayerActivity.kt (2351 lines); media/PlaybackService.kt:68 | missing — Phase 3 | absent | No in-app player or background audio |
| Download progress pill | BrowserScreen.kt:3526 | missing — Phase 3 | absent | No in-progress download indicator |

### Vault
| feature | Mariner ref | Pioneer | Pioneer ref | effect |
|---|---|---|---|---|
| Save-login prompt / capture | BrowserScreen.kt:342, 1828-1843 | missing — Phase 4 | absent | Logins never offered to be saved |
| Fill password from vault | BrowserChrome.kt:923-925 | missing — Phase 4 | absent | No password fill on login pages |
| Fill card / address (FormFill) | BrowserScreen.kt:346-349, 1193-1263; vault/FormFill.kt | missing — Phase 4 | absent | No card/address form filling |
| Biometric gate | BrowserScreen.kt:1391-1402, 1892-1903 | missing — Phase 4 | absent | No biometric unlock for vault actions |
| Never-save list | BrowserScreen.kt:1833-1834 | missing — Phase 4 | absent | No per-site "never save" list |
| Vault screens (manage, PIN, backup/CSV, generator) | ui/VaultScreen.kt:126; vault/VaultPin.kt, VaultCsvImport.kt, PasswordGenerator.kt | missing — Phase 4 | absent | No vault UI at all |
| System autofill service | vault/NotscapeAutofillService.kt; vault/VaultAutofillActivity.kt | missing — Phase 4 | absent | Pioneer cannot act as the system autofill provider |

### Cast
| feature | Mariner ref | Pioneer | Pioneer ref | effect |
|---|---|---|---|---|
| Cast discovery (routes listed) | media/CastHelper.kt:28; BrowserScreen.kt:406 | present | engine/GeckoEngine.kt:242-270; engine/Engine.kt:77; ui/BrowserScreen.kt:506-522 (Diagnostics) | Discovery works, but only as a diagnostics text line |
| Cast session launch | media/CastHelper.kt:80; BrowserScreen.kt:6380-6382 | missing — Phase 4 | PioneerCastOptionsProvider.kt:9-11 ("never launches a Cast session") | Cannot cast anything |
| Route chooser / Cast button | BrowserScreen.kt:6534-6535; media/CastHelper.kt:71-72 | missing — Phase 4 | absent | No Cast button or route picker in the UI |
| Cast detected media (cast pick) | BrowserScreen.kt:405, 6380 | missing — Phase 4 | absent | No cast option on detected media |

### Permissions, file chooser, popups, HTTPS, share
| feature | Mariner ref | Pioneer | Pioneer ref | effect |
|---|---|---|---|---|
| Camera / mic permission prompt (per-site allow/deny/remember) | BrowserScreen.kt:3078-3130, 5864-5873 | differs — Phase 3 | engine/GeckoEngine.kt:423-450 (deny outright; only media-key/autoplay/storage allowed) | Sites asking for camera/mic always fail silently; no prompt, no remembered choice |
| Location (geolocation) prompt | BrowserScreen.kt:3143-3159 | differs — Phase 3 | engine/GeckoEngine.kt:423-450 (no geolocation delegate; denied) | Location-aware sites always fail; no prompt |
| File chooser (`<input type=file>`) | BrowserScreen.kt:3175-3195 | missing — Phase 3 | absent | File uploads cannot be picked at all |
| Popup blocking (per-site allow/block) | BrowserScreen.kt:2715 (`blockPopups`) | differs | data/PioneerStore.kt:51-54 (`SiteSetting` has `allowPopups`/`blockPopups`, but no UI or engine enforcement) | Popup behaviour is the Gecko default; stored per-site fields are dead |
| HTTPS-only interstitial | BrowserScreen.kt:1003-1011, 5062-5088 | missing now | absent | No warning before loading a plain-http page when HTTPS-only is on (no such setting at all) |
| Plain-HTTP fallback (remembered per-site exception) | BrowserScreen.kt:5078-5082 (`setHttpsException`) | missing now | absent | No "Continue for this site" flow or exception list |
| Open in other browser | BrowserChrome.kt:669-676 | present | ui/BrowserChrome.kt:623-631 | — |
| Share page | BrowserChrome.kt:657-659 | present | ui/BrowserScreen.kt:179-187; ui/BrowserChrome.kt:610-613 | — |
| Copy URL | BrowserChrome.kt:661-667 | present | ui/BrowserChrome.kt:615-621 | — |
| Per-site controls screen (JS, images, cookies, permissions per host) | ui/SettingsScreen.kt:374-568 | missing now | absent (data model only: data/PioneerStore.kt:51-58, 451-454) | Per-site settings exist in the store but are unreachable — no UI to change them |

### Blocking
| feature | Mariner ref | Pioneer | Pioneer ref | effect |
|---|---|---|---|---|
| uBlock Origin install (prompt + version state) | absent in Mariner (own lists instead) | present | MainActivity.kt:94-98, 130-135; engine/Extensions.kt:165; ui/SettingsScreen.kt:~320 | Pioneer-only capability; install is a real dialog, never auto-approved |
| Mariner's own filter lists (FilterEngine) | WebTools.kt:22; MainActivity.kt:101-102, 176 | differs | absent (replaced by design) | Mariner's lists, rule counts and list updates are gone; uBlock Origin + ETP are the blockers instead (ui/BrowserChrome.kt:87-94) — Phase 3 scope ("Mariner's lists and per-site exceptions") |
| Per-site blocking exception | BrowserChrome.kt:629, 866-881; ui/SettingsScreen.kt:501 | missing now | absent (field only: data/PioneerStore.kt:54 `disableBlocking`) | Cannot turn blocking off for one site (Phase 3 per plan, but the field already exists with no UI) |
| Global ad-blocking toggle | BrowserChrome.kt:630, 882-896 (`blockAds`, default hidden) | differs | ui/SettingsScreen.kt:240-280 (ETP strict/basic/off instead) | No per-list global toggle; the global control is the ETP level |
| Block-an-element (picker + net rule) | BrowserChrome.kt:753-754; BrowserScreenParts.kt:145-262; BrowserScreen.kt:5718-5721 | missing now | absent | Cannot tap an element to block it, from the menu or a long-press (Phase 3 per plan) |
| Tracking protection strict / basic / off (ETP) | absent in Mariner (own lists instead) | present | MainActivity.kt:115-120; engine/GeckoEngine.kt:167-190; ui/SettingsScreen.kt:240-280 | Pioneer-only capability; applies at once and reloads the tab |
| Blocked-count per tab (menu header) | BrowserChrome.kt:608-609; BrowserScreen.kt:5306 | missing now | absent | No blocked-item count anywhere (Phase 3 per plan) |

### Theming
| feature | Mariner ref | Pioneer | Pioneer ref | effect |
|---|---|---|---|---|
| Theme modes system / light / dark | ui/AppTheme.kt:252-258 | present | ui/AppTheme.kt:176-190; ui/SettingsScreen.kt:184-205 | — |
| Material You (+ manual light/dark variants) | ui/AppTheme.kt:254-267 | present | ui/AppTheme.kt:179-192 (`materialyou-light` / `materialyou-dark`) | Engine support present; **no Settings rows to select them** (see §4) — Robert confirmed Material You on device 2026-11-11, presumably via System theme |
| UI font choice (`uiFont`) | ui/AppTheme.kt:160-186; ui/SettingsScreen.kt:991 | missing now | ui/AppTheme.kt:48-52 (comment: "the interface-font setting does not exist in Phase 2") | Interface font cannot be changed |
| UI font scale / interface text size | ui/AppTheme.kt:273; ui/SettingsScreen.kt:1056-1074 | missing now | absent (no `uiFontScale` wired) | Interface text size cannot be changed |
| Wordmark fonts (Garamond Bold / Montserrat) | ui/AppTheme.kt:55-63 | differs | ui/AppTheme.kt:52 (`WordmarkFontFamily = FontFamily.Default`) | Wordmark renders in the platform default face, not Mariner's Garamond/Montserrat |
| Interface settings (bar position, home button, immersive, etc.) | ui/SettingsScreen.kt:874-1090; ui/StartPageSettingsScreen.kt:343 | missing now | absent (SettingsScreen Appearance = theme/tiles/banner only) | No interface-settings page at all |
| Wallpaper follows theme (light/dark colours) | browser/StartPage.kt:147-174 | present | ui/StartPage.kt:118-126 (colour only) | Custom photo is §5, missing now |
| Search engine choice (+ custom URL) | ui/SettingsScreen.kt:1138-1163 | differs | ui/SettingsScreen.kt:226-231 (DuckDuckGo, fixed) | No engine choice; every search goes to DuckDuckGo |

### Menu rows + Customize — given list verified exactly
| feature | Mariner ref | Pioneer | Pioneer ref | effect |
|---|---|---|---|---|
| Menu row count: Mariner 33, Pioneer 18 | BrowserChrome.kt:329-363 (`ALL`, 33 entries) | differs | ui/BrowserChrome.kt:334-353 (`ALL`, 18 entries) | Verified correct as given |
| Dropped 19 rows | BrowserChrome.kt:329-363 | missing — mixed | absent | Verified: `readArticle, media, blockElement, savePdf, saveOffline, scanQr, screenshot, addToHome, siteBlocking, globalAds, readingList, readLater, playlists, offlineLibrary, downloads, bookshelf, games, vaultFill, passwordVault` all present in Mariner's `ALL`, all absent in Pioneer's. By phase: reader/vault → Phase 4; media/downloads/playlists/offlineLibrary/savePdf/saveOffline → Phase 3; games → games; `blockElement, siteBlocking, globalAds, scanQr, screenshot, addToHome` → missing now (no phase assigned) |
| Added 4 rows | absent in Mariner | present | ui/BrowserChrome.kt:348-352 (`installUBlock, copyStatus, diagnostics, licences`) | Verified — all four exist in Pioneer only |
| Customize menu (reorder / hide / reset, same keys) | BrowserChrome.kt:324-325, 408, 502-574 | present | ui/BrowserChrome.kt:331-332, 381, 465-537 (same keys `menuHiddenRows` / `menuRowOrder`) | — |

### App mode, games, updater, diagnostics, onboarding, default browser
| feature | Mariner ref | Pioneer | Pioneer ref | effect |
|---|---|---|---|---|
| App mode (pinned site, chromeless) | MainActivity.kt:162-163; BrowserScreen.kt:234-235, 4386-4448 | missing now | absent | No pinned-site / chromeless mode; launcher shortcuts cannot open a site as an app |
| Games / emulator screens | MainActivity.kt:607; ui/GamesScreen.kt:121 | missing — games (no phase assigned) | absent | No games section at all |
| Game library / ROM import / save states | ui/GamesScreen.kt:121, 445, 711; emu/GameActivity.kt | missing — games | absent | No ROM library, imports, or save states |
| Updater / update checks | UpdateCheck.kt:28, 59 (`checkAsync`) | missing — updater (no phase assigned) | absent | No automatic or manual update check |
| Updates screen | ui/UpdatesScreen.kt:45; UpdateCheck.kt:30 | missing — updater | absent | No updates page or release notes |
| Diagnostics / Copy status | absent in Mariner | present | MainActivity.kt:407-417; ui/BrowserScreen.kt:506-550; engine/Engine.kt:82 | Pioneer-only capability; status + media-probe lines, copyable |
| First-run / onboarding (WelcomeScreen) | MainActivity.kt:354-359, 416-421; ui/WelcomeScreen.kt | missing now | absent | App opens straight into the browser; only the uBlock prompt appears on first launch |
| Default-browser setting | ui/SettingsScreen.kt:2058-2081 (`ROLE_BROWSER`); ui/WelcomeScreen.kt:201-287 | missing now | absent | No way to set Pioneer as the default browser from inside the app |
| Licences screen | ui/ThirdPartyNotices.kt (notices only, no Licences screen) | present | ui/LicencesScreen.kt:22 | Pioneer-only screen; Mariner has only the notices list |

**Features counts: 115 total — 37 present, 22 differs, 56 missing (later phase 29: Phase 3: 13 · Phase 4: 12 · games: 2 · updater: 2; missing now: 26; mixed bundle: 1).**

**5 most user-visible missing-now items:** (1) Address bar fixed at top, no Bottom position — Mariner's default is bottom. (2) Forward, Stop, and Home buttons all absent — forward/stop exist only as engine calls with no UI; no Home button at all. (3) No pull-to-refresh, no scroll-hide bar, no swipe-on-pill tab switching. (4) No voice input and no site-info sheet. (5) No recently-closed, no close-tab Undo, no tab thumbnails.

**Corrections/notes found while verifying:** Pioneer's `ui/BrowserChrome.kt:89-92` header comment says Desktop site is omitted in Phase 2 — **stale**: the Desktop site row is implemented (`ui/BrowserChrome.kt:657-670`) and `setDesktopMode` works; only its persistence on relaunch is broken (hardcoded `desktopMode = false`, `MainActivity.kt:375`). Pioneer `SiteSetting` carries `disableBlocking`, `allowPopups`, `blockPopups`, `blockImages`, `javaScript` fields (`data/PioneerStore.kt:51-54`) but no UI or engine code reads them — the per-site model exists, the behaviour does not.

## §6 — The `barPosition` case (the miss this backstop exists to catch)

- **Mariner setting row:** `ui/SettingsScreen.kt:927–934` — `store.setting("barPosition", "bottom")`, ChoiceRows "Address bar: Bottom" → `putSetting("barPosition", "bottom")` and "Address bar: Top" → `"top"`. Default **bottom**.
- **Mariner single read site:** `browser/BrowserScreen.kt:3512` — `val barAtBottom = store.setting("barPosition", "bottom") != "top"`. That one boolean drives the whole layout: content padding `3563–3564, 3633–3634, 4038–4044`; bar alignment `4280–4281, 4389–4398`; the address bar itself placed conditionally at `4401` (`if (!barAtBottom) addressBar()`) vs `4404` (`if (barAtBottom) addressBar()`); toolbar/menu placement `4421–4435`; passed down at `4479, 4520`.
- **Pioneer absence confirmed twice:** `grep -rn "barPosition"` across the entire Pioneer tree = **0 hits** (verified by two auditors independently). `ui/BrowserScreen.kt:229–281`: the address-bar `Row` is the first child of a top-down `Column`, directly above the `GeckoView` (`weight(1f)`). No setting, no conditional, no bottom placement anywhere; `barAtBottom = false` is hardcoded at the FindBar/SuggestionsCard call sites (`ui/BrowserScreen.kt:302, 315`), so even the bottom-floating variants of those composables never render.
- **User-visible effect:** Pioneer users get a fixed top bar; Mariner users who chose Bottom (the default) lose that choice entirely, and the whole floating-bar system (scroll-hide, swipe-on-pill, satellite circles, pill-fill progress) is absent with it.
- **Why it slipped through:** Phase 2 rebuilt the shell from a behaviour spec instead of porting the tree, and an eyeballed UI pass compares what exists — it does not notice a setting that was never built. The inventory in §3–§5 is the backstop: every key/row/feature enumerated from Mariner's code, each checked against Pioneer.

## §7 — Cannot be judged from code alone (needs side-by-side on the device)

- **Exact visual match of the carried files:** FrostKit is byte-identical code and AppTheme near-identical, but rendering equality (colours, radii, elevation, type) needs Pioneer and Mariner open side by side on the Pixel 10a.
- **Material You:** engine support is present in Pioneer (`materialyou-light`/`materialyou-dark` in `ui/AppTheme.kt:179-192`) and Robert confirmed Material You on device 2026-11-11 — presumably via the System theme. What code cannot show: whether the System theme actually yields the dynamic palette, and whether the missing manual Material You rows matter to him. Verify on device; do not rebuild the theme.
- **Scroll-hide / swipe / pull-to-refresh feel:** absent in code, so nothing to judge visually — but once rebuilt, timing, thresholds and animation curves need device comparison against Mariner.
- **Find "0 / 0" bug, zoom liveness, ETP/desktop reload behaviour, first-load-failed-then-retry:** code shows the mechanisms; only device runs show whether they actually misbehave.
- **Tab thumbnails:** no engine screenshot path exists in Pioneer yet; whether GeckoSession can supply one (and at what cost) is a device + API question.
- **Player UI (Phase 3):** must be identical to Mariner's PlayerActivity — floating pill and all. Code comparison of the player cannot happen until Phase 3 builds it; the Gate 3 UI check must compare it pill by pill against §1 entries #18–26.
- **Screenshot comparison method:** for each gate, open the same screen in both apps on the same phone and compare directly; the inventory tables (§3–§5) are the checklist, not a substitute for looking.

## §8 — Recommended fix order (corrections as own commits, sequenced AFTER Phase 3 lands — do not collide with the Phase 3 builder on the same branch)

Order is by user visibility, not by file. Items marked Phase 3/4 in §3–§5 are NOT corrections — they land with their phase and are checked at that phase's gate UI check instead.

**Batch 1 — the floating bar (biggest visible gap, one coherent change):**
1. `barPosition` setting (Bottom default) + Settings rows "Address bar: Bottom/Top" — Mariner `ui/SettingsScreen.kt:927-934`, read at `browser/BrowserScreen.kt:3512`.
2. Rebuild the address bar as Mariner's floating frost capsule (`frostBar(RoundedCornerShape(50))`, 12dp side inset, 10dp from edge, scroll-hide slide+fade, pill-fill progress) + satellite circles (Back/Forward, Stop, tab-count, Media, Menu) + swipe-on-pill tab switching + pull-to-refresh indicator.
3. Mic/voice input + site-info/lock icon + on-screen Cancel while typing in the bar.

**Batch 2 — navigation buttons + tabs:**
4. Forward, Stop, Home buttons (engine calls exist — wire the UI); long-press Back/Forward history sheet; long-press tab-count = new tab.
5. Tab grid: per-class grids, thumbnails (or Mariner grid layout with title rows as a marked interim), search, grid/list toggle, reorder; recently-closed list; close-tab Undo snackbar; per-class tab count; close-duplicate + close-all; restore desktop mode + scroll position on relaunch (stop hardcoding `desktopMode = false`).
6. Private tabs: separate per-class switcher views, explicit profile wipe on last close.

**Batch 3 — small visible diffs (cheap, high count):**
7. Fetched favicons everywhere (tiles + rows) instead of letter tiles; history undo restores exactly (keep id/timestamp).
8. Wordmark fonts: bundle Cormorant Garamond Bold + Montserrat (Mariner `ui/AppTheme.kt:55-63`) instead of `FontFamily.Default`.
9. Back icon ChevronLeft on every sub-screen header (Mariner `ui/AppTheme.kt:318` → Pioneer `ui/AppTheme.kt:235` currently ArrowBack).
10. Find "0 / 0" fix (unknown total → count-only/…); uBlock prompt as `FrostDialog` (currently plain `AlertDialog`); restore the two lost menu-row icons + Tune icon; fix the stale Desktop-site header comment (`ui/BrowserChrome.kt:89-92`).
11. Material You rows in Settings (System/Light/Dark already there; add the manual Material You Light/Dark rows Mariner has) — only if device check shows System theme does not already yield it.

**Batch 4 — Settings + start page (missing-now rows):**
12. Settings: web dark mode, web text size, UI font + scale + custom font, interface text size, immersive, restore-tabs toggle, open-links-in-new-tab, search-engine choice + custom URL, HTTPS-only (+ exceptions), cookies/session/DOM/GPC/referer/autoplay, clear-data ranges + per-type toggles, delete-on-exit, per-site controls screen, default browser, welcome-guide replay, reduced motion, force zoom, system captions, storage sizes, clear cache, home-page URL, home-button toggle, showFrequent toggle.
13. Start page: shortcuts pins + Edit, custom-photo wallpaper, banner swatch/artwork, fetched favicons (see 7); confirm with Robert whether to keep Pioneer's added Bookmarks section.
14. Long-press menu: sheet presentation + View image / Download / Block this element rows (Download + Block land with Phase 3; View image is Batch 4).
15. Onboarding/welcome + default-browser flow; app mode (pinned site, chromeless + flat exit circle).

**Explicitly NOT corrections (land with their phase; verify at that gate's UI check):** Phase 3 — downloads stack + download pill + Downloads screen, media finder + media card + pop-up player + fullscreen + PiP + playlists + offline library, PlayerActivity (all 9 floating pills, identical), permissions prompts + file chooser, Mariner's filter lists + per-site exceptions + block-an-element + blocked-counts, cast chooser stays Phase 4. Phase 4 — reader + TTS pills/sheets, vault fill chip + vault screens + form fill, Cast session launch + route chooser. Games + updater: no phase assigned in the plan — do not build; flag to Robert as a scope question, not a correction.
