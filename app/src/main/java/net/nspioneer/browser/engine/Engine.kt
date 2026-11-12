package net.nspioneer.browser.engine
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.mozilla.geckoview.GeckoSession
import net.nspioneer.browser.media.DetectedMedia  // defined in C's
    // MediaLog.kt (design §5, with the `source` field) — do NOT
    // redefine it here, or integration gets a duplicate.

data class FindResult(val ordinal: Int, val count: Int, val found: Boolean)

/** What a long-press landed on (Batch 4): the link URL
 *  (when the element is or contains a link) and the image
 *  URL (when the element is an image — ContentDelegate
 *  TYPE_IMAGE + srcUri, verified against the pinned 159
 *  AAR). Either may be null; at least one is set when the
 *  menu shows. */
data class LongPressTarget(val linkUrl: String?, val imageUrl: String?)

/** One entry of a tab's session (back/forward) history, from
 *  GeckoSession.SessionState (Batch 2). [index] is the absolute
 *  index in the session list; [isCurrent] the page showing now. */
data class SessionHistoryEntry(
    val title: String,
    val url: String,
    val index: Int,
    val isCurrent: Boolean
)

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
    // Root scroll offset in CSS px, from
    // GeckoSession.ScrollDelegate (Batch 1): the shell hides the
    // floating address bar on scroll-down (Mariner
    // BrowserScreen.kt:2604) and arms pull-to-refresh only at 0.
    val scrollY: State<Int>
    // ---- Batch 2: session history + session state ----
    // From ProgressDelegate.onSessionStateChange; empty until
    // the first state push.
    val historyEntries: State<List<SessionHistoryEntry>>
    // Latest GeckoSession.SessionState.toString(), or null
    // before the first push. The shell persists this per tab
    // for relaunch restore (history + scroll + zoom + form
    // data, as Mariner's WebView saveState bundle does).
    val sessionStateJson: State<String?>
    // Jump to an absolute session-history index
    // (GeckoSession.gotoHistoryIndex).
    fun goToHistoryIndex(index: Int)
    // Ask for a fresh onSessionStateChange before persisting
    // (GeckoSession.flushSessionState).
    fun flushSessionState()
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
    var onLongPressTarget: ((LongPressTarget) -> Unit)?  // Batch 4:
        // the context-menu hook (GeckoSession.ContentDelegate.
        // onContextMenu, verified against the pinned 159 AAR:
        // element.linkUri + element.srcUri + type). Fires when
        // a link or an image is long-pressed; the shell shows
        // the long-press sheet.
    var onCrash: (() -> Unit)?

    // ---- Phase 3: media detection ----
    val detectedMedia: State<List<DetectedMedia>>
    fun runMediaScan()  // ask the probe for a fresh DOM scan now
        // (toolbar button / long-press path). The background
        // webRequest observer feeds detectedMedia continuously;
        // runMediaScan only re-runs the DOM half.
    var onVideoPopupTap: ((url: String) -> Unit)?
    var onVideoPopupLongPress: ((url: String) -> Unit)?
    // ---- Phase 3: fullscreen ----
    val isFullscreen: State<Boolean>  // true while a page is in
        // fullscreen (ContentDelegate.onFullScreen). The SHELL hides
        // the browser chrome; Fullscreen.kt hides the system bars.
    // ---- Phase 3: popup windows (strict popup blocker) ----
    // True when this tab is a popup window opened by
    // window.open / onNewSession (Mariner TabList.isPopupWin).
    // onCloseWindow closes only popup tabs.
    val isPopupWin: Boolean
}

interface Engine {
    fun createTab(
        isPrivate: Boolean,
        // Batch 2: a saved SessionState JSON to restoreState()
        // instead of loading a URL (null = fresh tab).
        restoreStateJson: String? = null,
        // Batch 2: desktop UA + viewport BEFORE any load or
        // restore — setDesktopMode would reload and lose the
        // restored scroll position.
        desktopMode: Boolean = false,
        // Batch 2: pending scroll restore for URL-only
        // restores (pre-alpha.6 saves); posted to the probe
        // when its port connects.
        scrollY: Int = 0,
    ): EngineTab
    // Batch 2: flush every tab's session state (onPause,
    // before the shell persists the session).
    fun flushSessionStates()
    fun destroyTab(tab: EngineTab)
    var onTabCreated: ((EngineTab) -> Unit)?  // fired when the engine
        // creates a tab itself (onNewSession / window.open): the shell
        // adds it to its tab list and switches to it.
    var onTabDestroyed: ((EngineTab) -> Unit)?  // fired when the engine
        // destroys a tab itself (onCloseWindow / window.close() on a
        // popup tab): the shell removes it from its tab list. Without
        // this the shell keeps a dead tab whose closed session the
        // view would rebind (watch-wrestling.eu crash suspect,
        // 2026-11-12). Not fired while the engine itself is closing.
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
    fun noteStatus(line: String)    // append a line to the status
        // panel (Diagnostics / Copy status); the shell uses it for
        // launch-time notes such as a previous run's crash line.
    fun refreshStats()              // recomputes the "stats: APK … | PSS …"
        // line (cold start, PSS, APK size).
    fun close()

    // ---- Phase 3: blocking ----
    // Per-site exception state for ONE host. disableBlocking is the
    // PioneerStore SiteSetting flag (JSON key "nb", already exists);
    // setBlockingEnabled applies it to every layer at once: ETP exception
    // via StorageController.setPermission(PERMISSION_TRACKING, VALUE_ALLOW),
    // Mariner-lists bypass in the probe, and the cosmetic bypass. uBlock
    // Origin is NOT toggled by this call — the app cannot toggle a
    // per-site exception inside uBlock; see PHASE3-DESIGN.md §10 (known
    // gap). Do not pretend otherwise in the UI: the site-info row says
    // what it disables.
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
    var onExternalDownload: ((tab: EngineTab, download: ExternalDownload) -> Unit)?
    // ---- Phase 3: permissions + file chooser ----
    // Mariner's PermPrompt, ported verbatim in shape (BrowserScreen.kt:430+).
    // onGrant receives the resources actually granted (after the OS check);
    // onDeny is called when the user dismisses or denies. The ENGINE builds
    // the prompt; the SHELL shows Mariner's dialog and calls back.
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

    // ---- Batch 4: settings the engine enforces ----
    // Web dark mode ("Auto" | "On" | "Off"): GeckoView has no
    // forced darkening — this sets prefers-color-scheme only
    // (GeckoRuntimeSettings.setPreferredColorScheme, verified
    // against the pinned 159 AAR). Pages with a dark theme
    // follow it; pages without one stay light.
    fun setWebDarkMode(mode: String)
    fun setForceZoom(enabled: Boolean)   // runtime forceUserScalableEnabled
    fun setGlobalPrivacyControl(enabled: Boolean)  // runtime GPC
    // Re-read the cookies / block-third-party settings and
    // apply the cookie behaviour (ACCEPT_NONE /
    // ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS /
    // ACCEPT_NON_TRACKERS). Called at runtime creation and
    // whenever the settings change; setTrackingProtection
    // routes through it instead of hardcoding a behaviour.
    fun applyCookieSettings()
    // Reduced motion: push the current setting to every open
    // tab's probe port; pages ask for it on load
    // ("privacy-query"). The probe overrides matchMedia and
    // stills animations (Mariner's DOCUMENT_START script,
    // probe-shaped, document_idle — an early animation may
    // run once before it lands).
    fun setReducedMotion(enabled: Boolean)
    // Re-apply the effective text size (per-site override, else
    // the global webTextZoom) to every open tab.
    fun applyWebTextZoom()
    // Push the current autoplay-mute state to every open tab's
    // probe port (the probe mutes media until the first
    // pointer/key interaction). Per-site changes reload the
    // tab instead, which re-queries.
    fun applyAutoplay()
    // Reload every open tab on [host] (per-site panel changes).
    // Applies the stored per-site JavaScript value to the
    // session first, so the reload is the only one.
    fun reloadTabsOnHost(host: String)
    // Re-read the user's own block rules from the store and
    // push them to the probe (the long-press "Block this
    // element" row).
    fun syncUserRules()
    // Re-push the rule set (per-site image blocking /
    // referer policy changed).
    fun refreshProbeRules()
    // Clear Gecko storage: StorageController.clearData flags
    // (COOKIES / ALL_CACHES / SITE_DATA …).
    fun clearGeckoData(flags: Long)
    // Clear all site data for one host
    // (StorageController.clearDataFromHost, SITE_DATA).
    fun clearDataForHost(host: String)
    // The https:// upgrade of an http URL for [tab], or null
    // when the URL cannot be upgraded (not http, or the host
    // is non-upgradeable / a previous upgrade failed there).
    fun httpsUpgradeFor(tab: EngineTab, raw: String): String?
    // A non-upgradeable http load was stopped by HTTPS-only
    // mode: the shell shows the interstitial and, on the
    // user's word, excepts the host and loads it.
    var onHttpsWarn: ((url: String) -> Unit)?
}

// ---- Phase 3: downloads ----
data class ExternalDownload(
    val url: String,
    val contentType: String?,         // from response headers, may be null
    val contentLength: Long,          // -1 when unknown
    val suggestedName: String?,       // parsed Content-Disposition, may be null
    val statusCode: Int,
    val isSecure: Boolean,
)

// ---- Phase 3: permissions + file chooser ----
data class PermPrompt(
    val origin: String,
    val host: String,
    val resources: List<String>,      // "camera" | "microphone" | "geolocation"
    val onGrant: (granted: List<String>) -> Unit,
    val onDeny: () -> Unit,
)
