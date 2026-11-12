package net.nspioneer.browser.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Toolbar media button (design §8.3, Mariner
 * BrowserScreen.kt ~3961 verbatim in structure).
 *
 * Visible only when the active tab's MediaLog is non-empty
 * and the tab is not the start page — the shell decides
 * visibility and passes it in; this composable only draws
 * the button. Tap → [onClick] (the shell runs
 * `runMediaScan()` and opens the media sheet). Long-press →
 * [onLongClick] (the shell plays the first detected video;
 * toast "No video on this page" if none).
 *
 * Wiring (Worker B, BrowserChrome.kt): place this in the
 * bottom bar next to the menu button, gated on
 * `!activeTab.isStartPage &&
 * MediaLog.forTab(activeTab.id).isNotEmpty()`, exactly
 * where Mariner places it in its bar.
 */
@Composable
fun MediaToolbarButton(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Box(
        modifier = modifier.size(44.dp).frostBar()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.PlayCircle,
            contentDescription = "Media on this page",
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * The frost chip behind the button. Mariner builds its bar
 * buttons on a shared `frost` modifier; Pioneer exposes the
 * same recipe as [frostBar], applied above.
 */
