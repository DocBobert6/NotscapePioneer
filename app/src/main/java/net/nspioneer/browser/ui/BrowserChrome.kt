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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.engine.EngineTab

/**
 * Browser chrome carried from Mariner's BrowserChrome.kt, adapted
 * for Pioneer Phase 2: the find-in-page pills, the local
 * suggestions card, and the browser menu sheet.
 *
 * Adapted points:
 * - Package net.nspioneer.browser.ui, PioneerStore, EngineTab
 *   (no WebView, no TabState, no Trace).
 * - Icons: androidx.compose.material.icons.filled icons from
 *   icons-core and icons-extended (the extended set arrived
 *   in Phase 3 for the player glyphs). Rows without a safe
 *   icon render with a blank glyph slot so the text still
 *   aligns; MenuRow takes a nullable icon for that reason.
 * - Menu rows are the Phase 2 set plus the alpha.10 page
 *   actions (Save as PDF, Save offline, Scan QR,
 *   Screenshot). Omitted by design (their phases have not
 *   arrived — do not fake them): Reader view, Vault, Games,
 *   TTS, Playlists, Reading list, Bookshelf, Global ad
 *   blocking (uBlock Origin is the blocker in Pioneer).
 *   Downloads, Offline library, Add to home screen,
 *   Block an element (long-press) and Blocked-elements
 *   Manage all exist now.
 * - Desktop site is a real row: it flips the GeckoView
 *   user-agent + viewport to desktop via EngineTab.setDesktopMode
 *   and stores the choice per site; the choice is restored on
 *   relaunch. (The Phase 2 comment claiming it was omitted was
 *   stale — the row was implemented in Phase 2.)
 * - Zoom is a real control card in the menu (per-site text zoom
 *   from the store, applied via EngineTab.setTextZoom), not a row.
 */

/** One browser-menu row. [icon] may be null: the row then renders
 * a blank glyph slot so its text aligns with the icon rows. */
@Composable
internal fun MenuRow(label: String, icon: ImageVector?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
        } else {
            Spacer(Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Find in page: the typing pill hugs the address bar; the match
 * count and steppers live in a small pill at the top-right. */
@Composable
internal fun BoxScope.FindBar(
    barAtBottom: Boolean,
    immersive: Boolean,
    findText: String,
    findCount: Int,
    findOrdinal: Int,
    findFound: Boolean,
    onFindTextChange: (String) -> Unit,
    onFindNext: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val findFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { findFocus.requestFocus() }
    }
    Row(
        modifier = Modifier
            .align(
                if (barAtBottom) Alignment.BottomCenter
                else Alignment.TopCenter
            )
            .then(
                if (barAtBottom) {
                    Modifier.windowInsetsPadding(
                        WindowInsets.navigationBars
                    ).imePadding()
                } else if (!immersive) {
                    Modifier.windowInsetsPadding(
                        WindowInsets.statusBars
                    )
                } else {
                    Modifier
                }
            )
            .padding(
                start = 12.dp, end = 12.dp,
                bottom = if (barAtBottom) 76.dp else 0.dp,
                top = if (barAtBottom) 0.dp else 76.dp
            )
            .fillMaxWidth()
            .widthIn(max = 430.dp)
            .frostBar(CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = "Search",
            tint = MaterialTheme.colorScheme
                .onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
                .size(19.dp)
        )
        BasicTextField(
            value = findText,
            onValueChange = onFindTextChange,
            singleLine = true,
            textStyle = MaterialTheme.typography
                .bodyLarge.copy(
                    color = MaterialTheme.colorScheme
                        .onSurface
                ),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Search
            ),
            keyboardActions = KeyboardActions(
                onSearch = {
                    onFindNext(true)
                }
            ),
            modifier = Modifier.weight(1f)
                .padding(horizontal = 10.dp)
                .focusRequester(findFocus)
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close find",
                modifier = Modifier.size(19.dp)
            )
        }
    }
    Row(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .then(
                if (!immersive) {
                    Modifier.windowInsetsPadding(
                        WindowInsets.statusBars
                    )
                } else {
                    Modifier
                }
            )
            .padding(top = 10.dp, end = 12.dp)
            .frostBar(CircleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            // Batch 3: GeckoView reports an unknown match total
            // as -1 (FinderResult.total), and the old code rendered
            // that as "0 / 0" even when matches exist. An unknown
            // total renders count-only / "…" instead; "0 / 0" is
            // reserved for a finished search with no matches (and
            // for a finder error, which also reports found=false).
            when {
                findCount > 0 -> "${findOrdinal + 1} / $findCount"
                findCount == -1 && findFound && findOrdinal >= 0 -> "${findOrdinal + 1} / …"
                findCount == -1 && findFound -> "…"
                else -> "0 / 0"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        IconButton(
            onClick = {
                onFindNext(false)
            },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = "Previous match",
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(
            onClick = {
                onFindNext(true)
            },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "Next match",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** Local suggestions while typing in the address bar:
 * matches from this device's own bookmarks and history
 * only. Nothing is sent anywhere. */
@Composable
internal fun BoxScope.SuggestionsCard(
    suggestions: List<Triple<String, String, Boolean>>,
    barAtBottom: Boolean,
    immersive: Boolean,
    suggestionQuery: String,
    onPick: (String) -> Unit
) {
    key(suggestionQuery) {
        Card(
            modifier = Modifier
                .align(if (barAtBottom) Alignment.BottomCenter else Alignment.TopCenter)
                .then(
                    if (barAtBottom) Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                        .imePadding()
                    else if (!immersive) Modifier.windowInsetsPadding(WindowInsets.statusBars)
                    else Modifier
                )
                .padding(
                    start = 12.dp, end = 12.dp,
                    bottom = if (barAtBottom) 84.dp else 0.dp,
                    top = if (barAtBottom) 0.dp else 84.dp
                )
                .fillMaxWidth(),
            shape = Frost.CardShape,
            colors = CardDefaults.cardColors(
                containerColor = Frost.cardColor()
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                suggestions.forEach { (title, url, isBookmark) ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                onPick(url)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isBookmark) Icons.Filled.Star else Icons.Filled.Refresh,
                            contentDescription = if (isBookmark) "Bookmarked" else "History",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(
                                title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                url, maxLines = 1, overflow = TextOverflow.Ellipsis,
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

/** Browser menu customization: which menu rows show.
 * The hidden set is stored as one comma-separated setting
 * under the same keys Mariner uses ("menuHiddenRows" /
 * "menuRowOrder"), so the Customize sheet behaves the way
 * Mariner's does. parseHidden / parseOrder are pure so they
 * can be pinned by tests. */
internal object BrowserMenuRows {
    const val HIDDEN_KEY = "menuHiddenRows"
    private const val UNSET = "DEFAULT"
    val ALL: List<Pair<String, String>> = listOf(
        "newTab" to "New tab",
        "newPrivateTab" to "New private tab",
        "reload" to "Reload",
        "sharePage" to "Share page",
        "copyPageUrl" to "Copy page URL",
        "openOtherBrowser" to "Open in other browser",
        // Batch 4: pin the page to the launcher as an
        // app-mode shortcut (Mariner BrowserChrome.kt:820).
        "addToHome" to "Add to home screen",
        "bookmark" to "Bookmark this page",
        "favorites" to "Add to favorites",
        "findInPage" to "Find in page",
        // alpha.10: page actions, in Mariner's order.
        "savePdf" to "Save as PDF",
        "saveOffline" to "Save page offline",
        "scanQr" to "Scan QR code",
        "screenshot" to "Screenshot",
        "desktopSite" to "Desktop site",
        "bookmarks" to "Bookmarks",
        "favoritesManager" to "Favorites",
        "history" to "History",
        "downloads" to "Downloads",
        "offlineLibrary" to "Offline library",
        "installUBlock" to "Install uBlock Origin",
        "copyStatus" to "Copy status",
        "diagnostics" to "Diagnostics",
        "settings" to "Settings",
        "licences" to "Licences"
    )
    val DEFAULT_HIDDEN: Set<String> = emptySet()
    /** The menu's sections. A row's section is fixed (see
     * sectionOf); the user's custom order moves rows around,
     * and the rendered menu groups consecutive rows of one
     * section under its label — so a moved row carries its
     * section with it. */
    enum class MenuSection(val label: String) {
        TABS("Tabs"),
        PAGE("Page actions"),
        LIBRARY("Library"),
        SETTINGS("Settings")
    }
    fun sectionOf(id: String): MenuSection = when (id) {
        "newTab", "newPrivateTab" -> MenuSection.TABS
        "bookmarks", "favoritesManager", "history", "downloads", "offlineLibrary" -> MenuSection.LIBRARY
        "settings", "licences", "copyStatus", "diagnostics" -> MenuSection.SETTINGS
        else -> MenuSection.PAGE
    }
    /** Groups consecutive ids of one section, in order, so the
     * menu renders one labelled card per run. Pure, so tests
     * can pin the grouping — and the LazyColumn keys derived
     * from it. A section CAN appear in two runs (alpha.25):
     * in the default order installUBlock sits between the
     * LIBRARY and SETTINGS blocks yet sectionOf maps it to
     * PAGE, and a custom order can interleave any section —
     * so a group must never be keyed by its section label
     * alone, or the second run collides with the first and
     * LazyColumn throws on menu open. */
    fun groupedSections(ids: List<String>): List<Pair<MenuSection, List<String>>> {
        val out = mutableListOf<Pair<MenuSection, List<String>>>()
        var i = 0
        while (i < ids.size) {
            val section = sectionOf(ids[i])
            val group = ids.subList(i, ids.size)
                .takeWhile { sectionOf(it) == section }
            out += section to group
            i += group.size
        }
        return out
    }
    /** The default row order: tabs, page actions, library,
     * settings. Every id in ALL appears exactly once here. */
    val DEFAULT_ORDER: List<String> = ALL.map { it.first }
    /** The user's custom row order, stored as one
     * comma-separated setting. Unset means the default
     * order. Unknown ids are dropped; any row missing from
     * a stored order (new rows, old stores) appends in
     * default-order position, so an order can never lose a
     * row. */
    const val ORDER_KEY = "menuRowOrder"
    fun parseOrder(raw: String): List<String> {
        val known = ALL.map { it.first }.toSet()
        val base = if (raw == UNSET) DEFAULT_ORDER
            else raw.split(",").map { it.trim() }
                .filter { it.isNotEmpty() }.distinct()
        val kept = base.filter { it in known }
        return kept + DEFAULT_ORDER.filter { it !in kept.toSet() }
    }
    fun orderedIds(store: PioneerStore): List<String> =
        parseOrder(store.setting(ORDER_KEY, UNSET))
    fun setOrder(store: PioneerStore, ids: List<String>) {
        store.putSetting(ORDER_KEY, ids.joinToString(","))
    }
    /** Move one row by [delta] places in the custom order
     * (delta -1 = up, +1 = down); clamps at the ends. */
    fun moved(order: List<String>, id: String, delta: Int): List<String> {
        val list = order.toMutableList()
        val from = list.indexOf(id)
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (from < 0 || from == to) return list
        list.removeAt(from)
        list.add(to, id)
        return list
    }
    fun move(store: PioneerStore, id: String, delta: Int) {
        setOrder(store, moved(orderedIds(store), id, delta))
    }
    /** Rows that persist something (a bookmark, a favourite)
     * and therefore never render for a private tab: a
     * private tab saves nothing. */
    val PRIVATE_HIDDEN: Set<String> =
        // Mariner's set, plus saveOffline + screenshot (Mariner
        // BrowserChrome.kt:441): a private tab saves nothing.
        setOf(
            "bookmark", "favorites", "openOtherBrowser", "addToHome",
            "saveOffline", "screenshot"
        )
    /** One authority for the private-tab menu: a row shows for a
     * private tab only when it is not a persisting row. */
    fun visibleForTab(id: String, isPrivate: Boolean): Boolean =
        !isPrivate || id !in PRIVATE_HIDDEN
    fun parseHidden(raw: String): Set<String> =
        if (raw == UNSET) DEFAULT_HIDDEN
        else raw.split(",").map { it.trim() }
            .filter { it.isNotEmpty() }.toSet()
    fun hiddenIds(store: PioneerStore): Set<String> =
        parseHidden(store.setting(HIDDEN_KEY, UNSET))
    fun isVisible(store: PioneerStore, id: String): Boolean =
        id !in hiddenIds(store)
    fun setVisible(store: PioneerStore, id: String, visible: Boolean) {
        val hidden = hiddenIds(store).toMutableSet()
        if (visible) hidden.remove(id) else hidden.add(id)
        store.putSetting(HIDDEN_KEY, hidden.joinToString(","))
    }
    fun reset(store: PioneerStore) {
        store.putSetting(
            HIDDEN_KEY, DEFAULT_HIDDEN.joinToString(",")
        )
        store.putSetting(ORDER_KEY, UNSET)
    }
}

/** One browser-menu row that honours the user's menu
 * customization: a hidden row simply does not render. */
@Composable
internal fun MenuRowVisible(
    store: PioneerStore,
    id: String,
    label: String,
    icon: ImageVector?,
    isPrivate: Boolean = false,
    onClick: () -> Unit
) {
    if (BrowserMenuRows.isVisible(store, id) &&
        BrowserMenuRows.visibleForTab(id, isPrivate)
    ) {
        MenuRow(label, icon) {
            onClick()
        }
    }
}

/** The "Customize menu" editor: every menu row, in the menu's
 * current order, each with a show/hide switch and move
 * up/down buttons. The editor itself is never hideable, so the
 * menu always has a way back here. Reset restores both the
 * default visibility and the default order. */
@Composable
internal fun BrowserMenuCustomizeSheet(
    store: PioneerStore,
    onDismiss: () -> Unit
) {
    FrostSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Customize menu",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            val order = BrowserMenuRows.orderedIds(store)
            val labels = BrowserMenuRows.ALL.toMap()
            GroupedCard {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    order.forEachIndexed { index, id ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(
                                    start = 20.dp, end = 8.dp,
                                    top = 4.dp, bottom = 4.dp
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                labels[id] ?: id,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    BrowserMenuRows.move(store, id, -1)
                                },
                                enabled = index > 0
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowUp,
                                    contentDescription = "Move up"
                                )
                            }
                            IconButton(
                                onClick = {
                                    BrowserMenuRows.move(store, id, 1)
                                },
                                enabled = index < order.lastIndex
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Move down"
                                )
                            }
                            Switch(
                                checked = BrowserMenuRows.isVisible(store, id),
                                onCheckedChange = { checked ->
                                    BrowserMenuRows.setVisible(
                                        store, id, checked
                                    )
                                }
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = { BrowserMenuRows.reset(store) }) {
                    Text("Reset to defaults")
                }
            }
        }
    }
}

/** One browser-menu row as a slot: its id, whether it shows
 * for this tab and store, and the row itself. The menu
 * renders slots in the user's customized order, grouping
 * consecutive rows of one section under a label. */
private class MenuSlot(
    val id: String,
    val shows: Boolean,
    val content: @Composable () -> Unit
)

/** Per-site text zoom steps (factors). */
private val ZOOM_STEPS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

/** The single browser menu: every Phase 2 menu row, grouped
 * under section labels and rendered in the user's customized
 * order, hosted in a bottom sheet (alpha.17) — see
 * BrowserMenuSheet. Rows honour the user's menu
 * customization; the zoom card and the "Customize menu" row
 * are not customizable rows. Swipe down or back returns to
 * the browser.
 *
 * Alpha.24: this emits its rows as LazyColumn items
 * (LazyListScope), so the sheet composes only visible rows
 * and the scroll position lives in the sheet's hoisted list
 * state rather than in this content. It is a plain function,
 * not a composable — the context the copy-URL row needs is
 * passed in from the sheet. */
internal fun LazyListScope.BrowserMenuRowsContent(
    context: Context,
    store: PioneerStore,
    tab: EngineTab,
    uBlockInstalled: Boolean,
    onDismiss: () -> Unit,
    onNewTab: (Boolean) -> Unit,
    onReload: () -> Unit,
    onShare: () -> Unit,
    onFind: () -> Unit,
    onOpenScreen: (PioneerScreen) -> Unit,
    onCopyStatus: () -> Unit,
    onInstallUBlock: () -> Unit,
    onCustomize: () -> Unit,
    // Batch 4: pin the page to the launcher (app mode).
    onAddToHome: () -> Unit,
    // alpha.10: page actions. Screenshot + PDF capture the
    // visible page in the shell (capturePixels); Save offline
    // asks the probe for the page HTML; Scan QR opens the
    // camera dialog.
    onSavePdf: () -> Unit,
    onSaveOffline: () -> Unit,
    onScreenshot: () -> Unit,
    onScanQr: () -> Unit
) {
    val rev = store.revision
    val url = tab.url.value
    val title = tab.title.value
    val isPrivate = tab.isPrivate
    val hasPage = url.isNotBlank()
    val bookmarkedNow = store.isBookmarked(url)
    val favouritedNow = store.isFavourite(url)
    // MenuRowVisible still decides rendering (customization
    // + private-tab rule); the slot's `shows` flag mirrors
    // that decision plus the row's own condition, so the
    // grouping pass never renders an empty section card.
    fun shows(id: String, extra: Boolean = true): Boolean =
        extra && BrowserMenuRows.isVisible(store, id) &&
            BrowserMenuRows.visibleForTab(id, isPrivate)
    val slots = listOf(
        MenuSlot("newTab", shows("newTab")) {
            MenuRowVisible(store, "newTab", "New tab", Icons.Filled.Add) {
                onDismiss(); onNewTab(false)
            }
        },
        MenuSlot("newPrivateTab", shows("newPrivateTab")) {
            MenuRowVisible(store, "newPrivateTab", "New private tab", Icons.Filled.Lock) {
                onDismiss(); onNewTab(true)
            }
        },
        MenuSlot("reload", shows("reload", hasPage)) {
            MenuRowVisible(store, "reload", "Reload", Icons.Filled.Refresh) {
                onDismiss(); onReload()
            }
        },
        MenuSlot("sharePage", shows("sharePage", hasPage)) {
            MenuRowVisible(store, "sharePage", "Share page", Icons.Filled.Share) {
                onDismiss(); onShare()
            }
        },
        MenuSlot("copyPageUrl", shows("copyPageUrl", hasPage)) {
            MenuRowVisible(store, "copyPageUrl", "Copy page URL", Icons.Filled.ContentCopy) {
                onDismiss()
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("link", url))
                Toast.makeText(context, "Page URL copied", Toast.LENGTH_SHORT).show()
            }
        },
        MenuSlot("openOtherBrowser", shows("openOtherBrowser", hasPage)) {
            MenuRowVisible(store, "openOtherBrowser", "Open in other browser", Icons.Filled.OpenInBrowser, isPrivate = isPrivate) {
                onDismiss()
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    context.startActivity(Intent.createChooser(intent, "Open with"))
                }.onFailure { android.util.Log.w("Pioneer", "BrowserChrome.openWith: ${it.javaClass.simpleName}") }
            }
        },
        // Batch 4: pin the page to the launcher as an app-mode
        // shortcut. Mariner's row icon was not confirmed from
        // the read, so this uses the Home icon.
        MenuSlot("addToHome", shows("addToHome", hasPage)) {
            MenuRowVisible(store, "addToHome", "Add to home screen", Icons.Filled.Home, isPrivate = isPrivate) {
                onDismiss(); onAddToHome()
            }
        },
        MenuSlot("bookmark", shows("bookmark", hasPage)) {
            MenuRowVisible(store, "bookmark",
                if (bookmarkedNow) "Remove bookmark" else "Bookmark this page",
                Icons.Filled.Star,
                isPrivate = isPrivate
            ) {
                onDismiss()
                if (url.isNotBlank()) store.toggleBookmark(title, url)
            }
        },
        MenuSlot("favorites", shows("favorites", hasPage)) {
            MenuRowVisible(store, "favorites",
                if (favouritedNow) "Remove from favorites" else "Add to favorites",
                Icons.Filled.Favorite,
                isPrivate = isPrivate
            ) {
                onDismiss()
                store.toggleFavourite(title, url)
            }
        },
        MenuSlot("findInPage", shows("findInPage", hasPage)) {
            MenuRowVisible(store, "findInPage", "Find in page", Icons.Filled.Search) {
                onDismiss(); onFind()
            }
        },
        // alpha.10: page actions, in Mariner's order.
        MenuSlot("savePdf", shows("savePdf", hasPage)) {
            MenuRowVisible(store, "savePdf", "Save as PDF", Icons.Filled.PictureAsPdf) {
                onDismiss(); onSavePdf()
            }
        },
        MenuSlot("saveOffline", shows("saveOffline", hasPage)) {
            MenuRowVisible(store, "saveOffline", "Save page offline", Icons.Filled.Download, isPrivate = isPrivate) {
                onDismiss(); onSaveOffline()
            }
        },
        MenuSlot("scanQr", shows("scanQr", hasPage)) {
            MenuRowVisible(store, "scanQr", "Scan QR code", Icons.Filled.QrCodeScanner, isPrivate = isPrivate) {
                onDismiss(); onScanQr()
            }
        },
        MenuSlot("screenshot", shows("screenshot", hasPage)) {
            MenuRowVisible(store, "screenshot", "Screenshot", Icons.Filled.Screenshot, isPrivate = isPrivate) {
                onDismiss(); onScreenshot()
            }
        },
        MenuSlot("desktopSite", shows("desktopSite", hasPage)) {
            val host = Uri.parse(url).host ?: ""
            val desktopNow = host.isNotBlank() && store.siteDesktop(host)
            MenuRowVisible(store, "desktopSite",
                if (desktopNow) "Desktop site ✓" else "Desktop site",
                Icons.Filled.DesktopWindows
            ) {
                onDismiss()
                if (host.isNotBlank()) {
                    val next = !store.siteDesktop(host)
                    store.putSiteDesktop(host, next)
                    tab.setDesktopMode(next)
                }
            }
        },
        MenuSlot("bookmarks", shows("bookmarks")) {
            MenuRowVisible(store, "bookmarks", "Bookmarks", Icons.Filled.Star) { onDismiss(); onOpenScreen(PioneerScreen.Bookmarks) }
        },
        MenuSlot("favoritesManager", shows("favoritesManager")) {
            MenuRowVisible(store, "favoritesManager", "Favorites", Icons.Filled.Favorite) { onDismiss(); onOpenScreen(PioneerScreen.Favorites) }
        },
        MenuSlot("history", shows("history")) {
            MenuRowVisible(store, "history", "History", Icons.Filled.History) { onDismiss(); onOpenScreen(PioneerScreen.History) }
        },
        MenuSlot("downloads", shows("downloads")) {
            MenuRowVisible(store, "downloads", "Downloads", Icons.Filled.Download) { onDismiss(); onOpenScreen(PioneerScreen.Downloads) }
        },
        MenuSlot("offlineLibrary", shows("offlineLibrary")) {
            MenuRowVisible(store, "offlineLibrary", "Offline library", Icons.Filled.Download) { onDismiss(); onOpenScreen(PioneerScreen.Library) }
        },
        MenuSlot("installUBlock", shows("installUBlock", !uBlockInstalled)) {
            MenuRowVisible(store, "installUBlock", "Install uBlock Origin", null) {
                onDismiss(); onInstallUBlock()
            }
        },
        MenuSlot("copyStatus", shows("copyStatus")) {
            MenuRowVisible(store, "copyStatus", "Copy status", Icons.Filled.ContentCopy) { onDismiss(); onCopyStatus() }
        },
        MenuSlot("diagnostics", shows("diagnostics")) {
            MenuRowVisible(store, "diagnostics", "Diagnostics", Icons.Filled.Info) { onDismiss(); onOpenScreen(PioneerScreen.Diagnostics) }
        },
        MenuSlot("settings", shows("settings")) {
            MenuRowVisible(store, "settings", "Settings", Icons.Filled.Settings) { onDismiss(); onOpenScreen(PioneerScreen.Settings) }
        },
        MenuSlot("licences", shows("licences")) {
            MenuRowVisible(store, "licences", "Licences", Icons.Filled.Info) { onDismiss(); onOpenScreen(PioneerScreen.Licences) }
        }
    )
    // Per-site text zoom, as a control card rather than a row:
    // the current zoom for this host, with step buttons and a
    // reset. Stored per host in the store and applied to the
    // tab at once; the shell re-applies it on navigation.
    if (hasPage) {
        val host = Uri.parse(url).host ?: ""
        if (host.isNotBlank()) {
            val zoom = store.siteZoom(host)
            item(key = "zoom") {
                GroupedCard {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Text size: ${(zoom * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                val next = ZOOM_STEPS.lastOrNull { it < zoom } ?: ZOOM_STEPS.first()
                                store.putSiteZoom(host, next)
                                tab.setTextZoom(next)
                            },
                            enabled = zoom > ZOOM_STEPS.first()
                        ) { Text("−") }
                        TextButton(
                            onClick = {
                                val next = ZOOM_STEPS.firstOrNull { it > zoom } ?: ZOOM_STEPS.last()
                                store.putSiteZoom(host, next)
                                tab.setTextZoom(next)
                            },
                            enabled = zoom < ZOOM_STEPS.last()
                        ) { Text("+") }
                        TextButton(
                            onClick = {
                                store.putSiteZoom(host, 1.0f)
                                tab.setTextZoom(1.0f)
                            },
                            enabled = zoom != 1.0f
                        ) { Text("Reset") }
                    }
                }
            }
            item(key = "zoomSpacer") { Spacer(modifier = Modifier.size(12.dp)) }
        }
    }
    // Render in the customized order, grouping consecutive
    // rows of one section into one labelled card. Each group
    // is keyed by its FIRST row's id (alpha.25): a section
    // can appear in two runs, so a label-derived key would
    // collide and LazyColumn would throw on menu open. Both
    // the label and the card key use the first row's id.
    val byId = slots.associateBy { it.id }
    val inOrder = BrowserMenuRows.orderedIds(store)
        .mapNotNull { byId[it] }.filter { it.shows }.map { it.id }
    for ((section, ids) in BrowserMenuRows.groupedSections(inOrder)) {
        val group = ids.mapNotNull { byId[it] }
        item(key = "label:${group.first().id}") {
            Text(
                section.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
            )
        }
        item(key = "card:${group.first().id}") {
            GroupedCard {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    group.forEach { it.content() }
                }
            }
        }
    }
    item(key = "customize") {
        GroupedCard {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                MenuRow("Customize menu", Icons.Filled.Tune) {
                    onDismiss(); onCustomize()
                }
            }
        }
    }
}

/** The browser menu as a bottom sheet (alpha.17): the same
 * rows content as the old full page, in a FrostSheet that
 * opens at its full height (skipPartiallyExpanded) and caps
 * at 85% of the screen, so the browser stays visible
 * behind it and long lists scroll. The "Menu" heading sits
 * at the top of the sheet beside the drag handle. Swipe
 * down or back returns to the browser.
 *
 * Alpha.24: the rows live in a LazyColumn whose list state
 * is hoisted here, so the scroll position survives
 * rows-content recomposition and only visible rows are
 * composed. The sheet's drag is gated on that state: it
 * may drag only while the list is at its top edge, so
 * flings scroll content only and swipe-down-to-dismiss
 * still works. */
@Composable
internal fun BrowserMenuSheet(
    store: PioneerStore,
    tab: EngineTab,
    uBlockInstalled: Boolean,
    onClose: () -> Unit,
    onNewTab: (Boolean) -> Unit,
    onReload: () -> Unit,
    onShare: () -> Unit,
    onFind: () -> Unit,
    onOpenScreen: (PioneerScreen) -> Unit,
    onCopyStatus: () -> Unit,
    onInstallUBlock: () -> Unit,
    onCustomize: () -> Unit,
    onAddToHome: () -> Unit,
    onSavePdf: () -> Unit,
    onSaveOffline: () -> Unit,
    onScreenshot: () -> Unit,
    onScanQr: () -> Unit
) {
    // alpha.24: the list state is hoisted here so the
    // scroll position survives rows-content recomposition
    // and only visible rows are composed.
    val context = LocalContext.current
    val listState = rememberLazyListState()
    // alpha.24, fix 1 — the drag handoff: the sheet may
    // drag only while the list is at its top edge. A
    // child-side NestedScrollConnection cannot gate the
    // sheet (the sheet's own connection is the parent and
    // sees pre-scroll first), so the gate lives on the
    // sheet's own gesture flag instead. derivedStateOf
    // means this only recomposes when the at-top boolean
    // flips, not on every scroll pixel. While the list is
    // mid-scroll the sheet takes no motion, so a fling can
    // no longer hand velocity to the sheet and snap back;
    // at the top edge a downward drag still dismisses the
    // sheet, as before.
    val atTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
        }
    }
    FrostSheet(
        onDismiss = onClose,
        draggable = atTop,
        skipPartiallyExpanded = true
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "heading") {
                Text(
                    "Menu",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            BrowserMenuRowsContent(
                context = context,
                store = store,
                tab = tab,
                uBlockInstalled = uBlockInstalled,
                onDismiss = onClose,
                onNewTab = onNewTab,
                onReload = onReload,
                onShare = onShare,
                onFind = onFind,
                onOpenScreen = onOpenScreen,
                onCopyStatus = onCopyStatus,
                onInstallUBlock = onInstallUBlock,
                onCustomize = onCustomize,
                onAddToHome = onAddToHome,
                onSavePdf = onSavePdf,
                onSaveOffline = onSaveOffline,
                onScreenshot = onScreenshot,
                onScanQr = onScanQr
            )
        }
    }
}
