package net.nspioneer.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.data.TabSnapshot
import net.nspioneer.browser.engine.Engine
import net.nspioneer.browser.engine.EngineTab
import net.nspioneer.browser.engine.GeckoEngine
import net.nspioneer.browser.ui.BookmarksScreen
import net.nspioneer.browser.ui.BrowserScreen
import net.nspioneer.browser.ui.DiagnosticsScreen
import net.nspioneer.browser.ui.FavoritesScreen
import net.nspioneer.browser.ui.HistoryScreen
import net.nspioneer.browser.ui.LicencesScreen
import net.nspioneer.browser.ui.NotscapeTheme
import net.nspioneer.browser.ui.PioneerScreen
import net.nspioneer.browser.ui.SettingsScreen

/**
 * Notscape Pioneer — Phase 2 shell.
 *
 * This activity owns exactly one [Engine] and one [PioneerStore]
 * and the screen navigation: Browser | Library (Bookmarks /
 * History / Favourites) | Settings | Licences | Diagnostics.
 * Everything that renders lives in the ui package; this file
 * owns tabs, session persistence, the uBlock install prompt and
 * the back chain's last step (exit).
 *
 * The spike's brown/gold values survive as the activity theme
 * (themes.xml windowBackground) until NotscapeTheme loads in
 * setContent; the theme itself is Mariner's, carried in AppTheme.
 *
 * Back: a sub-screen returns to the browser; in the browser the
 * shell's own back handler runs (engine back -> start page ->
 * close tab); only when it reports false does the app exit.
 */

private data class UBlockPrompt(
    val name: String,
    val permissions: List<String>,
    val decide: (Boolean) -> Unit
)

class MainActivity : ComponentActivity() {

    private lateinit var store: PioneerStore
    private lateinit var engine: Engine

    private var screen by mutableStateOf(PioneerScreen.Browser)
    private val tabs = mutableStateListOf<EngineTab>()
    private var activeTabIndex by mutableIntStateOf(0)
    private val homeTabIds = mutableStateSetOf<Int>()
    private var browserBackHandler: (() -> Boolean)? = null
    private var uBlockPrompt by mutableStateOf<UBlockPrompt?>(null)
    private var longPressUrl by mutableStateOf<String?>(null)

    private val activeTab: EngineTab?
        get() = tabs.getOrNull(activeTabIndex)

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PioneerStore.get(this)
        engine = GeckoEngine(this)

        // uBlock Origin: the install goes through a real dialog
        // (name + permissions, Install / Later). NEVER auto-approved.
        engine.onExtensionInstallPrompt = { name, permissions, decide ->
            store.putSetting("uBlockPrompted", "true")
            uBlockPrompt = UBlockPrompt(name, permissions, decide)
        }

        // Tabs the engine creates itself (window.open /
        // target=_blank): same wiring as createTab, then switch
        // to the new tab.
        engine.onTabCreated = { newTab ->
            wireTab(newTab)
            tabs.add(newTab)
            activeTabIndex = tabs.lastIndex
            persistTabSession()
        }

        // Tracking protection: the engine is built ETP Strict;
        // apply the stored level only when it differs, so a
        // plain launch never reloads the fresh tab for nothing.
        // Later changes go through SettingsScreen, which calls
        // engine.setTrackingProtection directly — there is no
        // other writer of this key, so nothing else needs to
        // re-apply it.
        val storedLevel = store.setting("trackingProtection", "strict")
        if (storedLevel != "strict") {
            engine.setTrackingProtection(storedLevel)
        }

        restoreTabSession()

        // Launched from another app with a link (ACTION_VIEW):
        // open it in the active tab.
        handleViewIntent(intent)

        // First launch (or until the prompt has been shown once):
        // ask about uBlock Origin. installUBlock checks first — if
        // it is already installed it just reports the version and
        // no prompt appears. "Later" is remembered via
        // uBlockPrompted, so the menu Install row is the retry.
        if (store.setting("uBlockPrompted", "false") != "true") {
            engine.installUBlock()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screen != PioneerScreen.Browser) {
                    screen = PioneerScreen.Browser
                    return
                }
                if (browserBackHandler?.invoke() == true) return
                finish()
            }
        })

        setContent {
            NotscapeTheme(store) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (screen) {
                        PioneerScreen.Browser -> BrowserScreen(
                            store = store,
                            engine = engine,
                            tabs = tabs,
                            activeTabIndex = activeTabIndex,
                            homeTabIds = homeTabIds,
                            onSwitchTab = { index -> switchTab(index) },
                            onNewTab = { isPrivate -> createTab(isPrivate) },
                            onCloseTab = { tab -> closeTab(tab) },
                            onDuplicateTab = { tab -> duplicateTab(tab) },
                            onCloseOtherTabs = { tab -> closeOtherTabs(tab) },
                            onOpenScreen = { target -> screen = target },
                            onCopyStatus = { copyStatusToClipboard() },
                            onInstallUBlock = { engine.installUBlock() },
                            longPressUrl = longPressUrl,
                            onLongPressHandled = { longPressUrl = null },
                            onOpenUrlInNewTab = { url, isPrivate, switchTo ->
                                createTab(isPrivate, url, switchTo)
                            },
                            onSetBackHandler = { handler -> browserBackHandler = handler }
                        )
                        PioneerScreen.Bookmarks -> BookmarksScreen(
                            store = store,
                            onBack = { screen = PioneerScreen.Browser },
                            onOpenUrl = { url -> openUrlInActiveTab(url) }
                        )
                        PioneerScreen.History -> HistoryScreen(
                            store = store,
                            onBack = { screen = PioneerScreen.Browser },
                            onOpenUrl = { url -> openUrlInActiveTab(url) }
                        )
                        PioneerScreen.Favorites -> FavoritesScreen(
                            store = store,
                            onBack = { screen = PioneerScreen.Browser },
                            onOpenUrl = { url -> openUrlInActiveTab(url) }
                        )
                        PioneerScreen.Settings -> SettingsScreen(
                            store = store,
                            engine = engine,
                            onBack = { screen = PioneerScreen.Browser },
                            onOpenLicences = { screen = PioneerScreen.Licences },
                            onOpenUrl = { url -> openUrlInActiveTab(url) }
                        )
                        PioneerScreen.Licences -> LicencesScreen(
                            onBack = { screen = PioneerScreen.Browser }
                        )
                        PioneerScreen.Diagnostics -> DiagnosticsScreen(
                            engine = engine,
                            onBack = { screen = PioneerScreen.Browser },
                            onCopyStatus = { copyStatusToClipboard() }
                        )
                    }

                    // The uBlock install prompt: a real dialog, never
                    // auto-approved. Install grants, Later declines.
                    uBlockPrompt?.let { prompt ->
                        AlertDialog(
                            onDismissRequest = {
                                prompt.decide(false)
                                uBlockPrompt = null
                            },
                            title = { Text("Install uBlock Origin?") },
                            text = {
                                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                    Text(prompt.name)
                                    Text(
                                        "This extension asks for these permissions:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (prompt.permissions.isEmpty()) {
                                        Text(
                                            "No extra permissions listed.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    } else {
                                        prompt.permissions.forEach { permission ->
                                            Text(
                                                "• $permission",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                Button(onClick = {
                                    prompt.decide(true)
                                    uBlockPrompt = null
                                }) { Text("Install") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    prompt.decide(false)
                                    uBlockPrompt = null
                                }) { Text("Later") }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        engine.refreshStats()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    /** A link sent from another app (ACTION_VIEW): open it in
     * the active tab. Anything else is ignored. */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val url = intent.data?.toString() ?: return
        openUrlInActiveTab(url)
    }

    override fun onPause() {
        persistTabSession()
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { engine.close() }
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------------

    /** The per-tab wiring every tab gets, however it was
     * created (shell or engine): history recording for
     * non-private tabs, and the long-press link menu. */
    private fun wireTab(tab: EngineTab) {
        tab.onNavigate = { navigateUrl, navigateTitle ->
            if (!tab.isPrivate) store.addHistory(navigateTitle, navigateUrl)
        }
        tab.onLongPressLink = { linkUrl ->
            longPressUrl = linkUrl
        }
    }

    private fun createTab(
        isPrivate: Boolean,
        url: String? = null,
        switchTo: Boolean = true,
        persist: Boolean = true
    ): EngineTab {
        val tab = engine.createTab(isPrivate)
        wireTab(tab)
        tabs.add(tab)
        if (url.isNullOrBlank()) {
            homeTabIds.add(tab.id)
        } else {
            tab.load(url)
        }
        if (switchTo) activeTabIndex = tabs.lastIndex
        if (persist) persistTabSession()
        return tab
    }

    private fun switchTab(index: Int) {
        if (index !in tabs.indices) return
        activeTabIndex = index
        persistTabSession()
    }

    private fun closeTab(tab: EngineTab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        engine.destroyTab(tab)
        tabs.removeAt(idx)
        homeTabIds.remove(tab.id)
        if (tabs.isEmpty()) {
            createTab(isPrivate = false)
            return
        }
        if (activeTabIndex >= tabs.size) activeTabIndex = tabs.lastIndex
        else if (idx < activeTabIndex) activeTabIndex--
        persistTabSession()
    }

    private fun duplicateTab(tab: EngineTab) {
        val url = tab.url.value.ifBlank { null }
        createTab(tab.isPrivate, url)
    }

    private fun closeOtherTabs(keep: EngineTab) {
        tabs.toList().filter { it !== keep }.forEach { other ->
            engine.destroyTab(other)
            homeTabIds.remove(other.id)
        }
        tabs.retainAll(listOf(keep))
        activeTabIndex = 0
        persistTabSession()
    }

    private fun openUrlInActiveTab(url: String) {
        screen = PioneerScreen.Browser
        val tab = activeTab ?: createTab(isPrivate = false)
        homeTabIds.remove(tab.id)
        tab.load(url)
    }

    // ------------------------------------------------------------------
    // Tab session persistence (non-private only, by URL)
    // ------------------------------------------------------------------

    private fun persistTabSession() {
        if (!::store.isInitialized) return
        val nonPrivate = tabs.filter { !it.isPrivate }
        val snapshots = nonPrivate.map { tab ->
            TabSnapshot(url = tab.url.value, title = tab.title.value, desktopMode = false)
        }
        val active = tabs.getOrNull(activeTabIndex)
        val activeIdx = if (active == null || active.isPrivate) 0
            else nonPrivate.indexOf(active).coerceAtLeast(0)
        store.saveTabSession(snapshots, activeIdx)
    }

    private fun restoreTabSession() {
        val saved = store.tabSession.toList()
        if (saved.isEmpty()) {
            createTab(isPrivate = false, persist = false)
            return
        }
        saved.forEach { snapshot ->
            // Restore by URL (no session-state restore in Phase 2);
            // a blank saved URL restores as a fresh start-page tab.
            createTab(
                isPrivate = false,
                url = snapshot.url.ifBlank { null },
                switchTo = false,
                persist = false
            )
        }
        activeTabIndex = store.tabSessionActive.coerceIn(0, tabs.lastIndex)
        persistTabSession()
    }

    // ------------------------------------------------------------------
    // Diagnostics: Copy status (as the spike did)
    // ------------------------------------------------------------------

    private fun copyStatusToClipboard() {
        val text = engine.copyStatusText()
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Pioneer status", text))
            Toast.makeText(this, "Status copied", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "Copy failed: ${e.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
        }
    }
}
