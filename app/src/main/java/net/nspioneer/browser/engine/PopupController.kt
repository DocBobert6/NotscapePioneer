package net.nspioneer.browser.engine

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import net.nspioneer.browser.data.PioneerStore

/** Strict-popup decision (Mariner's model, §8.8): allowed when strict is
 *  off, or when the site is whitelisted per-site. A blacklisted site's
 *  popup is denied without asking and its URL is captured for Open
 *  once / Open all — the notice is how he sees what was blocked
 *  (alpha.14: no more silent blacklist, which was the original
 *  "blocked with no notice" complaint). An unlisted site is ASK:
 *  the request is held for his choice, never answered by default.
 *  Pure — unit-tested (PopupControllerTest). */
enum class PopupVerdict { ALLOW, ASK, DENY_CAPTURE }

fun decidePopup(strict: Boolean, allowPopups: Boolean, blockPopups: Boolean): PopupVerdict =
    when {
        !strict || allowPopups -> PopupVerdict.ALLOW
        blockPopups -> PopupVerdict.DENY_CAPTURE
        else -> PopupVerdict.ASK
    }

/** The four choices on the popup prompt (alpha.14): Allow once,
 *  Deny once, Trust (always allow — whitelist), Don't trust
 *  (always deny — blacklist). */
enum class PopupChoice { ALLOW_ONCE, DENY_ONCE, TRUST, DONT_TRUST }

/** What answering a held popup request does — pure, unit-tested
 *  (PopupControllerTest). `armAllowed`: the subsequent
 *  onNewSession is the popup window. `capture`: the target URL
 *  joins the captured queue (notice). `writeAllow` /
 *  `writeBlock`: the per-site flags to persist (null = leave the
 *  site's settings untouched). */
data class PopupAnswerEffect(
    val complete: AllowOrDeny,
    val armAllowed: Boolean,
    val capture: Boolean,
    val writeAllow: Boolean?,
    val writeBlock: Boolean?,
)

fun answerEffect(choice: PopupChoice): PopupAnswerEffect = when (choice) {
    PopupChoice.ALLOW_ONCE -> PopupAnswerEffect(AllowOrDeny.ALLOW, true, false, null, null)
    PopupChoice.DENY_ONCE -> PopupAnswerEffect(AllowOrDeny.DENY, false, true, null, null)
    PopupChoice.TRUST -> PopupAnswerEffect(AllowOrDeny.ALLOW, true, false, true, false)
    PopupChoice.DONT_TRUST -> PopupAnswerEffect(AllowOrDeny.DENY, false, true, false, true)
}

/** A gesture link (target=_blank, no popup prompt) is blocked
 *  only when the strict decision for its source site denies —
 *  the same verdict as the prompt path, so a blacklisted site's
 *  tapped links are denied + captured like its popups. Pure —
 *  unit-tested (PopupControllerTest). */
fun isGestureBlocked(strict: Boolean, allowPopups: Boolean, blockPopups: Boolean): Boolean =
    decidePopup(strict, allowPopups, blockPopups) == PopupVerdict.DENY_CAPTURE

/** Capture-list rule (max 5, arrival order, deduped, blank /
 *  about:blank never captured). Pure — unit-tested
 *  (PopupControllerTest). Returns the new list; the caller swaps
 *  it in only when it grew. */
fun addPopupCapture(existing: List<String>, url: String, max: Int = 5): List<String> {
    if (url.isBlank() || url == "about:blank" || url in existing) return existing
    if (existing.size >= max) return existing
    return existing + url
}

/**
 * The single popup controller (rebuild item 1, alpha.13): one
 * per-source-tab state machine replacing the four split pieces —
 * the engine's `expectPopupSession` boolean, the PromptDelegate's
 * pending fields, the shell's `handlePopupPrompt`, and the
 * popunder-guard capture. It holds the Gecko request, the target
 * URI and the source tab together until answered, and owns the
 * one captured-URL queue both capture sources write to.
 *
 * What stays strict: `popupStrict` default true; per-site
 * `allowPopups` / `blockPopups` honoured; no popup ever opens
 * without a decision; denied popups never load.
 *
 * The decision is synchronous for whitelisted / blacklisted /
 * strict-off sites (Gecko's prompt request is answered in the
 * same call — a denied popup's window is dead by design; "Open
 * once" opens a fresh tab, as Mariner does for its denied
 * path). An UNLISTED site's request is HELD for the user's
 * choice (alpha.14): the controller owns the held Gecko request
 * and the shell answers it via [answerPending] — Allow / Deny /
 * Trust / Don't trust — and dismissing the prompt counts as
 * Deny (once) + capture. The controller's job is that the
 * decision, the arming and the capture all happen in ONE place,
 * against the SOURCE tab's host — never the engine's current
 * tab (rebuild item 2). A popup request must never hang: every
 * path completes the result, and any failure inside the
 * decision still completes it as a denial.
 */
class PopupController(
    private val store: PioneerStore,
    private val status: (String) -> Unit,
) {
    /** The tab a popup came from: its id, its committed host, its
     *  privacy class. The host is read from the tab's committed
     *  URL at request time — the best available signal, and the
     *  one Mariner uses (the opener page's host). */
    data class SourceTab(val id: Int, val host: String, val isPrivate: Boolean)

    /** An allowed popup waiting for its onNewSession: the next new
     *  session whose URI matches is the popup window. The URI match
     *  is the bound that stops a prompt that never produces a tab
     *  from mis-marking the next unrelated tab (audit S3). */
    private data class PendingAllowed(val sourceTabId: Int, val targetUri: String?)
    private var pendingAllowed: PendingAllowed? = null

    /** Captured popup targets, in arrival order (Mariner
     *  BrowserScreen.kt:711-721): prompt denies + popunder-guard
     *  captures share this one queue. The shell observes it for
     *  the notice and drains it on Open once / Open all. */
    val capturedUrls: SnapshotStateList<String> = mutableStateListOf()

    /** Host the captures came from (the source tab's host) — the
     *  notice's "on $host" and the Always allow / Always deny
     *  target. */
    val capturedHost: State<String> get() = _capturedHost
    private val _capturedHost = mutableStateOf("")

    /** A scripted-popup request waiting on the user's choice
     *  (alpha.14): the Gecko request is HELD, not answered,
     *  until he picks Allow / Deny / Trust / Don't trust. The
     *  shell renders the four-choice prompt from this state and
     *  answers it via [answerPending]; dismissing the prompt
     *  counts as Deny (once) + capture. */
    data class PendingPopupDecision(
        val sourceHost: String,
        val targetUri: String?,
        val sourceTabId: Int,
    )

    val pendingDecision: State<PendingPopupDecision?> get() = _pendingDecision
    private val _pendingDecision = mutableStateOf<PendingPopupDecision?>(null)
    private var pendingResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null
    private var pendingPrompt: GeckoSession.PromptDelegate.PopupPrompt? = null

    /**
     * Answer a scripted-popup request (PromptDelegate.onPopupPrompt)
     * against the SOURCE tab's host. Whitelisted / blacklisted /
     * strict-off sites are answered synchronously under the strict
     * model (already completed result). An UNLISTED site's request
     * is HELD for the user's choice (alpha.14): the returned
     * GeckoResult is intentionally uncompleted, and the shell's
     * four-choice prompt answers it via [answerPending]. A popup
     * request must never hang: every path completes the result,
     * and any failure inside the decision still completes it as
     * a denial.
     */
    fun onPopupRequest(
        source: SourceTab?,
        prompt: GeckoSession.PromptDelegate.PopupPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val host = source?.host ?: ""
        val target = prompt.targetUri
        runCatching {
            val site = if (host.isBlank()) null else store.siteSetting(host)
            val verdict = decidePopup(
                strict = store.setting("popupStrict", "true") == "true",
                allowPopups = site?.allowPopups == true,
                blockPopups = site?.blockPopups == true,
            )
            when (verdict) {
                PopupVerdict.ALLOW -> {
                    pendingAllowed = PendingAllowed(source?.id ?: -1, target)
                    status("popup allowed: ${target ?: "(no target)"} (tab ${source?.id ?: "?"})")
                    result.complete(prompt.confirm(AllowOrDeny.ALLOW))
                }
                PopupVerdict.DENY_CAPTURE -> {
                    status("popup blocked: ${target ?: "(no target)"} (tab ${source?.id ?: "?"})")
                    result.complete(prompt.confirm(AllowOrDeny.DENY))
                    capture(target, host)
                }
                PopupVerdict.ASK -> {
                    // A second request while one is pending
                    // answers the first as Deny (once) — a popup
                    // request must never hang, and the first
                    // one's URL is captured for the notice.
                    dismissPending()
                    pendingResult = result
                    pendingPrompt = prompt
                    _pendingDecision.value =
                        PendingPopupDecision(host, target, source?.id ?: -1)
                    status(
                        "popup waiting: ${target ?: "(no target)"} on " +
                            "${host.ifBlank { "?" }} — asking"
                    )
                }
            }
        }.onFailure {
            // Never hang a popup request: a failure inside the
            // decision still answers it, as a denial. If the
            // failure came after the request was held for the
            // prompt, drop the held state too — the shell must
            // never show a prompt for a request already answered.
            if (pendingResult === result) {
                pendingResult = null
                pendingPrompt = null
                _pendingDecision.value = null
                capture(target, host)
            }
            status("popup decision failed: ${it.javaClass.simpleName} — denying")
            runCatching { result.complete(prompt.confirm(AllowOrDeny.DENY)) }
        }
        return result
    }

    /** Answer the held popup request (the shell's four-choice
     *  prompt). The choice is applied in full: the Gecko request
     *  is completed, the site is whitelisted / blacklisted for
     *  Trust / Don't trust, an allowed popup is armed for its
     *  onNewSession, a denied one's URL is captured. No-op when
     *  nothing is held. */
    fun answerPending(choice: PopupChoice) {
        val result = pendingResult ?: return
        val prompt = pendingPrompt ?: return
        val decision = _pendingDecision.value
        pendingResult = null
        pendingPrompt = null
        _pendingDecision.value = null
        val effect = answerEffect(choice)
        val host = decision?.sourceHost ?: ""
        val target = decision?.targetUri
        runCatching {
            if (effect.writeAllow != null || effect.writeBlock != null) {
                if (host.isNotBlank()) {
                    val site = store.siteSetting(host)
                    store.putSiteSetting(
                        site.copy(
                            allowPopups = effect.writeAllow ?: site.allowPopups,
                            blockPopups = effect.writeBlock ?: site.blockPopups,
                        )
                    )
                }
            }
            if (effect.armAllowed) {
                pendingAllowed = PendingAllowed(decision?.sourceTabId ?: -1, target)
            }
            if (effect.capture) capture(target, host)
            status(
                "popup ${when (choice) {
                    PopupChoice.ALLOW_ONCE -> "allowed once"
                    PopupChoice.DENY_ONCE -> "denied"
                    PopupChoice.TRUST -> "trusted: ${host.ifBlank { "?" }} whitelisted"
                    PopupChoice.DONT_TRUST -> "distrusted: ${host.ifBlank { "?" }} blacklisted"
                }}: ${target ?: "(no target)"}"
            )
            result.complete(prompt.confirm(effect.complete))
        }.onFailure {
            // The request must still be answered even if a store
            // write failed — complete it as a denial.
            runCatching { result.complete(prompt.confirm(AllowOrDeny.DENY)) }
        }
    }

    /** Dismiss the held prompt without choosing: Deny (once) +
     *  capture (alpha.14). No-op when nothing is held. */
    fun dismissPending() {
        if (pendingResult == null) return
        answerPending(PopupChoice.DENY_ONCE)
    }

    /** The source tab of a held prompt was destroyed: answer its
     *  request as Deny (once) so it never hangs. No-op when the
     *  held prompt came from another tab (or none is held). */
    fun cancelPendingForTab(tabId: Int) {
        if (_pendingDecision.value?.sourceTabId == tabId) dismissPending()
    }

    /** Should a gesture link (target=_blank) from [host] open?
     *  False when the strict decision for that site denies
     *  (blacklisted) — the same verdict as the prompt path. The
     *  engine denies it (no tab) and captures the URL via
     *  [noteGestureDenied]. */
    fun gestureAllowed(host: String): Boolean {
        val site = if (host.isBlank()) null else store.siteSetting(host)
        return !isGestureBlocked(
            strict = store.setting("popupStrict", "true") == "true",
            allowPopups = site?.allowPopups == true,
            blockPopups = site?.blockPopups == true,
        )
    }

    /** A gesture link from a blacklisted site: deny it like any
     *  other blocked popup — capture the URL for the notice. */
    fun noteGestureDenied(url: String, host: String) {
        status("popup blocked: $url (gesture link on $host — blacklisted)")
        capture(url, host)
    }

    /**
     * The next onNewSession that matches a pending allowed popup.
     * Returns the source tab id (the tab to mark a popup window),
     * or null when no allowed popup is pending — a gesture link /
     * target=_blank, which is not a popup at all. The record is
     * consumed either way, so one prompt can never arm two tabs.
     */
    fun matchNewSession(uri: String): Int? {
        val pending = pendingAllowed ?: return null
        pendingAllowed = null
        if (pending.targetUri != null && pending.targetUri != uri) return null
        return pending.sourceTabId.takeIf { it >= 0 }
    }

    /** Second capture source (item 5): the popunder guard's
     *  gesture-less hop to a $document-blocked host. Same queue,
     *  same cap, same notice as the prompt path. */
    fun notePopunder(url: String, host: String) {
        capture(url, host)
    }

    private fun capture(url: String?, host: String) {
        if (url.isNullOrBlank()) return
        val next = addPopupCapture(capturedUrls.toList(), url)
        if (next.size == capturedUrls.size) return
        capturedUrls.clear()
        capturedUrls.addAll(next)
        if (host.isNotBlank()) _capturedHost.value = host
    }

    /** Drain the queue (Open once / Open all), then clear. */
    fun takeCaptured(): List<String> {
        val urls = capturedUrls.toList()
        capturedUrls.clear()
        return urls
    }

    fun clearCaptured() {
        capturedUrls.clear()
    }
}
