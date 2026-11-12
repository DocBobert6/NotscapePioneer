package net.nspioneer.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLEncoder
import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.engine.Engine
import net.nspioneer.browser.engine.EngineTab
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
