package net.nspioneer.browser.engine

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import net.nspioneer.browser.data.PioneerStore

/**
 * PermissionDelegate implementation (PHASE3-DESIGN.md §8.7,
 * PHASE3-GECKOVIEW-API.md §5): content / Android / media
 * permissions + the PermPrompt flow + grantWithOsCheck.
 *
 * Mariner behaviour, ported from BrowserScreen.kt:3078-3160
 * (:522-596, :5841): camera/mic arrive as
 * onMediaPermissionRequest; geolocation arrives as a content
 * permission (PERMISSION_GEOLOCATION — there is no separate
 * geolocation callback in GeckoView). Wanted resources map
 * camera/microphone; anything else → deny. Stored per-site
 * decisions `siteperm:$host:$resource` = "allow" / "deny" /
 * "ask" (unset). Mixed allow/ask: ask only about the
 * undecided ones; denying still grants the decided ones.
 *
 * The OS gate: this class never grants a resource whose
 * Android permission is missing. [checkOs] is supplied by
 * GeckoEngine and forwards to the shell's
 * RequestMultiplePermissions launcher (BrowserScreen's
 * grantWithOsCheck, ported verbatim from Mariner); when no
 * shell hook is wired it answers with the already-granted
 * subset only, so a stored "allow" can never grant what
 * the OS denied.
 *
 * An unanswered media-permission request must be answered
 * (grant or reject) or the page's getUserMedia promise
 * hangs (API doc §5d); [rejectOutstanding] answers every
 * pending request for a session on tab close / navigation.
 * Web notifications (PERMISSION_DESKTOP_NOTIFICATION) are
 * denied by default with no UI (§8.7 DROP — Mariner has
 * no such prompt; adding one would be new scope).
 */
class PioneerPermissionDelegate(
    private val appContext: Context,
    private val store: PioneerStore,
    private val runtime: GeckoRuntime,
    /** Deliver a prompt the shell must show (Mariner's dialog). */
    private val deliverPrompt: (PermPrompt) -> Unit,
    /**
     * OS-permission gate. [resources] are "camera" /
     * "microphone" / "geolocation"; the callback receives
     * the subset the OS actually granted.
     */
    private val checkOs: (resources: List<String>, onResult: (List<String>) -> Unit) -> Unit,
) : GeckoSession.PermissionDelegate {

    /** Pending content-permission answers, keyed by prompt. */
    private val pendingContent = mutableMapOf<PermPrompt, GeckoResult<Int>>()
    /** Pending media answers, keyed by prompt. */
    private val pendingMedia =
        mutableMapOf<PermPrompt, MediaPending>()
    /** Sessions of pending prompts, for rejectOutstanding. */
    private val promptSessions = mutableMapOf<PermPrompt, GeckoSession>()

    private data class MediaPending(
        val video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        val audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        val callback: GeckoSession.PermissionDelegate.MediaCallback,
    )

    private fun osPermissionFor(resource: String): String = when (resource) {
        "camera" -> android.Manifest.permission.CAMERA
        "microphone" -> android.Manifest.permission.RECORD_AUDIO
        else -> android.Manifest.permission.ACCESS_FINE_LOCATION
    }

    private fun hostOf(uri: String): String =
        runCatching { Uri.parse(uri).host }.getOrNull() ?: ""

    /** Build the engine-side prompt for [resources] on [host].
     *  onGrant completes the Gecko request with the resources
     *  actually granted (after the OS check); onDeny denies. */
    private fun buildPrompt(
        origin: String, host: String, resources: List<String>,
        complete: (granted: List<String>) -> Unit,
        deny: () -> Unit,
    ): PermPrompt = PermPrompt(
        origin = origin, host = host, resources = resources,
        onGrant = { granted -> complete(granted) },
        onDeny = { deny() },
    )

    // ------------------------------------------------------------------
    // Content permissions (geolocation, autoplay, DRM, storage, …)
    // ------------------------------------------------------------------

    override fun onContentPermissionRequest(
        session: GeckoSession,
        request: GeckoSession.PermissionDelegate.ContentPermission,
    ): GeckoResult<Int>? {
        val perm = request.permission
        val host = hostOf(request.uri)
        return when (perm) {
            GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> {
                // §8.7 DROP: denied by default, no UI.
                GeckoResult.fromValue(
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                )
            }
            GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS,
            GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> {
                // Spike posture, kept: DRM media keys and
                // persistent storage are granted silently.
                GeckoResult.fromValue(
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                )
            }
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> {
                // §8.7 autoplay: per-site siteautoplay:$host
                // overrides the global `autoplay` setting
                // (default "allow"). "block" → deny; anything
                // else → allow (the "muted" mode is a probe
                // behaviour, not a Gecko switch).
                val site = store.siteAutoplay(host)
                val mode = if (site.isNotBlank()) site
                else store.setting("autoplay", "allow")
                val value = if (mode == "block") {
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                } else {
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                }
                GeckoResult.fromValue(value)
            }
            GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> {
                // Mariner's geolocation flow, verbatim in
                // shape: stored allow → OS check then grant
                // (retain flag always false); stored deny →
                // deny; undecided → the dialog.
                val result = GeckoResult<Int>()
                val prompt = buildPrompt(
                    origin = request.uri, host = host,
                    resources = listOf("geolocation"),
                    complete = { granted ->
                        store.putSitePermission(host, "geolocation", "allow")
                        mirrorContentDecision(request, true)
                        result.complete(
                            if (granted.isNotEmpty()) {
                                GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                            } else {
                                GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                            }
                        )
                    },
                    deny = {
                        result.complete(
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                        )
                    },
                )
                pendingContent[prompt] = result
                promptSessions[prompt] = session
                when (store.sitePermission(host, "geolocation")) {
                    "allow" -> checkOs(listOf("geolocation")) { granted ->
                        prompt.onGrant(granted)
                    }
                    "deny" -> prompt.onDeny()
                    else -> {
                        runCatching { request.notifyShown() }
                        deliverPrompt(prompt)
                    }
                }
                result
            }
            else -> {
                // PERMISSION_TRACKING (per-site ETP exception is
                // set via setPermission, not answered here),
                // XR / storage-access / local-device / local-
                // network: no UI in Phase 3 — prompt (Gecko
                // default) rather than silently granting.
                GeckoResult.fromValue(
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Android permissions (the OS gate itself)
    // ------------------------------------------------------------------

    override fun onAndroidPermissionsRequest(
        session: GeckoSession,
        permissions: Array<String>?,
        callback: GeckoSession.PermissionDelegate.Callback,
    ) {
        // Only grant what the OS already granted — this
        // callback never launches a permission request
        // itself; the shell's grantWithOsCheck does that.
        val wanted = permissions ?: emptyArray()
        val allGranted = wanted.all { perm ->
            ContextCompat.checkSelfPermission(appContext, perm) ==
                PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) callback.grant() else callback.reject()
    }

    // ------------------------------------------------------------------
    // Media permissions (camera / mic)
    // ------------------------------------------------------------------

    override fun onMediaPermissionRequest(
        session: GeckoSession,
        uri: String,
        video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
        callback: GeckoSession.PermissionDelegate.MediaCallback,
    ) {
        val host = hostOf(uri)
        val wanted = mutableListOf<String>()
        if (!video.isNullOrEmpty()) wanted += "camera"
        if (!audio.isNullOrEmpty()) wanted += "microphone"
        if (wanted.isEmpty()) {
            // Nothing the dialog can grant — deny outright
            // (Mariner: wanted.isEmpty() → request.deny()).
            callback.reject()
            return
        }
        val pending = MediaPending(video, audio, callback)
        val complete: (List<String>) -> Unit = { granted ->
            if (granted.isEmpty()) {
                callback.reject()
            } else {
                // Grant the first source of each granted kind.
                val v = if ("camera" in granted) video?.firstOrNull() else null
                val a = if ("microphone" in granted) audio?.firstOrNull() else null
                callback.grant(v, a)
            }
        }
        val prompt = buildPrompt(
            origin = uri, host = host, resources = wanted,
            complete = { granted ->
                granted.forEach { store.putSitePermission(host, it, "allow") }
                complete(granted)
            },
            deny = { callback.reject() },
        )
        pendingMedia[prompt] = pending
        promptSessions[prompt] = session
        val decided = wanted.map { store.sitePermission(host, it) }
        val allowed = wanted.filterIndexed { i, _ -> decided[i] == "allow" }
        val asked = wanted.filterIndexed { i, _ -> decided[i] == "ask" }
        when {
            asked.isEmpty() -> {
                if (allowed.isNotEmpty()) {
                    checkOs(allowed) { granted -> prompt.onGrant(granted) }
                } else {
                    prompt.onDeny()
                }
            }
            allowed.isEmpty() -> deliverPrompt(prompt)
            else -> {
                // Mixed: ask about the undecided ones;
                // answering grants the decided ones together
                // with the newly allowed ones, and denying
                // still grants the decided ones. (Mariner,
                // BrowserScreen.kt:3110-3140, verbatim.)
                val mixed = prompt.copy(
                    resources = asked,
                    onGrant = { grantedAsked ->
                        val combined = allowed + grantedAsked
                        if (combined.isNotEmpty()) {
                            // Persist the newly allowed ones,
                            // then grant the combined set.
                            grantedAsked.forEach {
                                store.putSitePermission(host, it, "allow")
                            }
                            checkOs(combined) { osGranted ->
                                complete(osGranted)
                            }
                        } else {
                            callback.reject()
                        }
                    },
                    onDeny = {
                        if (allowed.isNotEmpty()) {
                            checkOs(allowed) { granted -> complete(granted) }
                        } else {
                            callback.reject()
                        }
                    },
                )
                // The mixed prompt answers the same Gecko
                // request: re-key the pending maps onto it.
                pendingMedia.remove(prompt)
                promptSessions.remove(prompt)
                pendingMedia[mixed] = pending
                promptSessions[mixed] = session
                deliverPrompt(mixed)
            }
        }
    }

    // ------------------------------------------------------------------
    // Shell answers (Engine.answerPermissionPrompt)
    // ------------------------------------------------------------------

    /** The shell calls this after the user answers Mariner's
     *  dialog; the engine persists the decision and completes
     *  the Gecko request. Do not answer the Gecko request
     *  from the shell — the request object lives here. */
    fun answer(prompt: PermPrompt, allow: Boolean) {
        if (allow) {
            prompt.resources.forEach {
                store.putSitePermission(prompt.host, it, "allow")
            }
            // The dialog path goes through the shell's
            // grantWithOsCheck (prompt.onGrant) instead; this
            // path is the programmatic answer, which grants
            // only the resources the OS already allows — and
            // denies when the OS granted none of them.
            val granted = prompt.resources.filter { res ->
                ContextCompat.checkSelfPermission(
                    appContext, osPermissionFor(res)
                ) == PackageManager.PERMISSION_GRANTED
            }
            if (granted.isNotEmpty()) prompt.onGrant(granted)
            else prompt.onDeny()
        } else {
            prompt.resources.forEach {
                store.putSitePermission(prompt.host, it, "deny")
            }
            prompt.onDeny()
        }
        pendingContent.remove(prompt)
        pendingMedia.remove(prompt)
        promptSessions.remove(prompt)
    }

    /** Answer every outstanding request for [session] with a
     *  denial — tab close / navigation must never leave a
     *  getUserMedia promise hanging. */
    fun rejectOutstanding(session: GeckoSession) {
        val doomed = promptSessions.filter { it.value === session }.keys.toList()
        for (prompt in doomed) {
            pendingContent[prompt]?.complete(
                GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
            )
            pendingMedia[prompt]?.callback?.reject()
            pendingContent.remove(prompt)
            pendingMedia.remove(prompt)
            promptSessions.remove(prompt)
        }
    }

    /** Mirror a content decision into Gecko's
     *  StorageController (the store is the source of truth;
     *  this is the Gecko-side mirror, §5b of the API doc).
     *  The request object itself carries the permission, so
     *  setPermission can update its stored value. */
    private fun mirrorContentDecision(
        request: GeckoSession.PermissionDelegate.ContentPermission,
        allow: Boolean,
    ) {
        runCatching {
            runtime.storageController.setPermission(
                request,
                if (allow) {
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                } else {
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                },
            )
        }
    }

    /**
     * Per-site blocking exception, applied to Gecko's
     * tracking-protection layer (§5 of the design doc).
     * ContentPermission has no public constructor — it is
     * built from its JSON form (fromJson, keys "uri" /
     * "perm" / "value" / "privateMode", verified against
     * the GeckoView source). NOTE: StorageController
     * rejects VALUE_PROMPT for PERMISSION_TRACKING, so
     * re-enabling blocking uses VALUE_DENY, not PROMPT.
     */
    fun setTrackingException(host: String, disabled: Boolean) {
        runCatching {
            val json = JSONObject().apply {
                put("uri", "https://$host")
                put("perm", "trackingprotection")
                put("value", GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT)
                put("privateMode", false)
            }
            val perm = GeckoSession.PermissionDelegate.ContentPermission
                .fromJson(json) ?: return@runCatching
            runtime.storageController.setPermission(
                perm,
                if (disabled) {
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                } else {
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                },
            )
        }
    }
}
