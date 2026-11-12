package net.nspioneer.browser

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File
import java.net.URLEncoder

/**
 * Notscape Pioneer — Phase 1 spike. THROWAWAY by design.
 *
 * Minimal GeckoView browser only: one GeckoRuntime, three GeckoSessions
 * as tabs, an address bar. No bookmarks, no history, no downloads, no
 * reader, no vault. Its job is to answer, with measured figures:
 *  - does it install and run on the Pixel 10a
 *  - real APK size and memory figures
 *  - does uBlock Origin install (WebExtensionController, from AMO) and block
 *  - does protected video (Widevine) play
 *  - does Cast discovery work from a Gecko app (probe only — no session)
 *  - how does media detection compare with WebView Mariner (probe
 *    extension reports media URLs + EME status per page)
 *
 * Everything the spike learns is written to the on-screen status panel
 * AND to logcat under tag "PioneerSpike", so Robert can read it without
 * a computer and Pip can read it from a bug report.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PioneerSpike"
        private const val UBLOCK_AMO_URL =
            "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
        private const val UBLOCK_ID = "uBlock0@raymondhill.net"
        private const val PROBE_LOCATION = "resource://android/assets/extensions/pioneer-probe/"
        private const val PROBE_ID = "pioneer-probe@pioneer"
        private const val PROBE_APP = "pioneer"
        private const val CAST_RECEIVER_APP_ID = "CC1AD845" // Default Media Receiver
        private const val TEST_PAGE = "https://www.theverge.com"
        private const val DRM_TEST_PAGE = "https://demo.castlabs.com/"
    }

    private val bootMs = SystemClock.elapsedRealtime()

    private lateinit var runtime: GeckoRuntime
    private lateinit var geckoView: GeckoView
    private val sessions = mutableListOf<GeckoSession>()
    private val tabTitles = mutableListOf("Tab 1", "Tab 2", "Tab 3")
    private val tabUrls = mutableListOf("", "", "")
    private val tabCanGoBack = mutableListOf(false, false, false)

    private var selectedTab by mutableIntStateOf(0)
    private var addressText by mutableStateOf(TEST_PAGE)
    private var coldStartMs by mutableStateOf<Long?>(null)
    private var castRoutes by mutableStateOf("cast: not probed yet")

    /** Status lines for the on-screen spike panel (newest last). */
    private val statusLines = mutableStateListOf<String>()
    /** Media URLs reported by the probe extension (newest first, capped). */
    private val mediaLines = mutableStateListOf<String>()

    private lateinit var mediaRouter: MediaRouter
    private var castCallback: MediaRouter.Callback? = null

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status("Pioneer spike 0.1.0-alpha.2 — GeckoView 159.0.20261102144711 (stable)")

        runtime = createRuntime()
        status("runtime: created (ETP Strict)")

        // Three sessions, one per tab. Tab 1 starts on the ad-heavy test
        // page; tabs 2 and 3 start blank.
        repeat(3) { i ->
            val session = GeckoSession()
            attachDelegates(session, i)
            session.open(runtime)
            sessions += session
        }
        sessions[0].loadUri(TEST_PAGE)
        sessions[0].setActive(true)
        sessions[0].setFocused(true)
        status("sessions: 3 opened (tab 1 -> theverge.com)")

        installProbeExtension()
        ensureUBlock()
        probeCastRoutes()
        refreshStats()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (tabCanGoBack[selectedTab]) {
                    sessions[selectedTab].goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        setContent { SpikeUi() }
    }

    override fun onResume() {
        super.onResume()
        sessions.getOrNull(selectedTab)?.setActive(true)
    }

    override fun onPause() {
        sessions.getOrNull(selectedTab)?.setActive(false)
        super.onPause()
    }

    override fun onDestroy() {
        castCallback?.let { mediaRouter.removeCallback(it) }
        sessions.forEach { runCatching { it.close() } }
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Gecko runtime + sessions
    // ------------------------------------------------------------------

    private fun createRuntime(): GeckoRuntime {
        val settings = GeckoRuntimeSettings.Builder()
            .contentBlocking(
                ContentBlocking.Settings.Builder()
                    .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                    .build()
            )
            .build()
        return GeckoRuntime.create(this, settings)
    }

    private fun attachDelegates(session: GeckoSession, tabIndex: Int) {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                if (session === sessions.getOrNull(selectedTab)) {
                    tabUrls[tabIndex] = url ?: ""
                    addressText = url ?: ""
                }
                url?.let { status("tab ${tabIndex + 1}: navigated -> $it") }
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny>? {
                // Supplementary media log: navigations + iframe loads.
                // The probe extension is the real media detector.
                if (isMediaUrl(request.uri)) {
                    noteMedia(tabIndex, "nav", request.uri)
                }
                return null // allow
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                tabCanGoBack[tabIndex] = canGoBack
            }

            override fun onNewSession(
                session: GeckoSession,
                uri: String,
            ): GeckoResult<GeckoSession>? = null // spike: no popups, load in place
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (session === sessions.getOrNull(selectedTab)) {
                    status("tab ${tabIndex + 1}: loading $url")
                }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                status("tab ${tabIndex + 1}: load ${if (success) "OK" else "FAILED"}")
                refreshStats()
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) = Unit
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                title?.let { tabTitles[tabIndex] = it.take(24) }
            }

            override fun onFirstComposite(session: GeckoSession) {
                if (coldStartMs == null) {
                    coldStartMs = SystemClock.elapsedRealtime() - bootMs
                    status("cold start: first composite in ${coldStartMs}ms")
                }
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                status("tab ${tabIndex + 1}: fullscreen ${if (fullScreen) "ON" else "OFF"}")
            }

            override fun onCrash(session: GeckoSession) {
                status("tab ${tabIndex + 1}: SESSION CRASHED")
            }
        }

        // Spike posture: grant what the spike needs to measure (DRM media
        // keys, autoplay, persistent storage); deny the rest. No silent
        // camera/mic/location — those are denied outright here.
        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<String>?,
                callback: GeckoSession.PermissionDelegate.Callback,
            ) {
                callback.reject()
            }

            override fun onContentPermissionRequest(
                session: GeckoSession,
                request: GeckoSession.PermissionDelegate.ContentPermission,
            ): GeckoResult<Int>? {
                val allow = when (request.permission) {
                    GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS,
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
                    GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE ->
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    else ->
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                }
                return GeckoResult.fromValue(allow)
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback,
            ) {
                callback.reject() // spike: no camera/mic, ever
            }
        }
    }

    // ------------------------------------------------------------------
    // Probe extension (own built-in, media + EME reporting)
    // ------------------------------------------------------------------

    private fun installProbeExtension() {
        try {
            runtime.webExtensionController
                .ensureBuiltIn(PROBE_LOCATION, PROBE_ID)
                .accept(
                    { ext ->
                        status("probe extension: installed (${ext?.id})")
                        // One message delegate per session.
                        ext?.let { extension ->
                            sessions.forEach { session ->
                                session.webExtensionController.setMessageDelegate(
                                    extension, probeMessageDelegate, PROBE_APP
                                )
                            }
                        }
                    },
                    { e -> status("probe extension: INSTALL FAILED: ${e?.message}") },
                )
        } catch (e: Throwable) {
            status("probe extension: threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private val probeMessageDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender,
        ): GeckoResult<Any>? {
            if (message !is JSONObject) return null
            val tabIndex = sessions.indexOf(sender.session).takeIf { it >= 0 } ?: -1
            val type = message.optString("type")
            if (type != "media-probe") return null
            val url = message.optString("url")
            val items = message.optJSONArray("items")
            val count = items?.length() ?: 0
            val eme = message.optJSONObject("eme")
            status(
                "probe tab ${tabIndex + 1} [${message.optString("phase")}]: " +
                    "$count media URLs on $url" +
                    (eme?.let { " | EME: $it" } ?: "")
            )
            if (items != null) {
                for (i in 0 until items.length()) {
                    val obj = items.optJSONObject(i) ?: continue
                    noteMedia(tabIndex, obj.optString("kind"), obj.optString("url"))
                }
            }
            refreshStats()
            return null
        }
    }

    // ------------------------------------------------------------------
    // uBlock Origin (runtime install from AMO, first extension)
    // ------------------------------------------------------------------

    private fun ensureUBlock() {
        val controller = runtime.webExtensionController
        // Spike only: auto-approve the install prompt (Robert approved
        // uBlock Origin by name; a real browser would show the prompt).
        controller.promptDelegate = object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>,
            ): GeckoResult<WebExtension.PermissionPromptResponse> {
                status("uBlock: install prompt auto-approved (spike)")
                // Grant everything: permissions, private mode, and
                // technical/interaction data collection (spike only).
                return GeckoResult.fromValue(
                    WebExtension.PermissionPromptResponse(true, true, true)
                )
            }

            override fun onUpdatePrompt(
                extension: WebExtension,
                newPermissions: Array<String>,
                newOrigins: Array<String>,
                newDataCollectionPermissions: Array<String>,
            ): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.ALLOW)

            override fun onOptionalPrompt(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>,
            ): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.ALLOW)
        }
        try {
            controller.list().accept(
                { installed ->
                    val existing = installed?.firstOrNull { it.id == UBLOCK_ID }
                    if (existing != null) {
                        status("uBlock: already installed (${existing.metaData?.version ?: "?"})")
                    } else {
                        status("uBlock: installing from AMO…")
                        controller.install(
                            UBLOCK_AMO_URL,
                            WebExtensionController.INSTALLATION_METHOD_MANAGER,
                        ).accept(
                            { ext ->
                                status("uBlock: INSTALLED (${ext?.id}, ${ext?.metaData?.version ?: "?"})")
                            },
                            { e ->
                                status("uBlock: INSTALL FAILED: ${e?.javaClass?.simpleName}: ${e?.message}")
                            },
                        )
                    }
                },
                { e -> status("uBlock: list failed: ${e?.message}") },
            )
        } catch (e: Throwable) {
            status("uBlock: threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Cast probe (discovery only — no session is ever launched)
    // ------------------------------------------------------------------

    private fun probeCastRoutes() {
        try {
            // Triggers the Cast framework's route provider init.
            com.google.android.gms.cast.framework.CastContext.getSharedInstance(this)
            mediaRouter = MediaRouter.getInstance(this)
            val selector = androidx.mediarouter.media.MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(CAST_RECEIVER_APP_ID))
                .build()
            val callback = object : MediaRouter.Callback() {
                override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) =
                    reportCastRoutes(router)

                override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) =
                    reportCastRoutes(router)

                override fun onRouteSelected(
                    router: MediaRouter,
                    route: MediaRouter.RouteInfo,
                    reason: Int,
                ) = reportCastRoutes(router)
            }
            castCallback = callback
            mediaRouter.addCallback(
                selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
            )
            reportCastRoutes(mediaRouter)
            status("cast: discovery started (Default Media Receiver)")
        } catch (e: Throwable) {
            castRoutes = "cast: probe unavailable (${e.javaClass.simpleName})"
            status("cast: probe failed: ${e.message}")
        }
    }

    private fun reportCastRoutes(router: MediaRouter) {
        val routes = router.routes.filter { it.isEnabled }
        val names = routes.joinToString(", ") { it.name ?: "?" }
        castRoutes = "cast: ${routes.size} route(s)${if (names.isNotEmpty()) " — $names" else ""}"
        status("cast: ${routes.size} route(s)${if (names.isNotEmpty()) " — $names" else ""}")
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun isMediaUrl(url: String): Boolean =
        Regex("""\.(m3u8|mpd|mp4|webm|m4s|mp3|aac|ogg|oga|wav|ts)(\?|#|$)""", RegexOption.IGNORE_CASE)
            .containsMatchIn(url)

    private fun noteMedia(tabIndex: Int, kind: String, url: String) {
        if (url.isBlank()) return
        val line = "tab ${tabIndex + 1} [$kind] ${url.take(120)}"
        if (!mediaLines.contains(line)) {
            mediaLines.add(0, line)
            while (mediaLines.size > 20) mediaLines.removeAt(mediaLines.lastIndex)
        }
        Log.i(TAG, "MEDIA $line")
    }

    private fun status(line: String) {
        Log.i(TAG, line)
        statusLines.add(line)
        while (statusLines.size > 60) statusLines.removeAt(0)
    }

    private fun refreshStats() {
        val pssKb = try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getProcessMemoryInfo(intArrayOf(Process.myPid()))[0].totalPss
        } catch (e: Throwable) {
            -1
        }
        val apkMb = try {
            File(applicationInfo.sourceDir).length() / 1_048_576.0
        } catch (e: Throwable) {
            -1.0
        }
        val stats = "stats: APK %.1f MB | PSS %d MB | cold start %s".format(
            apkMb, pssKb / 1024, coldStartMs?.let { "${it}ms" } ?: "—"
        )
        statusLines.removeAll { it.startsWith("stats:") }
        statusLines.add(stats)
    }

    private fun copyStatusToClipboard() {
        val lines = mutableListOf<String>()
        lines += "SPIKE STATUS — $castRoutes"
        lines += "— status —"
        lines += statusLines
        lines += "— media probe —"
        if (mediaLines.isEmpty()) {
            lines += "(no media URLs reported yet — load a video page)"
        } else {
            lines += mediaLines
        }
        val text = lines.joinToString("\n")
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Pioneer spike status", text))
            status("status copied to clipboard (${lines.size} lines)")
        } catch (e: Throwable) {
            status("copy failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun loadAddress(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            text.contains(".") && !text.contains(" ") -> "https://$text"
            else -> "https://duckduckgo.com/?q=" + URLEncoder.encode(text, "UTF-8")
        }
        sessions[selectedTab].loadUri(url)
    }

    private fun switchTab(index: Int) {
        if (index == selectedTab || index !in sessions.indices) return
        sessions[selectedTab].setActive(false)
        selectedTab = index
        sessions[selectedTab].setActive(true)
        sessions[selectedTab].setFocused(true)
        addressText = tabUrls[selectedTab]
        status("switched to tab ${index + 1}")
    }

    // ------------------------------------------------------------------
    // UI (spike chrome only — no Mariner UI kit)
    // ------------------------------------------------------------------

    @androidx.compose.runtime.Composable
    private fun SpikeUi() {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
            background = Color(0xFF1A120C),
            surface = Color(0xFF241A12),
            onBackground = Color(0xFFF2E8D8),
            onSurface = Color(0xFFF2E8D8),
            primary = Color(0xFFCD8E48),
        )) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1A120C))
                    .padding(8.dp)
            ) {
                // Tab strip.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tabTitles.forEachIndexed { i, title ->
                        OutlinedButton(
                            onClick = { switchTab(i) },
                            modifier = Modifier.weight(1f),
                        ) { Text(title, fontSize = 12.sp) }
                    }
                }
                // Address bar.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TextField(
                        value = addressText,
                        onValueChange = { addressText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { loadAddress(addressText) }),
                    )
                    Button(onClick = { loadAddress(addressText) }) { Text("Go") }
                }
                // Test-page shortcuts.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(onClick = { sessions[selectedTab].loadUri(TEST_PAGE) }) {
                        Text("The Verge", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { sessions[selectedTab].loadUri(DRM_TEST_PAGE) }) {
                        Text("DRM test", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { sessions[selectedTab].reload() }) {
                        Text("Reload", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { refreshStats() }) {
                        Text("Stats", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { copyStatusToClipboard() }) {
                        Text("Copy status", fontSize = 12.sp)
                    }
                }
                // The engine.
                AndroidView(
                    factory = {
                        geckoView = GeckoView(it)
                        geckoView.setSession(sessions[selectedTab])
                        geckoView
                    },
                    update = { view -> view.setSession(sessions[selectedTab]) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 6.dp),
                )
                // Spike status panel.
                Text(
                    "SPIKE STATUS — ${castRoutes}",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .verticalScroll(rememberScrollState())
                        .background(Color(0xFF241A12))
                        .padding(6.dp),
                ) {
                    Text(
                        "— status —",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    statusLines.forEach { line ->
                        Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Text(
                        "— media probe —",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (mediaLines.isEmpty()) {
                        Text(
                            "(no media URLs reported yet — load a video page)",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    } else {
                        mediaLines.forEach { line ->
                            Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}
