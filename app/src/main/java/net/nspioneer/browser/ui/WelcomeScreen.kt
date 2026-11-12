package net.nspioneer.browser.ui

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.nspioneer.browser.data.PioneerStore

/**
 * The first-run welcome (Batch 4, Mariner's WelcomeScreen
 * adapted to Pioneer scope): four short pages — what this
 * browser is, what the library does, the default-browser
 * ask, and the two choices worth making up front (theme,
 * search engine), written live to the same settings
 * Settings uses. Shown until the "onboarded" setting is
 * set; Settings > About can replay it at any time.
 * Skipping counts as finishing: the defaults are the ones
 * the pages would have set.
 *
 * Pioneer scope, not Mariner's: no Games page, no
 * bookshelf / reader / playlists, no notifications ask
 * (Pioneer has no download-notifications helper). Strings
 * are literals — Pioneer's strings.xml carries only
 * app_name.
 */
@Composable
fun WelcomeScreen(store: PioneerStore, onDone: () -> Unit) {
    var page by remember { mutableStateOf(0) }
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(40.dp))
            when (page) {
                0 -> {
                    Text(
                        "NOTSCAPE",
                        fontFamily = WordmarkFontFamily,
                        fontSize = 34.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        letterSpacing = 1.sp
                    )
                    Text(
                        "PIONEER",
                        fontFamily = SmallCapsFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 6.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "A browser that keeps everything on your device.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(24.dp))
                    PanelCard {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = 14.dp, vertical = 8.dp
                            )
                        ) {
                            WelcomeRow(
                                Icons.Filled.Lock,
                                "Local-only & private",
                                "History, bookmarks and favourites are " +
                                    "stored only on this device."
                            )
                            InsetDivider()
                            WelcomeRow(
                                Icons.Filled.Block,
                                "Blocking",
                                "Filter lists, enforced on-device, " +
                                    "with uBlock Origin available."
                            )
                            InsetDivider()
                            WelcomeRow(
                                Icons.Filled.MenuBook,
                                "Library",
                                "Bookmarks, history, favourites and " +
                                    "downloads, all on this device."
                            )
                        }
                    }
                }
                1 -> {
                    Text(
                        "Your library",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "The browser is half the app. The other " +
                            "half is a library that lives on the " +
                            "device with it.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(24.dp))
                    PanelCard {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = 14.dp, vertical = 8.dp
                            )
                        ) {
                            WelcomeRow(
                                Icons.Filled.Star,
                                "Bookmarks & favourites",
                                "Save pages, and keep your favourites " +
                                    "on the start page."
                            )
                            InsetDivider()
                            WelcomeRow(
                                Icons.Filled.History,
                                "History",
                                "Every page you visit, kept on the " +
                                    "device — and clearable any time."
                            )
                            InsetDivider()
                            WelcomeRow(
                                Icons.Filled.Download,
                                "Downloads & offline",
                                "Downloads are kept on the " +
                                    "device for watching and " +
                                    "listening offline."
                            )
                        }
                    }
                }
                2 -> {
                    val context = LocalContext.current
                    var askedTick by remember { mutableStateOf(0) }
                    val isDefault = remember(askedTick) {
                        isDefaultBrowser(context)
                    }
                    val roleLauncher =
                        rememberLauncherForActivityResult(
                            ActivityResultContracts
                                .StartActivityForResult()
                        ) { askedTick++ }
                    val iconBitmap = remember(context) {
                        runCatching {
                            val d = context.packageManager
                                .getApplicationIcon(
                                    context.packageName
                                )
                            val bmp = android.graphics.Bitmap
                                .createBitmap(
                                    192, 192,
                                    android.graphics.Bitmap.Config
                                        .ARGB_8888
                                )
                            val c = android.graphics.Canvas(bmp)
                            d.setBounds(0, 0, 192, 192)
                            d.draw(c)
                            bmp.asImageBitmap()
                        }.getOrNull()
                    }
                    Text(
                        "Your default browser",
                        style = MaterialTheme.typography
                            .headlineSmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Links from messages, mail and other " +
                            "apps open in the default browser. " +
                            "Make it this one and they open " +
                            "here - privately, with the ads " +
                            "already blocked.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme
                            .onSurfaceVariant
                    )
                    Spacer(Modifier.height(24.dp))
                    PanelCard {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = 14.dp, vertical = 14.dp
                            ),
                            horizontalAlignment =
                                Alignment.CenterHorizontally
                        ) {
                            if (iconBitmap != null) {
                                Image(
                                    bitmap = iconBitmap,
                                    contentDescription = null,
                                    modifier = Modifier.size(76.dp)
                                        .clip(
                                            RoundedCornerShape(18.dp)
                                        )
                                )
                                Spacer(Modifier.height(12.dp))
                            }
                            if (isDefault) {
                                WelcomeRow(
                                    Icons.Filled.Check,
                                    "Pioneer is your default",
                                    "Links from other apps " +
                                        "already open here."
                                )
                            } else {
                                Text(
                                    "Notscape Pioneer",
                                    style = MaterialTheme
                                        .typography.titleMedium
                                )
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        val intent =
                                            defaultBrowserIntent(
                                                context
                                            )
                                        roleLauncher.launch(intent)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Set as default browser")
                                }
                            }
                        }
                    }
                }
                else -> {
                    Text(
                        "Make it yours",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Two choices, applied right away - and " +
                            "changeable any time in Settings.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val themeMode = store.setting("themeMode", "system")
                    GroupTitle("Theme")
                    SettingsCard {
                        WelcomeChoiceRow(
                            "System default", themeMode == "system"
                        ) { store.putSetting("themeMode", "system") }
                        InsetDivider()
                        WelcomeChoiceRow(
                            "Light", themeMode == "light"
                        ) { store.putSetting("themeMode", "light") }
                        InsetDivider()
                        WelcomeChoiceRow(
                            "Dark", themeMode == "dark"
                        ) { store.putSetting("themeMode", "dark") }
                        InsetDivider()
                        WelcomeChoiceRow(
                            "Material You",
                            themeMode.startsWith("materialyou")
                        ) { store.putSetting("themeMode", "materialyou") }
                        if (themeMode.startsWith("materialyou")) {
                            WelcomeVariantRow(
                                "Follow system", themeMode == "materialyou"
                            ) { store.putSetting("themeMode", "materialyou") }
                            WelcomeVariantRow(
                                "Light", themeMode == "materialyou-light"
                            ) { store.putSetting("themeMode", "materialyou-light") }
                            WelcomeVariantRow(
                                "Dark", themeMode == "materialyou-dark"
                            ) { store.putSetting("themeMode", "materialyou-dark") }
                        }
                    }
                    val engine = store.setting("searchEngine", "duckduckgo")
                    GroupTitle("Search")
                    SettingsCard {
                        listOf(
                            "duckduckgo" to "DuckDuckGo (No AI)",
                            "startpage" to "Startpage",
                            "google" to "Google",
                            "bing" to "Bing"
                        ).forEachIndexed { i, (value, label) ->
                            if (i > 0) InsetDivider()
                            WelcomeChoiceRow(label, engine == value) {
                                store.putSetting("searchEngine", value)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (page < 3) {
                TextButton(onClick = onDone) { Text("Skip") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { page++ }) { Text("Continue") }
            } else {
                Button(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Start browsing") }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun WelcomeRow(
    icon: ImageVector, title: String, supporting: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier.width(30.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WelcomeChoiceRow(
    label: String, selected: Boolean, onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Whether Pioneer currently holds the default-browser
 * role (API 29+) or is the resolved default handler for
 * web links (before that). */
private fun isDefaultBrowser(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= 29) {
        val rm = context.getSystemService(RoleManager::class.java)
        rm?.isRoleHeld(RoleManager.ROLE_BROWSER) == true
    } else {
        val intent = Intent(
            Intent.ACTION_VIEW, Uri.parse("https://example.com")
        )
        val resolved = context.packageManager.resolveActivity(
            intent, PackageManager.MATCH_DEFAULT_ONLY
        )
        resolved?.activityInfo?.packageName ==
            context.packageName
    }
}

/** The intent asking for the browser role; falls back to
 * the system's default-apps screen where the role request
 * is unavailable. */
private fun defaultBrowserIntent(context: Context): Intent {
    if (Build.VERSION.SDK_INT >= 29) {
        val rm = context.getSystemService(RoleManager::class.java)
        if (rm != null &&
            rm.isRoleAvailable(RoleManager.ROLE_BROWSER) &&
            !rm.isRoleHeld(RoleManager.ROLE_BROWSER)
        ) {
            return rm.createRequestRoleIntent(
                RoleManager.ROLE_BROWSER
            )
        }
    }
    return Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
}

/** A Material You variant row: indented under the
 * Material You choice, check-marked when active — the
 * same presentation Settings uses. */
@Composable
private fun WelcomeVariantRow(
    label: String, active: Boolean, onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp)
    ) {
        Spacer(Modifier.size(48.dp))
        Text(
            label, modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )
        if (active) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
