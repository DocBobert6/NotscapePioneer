package net.nspioneer.browser.engine
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.mozilla.geckoview.GeckoSession

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
