package net.nspioneer.browser.engine

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
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
 * Popups — the single PopupController owns them (§8.9, rebuild
 * item 1, alpha.13): popupStrict (default true). Per-site
 * allowPopups → a real new tab (isPopupWin=true, created by the
 * engine as in Phase 2's onNewSession path). Blocked → the target
 * URL is collected into the controller's queue (max 5) and the
 * shell shows a notice, unless per-site blockPopups ("Always
 * deny" silences completely). This delegate only forwards the
 * request to the controller with the SOURCE tab attached; it
 * holds no popup state of its own. A popup request must never
 * hang — the controller answers every request synchronously,
 * and any failure inside it still completes as a denial.
 */
class PioneerPromptDelegate(
    private val appContext: Context,
    /** Deliver a file-chooser request to the shell. Null = no shell handler. */
    private val deliverFilePrompt: ((type: String, mimeTypes: List<String>, capture: Int,
        onResult: (List<Uri>?) -> Unit) -> Unit)?,
    /** The single popup controller (rebuild item 1). */
    private val popupController: PopupController,
    /** The tab the popup request's session belongs to (the source
     *  tab — the controller decides against ITS host, never the
     *  engine's current tab). Null when the session maps to no tab. */
    private val sourceTabForSession: (GeckoSession) -> PopupController.SourceTab?,
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

    // ---- popups (rebuild item 1) ----

    /** Forward the request to the single popup controller with
     *  the source tab attached; the controller answers it
     *  synchronously under the strict model and returns the
     *  completed GeckoResult. This delegate holds no popup
     *  state of its own. */
    override fun onPopupPrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.PopupPrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? =
        popupController.onPopupRequest(sourceTabForSession(session), prompt)
}
