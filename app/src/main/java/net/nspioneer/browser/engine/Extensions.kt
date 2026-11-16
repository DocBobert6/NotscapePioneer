package net.nspioneer.browser.engine

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * Notscape Pioneer — Phase 2 extensions.
 *
 * Carried from the Phase 1 spike (MainActivity, commit 4609fbb5):
 * the built-in probe extension (media + EME reporting) and the
 * approved-extension installs from AMO (see ApprovedExtensions).
 * One deliberate change from the
 * spike: the spike AUTO-APPROVED the uBlock install prompt. Phase 2
 * NEVER auto-approves — [WebExtensionController.PromptDelegate]
 * calls [Engine.onExtensionInstallPrompt] and waits for the shell's
 * decision (Install / Later) before completing the prompt result.
 *
 * Zoom (research §2 + §8 — no per-session text-zoom API, no JS eval):
 * the probe content script opens a native port
 * (`browser.runtime.connectNative("pioneer")`) and listens for
 * {"type":"zoom","factor":N}; the app posts that message on the
 * port belonging to the tab's session ([ProbeHost.registerProbePort],
 * keyed by `port.sender.session`). Because a port only exists while
 * its page is loaded, the content script also asks for its tab's
 * stored factor after every (re)load via a "zoom-query" native
 * message — the reply is the [WebExtension.MessageDelegate.onMessage]
 * return value, which resolves the script's sendNativeMessage
 * promise. If the port is unavailable (e.g. connectNative refused),
 * a live zoom change applies on the next navigation instead; that
 * is an on-device verification item, not a silent fallback.
 */
class ExtensionManager(
    private val runtime: GeckoRuntime,
    private val status: StatusPanel,
    private val host: ProbeHost,
    /** All sessions currently owned by the engine (tabs list). */
    private val sessionProvider: () -> List<GeckoSession>,
    /** Read at prompt time, so the shell may set it after construction. */
    private val promptProvider: () -> ((String, List<String>, (Boolean) -> Unit) -> Unit)?,
) {

    companion object {
        private const val PROBE_LOCATION = "resource://android/assets/extensions/pioneer-probe/"
        private const val PROBE_ID = "pioneer-probe@pioneer"
        const val PROBE_APP = "pioneer"
        // Alpha.52: the MAIN-world experiment extension (own
        // manifest asking for "world": "MAIN" + main-world.js).
        private const val MAIN_WORLD_LOCATION =
            "resource://android/assets/extensions/pioneer-main-world/"
        private const val MAIN_WORLD_ID = "pioneer-main-world@pioneer"
    }

    val uBlockState = mutableStateOf("not installed")
    val googleSearchFixerState = mutableStateOf("not installed")

    private var probeExtension: WebExtension? = null

    /** Alpha.52: the experiment extension once installed, and
     *  whether the install was attempted this session (at most
     *  once — a flag-off call must not consume the attempt). */
    private var mainWorldExtension: WebExtension? = null
    private var mainWorldAttempted = false

    /** Alpha.52: true once the experiment extension installed
     *  — the page-load arming only starts the bounded wait
     *  after this, so a load that merely triggered the install
     *  cannot time out before the script ever had a page. */
    fun isMainWorldInstalled(): Boolean = mainWorldExtension != null

    // ------------------------------------------------------------------
    // Probe extension (own built-in, media + EME reporting + zoom)
    // ------------------------------------------------------------------

    fun installProbeExtension() {
        try {
            runtime.webExtensionController
                .ensureBuiltIn(PROBE_LOCATION, PROBE_ID)
                .accept(
                    { ext ->
                        status.status("probe extension: installed (${ext?.id})")
                        ext?.let { extension ->
                            probeExtension = extension
                            sessionProvider().forEach { session ->
                                registerSession(session)
                            }
                        }
                    },
                    { e -> status.status("probe extension: INSTALL FAILED: ${e?.message}") },
                )
        } catch (e: Throwable) {
            status.status("probe extension: threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Alpha.52 — install the MAIN-world experiment extension,
     * ONLY when the probeMainWorld flag is on (default OFF).
     * The install result is itself part of the verdict: a
     * failed install records REJECTED (the ceiling is
     * confirmed); a successful install leaves the verdict
     * UNKNOWN until the sentinel arrives. Guarded so the
     * attempt happens at most once per session; a flag-off
     * call returns before setting the guard, so flipping the
     * flag on mid-session (Settings toggle or next page load)
     * still attempts it.
     */
    fun installMainWorldExperiment() {
        if (!host.probeMainWorldEnabled()) return
        if (mainWorldAttempted) return
        mainWorldAttempted = true
        try {
            runtime.webExtensionController
                .ensureBuiltIn(MAIN_WORLD_LOCATION, MAIN_WORLD_ID)
                .accept(
                    { ext ->
                        status.status("MAIN-world experiment: installed (${ext?.id})")
                        ext?.let { extension ->
                            mainWorldExtension = extension
                            sessionProvider().forEach { session ->
                                registerExperimentSession(session)
                            }
                        }
                    },
                    { e ->
                        status.status("MAIN-world experiment: INSTALL FAILED: ${e?.message}")
                        host.noteMainWorldInstallFailed(e?.message)
                    },
                )
        } catch (e: Throwable) {
            status.status("MAIN-world experiment: threw ${e.javaClass.simpleName}: ${e.message}")
            host.noteMainWorldInstallFailed(e.message)
        }
    }

    /**
     * Register the probe message delegate for the experiment
     * extension on one session — the sentinel
     * (`probe-main-world-ok`) arrives through the same
     * delegate as the probe's messages. Called for every tab
     * at creation once the experiment extension is installed.
     */
    private fun registerExperimentSession(session: GeckoSession) {
        mainWorldExtension?.let { extension ->
            runCatching {
                session.webExtensionController.setMessageDelegate(
                    extension, probeMessageDelegate, PROBE_APP
                )
            }.onFailure { e ->
                status.status("MAIN-world experiment: delegate for new tab failed: ${e.message}")
            }
        }
    }

    /**
     * Register the probe message delegate for one session. Called for
     * every tab at creation — tabs created after the probe install
     * (e.g. from window.open) need it too.
     */
    fun registerSession(session: GeckoSession) {
        probeExtension?.let { extension ->
            runCatching {
                session.webExtensionController.setMessageDelegate(
                    extension, probeMessageDelegate, PROBE_APP
                )
            }.onFailure { e ->
                status.status("probe: delegate for new tab failed: ${e.message}")
            }
        }
        // Alpha.52: tabs created after the experiment install
        // need its delegate too, or the sentinel never arrives.
        registerExperimentSession(session)
    }

    private val probePortDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            // Zoom ports are app -> page only; nothing is expected back.
        }

        override fun onDisconnect(port: WebExtension.Port) {
            host.unregisterProbePort(port)
        }
    }

    /** The background script's native port
     *  (`browser.runtime.connectNative("pioneer")`): the app
     *  pushes `rules-update` on it; the background script
     *  never posts on it (its messages arrive via
     *  sendNativeMessage instead). */
    private val backgroundPortDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            // The background script never posts on the native
            // port — nothing to route.
        }

        override fun onDisconnect(port: WebExtension.Port) {
            host.onBackgroundPortDisconnected(port)
        }
    }

    private val probeMessageDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender,
        ): GeckoResult<Any>? {
            if (message !is JSONObject) return null
            val type = message.optString("type")
            if (type == "zoom-query") {
                // The content script asks for its tab's stored zoom
                // factor after (re)load; the return value resolves
                // its sendNativeMessage promise.
                val factor = host.zoomFactorFor(sender.session)
                val reply = JSONObject().apply { put("factor", factor.toDouble()) }
                return GeckoResult.fromValue(reply as Any)
            }
            // Batch 4: the content script asks whether reduced
            // motion is on ("privacy-query") and whether media
            // on its host starts muted ("autoplay-query").
            // The replies resolve the script's sendNativeMessage
            // promises; live changes arrive as port messages
            // ("reduced-motion" / "autoplay-muted").
            if (type == "privacy-query") {
                val reply = JSONObject().apply {
                    put("reducedMotion", host.reducedMotionEnabled())
                }
                return GeckoResult.fromValue(reply as Any)
            }
            if (type == "autoplay-query") {
                val pageHost = message.optString("host")
                val reply = JSONObject().apply {
                    put("muted", host.autoplayMutedFor(pageHost))
                }
                return GeckoResult.fromValue(reply as Any)
            }
            // Phase 3 (Worker C wiring): the video pop-up
            // watcher's per-site opt-out query. The reply
            // resolves the watcher's sendNativeMessage
            // promise; the message carries the host.
            if (type == "video-popup-query") {
                val off = host.videoPopupOff(message.optString("host"))
                val reply = JSONObject().apply {
                    put("type", "video-popup-off")
                    put("off", off)
                    put("showButton", host.showVideoPopupButton())
                }
                return GeckoResult.fromValue(reply as Any)
            }
            // Phase 3 (Worker C wiring): the watcher ▶ button
            // was tapped / long-pressed. Routed to the tab's
            // EngineTab callbacks; the shell (VideoPopup.handle)
            // applies the allowCheck before playing.
            if (type == "video-popup-tap") {
                host.noteVideoPopupTap(sender.session, message.optString("url"))
                return null
            }
            if (type == "video-popup-longpress") {
                host.noteVideoPopupLongPress(sender.session, message.optString("url"))
                return null
            }
            // Alpha.52: the MAIN-world experiment sentinel. The
            // script reports whether it found itself in the
            // page's own world; the verdict lives in
            // MainWorldExperiment (first-wins, conservative —
            // only a MAIN-world proof accepts). An absent field
            // reads as false — never a false accept.
            if (type == "probe-main-world-ok") {
                host.noteMainWorldSentinel(message.optBoolean("mainWorld", false))
                return null
            }
            // Alpha.59: the cookie-capture experiment. The
            // probe reports cookie NAMES only — never values —
            // for an in-scope request that carried a `Cookie`
            // header (`cookie-capture`), and a tick with the
            // in-scope request count (`cookie-capture-tick`).
            // The verdict lives in CookieCaptureExperiment
            // (first-wins, conservative — only a seen header
            // accepts). A missing `names` array reads as
            // empty — never a false accept.
            if (type == "cookie-capture") {
                val namesArray = message.optJSONArray("names")
                val names = if (namesArray != null) {
                    List(namesArray.length()) { namesArray.optString(it) }
                } else {
                    emptyList()
                }
                host.noteCookieCapture(
                    message.optString("host"), names,
                    message.optInt("requests", 0)
                )
                return null
            }
            if (type == "cookie-capture-tick") {
                host.noteCookieCaptureTick(message.optInt("requests", 0))
                return null
            }
            // Phase 3 (Worker A wiring): a `webRequest`
            // observation from the probe background script.
            // The sender is the background script, so
            // sender.session is null — the engine attributes
            // the URL by host match, falling back to the
            // active tab (design §7). Routed into MediaLog.
            // alpha.47: a `media-net` from `onHeadersReceived`
            // carries the response Content-Type as `mime`;
            // URL-only `onBeforeRequest` observations carry
            // none — the engine classifies by MIME when one
            // is present, by URL otherwise.
            if (type == "media-net") {
                host.noteNetMedia(
                    sender.session,
                    message.optString("url"),
                    message.optString("mime").takeIf { it.isNotEmpty() },
                    message.optString("contentLength").toLongOrNull()
                )
                return null
            }
            // alpha.10: Save offline — the content script
            // answers a `page-html-request` port message with
            // this page's outerHTML. Routed to the tab's
            // onPageHtml handler; the shell saves + toasts.
            if (type == "page-html") {
                host.notePageHtml(sender.session, message.optString("html"))
                return null
            }
            if (type != "media-probe") {
                // Worker A fallthrough: the blocking bridge
                // owns `blocked`, `cosmetic-query` and
                // `rules-request` — and returns null for types
                // it does not own, so this is a pure chain, not
                // a catch-all. `media-net` never reaches here:
                // the Worker C routing above owns it (one sink,
                // no double-feed). New §7 names (A, recorded):
                // `blocked` (background→app cancellation report)
                // and `rules-request` (background asks for the
                // initial rules; the reply IS `rules-update`).
                // `rules-update` gains `disabledHosts`,
                // `userNet`, `trackerHosts`, `docHosts`,
                // `builtinHosts`, `blockAds`, `blockTrackers`
                // beside the five named keys.
                return host.handleBlockingMessage(message, sender.session)
            }
            val tabNumber = host.tabNumberFor(sender.session)
            val url = message.optString("url")
            val items = message.optJSONArray("items")
            val count = items?.length() ?: 0
            val eme = message.optJSONObject("eme")
            // Alpha.52: a probe report means a page loaded — the
            // bounded wait for the MAIN-world sentinel runs from
            // here (and a mid-session flip-on attempts the
            // install on this load).
            host.noteMainWorldPageLoad()
            // Alpha.59: the same report arms the bounded wait
            // for a `Cookie` header (re-arms per load while
            // the verdict is UNKNOWN).
            host.noteCookieCapturePageLoad()
            status.status(
                "probe tab $tabNumber [${message.optString("phase")}]: " +
                    "$count media URLs on $url" +
                    (eme?.let { " | EME: $it" } ?: "")
            )
            if (items != null) {
                for (i in 0 until items.length()) {
                    val obj = items.optJSONObject(i) ?: continue
                    host.noteProbeMedia(tabNumber, obj.optString("kind"), obj.optString("url"))
                }
            }
            return null
        }

        override fun onConnect(port: WebExtension.Port) {
            // sender.session is the GeckoSession the content
            // script runs in — null for the background script.
            // A null session is the BACKGROUND script's native
            // port (`browser.runtime.connectNative("pioneer")`):
            // it must go to the blocking bridge, NOT through
            // the tab-port registration below, which
            // disconnects session-less ports. If it went
            // through the tab path, `rules-update` pushes would
            // silently never arrive (the `rules-request` reply
            // is only the fallback).
            val session = port.sender?.session
            if (session == null) {
                port.setDelegate(backgroundPortDelegate)
                host.onBackgroundPortConnected(port)
                return
            }
            port.setDelegate(probePortDelegate)
            host.registerProbePort(session, port)
        }
    }

    // ------------------------------------------------------------------
    // Approved extensions (runtime install from AMO — see
    // ApprovedExtensions; by name only, never a store).
    // installUBlock is the original path, unchanged in
    // behaviour; installGoogleSearchFixer shares it.
    // ------------------------------------------------------------------

    fun installUBlock() =
        installFromAmo(ApprovedExtensions.UBLOCK_ORIGIN, uBlockState, "uBlock")

    fun installGoogleSearchFixer() =
        installFromAmo(
            ApprovedExtensions.GOOGLE_SEARCH_FIXER,
            googleSearchFixerState,
            "Google Search Fixer Refreshed",
        )

    private fun installFromAmo(
        extension: ApprovedExtension,
        state: MutableState<String>,
        statusTag: String,
    ) {
        val controller = runtime.webExtensionController
        // Set when the user declines the install prompt, so the
        // install failure that follows reports "declined" instead
        // of "failed". Per-install (local), not shared: two
        // installs never share one flag.
        var declinedByUser = false
        controller.promptDelegate =
            promptDelegateFor(extension.displayName) { declinedByUser = true }
        try {
            controller.list().accept(
                { installed ->
                    val existing = installed?.firstOrNull { it.id == extension.id }
                    if (existing != null) {
                        val version = existing.metaData?.version ?: "?"
                        state.value = "installed $version"
                        status.status("$statusTag: already installed ($version)")
                    } else {
                        declinedByUser = false
                        state.value = "installing…"
                        status.status("$statusTag: installing from AMO…")
                        controller.install(
                            extension.amoUrl,
                            WebExtensionController.INSTALLATION_METHOD_MANAGER,
                        ).accept(
                            { ext ->
                                val version = ext?.metaData?.version ?: "?"
                                state.value = "installed $version"
                                status.status("$statusTag: INSTALLED (${ext?.id}, $version)")
                            },
                            { e ->
                                if (declinedByUser) {
                                    state.value = "declined — retry from menu"
                                    status.status("$statusTag: install declined")
                                } else {
                                    state.value =
                                        "failed: ${e?.javaClass?.simpleName}: ${e?.message}"
                                    status.status(
                                        "$statusTag: INSTALL FAILED: " +
                                            "${e?.javaClass?.simpleName}: ${e?.message}"
                                    )
                                }
                            },
                        )
                    }
                },
                { e ->
                    state.value = "failed: ${e?.message}"
                    status.status("$statusTag: list failed: ${e?.message}")
                },
            )
        } catch (e: Throwable) {
            state.value = "failed: ${e.javaClass.simpleName}: ${e.message}"
            status.status("$statusTag: threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * The install prompt. NEVER auto-approves (the spike's
     * auto-approve was spike-only): the [GeckoResult] completes only
     * when the shell answers through [Engine.onExtensionInstallPrompt].
     * If no prompt handler is wired, the install is declined.
     * A double decision is ignored — the result completes once.
     */
    private fun promptDelegateFor(
        displayName: String,
        onDeclined: () -> Unit,
    ) = object : WebExtensionController.PromptDelegate {
        override fun onInstallPromptRequest(
            extension: WebExtension,
            permissions: Array<String>,
            origins: Array<String>,
            dataCollectionPermissions: Array<String>,
        ): GeckoResult<WebExtension.PermissionPromptResponse> {
            val result = GeckoResult<WebExtension.PermissionPromptResponse>()
            var decided = false
            val decide: (Boolean) -> Unit = { allow ->
                if (!decided) {
                    decided = true
                    if (!allow) onDeclined()
                    // On Install, grant everything the spike granted:
                    // permissions, private mode, and technical /
                    // interaction data collection — now behind the
                    // shell's dialog instead of automatic.
                    result.complete(WebExtension.PermissionPromptResponse(allow, allow, allow))
                }
            }
            val prompt = promptProvider()
            if (prompt != null) {
                status.status("$displayName: install prompt — asking the shell")
                prompt(displayName, permissions.toList(), decide)
            } else {
                status.status("$displayName: install prompt with no shell handler — declining")
                decide(false)
            }
            return result
        }

        override fun onUpdatePrompt(
            extension: WebExtension,
            newPermissions: Array<String>,
            newOrigins: Array<String>,
            newDataCollectionPermissions: Array<String>,
        ): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.ALLOW)

        override fun onOptionalPrompt(
            extension: WebExtension,
            permissions: Array<String>,
            origins: Array<String>,
            dataCollectionPermissions: Array<String>,
        ): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.ALLOW)
    }
}

/**
 * What the probe message delegate needs from the engine: which tab a
 * message came from, that tab's stored zoom factor, and where to keep
 * the native port the content script opened for live zoom pushes.
 */
interface ProbeHost {
    /** 1-based tab number for status/media lines, -1 if unknown. */
    fun tabNumberFor(session: GeckoSession?): Int

    /** Stored per-site text-zoom factor for the tab (1.0f default). */
    fun zoomFactorFor(session: GeckoSession?): Float

    /** Batch 4: the stored reduced-motion setting
     *  ("reducedMotion", default false) — the probe asks on
     *  load ("privacy-query"). */
    fun reducedMotionEnabled(): Boolean = false

    /** Batch 4: whether media on [host] starts muted — the
     *  per-site autoplay mode when set, else the global
     *  autoplay setting. The probe asks on load
     *  ("autoplay-query"). */
    fun autoplayMutedFor(host: String): Boolean = false

    fun noteProbeMedia(tabNumber: Int, kind: String, url: String)
    fun registerProbePort(session: GeckoSession?, port: WebExtension.Port)
    fun unregisterProbePort(port: WebExtension.Port)

    /** Phase 3 (Worker A): a `media-net` webRequest
     *  observation. [session] is null for background-script
     *  messages — the engine attributes by host match. [mime]
     *  is the response Content-Type when the observation came
     *  from `onHeadersReceived` (alpha.47); null for URL-only
     *  `onBeforeRequest` observations. [contentLength] is the
     *  response Content-Length when known (alpha.50, same
     *  split); null when the observation carries no size. */
    fun noteNetMedia(
        session: GeckoSession?,
        url: String,
        mime: String? = null,
        contentLength: Long? = null
    )
    /** Phase 3 (Worker C): the video pop-up watcher ▶ button
     *  was tapped / long-pressed in [session]'s page. */
    fun noteVideoPopupTap(session: GeckoSession?, url: String)
    fun noteVideoPopupLongPress(session: GeckoSession?, url: String)
    /** Phase 3 (Worker C): per-site pop-up-player opt-out
     *  for [host] (`videoPopupOff:$host`). */
    fun videoPopupOff(host: String): Boolean
    /** alpha.51: global on-video button option
     *  (`showVideoPopupButton`, default ON). */
    fun showVideoPopupButton(): Boolean
    /** Alpha.52: the MAIN-world experiment flag
     *  (`probeMainWorld`, default OFF). */
    fun probeMainWorldEnabled(): Boolean = false
    /** Alpha.52: the experiment extension's install failed —
     *  the ceiling is confirmed. */
    fun noteMainWorldInstallFailed(message: String?) {}
    /** Alpha.52: one sentinel arrived; [mainWorld] is true
     *  only when the script proved it runs in the page's own
     *  world. */
    fun noteMainWorldSentinel(mainWorld: Boolean) {}
    /** Alpha.52: a probe report means a page loaded — arm the
     *  bounded wait for the sentinel, and attempt the install
     *  if the flag was flipped on mid-session. */
    fun noteMainWorldPageLoad() {}
    /** Alpha.59: the cookie-capture experiment flag
     *  (`probeCookieCapture`, default OFF). */
    fun cookieCaptureEnabled(): Boolean = false
    /** Alpha.59: one `Cookie` header seen on [host] for
     *  [names] (names only — never values); [requests] is
     *  the probe's in-scope request count at that moment. */
    fun noteCookieCapture(host: String, names: List<String>, requests: Int) {}
    /** Alpha.59: a tick from the probe — [requests]
     *  in-scope requests observed with no `Cookie` header
     *  yet. */
    fun noteCookieCaptureTick(requests: Int) {}
    /** Alpha.59: a probe report means a page loaded — arm
     *  the bounded wait for a `Cookie` header (re-arms per
     *  load while the verdict is UNKNOWN). */
    fun noteCookieCapturePageLoad() {}
    /** alpha.10 (Save offline): the content script answered
     *  a `page-html-request` with this page's outerHTML. */
    fun notePageHtml(session: GeckoSession?, html: String)

    /** Phase 3 (Worker A): the blocking bridge's native
     *  messages (`blocked`, `cosmetic-query`,
     *  `rules-request`). Returns a GeckoResult only for
     *  messages that carry a reply; null otherwise (and for
     *  types it does not own, so the delegate can chain). */
    fun handleBlockingMessage(
        message: JSONObject, senderSession: GeckoSession?
    ): GeckoResult<Any>?

    /** Phase 3 (Worker A): the background script's native
     *  port (`sender.session == null`). NOT a tab port — the
     *  Phase 2 registration disconnects session-less ports,
     *  so this must never go through [registerProbePort]. */
    fun onBackgroundPortConnected(port: WebExtension.Port)
    fun onBackgroundPortDisconnected(port: WebExtension.Port)
}
