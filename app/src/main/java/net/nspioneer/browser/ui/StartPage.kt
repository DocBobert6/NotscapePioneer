package net.nspioneer.browser.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.nspioneer.browser.data.PioneerStore

/**
 * The browser start page, carried from Mariner's StartPage.kt and
 * cut down to what Pioneer Phase 2 has: the banner, favourites,
 * most-visited tiles, and bookmarks quick access.
 *
 * Dropped (their phases/features have not arrived): book /
 * updates / console / game callbacks (defaulted to no-ops in
 * Mariner — here they are gone outright), Continue watching,
 * Continue reading, Shortcuts pins, custom-photo wallpaper.
 * Wallpaper follows the theme (light/dark colours only — no
 * custom photo in Phase 2). Favicons are letter tiles in
 * Phase 2: the store returns null unless a cached file exists,
 * and no favicon is ever fetched for a tile.
 */

/**
 * A site tile icon: the persisted favicon for the host when one
 * was saved, else the title's initial letter in the kit's tinted
 * circle. Favicons are local-only.
 */
@Composable
private fun TileIcon(store: PioneerStore, url: String, title: String) {
    val host = remember(url) {
        runCatching { Uri.parse(url).host }.getOrNull() ?: ""
    }
    val fav: Bitmap? = remember(host) {
        if (host.isBlank()) null else store.loadFavicon(host)
    }
    Box(
        modifier = Modifier.size(52.dp).clip(CircleShape)
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (fav != null) {
            Image(
                bitmap = fav.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            Text(
                title.trim().firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StartPage(
    store: PioneerStore,
    onOpen: (String) -> Unit,
    contentTopPad: Dp = 0.dp,
    contentBottomPad: Dp = 0.dp
) {
    val showBanner = store.setting("showBanner", "true") == "true"
    val rev = store.revision
    // The wallpaper dresses the PAGE background only: since
    // Mariner 0.0.972 the wallpaper FOLLOWS THE THEME, and
    // Pioneer Phase 2 keeps only that — light/dark colours,
    // no custom photo. Any stored "custom" value falls back
    // to the themed colour.
    val themeMode = store.setting("themeMode", "system")
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val appDark = when (themeMode) {
        "dark", "materialyou-dark" -> true
        "light", "materialyou-light" -> false
        else -> systemDark
    }
    val wallpaperColor = if (appDark) Color(0xFF0A0A0A) else Color(0xFFFFFFFF)
    var favMenuFor by remember { mutableStateOf<Long?>(null) }
    var frequentMenuFor by remember { mutableStateOf<String?>(null) }

    // Hosts removed from Frequently visited by long-press:
    // hidden from this list only; their history entries are
    // untouched.
    val frequentHidden = remember(rev) {
        store.setting("frequentHidden", "")
            .lines().filter { it.isNotBlank() }.toSet()
    }
    val frequent = remember(rev) {
        // TRUE frequency: visits per host counted from history,
        // most-visited first, ties broken by recency; each host
        // is represented by its most recent visit.
        store.history.groupBy { Uri.parse(it.url).host ?: it.url }
            .values.map { visits ->
                visits.maxByOrNull { it.visitedAt }!! to visits.size
            }
            .sortedWith(
                compareByDescending<Pair<net.nspioneer.browser.data.HistoryEntry, Int>> { it.second }
                    .thenByDescending { it.first.visitedAt }
            )
            .filter {
                (Uri.parse(it.first.url).host ?: it.first.url) !in frequentHidden
            }
            .take(6)
            .map { it.first }
    }
    val showTiles = store.setting("startPageTiles", "true") == "true"
    val showFavourites = store.setting("showFavorites", "true") == "true"

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxSize().background(wallpaperColor))
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 760.dp),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = 16.dp + contentTopPad, bottom = 16.dp + contentBottomPad
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (showBanner) {
                item {
                    PanelCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "NOTSCAPE",
                                    fontFamily = WordmarkFontFamily,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    "PIONEER",
                                    fontFamily = SmallCapsFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 6.sp
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Local-only and private — everything stays on your device. " +
                                        "Type in the address bar to search or visit a site.",
                                    fontFamily = SmallCapsFontFamily,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            // No favourites, no section: the header and the
            // empty-state hint never sit on the start page
            // permanently; the section exists only when there
            // is something in it.
            if (showFavourites && store.favourites.isNotEmpty()) {
                item {
                    Text("Favorites", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    run {
                        val favRows = ((store.favourites.size + 3) / 4).coerceAtLeast(1)
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.height((favRows * 96).dp)
                        ) {
                            items(store.favourites.toList()) { fav ->
                                Box {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.fillMaxWidth().combinedClickable(
                                            onClick = { onOpen(fav.url) },
                                            onLongClick = { favMenuFor = fav.id }
                                        )
                                    ) {
                                        TileIcon(store, fav.url, fav.title)
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            fav.title, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = favMenuFor == fav.id,
                                        onDismissRequest = { favMenuFor = null }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Remove from Favorites") },
                                            onClick = {
                                                favMenuFor = null
                                                store.toggleFavourite(fav.title, fav.url)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (showTiles && frequent.isNotEmpty()) {
                item {
                    Text("Frequently visited", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    run {
                        val rows = ((frequent.size + 3) / 4).coerceAtLeast(1)
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.height((rows * 96).dp)
                        ) {
                            items(frequent) { entry ->
                                val host = Uri.parse(entry.url).host ?: entry.url
                                Box {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.fillMaxWidth().combinedClickable(
                                            onClick = { onOpen(entry.url) },
                                            onLongClick = { frequentMenuFor = host }
                                        )
                                    ) {
                                        TileIcon(store, entry.url, entry.title.ifBlank { entry.url })
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            host, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = frequentMenuFor == host,
                                        onDismissRequest = { frequentMenuFor = null }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Remove from Frequently visited") },
                                            onClick = {
                                                store.putSetting(
                                                    "frequentHidden",
                                                    (frequentHidden + host)
                                                        .joinToString("\n")
                                                )
                                                frequentMenuFor = null
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (store.bookmarks.isNotEmpty()) {
                item {
                    Text("Bookmarks", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        store.bookmarks.sortedByDescending { it.createdAt }.take(8).forEach { b ->
                            Card(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { onOpen(b.url) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TileIcon(store, b.url, b.title)
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            b.title.ifBlank { b.url }, maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            b.url, maxLines = 1,
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
    }
}
