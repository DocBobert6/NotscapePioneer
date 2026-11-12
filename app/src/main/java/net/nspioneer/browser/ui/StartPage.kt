package net.nspioneer.browser.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.nspioneer.browser.data.PioneerStore

/**
 * The browser start page, carried from Mariner's StartPage.kt and
 * cut down to what Pioneer Phase 2 has: the banner, favourites,
 * most-visited tiles, and shortcuts pins.
 *
 * Dropped (their phases/features have not arrived): book /
 * updates / console / game callbacks (defaulted to no-ops in
 * Mariner — here they are gone outright), Continue watching,
 * Continue reading. Batch 4: the Bookmarks section is REMOVED
 * by Robert's explicit order — bookmarks themselves (the
 * feature, folders, screens, import/export) are untouched,
 * only this section on the start page goes. Shortcuts pins
 * (sites + the Offline library section only — consoles and
 * games are games scope) and the custom-photo wallpaper with
 * its banner swatch arrive in Batch 4. Wallpaper follows the
 * theme (light/dark colours only) unless a custom photo is
 * chosen. Favicons are fetched in Batch 3: tiles use
 * [rememberSiteFavicon], which fetches
 * `https://<host>/favicon.ico` once and caches it; the fallback
 * is Mariner's generic Public icon, never a letter tile.
 */

/**
 * A site tile icon: the fetched favicon for the host, else
 * Mariner's generic Public icon in the kit's tinted circle.
 * Batch 3: letter tiles are gone — see [rememberSiteFavicon].
 */
@Composable
private fun TileIcon(store: PioneerStore, url: String) {
    val fav = rememberSiteFavicon(store, url)
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
            Icon(
                Icons.Filled.Public,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun StartPage(
    store: PioneerStore,
    onOpen: (String) -> Unit,
    onOpenSection: (PioneerScreen) -> Unit = {},
    contentTopPad: Dp = 0.dp,
    contentBottomPad: Dp = 0.dp
) {
    val context = LocalContext.current
    val showBanner = store.setting("showBanner", "true") == "true"
    val rev = store.revision
    // The wallpaper dresses the PAGE background only: since
    // Mariner 0.0.972 the wallpaper FOLLOWS THE THEME, and
    // Pioneer keeps only that — light/dark colours,
    // no custom photo — unless a custom photo is chosen,
    // which always wins (Batch 4, Mariner 0.0.963).
    val wallpaper = store.setting("wallpaper", "auto")
    val themeMode = store.setting("themeMode", "system")
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val appDark = when (themeMode) {
        "dark", "materialyou-dark" -> true
        "light", "materialyou-light" -> false
        else -> systemDark
    }
    val wallpaperColor = if (appDark) Color(0xFF0A0A0A) else Color(0xFFFFFFFF)
    // Custom photo (Batch 4): copied into app files by
    // Settings and drawn over the themed colour, which stays
    // as the fallback while no photo exists. Keyed on the
    // file's mtime so replacing the photo redraws in place.
    // Decoded off the UI thread, downsampled to ≤1600px
    // (Mariner's C4 rule, verbatim shape).
    val customWallpaperFile = remember {
        java.io.File(context.filesDir, "wallpaper/custom.jpg")
    }
    val wallpaperBitmap = produceState<android.graphics.Bitmap?>(
        initialValue = null,
        wallpaper, customWallpaperFile.lastModified()
    ) {
        value = if (wallpaper == "custom") {
            withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options()
                    .apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(
                    customWallpaperFile.absolutePath, bounds
                )
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options()
                    .apply { inSampleSize = sample }
                runCatching {
                    BitmapFactory.decodeFile(
                        customWallpaperFile.absolutePath, opts
                    )
                }.getOrNull()
            }
        } else {
            null
        }
    }.value
    // Banner palette (Batch 4, Mariner 0.0.965): when a custom
    // photo is the wallpaper, the banner card wears the photo's
    // own dominant colours instead of the kit's neutral card.
    var bannerSwatch by remember {
        mutableStateOf<Palette.Swatch?>(null)
    }
    LaunchedEffect(wallpaperBitmap) {
        bannerSwatch = wallpaperBitmap?.let { bmp ->
            runCatching {
                withContext(Dispatchers.Default) {
                    val small = android.graphics.Bitmap.createScaledBitmap(
                        bmp, 96,
                        (96f * bmp.height / bmp.width).toInt().coerceAtLeast(1),
                        false
                    )
                    val pal = Palette.from(small).generate()
                    pal.dominantSwatch ?: pal.vibrantSwatch ?: pal.mutedSwatch
                }
            }.getOrNull()
        }
    }
    // Section titles over a photo get a soft shadow so they stay
    // readable on any part of the image (Mariner 0.0.965).
    val headerStyle = MaterialTheme.typography.titleMedium.copy(
        shadow = if (wallpaperBitmap != null) {
            Shadow(
                Color.Black.copy(alpha = 0.55f),
                Offset(0f, 2f), 6f
            )
        } else {
            null
        }
    )
    var favMenuFor by remember { mutableStateOf<Long?>(null) }
    var frequentMenuFor by remember { mutableStateOf<String?>(null) }
    var showShortcutEdit by remember { mutableStateOf(false) }
    var shortcutDraft by remember { mutableStateOf("") }
    var shortcutError by remember { mutableStateOf<String?>(null) }

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
    // Batch 4: the "Frequently visited" toggle moved to the
    // Mariner key "showFrequent" (Settings → Start page); a
    // store that still carries the old "startPageTiles" key
    // reads from it until the user toggles.
    val showTiles = if ("showFrequent" in store.settings) {
        store.setting("showFrequent", "true") == "true"
    } else {
        store.setting("startPageTiles", "true") == "true"
    }
    val showFavourites = store.setting("showFavorites", "true") == "true"

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxSize().background(wallpaperColor))
        if (wallpaperBitmap != null) {
            Image(
                bitmap = wallpaperBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
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
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = bannerSwatch?.let { Color(it.rgb) }
                            ?: Color.Unspecified
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
                                    color = bannerSwatch?.let { Color(it.titleTextColor) }
                                        ?: MaterialTheme.colorScheme.onSurface,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    "PIONEER",
                                    fontFamily = SmallCapsFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = bannerSwatch?.let { Color(it.bodyTextColor) }
                                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 6.sp
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Local-only and private — everything stays on your device. " +
                                        "Type in the address bar to search or visit a site.",
                                    fontFamily = SmallCapsFontFamily,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = bannerSwatch?.let { Color(it.bodyTextColor) }
                                        ?: MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            // Shortcuts (Batch 4, Mariner beta.7): opt-in pins
            // for sites and the Offline library section. The row
            // always shows its Edit button so the first pin is
            // discoverable; chips appear only once something is
            // pinned. Long-press a chip to remove it. Consoles
            // and games are games scope and are not pinnable
            // here (see StartShortcuts).
            item {
                val pins = remember(rev) { StartShortcuts.load(store::setting) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Shortcuts",
                        style = headerStyle,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        shortcutDraft = ""
                        shortcutError = null
                        showShortcutEdit = true
                    }) {
                        Text("Edit")
                    }
                }
                if (pins.sections.isNotEmpty() || pins.sites.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pins.sections.forEach { section ->
                            ShortcutChip(
                                label = StartShortcuts.sectionLabel(section),
                                onClick = {
                                    onOpenSection(PioneerScreen.Library)
                                },
                                onLongClick = {
                                    StartShortcuts.toggleSection(store, section)
                                }
                            )
                        }
                        pins.sites.forEach { site ->
                            val host = Uri.parse(site).host ?: site
                            ShortcutChip(
                                label = host,
                                onClick = { onOpen(site) },
                                onLongClick = {
                                    StartShortcuts.toggleSite(store, site)
                                }
                            )
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
                    Text("Favorites", style = headerStyle)
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
                                        TileIcon(store, fav.url)
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
                    Text("Frequently visited", style = headerStyle)
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
                                        TileIcon(store, entry.url)
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
        }
    }

    // Shortcuts editor: add a site by URL, remove any pin.
    // The Library section pin toggles here too.
    if (showShortcutEdit) {
        val pins = remember(rev) { StartShortcuts.load(store::setting) }
        FrostDialog(
            onDismissRequest = { showShortcutEdit = false },
            title = { Text("Edit shortcuts") },
            text = {
                Column {
                    OutlinedTextField(
                        value = shortcutDraft,
                        onValueChange = { shortcutDraft = it; shortcutError = null },
                        label = { Text("Website address") },
                        singleLine = true,
                        isError = shortcutError != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (shortcutError != null) {
                        Text(
                            shortcutError ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    TextButton(
                        onClick = {
                            val raw = shortcutDraft.trim()
                            val url = when {
                                raw.isBlank() -> null
                                raw.startsWith("http://") ||
                                    raw.startsWith("https://") -> raw
                                raw.contains(".") && !raw.contains(" ") ->
                                    "https://$raw"
                                else -> null
                            }
                            if (url == null) {
                                shortcutError =
                                    "Enter a website address (example.com or https://…)."
                            } else if (url in pins.sites) {
                                shortcutError = "That site is already pinned."
                            } else {
                                StartShortcuts.save(
                                    store,
                                    pins.copy(sites = pins.sites + url)
                                )
                                shortcutDraft = ""
                                shortcutError = null
                            }
                        }
                    ) { Text("Add site") }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text(
                            "Offline library",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        TextButton(onClick = {
                            StartShortcuts.toggleSection(store, "library")
                        }) {
                            Text(
                                if ("library" in pins.sections) "Remove" else "Pin"
                            )
                        }
                    }
                    pins.sites.forEach { site ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                Uri.parse(site).host ?: site,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            TextButton(onClick = {
                                StartShortcuts.toggleSite(store, site)
                            }) { Text("Remove") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showShortcutEdit = false }) { Text("Done") }
            }
        )
    }
}

/** One shortcut pin: the site host (or section label) with
 *  Mariner's generic Public icon. Tap opens, long-press
 *  removes (Mariner StartPage.kt shortcuts row). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutChip(
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(
            Icons.Filled.Public,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text(
            label, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
