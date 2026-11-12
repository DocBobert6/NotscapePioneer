package net.nspioneer.browser.ui

import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.nspioneer.browser.BuildConfig
import net.nspioneer.browser.data.HistoryEntry
import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.data.StorageInfo
import net.nspioneer.browser.engine.Engine
import net.nspioneer.browser.engine.SearchTemplate
import net.nspioneer.browser.media.Downloads
import org.mozilla.geckoview.StorageController
import java.io.File
import java.io.FileOutputStream

/**
 * Focused settings for Pioneer Phase 2. Written new — Mariner's
 * 3,467-line SettingsScreen.kt is too entangled with vault, media,
 * downloads and books to port. Every row does something real;
 * there are no dead rows.
 *
 * Sections: Appearance (theme, bar position, banner, web dark
 * mode, web text size, UI font + scale), Browsing (immersive,
 * restore tabs, open links in new tab), Search (engine choice +
 * custom URL), Privacy (tracking protection, strict popup
 * blocking, HTTPS-only, autoplay, Clear history, Erase private
 * data), Cookies & storage (cookies, third-party), Connection
 * (GPC, referer policy), Per-site controls, Data (storage sizes,
 * clear browsing data, delete on exit), Start page (wallpaper,
 * home page, home button, frequently-visited tiles),
 * Accessibility (reduced motion, force zoom, system captions),
 * Blocking (filter lists, custom rules, blocked counts),
 * Bookmarks (Import/Export HTML), Extensions (uBlock Origin
 * status + Install/Remove), About (version, GeckoView version,
 * default browser, welcome guide, Licences).
 *
 * Notes:
 * - Tracking protection: the ETP level is changeable at runtime
 *   (GeckoView 71+); a change here applies at once and reloads
 *   the current tab. The row says so plainly.
 * - uBlock Remove: Engine.uninstallUBlock() (GeckoView
 *   WebExtensionController.uninstall, Phase 3) powers the Remove
 *   row in Extensions and in the Blocking section; the result
 *   comes back through uBlockState.
 * - GeckoView version is the pinned version from the app's
 *   build script (the coordinator updates it with the pin).
 *
 * Batch 4 fills in the missing-now rows from the UI audit
 * (item 12). Deliberately OMITTED (no GeckoView 159 API —
 * see the phase docs for the javap evidence, do not fake
 * them): session-only cookies (no cookie-lifetime /
 * session-only value in the 159 AAR), DOM storage toggle
 * (GeckoSessionSettings has no setDomStorageEnabled), and
 * per-site third-party cookies (no per-site cookie
 * behaviour; the ContentPermission kinds have no cookie
 * kind). Web dark mode is prefers-color-scheme only:
 * GeckoView has no forced darkening API.
 *
 * Phase 3/4 rows stay out: filter lists and blocked counts
 * live in BlockingSettingsContent, video-player settings
 * with Phase 3, form-fill/vault and reader rows with
 * Phase 4.
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

/** One labelled switch row (Pioneer has no icon rows). */
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    supporting: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SettingsScreen(
    store: PioneerStore,
    engine: Engine,
    onBack: () -> Unit,
    onOpenLicences: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onReplayWelcome: () -> Unit,
    // alpha.10: Renew (clear + close every tab) and Erase
    // everything (clear + wipe + close private tabs) are owned
    // by the shell — Settings only asks and confirms.
    onRenew: () -> Unit = {},
    onEraseAll: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rev = store.revision
    var showClearHistory by remember { mutableStateOf(false) }
    var showErasePrivate by remember { mutableStateOf(false) }
    var showSiteControls by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf<String?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var confirmRenew by remember { mutableStateOf(false) }

    // Backup export/import (alpha.10, Mariner's flow):
    // plain JSON through the document picker. No encrypted
    // variant — Pioneer has no EncryptedBackup.
    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(store.exportBackup().toByteArray())
                }
                Toast.makeText(context, "Backup saved", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Backup failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText().orEmpty()
                if (text.isNotBlank()) {
                    confirmImport = text
                }
            }.onFailure {
                Toast.makeText(context, "Couldn't read that file", Toast.LENGTH_SHORT).show()
            }
        }
    }

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

    // Custom UI font (Batch 4, Mariner's picker verbatim):
    // a file is copied to fonts/ui-font only if its magic
    // bytes and Typeface both accept it as a real font.
    var uiFontName by remember { mutableStateOf(store.setting("uiFontCustomName", "")) }
    val uiFontPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                var loadedName: String? = null
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = File(context.filesDir, "fonts").apply { mkdirs() }
                        val tmp = File(dir, "ui-font.tmp")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { input.copyTo(it) }
                        } ?: error("unreadable")
                        val name = runCatching {
                            context.contentResolver.query(
                                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                            )?.use { c ->
                                if (c.moveToFirst()) c.getString(0) else null
                            }
                        }.getOrNull()
                        val magic = ByteArray(4)
                        val read = tmp.inputStream().use { it.read(magic) }
                        val asText = if (read == 4) String(magic, Charsets.US_ASCII) else ""
                        val magicOk = read == 4 && (
                            (magic[0] == 0.toByte() && magic[1] == 1.toByte() &&
                                magic[2] == 0.toByte() && magic[3] == 0.toByte()) ||
                                asText == "OTTO" || asText == "true"
                            )
                        val faceOk = magicOk && runCatching {
                            Typeface.createFromFile(tmp) != null
                        }.getOrDefault(false)
                        if (!faceOk) {
                            tmp.delete()
                            false
                        } else {
                            tmp.copyTo(File(dir, "ui-font"), overwrite = true)
                            tmp.delete()
                            loadedName = name ?: "Custom font"
                            true
                        }
                    }.getOrDefault(false)
                }
                if (ok) {
                    uiFontName = loadedName ?: "Custom font"
                    store.putSetting("uiFontCustomName", uiFontName)
                    store.putSetting("uiFont", "custom")
                    Toast.makeText(context, "Font loaded", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        context,
                        "That file couldn't be loaded as a font - .ttf and .otf work best",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // Custom subtitle font (alpha.10, Mariner's picker
    // pattern as in uiFontPicker above): a file is copied
    // to fonts/subtitle-font only if its magic bytes and
    // Typeface both accept it as a real font. The player
    // loads it for the "custom" subtitle face.
    var subFontName by remember { mutableStateOf(store.setting("subFontCustomName", "")) }
    val subFontPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                var loadedName: String? = null
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = File(context.filesDir, "fonts").apply { mkdirs() }
                        val tmp = File(dir, "subtitle-font.tmp")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { input.copyTo(it) }
                        } ?: error("unreadable")
                        val name = runCatching {
                            context.contentResolver.query(
                                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                            )?.use { c ->
                                if (c.moveToFirst()) c.getString(0) else null
                            }
                        }.getOrNull()
                        val magic = ByteArray(4)
                        val read = tmp.inputStream().use { it.read(magic) }
                        val asText = if (read == 4) String(magic, Charsets.US_ASCII) else ""
                        val magicOk = read == 4 && (
                            (magic[0] == 0.toByte() && magic[1] == 1.toByte() &&
                                magic[2] == 0.toByte() && magic[3] == 0.toByte()) ||
                                asText == "OTTO" || asText == "true"
                            )
                        val faceOk = magicOk && runCatching {
                            Typeface.createFromFile(tmp) != null
                        }.getOrDefault(false)
                        if (!faceOk) {
                            tmp.delete()
                            false
                        } else {
                            tmp.copyTo(File(dir, "subtitle-font"), overwrite = true)
                            tmp.delete()
                            loadedName = name ?: "Custom font"
                            true
                        }
                    }.getOrDefault(false)
                }
                if (ok) {
                    subFontName = loadedName ?: "Custom font"
                    store.putSetting("subFontCustomName", subFontName)
                    store.putSetting("subFont", "custom")
                    Toast.makeText(context, "Font loaded", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        context,
                        "That file couldn't be loaded as a font - .ttf and .otf work best",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // Custom-photo wallpaper (Batch 4): the photo is copied
    // into files/wallpaper/custom.jpg, downscaled to ≤2048 on
    // the long edge, JPEG 88. There is no crop/framing
    // dialog — Mariner's WallpaperAdjustDialog is Mariner-only
    // polish; the photo is saved as-is, downscaled.
    val customWallpaperFile = remember {
        File(context.filesDir, "wallpaper/custom.jpg")
    }
    var customWallpaperExists by remember {
        mutableStateOf(customWallpaperFile.exists())
    }
    val wallpaperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val bmp = context.contentResolver.openInputStream(uri)?.use { input ->
                            BitmapFactory.decodeStream(input)
                        } ?: return@runCatching false
                        val scale = 2048f / maxOf(bmp.width, bmp.height)
                        val scaled = if (scale < 1f) {
                            Bitmap.createScaledBitmap(
                                bmp,
                                (bmp.width * scale).toInt().coerceAtLeast(1),
                                (bmp.height * scale).toInt().coerceAtLeast(1),
                                true
                            )
                        } else {
                            bmp
                        }
                        customWallpaperFile.parentFile?.mkdirs()
                        FileOutputStream(customWallpaperFile).use { fos ->
                            scaled.compress(Bitmap.CompressFormat.JPEG, 88, fos)
                        }
                        if (scaled != bmp) scaled.recycle()
                        true
                    }.getOrDefault(false)
                }
                customWallpaperExists = customWallpaperFile.exists()
                if (ok && customWallpaperExists) {
                    store.putSetting("wallpaper", "custom")
                } else {
                    Toast.makeText(
                        context, "Couldn't read that photo", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    if (showSiteControls) {
        PerSiteControlsContent(store, engine) { showSiteControls = false }
        return
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
                // Batch 3: Material You (Mariner SettingsScreen.kt
                // ~894-910). The System theme does NOT yield the
                // dynamic palette — NotscapeTheme only uses
                // dynamicLight/DarkColorScheme when themeMode starts
                // with "materialyou" — so these rows are the only
                // way to select it. The parent row selects the
                // dynamic palette following the system; its three
                // variants sit inline beneath it.
                ChoiceRow(
                    label = "Material You",
                    selected = themeMode.startsWith("materialyou"),
                    onClick = { store.putSetting("themeMode", "materialyou") }
                )
                if (themeMode.startsWith("materialyou")) {
                    listOf(
                        Triple("Follow system", "materialyou", themeMode == "materialyou"),
                        Triple("Light", "materialyou-light", themeMode == "materialyou-light"),
                        Triple("Dark", "materialyou-dark", themeMode == "materialyou-dark")
                    ).forEach { (label, value, active) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { store.putSetting("themeMode", value) }
                                .padding(vertical = 7.dp)
                        ) {
                            Spacer(Modifier.width(44.dp))
                            Text(
                                label, modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (active) {
                                Icon(
                                    Icons.Filled.Check, contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
                InsetDivider()
                // Batch 1: address bar position (Mariner
                // SettingsScreen.kt:927-934), default Bottom.
                val barPosition = store.setting("barPosition", "bottom")
                ChoiceRow(
                    label = "Address bar: Bottom",
                    selected = barPosition == "bottom",
                    onClick = { store.putSetting("barPosition", "bottom") }
                )
                InsetDivider()
                ChoiceRow(
                    label = "Address bar: Top",
                    selected = barPosition == "top",
                    onClick = { store.putSetting("barPosition", "top") }
                )
                InsetDivider()
                // Batch 4: the "Start page tiles" row moved to
                // the Start page group below (Mariner key
                // "showFrequent") — it is no longer here.
                ChoiceRow(
                    label = "Start page banner",
                    supporting = "Show the Notscape Pioneer banner on the start page",
                    selected = store.setting("showBanner", "true") == "true",
                    onClick = {
                        val now = store.setting("showBanner", "true") == "true"
                        store.putSetting("showBanner", (!now).toString())
                    }
                )
                InsetDivider()
                // Batch 4: web dark mode. GeckoView has no
                // forced darkening — this sets prefers-color-
                // scheme, so pages WITH a dark theme follow it
                // and pages without one stay light. Applies to
                // pages as they load; already-open pages pick
                // it up on their next load.
                Text(
                    "Web dark mode",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    "Pages with a dark theme follow it. Pages without one stay light — " +
                        "GeckoView has no forced darkening.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val darkMode = store.setting("webDarkMode", "auto")
                    listOf(
                        "auto" to "Auto",
                        "on" to "On",
                        "off" to "Off"
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = darkMode == value,
                            onClick = {
                                store.putSetting("webDarkMode", value)
                                engine.setWebDarkMode(value)
                            },
                            label = { Text(label) }
                        )
                    }
                }
                InsetDivider()
                // Batch 4: web text size, 50–200% (Mariner's
                // slider: draft on drag, save on release).
                val textZoom = store.setting("webTextZoom", "100").toIntOrNull() ?: 100
                var textZoomDraft by remember { mutableStateOf<Int?>(null) }
                val shownZoom = textZoomDraft ?: textZoom
                Text(
                    "Web text size — $shownZoom%",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Slider(
                    value = shownZoom.toFloat(),
                    onValueChange = { textZoomDraft = it.toInt() },
                    onValueChangeFinished = {
                        textZoomDraft?.let {
                            store.putSetting("webTextZoom", it.toString())
                            engine.applyWebTextZoom()
                        }
                        textZoomDraft = null
                    },
                    valueRange = 50f..200f,
                    steps = 14,
                    modifier = Modifier.fillMaxWidth()
                )
                InsetDivider()
                // Batch 4: interface font + scale + custom font
                // (Mariner's Appearance rows). The wordmark
                // fonts are separate — Cormorant Garamond Bold
                // and Montserrat, always.
                Text(
                    "Interface font",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) {
                    val uiFont = store.setting("uiFont", "default")
                    listOf(
                        "default" to "Default",
                        "opensans" to "Open Sans",
                        "roboto" to "Roboto",
                        "inter" to "Inter",
                        "custom" to "Custom"
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = uiFont == value,
                            onClick = {
                                if (value == "custom" &&
                                    !uiFontFile(context).exists()
                                ) {
                                    uiFontPicker.launch(arrayOf("*/*"))
                                } else {
                                    store.putSetting("uiFont", value)
                                }
                            },
                            label = { Text(label) }
                        )
                    }
                }
                if (store.setting("uiFont", "default") == "custom") {
                    Text(
                        uiFontName.ifBlank { "Custom font" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        TextButton(onClick = {
                            uiFontPicker.launch(arrayOf("*/*"))
                        }) { Text("Replace") }
                        TextButton(onClick = {
                            uiFontFile(context).delete()
                            store.putSetting("uiFont", "default")
                            store.removeSetting("uiFontCustomName")
                            uiFontName = ""
                        }) { Text("Remove") }
                    }
                }
                InsetDivider()
                // Batch 4: interface text size.
                Text(
                    "Interface text size",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) {
                    val uiScale = store.setting("uiFontScale", "1.0").toFloatOrNull() ?: 1.0f
                    listOf(
                        0.9f to "Compact",
                        1.0f to "Default",
                        1.15f to "Large",
                        1.3f to "Extra large"
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = uiScale == value,
                            onClick = {
                                store.putSetting("uiFontScale", value.toString())
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Text(
                    "The quick brown fox jumps over the lazy dog.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
                )
                Text(
                    "Text size follows your system font size throughout the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            GroupTitle("Browsing")
            SettingsCard {
                ToggleRow(
                    label = "Immersive full screen",
                    supporting = "Hide the status bar while browsing",
                    checked = store.setting("immersive", "false") == "true",
                    onCheckedChange = { store.putSetting("immersive", it.toString()) }
                )
                InsetDivider()
                ToggleRow(
                    label = "Restore tabs on launch",
                    supporting = "Reopen your tabs when Pioneer starts",
                    checked = store.setting("restoreTabs", "true") == "true",
                    onCheckedChange = { store.putSetting("restoreTabs", it.toString()) }
                )
                InsetDivider()
                ToggleRow(
                    label = "Open links in a new tab",
                    supporting = "Links you tap open in a new tab instead of this one",
                    checked = store.setting("openLinksInNewTab", "false") == "true",
                    onCheckedChange = { store.putSetting("openLinksInNewTab", it.toString()) }
                )
            }

            GroupTitle("Search")
            SettingsCard {
                val searchEngine = store.setting("searchEngine", "duckduckgo")
                val searchEngines = listOf(
                    "duckduckgo" to "DuckDuckGo (No AI)",
                    "startpage" to "Startpage",
                    "google" to "Google",
                    "bing" to "Bing"
                )
                searchEngines.forEachIndexed { index, (value, label) ->
                    if (index > 0) InsetDivider()
                    ChoiceRow(
                        label = label,
                        selected = searchEngine == value,
                        onClick = { store.putSetting("searchEngine", value) }
                    )
                }
                InsetDivider()
                ChoiceRow(
                    label = "Custom",
                    selected = searchEngine == "custom",
                    onClick = { store.putSetting("searchEngine", "custom") }
                )
                if (searchEngine == "custom") {
                    var customDraft by remember {
                        mutableStateOf(store.setting("customSearchUrl", ""))
                    }
                    var customError by remember { mutableStateOf<String?>(null) }
                    OutlinedTextField(
                        value = customDraft,
                        onValueChange = { customDraft = it; customError = null },
                        label = { Text("Search URL (must contain %s)") },
                        placeholder = { Text("https://search.example.com/?q=%s") },
                        singleLine = true,
                        isError = customError != null,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    if (customError != null) {
                        Text(
                            customError ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    TextButton(onClick = {
                        val validation = SearchTemplate.validate(customDraft.trim())
                        if (validation != null) {
                            customError = validation
                        } else {
                            store.putSetting("customSearchUrl", customDraft.trim())
                            customError = null
                            Toast.makeText(
                                context, "Search URL saved", Toast.LENGTH_SHORT
                            ).show()
                        }
                    }) { Text("Save search URL") }
                }
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Strict popup blocking",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = store.setting("popupStrict", "true") == "true",
                        onCheckedChange = {
                            store.putSetting("popupStrict", it.toString())
                        }
                    )
                }
                Text(
                    "Blocks every popup window, including ones a tap asks for. " +
                        "You can allow a blocked popup once, or always for a site.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                )
                InsetDivider()
                // Batch 4: HTTPS-only mode (app-layer — there is
                // no public HTTPS-only API in GeckoView 159).
                ToggleRow(
                    label = "HTTPS-only mode",
                    supporting = "Plain-http pages show a warning first; " +
                        "you can allow one site once",
                    checked = store.setting("httpsOnly", "false") == "true",
                    onCheckedChange = { store.putSetting("httpsOnly", it.toString()) }
                )
                InsetDivider()
                // Batch 4: autoplay cycle (Mariner's Allowed /
                // Blocked / Muted only). The "muted" mode is a
                // probe behaviour, implemented in probe.js.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Autoplay",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    val autoplay = store.setting("autoplay", "allow")
                    TextButton(onClick = {
                        val next = when (autoplay) {
                            "allow" -> "block"
                            "block" -> "muted"
                            else -> "allow"
                        }
                        store.putSetting("autoplay", next)
                        engine.applyAutoplay()
                    }) {
                        Text(
                            when (autoplay) {
                                "block" -> "Blocked"
                                "muted" -> "Muted only"
                                else -> "Allowed"
                            }
                        )
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
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

            GroupTitle("Cookies & storage")
            SettingsCard {
                ToggleRow(
                    label = "Accept cookies",
                    checked = store.setting("cookies", "true") == "true",
                    onCheckedChange = {
                        store.putSetting("cookies", it.toString())
                        engine.applyCookieSettings()
                    }
                )
                InsetDivider()
                ToggleRow(
                    label = "Block third-party cookies",
                    supporting = "Applies to every site — GeckoView has " +
                        "no per-site cookie exception",
                    checked = store.setting("blockThirdPartyCookies", "true") == "true",
                    onCheckedChange = {
                        store.putSetting("blockThirdPartyCookies", it.toString())
                        engine.applyCookieSettings()
                    }
                )
                // Session-only cookies and the DOM storage
                // toggle are omitted on purpose: no GeckoView
                // 159 API exists for either (no cookie-lifetime
                // value in the AAR; GeckoSessionSettings has
                // no setDomStorageEnabled). Not faked.
            }

            GroupTitle("Connection")
            SettingsCard {
                ToggleRow(
                    label = "Send Global Privacy Control",
                    supporting = "Ask sites not to sell or share your data (Sec-GPC: 1)",
                    checked = store.setting("gpc", "true") == "true",
                    onCheckedChange = {
                        store.putSetting("gpc", it.toString())
                        engine.setGlobalPrivacyControl(it)
                    }
                )
                InsetDivider()
                // Batch 4: referer policy, enforced by the
                // probe's onBeforeSendHeaders.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Referer policy",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    val policy = store.setting("refererPolicy", "")
                    TextButton(onClick = {
                        val next = when (policy) {
                            "" -> "origin"
                            "origin" -> "never"
                            else -> ""
                        }
                        store.putSetting("refererPolicy", next)
                        engine.refreshProbeRules()
                    }) {
                        Text(
                            when (policy) {
                                "origin" -> "Origin only"
                                "never" -> "Never"
                                else -> "Default"
                            }
                        )
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            GroupTitle("Per-site controls")
            SettingsCard {
                ActionRow(
                    label = "Per-site controls",
                    supporting = "JavaScript, images, popups, ad blocking, text zoom, " +
                        "desktop site, autoplay and permissions for each site",
                    actionLabel = "Open",
                    onClick = { showSiteControls = true }
                )
            }

            GroupTitle("Data")
            SettingsCard {
                StorageSection()
            }
            Spacer(Modifier.height(8.dp))
            SettingsCard {
                // Batch 4: clear browsing data (Mariner's Data
                // page). Cookies, cache and site data clear
                // completely whatever the range — only history
                // is in range, and only it shows a count.
                val clearRange = remember { mutableStateOf("day") }
                val clearHistoryOn = remember { mutableStateOf(true) }
                val clearCookiesOn = remember { mutableStateOf(true) }
                val clearCacheOn = remember { mutableStateOf(true) }
                val clearSiteDataOn = remember { mutableStateOf(true) }
                val clearedNote = remember { mutableStateOf<String?>(null) }
                Text(
                    "Clear browsing data",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 4.dp)
                )
                val cutoff = clearRangeCutoff(
                    clearRange.value, System.currentTimeMillis()
                )
                val historyCount = countHistoryInRange(
                    store.history.toList(), cutoff
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) {
                    listOf(
                        "hour" to "Last hour",
                        "day" to "Last 24 hours",
                        "week" to "Last 7 days",
                        "all" to "All time"
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = clearRange.value == value,
                            onClick = { clearRange.value = value },
                            label = { Text(label) }
                        )
                    }
                }
                ToggleRow(
                    label = "History ($historyCount)",
                    checked = clearHistoryOn.value,
                    onCheckedChange = { clearHistoryOn.value = it }
                )
                InsetDivider()
                ToggleRow(
                    label = "Cookies",
                    checked = clearCookiesOn.value,
                    onCheckedChange = { clearCookiesOn.value = it }
                )
                InsetDivider()
                ToggleRow(
                    label = "Cache",
                    checked = clearCacheOn.value,
                    onCheckedChange = { clearCacheOn.value = it }
                )
                InsetDivider()
                ToggleRow(
                    label = "Site data",
                    checked = clearSiteDataOn.value,
                    onCheckedChange = { clearSiteDataOn.value = it }
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val done = ArrayList<String>()
                        if (clearHistoryOn.value) {
                            store.clearHistoryOlderThan(cutoff)
                            done.add("history ($historyCount)")
                        }
                        if (clearCookiesOn.value) {
                            engine.clearGeckoData(StorageController.ClearFlags.COOKIES)
                            done.add("cookies")
                        }
                        if (clearCacheOn.value) {
                            engine.clearGeckoData(StorageController.ClearFlags.ALL_CACHES)
                            StorageInfo.clearCache(context)
                            done.add("cache")
                        }
                        if (clearSiteDataOn.value) {
                            engine.clearGeckoData(StorageController.ClearFlags.SITE_DATA)
                            done.add("site data")
                        }
                        clearedNote.value = if (done.isEmpty()) {
                            "Nothing selected."
                        } else {
                            "Cleared: " + done.joinToString(", ") + "."
                        }
                    },
                    enabled = clearHistoryOn.value || clearCookiesOn.value ||
                        clearCacheOn.value || clearSiteDataOn.value,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Clear browsing data") }
                if (clearedNote.value != null) {
                    Text(
                        clearedNote.value ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Text(
                    "Cookies, cache and site data clear completely whatever the range. " +
                        "Only history is cleared by range.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            SettingsCard {
                ToggleRow(
                    label = "Delete browsing data on exit",
                    supporting = "History, cookies, cache, site data, and open tabs " +
                        "are cleared when you leave the app",
                    checked = store.setting("deleteOnExit", "false") == "true",
                    onCheckedChange = { store.putSetting("deleteOnExit", it.toString()) }
                )
            }
            Spacer(Modifier.height(8.dp))
            SettingsCard {
                OutlinedButton(
                    onClick = { backupExportLauncher.launch("notscape-pioneer-backup.json") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Export backup") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { backupImportLauncher.launch(arrayOf("application/json")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Import backup") }
                Text(
                    "A backup holds bookmarks, history, favourites, " +
                        "downloads, site settings and settings. Importing " +
                        "replaces what is on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            // Downloads (alpha.10, Mariner's download rows):
            // location, EPUB auto-shelf, segmented downloads,
            // ask-before, Wi-Fi-only. The EPUB row stores its
            // preference now; it takes effect when the bookshelf
            // lands in Phase 4 (reader scope) — the row says so.
            GroupTitle("Downloads")
            SettingsCard {
                Text(
                    "Download location",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
                val dlLoc = store.setting("downloadLocation", "public")
                ChoiceRow(
                    label = "Downloads folder", selected = dlLoc == "public"
                ) { store.putSetting("downloadLocation", "public") }
                InsetDivider()
                ChoiceRow(
                    label = "App folder (private)", selected = dlLoc == "app"
                ) { store.putSetting("downloadLocation", "app") }
                InsetDivider()
                ToggleRow(
                    label = "Downloaded EPUBs go to bookshelf",
                    supporting = "Stored now; takes effect when the bookshelf lands in Phase 4",
                    checked = store.setting("epubAutoShelf", "true") == "true",
                    onCheckedChange = { store.putSetting("epubAutoShelf", it.toString()) }
                )
                InsetDivider()
                ToggleRow(
                    label = "Use segmented downloads",
                    checked = store.setting("segmentedDownloads", "true") == "true",
                    onCheckedChange = { store.putSetting("segmentedDownloads", it.toString()) }
                )
                InsetDivider()
                ToggleRow(
                    label = "Ask before downloading",
                    checked = store.setting("confirmDownloads", "true") == "true",
                    onCheckedChange = { store.putSetting("confirmDownloads", it.toString()) }
                )
                InsetDivider()
                ToggleRow(
                    label = "Wi-Fi only downloads",
                    checked = store.setting("wifiOnlyDownloads", "false") == "true",
                    onCheckedChange = { store.putSetting("wifiOnlyDownloads", it.toString()) }
                )
            }

            // Video player (alpha.10, Mariner's Video player
            // page): skip interval + subtitle font/size/colour/
            // background/edge + font import. Written to the
            // store; the player applies it (applySubtitleStyle).
            GroupTitle("Video player")
            GroupTitle("Skip interval")
            SettingsCard {
                listOf(
                    5 to "5 seconds", 10 to "10 seconds",
                    15 to "15 seconds", 20 to "20 seconds",
                    25 to "25 seconds", 30 to "30 seconds",
                    60 to "1 minute", 90 to "90 seconds",
                    120 to "2 minutes", 150 to "150 seconds",
                    180 to "3 minutes", 300 to "5 minutes"
                ).forEachIndexed { i, (value, label) ->
                    if (i > 0) InsetDivider()
                    ChoiceRow(
                        label = label,
                        selected = store.setting("playerSkipSec", "10") == value.toString()
                    ) { store.putSetting("playerSkipSec", value.toString()) }
                }
            }
            Spacer(Modifier.height(14.dp))
            GroupTitle("Subtitle font")
            SettingsCard {
                listOf(
                    "default" to "Default",
                    "opensans" to "Open Sans",
                    "roboto" to "Roboto",
                    "inter" to "Inter",
                    "serif" to "Serif",
                    "mono" to "Monospace",
                    "custom" to "Custom"
                ).forEachIndexed { i, (value, label) ->
                    if (i > 0) InsetDivider()
                    ChoiceRow(
                        label = label,
                        selected = store.setting("subFont", "default") == value
                    ) { store.putSetting("subFont", value) }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                TextButton(
                    onClick = { subFontPicker.launch(arrayOf("*/*")) }
                ) { Text("Import font file") }
                if (subFontName.isNotBlank()) {
                    Text(
                        subFontName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            GroupTitle("Subtitle size")
            SettingsCard {
                listOf("75", "100", "125", "150", "200")
                    .forEachIndexed { i, value ->
                        if (i > 0) InsetDivider()
                        ChoiceRow(
                            label = "$value%",
                            selected = store.setting("subSize", "100") == value
                        ) { store.putSetting("subSize", value) }
                    }
            }
            GroupTitle("Subtitle colour")
            SettingsCard {
                listOf(
                    "white" to "White",
                    "yellow" to "Yellow",
                    "cyan" to "Cyan",
                    "green" to "Green",
                    "magenta" to "Magenta",
                    "red" to "Red",
                    "blue" to "Blue",
                    "black" to "Black"
                ).forEachIndexed { i, (value, label) ->
                    if (i > 0) InsetDivider()
                    ChoiceRow(
                        label = label,
                        selected = store.setting("subColor", "white") == value
                    ) { store.putSetting("subColor", value) }
                }
            }
            GroupTitle("Subtitle background")
            SettingsCard {
                listOf(
                    "none" to "None",
                    "black50" to "Black, half",
                    "black75" to "Black, strong",
                    "black" to "Black, solid",
                    "white75" to "White, strong"
                ).forEachIndexed { i, (value, label) ->
                    if (i > 0) InsetDivider()
                    ChoiceRow(
                        label = label,
                        selected = store.setting("subBg", "none") == value
                    ) { store.putSetting("subBg", value) }
                }
            }
            GroupTitle("Subtitle edge")
            SettingsCard {
                listOf(
                    "outline" to "Outline",
                    "shadow" to "Drop shadow",
                    "none" to "None"
                ).forEachIndexed { i, (value, label) ->
                    if (i > 0) InsetDivider()
                    ChoiceRow(
                        label = label,
                        selected = store.setting("subEdge", "outline") == value
                    ) { store.putSetting("subEdge", value) }
                }
            }
            Text(
                "Applied in the video player. Until you change " +
                    "something here, your Android system caption " +
                    "style applies, as before.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )

            GroupTitle("Start page")
            SettingsCard {
                // Batch 4: wallpaper (Follow theme / Custom
                // photo). The photo is saved as-is,
                // downscaled — no crop/framing dialog.
                Text(
                    "Wallpaper",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 4.dp)
                )
                val wallpaper = store.setting("wallpaper", "auto")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) {
                    FilterChip(
                        selected = wallpaper != "custom",
                        onClick = { store.putSetting("wallpaper", "auto") },
                        label = { Text("Follow theme") }
                    )
                    FilterChip(
                        selected = wallpaper == "custom",
                        onClick = {
                            if (customWallpaperExists) {
                                store.putSetting("wallpaper", "custom")
                            } else {
                                wallpaperPicker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                        },
                        label = { Text("Custom photo") }
                    )
                }
                if (wallpaper == "custom" && customWallpaperExists) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        TextButton(onClick = {
                            wallpaperPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }) { Text("Replace photo") }
                        TextButton(onClick = {
                            customWallpaperFile.delete()
                            customWallpaperExists = false
                            store.putSetting("wallpaper", "auto")
                        }) { Text("Remove photo") }
                    }
                }
                InsetDivider()
                // Batch 4: home-page URL + home button.
                var homeDraft by remember {
                    mutableStateOf(store.setting("homePage", ""))
                }
                var homeSaved by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = homeDraft,
                    onValueChange = { homeDraft = it; homeSaved = false },
                    label = { Text("Home page URL") },
                    placeholder = { Text("Leave blank for the start page") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                TextButton(onClick = {
                    store.putSetting("homePage", homeDraft.trim())
                    homeSaved = true
                }) {
                    Text(if (homeSaved) "Home page saved" else "Save home page")
                }
                InsetDivider()
                ToggleRow(
                    label = "Show home button",
                    supporting = "Show the Home button in the browser",
                    checked = store.setting("showHomeButton", "true") == "true",
                    onCheckedChange = { store.putSetting("showHomeButton", it.toString()) }
                )
                InsetDivider()
                // Batch 4: showFrequent, moved from the old
                // "Start page tiles" row (Mariner key).
                val showFreq = if ("showFrequent" in store.settings) {
                    store.setting("showFrequent", "true") == "true"
                } else {
                    store.setting("startPageTiles", "true") == "true"
                }
                ToggleRow(
                    label = "Frequently visited tiles",
                    supporting = "Show the Frequently visited tiles on the start page",
                    checked = showFreq,
                    onCheckedChange = { store.putSetting("showFrequent", it.toString()) }
                )
            }

            GroupTitle("Accessibility")
            SettingsCard {
                ToggleRow(
                    label = "Reduced motion",
                    supporting = "Pages are told you prefer reduced motion, and " +
                        "in-page animation and transitions are stilled",
                    checked = store.setting("reducedMotion", "false") == "true",
                    onCheckedChange = {
                        store.putSetting("reducedMotion", it.toString())
                        engine.setReducedMotion(it)
                    }
                )
                InsetDivider()
                ToggleRow(
                    label = "Force zoom",
                    supporting = "Pages that disable scaling stay zoomable",
                    checked = store.setting("forceZoom", "false") == "true",
                    onCheckedChange = {
                        store.putSetting("forceZoom", it.toString())
                        engine.setForceZoom(it)
                    }
                )
                InsetDivider()
                ActionRow(
                    label = "System caption settings",
                    supporting = "Captions for videos and other media",
                    actionLabel = "Open",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_CAPTIONING_SETTINGS)
                            )
                        }
                    }
                )
            }

            BlockingSettingsContent(
                store = store,
                uBlockState = engine.uBlockState.value,
                onUninstallUBlock = { engine.uninstallUBlock() },
                onRulesChanged = { engine.syncUserRules() }
            )

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
                    InsetDivider()
                    ActionRow(
                        label = "Remove uBlock Origin",
                        supporting = "Removes the extension from Pioneer",
                        actionLabel = "Remove",
                        onClick = { engine.uninstallUBlock() }
                    )
                }
            }

            GroupTitle("About")
            SettingsCard {
                ActionRow(
                    label = "Local-only and private",
                    supporting = "Your data is stored on this device.",
                    onClick = { }
                )
                InsetDivider()
                ActionRow(
                    label = "Version",
                    supporting = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    onClick = { }
                )
                InsetDivider()
                ActionRow(
                    label = "Package",
                    supporting = context.packageName,
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
                    label = "Set as default browser",
                    supporting = "Open links in Notscape Pioneer",
                    actionLabel = "Set",
                    onClick = {
                        runCatching {
                            val intent = if (Build.VERSION.SDK_INT >= 29) {
                                val rm = context.getSystemService(
                                    RoleManager::class.java
                                )
                                if (rm != null &&
                                    rm.isRoleAvailable(RoleManager.ROLE_BROWSER) &&
                                    !rm.isRoleHeld(RoleManager.ROLE_BROWSER)
                                ) {
                                    rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
                                } else {
                                    Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                                }
                            } else {
                                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                            }
                            context.startActivity(intent)
                        }.onFailure {
                            Log.w(
                                "Pioneer",
                                "SettingsScreen.openRoleSettings: " +
                                    it.javaClass.simpleName
                            )
                        }
                    }
                )
                InsetDivider()
                ActionRow(
                    label = "See the welcome guide again",
                    supporting = "Replay the first-run guide",
                    actionLabel = "Open",
                    onClick = onReplayWelcome
                )
                InsetDivider()
                ActionRow(
                    label = "Licences",
                    supporting = "Open-source licences for Pioneer and its libraries",
                    actionLabel = "Open",
                    onClick = onOpenLicences
                )
            }
            Spacer(Modifier.height(16.dp))
            GroupTitle("Session")
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { confirmRenew = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Autorenew,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Renew",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "Clear cookies, site storage, cache and history, and close every tab",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            GroupTitle("Danger zone")
            SettingsCard {
                Text(
                    "Erase everything",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Bookmarks, history, favourites, downloads list, " +
                        "site settings and saved tabs on this device are " +
                        "deleted, along with website cookies, DOM storage " +
                        "and cached web data. Private tabs are closed and " +
                        "private browsing data erased. Downloaded files " +
                        "stay in their folders.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { confirmClearAll = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Erase everything") }
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

    if (confirmImport != null) {
        FrostDialog(
            onDismissRequest = { confirmImport = null },
            title = { Text("Import backup?") },
            text = {
                Text(
                    "Your bookmarks, history, favourites, downloads, " +
                        "site settings and settings will be replaced by " +
                        "the backup. This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val json = confirmImport
                    confirmImport = null
                    val ok = json != null && store.importBackup(json)
                    Toast.makeText(
                        context,
                        if (ok) "Backup imported" else "That file is not a backup",
                        Toast.LENGTH_SHORT
                    ).show()
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = null }) { Text("Cancel") }
            }
        )
    }

    // Renew (alpha.10): the actual clear + close runs in the
    // shell through onRenew; Settings only asks.
    if (confirmRenew) {
        FrostDialog(
            onDismissRequest = { confirmRenew = false },
            title = { Text("Renew — clear & close?") },
            text = {
                Text(
                    "Cookies, site storage, cache and history are " +
                        "cleared, and every tab is closed. Bookmarks, " +
                        "downloads and favourites are kept."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRenew = false
                    onRenew()
                }) { Text("Renew") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRenew = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmClearAll) {
        FrostDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Erase everything?") },
            text = {
                Text(
                    "Bookmarks, history, favourites, downloads list, " +
                        "site settings and saved tabs on this device " +
                        "will be deleted, along with website cookies, " +
                        "DOM storage and cached web data. Private tabs " +
                        "will be closed and private browsing data " +
                        "erased. Downloaded files stay in their " +
                        "folders. This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    Downloads.eraseAllDownloads(context, store)
                    store.clearAllData()
                    engine.clearGeckoData(
                        StorageController.ClearFlags.COOKIES or
                            StorageController.ClearFlags.SITE_DATA or
                            StorageController.ClearFlags.ALL_CACHES
                    )
                    onEraseAll()
                    Toast.makeText(context, "Everything erased", Toast.LENGTH_SHORT).show()
                }) { Text("Erase") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Storage sizes (Batch 4, Mariner's StorageSection shape,
 * Pioneer areas only): measured off the UI thread, largest
 * first, with the cache always listed and a Clear cache
 * button that empties the cache directories.
 */
@Composable
private fun StorageSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var areas by remember { mutableStateOf<List<StorageInfo.Area>?>(null) }
    var total by remember { mutableStateOf(0L) }
    var cleared by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            val measured = withContext(Dispatchers.IO) {
                StorageInfo.measure(context)
            }
            areas = measured
            total = measured.sumOf { it.bytes }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    val list = areas
    if (list == null) {
        Text("Measuring…", style = MaterialTheme.typography.bodyLarge)
    } else {
        list.forEachIndexed { i, area ->
            if (i > 0) InsetDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    area.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    StorageInfo.formatSize(area.bytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        InsetDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Total",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                StorageInfo.formatSize(total),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    val freed = withContext(Dispatchers.IO) {
                        StorageInfo.clearCache(context)
                    }
                    cleared = "Cache cleared — ${StorageInfo.formatSize(freed)} freed."
                    refresh()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Clear cache") }
        if (cleared != null) {
            Text(
                cleared ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

// --- Clear-data helpers (Mariner data/Store.kt:155-170, verbatim logic) ---

/** The cutoff in ms for a clear-data time range: hour/day/week, else 0 (all time). */
private fun clearRangeCutoff(range: String, nowMs: Long): Long = when (range) {
    "hour" -> nowMs - 3_600_000L
    "day" -> nowMs - 86_400_000L
    "week" -> nowMs - 7L * 86_400_000L
    else -> 0L
}

/** How many history entries fall inside the range (cutoff ≤ 0 = all of them). */
private fun countHistoryInRange(
    history: List<HistoryEntry>,
    cutoffMs: Long
): Int = if (cutoffMs <= 0) {
    history.size
} else {
    history.count { it.visitedAt >= cutoffMs }
}

// --- Per-site controls (Batch 4, Mariner's panel shape) ---

/**
 * The per-site controls screen: one collapsed card per site
 * (hosts from site settings + history), expanding to the
 * per-site rows. Every change reloads that host's open tabs
 * ([Engine.reloadTabsOnHost] — Pioneer has no
 * `requestSiteReload`).
 *
 * Deliberately OMITTED: the Third-party cookies row — no
 * per-site cookie API exists in GeckoView 159 (see the
 * header comment). Desktop site is a two-state cycle
 * (On/Default) — there is no global desktop mode to force
 * off against.
 */
@Composable
private fun PerSiteControlsContent(
    store: PioneerStore,
    engine: Engine,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var siteQuery by remember { mutableStateOf("") }
    var expandedHost by remember { mutableStateOf<String?>(null) }
    var siteLimit by remember { mutableStateOf(60) }
    val httpsOnly = store.setting("httpsOnly", "false") == "true"

    SubScreen("Per-site controls", onBack) {
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = siteQuery,
                onValueChange = { siteQuery = it },
                label = { Text("Search sites") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            val hosts = remember(store.revision, siteQuery) {
                (store.siteHosts() + store.history.mapNotNull {
                    runCatching { Uri.parse(it.url).host }.getOrNull()
                })
                    .filter { !it.isNullOrBlank() }
                    .map { it!! }
                    .filter {
                        siteQuery.isBlank() ||
                            it.contains(siteQuery, ignoreCase = true)
                    }
                    .distinct()
                    .sorted()
            }
            if (hosts.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "No sites yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            hosts.take(siteLimit).forEach { host ->
                val expanded = expandedHost == host
                Spacer(Modifier.height(8.dp))
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            host,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium
                        )
                        IconButton(onClick = {
                            expandedHost = if (expanded) null else host
                        }) {
                            Icon(
                                if (expanded) Icons.Filled.KeyboardArrowUp
                                else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (expanded) "Collapse" else "Expand"
                            )
                        }
                    }
                    if (expanded) {
                        val site = store.siteSetting(host)
                        val zoom = store.siteZoom(host)
                        val autoplay = store.siteAutoplay(host)
                        InsetDivider()
                        ToggleRow(
                            label = "JavaScript",
                            checked = site.javaScript,
                            onCheckedChange = {
                                store.putSiteSetting(site.copy(javaScript = it))
                                engine.reloadTabsOnHost(host)
                            }
                        )
                        InsetDivider()
                        ToggleRow(
                            label = "Images",
                            checked = !site.blockImages,
                            onCheckedChange = {
                                store.putSiteSetting(site.copy(blockImages = !it))
                                engine.refreshProbeRules()
                                engine.reloadTabsOnHost(host)
                            }
                        )
                        InsetDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Popups",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            TextButton(onClick = {
                                val next = when {
                                    site.blockPopups -> site.copy(
                                        allowPopups = true, blockPopups = false
                                    )
                                    site.allowPopups -> site.copy(
                                        allowPopups = false, blockPopups = false
                                    )
                                    else -> site.copy(
                                        allowPopups = false, blockPopups = true
                                    )
                                }
                                store.putSiteSetting(next)
                                engine.reloadTabsOnHost(host)
                            }) {
                                Text(
                                    when {
                                        site.allowPopups -> "Allowed"
                                        site.blockPopups -> "Always denied"
                                        else -> "Blocked"
                                    }
                                )
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        InsetDivider()
                        ToggleRow(
                            label = "Ad blocking",
                            checked = !site.disableBlocking,
                            onCheckedChange = {
                                engine.setBlockingEnabled(host, it)
                                engine.reloadTabsOnHost(host)
                            }
                        )
                        if (httpsOnly) {
                            InsetDivider()
                            ToggleRow(
                                label = "Allow plain HTTP",
                                supporting = "Let this site load over http://",
                                checked = store.isHttpsException(host),
                                onCheckedChange = {
                                    store.setHttpsException(host, it)
                                    engine.reloadTabsOnHost(host)
                                }
                            )
                        }
                        InsetDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Text zoom",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            TextButton(
                                onClick = {
                                    val next = (zoom - 0.25f).coerceAtLeast(0.5f)
                                    store.putSiteZoom(host, next)
                                    engine.reloadTabsOnHost(host)
                                },
                                enabled = zoom > 0.5f
                            ) { Text("−") }
                            Text(
                                if (zoom != 1.0f) "${(zoom * 100).toInt()}%" else "Default",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            TextButton(
                                onClick = {
                                    val next = (zoom + 0.25f).coerceAtMost(2.0f)
                                    store.putSiteZoom(host, next)
                                    engine.reloadTabsOnHost(host)
                                },
                                enabled = zoom < 2.0f
                            ) { Text("+") }
                            TextButton(onClick = {
                                store.putSiteZoom(host, 1.0f)
                                engine.reloadTabsOnHost(host)
                            }) { Text("Reset") }
                        }
                        InsetDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Desktop site",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            TextButton(onClick = {
                                store.putSiteDesktop(host, !store.siteDesktop(host))
                                engine.reloadTabsOnHost(host)
                            }) {
                                Text(if (store.siteDesktop(host)) "On" else "Default")
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        InsetDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Autoplay",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            TextButton(onClick = {
                                val next = when (autoplay) {
                                    "" -> "block"
                                    "block" -> "muted"
                                    "muted" -> "allow"
                                    else -> ""
                                }
                                store.putSiteAutoplay(host, next)
                                engine.reloadTabsOnHost(host)
                            }) {
                                Text(
                                    when (autoplay) {
                                        "block" -> "Blocked"
                                        "muted" -> "Muted only"
                                        "allow" -> "Allowed"
                                        else -> "Default"
                                    }
                                )
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        InsetDivider()
                        SitePermissionRow(
                            store = store,
                            engine = engine,
                            host = host,
                            label = "Camera",
                            resource = "camera"
                        )
                        InsetDivider()
                        SitePermissionRow(
                            store = store,
                            engine = engine,
                            host = host,
                            label = "Microphone",
                            resource = "microphone"
                        )
                        InsetDivider()
                        SitePermissionRow(
                            store = store,
                            engine = engine,
                            host = host,
                            label = "Location",
                            resource = "location"
                        )
                        InsetDivider()
                        TextButton(
                            onClick = {
                                engine.clearDataForHost(host)
                                Toast.makeText(
                                    context,
                                    "Data cleared for $host",
                                    Toast.LENGTH_SHORT
                                ).show()
                                engine.reloadTabsOnHost(host)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Clear data for this site") }
                    }
                }
            }
            if (hosts.size > siteLimit) {
                TextButton(
                    onClick = { siteLimit += 60 },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Show more sites") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One Ask / Allowed / Denied cycle row in the per-site panel. */
@Composable
private fun SitePermissionRow(
    store: PioneerStore,
    engine: Engine,
    host: String,
    label: String,
    resource: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )
        val current = store.sitePermission(host, resource)
        TextButton(onClick = {
            val next = when (current) {
                "" -> "allow"
                "allow" -> "deny"
                else -> ""
            }
            store.putSitePermission(host, resource, next)
            engine.reloadTabsOnHost(host)
        }) {
            Text(
                when (current) {
                    "allow" -> "Allowed"
                    "deny" -> "Denied"
                    else -> "Ask"
                }
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
