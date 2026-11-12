package net.nspioneer.browser.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.nspioneer.browser.BuildConfig
import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.engine.Engine

/**
 * Focused settings for Pioneer Phase 2. Written new — Mariner's
 * 3,467-line SettingsScreen.kt is too entangled with vault, media,
 * downloads and books to port. Every row does something real;
 * there are no dead rows.
 *
 * Sections: Appearance (theme, start-page tiles, banner), Search
 * (DuckDuckGo, fixed), Privacy (tracking protection, Clear
 * history, Erase private data), Bookmarks (Import/Export HTML),
 * Extensions (uBlock Origin status + Install), About (version,
 * GeckoView version, Licences).
 *
 * Notes:
 * - Tracking protection: the ETP level is changeable at runtime
 *   (GeckoView 71+); a change here applies at once and reloads
 *   the current tab. The row says so plainly.
 * - uBlock Remove: the Engine interface has no uninstall method,
 *   so there is no Remove button to offer. When installed the
 *   row shows the version and offers "Check again" (install
 *   re-checks first and reports the version). The missing
 *   uninstall is reported as a gap, not faked with a dead row.
 * - GeckoView version is the pinned version from the app's
 *   build script (the coordinator updates it with the pin).
 */

/** The GeckoView version pinned in app/build.gradle.kts. */
private const val GECKOVIEW_VERSION = "159.0.20261102144711"

@Composable
private fun ChoiceRow(
    label: String,
    supporting: String? = null,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = "Selected")
        } else {
            Spacer(Modifier.width(24.dp))
        }
    }
}

@Composable
private fun ActionRow(
    label: String,
    supporting: String? = null,
    actionLabel: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (actionLabel != null) {
            TextButton(onClick = onClick) { Text(actionLabel) }
        }
    }
}

@Composable
fun SettingsScreen(
    store: PioneerStore,
    engine: Engine,
    onBack: () -> Unit,
    onOpenLicences: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val context = LocalContext.current
    val rev = store.revision
    var showClearHistory by remember { mutableStateOf(false) }
    var showErasePrivate by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            }.getOrNull()
            if (text.isNullOrBlank()) {
                Toast.makeText(context, "Couldn't read that file", Toast.LENGTH_SHORT).show()
            } else {
                val count = runCatching { store.importBookmarksHtml(text) }.getOrDefault(0)
                Toast.makeText(
                    context,
                    if (count > 0) "Imported $count bookmarks" else "No new bookmarks found",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
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
                if (ok) "Exported ${store.bookmarks.size} bookmarks" else "Export failed",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    SubScreen("Settings", onBack) {
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GroupTitle("Appearance")
            SettingsCard {
                val themeMode = store.setting("themeMode", "system")
                ChoiceRow(
                    label = "System theme",
                    selected = themeMode == "system",
                    onClick = { store.putSetting("themeMode", "system") }
                )
                InsetDivider()
                ChoiceRow(
                    label = "Light theme",
                    selected = themeMode == "light",
                    onClick = { store.putSetting("themeMode", "light") }
                )
                InsetDivider()
                ChoiceRow(
                    label = "Dark theme",
                    selected = themeMode == "dark",
                    onClick = { store.putSetting("themeMode", "dark") }
                )
                InsetDivider()
                ChoiceRow(
                    label = "Start page tiles",
                    supporting = "Show the Frequently visited tiles on the start page",
                    selected = store.setting("startPageTiles", "true") == "true",
                    onClick = {
                        val now = store.setting("startPageTiles", "true") == "true"
                        store.putSetting("startPageTiles", (!now).toString())
                    }
                )
                InsetDivider()
                ChoiceRow(
                    label = "Start page banner",
                    supporting = "Show the Notscape Pioneer banner on the start page",
                    selected = store.setting("showBanner", "true") == "true",
                    onClick = {
                        val now = store.setting("showBanner", "true") == "true"
                        store.putSetting("showBanner", (!now).toString())
                    }
                )
            }

            GroupTitle("Search")
            SettingsCard {
                ActionRow(
                    label = "DuckDuckGo",
                    supporting = "The search engine. Fixed in this phase — searches and " +
                        "anything that is not an address go to DuckDuckGo.",
                    actionLabel = "Visit",
                    onClick = { onOpenUrl("https://duckduckgo.com") }
                )
            }

            GroupTitle("Privacy")
            SettingsCard {
                val level = store.setting("trackingProtection", "strict")
                Text(
                    "Tracking protection",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    "Applies now — the current tab reloads.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChoiceRow(
                    label = "Strict",
                    supporting = "Strongest blocking",
                    selected = level == "strict",
                    onClick = {
                        store.putSetting("trackingProtection", "strict")
                        engine.setTrackingProtection("strict")
                    }
                )
                InsetDivider()
                ChoiceRow(
                    label = "Basic",
                    supporting = "Blocks known trackers only",
                    selected = level == "basic",
                    onClick = {
                        store.putSetting("trackingProtection", "basic")
                        engine.setTrackingProtection("basic")
                    }
                )
                InsetDivider()
                ChoiceRow(
                    label = "Off",
                    supporting = "No tracking protection",
                    selected = level == "off",
                    onClick = {
                        store.putSetting("trackingProtection", "off")
                        engine.setTrackingProtection("off")
                    }
                )
                InsetDivider()
                ActionRow(
                    label = "Clear history",
                    supporting = "Removes all visited pages from History",
                    actionLabel = "Clear",
                    onClick = { showClearHistory = true }
                )
                InsetDivider()
                ActionRow(
                    label = "Erase private data",
                    supporting = "Clears history and the saved tab session",
                    actionLabel = "Erase",
                    onClick = { showErasePrivate = true }
                )
            }

            GroupTitle("Bookmarks")
            SettingsCard {
                ActionRow(
                    label = "Import bookmarks (HTML)",
                    supporting = "Reads a Netscape-format bookmarks file — the same " +
                        "format Mariner exports",
                    actionLabel = "Import",
                    onClick = { importLauncher.launch(arrayOf("text/html", "text/*")) }
                )
                InsetDivider()
                ActionRow(
                    label = "Export bookmarks (HTML)",
                    supporting = "${store.bookmarks.size} bookmarks, Netscape format",
                    actionLabel = "Export",
                    onClick = { exportLauncher.launch("bookmarks.html") }
                )
            }

            GroupTitle("Extensions")
            SettingsCard {
                val uBlockState = engine.uBlockState.value
                val installed = uBlockState.startsWith("installed")
                ActionRow(
                    label = "uBlock Origin",
                    supporting = uBlockState,
                    actionLabel = if (installed) "Check again" else "Install",
                    onClick = { engine.installUBlock() }
                )
                if (installed) {
                    Text(
                        "uBlock Origin is installed. Removing an extension is not " +
                            "available in this phase.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            GroupTitle("About")
            SettingsCard {
                ActionRow(
                    label = "Version",
                    supporting = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    onClick = { }
                )
                InsetDivider()
                ActionRow(
                    label = "GeckoView",
                    supporting = "$GECKOVIEW_VERSION (stable)",
                    onClick = { }
                )
                InsetDivider()
                ActionRow(
                    label = "Licences",
                    supporting = "Open-source licences for Pioneer and its libraries",
                    actionLabel = "Open",
                    onClick = onOpenLicences
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showClearHistory) {
        FrostDialog(
            onDismissRequest = { showClearHistory = false },
            title = { Text("Clear history") },
            text = { Text("Remove all history? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    store.clearHistory()
                    Toast.makeText(context, "History cleared", Toast.LENGTH_SHORT).show()
                    showClearHistory = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistory = false }) { Text("Cancel") }
            }
        )
    }

    if (showErasePrivate) {
        FrostDialog(
            onDismissRequest = { showErasePrivate = false },
            title = { Text("Erase private data") },
            text = { Text("Clear history and the saved tab session? Bookmarks and favourites are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    store.clearHistory()
                    store.saveTabSession(emptyList(), 0)
                    Toast.makeText(context, "Private data erased", Toast.LENGTH_SHORT).show()
                    showErasePrivate = false
                }) { Text("Erase") }
            },
            dismissButton = {
                TextButton(onClick = { showErasePrivate = false }) { Text("Cancel") }
            }
        )
    }
}
