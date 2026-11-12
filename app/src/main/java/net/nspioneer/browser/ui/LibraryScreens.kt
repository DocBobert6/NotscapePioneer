package net.nspioneer.browser.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.nspioneer.browser.data.Bookmark
import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.data.folderChild
import net.nspioneer.browser.data.folderChildren
import net.nspioneer.browser.data.folderName
import net.nspioneer.browser.data.folderParent
import net.nspioneer.browser.data.isFolderSegment

/**
 * Library screens carried from Mariner's LibraryScreens.kt:
 * Bookmarks, History and Favorites only. Playlists, Downloads
 * and Reading List are dropped — their phases have not arrived.
 *
 * Adapted points:
 * - PioneerStore (no Room/AppStore), folder helpers from the
 *   data package, export via store.exportBookmarksHtml().
 * - No material-icons-extended dependency in Pioneer: the
 *   favicon fallback is a letter tile, not the Public icon.
 * - History undo: the Phase 2 store API has no restoreHistory,
 *   so an undone delete re-adds the entries via addHistory
 *   (new id, fresh timestamp) instead of restoring them
 *   exactly. Bookmark undo still restores exactly via
 *   restoreBookmarks, which the API does provide.
 * - GroupTitle / SettingsCard / InsetDivider are carried here
 *   from Mariner's SettingsScreen.kt (they are shared kit
 *   pieces, not settings logic).
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

/** A letter tile for a bookmark/history/favourite row when no
 * favicon is cached: the title's initial in the kit's tinted
 * circle. Favicons are letter tiles in Phase 2 (no fetching). */
@Composable
private fun LetterTile(letter: String) {
    Box(
        modifier = Modifier.size(20.dp).background(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            CircleShape
        ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            letter.uppercase().take(1),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun RowFavicon(store: PioneerStore, url: String, title: String) {
    val host = runCatching { Uri.parse(url).host }.getOrNull()
    val fav = host?.let { remember(it) { store.loadFavicon(it) } }
    if (fav != null) {
        Image(
            bitmap = fav.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
    } else {
        LetterTile(title.trim().firstOrNull()?.toString() ?: "?")
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
        OutlinedTextField(
            value = bookmarkQuery,
            onValueChange = { bookmarkQuery = it },
            label = { Text("Search bookmarks") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
            Spacer(Modifier.weight(1f))
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
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("No bookmarks yet. Star a page to add one.", modifier = Modifier.padding(16.dp))
            }
        } else if (shown.isEmpty()) {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    if (bookmarkQuery.isNotBlank()) "No bookmarks match your search."
                    else "Nothing in this folder yet.",
                    modifier = Modifier.padding(16.dp)
                )
            }
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
                                            RowFavicon(store, b.url, b.title)
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
    // Undo for history re-adds the entries via addHistory: the
    // Phase 2 store API has no restoreHistory, so an undone
    // delete comes back with a new id and a fresh timestamp
    // instead of its original visit time.
    fun undoDelete(deleted: List<net.nspioneer.browser.data.HistoryEntry>) {
        deleted.forEach { store.addHistory(it.title, it.url) }
    }
    SubScreen("History", onBack) {
        OutlinedTextField(
            value = historyQuery,
            onValueChange = { historyQuery = it },
            label = { Text("Search history") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
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
            Spacer(Modifier.weight(1f))
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
            TextButton(onClick = { showClearHistory = true }) { Text("Clear history") }
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
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("No history yet.", modifier = Modifier.padding(16.dp))
            }
        } else if (shownHistory.isEmpty()) {
            PanelCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("No history matches your search.", modifier = Modifier.padding(16.dp))
            }
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
                                                    RowFavicon(store, h.url, h.title)
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
fun FavoritesScreen(store: PioneerStore, onBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    val rev = store.revision
    var query by remember { mutableStateOf("") }
    SubScreen(title = "Favorites", onBack = onBack) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search favorites") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
            val shown = store.favourites.filter { f ->
                query.isBlank() ||
                    f.title.contains(query, ignoreCase = true) ||
                    f.url.contains(query, ignoreCase = true)
            }
            if (shown.isEmpty()) {
                Text(
                    "No favorites yet. Star a page from the menu to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { f ->
                        KitGroupedRow(
                            onClick = { onOpenUrl(f.url) },
                            leading = {
                                val host = runCatching {
                                    Uri.parse(f.url).host
                                }.getOrNull()
                                val fav = host?.let {
                                    remember(it) { store.loadFavicon(it) }
                                }
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
}
