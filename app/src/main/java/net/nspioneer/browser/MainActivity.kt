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
import net.nspioneer.browser.data.StorageInfo
import net.nspioneer.browser.data.TabSnapshot
import net.nspioneer.browser.engine.Engine
import net.nspioneer.browser.engine.EngineTab
import net.nspioneer.browser.engine.GeckoEngine
import net.nspioneer.browser.engine.LongPressTarget
import net.nspioneer.browser.media.PageActions
import net.nspioneer.browser.ui.BookmarksScreen
import net.nspioneer.browser.ui.BrowserScreen
import net.nspioneer.browser.ui.DiagnosticsScreen
import net.nspioneer.browser.ui.DownloadsScreen
import net.nspioneer.browser.ui.FavoritesScreen
import net.nspioneer.browser.ui.FrostDialog
import net.nspioneer.browser.ui.HistoryScreen
import net.nspioneer.browser.ui.LibraryScreen
import net.nspioneer.browser.ui.LicencesScreen
import net.nspioneer.browser.ui.NotscapeTheme
import net.nspioneer.browser.ui.PioneerScreen
import net.nspioneer.browser.ui.SettingsScreen
import net.nspioneer.browser.ui.WelcomeScreen
import org.mozilla.geckoview.StorageController
import java.io.File

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

/** The intent extra marking a pinned-site (app mode) launch
 *  (Mariner TabList.kt:115). The shortcut intent carries
 *  ACTION_VIEW + the site URL + this extra. */
const val APP_MODE_EXTRA = "notscape_app_mode"

class MainActivity : ComponentActivity() {

    private lateinit var store: PioneerStore
    private lateinit var engine: Engine

    private var screen by mutableStateOf(PioneerScreen.Browser)
    private val tabs = mutableStateListOf<EngineTab>()
    private var activeTabIndex by mutableIntStateOf(0)
    // Batch 2: recently closed (normal tabs only, newest first,
    // capped at 20, persisted — Mariner TabList.closedSnapshots
    // + Store.saveClosedTabs). Private tabs are never recorded.
    private val closedTabs = mutableStateListOf<TabSnapshot>()
    private val homeTabIds = mutableStateSetOf<Int>()
    private var browserBackHandler: (() -> Boolean)? = null
    private var uBlockPrompt by mutableStateOf<UBlockPrompt?>(null)
    private var longPressTarget by mutableStateOf<LongPressTarget?>(null)
    // Batch 4: first-run welcome (shown until "onboarded" is
    // set) and app mode (the id of the pinned-site tab).
    private var showWelcome by mutableStateOf(false)
    private var appModeTabId by mutableStateOf<Int?>(null)

    private val activeTab: EngineTab?
        get() = tabs.getOrNull(activeTabIndex)

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        store = PioneerStore.get(this)
        engine = GeckoEngine(this)

        // A previous run crashed: surface the crash line in the
        // status panel so Copy status carries it (the app keeps
        // no other crash record — see installCrashHandler).
        runCatching {
            val crashFile = File(filesDir, "crash-last.log")
            if (crashFile.exists()) {
                val first = crashFile.readLines().firstOrNull() ?: ""
                if (first.isNotBlank()) engine.noteStatus("last crash: $first")
            }
        }

        // alpha.10: Save offline — the probe's `page-html`
        // answer arrives here; save the snapshot + record it,
        // and toast. A blank answer means the page had no HTML
        // to give (or the probe never answered).
        engine.onPageHtml = { tab, html ->
            val ok = if (html.isBlank()) false else
                PageActions.saveOfflinePage(
                    this, store, tab.title.value, tab.url.value, html
                )
            Toast.makeText(
                this,
                if (ok) "Page saved to Offline library"
                else "Couldn't save that page",
                Toast.LENGTH_SHORT
            ).show()
        }

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
            engine.onTabSelected(newTab)
            persistTabSession()
        }

        // Tabs the engine destroys itself (a popup tab's
        // window.close()): remove it from the shell's list too,
        // or the shell keeps a dead tab whose closed session the
        // view would rebind. Recorded in recently-closed first
        // (Mariner onCloseWindow -> closeTab), and a tab is kept
        // open so the browser never sits empty.
        engine.onTabDestroyed = { deadTab ->
            recordClosed(deadTab)
            removeTabFromShell(deadTab)
            if (tabs.isEmpty()) {
                createTab(isPrivate = false)
            } else {
                persistTabSession()
            }
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
        closedTabs.addAll(store.loadClosedTabs())

        // Batch 4: first-run welcome. An existing user (one
        // who has already been prompted about uBlock, or has
        // any history or bookmarks) never sees the four-page
        // welcome on upgrade — onboarded is seeded silently
        // for them. A fresh install gets the welcome.
        if (store.setting("onboarded", "") != "true") {
            val existing = store.setting("uBlockPrompted", "false") == "true" ||
                store.history.isNotEmpty() || store.bookmarks.isNotEmpty()
            if (existing) {
                store.putSetting("onboarded", "true")
            } else {
                showWelcome = true
            }
        }

        // Launched from another app with a link (ACTION_VIEW),
        // or as a pinned site (app mode — its own fresh
        // chromeless tab).
        handleAppModeOrViewIntent(intent)

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
                        PioneerScreen.Browser -> if (showWelcome) {
                            WelcomeScreen(store = store) {
                                store.putSetting("onboarded", "true")
                                showWelcome = false
                            }
                        } else BrowserScreen(
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
                            onCloseDuplicateTabs = { tab -> closeDuplicateTabs(tab) },
                            onCloseAllTabs = { isPrivate -> closeAllTabsInClass(isPrivate) },
                            onMoveTab = { tab, toIndexInClass -> moveTabWithinClass(tab, toIndexInClass) },
                            closedTabs = closedTabs,
                            onRemoveClosedTab = { snap ->
                                closedTabs.remove(snap)
                                store.saveClosedTabs(closedTabs.toList())
                            },
                            onClearClosedTabs = {
                                closedTabs.clear()
                                store.clearClosedTabs()
                            },
                            onOpenScreen = { target -> screen = target },
                            onCopyStatus = { copyStatusToClipboard() },
                            onInstallUBlock = { engine.installUBlock() },
                            // alpha.10: Save offline — the active
                            // tab must be a web page (Mariner's
                            // "Nothing to save here" guard); the
                            // probe answers asynchronously through
                            // engine.onPageHtml (set in onCreate).
                            onSaveOffline = {
                                val tab = activeTab
                                val url = tab?.url?.value.orEmpty()
                                if (tab == null || url.isBlank() || !url.startsWith("http")) {
                                    Toast.makeText(
                                        this, "Nothing to save here",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    engine.requestPageHtml(tab)
                                }
                            },
                            longPressTarget = longPressTarget,
                            onLongPressHandled = { longPressTarget = null },
                            onOpenUrlInNewTab = { url, isPrivate, switchTo ->
                                createTab(isPrivate, url, switchTo)
                            },
                            onSetBackHandler = { handler -> browserBackHandler = handler },
                            isAppMode = activeTab?.id == appModeTabId,
                            onExitAppMode = { activeTab?.let { closeTab(it) } }
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
                        PioneerScreen.Downloads -> DownloadsScreen(
                            store = store,
                            onBack = { screen = PioneerScreen.Browser }
                        )
                        PioneerScreen.Library -> LibraryScreen(
                            store = store,
                            onBack = { screen = PioneerScreen.Browser },
                            onOpenUrl = { url -> openUrlInActiveTab(url) }
                        )
                        PioneerScreen.Settings -> SettingsScreen(
                            store = store,
                            engine = engine,
                            onBack = { screen = PioneerScreen.Browser },
                            onOpenLicences = { screen = PioneerScreen.Licences },
                            onOpenUrl = { url -> openUrlInActiveTab(url) },
                            onReplayWelcome = { showWelcome = true },
                            // alpha.10: Renew mirrors Mariner's doFire —
                            // clear history + Gecko data + cache, close
                            // every tab. Bookmarks, downloads and
                            // favourites are kept.
                            onRenew = {
                                store.clearHistory()
                                engine.clearGeckoData(
                                    StorageController.ClearFlags.COOKIES or
                                        StorageController.ClearFlags.SITE_DATA or
                                        StorageController.ClearFlags.ALL_CACHES
                                )
                                StorageInfo.clearCache(this)
                                tabs.toList().forEach { closeTab(it) }
                                store.saveClosedTabs(emptyList())
                                screen = PioneerScreen.Browser
                                Toast.makeText(
                                    this,
                                    "Cleared — everything's closed",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            // alpha.10: Erase everything closes the
                            // private tabs last — the last private
                            // close wipes the private context (Batch 2).
                            onEraseAll = { closeAllTabsInClass(true) }
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
                    // Batch 3: a FrostDialog, like every other dialog
                    // in the app — not a plain AlertDialog.
                    uBlockPrompt?.let { prompt ->
                        FrostDialog(
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
        handleAppModeOrViewIntent(intent)
    }

    /** Batch 4: a link sent from another app (ACTION_VIEW).
     *  A pinned-site launch carries APP_MODE_EXTRA and gets
     *  its own fresh chromeless tab; a plain link opens in
     *  the active tab. An external http:// link upgrades
     *  when HTTPS-only is on; a non-upgradeable one loads
     *  as-is (fail-open) — the interstitial is for
     *  in-browser navigation, not for intents. Anything
     *  else is ignored. */
    private fun handleAppModeOrViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val url = intent.data?.toString() ?: return
        if (intent.getBooleanExtra(APP_MODE_EXTRA, false)) {
            screen = PioneerScreen.Browser
            val tab = createTab(isPrivate = false, url = url)
            appModeTabId = tab.id
            return
        }
        val loadUrl = if (store.setting("httpsOnly", "false") == "true" &&
            url.startsWith("http://")
        ) {
            val tab = activeTab ?: createTab(isPrivate = false)
            engine.httpsUpgradeFor(tab, url) ?: url
        } else {
            url
        }
        openUrlInActiveTab(loadUrl)
    }

    override fun onPause() {
        // Batch 2: flush the session states first so the
        // snapshot below carries the freshest history/scroll,
        // then write every dirty store file NOW — the debounced
        // save alone is not enough, the process may die before
        // its 500 ms timer fires (PHASE2-GECKOVIEW-API.md §3).
        engine.flushSessionStates()
        persistTabSession()
        store.flush()
        super.onPause()
    }

    override fun onDestroy() {
        // Batch 4: delete-on-exit (Mariner MainActivity): when
        // the activity is finishing, wipe history, cookies,
        // cache, site data and the tab session — before the
        // engine closes.
        if (isFinishing) {
            runCatching {
                if (::store.isInitialized &&
                    store.setting("deleteOnExit", "false") == "true"
                ) {
                    store.clearHistory()
                    store.saveTabSession(emptyList(), 0)
                    engine.clearGeckoData(
                        StorageController.ClearFlags.COOKIES or
                            StorageController.ClearFlags.NETWORK_CACHE or
                            StorageController.ClearFlags.IMAGE_CACHE or
                            StorageController.ClearFlags.DOM_STORAGES or
                            StorageController.ClearFlags.ALL_CACHES or
                            StorageController.ClearFlags.SITE_DATA
                    )
                }
            }
        }
        runCatching { engine.close() }
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------------

    /** Catch uncaught exceptions, write them to filesDir, then
     *  hand them to the previous handler (so the system crash
     *  dialog still appears). Pioneer previously kept no crash
     *  record at all — a silent close left zero evidence
     *  (watch-wrestling.eu, 2026-11-12). Native crashes are not
     *  caught here; they still need a logcat / bug report. */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stamp = java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
                ).format(java.util.Date())
                val writer = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(writer))
                File(filesDir, "crash-last.log").writeText(
                    "[$stamp] thread=${thread.name} " +
                        "${throwable.javaClass.name}: ${throwable.message}\n$writer"
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** The per-tab wiring every tab gets, however it was
     * created (shell or engine): history recording for
     * non-private tabs, and the long-press link menu. */
    private fun wireTab(tab: EngineTab) {
        tab.onNavigate = { navigateUrl, navigateTitle ->
            if (!tab.isPrivate) store.addHistory(navigateTitle, navigateUrl)
        }
        tab.onLongPressTarget = { target ->
            longPressTarget = target
        }
        // A dead tab used to fail silently (the engine only logged
        // it to the status panel); say so on screen, so a session
        // crash is not mistaken for an app crash.
        tab.onCrash = {
            Toast.makeText(
                this, "Tab crashed — tap reload to retry", Toast.LENGTH_SHORT
            ).show()
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
        if (switchTo) {
            activeTabIndex = tabs.lastIndex
            engine.onTabSelected(tab)
        }
        if (persist) persistTabSession()
        return tab
    }

    private fun switchTab(index: Int) {
        if (index !in tabs.indices) return
        activeTabIndex = index
        engine.onTabSelected(tabs[index])
        persistTabSession()
    }

    private fun closeTab(tab: EngineTab) {
        if (tabs.indexOf(tab) < 0) return
        recordClosed(tab)
        engine.destroyTab(tab)
        // destroyTab fires onTabDestroyed, which already removed
        // the tab from this list; removeTabFromShell is a no-op
        // when it is gone.
        removeTabFromShell(tab)
        if (tabs.isEmpty()) {
            createTab(isPrivate = false)
            return
        }
        persistTabSession()
    }

    /** Remove [tab] from the shell's list and fix the active
     *  index. Idempotent: a no-op when the tab is already gone
     *  (the engine's onTabDestroyed callback removes it first
     *  when the engine destroyed the tab itself). */
    private fun removeTabFromShell(tab: EngineTab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        tabs.removeAt(idx)
        homeTabIds.remove(tab.id)
        // Batch 4: closing the app-mode tab leaves app mode.
        if (appModeTabId == tab.id) appModeTabId = null
        if (activeTabIndex >= tabs.size) activeTabIndex = tabs.lastIndex
        else if (idx < activeTabIndex) activeTabIndex--
        // The newly active tab (if any) is selected: the engine
        // activates + focuses its session (rebuild item 4).
        if (tabs.isNotEmpty()) engine.onTabSelected(tabs[activeTabIndex])
    }

    /** Batch 2 (Mariner TabList.recordClosed): a closed NORMAL
     *  tab with a real URL is remembered newest-first, capped
     *  at 20, deduped by URL. Private tabs are never recorded —
     *  that is the point of them. */
    private fun recordClosed(tab: EngineTab) {
        if (tab.isPrivate) return
        val url = tab.url.value.trim()
        if (url.isBlank() || url == "about:blank") return
        closedTabs.removeAll { it.url == url }
        closedTabs.add(0, TabSnapshot(url, tab.title.value))
        while (closedTabs.size > 20) {
            closedTabs.removeAt(closedTabs.lastIndex)
        }
        store.saveClosedTabs(closedTabs.toList())
    }

    /** Duplicate a tab right after itself and switch to the copy
     *  (Mariner BrowserScreen.duplicateTab): the copy keeps the
     *  desktop mode, and a home tab duplicates as a home tab. */
    private fun duplicateTab(tab: EngineTab) {
        val url = tab.url.value.ifBlank { null }
        val idx = tabs.indexOf(tab)
        val copy = engine.createTab(tab.isPrivate, desktopMode = tab.isDesktopMode.value)
        wireTab(copy)
        tabs.add(if (idx >= 0) idx + 1 else tabs.size, copy)
        if (url == null) {
            homeTabIds.add(copy.id)
        } else {
            copy.load(url)
        }
        activeTabIndex = tabs.indexOf(copy)
        engine.onTabSelected(copy)
        persistTabSession()
    }

    /** Batch 2 (Mariner BrowserScreen.closeOtherTabs): close
     *  every tab in [keep]'s privacy class except [keep] itself.
     *  Goes through closeTab so each closed tab is recorded in
     *  recently-closed, exactly as a single close is. */
    private fun closeOtherTabs(keep: EngineTab) {
        tabs.filter { it.isPrivate == keep.isPrivate && it.id != keep.id }
            .toList().forEach { closeTab(it) }
        activeTabIndex = tabs.indexOf(keep).coerceAtLeast(0)
        persistTabSession()
    }

    /** Batch 2 (Mariner BrowserScreen.closeDuplicateTabs):
     *  close duplicate URLs in [keep]'s privacy class, keeping
     *  the active tab where it is one of the duplicates, else
     *  the first tab per URL. Blank URLs are never closed. */
    private fun closeDuplicateTabs(keep: EngineTab) {
        tabs.filter { it.isPrivate == keep.isPrivate }
            .groupBy { it.url.value.trim() }
            .filter { (url, ts) -> url.isNotEmpty() && ts.size > 1 }
            .values.forEach { ts ->
                val survivor = ts.firstOrNull { it.id == keep.id }
                    ?: ts.first()
                ts.filter { it.id != survivor.id }.toList()
                    .forEach { closeTab(it) }
            }
    }

    /** Batch 2 (Mariner BrowserScreen.closeAllTabsInClass):
     *  close every tab in one privacy class. */
    private fun closeAllTabsInClass(isPrivate: Boolean) {
        tabs.filter { it.isPrivate == isPrivate }.toList().forEach { closeTab(it) }
    }

    /** Batch 2 (Mariner TabList.moveWithinClass): move [tab] to
     *  [toIndexInClass] within its privacy class, keeping the
     *  class's relative order otherwise. */
    private fun moveTabWithinClass(tab: EngineTab, toIndexInClass: Int) {
        val active = activeTab
        val sameClass = tabs.filter { it.isPrivate == tab.isPrivate }
        val from = sameClass.indexOf(tab)
        if (from < 0) return
        val clamped = toIndexInClass.coerceIn(0, sameClass.size - 1)
        if (clamped == from) return
        val reordered = sameClass.toMutableList().apply {
            removeAt(from)
            add(clamped, tab)
        }
        var gi = 0
        val merged = tabs.map { t ->
            if (t.isPrivate == tab.isPrivate) reordered[gi++] else t
        }
        tabs.clear()
        tabs.addAll(merged)
        activeTabIndex = active?.let { tabs.indexOf(it) }?.takeIf { it >= 0 } ?: 0
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
            // Batch 2: the real desktop mode (no longer hardcoded
            // false), the scroll offset, and the session-state
            // JSON — together they restore the tab as it was.
            TabSnapshot(
                url = tab.url.value,
                title = tab.title.value,
                desktopMode = tab.isDesktopMode.value,
                scrollY = tab.scrollY.value,
                sessionState = tab.sessionStateJson.value ?: ""
            )
        }
        val active = tabs.getOrNull(activeTabIndex)
        val activeIdx = if (active == null || active.isPrivate) 0
            else nonPrivate.indexOf(active).coerceAtLeast(0)
        store.saveTabSession(snapshots, activeIdx)
    }

    private fun restoreTabSession() {
        // Batch 4: "Restore tabs on launch" off means a fresh
        // home tab; the saved session stays on disk, so
        // re-enabling the setting restores it.
        if (store.setting("restoreTabs", "true") != "true") {
            createTab(isPrivate = false, persist = false)
            return
        }
        val saved = store.tabSession.toList()
        if (saved.isEmpty()) {
            createTab(isPrivate = false, persist = false)
            return
        }
        saved.forEach { snapshot ->
            // Batch 2: a saved session state restores the tab
            // whole — history, scroll, zoom, form data — with
            // no load() afterwards. Without one (a pre-alpha.6
            // save, or a tab whose state never pushed) fall back
            // to the URL; the scroll rides the probe.
            val stateJson = snapshot.sessionState.ifBlank { null }
            val tab = engine.createTab(
                isPrivate = false,
                restoreStateJson = stateJson,
                desktopMode = snapshot.desktopMode,
                scrollY = snapshot.scrollY
            )
            wireTab(tab)
            tabs.add(tab)
            if (stateJson == null) {
                val url = snapshot.url.ifBlank { null }
                if (url == null) {
                    homeTabIds.add(tab.id)
                } else {
                    tab.load(url)
                }
            }
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
