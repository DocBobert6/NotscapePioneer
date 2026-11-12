package net.nspioneer.browser.engine

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.media.DetectedMedia
import net.nspioneer.browser.media.Downloads
import net.nspioneer.browser.media.MediaLog
import net.nspioneer.browser.media.VideoPopup
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension

/**
 * Notscape Pioneer — Phase 2 engine: the [Engine] interface over
 * GeckoView. Built by extracting the Phase 1 spike's runtime /
 * session / delegate / uBlock / probe / Cast / status code
 * (MainActivity, commits 4609fbb5 / fb3180f5 — proven on Robert's
 * Pixel 10a) into this module, preserving its behaviour.
 *
 * One [GeckoRuntime] (ETP Strict, as the spike), one [GeckoSession]
 * per tab. Private tabs are construction-time only
 * (`usePrivateMode(true)`) and never persisted.
 *
 * First-load watch item (Gate 1: a fresh page failed its first load
 * twice, then loaded fine on retry — youtube.com, flower.mp4):
 * what the spike did was open the sessions in onCreate, call
 * `loadUri` immediately, and only THEN call `setActive(true)` /
 * `setFocused(true)` — and the GeckoView itself did not exist yet
 * (created later in composition), so the first load raced runtime
 * init / view attachment. This engine therefore (a) opens the
 * session in [createTab] before any load can be issued, (b) marks
 * the session active + focused in [GeckoTab.load] BEFORE `loadUri`,
 * and (c) re-opens the session in [GeckoTab.load] if it is not open.
 * That is the cheap mitigation; the exact cause is unproven without
 * a device, so the item stays a known issue if it recurs on alpha.3
 * (a failed first load is logged as "load FAILED" by
 * onPageStop, exactly as the spike logged it).
 */
class GeckoEngine(context: Context) : Engine, ProbeHost, BlockingHost {

    companion object {
        private const val CAST_RECEIVER_APP_ID = "CC1AD845" // Default Media Receiver
        /** Shared context id for every private tab (Batch 2):
         *  one partitioned cookie jar for the whole private
         *  class, as Mariner's single "notscape_private"
         *  profile — so the last-private-close wipe below
         *  deletes exactly the private data and nothing else. */
        private const val PRIVATE_CONTEXT_ID = "pioneer-private"
    }

    private val appContext = context.applicationContext
    private val store = PioneerStore.get(context)
    private val statusPanel = StatusPanel(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The single popup controller (rebuild item 1, alpha.13):
     *  one per-source-tab state machine replacing the four split
     *  pieces (the `expectPopupSession` boolean, the
     *  PromptDelegate's pending fields, the shell's
     *  `handlePopupPrompt`, and the popunder-guard capture). */
    private val popupController = PopupController(store) { line ->
        statusPanel.status(line)
    }

    /** Phase 3 blocking bridge (Worker A): owns the app side
     *  of the probe's blocking messages (`blocked`,
     *  `cosmetic-query`, `rules-request` / `rules-update`) and
     *  the background script's native port. The engine is its
     *  [BlockingHost]. */
    private val blockingObserver = BlockingObserver(this).also {
        // Chain the filter-lists status line onto the
        // lists-changed push: BlockingObserver.init claims
        // FilterEngine.onListsChanged for the rules-update
        // push; the Settings status text refreshes here when
        // the new set lands.
        val prev = FilterEngine.onListsChanged
        FilterEngine.onListsChanged = { prev?.invoke(); refreshFilterListsState() }
    }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        // blockAds / blockTrackers live in the store and are
        // toggled by BlockingSettings (which never touches the
        // engine), so the engine watches the settings and
        // re-pushes the rule set (syncBlockingFlags) when
        // either flips. The first emission is the current
        // value — skipped: the background script already reads
        // the flags fresh from the rules-request reply / the
        // port-connect push.
        engineScope.launch {
            snapshotFlow {
                store.setting("blockAds", "true") to
                    store.setting("blockTrackers", "true")
            }.distinctUntilChanged().drop(1).collect {
                blockingObserver.syncBlockingFlags()
            }
        }
    }

    /** The Activity hosting this engine, for fullscreen
     *  system-bars handling (Fullscreen.kt). Null when the
     *  engine was built with a non-activity context. */
    private val activity: Activity? = context as? Activity

    /** Phase 3 delegates (Worker B). Lazy so they can
     *  close over [runtime], which is created just below. */
    private val permissionDelegate: PioneerPermissionDelegate by lazy {
        PioneerPermissionDelegate(
            appContext = appContext,
            store = store,
            runtime = runtime,
            deliverPrompt = { prompt -> onPermissionPrompt?.invoke(prompt) },
            checkOs = { resources, onResult ->
                osGrantHandler?.invoke(resources, onResult)
                    ?: onResult(resources.filter { res ->
                        val perm = when (res) {
                            "camera" -> android.Manifest.permission.CAMERA
                            "microphone" -> android.Manifest.permission.RECORD_AUDIO
                            else -> android.Manifest.permission.ACCESS_FINE_LOCATION
                        }
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            appContext, perm
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    })
            },
        )
    }
    private val promptDelegate: PioneerPromptDelegate by lazy {
        PioneerPromptDelegate(
            appContext = appContext,
            deliverFilePrompt = { type, mimeTypes, capture, onResult ->
                onFilePrompt?.invoke(type, mimeTypes, capture, onResult)
            },
            popupController = popupController,
            // The tab the popup request's session belongs to: the
            // controller decides against ITS host (rebuild item 2),
            // never the engine's current tab.
            sourceTabForSession = { session ->
                tabs.firstOrNull { it.session === session }?.let { tab ->
                    PopupController.SourceTab(
                        id = tab.id,
                        host = runCatching { Uri.parse(tab.url.value).host }
                            .getOrNull() ?: "",
                        isPrivate = tab.isPrivate,
                    )
                }
            },
        )
    }

    /**
     * OS-permission gate for stored-allow permission
     * decisions. The shell (BrowserScreen) sets this to its
     * RequestMultiplePermissions launcher (Mariner's
     * grantWithOsCheck); when unset, only already-granted
     * OS permissions are reported. Not part of the Engine
     * interface — the interface's PermPrompt flow covers
     * the dialog path, and this hook covers the stored-
     * decision path that also needs the OS check.
     */
    var osGrantHandler: ((resources: List<String>, onResult: (List<String>) -> Unit) -> Unit)? = null

    private val runtime: GeckoRuntime = createRuntime().also {
        statusPanel.status("runtime: created (ETP Strict)")
    }

    private val extensions = ExtensionManager(
        runtime = runtime,
        status = statusPanel,
        host = this,
        sessionProvider = { tabs.map { it.session } },
        promptProvider = { onExtensionInstallPrompt },
    )

    private val tabs = mutableListOf<GeckoTab>()
    private var nextTabId = 1
    private var closed = false

    /** Last tab to navigate or load — the "current tab" heuristic
     * for [setTrackingProtection]'s reload (the engine is not told
     * which tab the shell shows; a tab that just navigated is the
     * best available proxy). */
    private var currentTab: GeckoTab? = null

    /** The tab the shell currently shows (rebuild items 4 + 7):
     *  the shell reports every selection via [onTabSelected].
     *  The new-session bring-up activates + focuses only this
     *  tab, so a background popup tab never steals focus. */
    private var selectedTab: GeckoTab? = null

    // ---- Phase 3: Engine interface members ----
    override var onExternalDownload: ((EngineTab, ExternalDownload) -> Unit)? = null
    override var onPermissionPrompt: ((PermPrompt) -> Unit)? = null
    override var onFilePrompt: ((String, List<String>, Int,
        (List<android.net.Uri>?) -> Unit) -> Unit)? = null
    private val _filterListsState =
        mutableStateOf(blockingObserver.filterListsStateText())
    override val filterListsState: State<String> get() = _filterListsState

    /** Refresh the filter-lists status line from the blocking
     *  bridge (Worker A: `filterListsState` ←
     *  `filterListsStateText()`). Posted to the main thread:
     *  the lists-changed push can fire off it. */
    private fun refreshFilterListsState() {
        mainHandler.post {
            _filterListsState.value = blockingObserver.filterListsStateText()
        }
    }

    override var onTabCreated: ((EngineTab) -> Unit)? = null
    override var onTabDestroyed: ((EngineTab) -> Unit)? = null
    override var onPopupClosed: ((EngineTab) -> Unit)? = null
    override var onExtensionInstallPrompt: ((String, List<String>, (Boolean) -> Unit) -> Unit)? = null

    override val uBlockState: State<String> get() = extensions.uBlockState
    override val castRoutes: State<String> get() = _castRoutes
    private val _castRoutes = mutableStateOf("cast: not probed yet")
    override val statusLines get() = statusPanel.statusLines

    // Diagnostics trace window (alpha.16): markers live in the same
    // status log Copy status already copies.
    override val isTracing get() = statusPanel.isTracing
    override fun startTrace() = statusPanel.startTrace()
    override fun stopTrace() = statusPanel.stopTrace()
    override val mediaLines get() = statusPanel.mediaLines

    private var mediaRouter: MediaRouter? = null
    private var castCallback: MediaRouter.Callback? = null

    /** Last Cast route signature (sorted names) — the dedupe key. */
    private var lastCastSignature: String? = null

    init {
        extensions.installProbeExtension()
        probeCastRoutes()
        statusPanel.refreshStats()
        // Batch 2: wipe the private context at startup, as
        // Mariner wipes its private profile — a process that
        // died with private tabs open must not leave their
        // data behind.
        wipePrivateData()
    }

    // ------------------------------------------------------------------
    // Runtime
    // ------------------------------------------------------------------

    private fun createRuntime(): GeckoRuntime {
        val settings = GeckoRuntimeSettings.Builder()
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                    .cookieBehavior(cookieBehaviorForStore())
                    .build()
            )
            // Batch 4: web dark mode is prefers-color-scheme
            // only — GeckoView 159 has no forced-darkening
            // API (verified against the pinned AAR: no
            // setForceDark / algorithmic-darkening anywhere).
            .preferredColorScheme(preferredColorSchemeForStore())
            .forceUserScalableEnabled(
                store.setting("forceZoom", "false") == "true"
            )
            .globalPrivacyControlEnabled(
                store.setting("gpc", "true") == "true"
            )
            .build()
        return GeckoRuntime.create(appContext, settings)
    }

    /** The cookie behaviour the stored settings mean
     *  (Batch 4): cookies off → ACCEPT_NONE; on with
     *  third-party blocking → first-party + isolate others;
     *  on otherwise → non-trackers. */
    private fun cookieBehaviorForStore(): Int {
        if (store.setting("cookies", "true") != "true") {
            return ContentBlocking.CookieBehavior.ACCEPT_NONE
        }
        return if (store.setting("blockThirdPartyCookies", "true") == "true") {
            ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
        } else {
            ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS
        }
    }

    /** The stored web-dark-mode value as a GeckoView colour
     *  scheme (Batch 4): Auto → system, On → dark, Off →
     *  light. Case-normalised: Settings writes lowercase
     *  ("auto"/"on"/"off") and an older default was "Auto" —
     *  the reader must accept either case, or On/Off never
     *  takes effect (audit 2026-11-12). */
    private fun preferredColorSchemeForStore(): Int = when (
        store.setting("webDarkMode", "auto").lowercase()
    ) {
        "on" -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
        "off" -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
        else -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
    }

    // ------------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------------

    override fun createTab(
        isPrivate: Boolean,
        restoreStateJson: String?,
        desktopMode: Boolean,
        scrollY: Int,
    ): EngineTab =
        createTabInternal(
            isPrivate, isPopupWin = false,
            restoreStateJson = restoreStateJson,
            desktopMode = desktopMode, scrollY = scrollY
        )

    /** Internal tab creation: popup windows (window.open /
     *  onNewSession after an allowed onPopupPrompt) are
     *  marked isPopupWin=true (Mariner TabList.isPopupWin). */
    private fun createTabInternal(
        isPrivate: Boolean,
        isPopupWin: Boolean,
        restoreStateJson: String? = null,
        desktopMode: Boolean = false,
        scrollY: Int = 0,
        // onNewSession passes false: Gecko opens the session it
        // is handed itself, and opening an already-open session
        // throws AssertionError ("Must use an unopened
        // GeckoSession instance") as an uncaught GeckoResult
        // exception that kills the app process
        // (watch-wrestling.eu crash, 2026-11-12, alpha.11).
        // Delegates are attached above either way — Gecko needs
        // them in place when it opens the session.
        openSession: Boolean = true,
    ): EngineTab {
        val session = if (isPrivate) {
            GeckoSession(
                GeckoSessionSettings.Builder()
                    .usePrivateMode(true)
                    .contextId(PRIVATE_CONTEXT_ID)
                    .build()
            )
        } else {
            GeckoSession()
        }
        val tab = GeckoTab(nextTabId++, isPrivate, session, isPopupWin)
        attachDelegates(tab)
        // The session is fully open before any load can be issued
        // (first-load watch item: the spike loaded immediately after
        // open, before setActive/setFocused and before any view).
        // Skipped only for onNewSession (see openSession above):
        // GeckoTab.load re-opens on demand if a load ever arrives
        // before Gecko's own open.
        if (openSession) {
            session.open(runtime)
            // The shell binds the view on this signal (items 4 + 7).
            tab.sessionOpened.value = true
        }
        if (desktopMode) {
            // Batch 2: desktop UA + viewport BEFORE any load or
            // restore — setDesktopMode would reload and lose the
            // restored scroll position.
            session.settings.userAgentMode =
                GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            session.settings.viewportMode =
                GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            tab.isDesktopMode.value = true
        }
        if (!restoreStateJson.isNullOrBlank()) {
            // Batch 2: relaunch restore — history, scroll,
            // zoom and form data in one call (Mariner's
            // WebView restoreState bundle, Gecko-shaped).
            session.setActive(true)
            session.setFocused(true)
            runCatching {
                GeckoSession.SessionState.fromString(restoreStateJson)?.let { state ->
                    session.restoreState(state)
                    // The delegate may or may not re-fire
                    // onLocationChange for a restore; pin the bar
                    // state from the state itself so the tab never
                    // reads as a home tab, and keep the restore
                    // from re-recording history.
                    val current = state.getOrNull(state.currentIndex)
                    val uri = current?.uri
                    if (!uri.isNullOrBlank() && uri != "about:blank") {
                        tab.url.value = uri
                        tab.title.value = current.title ?: ""
                        tab.lastNavigatedUrl = uri
                    }
                }
            }.onFailure { e ->
                statusPanel.status(
                    "tab ${tab.id}: restoreState failed: ${e.message}"
                )
            }
        } else if (scrollY > 0) {
            // Batch 2: URL-only restore (pre-alpha.6 save) —
            // the scroll rides the probe port when it connects.
            tab.pendingScrollY = scrollY
        }
        tabs += tab
        extensions.registerSession(session)
        return tab
    }

    override fun destroyTab(tab: EngineTab) {
        val own = tab as? GeckoTab ?: return
        // A pending permission request must never hang: answer
        // every outstanding request for this session with a
        // denial before the session closes.
        permissionDelegate.rejectOutstanding(own.session)
        // A held popup prompt from this tab must never hang
        // either: answer it as Deny (once) before the session
        // closes (alpha.14).
        popupController.cancelPendingForTab(own.id)
        MediaLog.clear(own.id)
        tabs.remove(own)
        if (currentTab === own) currentTab = null
        own.probePort?.let { runCatching { it.disconnect() } }
        own.probePort = null
        runCatching { own.session.close() }
        // Batch 2: the last private tab closing wipes the whole
        // private context (Mariner PrivateProfile.wipe, Gecko
        // shape) — the session is already closed above, and no
        // other private tab remains.
        if (own.isPrivate && tabs.none { it.isPrivate }) {
            wipePrivateData()
            Downloads.wipePrivateDownloads(appContext, store)
        }
        // Tell the shell: the engine destroyed this tab itself
        // (onCloseWindow). Without this the shell keeps the dead
        // tab and the view rebinds its closed session. Not fired
        // while the engine itself is closing (close() sets
        // closed first) — the shell is going away too.
        if (!closed) onTabDestroyed?.invoke(own)
    }

    /** The shell calls this whenever the active tab changes
     *  (switch, new tab, close): record the selected tab and,
     *  when its session is open, activate + focus it — a
     *  new-session tab Gecko opened is inert until this runs
     *  (rebuild items 4 + 7). A background tab is never
     *  activated here; only the selected one. */
    override fun onTabSelected(tab: EngineTab) {
        val own = tab as? GeckoTab ?: return
        selectedTab = own
        if (own.session.isOpen) {
            runCatching {
                own.session.setActive(true)
                own.session.setFocused(true)
            }
        }
    }

    override fun flushSessionStates() {
        tabs.toList().forEach { tab ->
            runCatching { tab.session.flushSessionState() }
        }
    }

    /** Explicit private-data wipe (Mariner PrivateProfile.wipe):
     *  delete the shared private context's storage. Scoped to
     *  the private context id — normal tabs (null context) are
     *  never touched. Any failure is logged, never thrown. */
    private fun wipePrivateData() {
        runCatching {
            runtime.storageController.clearDataForSessionContext(PRIVATE_CONTEXT_ID)
            statusPanel.status("private: wiped private context")
        }.onFailure { e ->
            statusPanel.status(
                "private: wipe failed: ${e.javaClass.simpleName}"
            )
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        engineScope.cancel()
        castCallback?.let { runCatching { mediaRouter?.removeCallback(it) } }
        castCallback = null
        tabs.toList().forEach { destroyTab(it) }
    }

    // ------------------------------------------------------------------
    // Engine-level state
    // ------------------------------------------------------------------

    override fun installUBlock() = extensions.installUBlock()

    override fun setTrackingProtection(level: String) {
        val etp = when (level) {
            "strict" -> ContentBlocking.EtpLevel.STRICT
            "basic" -> ContentBlocking.EtpLevel.DEFAULT
            "off" -> ContentBlocking.EtpLevel.NONE
            else -> ContentBlocking.EtpLevel.STRICT
        }
        val cb = runtime.settings.contentBlocking
        // Batch 4: the stored cookie settings own the cookie
        // behaviour — re-apply them instead of hardcoding
        // ACCEPT_NON_TRACKERS, so "cookies off" (ACCEPT_NONE)
        // survives a tracking-protection change. The level
        // only takes effect with a non-ACCEPT_ALL behaviour
        // (research §7), and applyCookieSettings never
        // produces ACCEPT_ALL.
        applyCookieSettings()
        cb.setEnhancedTrackingProtectionLevel(etp)
        statusPanel.status("tracking protection: $level")
        // Reload the current tab to be safe (level changes may not
        // apply to an already-loaded page without one).
        currentTab?.let { tab ->
            if (tab.url.value.isNotBlank()) tab.reload()
        }
    }

    override fun copyStatusText(): String =
        statusPanel.copyStatusText(castRoutes.value)

    // ------------------------------------------------------------------
    // Phase 3: blocking / downloads / permissions / popups
    // ------------------------------------------------------------------

    override fun setBlockingEnabled(host: String, enabled: Boolean) {
        val clean = host.lowercase().trimEnd('.')
        if (clean.isBlank()) return
        // 1. The store flag (SiteSetting "nb") — the source of
        //    truth the site-info row reads.
        val current = store.siteSetting(clean)
        store.putSiteSetting(current.copy(disableBlocking = !enabled))
        // 2. ETP exception via StorageController
        //    (PERMISSION_TRACKING VALUE_ALLOW; re-enabling uses
        //    VALUE_DENY — Gecko rejects VALUE_PROMPT here).
        permissionDelegate.setTrackingException(clean, disabled = !enabled)
        // 3. Mariner-lists bypass in the probe: the observer
        //    re-reads the per-site exception set from the store
        //    (BlockingHost.disabledHosts) and pushes the updated
        //    rules to the background script. Cosmetics consult
        //    FilterEngine.isDisabledForPage at query time, so the
        //    bypass applies there too.
        blockingObserver.updateDisabledHosts()
        statusPanel.status(
            "blocking: $clean ${if (enabled) "enabled" else "disabled"} " +
                "(ETP + lists + cosmetics; uBlock NOT toggled — see design §10.1)"
        )
    }

    override fun refreshFilterLists() {
        _filterListsState.value = "updating…"
        statusPanel.status("filter lists: updating…")
        // The push to the background script happens from
        // FilterEngine.onListsChanged when the new set lands;
        // the status line refreshes through the chained
        // callback (see blockingObserver).
        blockingObserver.refreshFilterLists(appContext)
    }

    override fun uninstallUBlock() {
        try {
            runtime.webExtensionController.list().accept(
                { installed ->
                    val existing = installed?.firstOrNull {
                        it.id == "uBlock0@raymondhill.net"
                    }
                    if (existing == null) {
                        extensions.uBlockState.value = "not installed"
                        statusPanel.status("uBlock: uninstall — not installed")
                        return@accept
                    }
                    runtime.webExtensionController.uninstall(existing).accept(
                        {
                            extensions.uBlockState.value = "not installed"
                            statusPanel.status("uBlock: uninstalled")
                        },
                        { e ->
                            extensions.uBlockState.value =
                                "failed: ${e?.javaClass?.simpleName}: ${e?.message}"
                            statusPanel.status("uBlock: uninstall FAILED: ${e?.message}")
                        },
                    )
                },
                { e ->
                    extensions.uBlockState.value = "failed: ${e?.message}"
                    statusPanel.status("uBlock: uninstall list failed: ${e?.message}")
                },
            )
        } catch (e: Throwable) {
            extensions.uBlockState.value = "failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    override fun answerPermissionPrompt(prompt: PermPrompt, allow: Boolean) {
        permissionDelegate.answer(prompt, allow)
    }

    // Popup blocking (rebuild item 1): the single controller
    // owns the queue; the shell only renders and drains it.
    override val popupCaptures: SnapshotStateList<String>
        get() = popupController.capturedUrls
    override val popupCaptureHost: State<String>
        get() = popupController.capturedHost
    override fun takeCapturedPopups(): List<String> =
        popupController.takeCaptured()
    override fun clearCapturedPopups() {
        popupController.clearCaptured()
    }

    // Four-choice popup prompt (alpha.14): the controller holds
    // the unlisted site's request; the shell renders the prompt
    // from this state and answers it here.
    override val pendingPopupDecision: State<PopupController.PendingPopupDecision?>
        get() = popupController.pendingDecision
    override fun answerPopupDecision(choice: PopupChoice) =
        popupController.answerPending(choice)
    override fun dismissPopupDecision() = popupController.dismissPending()

    // ------------------------------------------------------------------
    // Batch 4: settings the engine enforces
    // ------------------------------------------------------------------

    override var onHttpsWarn: ((String) -> Unit)? = null

    override fun setWebDarkMode(mode: String) {
        runCatching {
            // Case-normalised: Settings passes lowercase
            // ("auto"/"on"/"off"); accept either case.
            runtime.settings.preferredColorScheme = when (mode.lowercase()) {
                "on" -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
                "off" -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
                else -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
            }
        }.onFailure { e ->
            statusPanel.status("web dark mode: apply failed: ${e.javaClass.simpleName}")
        }
        // Mariner's note, verbatim in spirit: applies to
        // pages as they load; already-open pages pick it up
        // on their next load. No reload here.
        statusPanel.status("web dark mode: $mode (prefers-color-scheme only)")
    }

    override fun setForceZoom(enabled: Boolean) {
        runCatching {
            runtime.settings.forceUserScalableEnabled = enabled
        }.onFailure { e ->
            statusPanel.status("force zoom: apply failed: ${e.javaClass.simpleName}")
        }
        statusPanel.status("force zoom: ${if (enabled) "on" else "off"}")
    }

    override fun setGlobalPrivacyControl(enabled: Boolean) {
        runCatching {
            runtime.settings.globalPrivacyControl = enabled
        }.onFailure { e ->
            statusPanel.status("GPC: apply failed: ${e.javaClass.simpleName}")
        }
        statusPanel.status("GPC: ${if (enabled) "on" else "off"}")
    }

    override fun applyCookieSettings() {
        runCatching {
            runtime.settings.contentBlocking.cookieBehavior =
                cookieBehaviorForStore()
        }.onFailure { e ->
            statusPanel.status("cookies: apply failed: ${e.javaClass.simpleName}")
        }
    }

    override fun setReducedMotion(enabled: Boolean) {
        tabs.toList().forEach { postReducedMotion(it, enabled) }
        statusPanel.status("reduced motion: ${if (enabled) "on" else "off"}")
    }

    override fun applyWebTextZoom() {
        tabs.toList().forEach { tab ->
            val factor = effectiveZoomFor(tab)
            tab.textZoomFactor = factor
            postZoom(tab, factor)
        }
    }

    override fun applyAutoplay() {
        tabs.toList().forEach { postAutoplayMuted(it) }
    }

    override fun reloadTabsOnHost(host: String) {
        val clean = host.lowercase().trimEnd('.')
        if (clean.isBlank()) return
        tabs.toList().filter { tab ->
            runCatching { Uri.parse(tab.url.value).host }.getOrNull()
                ?.lowercase()?.trimEnd('.') == clean
        }.forEach { tab ->
            // Apply the stored per-site JavaScript value before
            // the reload, so the reload is the only one (the
            // onLocationChange guard would otherwise reload
            // a second time).
            applySiteJavaScript(tab, clean)
            tab.reload()
        }
    }

    /** Set this tab's session JavaScript from the store for
     *  [host], tracking the applied value so onLocationChange
     *  does not reload again for the same host. Returns true
     *  when the value changed. */
    private fun applySiteJavaScript(tab: GeckoTab, host: String): Boolean {
        val wantJs = store.siteSetting(host).javaScript
        if (tab.jsAppliedValue == wantJs) return false
        tab.session.settings.allowJavascript = wantJs
        tab.jsAppliedValue = wantJs
        return true
    }

    override fun syncUserRules() {
        val net = UserBlockRules.parseNet(
            store.setting(UserBlockRules.NET_KEY, "")
        )
        val cosmetic = UserBlockRules.parseCosmetic(
            store.setting(UserBlockRules.COSMETIC_KEY, "")
        )
        blockingObserver.syncUserRules(net, cosmetic)
    }

    override fun refreshProbeRules() {
        blockingObserver.syncBlockingFlags()
    }

    override fun clearGeckoData(flags: Long) {
        runCatching {
            runtime.storageController.clearData(flags)
            statusPanel.status("storage: cleared flags=$flags")
        }.onFailure { e ->
            statusPanel.status("storage: clear failed: ${e.javaClass.simpleName}")
        }
    }

    override fun clearDataForHost(host: String) {
        val clean = host.lowercase().trimEnd('.')
        if (clean.isBlank()) return
        runCatching {
            runtime.storageController.clearDataFromHost(
                clean, StorageController.ClearFlags.SITE_DATA
            )
            statusPanel.status("storage: cleared data for $clean")
        }.onFailure { e ->
            statusPanel.status(
                "storage: clear for $clean failed: ${e.javaClass.simpleName}"
            )
        }
    }

    override fun httpsUpgradeFor(tab: EngineTab, raw: String): String? {
        val own = tab as? GeckoTab ?: return null
        return httpsUpgradeFor(raw, own.httpOnlyHosts)
    }

    /** Parse an ExternalDownload from a WebResponse's headers.
     *  WebResponse extends WebMessage, NOT WebResponseInfo — it
     *  has NO filename/contentLength/contentType fields; those
     *  come from headers, and the v160 content-disposition
     *  validation is absent on 159, so the raw server value is
     *  parsed and sanitised here (Downloads.resolveFileName
     *  applies the final sanitise). */
    internal fun externalDownloadFrom(
        response: org.mozilla.geckoview.WebResponse,
    ): ExternalDownload {
        val headers = response.headers
        fun header(name: String): String? =
            headers.entries.firstOrNull {
                it.key.equals(name, ignoreCase = true)
            }?.value
        val contentType = header("Content-Type")
            ?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
        val contentLength = header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
        val suggestedName = parseContentDisposition(header("Content-Disposition"))
            ?: Uri.parse(response.uri).lastPathSegment
        return ExternalDownload(
            url = response.uri,
            contentType = contentType,
            contentLength = contentLength,
            suggestedName = suggestedName,
            statusCode = response.statusCode,
            isSecure = response.isSecure,
        )
    }

    /** Parse filename from a Content-Disposition header:
     *  RFC 5987 filename*=UTF-8''… first, then a quoted or
     *  bare filename=…. Returns null when there is none. */
    internal fun parseContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val encoded = Regex(
            """filename\*\s*=\s*UTF-8''([^;\s]+)""", RegexOption.IGNORE_CASE
        ).find(header)?.groupValues?.getOrNull(1)
        if (!encoded.isNullOrBlank()) {
            return runCatching {
                java.net.URLDecoder.decode(encoded, "UTF-8")
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        val plain = Regex(
            """filename\s*=\s*"([^"]+)"|filename\s*=\s*([^;\s]+)""",
            RegexOption.IGNORE_CASE
        ).find(header)
        val name = plain?.groupValues?.firstOrNull { it.isNotBlank() && !it.startsWith("filename") }
            ?: plain?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: plain?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
        return name?.trim()?.takeIf { it.isNotBlank() }
    }

    override fun refreshStats() = statusPanel.refreshStats()

    // ------------------------------------------------------------------
    // BlockingHost (blocking bridge -> engine, Worker A)
    // ------------------------------------------------------------------

    /** Engine tab id for the tab currently on [pageHost], or
     *  -1 when no tab — or more than one tab — is on it.
     *  Ambiguity drops the observation; the host must return -1
     *  when two tabs share the host. */
    override fun tabIdForPageHost(pageHost: String): Int {
        val wanted = pageHost.lowercase()
        val matches = tabs.filter { t ->
            runCatching { Uri.parse(t.url.value).host }
                .getOrNull()?.lowercase() == wanted
        }
        return if (matches.size == 1) matches[0].id else -1
    }

    /** A media URL the probe observed on [tabId], for the
     *  engine's media log. The net half sink
     *  (MediaLog.inspect derives kind itself). In practice the
     *  delegate routes `media-net` through noteNetMedia first
     *  (Worker C), so this only fires if the observer is ever
     *  called directly. */
    override fun onMediaObserved(tabId: Int, url: String, kind: String) {
        MediaLog.inspect(tabId, url)
        tabs.firstOrNull { it.id == tabId }?.let { refreshTabMedia(it) }
    }

    /** Every host whose `SiteSetting.disableBlocking` is on. */
    override fun disabledHosts(): Set<String> = store.disabledBlockingHosts()

    /** Mariner-list ad blocking on (`blockAds`, default true). */
    override fun blockAdsEnabled(): Boolean =
        store.setting("blockAds", "true") == "true"

    /** Mariner-list tracker blocking on (`blockTrackers`,
     *  default true). */
    override fun blockTrackersEnabled(): Boolean =
        store.setting("blockTrackers", "true") == "true"

    /** Batch 4: every host whose `SiteSetting.blockImages`
     *  is on. */
    override fun blockImagesHosts(): Set<String> =
        store.siteHosts().filter { store.siteSetting(it).blockImages }.toSet()

    /** Batch 4: the stored referer policy ("", "origin" or
     *  "never"). */
    override fun refererPolicy(): String =
        store.setting("refererPolicy", "")

    override fun noteStatus(line: String) {
        statusPanel.status(line)
    }

    // ProbeHost (probe extension -> engine)
    // ------------------------------------------------------------------

    override fun tabNumberFor(session: GeckoSession?): Int {
        val index = tabs.indexOfFirst { it.session === session }
        return if (index >= 0) index + 1 else -1
    }

    override fun zoomFactorFor(session: GeckoSession?): Float =
        tabs.firstOrNull { it.session === session }
            ?.let { effectiveZoomFor(it) }
            ?: globalWebTextZoom()

    /** The global web text size (webTextZoom, default 100%)
     *  as a factor. */
    private fun globalWebTextZoom(): Float =
        ((store.setting("webTextZoom", "100").toIntOrNull() ?: 100) / 100f)
            .coerceIn(0.5f, 2.0f)

    /** The effective text size for a tab: its host's
     *  per-site override when set, else the global size. */
    private fun effectiveZoomFor(tab: GeckoTab): Float {
        val host = runCatching { Uri.parse(tab.url.value).host }.getOrNull()
        val site = if (!host.isNullOrBlank()) store.siteZoom(host) else 1.0f
        return if (site != 1.0f) site else globalWebTextZoom()
    }

    /** Batch 4: the stored reduced-motion setting. */
    override fun reducedMotionEnabled(): Boolean =
        store.setting("reducedMotion", "false") == "true"

    /** Batch 4: whether media on [host] starts muted — the
     *  per-site autoplay mode when set, else the global
     *  autoplay setting. */
    override fun autoplayMutedFor(host: String): Boolean {
        val site = store.siteAutoplay(host)
        val mode = if (site.isNotBlank()) site
        else store.setting("autoplay", "allow")
        return mode == "muted"
    }

    override fun noteProbeMedia(tabNumber: Int, kind: String, url: String) {
        statusPanel.noteMedia(tabNumber, kind, url)
        // Worker C wiring: the DOM half feeds MediaLog as
        // well as the status panel. MediaLog is keyed by
        // EngineTab.id (not the 1-based tab number), so the
        // tab is resolved from the tabs list.
        val tab = tabs.getOrNull(tabNumber - 1)
        if (tab != null) {
            MediaLog.noteDomItem(tab.id, kind, url)
            refreshTabMedia(tab)
        }
    }

    override fun noteNetMedia(session: GeckoSession?, url: String) {
        // Background-script observations arrive with a null
        // session: attribute to the tab whose current host
        // matches the URL's host, falling back to the active
        // tab (design §7). Unattributable observations are
        // dropped, never guessed — but a host match or the
        // active tab always attributes here, so a drop only
        // happens with no tabs at all.
        val urlHost = runCatching { Uri.parse(url).host }.getOrNull() ?: ""
        val tab = tabs.firstOrNull { it.session === session }
            ?: tabs.firstOrNull { t ->
                urlHost.isNotBlank() &&
                    runCatching { Uri.parse(t.url.value).host }.getOrNull() == urlHost
            }
            ?: currentTab
            ?: return
        MediaLog.inspect(tab.id, url)
        refreshTabMedia(tab)
    }

    override fun noteVideoPopupTap(session: GeckoSession?, url: String) {
        tabs.firstOrNull { it.session === session }?.onVideoPopupTap?.invoke(url)
    }

    override fun noteVideoPopupLongPress(session: GeckoSession?, url: String) {
        tabs.firstOrNull { it.session === session }?.onVideoPopupLongPress?.invoke(url)
    }

    override fun videoPopupOff(host: String): Boolean =
        VideoPopup.isOff(store, host)

    /** Worker A fallthrough: the blocking bridge owns
     *  `blocked`, `cosmetic-query` and `rules-request` (and
     *  returns null for types it does not own, so this is a
     *  pure chain). `media-net` never reaches here — the
     *  Worker C routing above owns it, one sink, no
     *  double-feed. */
    override fun handleBlockingMessage(
        message: JSONObject, senderSession: GeckoSession?
    ): GeckoResult<Any>? =
        blockingObserver.handleNativeMessage(message, senderSession)

    /** The background script's native port
     *  (`sender.session == null`): NOT a tab port — the
     *  bridge stores it and pushes the rule set at once. */
    override fun onBackgroundPortConnected(port: WebExtension.Port) {
        blockingObserver.onBackgroundPortConnected(port)
    }

    override fun onBackgroundPortDisconnected(port: WebExtension.Port) {
        blockingObserver.onBackgroundPortDisconnected(port)
    }

    /** Refresh one tab's EngineTab.detectedMedia from
     *  MediaLog (the merged dom + net + session list). */
    private fun refreshTabMedia(tab: GeckoTab) {
        val merged = MediaLog.forTab(tab.id)
        mainHandler.post { tab.detectedMediaState.value = merged }
    }

    /** Merge one now-playing entry (MediaSession, source
     *  "session") into a tab's log. Set as
     *  MediaSessionHandler.onNowPlaying before registering
     *  each session (Worker C call order). */
    private fun noteSessionMedia(session: GeckoSession, media: DetectedMedia) {
        val tab = tabs.firstOrNull { it.session === session } ?: return
        // A session entry has no stream URL (url == ""): it
        // is a now-playing pointer, never offered to the
        // player. It is recorded in the tab state only when
        // it carries a label; MediaLog has no URL key for it.
        if (media.label.isBlank()) return
        mainHandler.post {
            val current = tab.detectedMediaState.value
            if (current.none { it.source == "session" && it.label == media.label }) {
                tab.detectedMediaState.value = current + media
            }
        }
    }

    override fun registerProbePort(session: GeckoSession?, port: WebExtension.Port) {
        val tab = tabs.firstOrNull { it.session === session }
        if (tab == null) {
            runCatching { port.disconnect() }
            return
        }
        tab.probePort?.let { runCatching { it.disconnect() } }
        tab.probePort = port
        // The freshly connected page gets its effective factor
        // at once, so a live zoom change is not the only path
        // that applies it.
        val zoom = effectiveZoomFor(tab)
        tab.textZoomFactor = zoom
        if (zoom != 1.0f) postZoom(tab, zoom)
        // Batch 4: the freshly connected page also gets the
        // current reduced-motion and autoplay-mute state; the
        // probe asks for both on load too, so a missed push
        // is not fatal.
        postReducedMotion(tab, reducedMotionEnabled())
        postAutoplayMuted(tab)
        // Batch 2: a pending scroll restore (URL-only relaunch
        // restore, pre-alpha.6 saves) rides the port the moment
        // it connects — the content script scrolls after load.
        if (tab.pendingScrollY > 0) {
            val y = tab.pendingScrollY
            tab.pendingScrollY = 0
            runCatching {
                port.postMessage(
                    JSONObject().apply {
                        put("type", "scroll")
                        put("y", y)
                    }
                )
            }.onFailure { e ->
                statusPanel.status("tab ${tab.id}: scroll post failed: ${e.message}")
            }
        }
    }

    override fun unregisterProbePort(port: WebExtension.Port) {
        tabs.firstOrNull { it.probePort === port }?.probePort = null
    }

    /** Push a zoom factor to a tab's live probe port, if one is open. */
    private fun postZoom(tab: GeckoTab, factor: Float) {
        val port = tab.probePort ?: return
        runCatching {
            port.postMessage(
                JSONObject().apply {
                    put("type", "zoom")
                    put("factor", factor.toDouble())
                }
            )
        }.onFailure { e ->
            statusPanel.status("zoom: post to tab ${tab.id} failed: ${e.message}")
        }
    }

    /** Push the reduced-motion setting to a tab's live probe
     *  port, if one is open (Batch 4). */
    private fun postReducedMotion(tab: GeckoTab, enabled: Boolean) {
        val port = tab.probePort ?: return
        runCatching {
            port.postMessage(
                JSONObject().apply {
                    put("type", "reduced-motion")
                    put("on", enabled)
                }
            )
        }.onFailure { e ->
            statusPanel.status(
                "tab ${tab.id}: reduced-motion post failed: ${e.message}"
            )
        }
    }

    /** alpha.10 (Save offline): ask the tab's probe content
     *  script for the page's outerHTML. The script answers
     *  with a `page-html` native message, routed through
     *  [notePageHtml] to [onPageHtml]. No reply if the port
     *  is not connected (page still loading, or a non-web
     *  tab) — the caller toasts that itself. */
    override var onPageHtml: ((EngineTab, String) -> Unit)? = null

    override fun requestPageHtml(tab: EngineTab) {
        // GeckoTab is private-in-class: the public API takes
        // the EngineTab and resolves it by identity.
        val geckoTab = tabs.firstOrNull { it === tab } ?: return
        val port = geckoTab.probePort ?: return
        runCatching {
            port.postMessage(
                JSONObject().apply { put("type", "page-html-request") }
            )
        }.onFailure { e ->
            statusPanel.status(
                "tab ${geckoTab.id}: page-html request failed: ${e.message}"
            )
        }
    }

    override fun notePageHtml(session: GeckoSession?, html: String) {
        val tab = tabs.firstOrNull { it.session === session } ?: return
        onPageHtml?.invoke(tab, html)
    }

    /** Push the autoplay-mute state for this tab's host to
     *  its live probe port, if one is open (Batch 4). */
    private fun postAutoplayMuted(tab: GeckoTab) {
        val port = tab.probePort ?: return
        val host = runCatching { Uri.parse(tab.url.value).host }.getOrNull() ?: ""
        val muted = autoplayMutedFor(host)
        runCatching {
            port.postMessage(
                JSONObject().apply {
                    put("type", "autoplay-muted")
                    put("muted", muted)
                }
            )
        }.onFailure { e ->
            statusPanel.status(
                "tab ${tab.id}: autoplay-muted post failed: ${e.message}"
            )
        }
    }

    // ------------------------------------------------------------------
    // Cast probe (discovery only — no session is ever launched)
    // ------------------------------------------------------------------

    private fun probeCastRoutes() {
        try {
            // Triggers the Cast framework's route provider init.
            CastContext.getSharedInstance(appContext)
            val router = MediaRouter.getInstance(appContext)
            mediaRouter = router
            val selector = MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(CAST_RECEIVER_APP_ID))
                .build()
            val callback = object : MediaRouter.Callback() {
                override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) =
                    reportCastRoutes(router)

                override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) =
                    reportCastRoutes(router)

                override fun onRouteSelected(
                    router: MediaRouter,
                    route: MediaRouter.RouteInfo,
                    reason: Int,
                ) = reportCastRoutes(router)
            }
            castCallback = callback
            router.addCallback(
                selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
            )
            reportCastRoutes(router)
            statusPanel.status("cast: discovery started (Default Media Receiver)")
        } catch (e: Throwable) {
            _castRoutes.value = "cast: probe unavailable (${e.javaClass.simpleName})"
            statusPanel.status("cast: probe failed: ${e.message}")
        }
    }

    /**
     * Gate 1 watch item: the spike logged the same route line on
     * every MediaRouter callback, spamming the panel. Now the state
     * and the status line update ONLY when the route set actually
     * changes (compared as a sorted-name signature).
     */
    private fun reportCastRoutes(router: MediaRouter) {
        val routes = router.routes.filter { it.isEnabled }
        val names = routes.map { it.name ?: "?" }
        val signature = names.sorted().joinToString("|")
        if (signature == lastCastSignature) return
        lastCastSignature = signature
        val joined = names.joinToString(", ")
        _castRoutes.value =
            "cast: ${routes.size} route(s)${if (joined.isNotEmpty()) " — $joined" else ""}"
        statusPanel.status(
            "cast: ${routes.size} route(s)${if (joined.isNotEmpty()) " — $joined" else ""}"
        )
    }

    // ------------------------------------------------------------------
    // Per-tab delegates
    // ------------------------------------------------------------------

    /** Create + wire + bring up a tab for an onNewSession request
     *  and return its (unopened) session for Gecko to open
     *  (alpha.12: handing Gecko an already-open session throws
     *  AssertionError and kills the app). Used directly for
     *  allowed popups and same-site links, and as the factory a
     *  held cross-site tapped link runs at answer time
     *  (alpha.20) — the tab must not exist until he allows it. */
    private fun createNewSessionTab(
        sourceTab: GeckoTab,
        uri: String,
        isPopup: Boolean,
    ): GeckoSession {
        val newTab = createTabInternal(
            sourceTab.isPrivate, isPopup, openSession = false
        ) as GeckoTab
        // The opener (this session) — the popunder guard's
        // hereHost fallback for a fresh popup tab whose own
        // URL is still blank (item 6). Every new-session
        // tab gets one, popup or gesture link alike.
        newTab.openerTab = sourceTab
        newTab.popupTargetUri = uri
        newTab.isNewSessionPending.value = true
        onTabCreated?.invoke(newTab)
        statusPanel.status(
            "tab ${tabNumberFor(sourceTab.session)}: window.open -> tab ${newTab.id}" +
                if (isPopup) " (popup)" else ""
        )
        bringUpNewSessionTab(newTab, uri)
        return newTab.session
    }

    private fun attachDelegates(tab: GeckoTab) {
        val session = tab.session
        fun n() = tabNumberFor(session)

        // ETP telemetry (Worker A): a ContentBlocking
        // onContentBlocked event feeds the same per-tab
        // counters as the Mariner lists (noteContentBlocked).
        // ETP itself is not the blocker — uBlock + Mariner's
        // lists are — so this never runs a separate count.
        // Mapping: ad/analytics lists count as ads; anything
        // else tracked (social, fingerprinting, cryptomining,
        // …) counts as a tracker; a block with no
        // anti-tracking category (e.g. safe browsing) is other.
        session.setContentBlockingDelegate(object : ContentBlocking.Delegate {
            override fun onContentBlocked(
                session: GeckoSession,
                event: ContentBlocking.BlockEvent,
            ) {
                if (!event.isBlocking()) return
                val own = tabs.firstOrNull { it.session === session } ?: return
                val at = event.antiTrackingCategory
                val category = when {
                    at and (ContentBlocking.AntiTracking.AD or
                        ContentBlocking.AntiTracking.ANALYTIC) != 0 ->
                        FilterEngine.CAT_ADS
                    at == 0 -> FilterEngine.CAT_OTHER
                    else -> FilterEngine.CAT_TRACKER
                }
                blockingObserver.noteContentBlocked(own.id, category)
            }
        })

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                // Committed top-level navigation only (subframes never
                // produce onLocationChange). about:blank reads as "".
                tab.url.value = if (url == "about:blank") "" else (url ?: "")
                currentTab = tab
                val u = url ?: return
                if (u == tab.lastNavigatedUrl) return
                if (u == "about:blank") return
                if (!u.startsWith("http://") && !u.startsWith("https://")) return
                tab.lastNavigatedUrl = u
                tab.onNavigate?.invoke(u, tab.title.value)
                // Batch 4: per-site JavaScript. The load path
                // (GeckoTab.load) applies the value before
                // loadUri; this covers loads the engine did not
                // start (window.open / redirects). Guarded on
                // the tracked value: without it the reload
                // would re-trigger this effect and loop.
                val host = runCatching { Uri.parse(u).host }.getOrNull()
                if (!host.isNullOrBlank() && applySiteJavaScript(tab, host)) {
                    tab.reload()
                }
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny>? {
                // Supplementary media log: navigations + iframe loads.
                // The probe extension is the real media detector.
                if (isMediaUrl(request.uri)) {
                    statusPanel.noteMedia(n(), "nav", request.uri)
                }
                // Batch 4: HTTPS-only mode + open-links-in-new-tab
                // (Mariner BrowserScreen.kt:2653-2700, Gecko-shaped).
                // Both run before the popunder guard: an upgrade
                // or a new-tab split is not a popunder. HTTPS-only
                // runs first, as in Mariner — a tapped http link
                // upgrades in place rather than splitting.
                if (request.target ==
                    GeckoSession.NavigationDelegate.TARGET_WINDOW_CURRENT &&
                    (request.uri.startsWith("http://") ||
                        request.uri.startsWith("https://"))
                ) {
                    val u = request.uri
                    // HTTPS-only: in-page loads (not direct
                    // navigations — typed addresses / app loads /
                    // the user's confirmed "Continue for this
                    // site" load) of http:// upgrade, or warn.
                    if (store.setting("httpsOnly", "false") == "true" &&
                        !request.isDirectNavigation &&
                        u.startsWith("http://")
                    ) {
                        val host = runCatching { Uri.parse(u).host }.getOrNull() ?: ""
                        if (!store.isHttpsException(host)) {
                            val upgraded = httpsUpgradeFor(u, tab.httpOnlyHosts)
                            if (upgraded != null) {
                                tab.upgradedFromHttp = upgraded
                                mainHandler.post { tab.load(upgraded) }
                                statusPanel.status(
                                    "tab ${n()}: https-only upgrade: $u"
                                )
                            } else {
                                statusPanel.status(
                                    "tab ${n()}: https-only warn: $u"
                                )
                                onHttpsWarn?.invoke(u)
                            }
                            return GeckoResult.fromValue(AllowOrDeny.DENY)
                        }
                    }
                    // Open links in a new tab: a plain tap on a
                    // link opens it in a foreground tab of the
                    // same privacy class instead of navigating
                    // here. Same-page anchors stay put, and
                    // _blank links never reach here (onNewSession
                    // owns them).
                    if (store.setting("openLinksInNewTab", "false") == "true" &&
                        request.hasUserGesture &&
                        !isAnchorLink(tab.url.value, u)
                    ) {
                        val newTab = createTabInternal(tab.isPrivate, false)
                        onTabCreated?.invoke(newTab)
                        mainHandler.post { newTab.load(u) }
                        statusPanel.status(
                            "tab ${n()}: link opened in new tab: $u"
                        )
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }
                }
                // Strict popup blocker — popunder guard (Mariner
                // BrowserScreen.kt:2695+, adapted to GeckoView):
                // a gesture-less main-frame hop to a different
                // host covered by FilterEngine.isDocumentBlocked
                // is captured as a blocked popup instead of
                // loading. onLoadRequest fires before each
                // top-level page load (GeckoView doc), so this
                // is Mariner's shouldOverrideUrlLoading point.
                // Typed addresses / app loads (isDirectNavigation)
                // and tapped links (hasUserGesture) never pass
                // through here; redirects to a blocked host are
                // caught exactly as Mariner catches them.
                // The "here" host is the page the hop came from. A
                // fresh popup tab has no committed URL yet, so its
                // own host is blank and the guard could never fire
                // there (item 6) — fall back to the opener tab's
                // host, so redirect hops inside a popup tab's load
                // chain are capturable too (deny + capture with
                // notice, not silent deny). Once the tab commits
                // its own URL, that host takes over.
                if (request.target ==
                    GeckoSession.NavigationDelegate.TARGET_WINDOW_CURRENT &&
                    !request.hasUserGesture && !request.isDirectNavigation
                ) {
                    val u = request.uri
                    if ((u.startsWith("http://") || u.startsWith("https://"))) {
                        val targetHost = runCatching { Uri.parse(u).host }.getOrNull() ?: ""
                        val hereHost = runCatching {
                            Uri.parse(tab.url.value).host
                        }.getOrNull()?.takeIf { it.isNotBlank() }
                            ?: runCatching {
                                Uri.parse(tab.openerTab?.url?.value ?: "").host
                            }.getOrNull()?.takeIf { it.isNotBlank() }
                            ?: ""
                        if (targetHost.isNotBlank() && hereHost.isNotBlank() &&
                            targetHost != hereHost &&
                            FilterEngine.isDocumentBlocked(u, hereHost)
                        ) {
                            // Second capture source (item 5): the
                            // guard's capture goes into the
                            // controller's one queue, with the
                            // notice, instead of a separate list
                            // the shell had to merge.
                            popupController.notePopunder(u, hereHost)
                            statusPanel.status(
                                "tab ${n()}: popunder blocked: $u"
                            )
                            return GeckoResult.fromValue(AllowOrDeny.DENY)
                        }
                    }
                }
                return null // allow
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                tab.canGoBack.value = canGoBack
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                tab.canGoForward.value = canGoForward
            }

            override fun onNewSession(
                session: GeckoSession,
                uri: String,
            ): GeckoResult<GeckoSession>? {
                // target=_blank / window.open: the engine creates the
                // tab itself and reports it via Engine.onTabCreated.
                // NEVER call load here — but see bringUpNewSessionTab
                // below: the API evidence does not settle whether
                // Gecko loads the URI into the returned session (the
                // javadoc is silent; the alpha.12 video shows an
                // empty tab), so the app loads it itself after
                // Gecko's open, ONLY while the session is still
                // blank. A private parent opens a private child;
                // null would make window.open() fail.
                // Popup windows: the controller matches this
                // onNewSession against a pending allowed popup
                // (rebuild item 1) and the new tab is marked
                // isPopupWin=true (Mariner TabList.isPopupWin) so
                // onCloseWindow can close it.
                val isPopup = popupController.matchNewSession(uri) != null
                if (!isPopup) {
                    val sourceHost = runCatching {
                        Uri.parse(tab.url.value).host
                    }.getOrNull() ?: ""
                    val targetHost = runCatching {
                        Uri.parse(uri).host
                    }.getOrNull() ?: ""
                    if (isCrossSiteLink(sourceHost, targetHost)) {
                        // A tapped link to a different site
                        // (alpha.20): hold it for the four-choice
                        // prompt, exactly like a scripted popup.
                        // The tab is created only if he allows
                        // it — createNewSessionTab runs at answer
                        // time, not here. Same-site links open
                        // directly, as before.
                        return popupController.onGestureRequest(
                            PopupController.SourceTab(
                                tab.id, sourceHost, tab.isPrivate
                            ),
                            uri,
                        ) { createNewSessionTab(tab, uri, isPopup = false) }
                    }
                    // A tapped link from a blacklisted site: deny
                    // it silently (alpha.21) — no tab is created,
                    // no capture, no notice. Tapping a link on
                    // a site he distrusts is not consent for its
                    // popups to open.
                    if (!popupController.gestureAllowed(sourceHost)) {
                        popupController.noteGestureDenied(uri, sourceHost)
                        return null
                    }
                }
                return GeckoResult.fromValue(
                    createNewSessionTab(tab, uri, isPopup)
                )
            }
        }

        // Batch 1: root scroll offset for the shell's
        // scroll-hide + pull-to-refresh (Mariner
        // BrowserScreen.kt:2604, WebView scroll listener).
        // GeckoView has no View scrollY of its own; the
        // session's ScrollDelegate reports the content
        // scroll in CSS px (verified against the pinned
        // GeckoView 159 AAR: ScrollDelegate.onScrollChanged
        // (session, scrollX, scrollY)).
        session.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                tab.scrollY.value = scrollY
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tab.isLoading.value = true
                // A load has started: the new-session bring-up must
                // not load the URI again (items 3 + 4). This also
                // ends the pending state, so the loading overlay
                // gives way to the page.
                tab.loadStarted = true
                tab.isNewSessionPending.value = false
                tab.progress.value = 0
                statusPanel.status("tab ${n()}: loading $url")
                // Batch 4: a new load that is not the pending
                // upgrade clears the marker.
                if (tab.upgradedFromHttp != null && tab.upgradedFromHttp != url) {
                    tab.upgradedFromHttp = null
                }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tab.isLoading.value = false
                tab.progress.value = 100
                statusPanel.status("tab ${n()}: load ${if (success) "OK" else "FAILED"}")
                // Batch 4: a failed HTTPS-only upgrade means the
                // host does not serve https — remember it on the
                // tab, so the next attempt warns instead of
                // looping through the upgrade. A success clears
                // the marker.
                val upgraded = tab.upgradedFromHttp
                if (upgraded != null) {
                    tab.upgradedFromHttp = null
                    if (!success) {
                        val host = runCatching {
                            Uri.parse(upgraded).host
                        }.getOrNull()
                        if (!host.isNullOrBlank()) {
                            tab.httpOnlyHosts.add(host.lowercase())
                            statusPanel.status(
                                "tab ${n()}: https upgrade failed for $host — " +
                                    "will warn next time"
                            )
                        }
                    }
                }
                statusPanel.refreshStats()
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                tab.progress.value = progress
            }

            // Batch 2: the session state is pushed on navigation,
            // scrolling and form-data changes; it carries the
            // back/forward history (for the history sheet) and,
            // as a JSON string, the whole restorable tab (for
            // relaunch restore). Kept per tab, memory-only.
            override fun onSessionStateChange(
                session: GeckoSession,
                state: GeckoSession.SessionState,
            ) {
                val current = state.currentIndex
                tab.historyEntries.value = (0 until state.size).map { i ->
                    val item = state[i]
                    SessionHistoryEntry(
                        title = item.title ?: "",
                        url = item.uri ?: "",
                        index = i,
                        isCurrent = i == current
                    )
                }
                tab.sessionStateJson.value = state.toString()
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                title?.let { tab.title.value = it }
            }

            override fun onFirstComposite(session: GeckoSession) {
                statusPanel.noteFirstComposite()
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                statusPanel.status("tab ${n()}: fullscreen ${if (fullScreen) "ON" else "OFF"}")
                // §8.6: the page's fullscreen element stays
                // inside the GeckoView — the app only changes
                // the chrome around it. The shell hides the
                // browser chrome from isFullscreen; Fullscreen.kt
                // hides / restores the system bars.
                tab.isFullscreenState.value = fullScreen
                val act = activity
                if (act != null) {
                    if (fullScreen) Fullscreen.enter(act) else Fullscreen.exit(act)
                }
            }

            override fun onExternalResponse(
                session: GeckoSession,
                response: org.mozilla.geckoview.WebResponse,
            ) {
                // Downloads are an app-side stack, not an engine
                // feature: onExternalResponse only REPORTS the
                // download. The engine NEVER shows UI — the shell
                // shows Mariner's confirm dialog and calls
                // Downloads.start. The response body MUST be
                // closed even when ignored, or the connection
                // stays open until GC (API doc §1a).
                runCatching { response.body?.close() }
                val download = externalDownloadFrom(response)
                statusPanel.status(
                    "tab ${n()}: external response: ${download.url} " +
                        "(${download.contentType ?: "?"}, ${download.contentLength})"
                )
                onExternalDownload?.invoke(tab, download)
            }

            override fun onCloseRequest(session: GeckoSession) {
                // window.close() from an allowed popup: honour
                // it by closing its tab (Mariner onCloseWindow),
                // or the opener hangs waiting on a dead window.
                // Only popup tabs close this way.
                if (tab.isPopupWin) {
                    statusPanel.status("tab ${n()}: popup close requested")
                    val wasSelected = selectedTab === tab
                    destroyTab(tab)
                    // Rebuild item 8: a self-closing popup tab used
                    // to vanish without a trace. Leave a status line
                    // always, and a "Popup closed" trace in the shell
                    // when it was the tab on screen.
                    statusPanel.status("tab ${n()}: popup closed")
                    if (wasSelected) onPopupClosed?.invoke(tab)
                }
            }

            override fun onCrash(session: GeckoSession) {
                statusPanel.status("tab ${n()}: SESSION CRASHED")
                tab.onCrash?.invoke()
            }

            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement,
            ) {
                // Link detection is element.linkUri != null (research
                // §6); type only distinguishes image/video/audio/none.
                // Batch 4: the image URL rides along (TYPE_IMAGE +
                // srcUri) so the sheet can offer "View image",
                // "Download" and "Block this element".
                val imageUrl = if (
                    element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE
                ) element.srcUri else null
                if (element.linkUri != null || imageUrl != null) {
                    tab.onLongPressTarget?.invoke(
                        LongPressTarget(element.linkUri, imageUrl)
                    )
                }
            }
        }

        // Phase 3: the engine's own permission / prompt
        // delegates replace the spike's inline posture (which
        // denied camera/mic/location outright and granted DRM /
        // autoplay / storage silently). See
        // PermissionDelegate.kt / PromptDelegate.kt.
        session.permissionDelegate = permissionDelegate
        session.promptDelegate = promptDelegate

        // Phase 3 (Worker C call order): set the now-playing
        // sink FIRST, then register once per tab session, on
        // the UI thread (setMediaSessionDelegate became
        // @UiThread in v154). attachDelegates runs on the UI
        // thread (createTab is called from the shell).
        MediaSessionHandler.onNowPlaying = { s, media -> noteSessionMedia(s, media) }
        MediaSessionHandler.register(session)
    }

    /**
     * Bring up a tab returned from onNewSession (rebuild items
     * 3 + 4, alpha.13): Gecko opens the session itself, on its
     * own thread, so the app cannot activate it or load into it
     * synchronously. 500ms later — a generous margin for a
     * message round-trip — check what Gecko did:
     *  - session open → mark it opened (the shell binds the view
     *    on that signal) and, if the tab is still selected,
     *    activate + focus it;
     *  - no load started and the session still blank → Gecko did
     *    NOT load the URI (the API evidence never settled this;
     *    the alpha.12 video shows an empty tab), so load it from
     *    the app. Correct whether or not Gecko loads it: when
     *    Gecko does load, onPageStart has already fired and the
     *    load is skipped — never a double load;
     *  - session still not open → log it and clear the pending
     *    flag, so the tab falls back to the start page instead
     *    of a permanent spinner.
     * The Copy-status discriminator lines for the device test:
     * "tab M: window.open -> tab N" (onNewSession, above), then
     * either "tab N: loading <url>" + "load OK/FAILED" (Gecko
     * loaded it) or "tab N: Gecko did not load the new-session
     * URI — loading it" + the same loading lines (the app
     * loaded it). Robert runs this check on alpha.13 (the
     * alpha.12 check never happened).
     */
    private fun bringUpNewSessionTab(newTab: GeckoTab, uri: String) {
        mainHandler.postDelayed({
            val tab = tabs.firstOrNull { it.id == newTab.id }
                ?: return@postDelayed
            if (!tab.session.isOpen) {
                statusPanel.status(
                    "tab ${tab.id}: new-session session still not open"
                )
                tab.isNewSessionPending.value = false
                return@postDelayed
            }
            tab.sessionOpened.value = true
            if (selectedTab === tab) {
                runCatching {
                    tab.session.setActive(true)
                    tab.session.setFocused(true)
                }
            }
            if (!tab.loadStarted && tab.url.value.isBlank() &&
                uri.isNotBlank() && uri != "about:blank"
            ) {
                statusPanel.status(
                    "tab ${tab.id}: Gecko did not load the " +
                        "new-session URI — loading it"
                )
                tab.loadInternal(uri, makeCurrent = selectedTab === tab)
            }
            tab.isNewSessionPending.value = false
        }, 500)
    }

    // ------------------------------------------------------------------
    // One tab
    // ------------------------------------------------------------------

    private inner class GeckoTab(
        override val id: Int,
        override val isPrivate: Boolean,
        override val session: GeckoSession,
        override val isPopupWin: Boolean = false,
    ) : EngineTab {

        override val url = mutableStateOf("")
        override val title = mutableStateOf("")
        override val canGoBack = mutableStateOf(false)
        override val canGoForward = mutableStateOf(false)
        override val isLoading = mutableStateOf(false)
        override val progress = mutableIntStateOf(0)
        override val scrollY = mutableIntStateOf(0)
        override val findResult = mutableStateOf<FindResult?>(null)
        override val isDesktopMode = mutableStateOf(false)

        /** True from an onNewSession creation until the first load
         *  starts (or the bring-up resolves): the shell shows a
         *  loading state for the tab instead of the start page
         *  (rebuild item 4). */
        override val isNewSessionPending = mutableStateOf(false)

        /** True once the session is open: the shell binds the view
         *  on this signal (rebuild items 4 + 7). Set by the engine
         *  when it opens the session, and by the bring-up when it
         *  observes Gecko's open. */
        override val sessionOpened = mutableStateOf(false)

        // ---- Batch 2: session history + session state ----
        override val historyEntries =
            mutableStateOf<List<SessionHistoryEntry>>(emptyList())
        override val sessionStateJson = mutableStateOf<String?>(null)

        /** Pending scroll restore for a URL-only relaunch
         *  restore (pre-alpha.6 save): posted to the probe
         *  port when it connects, then cleared. */
        var pendingScrollY: Int = 0

        override fun goToHistoryIndex(index: Int) {
            runCatching { session.gotoHistoryIndex(index) }
        }

        override fun flushSessionState() {
            runCatching { session.flushSessionState() }
        }

        override var onNavigate: ((String, String) -> Unit)? = null
        override var onLongPressTarget: ((LongPressTarget) -> Unit)? = null
        override var onCrash: (() -> Unit)? = null

        // ---- Phase 3: media / fullscreen / popups ----
        internal val detectedMediaState = mutableStateOf<List<DetectedMedia>>(emptyList())
        override val detectedMedia: State<List<DetectedMedia>> get() = detectedMediaState
        override var onVideoPopupTap: ((String) -> Unit)? = null
        override var onVideoPopupLongPress: ((String) -> Unit)? = null
        internal val isFullscreenState = mutableStateOf(false)
        override val isFullscreen: State<Boolean> get() = isFullscreenState

        override fun runMediaScan() {
            // Ask the probe for a fresh DOM scan now. The
            // probe content script receives app→page messages
            // on its native port (the "zoom" channel); a
            // "media-scan" port message is posted here and
            // the probe re-reports via `media-probe`.
            // NOTE (integration): probe.js currently handles
            // only "zoom" on the port — the "media-scan"
            // handler is a 3-line addition in C's probe.js;
            // until it lands, the scan re-runs on the next
            // page load / phase report, and the sheet reads
            // the already-merged MediaLog list.
            val port = probePort
            if (port != null) {
                runCatching {
                    port.postMessage(
                        JSONObject().apply { put("type", "media-scan") }
                    )
                }.onFailure { e ->
                    statusPanel.status("tab $id: media-scan post failed: ${e.message}")
                }
            }
            // Refresh from the merged log either way, so the
            // sheet never shows a stale list after a tap.
            refreshTabMedia(this)
        }

        /** The tab that opened this one (onNewSession's session) —
         *  the popunder guard's hereHost fallback (item 6). Null
         *  for shell-created tabs. */
        var openerTab: GeckoTab? = null

        /** The URI onNewSession was asked to open — the bring-up
         *  loads it when Gecko did not (items 3 + 4). */
        var popupTargetUri: String? = null

        /** True once a load has started on this tab (onPageStart).
         *  The new-session bring-up consults it: a started load
         *  means Gecko loaded the URI, so the app must not load
         *  it again. */
        var loadStarted: Boolean = false

        /** Last URL onNavigate fired for (dedupe same-URL repeats). */
        var lastNavigatedUrl: String? = null

        /** Stored per-site text-zoom factor; the probe content script
         * applies it (see Extensions.kt). */
        var textZoomFactor: Float = 1.0f

        /** Batch 4: hosts where an HTTPS-only upgrade failed —
         *  the next attempt warns instead of upgrading
         *  (Mariner TabState.httpOnlyHosts). */
        val httpOnlyHosts = mutableSetOf<String>()

        /** Batch 4: the https URL loaded as an HTTPS-only
         *  upgrade, until its load settles (onPageStop). */
        var upgradedFromHttp: String? = null

        /** Batch 4: the per-site JavaScript value last applied
         *  to this session (null = never applied — the session
         *  default, allow, stands). */
        var jsAppliedValue: Boolean? = null

        /** Native port the probe content script opened in this tab's
         * page, for live zoom pushes. Null until the page loads. */
        var probePort: WebExtension.Port? = null

        override fun load(url: String) {
            loadInternal(url, makeCurrent = true)
        }

        /** The load itself: the session must be fully open, active
         *  and focused BEFORE the first loadUri (first-load watch
         *  item). makeCurrent=false (new-session bring-up, items
         *  3 + 4) skips the current-tab bookkeeping, so a
         *  background popup tab never steals the active tab —
         *  the bring-up activates + focuses it itself, and only
         *  when it is still selected. */
        internal fun loadInternal(url: String, makeCurrent: Boolean) {
            // First-load watch item: the session must be fully open,
            // active and focused BEFORE the first loadUri.
            if (!session.isOpen) {
                session.open(runtime)
                sessionOpened.value = true
            }
            if (makeCurrent) {
                session.setActive(true)
                session.setFocused(true)
                currentTab = this
            }
            // Batch 4: per-site JavaScript, applied before the
            // load so the page's scripts see the right value.
            val host = runCatching { Uri.parse(url).host }.getOrNull()
            if (!host.isNullOrBlank()) applySiteJavaScript(this, host)
            session.loadUri(url)
        }

        override fun reload() = session.reload()
        override fun stop() = session.stop()
        override fun goBack() = session.goBack()
        override fun goForward() = session.goForward()

        override fun find(query: String) {
            if (query.isEmpty()) {
                clearFind()
                return
            }
            session.finder.setDisplayFlags(GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL)
            session.finder.find(query, GeckoSession.FINDER_FIND_FORWARD)
                .accept(
                    { result -> postFindResult(result) },
                    { _ -> postFindResult(null) },
                )
        }

        override fun findNext(forward: Boolean) {
            val flags = if (forward) {
                GeckoSession.FINDER_FIND_FORWARD
            } else {
                GeckoSession.FINDER_FIND_BACKWARDS
            }
            // searchString == null: find again with the previous string.
            session.finder.find(null, flags)
                .accept(
                    { result -> postFindResult(result) },
                    { _ -> postFindResult(null) },
                )
        }

        override fun clearFind() {
            session.finder.clear()
            findResult.value = null
        }

        /**
         * Finder results arrive asynchronously; state updates go to
         * the main thread. FinderResult.current is 1-based, so the
         * ordinal is current - 1 (0-based — the shell displays
         * ordinal + 1 / count). FinderResult.total may be -1
         * (unknown) and is passed through as-is.
         */
        private fun postFindResult(result: GeckoSession.FinderResult?) {
            val mapped = if (result == null) {
                FindResult(ordinal = 0, count = -1, found = false)
            } else {
                FindResult(
                    ordinal = result.current - 1,
                    count = result.total,
                    found = result.found,
                )
            }
            mainHandler.post { findResult.value = mapped }
        }

        override fun setTextZoom(factor: Float) {
            textZoomFactor = factor
            // Content-script route (research §2 + §8): the probe
            // applies documentElement font-size scaling on a "zoom"
            // native message. No runtime-global fallback is used —
            // setFontSizeFactor is NOT per-site, so it would change
            // every tab at once. If no port is open yet (page still
            // loading), the content script picks the stored factor up
            // itself via a "zoom-query" after load.
            postZoom(this, factor)
        }

        override fun setDesktopMode(desktop: Boolean) {
            val settings = session.settings
            if (desktop) {
                // Both (research §5 / bug 1803740): viewport alone
                // still redirects to m.*.
                settings.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                settings.viewportMode = GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            } else {
                settings.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                settings.viewportMode = GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            }
            isDesktopMode.value = desktop
            session.reload()
        }

        override fun close() {
            destroyTab(this)
        }
    }

    private fun isMediaUrl(url: String): Boolean =
        Regex("""\.(m3u8|mpd|mp4|webm|m4s|mp3|aac|ogg|oga|wav|ts)(\?|#|$)""", RegexOption.IGNORE_CASE)
            .containsMatchIn(url)
}
