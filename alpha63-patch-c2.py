#!/usr/bin/env python3
"""Alpha.63 commit 2 (wiring): route cookie-api-probe, flag, state,
Settings toggle, Diagnostics line, version bump 62 -> 63.

Each replacement re-reads the file fresh (never a stale snapshot),
asserts the anchor occurs exactly once, and writes. Run from the
staging root: python3 alpha63-patch-c2.py
"""
import sys

EXT = "app/src/main/java/net/nspioneer/browser/engine/Extensions.kt"
BO = "app/src/main/java/net/nspioneer/browser/engine/BlockingObserver.kt"
GE = "app/src/main/java/net/nspioneer/browser/engine/GeckoEngine.kt"
ENG = "app/src/main/java/net/nspioneer/browser/engine/Engine.kt"
SET = "app/src/main/java/net/nspioneer/browser/ui/SettingsScreen.kt"
BRW = "app/src/main/java/net/nspioneer/browser/ui/BrowserScreen.kt"
BG = "app/build.gradle.kts"


def replace_once(path, old, new, label):
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()
    n = text.count(old)
    if n != 1:
        print(f"FAIL {label}: anchor occurs {n} times (want 1)")
        sys.exit(1)
    text = text.replace(old, new)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"ok {label}")


# ---- Extensions.kt: route cookie-api-probe in the one sink ----
replace_once(
    EXT,
    '        // Alpha.61: the startup handshake — the background\n'
    '        // script is alive and the port channel works.\n'
    '        if (type == "background-alive") {\n',
    '        // Alpha.63: the cookies-API probe. The background\n'
    '        // script reports whether browser.cookies is exposed\n'
    '        // there, and per-store cookie NAMES (never values)\n'
    '        // for the probed page — or an error name when getAll\n'
    '        // threw. A missing `api` reads as absent, a missing\n'
    '        // `stores` array as empty — never a false accept.\n'
    '        if (type == "cookie-api-probe") {\n'
    '            val storesArray = message.optJSONArray("stores")\n'
    '            val stores = if (storesArray != null) {\n'
    '                List(storesArray.length()) { i ->\n'
    '                    val obj = storesArray.optJSONObject(i)\n'
    '                    val namesArray = obj?.optJSONArray("names")\n'
    '                    val names = if (namesArray != null) {\n'
    '                        List(namesArray.length()) { namesArray.optString(it) }\n'
    '                    } else {\n'
    '                        emptyList()\n'
    '                    }\n'
    '                    (obj?.optString("storeId") ?: "") to names\n'
    '                }\n'
    '            } else {\n'
    '                emptyList()\n'
    '            }\n'
    '            host.noteCookiesApiProbe(\n'
    '                message.optBoolean("api", false),\n'
    '                message.optString("host"),\n'
    '                message.optString("error").takeIf { it.isNotEmpty() },\n'
    '                stores\n'
    '            )\n'
    '            return true\n'
    '        }\n'
    '        // Alpha.61: the startup handshake — the background\n'
    '        // script is alive and the port channel works.\n'
    '        if (type == "background-alive") {\n',
    "ext.route",
)

# ---- Extensions.kt: arm the bounded wait on a probe report ----
replace_once(
    EXT,
    '            host.noteCookieCapturePageLoad()\n'
    '            status.status(\n',
    '            host.noteCookieCapturePageLoad()\n'
    '            // Alpha.63: the same report arms the bounded wait\n'
    '            // for a cookie name (re-arms per load while the\n'
    '            // verdict is UNKNOWN).\n'
    '            host.noteCookiesApiPageLoad()\n'
    '            status.status(\n',
    "ext.arm",
)

# ---- Extensions.kt: ProbeHost members ----
replace_once(
    EXT,
    '    /** Alpha.59: a probe report means a page loaded — arm\n'
    '     *  the bounded wait for a `Cookie` header (re-arms per\n'
    '     *  load while the verdict is UNKNOWN). */\n'
    '    fun noteCookieCapturePageLoad() {}\n',
    '    /** Alpha.59: a probe report means a page loaded — arm\n'
    '     *  the bounded wait for a `Cookie` header (re-arms per\n'
    '     *  load while the verdict is UNKNOWN). */\n'
    '    fun noteCookieCapturePageLoad() {}\n'
    '    /** Alpha.63: the cookies-API experiment flag\n'
    '     *  (`probeCookiesApi`, default OFF). */\n'
    '    fun cookiesApiEnabled(): Boolean = false\n'
    '    /** Alpha.63: one cookies-API probe report — [apiPresent]\n'
    '     *  false means browser.cookies is undefined in the\n'
    '     *  background script; [error] names a getAll failure;\n'
    '     *  [stores] maps storeId to cookie NAMES (never values). */\n'
    '    fun noteCookiesApiProbe(\n'
    '        apiPresent: Boolean,\n'
    '        host: String,\n'
    '        error: String?,\n'
    '        stores: List<Pair<String, List<String>>>\n'
    '    ) {}\n'
    '    /** Alpha.63: a probe report means a page loaded — arm\n'
    '     *  the bounded wait for a cookie name (re-arms per load\n'
    '     *  while the verdict is UNKNOWN). */\n'
    '    fun noteCookiesApiPageLoad() {}\n',
    "ext.host",
)

# ---- BlockingObserver.kt: flag rides rules-update ----
replace_once(
    BO,
    '            put("cookieCapture", host.cookieCaptureEnabled())\n',
    '            put("cookieCapture", host.cookieCaptureEnabled())\n'
    '            // Alpha.63: the cookies-API experiment flag\n'
    '            // rides top-level with the flags; the background\n'
    '            // script reads it fresh on every push.\n'
    '            put("cookiesApi", host.cookiesApiEnabled())\n',
    "bo.rules",
)

# ---- BlockingObserver.kt: BlockingHost member ----
replace_once(
    BO,
    '    /** Alpha.59: the cookie-capture experiment flag\n'
    '     *  (`probeCookieCapture`, default OFF). */\n'
    '    fun cookieCaptureEnabled(): Boolean = false\n',
    '    /** Alpha.59: the cookie-capture experiment flag\n'
    '     *  (`probeCookieCapture`, default OFF). */\n'
    '    fun cookieCaptureEnabled(): Boolean = false\n'
    '\n'
    '    /** Alpha.63: the cookies-API experiment flag\n'
    '     *  (`probeCookiesApi`, default OFF). */\n'
    '    fun cookiesApiEnabled(): Boolean = false\n',
    "bo.host",
)

# ---- GeckoEngine.kt: experiment instance ----
replace_once(
    GE,
    '    private val cookieCaptureExperiment = CookieCaptureExperiment().also {\n'
    '        it.onStateChanged = {\n'
    '            refreshCookieCaptureState()\n'
    '            val reason = it.rejectReason?.let { r -> " ($r)" } ?: ""\n'
    '            statusPanel.status("cookie capture: ${it.verdict.name.lowercase()}$reason")\n'
    '        }\n'
    '    }\n',
    '    private val cookieCaptureExperiment = CookieCaptureExperiment().also {\n'
    '        it.onStateChanged = {\n'
    '            refreshCookieCaptureState()\n'
    '            val reason = it.rejectReason?.let { r -> " ($r)" } ?: ""\n'
    '            statusPanel.status("cookie capture: ${it.verdict.name.lowercase()}$reason")\n'
    '        }\n'
    '    }\n'
    '\n'
    '    /** Alpha.63: the cookies-API experiment verdict. The\n'
    '     *  callback refreshes the Diagnostics state line and\n'
    '     *  logs the verdict once (first-wins, so exactly one\n'
    '     *  line per session). */\n'
    '    private val cookieApiExperiment = CookieApiExperiment().also {\n'
    '        it.onStateChanged = {\n'
    '            refreshCookiesApiState()\n'
    '            val reason = it.rejectReason?.let { r -> " ($r)" } ?: ""\n'
    '            statusPanel.status("cookies API: ${it.verdict.name.lowercase()}$reason")\n'
    '        }\n'
    '    }\n',
    "ge.instance",
)

# ---- GeckoEngine.kt: settings-watch gains the flag ----
replace_once(
    GE,
    '            snapshotFlow {\n'
    '                Triple(\n'
    '                    store.setting("blockAds", "true"),\n'
    '                    store.setting("blockTrackers", "true"),\n'
    '                    // alpha.59: the cookie-capture flag rides\n'
    '                    // the same push — flipping the experiment\n'
    '                    // toggle re-sends rules-update at once.\n'
    '                    store.setting(\n'
    '                        CookieCaptureExperiment.flagKey(), "false"\n'
    '                    )\n'
    '                )\n'
    '            }.distinctUntilChanged().drop(1).collect {\n',
    '            snapshotFlow {\n'
    '                listOf(\n'
    '                    store.setting("blockAds", "true"),\n'
    '                    store.setting("blockTrackers", "true"),\n'
    '                    // alpha.59: the cookie-capture flag rides\n'
    '                    // the same push — flipping the experiment\n'
    '                    // toggle re-sends rules-update at once.\n'
    '                    store.setting(\n'
    '                        CookieCaptureExperiment.flagKey(), "false"\n'
    '                    ),\n'
    '                    // alpha.63: the cookies-API flag rides the\n'
    '                    // same push — flipping its toggle re-sends\n'
    '                    // rules-update at once. (A list, not a\n'
    '                    // Triple: four values do not fit one.)\n'
    '                    store.setting(\n'
    '                        CookieApiExperiment.flagKey(), "false"\n'
    '                    )\n'
    '                )\n'
    '            }.distinctUntilChanged().drop(1).collect {\n',
    "ge.watch",
)

# ---- GeckoEngine.kt: state holder ----
replace_once(
    GE,
    '    private val _cookieObserverState =\n'
    '        mutableStateOf(\n'
    '            CookieCaptureExperiment.observerStateText(0, 0, false)\n'
    '        )\n'
    '    override val cookieObserverState: State<String> get() = _cookieObserverState\n',
    '    private val _cookieObserverState =\n'
    '        mutableStateOf(\n'
    '            CookieCaptureExperiment.observerStateText(0, 0, false)\n'
    '        )\n'
    '    override val cookieObserverState: State<String> get() = _cookieObserverState\n'
    '\n'
    '    private val _cookieApiState =\n'
    '        mutableStateOf(\n'
    '            cookieApiExperiment.cookiesApiStateText(\n'
    '                CookieApiExperiment.isEnabled(store)\n'
    '            )\n'
    '        )\n'
    '    override val cookieApiState: State<String> get() = _cookieApiState\n',
    "ge.state",
)

# ---- GeckoEngine.kt: refresh function ----
replace_once(
    GE,
    '    private fun refreshCookieCaptureState() {\n'
    '        mainHandler.post {\n'
    '            _cookieCaptureState.value = cookieCaptureExperiment.cookieCaptureStateText(\n'
    '                CookieCaptureExperiment.isEnabled(store)\n'
    '            )\n'
    '        }\n'
    '    }\n',
    '    private fun refreshCookieCaptureState() {\n'
    '        mainHandler.post {\n'
    '            _cookieCaptureState.value = cookieCaptureExperiment.cookieCaptureStateText(\n'
    '                CookieCaptureExperiment.isEnabled(store)\n'
    '            )\n'
    '        }\n'
    '    }\n'
    '\n'
    '    /** Refresh the cookies-API experiment status line\n'
    '     *  from the verdict (alpha.63: `cookieApiState` ←\n'
    '     *  `cookiesApiStateText()`). Posted to the main\n'
    '     *  thread: the probe reports can arrive off it. */\n'
    '    private fun refreshCookiesApiState() {\n'
    '        mainHandler.post {\n'
    '            _cookieApiState.value = cookieApiExperiment.cookiesApiStateText(\n'
    '                CookieApiExperiment.isEnabled(store)\n'
    '            )\n'
    '        }\n'
    '    }\n',
    "ge.refresh",
)

# ---- GeckoEngine.kt: flag override ----
replace_once(
    GE,
    '    override fun cookieCaptureEnabled(): Boolean =\n'
    '        CookieCaptureExperiment.isEnabled(store)\n',
    '    override fun cookieCaptureEnabled(): Boolean =\n'
    '        CookieCaptureExperiment.isEnabled(store)\n'
    '\n'
    '    /** Alpha.63: the cookies-API experiment flag\n'
    '     *  (`probeCookiesApi`, default OFF). */\n'
    '    override fun cookiesApiEnabled(): Boolean =\n'
    '        CookieApiExperiment.isEnabled(store)\n',
    "ge.flag",
)

# ---- GeckoEngine.kt: probe handler + bounded wait ----
replace_once(
    GE,
    '    override fun noteCookieCapturePageLoad() {\n'
    '        if (!CookieCaptureExperiment.isEnabled(store)) return\n'
    '        if (cookieCaptureExperiment.verdict != CookieCaptureExperiment.Verdict.UNKNOWN ||\n'
    '            cookieCaptureTimeoutArmed\n'
    '        ) return\n'
    '        cookieCaptureTimeoutArmed = true\n'
    '        mainHandler.postDelayed({\n'
    '            cookieCaptureExperiment.noteTimeout()\n'
    '            cookieCaptureTimeoutArmed = false\n'
    '        }, 10_000)\n'
    '    }\n',
    '    override fun noteCookieCapturePageLoad() {\n'
    '        if (!CookieCaptureExperiment.isEnabled(store)) return\n'
    '        if (cookieCaptureExperiment.verdict != CookieCaptureExperiment.Verdict.UNKNOWN ||\n'
    '            cookieCaptureTimeoutArmed\n'
    '        ) return\n'
    '        cookieCaptureTimeoutArmed = true\n'
    '        mainHandler.postDelayed({\n'
    '            cookieCaptureExperiment.noteTimeout()\n'
    '            cookieCaptureTimeoutArmed = false\n'
    '        }, 10_000)\n'
    '    }\n'
    '\n'
    '    /** Alpha.63: one cookies-API probe report from the\n'
    '     *  background script. Accepted regardless of the store\n'
    '     *  flag (like the alpha.60 probe): a report that\n'
    '     *  arrives after a mid-flight flip-off is still\n'
    '     *  evidence, and the Diagnostics line reads "off"\n'
    '     *  while the flag is off anyway. Posted to the main\n'
    '     *  thread: probe reports can arrive off it. The first\n'
    '     *  report also logs a status line with the per-store\n'
    '     *  counts, so Copy status shows what the probe found. */\n'
    '    override fun noteCookiesApiProbe(\n'
    '        apiPresent: Boolean,\n'
    '        host: String,\n'
    '        error: String?,\n'
    '        stores: List<Pair<String, List<String>>>\n'
    '    ) {\n'
    '        val first = cookieApiExperiment.probesRun == 0\n'
    '        mainHandler.post {\n'
    '            if (!apiPresent) {\n'
    '                cookieApiExperiment.noteApiAbsent(host)\n'
    '            } else if (error != null) {\n'
    '                cookieApiExperiment.noteApiError(host, error)\n'
    '            } else {\n'
    '                cookieApiExperiment.noteProbe(\n'
    '                    host,\n'
    '                    stores.map { CookieApiExperiment.StoreResult(it.first, it.second) }\n'
    '                )\n'
    '            }\n'
    '            refreshCookiesApiState()\n'
    '            if (first) {\n'
    '                val summary = when {\n'
    '                    !apiPresent -> "browser.cookies undefined"\n'
    '                    error != null -> "error: $error"\n'
    '                    else -> stores.joinToString("; ") { (id, names) ->\n'
    '                        "$id: ${names.size} name(s)"\n'
    '                    }\n'
    '                }\n'
    '                statusPanel.status("cookies API: probed $host · $summary")\n'
    '            }\n'
    '        }\n'
    '    }\n'
    '\n'
    '    /** Alpha.63: 10 s bounded wait for a cookie name,\n'
    '     *  armed once per page load while the verdict is UNKNOWN\n'
    '     *  (a probe report means a page loaded). Unlike the\n'
    '     *  MAIN-world wait it re-arms: a timeout with zero\n'
    '     *  probe runs leaves the verdict UNKNOWN (no evidence\n'
    '     *  either way), so the next load tries again. */\n'
    '    private var cookiesApiTimeoutArmed = false\n'
    '\n'
    '    override fun noteCookiesApiPageLoad() {\n'
    '        if (!CookieApiExperiment.isEnabled(store)) return\n'
    '        if (cookieApiExperiment.verdict != CookieApiExperiment.Verdict.UNKNOWN ||\n'
    '            cookiesApiTimeoutArmed\n'
    '        ) return\n'
    '        cookiesApiTimeoutArmed = true\n'
    '        mainHandler.postDelayed({\n'
    '            cookieApiExperiment.noteTimeout()\n'
    '            cookiesApiTimeoutArmed = false\n'
    '        }, 10_000)\n'
    '    }\n',
    "ge.handler",
)

# ---- Engine.kt: Diagnostics state ----
replace_once(
    ENG,
    '    // alpha.60: "cookie observer: N events · M in scope ·\n'
    '    // flag on/off" — the probe counts, for Diagnostics\n'
    '    val cookieObserverState: State<String>\n',
    '    // alpha.60: "cookie observer: N events · M in scope ·\n'
    '    // flag on/off" — the probe counts, for Diagnostics\n'
    '    val cookieObserverState: State<String>\n'
    '    // alpha.63: "cookies API: off/unknown/accepted/\n'
    '    // rejected · host · store summary" — for Diagnostics\n'
    '    val cookieApiState: State<String>\n',
    "eng.state",
)

# ---- SettingsScreen.kt: import ----
replace_once(
    SET,
    'import net.nspioneer.browser.engine.CookieCaptureExperiment\n',
    'import net.nspioneer.browser.engine.CookieApiExperiment\n'
    'import net.nspioneer.browser.engine.CookieCaptureExperiment\n',
    "set.import",
)

# ---- SettingsScreen.kt: toggle row ----
replace_once(
    SET,
    '                    checked = CookieCaptureExperiment.isEnabled(store),\n'
    '                    onCheckedChange = {\n'
    '                        store.putSetting(CookieCaptureExperiment.flagKey(), it.toString())\n'
    '                    }\n'
    '                )\n'
    '            }\n',
    '                    checked = CookieCaptureExperiment.isEnabled(store),\n'
    '                    onCheckedChange = {\n'
    '                        store.putSetting(CookieCaptureExperiment.flagKey(), it.toString())\n'
    '                    }\n'
    '                )\n'
    '                InsetDivider()\n'
    '                ToggleRow(\n'
    '                    label = "Cookies API experiment",\n'
    '                    supporting = "Ask the browser\'s cookies API for the current page\'s " +\n'
    '                        "cookies and report accepted/rejected in Diagnostics. " +\n'
    '                        "Only cookie NAMES are ever recorded — never values — " +\n'
    '                        "and only for the page\'s own URL. Nothing is wired into " +\n'
    '                        "downloads by this experiment.",\n'
    '                    checked = CookieApiExperiment.isEnabled(store),\n'
    '                    onCheckedChange = {\n'
    '                        store.putSetting(CookieApiExperiment.flagKey(), it.toString())\n'
    '                    }\n'
    '                )\n'
    '            }\n',
    "set.toggle",
)

# ---- BrowserScreen.kt: Diagnostics line ----
replace_once(
    BRW,
    '            Text(\n'
    '                engine.cookieObserverState.value,\n'
    '                style = MaterialTheme.typography.bodyMedium\n'
    '            )\n',
    '            Text(\n'
    '                engine.cookieObserverState.value,\n'
    '                style = MaterialTheme.typography.bodyMedium\n'
    '            )\n'
    '            // alpha.63: the cookies-API experiment verdict —\n'
    '            // off until the flag flips on, unknown until the\n'
    '            // first probe, then accepted with the host and the\n'
    '            // per-store cookie names seen (names only — never\n'
    '            // values), or rejected with the reason.\n'
    '            Text(\n'
    '                engine.cookieApiState.value,\n'
    '                style = MaterialTheme.typography.bodyMedium\n'
    '            )\n',
    "brw.line",
)

# ---- Version bump: 62 -> 63 ----
replace_once(
    BG,
    '        versionCode = 62\n'
    '        versionName = "0.1.0-alpha.62"\n',
    '        versionCode = 63\n'
    '        versionName = "0.1.0-alpha.63"\n',
    "version",
)

print("all alpha.63 c2 wiring patches applied")
