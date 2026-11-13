package net.nspioneer.browser.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nspioneer.browser.data.Bookmark
import net.nspioneer.browser.data.DownloadRecord
import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.data.folderChild
import net.nspioneer.browser.data.folderChildren
import net.nspioneer.browser.data.folderName
import net.nspioneer.browser.data.folderParent
import net.nspioneer.browser.data.isFolderSegment
import net.nspioneer.browser.media.Downloads
import net.nspioneer.browser.media.PlayerActivity
import net.nspioneer.browser.media.downloadNotificationsEnabled
import net.nspioneer.browser.media.openAppNotificationSettings
import net.nspioneer.browser.media.rememberAskDownloadNotifPermission

/**
 * Library screens carried from Mariner's LibraryScreens.kt:
 * Bookmarks, History, Favorites, Downloads and the Offline
 * library. Playlists and Reading List are dropped — their
 * phases have not arrived.
 *
 * Adapted points:
 * - PioneerStore (no Room/AppStore), folder helpers from the
 *   data package, export via store.exportBookmarksHtml().
 * - Favicons are fetched, not letter tiles (Batch 3): rows
 *   use [rememberSiteFavicon], which fetches
 *   `https://<host>/favicon.ico` once and caches it; the
 *   fallback is Mariner's generic Public icon. (GeckoView
 *   supplies no favicon path, Bug 1770989.)
 * - History undo restores exactly via
 *   [PioneerStore.restoreHistory] (Batch 3, mirroring
 *   Mariner): the deleted entries come back with their
 *   original ids and timestamps. Bookmark undo already did
 *   this via restoreBookmarks.
 * - GroupTitle / SettingsCard / InsetDivider are carried here
 *   from Mariner's SettingsScreen.kt (they are shared kit
 *   pieces, not settings logic).
 * - Downloads / Offline library (Mariner LibraryScreens.kt
 *   1261-2639, §8.4): the two screens share one row
 *   ([DownloadRow]) instead of Mariner's duplicated row
 *   code; behaviour, strings and order are identical.
 *   Mariner's games zip-import button and the saved-pages
 *   (offlinePages) section are DROPPED — no games in
 *   Phase 3, no saved pages in Pioneer. Pioneer has no
 *   R.string download strings, so the two literals are
 *   written out: "Retrying…" and
 *   "Couldn't delete that download."
 */

@Composable
fun SubScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        AppHeader(title = title, onBack = onBack)
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme
                        .surfaceContainerLow
                )
        ) { content() }
    }
}

/** The kit list row for the library screens: a Frost card
 * surface with a leading slot, a title and supporting lines
 * in kit type, and a trailing slot. */
@Composable
fun KitRow(
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    title: @Composable () -> Unit,
    supporting: (@Composable () -> Unit)? = null
) {
    Surface(
        shape = Frost.CardShape,
        color = Frost.cardColor(),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .then(
                    if (onClick != null) {
                        Modifier.clickable { onClick() }
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                title()
                if (supporting != null) supporting()
            }
            if (trailing != null) trailing()
        }
    }
}

/** The kit's selectable pill: filled with the primary colour
 * when selected, a quiet container colour otherwise. */
@Composable
fun KitPill(
    label: String,
    selected: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier.heightIn(min = 48.dp).clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 14.dp, vertical = 8.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A row inside a grouped card: no surface of its own — it
 * sits directly on a SettingsCard, with the leading slot
 * centred in a fixed-width column so the text starts align
 * down the card. */
@Composable
fun KitGroupedRow(
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    leadingWidth: Dp = 30.dp,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    title: @Composable () -> Unit,
    supporting: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier
                }
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            Box(
                modifier = Modifier.width(leadingWidth),
                contentAlignment = Alignment.Center
            ) { leading() }
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            title()
            if (supporting != null) supporting()
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun GroupTitle(text: String) {
    Spacer(Modifier.height(22.dp))
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp)
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    // The design kit's grouped card: bright card on the tinted
    // backdrop the library pages wear.
    GroupedCard {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            content()
        }
    }
}

@Composable
fun InsetDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 42.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    )
}

/** One consistent frosted action bar for the Library pages
 *  (alpha.18): the page's actions in a floating card, in
 *  the same order everywhere — Sort, then the page's own
 *  actions, then Select. Horizontally scrollable so the
 *  longer download bars fit on narrow screens. The
 *  optional second row is the Downloads page's
 *  notifications recovery ("Download notifications are
 *  off" + Enable). */
@Composable
fun LibraryActionBar(
    modifier: Modifier = Modifier,
    notification: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit
) {
    GroupedCard(modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) { actions() }
        if (notification != null) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            )
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) { notification() }
        }
    }
}

/** The standard empty state for the Library pages
 *  (alpha.18): a frosted card with an icon and the
 *  message — the same on all five pages. The card wears
 *  the kit card colour, so it floats on the tinted
 *  backdrop instead of vanishing into it (PanelCard's
 *  default colour is the backdrop colour itself). */
@Composable
fun LibraryEmptyState(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier
) {
    PanelCard(
        modifier = modifier.padding(horizontal = 16.dp),
        containerColor = Frost.cardColor()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The History day-group label. */
internal fun dayLabel(visitedAt: Long): String {
    val cal = java.util.Calendar.getInstance().apply {
        timeInMillis = visitedAt
    }
    val now = java.util.Calendar.getInstance()
    fun sameDay(
        a: java.util.Calendar, b: java.util.Calendar
    ) = a.get(java.util.Calendar.ERA) ==
        b.get(java.util.Calendar.ERA) &&
        a.get(java.util.Calendar.YEAR) ==
        b.get(java.util.Calendar.YEAR) &&
        a.get(java.util.Calendar.DAY_OF_YEAR) ==
        b.get(java.util.Calendar.DAY_OF_YEAR)
    if (sameDay(cal, now)) return "Today"
    now.add(java.util.Calendar.DAY_OF_YEAR, -1)
    if (sameDay(cal, now)) return "Yesterday"
    return java.text.SimpleDateFormat(
        "EEE, MMM d, yyyy", java.util.Locale.US
    ).format(java.util.Date(visitedAt))
}

/** A row's site icon: the fetched favicon for the host, else
 *  Mariner's generic Public icon. Batch 3: letter tiles are
 *  gone — see [rememberSiteFavicon]. */
@Composable
private fun RowFavicon(store: PioneerStore, url: String) {
    val fav = rememberSiteFavicon(store, url)
    if (fav != null) {
        Image(
            bitmap = fav.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
    } else {
        Icon(
            Icons.Filled.Public,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun BookmarksScreen(store: PioneerStore, onBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    val rev = store.revision
    val context = LocalContext.current
    // Folder filter: null = All, "" = Unfiled, otherwise a folder name.
    var folderFilter by remember { mutableStateOf<String?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showManageFolders by remember { mutableStateOf(false) }
    var renameFolder by remember { mutableStateOf<String?>(null) }
    var confirmDeleteFolder by remember { mutableStateOf<String?>(null) }
    var moveTarget by remember { mutableStateOf<Bookmark?>(null) }
    var bookmarkQuery by remember { mutableStateOf("") }
    var editBookmark by remember { mutableStateOf<Bookmark?>(null) }
    var bookmarkSort by remember { mutableStateOf("newest") }
    var bookmarkSelecting by remember { mutableStateOf(false) }
    var bookmarkSelected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val snackHost = remember { SnackbarHostState() }
    val snackScope = rememberCoroutineScope()
    var showBulkMove by remember { mutableStateOf(false) }
    // Export bookmarks as Netscape HTML, the same format the
    // Settings import reads.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        if (uri != null) {
            val text = store.exportBookmarksHtml()
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray())
                } ?: error("no stream")
            }.isSuccess
            Toast.makeText(
                context,
                if (ok) "Exported ${store.bookmarks.size} bookmarks"
                else "Export failed",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    SubScreen("Bookmarks", onBack) {
        FrostSearchBar(
            value = bookmarkQuery,
            onValueChange = { bookmarkQuery = it },
            placeholder = "Search bookmarks"
        )
        LibraryActionBar {
            TextButton(onClick = {
                bookmarkSort = when (bookmarkSort) {
                    "newest" -> "oldest"
                    "oldest" -> "title"
                    else -> "newest"
                }
            }) {
                Text(
                    when (bookmarkSort) {
                        "oldest" -> "Sort: oldest"
                        "title" -> "Sort: title"
                        else -> "Sort: newest"
                    }
                )
            }
            TextButton(onClick = { exportLauncher.launch("bookmarks.html") }) {
                Text("Export")
            }
            TextButton(onClick = {
                bookmarkSelecting = !bookmarkSelecting
                if (!bookmarkSelecting) bookmarkSelected = emptySet()
            }) { Text(if (bookmarkSelecting) "Done" else "Select") }
            if (bookmarkSelected.isNotEmpty()) {
                TextButton(onClick = { showBulkMove = true }) {
                    Text("Move (${bookmarkSelected.size})")
                }
                TextButton(onClick = {
                    val deleted = store.bookmarks.filter { it.id in bookmarkSelected }
                    bookmarkSelected.forEach { store.removeBookmark(it) }
                    bookmarkSelected = emptySet()
                    bookmarkSelecting = false
                    snackScope.launch {
                        if (snackHost.showSnackbar(
                                if (deleted.size == 1) "Deleted 1 bookmark"
                                else "Deleted ${deleted.size} bookmarks",
                                actionLabel = "Undo"
                            ) == SnackbarResult.ActionPerformed
                        ) {
                            store.restoreBookmarks(deleted)
                        }
                    }
                }) { Text("Delete (${bookmarkSelected.size})") }
            }
        }
        // The row shows a breadcrumb back up the current path,
        // then that folder's children (as segments); the top
        // level shows every top-level folder, Unfiled, New
        // folder and Manage folders.
        val childFolders = folderChildren(
            store.bookmarkFolders, folderFilter ?: ""
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (folderFilter != null && folderFilter!!.isNotBlank()) {
                item {
                    KitPill(
                        label = "All",
                        selected = false,
                        onClick = { folderFilter = null }
                    )
                }
                var path = ""
                for (seg in folderFilter!!.split("/")) {
                    path = if (path.isEmpty()) seg else "$path/$seg"
                    val p = path
                    item {
                        KitPill(
                            label = seg,
                            selected = p == folderFilter,
                            onClick = { folderFilter = p }
                        )
                    }
                }
            } else {
                item {
                    KitPill(
                        label = "All",
                        selected = folderFilter == null,
                        onClick = { folderFilter = null }
                    )
                }
                item {
                    KitPill(
                        label = "Unfiled",
                        selected = folderFilter == "",
                        onClick = { folderFilter = "" }
                    )
                }
            }
            items(childFolders) { f ->
                KitPill(
                    label = folderName(f),
                    selected = folderFilter == f,
                    onClick = { folderFilter = f }
                )
            }
            item {
                KitPill(
                    label = "New folder",
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    onClick = { showNewFolder = true }
                )
            }
            if (store.bookmarkFolders.isNotEmpty()) {
                item {
                    KitPill(
                        label = "Manage folders",
                        onClick = { showManageFolders = true }
                    )
                }
            }
        }
        val shown = store.bookmarks.filter { b ->
            (folderFilter == null || b.folder == folderFilter) &&
                (bookmarkQuery.isBlank() ||
                    b.title.contains(bookmarkQuery, ignoreCase = true) ||
                    b.url.contains(bookmarkQuery, ignoreCase = true))
        }.let { list ->
            when (bookmarkSort) {
                "oldest" -> list.sortedBy { it.createdAt }
                "title" -> list.sortedBy { it.title.lowercase() }
                else -> list.sortedByDescending { it.createdAt }
            }
        }
        if (store.bookmarks.isEmpty()) {
            LibraryEmptyState(
                icon = Icons.Filled.Star,
                text = "No bookmarks yet. Star a page to add one."
            )
        } else if (shown.isEmpty()) {
            LibraryEmptyState(
                icon = Icons.Filled.Star,
                text = if (bookmarkQuery.isNotBlank()) "No bookmarks match your search."
                else "Nothing in this folder yet."
            )
        }
        if (shown.isNotEmpty()) {
            LazyColumn {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        GroupTitle(
                            when {
                                folderFilter == null -> "All"
                                folderFilter == "" -> "Unfiled"
                                else -> folderFilter!!
                            }
                        )
                        SettingsCard {
                            shown.forEachIndexed { i, b ->
                                if (i > 0) InsetDivider()
                                KitGroupedRow(
                                    onClick = {
                                        if (bookmarkSelecting) {
                                            bookmarkSelected =
                                                if (b.id in bookmarkSelected) bookmarkSelected - b.id
                                                else bookmarkSelected + b.id
                                        } else onOpenUrl(b.url)
                                    },
                                    leading = {
                                        if (bookmarkSelecting) {
                                            Checkbox(
                                                checked = b.id in bookmarkSelected,
                                                onCheckedChange = {
                                                    bookmarkSelected =
                                                        if (b.id in bookmarkSelected) bookmarkSelected - b.id
                                                        else bookmarkSelected + b.id
                                                }
                                            )
                                        } else {
                                            RowFavicon(store, b.url)
                                        }
                                    },
                                    title = {
                                        Text(
                                            b.title, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    },
                                    supporting = {
                                        Column {
                                            Text(
                                                b.url, maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (folderFilter == null && b.folder.isNotBlank()) {
                                                Text(
                                                    b.folder, maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    trailing = {
                                        Row {
                                            IconButton(onClick = { editBookmark = b }) {
                                                Icon(Icons.Filled.Edit, contentDescription = "Edit bookmark")
                                            }
                                            IconButton(onClick = { moveTarget = b }) {
                                                Icon(Icons.Filled.MoreVert, contentDescription = "Move to folder")
                                            }
                                            IconButton(onClick = { store.toggleBookmark(b.title, b.url) }) {
                                                Icon(Icons.Filled.Delete, contentDescription = "Remove")
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    // Edit title + URL, re-validated to http(s).
    editBookmark?.let { b ->
        var titleDraft by remember(b.id) { mutableStateOf(b.title) }
        var urlDraft by remember(b.id) { mutableStateOf(b.url) }
        val urlOk = urlDraft.startsWith("http://") || urlDraft.startsWith("https://")
        FrostDialog(
            onDismissRequest = { editBookmark = null },
            title = { Text("Edit bookmark") },
            text = {
                Column {
                    OutlinedTextField(
                        value = titleDraft,
                        onValueChange = { titleDraft = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = urlDraft,
                        onValueChange = { urlDraft = it },
                        label = { Text("URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!urlOk) {
                        Text(
                            "URL must start with http:// or https://",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = urlOk && urlDraft.isNotBlank(),
                    onClick = {
                        store.updateBookmark(b.id, titleDraft, urlDraft.trim())
                        editBookmark = null
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editBookmark = null }) { Text("Cancel") }
            }
        )
    }

    if (showNewFolder) {
        var name by remember { mutableStateOf("") }
        FrostDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Folder name") }, singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // The new folder is created inside the
                        // folder being viewed (top level from
                        // All/Unfiled).
                        if (isFolderSegment(name)) {
                            val parent = folderFilter ?: ""
                            store.addBookmarkFolder(name, parent)
                            folderFilter = folderChild(parent, name)
                        }
                        showNewFolder = false
                    },
                    enabled = name.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolder = false }) { Text("Cancel") }
            }
        )
    }

    if (showManageFolders) {
        FrostDialog(
            onDismissRequest = { showManageFolders = false },
            title = { Text("Manage folders") },
            text = {
                Column {
                    Text("Deleting a folder keeps its bookmarks — they become Unfiled. Subfolders are deleted too.")
                    Spacer(Modifier.height(8.dp))
                    store.bookmarkFolders.toList().forEach { f ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            // Nested folders are indented by depth,
                            // showing only their last segment.
                            Text(
                                folderName(f),
                                modifier = Modifier.weight(1f)
                                    .padding(
                                        start = (f.count { it == '/' } * 12).dp,
                                        top = 10.dp, bottom = 10.dp
                                    )
                            )
                            TextButton(onClick = {
                                showManageFolders = false
                                renameFolder = f
                            }) { Text("Rename") }
                            TextButton(onClick = {
                                showManageFolders = false
                                confirmDeleteFolder = f
                            }) { Text("Delete") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showManageFolders = false }) { Text("Done") }
            }
        )
    }

    renameFolder?.let { old ->
        var name by remember(old) { mutableStateOf(folderName(old)) }
        FrostDialog(
            onDismissRequest = { renameFolder = null },
            title = { Text("Rename folder") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Folder name") }, singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.renameBookmarkFolder(old, name)
                        // The view follows the renamed folder
                        // (and any folder below it).
                        val newPath = folderChild(folderParent(old), name)
                        if (folderFilter == old) folderFilter = newPath
                        else if (folderFilter != null &&
                            folderFilter!!.startsWith("$old/")
                        ) {
                            folderFilter = newPath +
                                folderFilter!!.removePrefix(old)
                        }
                        renameFolder = null
                    },
                    enabled = name.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameFolder = null }) { Text("Cancel") }
            }
        )
    }

    confirmDeleteFolder?.let { f ->
        FrostDialog(
            onDismissRequest = { confirmDeleteFolder = null },
            title = { Text("Delete folder “$f”?") },
            text = { Text("Its bookmarks are kept and become Unfiled.") },
            confirmButton = {
                TextButton(onClick = {
                    store.deleteBookmarkFolder(f)
                    if (folderFilter == f ||
                        (folderFilter != null &&
                            folderFilter!!.startsWith("$f/"))
                    ) folderFilter = null
                    confirmDeleteFolder = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteFolder = null }) { Text("Cancel") }
            }
        )
    }

    if (showBulkMove) {
        FrostDialog(
            onDismissRequest = { showBulkMove = false },
            title = { Text("Move ${bookmarkSelected.size} bookmarks") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            bookmarkSelected.forEach { store.setBookmarkFolder(it, "") }
                            bookmarkSelected = emptySet()
                            bookmarkSelecting = false
                            showBulkMove = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Unfiled") }
                    store.bookmarkFolders.toList().forEach { f ->
                        TextButton(
                            onClick = {
                                bookmarkSelected.forEach { store.setBookmarkFolder(it, f) }
                                bookmarkSelected = emptySet()
                                bookmarkSelecting = false
                                showBulkMove = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(f) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBulkMove = false }) { Text("Cancel") }
            }
        )
    }

    moveTarget?.let { b ->
        FrostDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text("Move to folder") },
            text = {
                Column {
                    Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { store.setBookmarkFolder(b.id, ""); moveTarget = null },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (b.folder.isEmpty()) "Unfiled ✓" else "Unfiled") }
                    store.bookmarkFolders.toList().forEach { f ->
                        TextButton(
                            onClick = { store.setBookmarkFolder(b.id, f); moveTarget = null },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (b.folder == f) "$f ✓" else f) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { moveTarget = null }) { Text("Cancel") }
            }
        )
    }

    // Undo snackbar. Zero height while idle — the host only
    // lays out while a snackbar is showing.
    Box(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        SnackbarHost(hostState = snackHost)
    }
}

@Composable
fun HistoryScreen(store: PioneerStore, onBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    val rev = store.revision
    var historyQuery by remember { mutableStateOf("") }
    var historySort by remember { mutableStateOf("newest") }
    var historySelecting by remember { mutableStateOf(false) }
    var historySelected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showClearHistory by remember { mutableStateOf(false) }
    val historySnackHost = remember { SnackbarHostState() }
    val historySnackScope = rememberCoroutineScope()
    // Undo for history restores the deleted entries exactly
    // (Batch 3, mirroring Mariner's restoreHistory): the same
    // ids and timestamps, so the ordering does not change.
    fun undoDelete(deleted: List<net.nspioneer.browser.data.HistoryEntry>) {
        store.restoreHistory(deleted)
    }
    SubScreen("History", onBack) {
        FrostSearchBar(
            value = historyQuery,
            onValueChange = { historyQuery = it },
            placeholder = "Search history"
        )
        LibraryActionBar {
            TextButton(onClick = {
                historySort = when (historySort) {
                    "newest" -> "oldest"
                    "oldest" -> "title"
                    else -> "newest"
                }
            }) {
                Text(
                    when (historySort) {
                        "oldest" -> "Sort: oldest"
                        "title" -> "Sort: title"
                        else -> "Sort: newest"
                    }
                )
            }
            TextButton(onClick = { showClearHistory = true }) { Text("Clear history") }
            TextButton(onClick = {
                historySelecting = !historySelecting
                if (!historySelecting) historySelected = emptySet()
            }) { Text(if (historySelecting) "Done" else "Select") }
            if (historySelected.isNotEmpty()) {
                TextButton(onClick = {
                    val deleted = store.history.filter { it.id in historySelected }
                    historySelected.forEach { store.removeHistory(it) }
                    historySelected = emptySet()
                    historySelecting = false
                    historySnackScope.launch {
                        if (historySnackHost.showSnackbar(
                                if (deleted.size == 1) "Deleted 1 entry"
                                else "Deleted ${deleted.size} entries",
                                actionLabel = "Undo"
                            ) == SnackbarResult.ActionPerformed
                        ) {
                            undoDelete(deleted)
                        }
                    }
                }) { Text("Delete (${historySelected.size})") }
            }
        }
        val shownHistory = store.history.filter { h ->
            historyQuery.isBlank() ||
                h.title.contains(historyQuery, ignoreCase = true) ||
                h.url.contains(historyQuery, ignoreCase = true)
        }.let { list ->
            when (historySort) {
                "oldest" -> list.sortedBy { it.visitedAt }
                "title" -> list.sortedBy { it.title.lowercase() }
                else -> list.sortedByDescending { it.visitedAt }
            }
        }
        if (store.history.isEmpty()) {
            LibraryEmptyState(
                icon = Icons.Filled.History,
                text = "No history yet."
            )
        } else if (shownHistory.isEmpty()) {
            LibraryEmptyState(
                icon = Icons.Filled.History,
                text = "No history matches your search."
            )
        }
        if (shownHistory.isNotEmpty()) {
            LazyColumn {
                shownHistory.groupBy { dayLabel(it.visitedAt) }
                    .forEach { (day, entries) ->
                        item(key = "day-$day") {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp)
                            ) {
                                GroupTitle(
                                    if (entries.size == 1) "$day (1 visit)"
                                    else "$day (${entries.size} visits)"
                                )
                                SettingsCard {
                                    entries.forEachIndexed { i, h ->
                                        if (i > 0) InsetDivider()
                                        KitGroupedRow(
                                            onClick = {
                                                if (historySelecting) {
                                                    historySelected =
                                                        if (h.id in historySelected) historySelected - h.id
                                                        else historySelected + h.id
                                                } else onOpenUrl(h.url)
                                            },
                                            leading = {
                                                if (historySelecting) {
                                                    Checkbox(
                                                        checked = h.id in historySelected,
                                                        onCheckedChange = {
                                                            historySelected =
                                                                if (h.id in historySelected) historySelected - h.id
                                                                else historySelected + h.id
                                                        }
                                                    )
                                                } else {
                                                    RowFavicon(store, h.url)
                                                }
                                            },
                                            trailing = {
                                                IconButton(onClick = {
                                                    val deleted = listOf(h)
                                                    store.removeHistory(h.id)
                                                    historySnackScope.launch {
                                                        if (historySnackHost.showSnackbar(
                                                                "Deleted 1 entry",
                                                                actionLabel = "Undo"
                                                            ) == SnackbarResult.ActionPerformed
                                                        ) {
                                                            undoDelete(deleted)
                                                        }
                                                    }
                                                }) {
                                                    Icon(Icons.Filled.Delete, contentDescription = "Delete entry")
                                                }
                                            },
                                            title = {
                                                Text(
                                                    h.title, maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodyLarge
                                                )
                                            },
                                            supporting = {
                                                Text(
                                                    h.url, maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            },
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
            }
        }
    }

    // Time-range clear.
    if (showClearHistory) {
        FrostDialog(
            onDismissRequest = { showClearHistory = false },
            title = { Text("Clear history") },
            text = { Text("Remove history from the last hour, day, week, or all of it?") },
            confirmButton = {
                Column {
                    listOf(
                        "Last hour" to 3600_000L,
                        "Last day" to 24L * 3600_000L,
                        "Last week" to 7L * 24 * 3600_000L
                    ).forEach { (label, range) ->
                        TextButton(
                            onClick = {
                                store.clearHistoryOlderThan(
                                    System.currentTimeMillis() - range
                                )
                                showClearHistory = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(label) }
                    }
                    TextButton(
                        onClick = {
                            store.clearHistory()
                            showClearHistory = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("All history") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistory = false }) { Text("Cancel") }
            }
        )
    }

    // Undo snackbar. Zero height while idle.
    Box(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        SnackbarHost(hostState = historySnackHost)
    }
}

@Composable
/** Favorites sort (alpha.29): the same three orders as
 *  Bookmarks — newest, oldest, title. Pure, so it is
 *  unit-testable; the page applies it to the filtered list. */
internal fun sortFavourites(
    list: List<Bookmark>, sort: String
): List<Bookmark> = when (sort) {
    "oldest" -> list.sortedBy { it.createdAt }
    "title" -> list.sortedBy { it.title.lowercase() }
    else -> list.sortedByDescending { it.createdAt }
}

@Composable
fun FavoritesScreen(store: PioneerStore, onBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    val rev = store.revision
    var query by remember { mutableStateOf("") }
    // Alpha.29: Sort + Select, the same pattern as Bookmarks
    // and History — Sort cycles newest → oldest → title,
    // Select toggles multi-select with a bulk Remove.
    var favSort by remember { mutableStateOf("newest") }
    var favSelecting by remember { mutableStateOf(false) }
    var favSelected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val favSnackHost = remember { SnackbarHostState() }
    val favSnackScope = rememberCoroutineScope()
    SubScreen(title = "Favorites", onBack = onBack) {
        Column(modifier = Modifier.fillMaxSize()) {
            FrostSearchBar(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search favorites"
            )
            LibraryActionBar {
                TextButton(onClick = {
                    favSort = when (favSort) {
                        "newest" -> "oldest"
                        "oldest" -> "title"
                        else -> "newest"
                    }
                }) {
                    Text(
                        when (favSort) {
                            "oldest" -> "Sort: oldest"
                            "title" -> "Sort: title"
                            else -> "Sort: newest"
                        }
                    )
                }
                TextButton(onClick = {
                    favSelecting = !favSelecting
                    if (!favSelecting) favSelected = emptySet()
                }) { Text(if (favSelecting) "Done" else "Select") }
                if (favSelected.isNotEmpty()) {
                    TextButton(onClick = {
                        val deleted = store.removeFavourites(favSelected)
                        favSelected = emptySet()
                        favSelecting = false
                        favSnackScope.launch {
                            if (favSnackHost.showSnackbar(
                                    if (deleted.size == 1) "Removed 1 favorite"
                                    else "Removed ${deleted.size} favorites",
                                    actionLabel = "Undo"
                                ) == SnackbarResult.ActionPerformed
                            ) {
                                store.restoreFavourites(deleted)
                            }
                        }
                    }) { Text("Remove (${favSelected.size})") }
                }
            }
            val shown = sortFavourites(
                store.favourites.filter { f ->
                    query.isBlank() ||
                        f.title.contains(query, ignoreCase = true) ||
                        f.url.contains(query, ignoreCase = true)
                },
                favSort
            )
            if (shown.isEmpty()) {
                LibraryEmptyState(
                    icon = Icons.Filled.Favorite,
                    text = "No favorites yet. Star a page from the menu to add one."
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { f ->
                        KitGroupedRow(
                            onClick = {
                                if (favSelecting) {
                                    favSelected =
                                        if (f.id in favSelected) favSelected - f.id
                                        else favSelected + f.id
                                } else onOpenUrl(f.url)
                            },
                            leading = {
                                if (favSelecting) {
                                    Checkbox(
                                        checked = f.id in favSelected,
                                        onCheckedChange = {
                                            favSelected =
                                                if (f.id in favSelected) favSelected - f.id
                                                else favSelected + f.id
                                        }
                                    )
                                } else {
                                    val fav = rememberSiteFavicon(store, f.url)
                                    if (fav != null) {
                                        Image(
                                            bitmap = fav.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.Star,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            trailing = {
                                IconButton(onClick = {
                                    store.toggleFavourite(f.title, f.url)
                                }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Remove favorite")
                                }
                            },
                            title = { Text(f.title.ifBlank { f.url }) },
                            supporting = { Text(f.url) }
                        )
                        InsetDivider()
                    }
                }
            }
        }
    }

    // Undo snackbar. Zero height while idle — the host only
    // lays out while a snackbar is showing.
    Box(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        SnackbarHost(hostState = favSnackHost)
    }
}

/* ------------------------------------------------------------------ */
/* Downloads + Offline library (§8.4)                                  */
/* ------------------------------------------------------------------ */

/** Audit F12: only drop the record when both removals
 *  actually worked. Returns true when the record was
 *  removed. */
private fun deleteDownloadRecord(
    context: Context, store: PioneerStore, rec: DownloadRecord
): Boolean {
    val dmOk = if (rec.dmId >= 0) {
        runCatching {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(rec.dmId)
        }.isSuccess
    } else true
    val wiped = Downloads.deleteLocal(context, store, rec)
    return if (dmOk && wiped) {
        Downloads.clearJobBundle(store, rec.id)
        store.removeDownload(rec.id)
        Downloads.deleteEngineStaging(context, store, rec)
        true
    } else {
        false
    }
}

private fun deleteDownloadFailedToast(context: Context) {
    Toast.makeText(
        context, "Couldn't delete that download.",
        Toast.LENGTH_LONG
    ).show()
}

/** The one download row, shared by DownloadsScreen and
 *  LibraryScreen (Mariner duplicates this row in both
 *  screens; behaviour, strings and order are identical
 *  here). [showPlay] is the Offline library's play-offline
 *  button, [showOpen] the Downloads screen's open-in-app
 *  button, [showExport] the library's save-a-copy button.
 *  [showStatusDetail] hides the error / downgrade lines on
 *  the private list, matching Mariner's private rows. */
@Composable
private fun DownloadRow(
    rec: DownloadRecord,
    store: PioneerStore,
    selecting: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    showPlay: Boolean,
    showOpen: Boolean,
    showExport: Boolean,
    showStatusDetail: Boolean = true,
    onExport: ((DownloadRecord) -> Unit)? = null,
    askDownloadNotif: () -> Unit,
) {
    val context = LocalContext.current
    val source = Downloads.localSource(context, store, rec)
    val active = rec.status == "queued" || rec.status == "downloading" ||
        rec.status == "paused"
    KitGroupedRow(
        leading = {
            if (selecting) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() }
                )
            } else {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        title = {
            Text(
                rec.title, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        supporting = {
            Column {
                // Audit P-m13: a download still in progress
                // after a failed attempt is a retry in
                // flight, not a fresh download.
                val dlErrFirst = store.setting("dlerror:${rec.id}", "")
                val statusText = when {
                    Downloads.isWaitingForWifi(context, store) &&
                        (rec.status == "downloading" || rec.status == "queued") ->
                        "Waiting for Wi-Fi"
                    rec.status == "downloading" && dlErrFirst.isNotBlank() ->
                        "Retrying…"
                    else -> rec.status
                }
                Text(
                    "${statusText}${if (active && rec.progress >= 0) " • ${rec.progress}%" else ""} • ${rec.fileName}",
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Beta.64 #30: size / speed / ETA.
                Downloads.progressDetailLine(rec)?.let { detail ->
                    Text(
                        detail,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (showStatusDetail) {
                    val dlErr = store.setting("dlerror:${rec.id}", "")
                    if (dlErr.isNotBlank() &&
                        (rec.status == "failed" || active)
                    ) {
                        Text(
                            dlErr,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    // Audit D-p4: a downgraded job kept
                    // going under the system downloader
                    // — say so instead of implying the
                    // segmented gate still applies.
                    if (store.setting("dldowngrade:${rec.id}", "") == "1") {
                        Text(
                            "Continued via system downloader",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (active) {
                    LinearProgressIndicator(
                        progress = { if (rec.progress >= 0) rec.progress / 100f else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
        },
        trailing = {
            Row {
                if (rec.status == "downloading" &&
                    Downloads.isResumableEngine(Downloads.engineFor(store, rec))
                ) {
                    IconButton(onClick = { Downloads.pause(context, store, rec) }) {
                        Icon(Icons.Filled.Pause, contentDescription = "Pause download")
                    }
                }
                if (rec.status == "paused" &&
                    Downloads.isResumableEngine(Downloads.engineFor(store, rec))
                ) {
                    IconButton(onClick = { Downloads.resume(context, store, rec) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Resume download")
                    }
                }
                if (active) {
                    IconButton(onClick = { Downloads.cancel(context, store, rec) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel download")
                    }
                }
                if (showPlay && rec.status == "complete" && source != null) {
                    IconButton(onClick = {
                        PlayerActivity.launch(context, source, rec.title)
                    }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play offline")
                    }
                }
                if (showOpen && rec.status == "complete" && source != null) {
                    IconButton(onClick = {
                        val intent = Downloads.openIntent(context, store, rec)
                        val opened = intent != null && runCatching {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }.isSuccess
                        if (!opened) {
                            Toast.makeText(
                                context, "No app can open this file",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = "Open download")
                    }
                }
                if (rec.status == "complete" && source != null) {
                    IconButton(onClick = {
                        Downloads.shareIntent(context, store, rec)
                            ?.let { intent ->
                                context.startActivity(
                                    Intent.createChooser(
                                        intent, "Share download"
                                    ).addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK
                                    )
                                )
                            }
                    }) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = "Share download"
                        )
                    }
                    if (showExport) {
                        IconButton(onClick = { onExport?.invoke(rec) }) {
                            Icon(
                                Icons.Filled.SaveAlt,
                                contentDescription = "Save a copy"
                            )
                        }
                    }
                }
                if (rec.status == "failed") {
                    IconButton(onClick = {
                        // Audit D-M1: retry re-enqueues
                        // through the saved job bundle —
                        // merges re-run with both side
                        // URLs, location and rename are
                        // preserved. Only a missing
                        // bundle falls back to
                        // delete-and-restart.
                        askDownloadNotif()
                        if (Downloads.retry(context, store, rec)) {
                            Toast.makeText(
                                context, "Download restarted",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            deleteDownloadFailedToast(context)
                        }
                    }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Retry download"
                        )
                    }
                }
                IconButton(onClick = {
                    if (!deleteDownloadRecord(context, store, rec)) {
                        deleteDownloadFailedToast(context)
                    }
                }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete download")
                }
            }
        },
    )
}

/** The Offline library: media downloads, plus saved pages
 *  (alpha.10). Ordinary file and image downloads live in
 *  the phone's Downloads folder and on the Downloads page.
 *  (Mariner LibraryScreens.kt:1261.) A saved page is an
 *  HTML snapshot — tapping its row re-opens the original
 *  URL, as Mariner's .mht rows do. */
@Composable
fun LibraryScreen(
    store: PioneerStore,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit = {},
) {
    val rev = store.revision
    // Audit P-M5: progress ticks bump this, not the global revision.
    val progressRev = store.downloadProgressRev
    val context = LocalContext.current
    // One-time download notification ask (2026-10-26); no-ops once asked or granted.
    val askDownloadNotif = rememberAskDownloadNotifPermission(store)
    var downloadQuery by remember { mutableStateOf("") }
    // Audit P-m7: multi-select delete on this screen.
    var dlSelecting by remember { mutableStateOf(false) }
    var dlSelected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val dlScreenMedia = true
    // Beta.64 #21: sort the offline library.
    var mediaSort by remember { mutableStateOf("newest") }
    var exportTarget by remember {
        mutableStateOf<DownloadRecord?>(null)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val rec = exportTarget
        exportTarget = null
        if (uri != null && rec != null) {
            Thread { Downloads.copyExport(context, store, rec, uri) }.start()
        }
    }
    LaunchedEffect(Unit) {
        Downloads.refresh(context, store)
    }
    SubScreen("Offline library", onBack) {
        var refreshing by remember { mutableStateOf(false) }
        val refreshScope = rememberCoroutineScope()
        FrostSearchBar(
            value = downloadQuery,
            onValueChange = { downloadQuery = it },
            placeholder = "Search downloads"
        )
        LibraryActionBar {
            TextButton(onClick = {
                mediaSort = when (mediaSort) {
                    "newest" -> "oldest"
                    "oldest" -> "title"
                    else -> "newest"
                }
            }) {
                Text(
                    when (mediaSort) {
                        "oldest" -> "Sort: oldest"
                        "title" -> "Sort: title"
                        else -> "Sort: newest"
                    }
                )
            }
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
            }
            TextButton(
                enabled = !refreshing,
                onClick = {
                    refreshScope.launch {
                        refreshing = true
                        withContext(Dispatchers.IO) {
                            Downloads.refresh(context, store)
                        }
                        refreshing = false
                    }
                }
            ) { Text(if (refreshing) "Refreshing…" else "Refresh status") }
            // Audit P-p4: records only — files stay.
            if (store.downloadsSnapshot().any { it.media && it.status == "complete" }) {
                TextButton(onClick = { Downloads.clearCompletedRecords(store, true) }) {
                    Text("Clear completed")
                }
            }
            TextButton(onClick = {
                dlSelecting = !dlSelecting
                if (!dlSelecting) dlSelected = emptySet()
            }) { Text(if (dlSelecting) "Done" else "Select") }
            if (dlSelected.isNotEmpty()) {
                // Records only, files stay — same rule
                // as "Clear completed" (audit P-p4).
                TextButton(onClick = {
                    store.downloadsSnapshot()
                        .filter { it.id in dlSelected && it.media == dlScreenMedia }
                        .forEach { rec ->
                            Downloads.deleteEngineStaging(context, store, rec)
                            Downloads.clearJobBundle(store, rec.id)
                            store.removeDownload(rec.id)
                        }
                    dlSelected = emptySet()
                    dlSelecting = false
                }) { Text("Delete records (${dlSelected.size})") }
            }
        }
        // The offline library is for media downloads only; ordinary file
        // and image downloads live in the phone's Downloads folder
        // and on the Downloads page.
        val mediaDownloads = store.downloadsSnapshot().filter { rec ->
            rec.media && !rec.isPrivate &&
                (downloadQuery.isBlank() ||
                    rec.title.contains(downloadQuery, ignoreCase = true) ||
                    rec.fileName.contains(downloadQuery, ignoreCase = true))
        }.let { list ->
            // Beta.64 #21: sort the offline library.
            when (mediaSort) {
                "oldest" -> list.sortedBy { it.addedAt }
                "title" -> list.sortedBy { it.title.lowercase() }
                else -> list.sortedByDescending { it.addedAt }
            }
        }
        if (mediaDownloads.isEmpty()) {
            LibraryEmptyState(
                icon = Icons.Filled.Download,
                text = if (downloadQuery.isNotBlank()) "No downloads match your search." else "Nothing downloaded yet. Use “Media on this page” in the browser and tap download."
            )
        }
        if (mediaDownloads.isNotEmpty()) {
            LazyColumn {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        GroupTitle("Downloads")
                        SettingsCard {
                            mediaDownloads.forEachIndexed { i, rec ->
                                if (i > 0) InsetDivider()
                                DownloadRow(
                                    rec = rec,
                                    store = store,
                                    selecting = dlSelecting,
                                    selected = rec.id in dlSelected,
                                    onToggleSelect = {
                                        dlSelected =
                                            if (rec.id in dlSelected) dlSelected - rec.id
                                            else dlSelected + rec.id
                                    },
                                    showPlay = true,
                                    showOpen = false,
                                    showExport = true,
                                    onExport = { target ->
                                        exportTarget = target
                                        exportLauncher.launch(target.fileName)
                                    },
                                    askDownloadNotif = askDownloadNotif,
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
        // alpha.10: saved pages (HTML snapshots from "Save
        // page offline"). Tapping a row re-opens the original
        // URL; the snapshot itself cannot render in a tab.
        // Deleting removes the record and the snapshot file.
        val savedPages = store.offlinePages().filter { page ->
            downloadQuery.isBlank() ||
                page.title.contains(downloadQuery, ignoreCase = true) ||
                page.url.contains(downloadQuery, ignoreCase = true)
        }
        if (savedPages.isNotEmpty()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                GroupTitle("Saved pages")
                SettingsCard {
                    savedPages.forEachIndexed { i, page ->
                        if (i > 0) InsetDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onOpenUrl(page.url) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    page.title.ifBlank { page.url },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    page.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = {
                                runCatching {
                                    java.io.File(page.path).delete()
                                }
                                store.removeOfflinePage(page.id)
                            }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete saved page"
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Ordinary file and image downloads. Media downloads
 *  live in the Offline library. (Mariner
 *  LibraryScreens.kt:1677.) The games zip-import button
 *  is dropped — no games in Phase 3. */
@Composable
fun DownloadsScreen(
    store: PioneerStore,
    onBack: () -> Unit,
) {
    val rev = store.revision
    // Audit P-M5: progress ticks bump this, not the global revision.
    val progressRev = store.downloadProgressRev
    val context = LocalContext.current
    // One-time download notification ask (2026-10-26); no-ops once asked or granted.
    val askDownloadNotif = rememberAskDownloadNotifPermission(store)
    var downloadQuery by remember { mutableStateOf("") }
    // Beta.64 #20: the private-downloads confirm dialog.
    var showPrivateConfirm by remember { mutableStateOf(false) }
    // Audit P-m7: multi-select delete on this screen.
    var dlSelecting by remember { mutableStateOf(false) }
    var dlSelected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val dlScreenMedia = false
    // Beta.64 #21: sort file downloads (and the private
    // list below, same order).
    var fileSort by remember { mutableStateOf("newest") }
    LaunchedEffect(Unit) {
        Downloads.refresh(context, store)
    }
    SubScreen("Downloads", onBack) {
        var refreshing by remember { mutableStateOf(false) }
        val refreshScope = rememberCoroutineScope()
        FrostSearchBar(
            value = downloadQuery,
            onValueChange = { downloadQuery = it },
            placeholder = "Search downloads"
        )
        // Download notifications are off (2026-10-26): worker
        // downloads announce completion as an ordinary
        // notification, which needs the permission — the
        // bar's second row is the recovery path for a denial.
        // notifTick re-checks when returning from Settings.
        var notifTick by remember { mutableStateOf(0) }
        val notifLifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(notifLifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    notifTick++
                }
            }
            notifLifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                notifLifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
        val notifOff = remember(notifTick) {
            android.os.Build.VERSION.SDK_INT >= 33 &&
                !downloadNotificationsEnabled(context)
        }
        LibraryActionBar(
            notification = if (notifOff) {
                {
                    Text(
                        "Download notifications are off",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = { openAppNotificationSettings(context) }) {
                        Text("Enable")
                    }
                }
            } else null
        ) {
            TextButton(onClick = {
                fileSort = when (fileSort) {
                    "newest" -> "oldest"
                    "oldest" -> "title"
                    else -> "newest"
                }
            }) {
                Text(
                    when (fileSort) {
                        "oldest" -> "Sort: oldest"
                        "title" -> "Sort: title"
                        else -> "Sort: newest"
                    }
                )
            }
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
            }
            TextButton(
                enabled = !refreshing,
                onClick = {
                    refreshScope.launch {
                        refreshing = true
                        withContext(Dispatchers.IO) {
                            Downloads.refresh(context, store)
                        }
                        refreshing = false
                    }
                }
            ) { Text(if (refreshing) "Refreshing…" else "Refresh status") }
            // Audit P-p4: records only — files stay.
            if (store.downloadsSnapshot().any { !it.media && it.status == "complete" }) {
                TextButton(onClick = { Downloads.clearCompletedRecords(store, false) }) {
                    Text("Clear completed")
                }
            }
            // Audit D-m12: remove files pruned records left behind.
            if (Downloads.prunedFiles(store).isNotEmpty()) {
                TextButton(onClick = { Downloads.clearPrunedFiles(context, store) }) {
                    Text("Clear leftover files")
                }
            }
            TextButton(onClick = {
                dlSelecting = !dlSelecting
                if (!dlSelecting) dlSelected = emptySet()
            }) { Text(if (dlSelecting) "Done" else "Select") }
            if (dlSelected.isNotEmpty()) {
                // Records only, files stay — same rule
                // as "Clear completed" (audit P-p4).
                TextButton(onClick = {
                    store.downloadsSnapshot()
                        .filter { it.id in dlSelected && it.media == dlScreenMedia }
                        .forEach { rec ->
                            Downloads.deleteEngineStaging(context, store, rec)
                            Downloads.clearJobBundle(store, rec.id)
                            store.removeDownload(rec.id)
                        }
                    dlSelected = emptySet()
                    dlSelecting = false
                }) { Text("Delete records (${dlSelected.size})") }
            }
        }
        // The Downloads page is for ordinary file and
        // image downloads only; media downloads live
        // in the Offline library.
        val fileDownloads = store.downloadsSnapshot().filter { rec ->
            !rec.media && !rec.isPrivate &&
                (downloadQuery.isBlank() ||
                    rec.title.contains(downloadQuery, ignoreCase = true) ||
                    rec.fileName.contains(downloadQuery, ignoreCase = true))
        }.let { list ->
            // Beta.64 #21: sort file downloads.
            when (fileSort) {
                "oldest" -> list.sortedBy { it.addedAt }
                "title" -> list.sortedBy { it.title.lowercase() }
                else -> list.sortedByDescending { it.addedAt }
            }
        }
        // Beta.64 #20: private file downloads, shown only
        // behind the confirm gate below.
        val privateDownloads = store.downloadsSnapshot().filter { rec ->
            !rec.media && rec.isPrivate &&
                (downloadQuery.isBlank() ||
                    rec.title.contains(downloadQuery, ignoreCase = true) ||
                    rec.fileName.contains(downloadQuery, ignoreCase = true))
        }.let { list ->
            // Beta.64 #21: same order as the file list.
            when (fileSort) {
                "oldest" -> list.sortedBy { it.addedAt }
                "title" -> list.sortedBy { it.title.lowercase() }
                else -> list.sortedByDescending { it.addedAt }
            }
        }
        if (fileDownloads.isEmpty() && privateDownloads.isEmpty()) {
            LibraryEmptyState(
                icon = Icons.Filled.Download,
                text = if (downloadQuery.isNotBlank()) "No downloads match your search." else "Nothing downloaded yet. Files and images you download from the browser will appear here."
            )
        }
        if (fileDownloads.isNotEmpty() || privateDownloads.isNotEmpty()) {
            LazyColumn {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        GroupTitle("Downloads")
                        SettingsCard {
                            fileDownloads.forEachIndexed { i, rec ->
                                if (i > 0) InsetDivider()
                                DownloadRow(
                                    rec = rec,
                                    store = store,
                                    selecting = dlSelecting,
                                    selected = rec.id in dlSelected,
                                    onToggleSelect = {
                                        dlSelected =
                                            if (rec.id in dlSelected) dlSelected - rec.id
                                            else dlSelected + rec.id
                                    },
                                    showPlay = false,
                                    showOpen = true,
                                    showExport = false,
                                    askDownloadNotif = askDownloadNotif,
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
                if (privateDownloads.isNotEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            GroupTitle("Private downloads")
                            if (!store.privateDownloadsGate.unlocked) {
                                // Beta.64 #20: private content
                                // stays hidden until the user
                                // explicitly confirms, this
                                // session only.
                                PanelCard {
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "Private downloads are hidden",
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = { showPrivateConfirm = true }) {
                                            Text("Show")
                                        }
                                    }
                                }
                            } else {
                                SettingsCard {
                                    privateDownloads.forEachIndexed { i, rec ->
                                        if (i > 0) InsetDivider()
                                        DownloadRow(
                                            rec = rec,
                                            store = store,
                                            selecting = false,
                                            selected = false,
                                            onToggleSelect = {},
                                            showPlay = false,
                                            showOpen = true,
                                            showExport = false,
                                            showStatusDetail = false,
                                            askDownloadNotif = askDownloadNotif,
                                        )
                                    }
                                }
                                TextButton(onClick = { store.privateDownloadsGate.hide() }) {
                                    Text("Hide private downloads")
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
        if (showPrivateConfirm) {
            FrostDialog(
                onDismissRequest = { showPrivateConfirm = false },
                title = { Text("Show private downloads?") },
                text = {
                    Text("Private downloads are hidden by default. Showing them lists files downloaded in private tabs, until you hide them again.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        store.privateDownloadsGate.confirm()
                        showPrivateConfirm = false
                    }) { Text("Show") }
                },
                dismissButton = {
                    TextButton(onClick = { showPrivateConfirm = false }) { Text("Cancel") }
                }
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
