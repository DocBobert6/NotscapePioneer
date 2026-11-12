package net.nspioneer.browser.engine

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import net.nspioneer.browser.data.PioneerStore
import java.io.File

/**
 * PromptDelegate implementation (PHASE3-DESIGN.md §8.8, §8.9):
 * file chooser (onFilePrompt) + popup prompt (onPopupPrompt).
 * Fullscreen is a ContentDelegate method in GeckoEngine.
 *
 * File chooser — Mariner's behaviour, which has no custom UI
 * (BrowserScreen.kt onShowFileChooser): cancel any pending
 * callback with null first, launch the chooser, null intent
 * or launch failure → callback null. Pioneer (API doc §3):
 * single / multiple / folder (Type.FOLDER, v138) via the
 * matching ActivityResult contract; null or launch failure
 * → answer null.
 *
 * The content:// trap (API doc §3, verified against the
 * GeckoView source — FilePrompt.getFile, GeckoSession.java):
 * getFile() resolves "file:" directly and attempts
 * resolveDocumentUri / resolveContentUri for "content:",
 * but SAF document URIs (Downloads provider, etc.) can
 * still resolve to null, logging "Only file URIs are
 * supported". Fenix converts content:// to file:// before
 * confirming (bug 1591640); this delegate does the same:
 * a picked content:// file is copied to a temp file and
 * confirmed with a file:// URI. Folder (tree) URIs are
 * passed through untouched — FilePrompt.confirm traverses
 * the tree itself (filesInWebKitDirectory).
 *
 * Popups — Mariner's model (§8.9, BrowserScreen.kt:3201+):
 * popupStrict (default true). Per-site allowPopups → a real
 * new tab (isPopupWin=true, created by the engine as in
 * Phase 2's onNewSession path). Blocked → the target URL is
 * collected into pendingPopupUrls (max 5) and a notice is
 * shown, unless per-site blockPopups ("Always deny"
 * silences completely). The decision itself is applied by
 * the SHELL (BrowserScreen, which owns the queue and the
 * notice) through Engine.answerPopupPrompt; this delegate
 * only holds the pending Gecko request. If no shell handler
 * is wired — or the wired handler did not answer synchronously
 * (it answers promptly by contract) — the strict model is
 * applied here instead so a popup request can never hang
 * unanswered.
 */
class PioneerPromptDelegate(
    private val appContext: Context,
    private val store: PioneerStore,
    /** Deliver a file-chooser request to the shell. Null = no shell handler. */
    private val deliverFilePrompt: ((type: String, mimeTypes: List<String>, capture: Int,
        onResult: (List<Uri>?) -> Unit) -> Unit)?,
    /** Deliver a popup request to the shell. Null = no shell handler. */
    private val deliverPopupPrompt: ((targetUri: String?) -> Unit)?,
    /** Host of the page the popup / chooser came from. */
    private val pageHost: () -> String,
    /** Called when a popup is allowed (engine marks the next
     *  onNewSession tab as a popup window). */
    private val onPopupAllowed: () -> Unit,
) : GeckoSession.PromptDelegate {

    // ---- file chooser ----

    private var pendingFileResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null
    private var pendingFilePrompt: GeckoSession.PromptDelegate.FilePrompt? = null

    override fun onFilePrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.FilePrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        // Mariner rule: cancel any pending callback with
        // null FIRST — the engine enforces it by completing
        // the previous prompt with null before delivering a
        // new one.
        pendingFileResult?.let { runCatching { it.complete(null) } }
        pendingFileResult = null
        pendingFilePrompt = null

        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        pendingFileResult = result
        pendingFilePrompt = prompt
        val type = when (prompt.type) {
            GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE -> "multiple"
            GeckoSession.PromptDelegate.FilePrompt.Type.FOLDER -> "folder"
            else -> "single"
        }
        val mimeTypes = prompt.mimeTypes?.toList() ?: emptyList()
        val deliver = deliverFilePrompt
        if (deliver == null) {
            // No shell handler: answer null rather than hang.
            result.complete(null)
            pendingFileResult = null
            pendingFilePrompt = null
            return result
        }
        deliver(type, mimeTypes, prompt.capture) { uris ->
            answerFile(uris)
        }
        return result
    }

    /** Complete the pending file prompt with [uris] (null =
     *  cancelled / failed). content:// files are copied to a
     *  temp file first — see the class comment. */
    private fun answerFile(uris: List<Uri>?) {
        val result = pendingFileResult ?: return
        val prompt = pendingFilePrompt
        pendingFileResult = null
        pendingFilePrompt = null
        if (uris == null || prompt == null) {
            runCatching { result.complete(null) }
            return
        }
        val converted = runCatching {
            uris.map { uri -> toFileUri(uri) }
        }.getOrNull()
        if (converted == null) {
            runCatching { result.complete(null) }
            return
        }
        runCatching {
            val response = if (converted.size == 1) {
                prompt.confirm(appContext, converted[0])
            } else {
                prompt.confirm(appContext, converted.toTypedArray())
            }
            result.complete(response)
        }.onFailure {
            runCatching { result.complete(null) }
        }
    }

    /**
     * Convert one picked URI to a file:// URI Gecko can use.
     * file:// passes through; a tree URI (folder upload)
     * passes through (confirm traverses it); any other
     * content:// URI is copied to a temp file under
     * cacheDir/pioneer-filechooser and confirmed as file://.
     */
    private fun toFileUri(uri: Uri): Uri {
        if (uri.scheme == "file") return uri
        if (uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) return uri
        if (uri.scheme != "content") return uri
        val name = runCatching {
            appContext.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "upload"
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeIf { it.isNotBlank() } ?: "upload"
        val dir = File(appContext.cacheDir, "pioneer-filechooser").apply { mkdirs() }
        // Unique temp name per pick, so two picks of the same
        // file never collide.
        val tmp = File(dir, "${System.currentTimeMillis()}-$safeName")
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out -> input.copyTo(out) }
        } ?: error("no stream for $uri")
        return Uri.fromFile(tmp)
    }

    // ---- popups ----

    private var pendingPopupResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null
    private var pendingPopupPrompt: GeckoSession.PromptDelegate.PopupPrompt? = null
    private var pendingPopupTarget: String? = null

    override fun onPopupPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.PopupPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
        // A popup request must never hang: cancel any previous
        // unanswered one with a denial first.
        pendingPopupResult?.let { runCatching { it.complete(null) } }
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        pendingPopupResult = result
        pendingPopupPrompt = prompt
        pendingPopupTarget = prompt.targetUri
        val deliver = deliverPopupPrompt
        if (deliver == null) {
            // No shell handler: apply the strict model here.
            val allowed = popupAllowedNow()
            if (allowed) onPopupAllowed()
            runCatching {
                result.complete(
                    prompt.confirm(
                        if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY
                    )
                )
            }
            pendingPopupResult = null
            pendingPopupPrompt = null
            pendingPopupTarget = null
            return result
        }
        deliver(prompt.targetUri)
        // The shell answers synchronously (handlePopupPrompt
        // completes the request promptly). If it did not — its
        // handler is not wired yet — apply the strict model
        // here instead of leaving the request hanging: a popup
        // request must never hang unanswered.
        if (pendingPopupResult === result) {
            val allowed = popupAllowedNow()
            if (allowed) onPopupAllowed()
            runCatching {
                result.complete(
                    prompt.confirm(
                        if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY
                    )
                )
            }
            pendingPopupResult = null
            pendingPopupPrompt = null
            pendingPopupTarget = null
        }
        return result
    }

    /** Mariner's strict-model decision for the current page
     *  (BrowserScreen.kt:3216-3218): allowed when strict is
     *  off, or when the site is whitelisted per-site. */
    fun popupAllowedNow(): Boolean {
        val strict = store.setting("popupStrict", "true") == "true"
        val host = pageHost()
        return !strict || (host.isNotBlank() && store.siteSetting(host).allowPopups)
    }

    /** The shell's answer to a pending popup request
     *  (Engine.answerPopupPrompt). Completes the Gecko
     *  request via the stored prompt object; an allow also
     *  arms the next onNewSession tab as a popup window. */
    fun answerPopup(allow: Boolean) {
        val result = pendingPopupResult ?: return
        val prompt = pendingPopupPrompt
        pendingPopupResult = null
        pendingPopupPrompt = null
        pendingPopupTarget = null
        if (prompt == null) {
            runCatching { result.complete(null) }
            return
        }
        if (allow) onPopupAllowed()
        runCatching {
            result.complete(
                prompt.confirm(if (allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            )
        }
    }

    /** Whether the site has silenced popup notices entirely
     *  ("Always deny" — Mariner: no notice, ever). */
    fun popupsSilenced(): Boolean {
        val host = pageHost()
        return host.isNotBlank() && store.siteSetting(host).blockPopups
    }
}
