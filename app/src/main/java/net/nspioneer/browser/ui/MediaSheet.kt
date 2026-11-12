package net.nspioneer.browser.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.nspioneer.browser.media.CastHelper
import net.nspioneer.browser.media.DetectedMedia
import net.nspioneer.browser.media.VideoPopup

/**
 * "Media on this page (N)" sheet (design §8.3, Mariner
 * BrowserScreen.kt ~6283 verbatim in structure).
 *
 * Trigger: toolbar media button → `runMediaScan()` → sheet.
 * FrostSheet, max 60% screen height; per item Play, PiP
 * (video only, launches the player straight into PiP),
 * Download, Cast (only if [castAvailable] and the URL is
 * remote); merge checkboxes (select 2 → "Merge" →
 * [onMerge] with the pair); footer per-site pop-up
 * opt-out toggle; empty state verbatim.
 *
 * Phase 4 boundary: the Cast tap is present but does not
 * launch a session (design §10). [onCast] is the shell's
 * hook for whatever the integration decides (deferred
 * notice); the sheet itself never starts a Cast session.
 * Likewise [onDownload] / [onMerge] are shell hooks —
 * the download engines live outside this file. Add to
 * playlist is Phase 4 with playlists: no parameter, no
 * button until then (audit S3 cleanup).
 */
@Composable
fun MediaSheet(
    media: List<DetectedMedia>,
    pageTitle: String,
    pageUrl: String,
    isPrivate: Boolean,
    popupHost: String,
    popupOff: Boolean,
    onPopupOffChange: (Boolean) -> Unit,
    castAvailable: Boolean,
    onPlay: (DetectedMedia, startPip: Boolean) -> Unit,
    onDownload: (DetectedMedia) -> Unit,
    onOpenPage: (DetectedMedia) -> Unit,
    onCast: ((DetectedMedia) -> Unit)? = null,
    onMerge: ((List<DetectedMedia>) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var mergeSelect by remember { mutableStateOf(setOf<DetectedMedia>()) }
    // A "page" entry for the page already open is redundant:
    // "Open" just reloads it, and the host's scripts may then
    // open tabs of their own. The sheet is for media that
    // plays — drop that one entry. Other embed pages
    // (YouTube/Vimeo/…) stay listed.
    val shownMedia = remember(media, pageUrl) {
        media.filterNot { it.kind == "page" && samePageUrl(it.url, pageUrl) }
    }
    FrostSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 28.dp)
        ) {
            GroupedCard {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Media on this page (${shownMedia.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                        if (mergeSelect.size == 2) {
                            TextButton(onClick = {
                                onMerge?.invoke(shownMedia.filter { it in mergeSelect })
                            }) { Text("Merge") }
                        }
                    }
                    if (mergeSelect.size == 1) {
                        Text(
                            "Select one more file to merge the two into one MP4.",
                            modifier = Modifier.padding(
                                horizontal = 20.dp, vertical = 2.dp
                            )
                        )
                    }
                    if (shownMedia.isEmpty()) {
                        Text(
                            "No playable media was found on this page. If a video is playing, " +
                                "give it a few seconds and try again — streams are spotted as the page loads them.",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    } else {
                        // Window-relative cap (audit): the old
                        // fixed 420dp overflowed short windows.
                        val sheetMax = (LocalConfiguration.current
                            .screenHeightDp * 0.6f).dp
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = sheetMax)) {
                            items(shownMedia) { item ->
                                ListItem(
                                    // The whole media row is tappable
                                    // to play — the small Play icon was
                                    // the only tap target, and a tap on
                                    // the row text did nothing. Page
                                    // entries keep no row tap: they are
                                    // not playable, their only action is
                                    // the "Open" button.
                                    modifier = if (item.kind == "page") Modifier else Modifier.clickable {
                                        onDismiss()
                                        onPlay(item, false)
                                    },
                                    colors = ListItemDefaults.colors(
                                        containerColor = Color.Transparent
                                    ),
                                    leadingContent = if (item.kind != "page") {
                                        {
                                            Checkbox(
                                                checked = item in mergeSelect,
                                                onCheckedChange = { on ->
                                                    mergeSelect = if (on) {
                                                        mergeSelect + item
                                                    } else {
                                                        mergeSelect - item
                                                    }
                                                }
                                            )
                                        }
                                    } else null,
                                    headlineContent = {
                                        Text(
                                            // A page entry is labeled
                                            // plainly as a page — its
                                            // host, never a media
                                            // filename (an embed URL's
                                            // last segment can be a long
                                            // opaque slug).
                                            if (item.kind == "page") pageHeadline(item.url)
                                            else item.label.ifBlank { item.url.substringAfterLast('/') },
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            "${item.kind} • ${item.url}",
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    trailingContent = {
                                        if (item.kind == "page") {
                                            if (item.url.isNotBlank()) {
                                                TextButton(onClick = {
                                                    onDismiss()
                                                    onOpenPage(item)
                                                }) { Text("Open") }
                                            }
                                        } else {
                                            Row {
                                                IconButton(onClick = {
                                                    onDismiss()
                                                    onPlay(item, false)
                                                }) {
                                                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                                                }
                                                // Video only — an
                                                // audio-only item in PiP would
                                                // be a black box. Launches the
                                                // player straight into PiP.
                                                if (item.kind == "video") {
                                                    IconButton(onClick = {
                                                        onDismiss()
                                                        onPlay(item, true)
                                                    }) {
                                                        Icon(
                                                            Icons.Filled.PictureInPictureAlt,
                                                            contentDescription = "Picture in picture"
                                                        )
                                                    }
                                                }
                                                IconButton(onClick = {
                                                    onDismiss()
                                                    onDownload(item)
                                                }) {
                                                    Icon(Icons.Filled.Download, contentDescription = "Download")
                                                }
                                                if (castAvailable && CastHelper.isRemote(item.url)) {
                                                    IconButton(onClick = {
                                                        onCast?.invoke(item)
                                                    }) {
                                                        Icon(Icons.Filled.Cast, contentDescription = "Cast")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            // Per-site pop-up opt-out — the sheet footer,
            // below the media card.
            if (!isPrivate && popupHost.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                ) {
                    Text(
                        "Don't show the video pop-up on this site",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = popupOff,
                        onCheckedChange = onPopupOffChange
                    )
                }
            }
        }
    }
}

/** The sheet's pop-up footer, read straight from the store. */
fun popupOffFor(store: net.nspioneer.browser.data.PioneerStore, host: String): Boolean =
    VideoPopup.isOff(store, host)

/** Same page URL, ignoring a trailing slash either side. */
private fun samePageUrl(a: String, b: String): Boolean =
    a.trimEnd('/') == b.trimEnd('/')

/** Headline for a "page" entry: the host, plainly a page. */
private fun pageHeadline(url: String): String {
    val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
    return if (host.isNotBlank()) "Page: $host" else "Web page"
}
