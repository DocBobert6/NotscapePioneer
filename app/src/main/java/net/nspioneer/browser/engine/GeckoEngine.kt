package net.nspioneer.browser.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
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
class GeckoEngine(context: Context) : Engine, ProbeHost {

    companion object {
        private const val CAST_RECEIVER_APP_ID = "CC1AD845" // Default Media Receiver
    }

    private val appContext = context.applicationContext
    private val statusPanel = StatusPanel(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

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

    override var onTabCreated: ((EngineTab) -> Unit)? = null
    override var onExtensionInstallPrompt: ((String, List<String>, (Boolean) -> Unit) -> Unit)? = null

    override val uBlockState: State<String> get() = extensions.uBlockState
    override val castRoutes: State<String> get() = _castRoutes
    private val _castRoutes = mutableStateOf("cast: not probed yet")
    override val statusLines get() = statusPanel.statusLines
    override val mediaLines get() = statusPanel.mediaLines

    private var mediaRouter: MediaRouter? = null
    private var castCallback: MediaRouter.Callback? = null

    /** Last Cast route signature (sorted names) — the dedupe key. */
    private var lastCastSignature: String? = null

    init {
        extensions.installProbeExtension()
        probeCastRoutes()
        statusPanel.refreshStats()
    }

    // ------------------------------------------------------------------
    // Runtime
    // ------------------------------------------------------------------

    private fun createRuntime(): GeckoRuntime {
        val settings = GeckoRuntimeSettings.Builder()
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                    .build()
            )
            .build()
        return GeckoRuntime.create(appContext, settings)
    }

    // ------------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------------

    override fun createTab(isPrivate: Boolean): EngineTab {
        val session = if (isPrivate) {
            GeckoSession(
                GeckoSessionSettings.Builder().usePrivateMode(true).build()
            )
        } else {
            GeckoSession()
        }
        val tab = GeckoTab(nextTabId++, isPrivate, session)
        attachDelegates(tab)
        // The session is fully open before any load can be issued
        // (first-load watch item: the spike loaded immediately after
        // open, before setActive/setFocused and before any view).
        session.open(runtime)
        tabs += tab
        extensions.registerSession(session)
        return tab
    }

    override fun destroyTab(tab: EngineTab) {
        val own = tab as? GeckoTab ?: return
        tabs.remove(own)
        if (currentTab === own) currentTab = null
        own.probePort?.let { runCatching { it.disconnect() } }
        own.probePort = null
        runCatching { own.session.close() }
    }

    override fun close() {
        if (closed) return
        closed = true
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
        // Research §7: the level only takes effect if the cookie
        // behaviour is ACCEPT_NON_TRACKERS (or
        // ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS). If something ever
        // leaves it at ACCEPT_ALL, changing the level would be a
        // silent no-op — restore the working behaviour first.
        if (cb.cookieBehavior == ContentBlocking.CookieBehavior.ACCEPT_ALL) {
            cb.cookieBehavior = ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS
        }
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

    override fun refreshStats() = statusPanel.refreshStats()

    // ------------------------------------------------------------------
    // ProbeHost (probe extension -> engine)
    // ------------------------------------------------------------------

    override fun tabNumberFor(session: GeckoSession?): Int {
        val index = tabs.indexOfFirst { it.session === session }
        return if (index >= 0) index + 1 else -1
    }

    override fun zoomFactorFor(session: GeckoSession?): Float =
        tabs.firstOrNull { it.session === session }?.textZoomFactor ?: 1.0f

    override fun noteProbeMedia(tabNumber: Int, kind: String, url: String) {
        statusPanel.noteMedia(tabNumber, kind, url)
    }

    override fun registerProbePort(session: GeckoSession?, port: WebExtension.Port) {
        val tab = tabs.firstOrNull { it.session === session }
        if (tab == null) {
            runCatching { port.disconnect() }
            return
        }
        tab.probePort?.let { runCatching { it.disconnect() } }
        tab.probePort = port
        // The freshly connected page gets its stored factor at once,
        // so a live zoom change is not the only path that applies it.
        if (tab.textZoomFactor != 1.0f) postZoom(tab, tab.textZoomFactor)
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

    private fun attachDelegates(tab: GeckoTab) {
        val session = tab.session
        fun n() = tabNumberFor(session)

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
                // NEVER call load here; Gecko loads the URI into the
                // returned session. A private parent opens a private
                // child; null would make window.open() fail.
                val newTab = createTab(tab.isPrivate) as GeckoTab
                onTabCreated?.invoke(newTab)
                statusPanel.status("tab ${n()}: window.open -> tab ${newTab.id}")
                return GeckoResult.fromValue(newTab.session)
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tab.isLoading.value = true
                tab.progress.value = 0
                statusPanel.status("tab ${n()}: loading $url")
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tab.isLoading.value = false
                tab.progress.value = 100
                statusPanel.status("tab ${n()}: load ${if (success) "OK" else "FAILED"}")
                statusPanel.refreshStats()
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                tab.progress.value = progress
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
                element.linkUri?.let { tab.onLongPressLink?.invoke(it) }
            }
        }

        // Spike posture: grant what the engine needs to measure (DRM
        // media keys, autoplay, persistent storage); deny the rest.
        // No silent camera/mic/location — denied outright here.
        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<String>?,
                callback: GeckoSession.PermissionDelegate.Callback,
            ) {
                callback.reject()
            }

            override fun onContentPermissionRequest(
                session: GeckoSession,
                request: GeckoSession.PermissionDelegate.ContentPermission,
            ): GeckoResult<Int>? {
                val allow = when (request.permission) {
                    GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS,
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
                    GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE ->
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    else ->
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                }
                return GeckoResult.fromValue(allow)
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback,
            ) {
                callback.reject() // no camera/mic, ever
            }
        }
    }

    // ------------------------------------------------------------------
    // One tab
    // ------------------------------------------------------------------

    private inner class GeckoTab(
        override val id: Int,
        override val isPrivate: Boolean,
        override val session: GeckoSession,
    ) : EngineTab {

        override val url = mutableStateOf("")
        override val title = mutableStateOf("")
        override val canGoBack = mutableStateOf(false)
        override val canGoForward = mutableStateOf(false)
        override val isLoading = mutableStateOf(false)
        override val progress = mutableIntStateOf(0)
        override val findResult = mutableStateOf<FindResult?>(null)
        override val isDesktopMode = mutableStateOf(false)

        override var onNavigate: ((String, String) -> Unit)? = null
        override var onLongPressLink: ((String) -> Unit)? = null
        override var onCrash: (() -> Unit)? = null

        /** Last URL onNavigate fired for (dedupe same-URL repeats). */
        var lastNavigatedUrl: String? = null

        /** Stored per-site text-zoom factor; the probe content script
         * applies it (see Extensions.kt). */
        var textZoomFactor: Float = 1.0f

        /** Native port the probe content script opened in this tab's
         * page, for live zoom pushes. Null until the page loads. */
        var probePort: WebExtension.Port? = null

        override fun load(url: String) {
            // First-load watch item: the session must be fully open,
            // active and focused BEFORE the first loadUri.
            if (!session.isOpen) session.open(runtime)
            session.setActive(true)
            session.setFocused(true)
            currentTab = this
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
