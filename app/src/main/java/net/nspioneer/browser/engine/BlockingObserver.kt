package net.nspioneer.browser.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.text.DateFormat
import java.util.Date

/**
 * Notscape Pioneer — Phase 3 blocking bridge, native-message side.
 *
 * Owns the app side of the probe's blocking messages (design §7):
 *
 *  - receives `media-net` observations from the background script
 *    (`webRequest` sightings of `.m3u8` / `.mpd` / segment / direct
 *    media URLs) and hands the attributed ones to the engine's media
 *    log ([BlockingHost.onMediaObserved], Worker C's `MediaLog`);
 *  - receives `blocked` reports from the background script (a
 *    Mariner-list match it cancelled) and feeds
 *    `FilterEngine.noteBlockedFor`, which is the per-tab / session
 *    blocked count Settings shows;
 *  - serves `rules-update`: pushes the compiled rule set
 *    ([FilterEngine.compiledRules]) to the background script, on
 *    the background port when one is open and as the reply to the
 *    background script's `rules-request` on startup;
 *  - serves `cosmetic-query`: replies with the page's cosmetic CSS
 *    ([FilterEngine.cosmeticCssParts]) for `blocking.js`.
 *
 * Tab attribution (design §7, API doc §10): the background script
 * sends the extension tab id AND the tab's page host with every
 * observation. The app attributes by page host
 * ([BlockingHost.tabIdForPageHost]) — the same host-match fallback
 * the design names. An observation whose host matches no tab, or
 * matches ambiguously, is DROPPED, never guessed; drops are logged
 * to the status panel so a silent attribution failure is visible.
 * Direct-URL recovery for MSE playback is impossible in principle
 * (no URL exists for an MSE stream); `media-net` carries the
 * manifest / segment URLs the observer saw, which are transient
 * and signed — pass-through only, never re-fetched without the
 * page's cookies.
 *
 * Wiring (Worker B owns the files): the engine implements
 * [BlockingHost], constructs one `BlockingObserver`, and routes
 * probe native messages to [handleNativeMessage] from the probe
 * `MessageDelegate` (chained with Worker C's handler — this
 * function returns null for message types it does not own, so the
 * chain can continue). The background script's native port
 * (`browser.runtime.connectNative("pioneer")`, `sender.session ==
 * null`) must be handed to [onBackgroundPortConnected] — it is NOT
 * a tab port, and the Phase 2 port registration (which disconnects
 * a port with no tab) must not touch it; that is a Worker B
 * integration item, flagged in the Phase 3 report.
 *
 * Message types added beyond design §7 (reported to the
 * coordinator for a §7 amendment):
 *  - `blocked` (background → app): a Mariner-list cancellation
 *    report. §7 names no report message, but §8.1 requires the
 *    background script's cancellations to feed
 *    `noteBlockedFor(tabId, category)` — without it the per-tab
 *    count could never move.
 *  - `rules-request` (background → app): the background script
 *    asks for the initial rule set on startup; the reply IS the
 *    `rules-update` payload. The app cannot otherwise know when
 *    the background script started.
 *  - `rules-update` payload gains `disabledHosts` (per-site
 *    exceptions) and `userNet` (the user's own net rules) beside
 *    the five keys §7 names, plus `trackerHosts` (the tracker
 *    subset of `hosts`, so a host hit attributes CAT_TRACKER
 *    exactly as Kotlin does), `docHosts` (pure `$document` /
 *    `$popup` hosts) and `builtinHosts` (the built-in tracker
 *    set, which lives in Kotlin, not in any list) — all five
 *    are needed for the JS mirror to agree with
 *    [FilterEngine.blockCategory] / [isDocumentBlocked] on the
 *    sample table, including categories. The extensions are
 *    reported to the coordinator for a §7 amendment; the
 *    background script in this phase already reads them.
 */
class BlockingObserver(private val host: BlockingHost) {

    companion object {
        /** Media extensions the `webRequest` observer reports,
         *  same regex as Mariner's `MediaLog.inspect`. `blob:`
         *  URLs are never media here — the probe sees them, the
         *  observer must not report them as playable. */
        private val STREAM_RE = Regex(
            """\.(m3u8|mpd|mp4|webm|mkv|mov|mp3|m4a|aac|ogg|opus|flac|wav|m4v|ts)(\?|#|$)""",
            RegexOption.IGNORE_CASE
        )
        private val AUDIO_RE = Regex(
            """\.(mp3|m4a|aac|ogg|opus|flac|wav)(\?|#|$)""",
            RegexOption.IGNORE_CASE
        )

        /** "video" / "audio" for a media URL, null when the URL
         *  is not reportable media (not a stream extension, or a
         *  blob: URL). Pure, so unit tests pin it down. */
        fun mediaKindFor(url: String): String? {
            if (url.startsWith("blob:")) return null
            if (!STREAM_RE.containsMatchIn(url)) return null
            val lower = url.lowercase()
            return if (AUDIO_RE.containsMatchIn(lower)) "audio" else "video"
        }
    }

    /** The background script's native port, if it has connected.
     *  `rules-update` is pushed on it whenever the compiled set
     *  changes (list refresh / reload / user-rule / per-site
     *  change). Until it connects, the background script gets its
     *  rules from the `rules-request` reply instead. */
    private var backgroundPort: WebExtension.Port? = null

    init {
        // Whenever the compiled set lands (disk load, refresh,
        // reload), push it to the background script if its port
        // is open. The background script replaces its in-memory
        // set on receipt.
        FilterEngine.onListsChanged = { pushRulesUpdate() }
    }

    // ------------------------------------------------------------------
    // Native-message dispatch (called from the probe MessageDelegate)
    // ------------------------------------------------------------------

    /**
     * Handle one native message from the probe. Returns a
     * [GeckoResult] only for messages that carry a reply
     * (`cosmetic-query`, `rules-request`); returns null for
     * fire-and-forget observations (`media-net`, `blocked`) and
     * for types this observer does not own (`video-popup-tap`,
     * `video-popup-longpress` — Worker C; anything else) so the
     * delegate chain can continue.
     */
    fun handleNativeMessage(
        message: JSONObject,
        // The sender session is intentionally unused: background-
        // script messages carry sender.session == null, and
        // content-script messages are attributed by page host
        // (see attributeTab), never by session. The parameter
        // stays so the probe MessageDelegate can pass its
        // sender through unchanged.
        @Suppress("UNUSED_PARAMETER") senderSession: GeckoSession?
    ): GeckoResult<Any>? {
        return when (message.optString("type")) {
            "media-net" -> {
                onMediaNet(message)
                null
            }
            "blocked" -> {
                onBlockedReport(message)
                null
            }
            "cosmetic-query" -> {
                val hostName = message.optString("host")
                GeckoResult.fromValue(cosmeticReply(hostName) as Any)
            }
            "rules-request" -> {
                GeckoResult.fromValue(rulesUpdateJson() as Any)
            }
            else -> null
        }
    }

    private fun onMediaNet(message: JSONObject) {
        val url = message.optString("url")
        val kind = mediaKindFor(url) ?: return
        val pageHost = message.optString("pageHost")
        val tabId = attributeTab(pageHost, message.optInt("tabId", -1))
        if (tabId < 0) {
            host.noteStatus(
                "media-net: dropped unattributable observation " +
                    "($kind $url, pageHost=$pageHost)"
            )
            return
        }
        host.onMediaObserved(tabId, url, kind)
    }

    private fun onBlockedReport(message: JSONObject) {
        val url = message.optString("url")
        val category = message.optInt("category", FilterEngine.CAT_ADS)
        val pageHost = message.optString("pageHost")
        val tabId = attributeTab(pageHost, message.optInt("tabId", -1))
        if (tabId < 0) {
            host.noteStatus(
                "blocked: dropped unattributable report " +
                    "($url, pageHost=$pageHost)"
            )
            return
        }
        FilterEngine.noteBlockedFor(tabId, category)
    }

    /**
     * Attribute an observation to an engine tab id. The extension
     * tab id the background script sends cannot be mapped by the
     * app directly, so attribution is by page host (the design's
     * host-match rule): exactly one tab on that host attributes;
     * zero or ambiguous matches drop the observation. The
     * extension tab id is logged, not trusted, because nothing in
     * the app can verify it belongs to the tab it names.
     */
    private fun attributeTab(pageHost: String, @Suppress("UNUSED_PARAMETER") extTabId: Int): Int {
        if (pageHost.isEmpty()) return -1
        return host.tabIdForPageHost(pageHost.lowercase())
    }

    // ------------------------------------------------------------------
    // rules-update (app → background)
    // ------------------------------------------------------------------

    /** The `rules-update` payload: `{type, rules:{…}}`. */
    fun rulesUpdateJson(): JSONObject {
        val compiled = FilterEngine.compiledRules()
        val rules = JSONObject().apply {
            put("hosts", JSONArray(compiled.hosts))
            put("trackerHosts", JSONArray(compiled.trackerHosts))
            put("docHosts", JSONArray(compiled.docHosts))
            put("scoped", JSONArray().apply {
                for (r in compiled.scoped) {
                    put(JSONObject().apply {
                        put("host", r.host)
                        if (r.pathPrefix != null) put("pathPrefix", r.pathPrefix)
                        if (r.domains != null) put("domains", JSONArray(r.domains))
                        if (r.notDomains != null) put("notDomains", JSONArray(r.notDomains))
                        put("exception", r.exception)
                        if (r.thirdParty != null) put("thirdParty", r.thirdParty)
                        put("important", r.important)
                        put("category", r.category)
                        put("document", r.document)
                    })
                }
            })
            put("pathKeywords", JSONArray(compiled.pathKeywords))
            put("exceptions", JSONArray(compiled.exceptions))
            put("neverBlock", JSONArray(compiled.neverBlock))
            put("builtinHosts", JSONArray(compiled.builtinHosts))
            put("disabledHosts", JSONArray(compiled.disabledHosts))
            // Batch 4: hosts whose SiteSetting.blockImages is
            // on — the background script cancels their images.
            put("blockImagesHosts", JSONArray(host.blockImagesHosts()))
            put("userNet", JSONArray().apply {
                for (r in compiled.userNet) {
                    put(JSONObject().apply {
                        put("kind", r.kind)
                        put("value", r.value)
                    })
                }
            })
        }
        return JSONObject().apply {
            put("type", "rules-update")
            // The list toggles ride with the rules: the
            // background script blocks only what the user has
            // on (blockAds gates user rules + pathKeywords,
            // blockTrackers gates the builtin set — same flags
            // Kotlin applies per request). Read fresh on every
            // push; the engine re-pushes when a toggle changes.
            put("blockAds", host.blockAdsEnabled())
            put("blockTrackers", host.blockTrackersEnabled())
            // Batch 4: the referer policy ("", "origin" or
            // "never") rides top-level with the flags; the
            // per-site image-blocking hosts ride inside the
            // rules, beside disabledHosts.
            put("refererPolicy", host.refererPolicy())
            put("rules", rules)
        }
    }

    /** Push the current compiled set to the background script's
     *  port. Returns false when no background port is open (the
     *  background script then gets its rules from the
     *  `rules-request` reply on its next startup instead). */
    fun pushRulesUpdate(): Boolean {
        val port = backgroundPort ?: return false
        return runCatching {
            port.postMessage(rulesUpdateJson())
            true
        }.onFailure {
            host.noteStatus("rules-update: push failed: ${it.message}")
        }.getOrDefault(false)
    }

    /** The background script connected its native port
     *  (`sender.session == null`). Hand it here — NOT through
     *  the tab-port registration, which disconnects session-less
     *  ports. Pushes the current rule set at once. */
    fun onBackgroundPortConnected(port: WebExtension.Port) {
        backgroundPort = port
        pushRulesUpdate()
    }

    fun onBackgroundPortDisconnected(port: WebExtension.Port) {
        if (backgroundPort === port) backgroundPort = null
    }

    /** Re-push the rule set after a list toggle changed
     *  (`blockAds` / `blockTrackers`). The flags are read fresh
     *  in [rulesUpdateJson]; this just re-sends. Called by the
     *  engine when the settings change. */
    fun syncBlockingFlags() {
        pushRulesUpdate()
    }

    /** Re-read the per-site exception hosts from the engine and
     *  push the updated set. Called when a site's
     *  `disableBlocking` flag changes (Engine.setBlockingEnabled,
     *  Worker B). */
    fun updateDisabledHosts() {
        FilterEngine.updateDisabledHosts(host.disabledHosts())
        pushRulesUpdate()
    }

    /** Re-read the user's own rules from the store and push the
     *  updated set. Called when `userBlockNet` /
     *  `userBlockCosmetic` change. */
    fun syncUserRules(
        net: List<UserBlockRules.NetRule>,
        cosmetic: List<UserBlockRules.CosmeticRule>
    ) {
        FilterEngine.userNetRules = net
        FilterEngine.userCosmeticRules = cosmetic
        pushRulesUpdate()
    }

    // ------------------------------------------------------------------
    // cosmetic-query (content → app)
    // ------------------------------------------------------------------

    /** Reply to a `cosmetic-query`: `{type:"cosmetic", css,
     *  preciseCss, genericCss, generichide, hideOff}`. `css` is
     *  the combined sheet (Mariner's `cosmeticCss`); the split
     *  fields let `blocking.js` apply `$generichide` itself by
     *  walking `location.hostname` up parent domains against
     *  `hideOff` — if the walk matches, the generic half is
     *  skipped and only the precise half is injected. The app
     *  already excludes generic selectors from `genericCss` on
     *  `$generichide` hosts, so the two layers agree; the
     *  content-script walk is the enforcement point the design
     *  names. ≤ 200,000 chars, same budget as Mariner. */
    fun cosmeticReply(pageHost: String): JSONObject {
        val parts = FilterEngine.cosmeticCssParts(pageHost)
        return JSONObject().apply {
            put("type", "cosmetic")
            put("css", parts.preciseCss + parts.genericCss)
            put("preciseCss", parts.preciseCss)
            put("genericCss", parts.genericCss)
            put("generichide", parts.generichide)
            put("hideOff", JSONArray().apply {
                // The full $generichide host set is small; the
                // content script walks it against its own
                // hostname. Sent on every query — the set is
                // rebuilt only when the lists reload.
                for (h in FilterEngine.genericHideOffHostsSnapshot()) put(h)
            })
        }
    }

    // ------------------------------------------------------------------
    // Counts + Settings text (read by the engine / Settings UI)
    // ------------------------------------------------------------------

    /** Per-tab blocked count; resets on navigation (the engine
     *  calls [resetTab] from its navigation delegate). */
    fun blockedCountForTab(tabId: Int): Long = FilterEngine.blockedCountFor(tabId)

    fun resetTab(tabId: Int) = FilterEngine.resetTabCount(tabId)

    /** Exact session total (unthrottled publish value aside). */
    fun sessionBlockedCount(): Long = FilterEngine.sessionBlockedCount()

    fun blockedHistory(): Map<String, List<Long>> = FilterEngine.blockedHistory()

    /** ETP telemetry only (API doc §6): a `ContentBlocking`
     *  `onContentBlocked` event for [tabId]. [category] is the
     *  CAT_* the engine mapped the BlockEvent to. ETP itself is
     *  not the blocker — uBlock + Mariner's lists are — so this
     *  feeds the same counters, never a separate one. */
    fun noteContentBlocked(tabId: Int, category: Int) {
        FilterEngine.noteBlockedFor(tabId, category)
    }

    /** Settings status line for the filter lists (Engine
     *  `filterListsState`, design §5): "7 lists • N rules •
     *  updated …" | "updating…" | "Lists not downloaded yet
     *  (built-in rules active)". There is no "failed" state to
     *  report: a refresh that fails leaves the previous good
     *  lists in place, exactly as Mariner does. */
    fun filterListsStateText(): String = when {
        FilterEngine.updating -> "updating…"
        FilterEngine.ruleCount > 0 -> {
            val whenText = if (FilterEngine.updatedAt > 0) {
                DateFormat.getDateInstance().format(Date(FilterEngine.updatedAt))
            } else "—"
            "7 lists • ${FilterEngine.ruleCount} rules • updated $whenText"
        }
        else -> "Lists not downloaded yet (built-in rules active)"
    }

    /** Re-download + re-parse the 7 lists (Engine
     *  `refreshFilterLists`, design §5). The push to the
     *  background script happens from `FilterEngine.onListsChanged`
     *  when the new set lands. */
    fun refreshFilterLists(context: Context) {
        FilterEngine.refreshAsync(context)
    }
}

/**
 * What the blocking bridge needs from the engine (Worker B
 * implements this in `GeckoEngine` / `Engine`):
 * which tab a page host belongs to, the tab's media sink, the
 * per-site exception state, and the list toggles. Default
 * bodies keep the observer usable in tests with a minimal stub.
 */
interface BlockingHost {
    /** Engine tab id for the tab currently on [pageHost],
     *  or -1 when no tab — or more than one tab — is on it.
     *  Ambiguity drops the observation; the host must return -1
     *  when two tabs share the host. */
    fun tabIdForPageHost(pageHost: String): Int

    /** A media URL the probe observed on [tabId], for the
     *  engine's media log (Worker C's `MediaLog`). */
    fun onMediaObserved(tabId: Int, url: String, kind: String)

    /** Every host whose `SiteSetting.disableBlocking` is on. */
    fun disabledHosts(): Set<String>

    /** Mariner-list ad blocking on (`blockAds`, default true). */
    fun blockAdsEnabled(): Boolean = true

    /** Mariner-list tracker blocking on (`blockTrackers`,
     *  default true). */
    fun blockTrackersEnabled(): Boolean = true

    /** Batch 4: every host whose `SiteSetting.blockImages`
     *  is on. */
    fun blockImagesHosts(): Set<String> = emptySet()

    /** Batch 4: the stored referer policy ("", "origin" or
     *  "never"). */
    fun refererPolicy(): String = ""

    /** Status-panel line (diagnostics only). */
    fun noteStatus(line: String) {}
}
