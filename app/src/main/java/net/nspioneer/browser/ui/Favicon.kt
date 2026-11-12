package net.nspioneer.browser.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.nspioneer.browser.data.PioneerStore

/**
 * Batch 3: fetched site favicons for tiles and rows.
 *
 * GeckoView supplies no favicon path (Bug 1770989, still open),
 * so there is nothing to capture from a tab the way Mariner
 * does — instead the first composition for a host fetches
 * `https://<host>/favicon.ico` once via [PioneerStore.fetchFavicon]
 * (memory + disk cache, mirroring Mariner's `loadEngineFavicon`),
 * and every later composition reads the cache. Until the fetch
 * lands — or when the host has no icon — callers show Mariner's
 * generic Public icon, never a letter tile.
 *
 * The fetch is local-only in the same sense as Mariner's: one
 * plain GET to the site itself, no third-party icon service, and
 * nothing is sent that the browser would not already send by
 * visiting the site.
 */
@Composable
fun rememberSiteFavicon(store: PioneerStore, url: String): Bitmap? {
    val host = remember(url) {
        runCatching { Uri.parse(url).host }.getOrNull() ?: ""
    }
    var bmp by remember(host) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(host) {
        if (host.isBlank()) return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) { store.fetchFavicon(host) }
    }
    return bmp
}
