package net.nspioneer.browser.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.engine.FilterEngine
import net.nspioneer.browser.engine.UserBlockRules

/**
 * Notscape Pioneer — Phase 3 blocking settings.
 *
 * Mirrors Mariner's privacy-page "Blocking" card
 * (`SettingsScreen.kt`, "Block ads (filter lists)" /
 * "Block trackers" / per-list toggles / custom list URL /
 * custom ABP rules / blocked-this-session + history). Same
 * setting keys, same defaults, same strings where Mariner
 * has them — the card is carried over, not redesigned.
 *
 * Wiring: the shell (Worker B's `SettingsScreen`) drops
 * [BlockingSettingsContent] into its privacy section. The
 * uBlock row reports [uBlockState] (Engine, Worker B) and
 * offers Remove through [onUninstallUBlock]
 * (`Engine.uninstallUBlock`, Worker B) — this file never
 * touches the extension controller itself.
 *
 * Per-site exceptions are NOT set here: the site-info sheet
 * row toggles `SiteSetting.disableBlocking` (JSON key `"nb"`).
 * [DISABLE_BLOCKING_NOTE] is the exact text that row must
 * carry — it says what the toggle disables and, honestly,
 * what it does not (uBlock Origin keeps blocking on that
 * site; the app cannot toggle a per-site exception inside
 * uBlock — design §10 gap 1).
 */
object BlockingSettings {

    /** Exact site-info row text for the "Ad blocking" toggle.
     *  Worker B's site-info sheet uses this verbatim. */
    const val DISABLE_BLOCKING_NOTE =
        "Turns off Enhanced Tracking Protection, the built-in " +
            "filter lists and cosmetic hiding on this site. " +
            "uBlock Origin keeps blocking on this site."

    val listNames: List<String> = FilterEngine.listNames
}

@Composable
private fun BlockingToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun BlockingSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

/**
 * The blocking card. [store] is the PioneerStore (settings
 * read/write go through `setting()` / `putSetting()`, same
 * keys as Mariner). [uBlockState] is the Engine's uBlock
 * status line ("not installed" | "installing…" |
 * "installed <version>" | "failed: …"). [onUninstallUBlock]
 * is called when the user taps Remove; the engine reports
 * the result back through [uBlockState].
 */
@Composable
fun BlockingSettingsContent(
    store: PioneerStore,
    uBlockState: String,
    onUninstallUBlock: () -> Unit,
    modifier: Modifier = Modifier,
    // Called after any user-rule mutation (delete / import /
    // clear-all) so the shell can push the new rules to the
    // probe (engine.syncUserRules) — the store + FilterEngine
    // alone do not reach the background script.
    onRulesChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    // The store revision drives recomposition when any setting
    // changes (same pattern as SettingsScreen).
    @Suppress("UNUSED_VARIABLE")
    val rev = store.revision

    // Beta.63 #26: flush today's counts before showing the
    // history, so it shows the current session too.
    LaunchedEffect(Unit) {
        FilterEngine.flushBlockedHistory()?.let { snap ->
            store.putSetting("blockedHistory", snap)
        }
        FilterEngine.loadBlockedHistory(store.setting("blockedHistory", ""))
    }

    Column(modifier = modifier.fillMaxWidth()) {
        BlockingSectionTitle("Blocking")
        BlockingToggleRow(
            "Block ads (filter lists)",
            store.setting("blockAds", "true") == "true"
        ) { store.putSetting("blockAds", it.toString()) }
        BlockingToggleRow(
            "Block trackers",
            store.setting("blockTrackers", "true") == "true"
        ) { store.putSetting("blockTrackers", it.toString()) }

        // Status line — same strings as
        // BlockingObserver.filterListsStateText; keep the two
        // in sync if either changes.
        val filterStatus = when {
            FilterEngine.updating -> "Updating filter lists…"
            FilterEngine.ruleCount > 0 -> {
                val whenText = if (FilterEngine.updatedAt > 0) {
                    DateFormat.getDateInstance().format(Date(FilterEngine.updatedAt))
                } else "—"
                "${FilterEngine.ruleCount} rules · updated $whenText"
            }
            else -> "Lists not downloaded yet (built-in rules active)"
        }
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                filterStatus,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { FilterEngine.refreshAsync(context) },
                enabled = !FilterEngine.updating
            ) { Text("Update now") }
        }

        BlockingSectionTitle("Filter lists")
        var offTick by remember { mutableStateOf(0) }
        val offSet = remember(offTick) {
            FilterEngine.parseDisabledFilterLists(
                store.setting("filterListsOff", "")
            )
        }
        BlockingSettings.listNames.forEachIndexed { i, name ->
            BlockingToggleRow(
                name,
                i !in offSet
            ) { checked ->
                val next = if (checked) offSet - i else offSet + i
                store.putSetting(
                    "filterListsOff",
                    next.sorted().joinToString(",")
                )
                FilterEngine.setListConfig(
                    next,
                    store.setting("customFilterUrl", ""),
                    store.setting("customAbpRules", "")
                )
                FilterEngine.reloadLists(context)
                offTick++
            }
        }

        var customUrlDraft by remember {
            mutableStateOf(store.setting("customFilterUrl", ""))
        }
        OutlinedTextField(
            value = customUrlDraft,
            onValueChange = { customUrlDraft = it },
            label = { Text("Custom list URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
                .padding(top = 8.dp)
        )
        TextButton(onClick = {
            val url = customUrlDraft.trim()
            store.putSetting("customFilterUrl", url)
            FilterEngine.setListConfig(
                offSet, url, store.setting("customAbpRules", "")
            )
            FilterEngine.refreshAsync(context)
        }) { Text("Fetch custom list") }

        var abpDraft by remember {
            mutableStateOf(store.setting("customAbpRules", ""))
        }
        var abpError by remember { mutableStateOf("") }
        OutlinedTextField(
            value = abpDraft,
            onValueChange = { abpDraft = it },
            label = { Text("Custom filter rules (one per line)") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
                .padding(top = 4.dp)
        )
        if (abpError.isNotBlank()) {
            Text(
                abpError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        TextButton(onClick = {
            val bad = FilterEngine.validateAbpRules(abpDraft)
            if (bad.isNotEmpty()) {
                abpError = "Not valid filter rules: lines " +
                    bad.joinToString(", ") + "."
            } else {
                abpError = ""
                store.putSetting("customAbpRules", abpDraft)
                FilterEngine.setListConfig(
                    offSet,
                    store.setting("customFilterUrl", ""),
                    abpDraft
                )
                FilterEngine.reloadLists(context)
            }
        }) { Text("Save rules") }

        BlockingSectionTitle("Blocked")
        Text(
            "Blocked this session: ${FilterEngine.sessionBlockedCount()}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        val cats = FilterEngine.blockedByCategory
        Text(
            "Ads ${cats.getOrElse(0) { 0 }} · " +
                "Trackers ${cats.getOrElse(1) { 0 }} · " +
                "Other ${cats.getOrElse(2) { 0 }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        // Per-day history, newest first (one line per day,
        // "yyyy-MM-dd=ads,trackers,other" in the store).
        for ((day, counts) in FilterEngine.blockedHistory()) {
            Text(
                "$day: ${counts.getOrElse(0) { 0 }} ads, " +
                    "${counts.getOrElse(1) { 0 }} trackers, " +
                    "${counts.getOrElse(2) { 0 }} other",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        BlockingSectionTitle("Your rules")
        var showBlockedElements by remember { mutableStateOf(false) }
        var showBlockImport by remember { mutableStateOf(false) }
        var blockImportText by remember { mutableStateOf("") }
        var blockImportResult by remember { mutableStateOf("") }
        val netCount = UserBlockRules.parseNet(
            store.setting(UserBlockRules.NET_KEY, "")
        ).size
        val cosCount = UserBlockRules.parseCosmetic(
            store.setting(UserBlockRules.COSMETIC_KEY, "")
        ).size
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "$netCount network rules · $cosCount cosmetic rules",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showBlockedElements = true }) { Text("Manage") }
        }

        BlockingSectionTitle("uBlock Origin")
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                uBlockState,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            if (uBlockState.startsWith("installed")) {
                TextButton(onClick = onUninstallUBlock) { Text("Remove") }
            }
        }
        Text(
            "uBlock Origin is the primary blocker. The filter " +
                "lists above run alongside it as the layer this " +
                "app controls (per-site exceptions, custom lists, " +
                "your rules).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // Blocked-elements Manage (alpha.10, Mariner's dialog):
    // per-rule Delete, Export, Import, Clear all. Every
    // mutation writes the store, updates FilterEngine, and
    // calls onRulesChanged so the probe gets the new rules.
    if (showBlockedElements) {
        FrostDialog(
            onDismissRequest = { showBlockedElements = false },
            title = { Text("Blocked elements") },
            text = {
                val netRules = UserBlockRules.parseNet(
                    store.setting(UserBlockRules.NET_KEY, "")
                )
                val cosRules = UserBlockRules.parseCosmetic(
                    store.setting(UserBlockRules.COSMETIC_KEY, "")
                )
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    if (netRules.isEmpty() && cosRules.isEmpty()) {
                        Text(
                            "Nothing blocked yet. Long-press a link or " +
                                "image, or use “Block an element” in the " +
                                "browser menu, to add a rule here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    netRules.forEach { rule ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                when (rule) {
                                    is UserBlockRules.NetRule.Host ->
                                        "Everything from ${rule.host}"
                                    is UserBlockRules.NetRule.Url ->
                                        rule.url
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    val kept = netRules - rule
                                    store.putSetting(
                                        UserBlockRules.NET_KEY,
                                        UserBlockRules.serializeNet(kept)
                                    )
                                    FilterEngine.userNetRules = kept
                                    onRulesChanged()
                                }
                            ) { Text("Delete") }
                        }
                    }
                    cosRules.forEach { rule ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${rule.selector} on ${rule.host}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    val kept = cosRules - rule
                                    store.putSetting(
                                        UserBlockRules.COSMETIC_KEY,
                                        UserBlockRules.serializeCosmetic(kept)
                                    )
                                    FilterEngine.userCosmeticRules = kept
                                    onRulesChanged()
                                }
                            ) { Text("Delete") }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = {
                            val exportText = listOf(
                                UserBlockRules.serializeNet(netRules),
                                UserBlockRules.serializeCosmetic(cosRules)
                            ).filter { it.isNotEmpty() }
                                .joinToString("\n")
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, exportText)
                            }
                            context.startActivity(
                                Intent.createChooser(send, "Export block list")
                            )
                        }) { Text("Export") }
                        TextButton(onClick = {
                            blockImportText = ""
                            blockImportResult = ""
                            showBlockImport = true
                        }) { Text("Import") }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.putSetting(UserBlockRules.NET_KEY, "")
                        store.putSetting(UserBlockRules.COSMETIC_KEY, "")
                        FilterEngine.userNetRules = emptyList()
                        FilterEngine.userCosmeticRules = emptyList()
                        onRulesChanged()
                    }
                ) { Text("Clear all") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBlockedElements = false }
                ) { Text("Done") }
            }
        )
    }

    if (showBlockImport) {
        FrostDialog(
            onDismissRequest = { showBlockImport = false },
            title = { Text("Import block list") },
            text = {
                Column {
                    Text(
                        "Paste exported rules (net / cos lines). " +
                            "Cosmetic rules are checked before import; " +
                            "unsafe ones are dropped.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = blockImportText,
                        onValueChange = { blockImportText = it },
                        label = { Text("Rules") },
                        modifier = Modifier.fillMaxWidth()
                            .padding(top = 8.dp),
                        minLines = 4
                    )
                    if (blockImportResult.isNotEmpty()) {
                        Text(
                            blockImportResult,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val curNet = UserBlockRules.parseNet(
                        store.setting(UserBlockRules.NET_KEY, "")
                    )
                    val curCos = UserBlockRules.parseCosmetic(
                        store.setting(UserBlockRules.COSMETIC_KEY, "")
                    )
                    val newNet = UserBlockRules.parseNet(blockImportText)
                    val newCos = UserBlockRules.parseCosmetic(blockImportText)
                    val mergedNet = (curNet + newNet).distinct()
                    val mergedCos = (curCos + newCos).distinct()
                    store.putSetting(
                        UserBlockRules.NET_KEY,
                        UserBlockRules.serializeNet(mergedNet)
                    )
                    store.putSetting(
                        UserBlockRules.COSMETIC_KEY,
                        UserBlockRules.serializeCosmetic(mergedCos)
                    )
                    FilterEngine.userNetRules = mergedNet
                    FilterEngine.userCosmeticRules = mergedCos
                    onRulesChanged()
                    val lines = blockImportText.lines()
                        .count { it.isNotBlank() }
                    val skipped = lines - newNet.size - newCos.size
                    blockImportResult =
                        "Imported ${newNet.size + newCos.size} rules" +
                            if (skipped > 0) ", $skipped lines skipped"
                            else ""
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showBlockImport = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
