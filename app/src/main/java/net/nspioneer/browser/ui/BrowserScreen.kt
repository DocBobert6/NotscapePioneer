package net.nspioneer.browser.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.engine.Engine
import net.nspioneer.browser.engine.EngineTab
import net.nspioneer.browser.engine.GeckoEngine
import net.nspioneer.browser.engine.PermPrompt
import net.nspioneer.browser.media.Downloads
import net.nspioneer.browser.media.MediaLog
import net.nspioneer.browser.media.PlayerActivity
import net.nspioneer.browser.media.VideoPopup
import net.nspioneer.browser.media.rememberAskDownloadNotifPermission
import net.nspioneer.browser.media.rememberCastAvailable
import org.mozilla.geckoview.GeckoView

/**
 * The Phase 2 browser shell, written clean for Gecko. Deliberately
 * NOT a port of Mariner's 6,674-line BrowserScreen.kt: no vault,
 * no media detection, no downloads, no reader — those arrive with
 * their phases. What it does:
 *
 * - one GeckoView (AndroidView) bound to the active tab's session;
 *   the start page overlays it while a tab is in its home state.
 * - tab switcher sheet: title + URL rows, close, New tab, New
 *   private tab; NO thumbnails in Phase 2 (known gap).
 * - address bar with local suggestions (history + bookmarks);
 *   the spike's loadAddress rule (bare domain -> https://, else
 *   DuckDuckGo search).
 * - find bar wired to EngineTab.find / findNext / clearFind.
 * - per-site text zoom applied on navigation from store.siteZoom.
 * - back chain: engine back -> start page -> close tab / exit.
 *   The start page is a home state per tab (the session is not
 *   navigated away — the tab keeps its page underneath).
 * - long-press link menu, only when the engine fires
 *   onLongPressLink (if GeckoView exposes no hook, the menu
 *   simply never appears; recorded as a gap).
 */

/** The spike's address rule, preserved exactly: a bare domain
 * gains https://, anything else searches DuckDuckGo. Null
 * means "nothing to load" (blank input). */
internal fun resolveAddress(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    return when {
        text.startsWith("http://") || text.startsWith("https://") -> text
        text.contains(".") && !text.contains(" ") -> "https://$text"
        else -> "https://duckduckgo.com/?q=" + URLEncoder.encode(text, "UTF-8")
    }
}

/** The download awaiting confirmation in the "Download
 *  file" dialog (Mariner BrowserScreen.kt:422, shape
 *  verbatim). videoUrl/audioUrl are set for a merge
 *  (two-sided download); otherwise null. */
private data class PendingDownload(
    val title: String,
    val url: String,
    val media: Boolean,
    val contentType: String?,
    val suggestedName: String?,
    val referer: String?,
    val isPrivate: Boolean,
    val videoUrl: String? = null,
    val audioUrl: String? = null,
)

/** Mariner's permission resource words ("Allow $host to
 *  use …?"). */
private fun permResourceWord(resource: String): String = when (resource) {
    "camera" -> "your camera"
    "microphone" -> "your microphone"
    else -> "your location"
}

@Composable
fun BrowserScreen(
    store: PioneerStore,
    engine: Engine,
    tabs: SnapshotStateList<EngineTab>,
    activeTabIndex: Int,
    homeTabIds: MutableSet<Int>,
    onSwitchTab: (Int) -> Unit,
    onNewTab: (Boolean) -> Unit,
    onCloseTab: (EngineTab) -> Unit,
    onDuplicateTab: (EngineTab) -> Unit,
    onCloseOtherTabs: (EngineTab) -> Unit,
    onOpenScreen: (PioneerScreen) -> Unit,
    onCopyStatus: () -> Unit,
    onInstallUBlock: () -> Unit,
    longPressUrl: String?,
    onLongPressHandled: () -> Unit,
    onOpenUrlInNewTab: (String, Boolean, Boolean) -> Unit,
    onSetBackHandler: ((() -> Boolean)?) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val tab = tabs.getOrNull(activeTabIndex) ?: return
    val tabUrl = tab.url.value
    val isHome = tab.id in homeTabIds || tabUrl.isBlank()
    var menuOpen by remember { mutableStateOf(false) }
    var customizeOpen by remember { mutableStateOf(false) }
    var tabSwitcherOpen by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var editingAddress by remember { mutableStateOf(false) }
    var addressText by remember(tab.id) { mutableStateOf(if (isHome) "" else tabUrl) }

    // A tab that navigates away from blank leaves its home state.
    LaunchedEffect(tabUrl) {
        if (tabUrl.isNotBlank()) homeTabIds.remove(tab.id)
    }
    // Per-site text zoom, applied when a tab navigates to a host
    // with a stored zoom.
    LaunchedEffect(tabUrl) {
        val host = runCatching { Uri.parse(tabUrl).host }.getOrNull()
        if (!host.isNullOrBlank()) {
            val zoom = store.siteZoom(host)
            if (zoom != 1.0f) tab.setTextZoom(zoom)
        }
    }
    // Per-site desktop mode, applied when a tab navigates to a host
    // with a stored desktop preference. Guarded on the tab's current
    // mode: setDesktopMode reloads, and without the guard the reload
    // would re-trigger this effect and loop.
    LaunchedEffect(tabUrl) {
        val host = runCatching { Uri.parse(tabUrl).host }.getOrNull()
        if (!host.isNullOrBlank()) {
            val desktop = store.siteDesktop(host)
            if (tab.isDesktopMode.value != desktop) tab.setDesktopMode(desktop)
        }
    }
    // The address field follows the tab while the user is not
    // editing it.
    LaunchedEffect(tabUrl, isHome) {
        if (!editingAddress) addressText = if (isHome) "" else tabUrl
    }

    fun openUrl(url: String) {
        homeTabIds.remove(tab.id)
        tab.load(url)
    }
    fun goAddress(raw: String) {
        val resolved = resolveAddress(raw) ?: return
        openUrl(resolved)
        editingAddress = false
        focusManager.clearFocus()
    }
    fun closeFind() {
        findOpen = false
        findText = ""
        tab.clearFind()
    }
    fun shareUrl(url: String) {
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        context.startActivity(Intent.createChooser(intent, "Share page"))
    }

    // Back chain (MainActivity calls this first; false means
    // "nothing left here — exit"): close overlays, then engine
    // back, then the start page, then close the tab; the last
    // home tab exits the app.
    val backHandler: () -> Boolean = {
        when {
            longPressUrl != null -> { onLongPressHandled(); true }
            mediaSheetOpen -> { mediaSheetOpen = false; true }
            siteInfoOpen -> { siteInfoOpen = false; true }
            showPopupNotice -> { showPopupNotice = false; clearPendingPopups(); true }
            customizeOpen -> { customizeOpen = false; true }
            menuOpen -> { menuOpen = false; true }
            tabSwitcherOpen -> { tabSwitcherOpen = false; true }
            findOpen -> { closeFind(); true }
            editingAddress -> { editingAddress = false; focusManager.clearFocus(); true }
            tab.canGoBack.value -> { tab.goBack(); true }
            !isHome -> { homeTabIds.add(tab.id); true }
            tabs.size > 1 -> { onCloseTab(tab); true }
            else -> false
        }
    }
    SideEffect { onSetBackHandler(backHandler) }

    val suggestions = remember(addressText, editingAddress, store.revision) {
        if (!editingAddress) emptyList()
        else {
            val q = addressText.trim()
            if (q.isBlank()) emptyList()
            else {
                val bookmarkHits = store.bookmarks.filter {
                    it.title.contains(q, ignoreCase = true) ||
                        it.url.contains(q, ignoreCase = true)
                }.map { Triple(it.title.ifBlank { it.url }, it.url, true) }
                val historyHits = store.history.filter {
                    it.title.contains(q, ignoreCase = true) ||
                        it.url.contains(q, ignoreCase = true)
                }.distinctBy { it.url }
                    .map { Triple(it.title.ifBlank { it.url }, it.url, false) }
                (bookmarkHits + historyHits).distinctBy { it.second }.take(6)
            }
        }
    }
    val uBlockInstalled = engine.uBlockState.value.startsWith("installed")

    // ---- Phase 3: downloads / permissions / file chooser / popups ----
    val askDownloadNotif = rememberAskDownloadNotifPermission(store)
    var permPrompt by remember { mutableStateOf<PermPrompt?>(null) }
    var pendingPermGrant by remember { mutableStateOf<Triple<PermPrompt, List<String>, List<String>>?>(null) }
    var pendingOsGrant by remember { mutableStateOf<Pair<List<String>, (List<String>) -> Unit>?>(null) }
    var pendingDownload by remember { mutableStateOf<PendingDownload?>(null) }
    var confirmName by remember { mutableStateOf("") }
    var confirmLocation by remember { mutableStateOf("public") }
    var confirmDontAsk by remember { mutableStateOf(false) }
    var pendingVariants by remember { mutableStateOf<List<Downloads.DownloadVariant>>(emptyList()) }
    var pendingVariantIndex by remember { mutableStateOf(0) }
    // Captured popup targets, in arrival order (Mariner
    // BrowserScreen.kt:711-721). A queue, not a single slot:
    // one tap often fires TWO window.open calls, and
    // collapsing them meant the survivor was usually the ad.
    val pendingPopupUrls = remember { mutableStateListOf<String>() }
    var popupPageHost by remember { mutableStateOf("") }
    var showPopupNotice by remember { mutableStateOf(false) }
    var mediaSheetOpen by remember { mutableStateOf(false) }
    var siteInfoOpen by remember { mutableStateOf(false) }
    var pendingFileCallback by remember { mutableStateOf<((List<Uri>?) -> Unit)?>(null) }
    val castAvailable = rememberCastAvailable()
    val pageHost = runCatching { Uri.parse(tabUrl).host }.getOrNull() ?: ""

    fun osPermFor(res: String): String = when (res) {
        "camera" -> Manifest.permission.CAMERA
        "microphone" -> Manifest.permission.RECORD_AUDIO
        else -> Manifest.permission.ACCESS_FINE_LOCATION
    }

    val permOsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        pendingPermGrant?.let { grant ->
            pendingPermGrant = null
            val (prompt, asked, _) = grant
            val granted = asked.filter { res -> results[osPermFor(res)] == true }
            if (granted.isNotEmpty()) prompt.onGrant(granted) else prompt.onDeny()
        }
        pendingOsGrant?.let { (resources, onResult) ->
            pendingOsGrant = null
            val granted = resources.filter { res ->
                results[osPermFor(res)] == true ||
                    ContextCompat.checkSelfPermission(context, osPermFor(res)) ==
                        PackageManager.PERMISSION_GRANTED
            }
            onResult(granted)
        }
    }

    /** Mariner's grantWithOsCheck (BrowserScreen.kt:522-596,
     *  verbatim): grant straight away when the OS
     *  permissions are already held, otherwise ask the OS
     *  first and grant only what it allows. */
    fun grantWithOsCheck(prompt: PermPrompt, resources: List<String>) {
        val missing = resources.mapNotNull { res ->
            val perm = osPermFor(res)
            if (ContextCompat.checkSelfPermission(
                    context, perm
                ) == PackageManager.PERMISSION_GRANTED
            ) null else perm
        }
        if (missing.isEmpty()) {
            prompt.onGrant(resources)
        } else {
            pendingPermGrant = Triple(prompt, resources, missing)
            permOsLauncher.launch(missing.toTypedArray())
        }
    }

    val fileSingleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val cb = pendingFileCallback
        pendingFileCallback = null
        cb?.invoke(uri?.let { listOf(it) })
    }
    val fileMultipleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        val cb = pendingFileCallback
        pendingFileCallback = null
        cb?.invoke(uris.ifEmpty { null })
    }
    val fileFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val cb = pendingFileCallback
        pendingFileCallback = null
        cb?.invoke(uri?.let { listOf(it) })
    }

    /** Mariner's beginDownload (BrowserScreen.kt:597-625),
     *  minus the PDF diversion — PDFs download as files in
     *  Pioneer (§8.2: no PDF viewer in Phase 3). */
    fun beginDownload(
        title: String, url: String, media: Boolean,
        contentType: String?, suggestedName: String?,
        referer: String?, isPrivate: Boolean
    ) {
        if (store.setting("confirmDownloads", "true") == "true") {
            pendingDownload = PendingDownload(
                title, url, media, contentType, suggestedName, referer, isPrivate
            )
            confirmName = suggestedName
                ?: Uri.parse(url).lastPathSegment
                ?: title
            confirmLocation = if (isPrivate) "app" else store.setting("downloadLocation", "public")
            confirmDontAsk = false
        } else {
            askDownloadNotif()
            Downloads.start(
                context, store, title, url, media = media,
                contentType = contentType, suggestedName = suggestedName,
                referer = referer, isPrivate = isPrivate
            )
            Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
        }
    }

    /** Merge confirmation (Mariner BrowserScreen.kt:626-650):
     *  merges go through the same name/destination dialog as
     *  plain downloads. */
    fun beginMergeDownload(
        title: String, videoUrl: String, audioUrl: String,
        referer: String?, isPrivate: Boolean
    ) {
        if (store.setting("confirmDownloads", "true") == "true") {
            pendingDownload = PendingDownload(
                title, videoUrl, true, null, null, referer, isPrivate,
                videoUrl = videoUrl, audioUrl = audioUrl
            )
            confirmName = title
            confirmLocation = if (isPrivate) "app" else store.setting("downloadLocation", "public")
            confirmDontAsk = false
        } else {
            askDownloadNotif()
            Downloads.startMerge(
                context, store, title, videoUrl, audioUrl,
                referer = referer, isPrivate = isPrivate
            )
            Toast.makeText(context, "Merging into one MP4", Toast.LENGTH_SHORT).show()
        }
    }

    /** Download quality variants (Mariner
     *  BrowserScreen.kt:650-700): fetched for HLS master
     *  playlists and DASH manifests while the confirmation
     *  dialog is up; empty means no choice (single-variant
     *  source, or the fetch failed). */
    LaunchedEffect(pendingDownload) {
        val pending = pendingDownload
        pendingVariants = emptyList()
        pendingVariantIndex = 0
        if (pending == null || !pending.media || pending.videoUrl != null) return@LaunchedEffect
        pendingVariants = withContext(Dispatchers.IO) {
            Downloads.fetchDownloadVariants(pending.url, pending.contentType, pending.referer)
        }
    }

    fun clearPendingPopups() {
        pendingPopupUrls.clear()
        (engine as? GeckoEngine)?.popunderCaptures?.clear()
    }

    /** Open every captured popup in its own tab, then clear. */
    fun openPendingPopups() {
        val urls = pendingPopupUrls.toList()
        clearPendingPopups()
        urls.forEach { u -> onOpenUrlInNewTab(u, tab.isPrivate, true) }
    }

    /** The shell's answer to a popup request (§8.9): the
     *  strict model decides first; a blocked popup completes
     *  the Gecko request as denied PROMPTLY (never hang a
     *  popup request while a notice is up) and its URL is
     *  captured for Open once / Open all instead. Mariner
     *  captures via a sacrificial WebView; GeckoView hands
     *  us the target URI directly, so no sacrificial view
     *  is needed. Sites the user blacklisted ("Always deny")
     *  are silenced completely: no notice, ever. */
    fun handlePopupPrompt(targetUri: String?) {
        val host = pageHost
        val strict = store.setting("popupStrict", "true") == "true"
        val allowed = !strict || store.siteSetting(host).allowPopups
        if (allowed) {
            engine.answerPopupPrompt(true)
            return
        }
        engine.answerPopupPrompt(false)
        if (!targetUri.isNullOrBlank() && targetUri != "about:blank" &&
            targetUri !in pendingPopupUrls && pendingPopupUrls.size < 5
        ) {
            pendingPopupUrls.add(targetUri)
        }
        popupPageHost = host
        if (!store.siteSetting(host).blockPopups) {
            showPopupNotice = true
        }
    }

    // Popup notice auto-dismiss (Mariner
    // BrowserScreen.kt:1794).
    LaunchedEffect(showPopupNotice) {
        if (showPopupNotice) {
            kotlinx.coroutines.delay(12_000)
            showPopupNotice = false
            clearPendingPopups()
        }
    }

    fun openMediaSheet() {
        tab.runMediaScan()
        mediaSheetOpen = true
    }

    fun playFirstVideo() {
        val first = MediaLog.firstVideo(MediaLog.forTab(tab.id))
        if (first != null) {
            PlayerActivity.launch(
                context, first.url, first.label.ifBlank { tab.title.value },
                referer = tab.url.value, privateSession = tab.isPrivate
            )
        } else {
            Toast.makeText(context, "No video on this page", Toast.LENGTH_SHORT).show()
        }
    }

    // Engine callback wiring. Set in SideEffect so the
    // closures refresh every composition (they close over
    // the current tab and dialog state).
    SideEffect {
        // The stored-allow path also needs the OS check
        // (Mariner's grantWithOsCheck); when this hook is
        // unset the engine only reports already-granted OS
        // permissions. osGrantHandler is a GeckoEngine
        // member, not part of the Engine interface — the
        // interface's PermPrompt flow covers the dialog path.
        (engine as? GeckoEngine)?.osGrantHandler = { resources, onResult ->
            val missing = resources.mapNotNull { res ->
                val perm = osPermFor(res)
                if (ContextCompat.checkSelfPermission(
                        context, perm
                    ) == PackageManager.PERMISSION_GRANTED
                ) null else perm
            }
            if (missing.isEmpty()) onResult(resources)
            else {
                pendingOsGrant = resources to onResult
                permOsLauncher.launch(missing.toTypedArray())
            }
        }
        engine.onExternalDownload = { t, dl ->
            beginDownload(
                t.title.value, dl.url, false, dl.contentType,
                dl.suggestedName, t.url.value, t.isPrivate
            )
        }
        engine.onPermissionPrompt = { prompt -> permPrompt = prompt }
        engine.onFilePrompt = { type, mimeTypes, _, onResult ->
            pendingFileCallback = onResult
            val mime = mimeTypes.firstOrNull()?.takeIf { it.isNotBlank() } ?: "*/*"
            // The launcher must run on the main thread; the
            // engine delivers prompts there, but post
            // defensively anyway. A launch failure answers
            // the prompt with null (cancelled).
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    when (type) {
                        "folder" -> fileFolderLauncher.launch(null)
                        "multiple" -> fileMultipleLauncher.launch(mime)
                        else -> fileSingleLauncher.launch(mime)
                    }
                }.onFailure {
                    val cb = pendingFileCallback
                    pendingFileCallback = null
                    cb?.invoke(null)
                }
            }
        }
        engine.onPopupPrompt = { targetUri -> handlePopupPrompt(targetUri) }
        tab.onVideoPopupTap = { url ->
            VideoPopup.handle(
                url, false, MediaLog.forTab(tab.id),
                onPlay = { entry ->
                    PlayerActivity.launch(
                        context, entry.url, entry.label.ifBlank { tab.title.value },
                        referer = tab.url.value, privateSession = tab.isPrivate
                    )
                },
                onOpenSheet = { openMediaSheet() }
            )
        }
        tab.onVideoPopupLongPress = { url ->
            VideoPopup.handle(
                url, true, MediaLog.forTab(tab.id),
                onPlay = { entry ->
                    PlayerActivity.launch(
                        context, entry.url, entry.label.ifBlank { tab.title.value },
                        referer = tab.url.value, privateSession = tab.isPrivate
                    )
                },
                onOpenSheet = { openMediaSheet() }
            )
        }
        // Popunder-guard captures (engine side) merge into
        // the shell's popup notice queue.
        (engine as? GeckoEngine)?.let { ge ->
            if (ge.popunderCaptures.isNotEmpty()) {
                val host = ge.popunderPageHost.value
                ge.popunderCaptures.toList().forEach { u ->
                    if (u !in pendingPopupUrls && pendingPopupUrls.size < 5) {
                        pendingPopupUrls.add(u)
                    }
                }
                ge.popunderCaptures.clear()
                if (host.isNotBlank()) popupPageHost = host
                if (host.isBlank() || !store.siteSetting(host).blockPopups) {
                    showPopupNotice = true
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Address bar.
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { tabSwitcherOpen = true },
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Text("${tabs.size}")
                }
                TextField(
                    value = addressText,
                    onValueChange = { addressText = it },
                    modifier = Modifier.weight(1f)
                        .onFocusChanged { editingAddress = it.isFocused },
                    singleLine = true,
                    placeholder = { Text("Search or enter address") },
                    leadingIcon = {
                        if (tab.isPrivate) {
                            Icon(Icons.Filled.Lock, contentDescription = "Private tab")
                        } else {
                            Icon(Icons.Filled.Search, contentDescription = null)
                        }
                    },
                    trailingIcon = {
                        if (addressText.isNotEmpty()) {
                            IconButton(onClick = {
                                addressText = ""
                            }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { goAddress(addressText) })
                )
                // Media on this page (§8.3): next to the menu
                // button, only when the tab is not the start
                // page and the probe has seen media. Tap runs
                // a fresh scan and opens the sheet; long-press
                // plays the first video.
                if (!isHome && MediaLog.forTab(tab.id).isNotEmpty()) {
                    MediaToolbarButton(
                        onClick = { openMediaSheet() },
                        onLongClick = { playFirstVideo() }
                    )
                }
                // Site information (Mariner BrowserScreen.kt:3800):
                // the lock opens the per-site sheet.
                IconButton(onClick = { siteInfoOpen = true }) {
                    Icon(
                        if (tabUrl.startsWith("https://")) Icons.Filled.Lock
                        else Icons.Filled.LockOpen,
                        contentDescription = "Site information"
                    )
                }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.Menu, contentDescription = "Menu")
                }
            }
            if (tab.isLoading.value) {
                LinearProgressIndicator(
                    progress = { (tab.progress.value.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // The engine view, with the start page over it while
            // this tab is in its home state.
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    factory = { ctx -> GeckoView(ctx) },
                    update = { view -> view.setSession(tab.session) },
                    modifier = Modifier.fillMaxSize()
                )
                if (isHome) {
                    StartPage(
                        store = store,
                        onOpen = { url -> openUrl(url) },
                        contentTopPad = 0.dp,
                        contentBottomPad = 0.dp
                    )
                }
            }
        }

        // Local suggestions over the page while typing.
        if (editingAddress && suggestions.isNotEmpty()) {
            SuggestionsCard(
                suggestions = suggestions,
                barAtBottom = false,
                immersive = false,
                suggestionQuery = addressText,
                onPick = { url ->
                    goAddress(url)
                }
            )
        }

        // Find in page.
        if (findOpen) {
            val result = tab.findResult.value
            FindBar(
                barAtBottom = false,
                immersive = false,
                findText = findText,
                findCount = result?.count ?: 0,
                findOrdinal = result?.ordinal ?: 0,
                onFindTextChange = { text ->
                    findText = text
                    tab.find(text)
                },
                onFindNext = { forward -> tab.findNext(forward) },
                onClose = { closeFind() }
            )
        }

        // Popup notice (Mariner BrowserScreen.kt:4280-4380):
        // blocked popups collect in the queue and a notice
        // is shown, unless per-site blockPopups silences it.
        if (showPopupNotice) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 92.dp)
                    .fillMaxWidth()
            ) {
                Surface(
                    shape = Frost.CardShape,
                    color = Frost.cardColor(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Block, contentDescription = "Blocking",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    when {
                                        pendingPopupUrls.size > 1 ->
                                            "${pendingPopupUrls.size} popups blocked on $popupPageHost"
                                        popupPageHost.isNotBlank() -> "Popup blocked on $popupPageHost"
                                        else -> "Popup blocked"
                                    },
                                    style = MaterialTheme.typography.titleSmall
                                )
                                pendingPopupUrls.firstOrNull()?.let { firstUrl ->
                                    Text(
                                        firstUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(
                                onClick = { showPopupNotice = false; clearPendingPopups() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (pendingPopupUrls.isNotEmpty()) {
                                Button(onClick = {
                                    openPendingPopups()
                                    showPopupNotice = false
                                }) {
                                    Text(
                                        if (pendingPopupUrls.size > 1) {
                                            "Open all (${pendingPopupUrls.size})"
                                        } else {
                                            "Open once"
                                        }
                                    )
                                }
                            }
                            OutlinedButton(onClick = {
                                // Whitelist the site, then open the
                                // popup so the tap still lands where
                                // the user aimed.
                                val host = popupPageHost
                                if (host.isNotBlank()) {
                                    store.putSiteSetting(
                                        store.siteSetting(host).copy(allowPopups = true, blockPopups = false)
                                    )
                                }
                                openPendingPopups()
                                showPopupNotice = false
                            }) { Text("Always allow") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                showPopupNotice = false
                                clearPendingPopups()
                            }) { Text("Deny") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                // Blacklist the site: its popups are
                                // blocked silently from now on (undo in
                                // Settings, Per-site controls).
                                val host = popupPageHost
                                if (host.isNotBlank()) {
                                    store.putSiteSetting(
                                        store.siteSetting(host).copy(allowPopups = false, blockPopups = true)
                                    )
                                }
                                showPopupNotice = false
                                clearPendingPopups()
                            }) { Text("Always deny") }
                        }
                    }
                }
            }
        }
    }

    // Tab switcher sheet: title + URL rows, close, New tab, New
    // private tab. NO thumbnails in Phase 2 (known gap).
    if (tabSwitcherOpen) {
        FrostSheet(onDismiss = { tabSwitcherOpen = false }) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Tabs (${tabs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(tabs.toList(), key = { it.id }) { t ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    val idx = tabs.indexOf(t)
                                    if (idx >= 0) onSwitchTab(idx)
                                    tabSwitcherOpen = false
                                }
                                .background(
                                    if (t.id == tab.id) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else MaterialTheme.colorScheme.surface,
                                    MaterialTheme.shapes.medium
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (t.isPrivate) {
                                Icon(
                                    Icons.Filled.Lock,
                                    contentDescription = "Private tab",
                                    modifier = Modifier.size(18.dp)
                                        .padding(end = 4.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    t.title.value.ifBlank { if (t.url.value.isBlank()) "New tab" else t.url.value },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    t.url.value.ifBlank { "Start page" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onCloseTab(t) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Close tab")
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onNewTab(false); tabSwitcherOpen = false },
                        modifier = Modifier.weight(1f)
                    ) { Text("New tab") }
                    OutlinedButton(
                        onClick = { onNewTab(true); tabSwitcherOpen = false },
                        modifier = Modifier.weight(1f)
                    ) { Text("New private tab") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = { onDuplicateTab(tab); tabSwitcherOpen = false },
                        modifier = Modifier.weight(1f)
                    ) { Text("Duplicate current tab") }
                    TextButton(
                        onClick = { onCloseOtherTabs(tab); tabSwitcherOpen = false },
                        modifier = Modifier.weight(1f)
                    ) { Text("Close other tabs") }
                }
            }
        }
    }

    // The browser menu (full page).
    if (menuOpen) {
        BrowserMenuPage(
            store = store,
            tab = tab,
            uBlockInstalled = uBlockInstalled,
            onClose = { menuOpen = false },
            onNewTab = { isPrivate -> onNewTab(isPrivate) },
            onReload = { tab.reload() },
            onShare = { shareUrl(tabUrl) },
            onFind = { findOpen = true },
            onOpenScreen = { screen -> onOpenScreen(screen) },
            onCopyStatus = { onCopyStatus() },
            onInstallUBlock = { onInstallUBlock() },
            onCustomize = { customizeOpen = true }
        )
    }

    // Menu customization sheet.
    if (customizeOpen) {
        BrowserMenuCustomizeSheet(
            store = store,
            onDismiss = { customizeOpen = false }
        )
    }

    // Long-press link menu. This only ever appears when the
    // engine fires onLongPressLink — if GeckoView exposes no
    // long-press hook, the engine leaves it null and this menu
    // never shows (known gap, not a dead row: the trigger
    // itself is absent).
    if (longPressUrl != null) {
        val linkUrl = longPressUrl
        FrostDialog(
            onDismissRequest = { onLongPressHandled() },
            title = { Text("Link") },
            text = {
                Text(
                    linkUrl, maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Column {
                    TextButton(
                        onClick = { onOpenUrlInNewTab(linkUrl, false, true); onLongPressHandled() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open in new tab") }
                    TextButton(
                        onClick = { onOpenUrlInNewTab(linkUrl, false, false); onLongPressHandled() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open in background tab") }
                    TextButton(
                        onClick = { onOpenUrlInNewTab(linkUrl, true, true); onLongPressHandled() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open in private tab") }
                    TextButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("link", linkUrl))
                            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                            onLongPressHandled()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Copy link") }
                    TextButton(
                        onClick = { shareUrl(linkUrl); onLongPressHandled() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Share") }
                }
            },
            dismissButton = {
                TextButton(onClick = { onLongPressHandled() }) { Text("Cancel") }
            }
        )
    }

    // Permission dialog (Mariner BrowserScreen.kt:5839-5877,
    // verbatim): "Allow $host to use …?" Dismissing without
    // answering denies — a pending permission request must
    // never hang waiting for an answer.
    permPrompt?.let { prompt ->
        val host = prompt.host.ifBlank {
            runCatching { Uri.parse(prompt.origin).host }.getOrNull() ?: prompt.origin
        }
        FrostDialog(
            onDismissRequest = {
                prompt.onDeny()
                permPrompt = null
            },
            title = { Text("Allow $host to use") },
            text = {
                Text(
                    prompt.resources.joinToString(", ") { permResourceWord(it) } + "?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prompt.resources.forEach {
                        store.putSitePermission(host, it, "allow")
                    }
                    permPrompt = null
                    grantWithOsCheck(prompt, prompt.resources)
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = {
                    prompt.resources.forEach {
                        store.putSitePermission(host, it, "deny")
                    }
                    permPrompt = null
                    prompt.onDeny()
                }) { Text("Deny") }
            }
        )
    }

    // Download confirm dialog (Mariner
    // BrowserScreen.kt:5882-6010, verbatim).
    pendingDownload?.let { pending ->
        FrostDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text("Download file") },
            text = {
                Column {
                    Text(
                        pending.url, maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = confirmName,
                        onValueChange = { confirmName = it },
                        label = { Text("File name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Save to",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Audit D-M2: a private download never
                    // leaves the app folder — the public
                    // choice is not offered for it.
                    if (!pending.isPrivate) {
                        TextButton(
                            onClick = { confirmLocation = "public" },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                (if (confirmLocation == "public") "✓ " else "") +
                                    "Downloads folder",
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        Text(
                            "Private downloads always save to the app folder.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = { confirmLocation = "app" },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            (if (confirmLocation == "app") "✓ " else "") +
                                "App folder",
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    // Audit D-m13: quality choice, defaulting
                    // to the highest variant.
                    if (pendingVariants.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Quality",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        pendingVariants.forEachIndexed { i, variant ->
                            TextButton(
                                onClick = { pendingVariantIndex = i },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    (if (pendingVariantIndex == i) "✓ " else "") +
                                        variant.label,
                                    textAlign = TextAlign.Start,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = confirmDontAsk,
                            onCheckedChange = { confirmDontAsk = it }
                        )
                        Text(
                            "Don't ask again",
                            modifier = Modifier.clickable {
                                confirmDontAsk = !confirmDontAsk
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (confirmDontAsk) {
                            store.putSetting("confirmDownloads", "false")
                        }
                        askDownloadNotif()
                        if (pending.videoUrl != null && pending.audioUrl != null) {
                            Downloads.startMerge(
                                context, store, pending.title,
                                pending.videoUrl, pending.audioUrl,
                                referer = pending.referer,
                                isPrivate = pending.isPrivate,
                                location = confirmLocation,
                                fileNameOverride = confirmName
                            )
                        } else {
                            val chosen = pendingVariants.getOrNull(pendingVariantIndex)
                            Downloads.start(
                                context, store, pending.title,
                                chosen?.url ?: pending.url,
                                media = pending.media,
                                contentType = pending.contentType,
                                suggestedName = pending.suggestedName,
                                referer = pending.referer,
                                isPrivate = pending.isPrivate,
                                location = confirmLocation,
                                fileNameOverride = confirmName,
                                variantBandwidth = chosen?.bandwidth
                            )
                        }
                        pendingDownload = null
                        Toast.makeText(
                            context, "Download started", Toast.LENGTH_SHORT
                        ).show()
                    }
                ) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDownload = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Media sheet (§8.3 call site): tap the toolbar button
    // (or a video-popup long-press) after runMediaScan().
    if (mediaSheetOpen) {
        MediaSheet(
            media = MediaLog.forTab(tab.id),
            pageTitle = tab.title.value,
            pageUrl = tabUrl,
            isPrivate = tab.isPrivate,
            popupHost = pageHost,
            popupOff = VideoPopup.isOff(store, pageHost),
            onPopupOffChange = { off ->
                if (pageHost.isNotBlank()) {
                    store.putSetting(VideoPopup.offKey(pageHost), off.toString())
                }
            },
            castAvailable = castAvailable,
            playlistAddAllowed = false,
            onPlay = { media, startPip ->
                PlayerActivity.launch(
                    context, media.url, media.label.ifBlank { tab.title.value },
                    referer = tabUrl, privateSession = tab.isPrivate,
                    startPip = startPip
                )
                mediaSheetOpen = false
            },
            onDownload = { media ->
                mediaSheetOpen = false
                beginDownload(
                    media.label.ifBlank { media.url }, media.url,
                    media = true, contentType = null, suggestedName = null,
                    referer = tabUrl, isPrivate = tab.isPrivate
                )
            },
            onOpenPage = { media ->
                mediaSheetOpen = false
                openUrl(media.url)
            },
            onMerge = { items ->
                val video = items.firstOrNull { it.kind == "video" }
                val audio = items.firstOrNull { it.kind == "audio" }
                if (video != null && audio != null) {
                    mediaSheetOpen = false
                    beginMergeDownload(
                        video.label.ifBlank { video.url },
                        video.url, audio.url,
                        referer = tabUrl, isPrivate = tab.isPrivate
                    )
                }
            },
            onDismiss = { mediaSheetOpen = false }
        )
    }

    // Site-information sheet (Mariner
    // BrowserScreen.kt:5091-5172, adapted): connection line
    // + per-site Ad blocking + the Popups three-state row
    // (Mariner SettingsScreen.kt:466-484) + Strict popup
    // blocking (Mariner SettingsScreen.kt:1381-1383).
    // DROPPED vs Mariner: the JavaScript toggle (Pioneer
    // has no per-site JS engine switch in Phase 3) and the
    // cookie count / "Clear data for this site" row
    // (GeckoView exposes no cookie API).
    if (siteInfoOpen) {
        val infoHost = pageHost
        val infoSite = if (infoHost.isNotBlank()) store.siteSetting(infoHost) else null
        FrostDialog(
            onDismissRequest = { siteInfoOpen = false },
            title = { Text(infoHost.ifBlank { "This site" }) },
            text = {
                Column {
                    Text(
                        when {
                            tabUrl.startsWith("https://") -> "Secure connection."
                            tabUrl.startsWith("http://") -> "Not secure: this page is plain http."
                            else -> "Local page."
                        }
                    )
                    if (infoSite != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Ad blocking",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Switch(
                                checked = !infoSite.disableBlocking,
                                onCheckedChange = { enabled ->
                                    engine.setBlockingEnabled(infoHost, enabled)
                                    tab.reload()
                                }
                            )
                        }
                        // Popups is a three-state setting
                        // (blocked / allowed / always denied):
                        // a value row that cycles on tap, in
                        // the house row style.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    store.putSiteSetting(
                                        when {
                                            infoSite.allowPopups -> infoSite.copy(allowPopups = false, blockPopups = true)
                                            infoSite.blockPopups -> infoSite.copy(allowPopups = false, blockPopups = false)
                                            else -> infoSite.copy(allowPopups = true, blockPopups = false)
                                        }
                                    )
                                    tab.reload()
                                }
                                .padding(vertical = 7.dp)
                        ) {
                            Icon(
                                Icons.Filled.OpenInBrowser, contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.size(12.dp))
                            Text(
                                "Popups", modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                when {
                                    infoSite.allowPopups -> "Allowed"
                                    infoSite.blockPopups -> "Always denied"
                                    else -> "Blocked"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Icon(
                                Icons.Filled.KeyboardArrowDown, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Strict popup blocking",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Switch(
                                checked = store.setting("popupStrict", "true") == "true",
                                onCheckedChange = {
                                    store.putSetting("popupStrict", it.toString())
                                }
                            )
                        }
                        Text(
                            "Blocks every popup window, including ones a tap asks for. " +
                                "You can allow a blocked popup once, or always for a site below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 42.dp, top = 2.dp, bottom = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { siteInfoOpen = false }) { Text("Done") }
            }
        )
    }
}

/** The diagnostics view: the engine's status + media probe
 * lines, scrollable, with its own Copy status button. Kept
 * from the spike (Robert uses Copy status to send results). */
@Composable
fun DiagnosticsScreen(
    engine: Engine,
    onBack: () -> Unit,
    onCopyStatus: () -> Unit
) {
    SubScreen("Diagnostics", onBack = onBack) {
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { onCopyStatus() }) { Text("Copy status") }
            Text(
                engine.castRoutes.value,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "— status —",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace
            )
            engine.statusLines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            Text(
                "— media probe —",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace
            )
            if (engine.mediaLines.isEmpty()) {
                Text(
                    "(no media URLs reported yet — load a video page)",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                engine.mediaLines.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
