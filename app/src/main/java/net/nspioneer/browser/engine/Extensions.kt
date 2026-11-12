package net.nspioneer.browser.engine

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
 * uBlock Origin install from AMO. One deliberate change from the
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
        private const val UBLOCK_AMO_URL =
            "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
        private const val UBLOCK_ID = "uBlock0@raymondhill.net"
        private const val PROBE_LOCATION = "resource://android/assets/extensions/pioneer-probe/"
        private const val PROBE_ID = "pioneer-probe@pioneer"
        const val PROBE_APP = "pioneer"
    }

    val uBlockState = mutableStateOf("not installed")

    private var probeExtension: WebExtension? = null

    /** Set when the user declines the install prompt, so the install
     * failure that follows reports "declined" instead of "failed". */
    private var declinedByUser = false

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
            // Phase 3 (Worker C wiring): the video pop-up
            // watcher's per-site opt-out query. The reply
            // resolves the watcher's sendNativeMessage
            // promise; the message carries the host.
            if (type == "video-popup-query") {
                val off = host.videoPopupOff(message.optString("host"))
                val reply = JSONObject().apply {
                    put("type", "video-popup-off")
                    put("off", off)
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
            // Phase 3 (Worker A wiring): a `webRequest`
            // observation from the probe background script.
            // The sender is the background script, so
            // sender.session is null — the engine attributes
            // the URL by host match, falling back to the
            // active tab (design §7). Routed into MediaLog.
            if (type == "media-net") {
                host.noteNetMedia(sender.session, message.optString("url"))
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
    // uBlock Origin (runtime install from AMO, first extension)
    // ------------------------------------------------------------------

    fun installUBlock() {
        val controller = runtime.webExtensionController
        controller.promptDelegate = ubPromptDelegate
        try {
            controller.list().accept(
                { installed ->
                    val existing = installed?.firstOrNull { it.id == UBLOCK_ID }
                    if (existing != null) {
                        val version = existing.metaData?.version ?: "?"
                        uBlockState.value = "installed $version"
                        status.status("uBlock: already installed ($version)")
                    } else {
                        declinedByUser = false
                        uBlockState.value = "installing…"
                        status.status("uBlock: installing from AMO…")
                        controller.install(
                            UBLOCK_AMO_URL,
                            WebExtensionController.INSTALLATION_METHOD_MANAGER,
                        ).accept(
                            { ext ->
                                val version = ext?.metaData?.version ?: "?"
                                uBlockState.value = "installed $version"
                                status.status("uBlock: INSTALLED (${ext?.id}, $version)")
                            },
                            { e ->
                                if (declinedByUser) {
                                    uBlockState.value = "declined — retry from menu"
                                    status.status("uBlock: install declined")
                                } else {
                                    uBlockState.value =
                                        "failed: ${e?.javaClass?.simpleName}: ${e?.message}"
                                    status.status(
                                        "uBlock: INSTALL FAILED: " +
                                            "${e?.javaClass?.simpleName}: ${e?.message}"
                                    )
                                }
                            },
                        )
                    }
                },
                { e ->
                    uBlockState.value = "failed: ${e?.message}"
                    status.status("uBlock: list failed: ${e?.message}")
                },
            )
        } catch (e: Throwable) {
            uBlockState.value = "failed: ${e.javaClass.simpleName}: ${e.message}"
            status.status("uBlock: threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * The install prompt. NEVER auto-approves (the spike's
     * auto-approve was spike-only): the [GeckoResult] completes only
     * when the shell answers through [Engine.onExtensionInstallPrompt].
     * If no prompt handler is wired, the install is declined.
     * A double decision is ignored — the result completes once.
     */
    private val ubPromptDelegate = object : WebExtensionController.PromptDelegate {
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
                    if (!allow) declinedByUser = true
                    // On Install, grant everything the spike granted:
                    // permissions, private mode, and technical /
                    // interaction data collection — now behind the
                    // shell's dialog instead of automatic.
                    result.complete(WebExtension.PermissionPromptResponse(allow, allow, allow))
                }
            }
            val prompt = promptProvider()
            if (prompt != null) {
                status.status("uBlock: install prompt — asking the shell")
                prompt("uBlock Origin", permissions.toList(), decide)
            } else {
                status.status("uBlock: install prompt with no shell handler — declining")
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

    fun noteProbeMedia(tabNumber: Int, kind: String, url: String)
    fun registerProbePort(session: GeckoSession?, port: WebExtension.Port)
    fun unregisterProbePort(port: WebExtension.Port)

    /** Phase 3 (Worker A): a `media-net` webRequest
     *  observation. [session] is null for background-script
     *  messages — the engine attributes by host match. */
    fun noteNetMedia(session: GeckoSession?, url: String)
    /** Phase 3 (Worker C): the video pop-up watcher ▶ button
     *  was tapped / long-pressed in [session]'s page. */
    fun noteVideoPopupTap(session: GeckoSession?, url: String)
    fun noteVideoPopupLongPress(session: GeckoSession?, url: String)
    /** Phase 3 (Worker C): per-site pop-up-player opt-out
     *  for [host] (`videoPopupOff:$host`). */
    fun videoPopupOff(host: String): Boolean

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
