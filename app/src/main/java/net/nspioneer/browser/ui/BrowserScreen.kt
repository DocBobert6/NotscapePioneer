package net.nspioneer.browser.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.speech.RecognizerIntent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.DragAndDropTarget
import androidx.compose.foundation.draganddrop.DragAndDropTransferData
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.draganddrop.toAndroidDragEvent
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.data.TabSnapshot
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
 * - tab grid overlay: per-class grids (normal vs private), thumbnails
 *   captured from the GeckoView (GeckoView.capturePixels), search,
 *   grid/list toggle, drag-reorder, swipe-to-close with Undo,
 *   recently-closed, close-duplicate + close-all per class.
 *   Thumbnails are memory-only, never persisted — a tab with no
 *   still yet shows its title rows, as Mariner does.
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

/** One row of the Back/Forward history sheet: the title and URL
 *  to show, [steps] the signed jump from the current page, and
 *  [index] the absolute session-history index to jump to. */
private data class HistorySheetEntry(
    val title: String,
    val url: String,
    val steps: Int,
    val index: Int
)

/** Mariner BrowserScreenParts.historySheetEntries, verbatim:
 *  the entries in [direction] from the current index, nearest
 *  first: [items] are (title, url) pairs in session order,
 *  [currentIndex] the page showing now, [direction] -1 for back,
 *  +1 for forward. Anything else is empty. A blank title falls
 *  back to the URL. */
private fun historySheetEntries(
    currentIndex: Int,
    items: List<Pair<String, String>>,
    direction: Int
): List<HistorySheetEntry> {
    if (direction != -1 && direction != 1) return emptyList()
    val out = ArrayList<HistorySheetEntry>()
    var i = currentIndex + direction
    while (i in items.indices) {
        val (title, url) = items[i]
        out.add(
            HistorySheetEntry(
                title = title.ifBlank { url },
                url = url,
                steps = i - currentIndex,
                index = i
            )
        )
        i += direction
    }
    return out
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
    // Batch 2: close-duplicate + close-all (per class) and
    // drag-reorder within a class (Mariner TabList).
    onCloseDuplicateTabs: (EngineTab) -> Unit,
    onCloseAllTabs: (Boolean) -> Unit,
    onMoveTab: (EngineTab, Int) -> Unit,
    // Batch 2: recently closed (normal tabs only).
    closedTabs: List<TabSnapshot>,
    onRemoveClosedTab: (TabSnapshot) -> Unit,
    onClearClosedTabs: () -> Unit,
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
    // ---- Batch 2: tab grid + history sheet + undo ----
    // historySheetDir: 0 = closed, -1 = back history, +1 = forward.
    var historySheetDir by remember { mutableStateOf(0) }
    // Which privacy class the grid shows; set to the active tab's
    // class every time the grid opens (Mariner gridPrivate).
    var gridPrivate by remember { mutableStateOf(false) }
    var tabSearch by remember { mutableStateOf("") }
    var showRecentlyClosed by remember { mutableStateOf(false) }
    // Undo snackbar (Mariner BrowserScreen.kt:5047-5060): the host
    // sits bottom-centre with 88dp clearance, above the bar.
    val snackHost = remember { SnackbarHostState() }
    val snackScope = rememberCoroutineScope()
    // Tab thumbnails, memory-only (Mariner tabPreviews): one
    // 320-px still per tab, recycled on replacement/close.
    val tabPreviews = remember { mutableStateMapOf<Int, Bitmap>() }
    // The single GeckoView, captured from the AndroidView factory
    // so thumbnails can be taken (GeckoView.capturePixels). A plain
    // holder, not state — only event handlers read it.
    val geckoViewBox = remember { arrayOf<GeckoView?>(null) }
    var findOpen by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var editingAddress by remember { mutableStateOf(false) }
    // Mariner's field value (BrowserScreen.kt:3830): a
    // TextFieldValue so focusing the pill selects the whole
    // URL, and a mic result lands selected too.
    var fieldValue by remember(tab.id) {
        mutableStateOf(TextFieldValue(if (isHome) "" else tabUrl))
    }

    // ---- Batch 1: floating address bar ----
    // Reading revision subscribes this screen to store
    // mutations, so a barPosition change in Settings
    // moves the bar the moment the user comes back.
    @Suppress("UNUSED_VARIABLE")
    val storeRev = store.revision
    val barAtBottom = store.setting("barPosition", "bottom") != "top"
    // Scroll-hide (Mariner BrowserScreen.kt:2604-2607):
    // down past 80px hides the bar, any up-scroll shows
    // it. The bar never hides while typing or on the
    // start page (the AnimatedVisibility condition).
    var barHidden by remember { mutableStateOf(false) }
    var lastScrollY by remember { mutableIntStateOf(0) }
    // Pull-to-refresh (Mariner BrowserScreen.kt:2200-2246):
    // measured on the GeckoView itself; pullOffsetPx
    // drives the frost indicator.
    var pullRefreshing by remember { mutableStateOf(false) }
    var pullOffsetPx by remember { mutableFloatStateOf(0f) }
    val pullTriggerPx = with(LocalDensity.current) { 84.dp.toPx() }
    var pullStartX by remember { mutableFloatStateOf(0f) }
    var pullStartY by remember { mutableFloatStateOf(0f) }
    var pullTracking by remember { mutableStateOf(false) }
    // Voice input (Mariner BrowserScreen.kt:3870-3911):
    // the mic result waits here until the bar takes
    // focus, then lands in the field, selected.
    var micPending by remember { mutableStateOf<String?>(null) }
    val barFocusRequester = remember { FocusRequester() }
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val heard = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!heard.isNullOrBlank()) {
                micPending = heard
                barFocusRequester.requestFocus()
            }
        }
    }

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
        if (!editingAddress) {
            fieldValue = TextFieldValue(if (isHome) "" else tabUrl)
        }
    }
    // Scroll-hide: the engine's ScrollDelegate feeds
    // tab.scrollY; Mariner's thresholds, verbatim
    // (BrowserScreen.kt:2604-2607). A tab switch resets
    // the reference point and shows the bar again.
    LaunchedEffect(tab.id) {
        lastScrollY = tab.scrollY.value
        barHidden = false
    }
    LaunchedEffect(tab.scrollY.value) {
        val y = tab.scrollY.value
        when {
            y > lastScrollY + 6 && y > 80 -> barHidden = true
            y < lastScrollY - 6 -> barHidden = false
        }
        lastScrollY = y
    }
    // A finished load ends a pull-to-refresh (Mariner
    // BrowserScreen.kt:3172).
    LaunchedEffect(tab.progress.value) {
        if (tab.progress.value >= 100) pullRefreshing = false
    }
    // Batch 2: opening the grid captures the active tab's still
    // first, so its card shows the page just left (Mariner).
    LaunchedEffect(tabSwitcherOpen) {
        if (tabSwitcherOpen) captureTabPreview(tab.id)
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

    /** Mariner BrowserScreenParts.thumbnailSizeFor, verbatim: a
     *  320-px-wide still preserving the page aspect. */
    fun thumbnailSizeFor(viewW: Int, viewH: Int, maxW: Int = 320): Pair<Int, Int> {
        if (viewW <= 0 || viewH <= 0 || maxW <= 0) return 0 to 0
        val w = minOf(viewW, maxW)
        val h = ((viewH.toLong() * w) / viewW).toInt().coerceAtLeast(1)
        return w to h
    }

    /** Capture the visible page of [tabId] into tabPreviews
     *  (Mariner captureTabPreview, Gecko-shaped: only the
     *  ACTIVE tab can be captured — it is the one attached to
     *  the single GeckoView. Captures run as the grid opens,
     *  never around a switch: capturePixels is async, so a
     *  capture requested just before a session switch could
     *  land the incoming tab's pixels. A blank/home tab has
     *  nothing to capture. */
    fun captureTabPreview(tabId: Int) {
        val view = geckoViewBox[0] ?: return
        val t = tabs.firstOrNull { it.id == tabId } ?: return
        if (t.url.value.isBlank()) return
        runCatching {
            view.capturePixels().accept(
                { bitmap ->
                    if (bitmap == null) return@accept
                    val (tw, th) = thumbnailSizeFor(bitmap.width, bitmap.height)
                    if (tw <= 0 || th <= 0) {
                        bitmap.recycle()
                        return@accept
                    }
                    val scaled = Bitmap.createScaledBitmap(bitmap, tw, th, true)
                    if (scaled !== bitmap) bitmap.recycle()
                    tabPreviews[tabId]?.recycle()
                    tabPreviews[tabId] = scaled
                },
                { _ -> }
            )
        }
    }

    /** Free the stills of tabs that no longer exist (Mariner
     *  frees a tab's still in closeTab; bulk closes go through
     *  the shell, so the grid prunes after each bulk action). */
    fun prunePreviews() {
        tabPreviews.keys.filter { id -> tabs.none { it.id == id } }.forEach { id ->
            tabPreviews.remove(id)?.recycle()
        }
    }

    /** Close a tab from the grid with an Undo snackbar (Mariner
     *  closeTab(offerUndo = true)): the shell records the close
     *  in recently-closed either way; Undo reopens the URL in a
     *  new tab. Private tabs and blank tabs offer no Undo. */
    fun closeTabWithUndo(t: EngineTab) {
        val url = t.url.value.trim()
        val wasPrivate = t.isPrivate
        onCloseTab(t)
        prunePreviews()
        if (!wasPrivate && url.isNotBlank() && url != "about:blank") {
            snackScope.launch {
                if (snackHost.showSnackbar("Tab closed", actionLabel = "Undo") ==
                    SnackbarResult.ActionPerformed
                ) {
                    onOpenUrlInNewTab(url, false, true)
                }
            }
        }
    }

    /** Switch to [target] from the grid, then close it. The
     *  outgoing tab's still was already captured when the grid
     *  opened — capturing here would race the session switch
     *  (capturePixels is async) and could store the incoming
     *  tab's pixels under the outgoing tab's id. */
    fun switchToTab(target: EngineTab) {
        val idx = tabs.indexOf(target)
        if (idx >= 0) onSwitchTab(idx)
        tabSwitcherOpen = false
    }

    val suggestions = remember(fieldValue.text, editingAddress, store.revision) {
        if (!editingAddress) emptyList()
        else {
            val q = fieldValue.text.trim()
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

    // Back chain (MainActivity calls this first; false means
    // "nothing left here — exit"): close overlays, then engine
    // back, then the start page, then close the tab; the last
    // home tab exits the app. Declared here, after the sheet /
    // popup state and clearPendingPopups it closes over, which
    // are declared above in the Phase 3 section.
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

    /** Mariner's floating address bar (BrowserScreen.kt:3663-3995),
     *  ported: frost capsule groups, satellite circles beside
     *  it. The download pill and vault chip are NOT here — Phase 3
     *  / Phase 4 scope; they ride in the same column when they
     *  land. Long-press Back/Forward opens the history sheet
     *  (Batch 2, Mariner beta.69 #58). */
    @Composable
    fun addressBar() {
        val pillShape = RoundedCornerShape(50)
        val contentColor = MaterialTheme.colorScheme.onSurface
        val frost = Modifier.frostBar(pillShape)
        var dragTotal by remember { mutableStateOf(0f) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (tab.canGoBack.value || tab.canGoForward.value) {
                Row(modifier = frost) {
                    if (tab.canGoBack.value) {
                        // Batch 2 (Mariner beta.69 #58): long-press
                        // lists the back history; a tap jumps there.
                        Box(
                            modifier = Modifier.size(44.dp)
                                .combinedClickable(
                                    onClick = { tab.goBack() },
                                    onLongClick = { historySheetDir = -1 }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.ChevronLeft, contentDescription = "Back", tint = contentColor, modifier = Modifier.size(24.dp))
                        }
                    }
                    if (tab.canGoForward.value) {
                        // Batch 2 (Mariner beta.69 #58): long-press
                        // lists the forward history; a tap jumps there.
                        Box(
                            modifier = Modifier.size(44.dp)
                                .combinedClickable(
                                    onClick = { tab.goForward() },
                                    onLongClick = { historySheetDir = 1 }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.ChevronRight, contentDescription = "Forward", tint = contentColor, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
            Box(
                modifier = Modifier.weight(1f).then(frost)
                    .pointerInput(tabs.size, tab.id) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragTotal = 0f },
                            onHorizontalDrag = { _, amount -> dragTotal += amount },
                            onDragEnd = {
                                // Swiping the pill moves within the
                                // current tab class only (normal ↔
                                // normal, private ↔ private).
                                val siblings = tabs.filter { it.isPrivate == tab.isPrivate }
                                val idx = siblings.indexOfFirst { it.id == tab.id }
                                val target = when {
                                    dragTotal < -90f && idx in 0 until siblings.size - 1 -> siblings[idx + 1]
                                    dragTotal > 90f && idx > 0 -> siblings[idx - 1]
                                    else -> null
                                }
                                if (target != null) {
                                    val targetIdx = tabs.indexOf(target)
                                    if (targetIdx >= 0) onSwitchTab(targetIdx)
                                }
                                dragTotal = 0f
                            },
                            onDragCancel = { dragTotal = 0f }
                        )
                    }
            ) {
                // Loading progress fills the pill itself, in the
                // theme's ink (Mariner BrowserScreen.kt:3741).
                val progress = tab.progress.value
                if (progress in 1..99) {
                    Box(modifier = Modifier.matchParentSize().clip(pillShape)) {
                        Box(
                            modifier = Modifier.fillMaxHeight()
                                .fillMaxWidth(progress / 100f)
                                .background(
                                    Brush.horizontalGradient(
                                        0f to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f),
                                        0.7f to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f),
                                        1f to MaterialTheme.colorScheme.onSurface.copy(alpha = 0f)
                                    )
                                )
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!editingAddress) {
                        // Home: the start page is a home state per
                        // tab in Pioneer (no homePage setting);
                        // long-press opens it in a new tab of the
                        // SAME class (Mariner beta.69 #58).
                        Box(
                            modifier = Modifier.size(34.dp)
                                .combinedClickable(
                                    onClick = { homeTabIds.add(tab.id) },
                                    onLongClick = { onNewTab(tab.isPrivate) }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Home, contentDescription = "Home", tint = contentColor, modifier = Modifier.size(19.dp))
                        }
                    }
                    if (tab.isPrivate) {
                        Icon(
                            Icons.Filled.Lock, contentDescription = "Private tab",
                            tint = contentColor, modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    // The lock opens the site-information sheet
                    // (Mariner BrowserScreen.kt:3799-3812).
                    IconButton(
                        onClick = { siteInfoOpen = true },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            if (tabUrl.startsWith("https://")) Icons.Filled.Lock
                            else Icons.Filled.LockOpen,
                            contentDescription = "Site information",
                            tint = contentColor, modifier = Modifier.size(19.dp)
                        )
                    }
                    BasicTextField(
                        value = fieldValue,
                        onValueChange = { fieldValue = it },
                        modifier = Modifier.weight(1f).focusRequester(barFocusRequester).onFocusChanged { st ->
                            if (st.isFocused && !editingAddress) {
                                editingAddress = true
                                val pending = micPending
                                micPending = null
                                fieldValue = if (pending != null) {
                                    TextFieldValue(
                                        pending,
                                        TextRange(0, pending.length)
                                    )
                                } else {
                                    TextFieldValue(
                                        tabUrl,
                                        TextRange(0, tabUrl.length)
                                    )
                                }
                            } else if (!st.isFocused && editingAddress) {
                                editingAddress = false
                                fieldValue = TextFieldValue(if (isHome) "" else tabUrl)
                            }
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = contentColor,
                            fontSize = 16.sp,
                            // Unfocused text starts at the beginning
                            // of the address: a centered long URL
                            // would show its middle, cut at both ends.
                            textAlign = TextAlign.Start
                        ),
                        cursorBrush = SolidColor(contentColor),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Go,
                            keyboardType = KeyboardType.Uri,
                            autoCorrectEnabled = false,
                            capitalization = KeyboardCapitalization.None
                        ),
                        keyboardActions = KeyboardActions(onGo = {
                            goAddress(fieldValue.text)
                        }),
                        decorationBox = { inner ->
                            if (fieldValue.text.isEmpty()) {
                                Text(
                                    "Search or enter address",
                                    color = contentColor.copy(alpha = 0.55f),
                                    fontSize = 16.sp, maxLines = 1,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            inner()
                        }
                    )
                    IconButton(
                        onClick = {
                            val intent = Intent(
                                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                            ).apply {
                                putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                )
                                putExtra(
                                    RecognizerIntent.EXTRA_PROMPT,
                                    "Speak a search or address"
                                )
                            }
                            runCatching { speechLauncher.launch(intent) }
                                .onFailure {
                                    Toast.makeText(
                                        context,
                                        "Voice search is not available on this device",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Filled.Mic, contentDescription = "Voice search",
                            tint = contentColor, modifier = Modifier.size(19.dp)
                        )
                    }
                    if (editingAddress && fieldValue.text.isNotEmpty()) {
                        IconButton(
                            onClick = { fieldValue = TextFieldValue("") },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear", tint = contentColor, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            if (editingAddress) {
                TextButton(onClick = { focusManager.clearFocus() }) { Text("Cancel") }
            } else {
                // While a page is loading, the bar offers Stop
                // next to the tab count; it hides the moment
                // nothing is loading (progress 100 or idle).
                if (tab.progress.value in 1..99) {
                    Box(
                        modifier = Modifier.size(44.dp).then(frost)
                            .clickable { tab.stop() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Close, contentDescription = "Stop loading",
                            tint = contentColor, modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Box(
                    modifier = Modifier.size(44.dp).then(frost)
                        .combinedClickable(
                            onClick = {
                                // The grid opens on the active tab's
                                // class (Mariner gridPrivate).
                                gridPrivate = tab.isPrivate
                                tabSearch = ""
                                tabSwitcherOpen = true
                            },
                            // Long-press opens a new tab without
                            // opening the switcher.
                            onLongClick = { onNewTab(tab.isPrivate) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = contentColor.copy(alpha = 0.14f),
                        modifier = Modifier.size(25.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "${tabs.count { it.isPrivate == tab.isPrivate }}",
                                style = MaterialTheme.typography.titleSmall,
                                color = contentColor
                            )
                        }
                    }
                }
                // Media on this page (§8.3): the Phase 3
                // MediaToolbarButton, already a 44dp frost
                // circle in Mariner's shape.
                if (!isHome && MediaLog.forTab(tab.id).isNotEmpty()) {
                    MediaToolbarButton(
                        onClick = { openMediaSheet() },
                        onLongClick = { playFirstVideo() }
                    )
                }
                Box(
                    modifier = Modifier.size(44.dp).then(frost)
                        .combinedClickable(
                            onClick = { menuOpen = true },
                            // Long-press jumps straight to Settings.
                            onLongClick = { onOpenScreen(PioneerScreen.Settings) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.MoreHoriz, contentDescription = "Menu", tint = contentColor, modifier = Modifier.size(22.dp))
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // The engine view, full-bleed; the start page overlays
        // it while this tab is in its home state. The bar
        // floats over both (below).
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx -> GeckoView(ctx).also { geckoViewBox[0] = it } },
                update = { view ->
                    view.setSession(tab.session)
                    // Pull-to-refresh (Mariner
                    // BrowserScreen.kt:2200-2246): this listener
                    // only OBSERVES (always returns false): a
                    // downward drag that starts with the page at
                    // its very top moves the indicator, and past
                    // the trigger it reloads. Scrolling and taps
                    // are untouched.
                    view.setOnTouchListener { v, e ->
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                pullStartX = e.x
                                pullStartY = e.y
                                pullTracking = tab.scrollY.value == 0 && !pullRefreshing
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (pullTracking) {
                                    if (tab.scrollY.value > 0) {
                                        pullTracking = false
                                        pullOffsetPx = 0f
                                    } else {
                                        val dy = e.y - pullStartY
                                        val dx = kotlin.math.abs(e.x - pullStartX)
                                        val slop = 24f * v.resources.displayMetrics.density
                                        pullOffsetPx =
                                            if (dy > slop && dy > dx * 1.6f) {
                                                ((dy - slop) * 0.55f).coerceAtMost(
                                                    pullTriggerPx * 1.5f
                                                )
                                            } else {
                                                0f
                                            }
                                    }
                                }
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                if (pullTracking && pullOffsetPx >= pullTriggerPx) {
                                    pullRefreshing = true
                                    tab.reload()
                                }
                                pullTracking = false
                                pullOffsetPx = 0f
                            }
                        }
                        false
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            if (isHome) {
                StartPage(
                    store = store,
                    onOpen = { url -> openUrl(url) },
                    contentTopPad = if (barAtBottom) 0.dp else 92.dp,
                    contentBottomPad = if (barAtBottom) 92.dp else 0.dp
                )
            }
            // Pull-to-refresh indicator (Mariner
            // BrowserScreen.kt:4168-4200): a frost circle at the
            // top, sliding down with the pull and rotating as it
            // nears the trigger.
            if (pullOffsetPx > 0f || pullRefreshing) {
                Box(
                    modifier = Modifier.align(Alignment.TopCenter)
                        .offset {
                            IntOffset(0, (pullOffsetPx * 0.45f).toInt())
                        }
                        .padding(top = 16.dp)
                        .size(38.dp)
                        .frostBar(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (pullRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Icon(
                            Icons.Filled.ArrowDownward,
                            contentDescription = "Pull to refresh",
                            modifier = Modifier.size(20.dp)
                                .rotate(
                                    (pullOffsetPx / pullTriggerPx)
                                        .coerceIn(0f, 1f) * 180f
                                ),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // The floating bar (Mariner BrowserScreen.kt:4381-4410):
        // the whole bar leaves on scroll-down and slides back
        // on scroll-up. It never hides while the user is typing
        // in it, or on the start page (nothing to scroll).
        AnimatedVisibility(
            visible = !barHidden || editingAddress || isHome,
            modifier = Modifier
                .align(if (barAtBottom) Alignment.BottomCenter else Alignment.TopCenter)
                .then(
                    if (barAtBottom) Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                        .imePadding()
                    else Modifier.windowInsetsPadding(WindowInsets.statusBars)
                )
                .padding(top = if (barAtBottom) 0.dp else 10.dp, bottom = if (barAtBottom) 10.dp else 0.dp),
            enter = slideInVertically { if (barAtBottom) it else -it } + fadeIn(),
            exit = slideOutVertically { if (barAtBottom) it else -it } + fadeOut()
        ) {
            addressBar()
        }

        // Local suggestions over the page while typing.
        if (editingAddress && suggestions.isNotEmpty()) {
            SuggestionsCard(
                suggestions = suggestions,
                barAtBottom = barAtBottom,
                immersive = false,
                suggestionQuery = fieldValue.text,
                onPick = { url ->
                    goAddress(url)
                }
            )
        }

        // Find in page.
        if (findOpen) {
            val result = tab.findResult.value
            FindBar(
                barAtBottom = barAtBottom,
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

    // Batch 2 — tab grid (Mariner BrowserScreen.kt:4573-5030):
    // full-screen overlay, opaque gradient backdrop, per-class
    // grids, thumbnails, search, grid/list toggle, drag-reorder,
    // swipe-to-close with Undo, recently-closed, close-duplicate
    // + close-all per class, floating pill action bar.
    if (tabSwitcherOpen) {
        Box(
            modifier = Modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceContainer
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(16.dp)
            ) {
                Spacer(Modifier.height(12.dp))
                // Private-mode indicator beyond the lock icon
                // (audit §2): what private means, where private
                // tabs are managed.
                if (gridPrivate) {
                    Text(
                        "Private tabs — never saved to history or the session. " +
                            "Their data is wiped when the last private tab closes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                OutlinedTextField(
                    value = tabSearch,
                    onValueChange = { tabSearch = it },
                    placeholder = { Text("Search tabs") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            val keep = tabs.firstOrNull {
                                it.id == tab.id && it.isPrivate == gridPrivate
                            } ?: tabs.firstOrNull { it.isPrivate == gridPrivate }
                            keep?.let { onCloseOtherTabs(it); prunePreviews() }
                        },
                        enabled = tabs.count { it.isPrivate == gridPrivate } > 1
                    ) { Text("Close others") }
                    TextButton(
                        onClick = {
                            val keep = tabs.firstOrNull {
                                it.id == tab.id && it.isPrivate == gridPrivate
                            } ?: tabs.firstOrNull { it.isPrivate == gridPrivate }
                            keep?.let { onCloseDuplicateTabs(it); prunePreviews() }
                        },
                        enabled = tabs.filter { it.isPrivate == gridPrivate }
                            .groupBy { it.url.value.trim() }
                            .any { (url, ts) -> url.isNotEmpty() && ts.size > 1 }
                    ) { Text("Close duplicates") }
                    TextButton(
                        onClick = { onCloseAllTabs(gridPrivate); prunePreviews() },
                        enabled = tabs.any { it.isPrivate == gridPrivate }
                    ) { Text("Close all") }
                    TextButton(onClick = {
                        val cur = store.setting("tabViewMode", "grid")
                        store.putSetting(
                            "tabViewMode",
                            if (cur == "list") "grid" else "list"
                        )
                    }) {
                        Text(
                            text = if (store.setting("tabViewMode", "grid") == "list") "Grid view" else "List view",
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (!gridPrivate && closedTabs.isNotEmpty()) {
                        TextButton(onClick = { showRecentlyClosed = true }) {
                            Text("Recently closed (${closedTabs.size})")
                        }
                    }
                }
                val gridTabs = tabs.filter {
                    it.isPrivate == gridPrivate &&
                        (tabSearch.isBlank() ||
                            it.title.value.contains(tabSearch, ignoreCase = true) ||
                            it.url.value.contains(tabSearch, ignoreCase = true))
                }
                val listMode =
                    store.setting("tabViewMode", "grid") == "list"
                // No tab groups: one flat list, in strip
                // order, for the visible privacy class.
                if (tabs.none { it.isPrivate == gridPrivate }) {
                    Text(
                        if (gridPrivate) "No private tabs yet — open one below." else "No tabs.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                if (gridTabs.isEmpty() && tabs.any { it.isPrivate == gridPrivate }) {
                    Text(
                        "No tabs match your search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (listMode) 1 else 2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(gridTabs, key = { it.id }) { t ->
                        // Swipe a card sideways to close the tab (the
                        // ✕ button still works too).
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart ||
                                    value == SwipeToDismissBoxValue.StartToEnd
                                ) {
                                    closeTabWithUndo(t)
                                    true
                                } else false
                            }
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                Box(
                                    modifier = Modifier.fillMaxSize()
                                        .background(
                                            MaterialTheme.colorScheme.errorContainer,
                                            RoundedCornerShape(20.dp)
                                        )
                                        .padding(horizontal = 16.dp),
                                    contentAlignment = if (dismissState.dismissDirection ==
                                        SwipeToDismissBoxValue.StartToEnd
                                    ) Alignment.CenterStart else Alignment.CenterEnd
                                ) {
                                    Icon(
                                        Icons.Filled.Close, contentDescription = "Close tab",
                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth()
                                    .height(if (listMode) 80.dp else 150.dp)
                                    .dragAndDropTarget(
                                        shouldStartDragAndDrop = { event ->
                                            val idText = event.toAndroidDragEvent().clipData
                                                .getItemAt(0)?.text?.toString()
                                            val id = idText?.toIntOrNull()
                                            val dragged = tabs.firstOrNull { it.id == id }
                                            dragged != null &&
                                                dragged.isPrivate == t.isPrivate
                                        },
                                        target = object : DragAndDropTarget {
                                            override fun onDrop(
                                                event: androidx.compose.ui.draganddrop.DragAndDropEvent
                                            ): Boolean {
                                                val id = event.toAndroidDragEvent().clipData
                                                    .getItemAt(0)?.text?.toString()
                                                    ?.toIntOrNull() ?: return false
                                                val dragged = tabs.firstOrNull { it.id == id }
                                                    ?: return false
                                                if (dragged.isPrivate != t.isPrivate) {
                                                    return false
                                                }
                                                val idx = tabs.filter { it.isPrivate == t.isPrivate }
                                                    .indexOf(t)
                                                if (idx >= 0) {
                                                    onMoveTab(dragged, idx)
                                                }
                                                return true
                                            }
                                        }
                                    )
                                    .combinedClickable(
                                        onClick = { switchToTab(t) }
                                    ),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (t.id == tab.id)
                                        Frost.cardColor()
                                    else MaterialTheme.colorScheme.surfaceContainerLow
                                ),
                                border = BorderStroke(
                                    if (t.id == tab.id) 2.dp else 0.5.dp,
                                    if (t.id == tab.id)
                                        MaterialTheme.colorScheme.primary
                                    else Frost.hairline()
                                ),
                                shape = Frost.CardShape
                            ) {
                                val preview = tabPreviews[t.id]
                                val isStartPage = t.id in homeTabIds || t.url.value.isBlank()
                                val hostText = runCatching { Uri.parse(t.url.value).host }.getOrNull()
                                    ?: t.url.value
                                if (listMode) {
                                    // List rows carry a small still of the
                                    // tab beside the title/host, from the
                                    // same memory-only tabPreviews map the
                                    // grid uses; no still yet keeps the
                                    // title/host row unchanged.
                                    Row(
                                        modifier = Modifier.fillMaxSize().padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (preview != null) {
                                            Image(
                                                bitmap = preview.asImageBitmap(),
                                                contentDescription = "Tab preview",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.width(56.dp).fillMaxHeight()
                                                    .clip(RoundedCornerShape(8.dp))
                                            )
                                            Spacer(Modifier.width(10.dp))
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (t.isPrivate) {
                                                    Icon(
                                                        Icons.Filled.Lock, contentDescription = "Private",
                                                        modifier = Modifier.size(14.dp),
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                    Spacer(Modifier.width(4.dp))
                                                }
                                                Text(
                                                    if (isStartPage) "Start page"
                                                    else t.title.value.ifBlank { "New tab" },
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                IconButton(
                                                    onClick = { onDuplicateTab(t) },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate tab", modifier = Modifier.size(15.dp))
                                                }
                                                // Long-press the handle to
                                                // drag this card within its
                                                // class (Mariner audit P-M7).
                                                IconButton(
                                                    onClick = {},
                                                    modifier = Modifier.size(28.dp)
                                                        .dragAndDropSource { _ ->
                                                            DragAndDropTransferData(
                                                                clipData = ClipData.newPlainText(
                                                                    "tab",
                                                                    t.id.toString()
                                                                )
                                                            )
                                                        }
                                                ) {
                                                    Icon(
                                                        Icons.Filled.DragHandle,
                                                        contentDescription = "Drag to reorder",
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { closeTabWithUndo(t) },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(Icons.Filled.Close, contentDescription = "Close tab", modifier = Modifier.size(16.dp))
                                                }
                                            }
                                            if (!isStartPage) {
                                                Text(
                                                    hostText,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (t.isPrivate) {
                                                Icon(
                                                    Icons.Filled.Lock, contentDescription = "Private",
                                                    modifier = Modifier.size(14.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(Modifier.width(4.dp))
                                            }
                                            Text(
                                                if (isStartPage) "Start page"
                                                else t.title.value.ifBlank { "New tab" },
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.titleSmall,
                                                modifier = Modifier.weight(1f)
                                            )
                                            IconButton(
                                                onClick = { onDuplicateTab(t) },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate tab", modifier = Modifier.size(15.dp))
                                            }
                                            // Long-press the handle to drag
                                            // this card within its class
                                            // (Mariner audit P-M7).
                                            IconButton(
                                                onClick = {},
                                                modifier = Modifier.size(28.dp)
                                                    .dragAndDropSource { _ ->
                                                        DragAndDropTransferData(
                                                            clipData = ClipData.newPlainText(
                                                                "tab",
                                                                t.id.toString()
                                                            )
                                                        )
                                                    }
                                            ) {
                                                Icon(
                                                    Icons.Filled.DragHandle,
                                                    contentDescription = "Drag to reorder",
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            IconButton(
                                                onClick = { closeTabWithUndo(t) },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Filled.Close, contentDescription = "Close tab", modifier = Modifier.size(16.dp))
                                            }
                                        }
                                        if (!isStartPage) {
                                            Text(
                                                hostText,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (preview != null) {
                                            Image(
                                                bitmap = preview.asImageBitmap(),
                                                contentDescription = "Tab preview",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.weight(1f).fillMaxWidth()
                                                    .padding(top = 6.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .frostBar(RoundedCornerShape(50), 8.dp)
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            onNewTab(gridPrivate)
                            tabSwitcherOpen = false
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = if (gridPrivate) "New private tab" else "New tab",
                            modifier = Modifier.size(25.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Box(
                        modifier = Modifier.weight(1f)
                            .clip(RoundedCornerShape(50))
                            .clickable { gridPrivate = !gridPrivate }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (gridPrivate) {
                                Icon(
                                    Icons.Filled.Lock,
                                    contentDescription = "Private tabs",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                if (gridPrivate) "Private • ${tabs.size} tabs (${tabs.count { it.isPrivate }} private)"
                                else "${tabs.size} tabs (${tabs.count { it.isPrivate }} private)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                        }
                    }
                    IconButton(
                        onClick = { tabSwitcherOpen = false },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Done",
                            modifier = Modifier.size(25.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
    // Batch 2 — Back/Forward history sheet (Mariner
    // BrowserScreen.kt:6448-6510, beta.69 #58): long-press Back
    // or Forward lists the history in that direction; a tap
    // jumps straight there instead of stepping one page at a
    // time. Entries come from the tab's SessionState, so no
    // WebView back/forward list is needed.
    if (historySheetDir != 0) {
        val entries = tab.historyEntries.value
        val currentIndex = entries.indexOfFirst { it.isCurrent }
            .takeIf { it >= 0 } ?: (entries.size - 1)
        val sheetEntries = historySheetEntries(
            currentIndex,
            entries.map { it.title to it.url },
            historySheetDir
        )
        FrostSheet(onDismiss = { historySheetDir = 0 }) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 28.dp)
            ) {
                Text(
                    if (historySheetDir < 0) "Back" else "Forward",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                if (sheetEntries.isEmpty()) {
                    Text(
                        "No pages in that direction.",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                } else {
                    val sheetMax = (LocalConfiguration.current
                        .screenHeightDp * 0.6f).dp
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = sheetMax)
                    ) {
                        items(sheetEntries, key = { it.index }) { entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        tab.goToHistoryIndex(entry.index)
                                        historySheetDir = 0
                                    }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        entry.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        entry.url,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Batch 2 — recently closed (Mariner BrowserScreen.kt:4531-4571):
    // a tap reopens the tab and drops it from the list.
    if (showRecentlyClosed) {
        FrostDialog(
            onDismissRequest = { showRecentlyClosed = false },
            title = { Text("Recently closed") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(closedTabs.toList(), key = { it.url }) { snap ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    snap.title.ifBlank { "Untitled" },
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = {
                                Text(snap.url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            modifier = Modifier.clickable {
                                onRemoveClosedTab(snap)
                                showRecentlyClosed = false
                                tabSwitcherOpen = false
                                onOpenUrlInNewTab(snap.url, false, true)
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearClosedTabs()
                        showRecentlyClosed = false
                    }
                ) { Text("Clear list") }
            },
            dismissButton = {
                TextButton(onClick = { showRecentlyClosed = false }) { Text("Close") }
            }
        )
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

    // Batch 2 — Undo snackbar (Mariner BrowserScreen.kt:5047-5060):
    // no BoxScope reaches function top level, so the host gets
    // its own full-screen bottom-centred Box; the Box itself
    // takes no touches, so taps pass through to the browser
    // below except on a shown snackbar.
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        SnackbarHost(
            hostState = snackHost,
            modifier = Modifier.padding(bottom = 88.dp)
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
