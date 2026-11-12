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
 *  off, or when the site is whitelisted per-site. A denied popup is
 *  silent only when the site is blacklisted ("Always deny"); otherwise
 *  its URL is captured for Open once / Open all. Pure — unit-tested
 *  (PopupControllerTest). */
enum class PopupVerdict { ALLOW, DENY_CAPTURE, DENY_SILENT }

fun decidePopup(strict: Boolean, allowPopups: Boolean, blockPopups: Boolean): PopupVerdict =
    when {
        !strict || allowPopups -> PopupVerdict.ALLOW
        blockPopups -> PopupVerdict.DENY_SILENT
        else -> PopupVerdict.DENY_CAPTURE
    }

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
 * The decision is synchronous under the strict model (Gecko's
 * prompt request is answered in the same call — a denied popup's
 * window is dead by design; "Open once" opens a fresh tab, as
 * Mariner does for its denied path). The controller's job is that
 * the decision, the arming and the capture all happen in ONE
 * place, against the SOURCE tab's host — never the engine's
 * current tab (rebuild item 2).
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

    /**
     * Answer a scripted-popup request (PromptDelegate.onPopupPrompt)
     * synchronously under the strict model, against the SOURCE tab's
     * host. Returns the GeckoResult the delegate hands back to
     * Gecko (already completed). A popup request must never hang:
     * every path completes the result, and any failure inside the
     * decision still completes it as a denial.
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
                PopupVerdict.DENY_SILENT -> {
                    status("popup blocked silently: ${target ?: "(no target)"} (tab ${source?.id ?: "?"})")
                    result.complete(prompt.confirm(AllowOrDeny.DENY))
                }
                PopupVerdict.DENY_CAPTURE -> {
                    status("popup blocked: ${target ?: "(no target)"} (tab ${source?.id ?: "?"})")
                    result.complete(prompt.confirm(AllowOrDeny.DENY))
                    capture(target, host)
                }
            }
        }.onFailure {
            // Never hang a popup request: a failure inside the
            // decision still answers it, as a denial.
            status("popup decision failed: ${it.javaClass.simpleName} — denying")
            runCatching { result.complete(prompt.confirm(AllowOrDeny.DENY)) }
        }
        return result
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
