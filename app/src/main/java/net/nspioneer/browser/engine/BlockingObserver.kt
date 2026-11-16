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
         *  blob: URL). Pure, so unit tests pin it down.
         *
         *  alpha.50: segment noise is never media, and the
         *  10 KB size floor applies where a size is known
         *  ([contentLength] — an `onHeadersReceived`
         *  observation; null for URL-only `onBeforeRequest`
         *  observations, which keep today's behaviour).
         *  Must agree with background.js `mediaKindFor` and
         *  MediaLog's copy. */
        fun mediaKindFor(
            url: String,
            mime: String? = null,
            contentLength: Long? = null
        ): String? {
            if (isSegmentNoise(url)) return null
            if (contentLength != null && contentLength < MEDIA_SIZE_FLOOR_BYTES &&
                !isManifest(url, mime)
            ) return null
            // alpha.47: when a response MIME is present (an
            // `onHeadersReceived` observation) the MIME
            // decides — a media MIME reports even for an
            // extension-less URL, a non-media MIME vetoes
            // even a media-looking URL. With no MIME (an
            // `onBeforeRequest` observation) the URL regex
            // decides, exactly as before.
            if (!mime.isNullOrBlank()) return mediaKindForMime(mime)
            if (url.startsWith("blob:")) return null
            if (!STREAM_RE.containsMatchIn(url)) return null
            val lower = url.lowercase()
            return if (AUDIO_RE.containsMatchIn(lower)) "audio" else "video"
        }

        /** Segment noise (alpha.50, gap plan row A-3):
         *  observations that are fragments of a manifest
         *  stream, not media in their own right — each one
         *  became a media-sheet row of its own. A manifest
         *  (.m3u8 / .mpd) is never noise, even if its path
         *  happens to carry a noise-shaped token. Pure, so
         *  unit tests pin it down. Must agree with
         *  background.js `isSegmentNoise` and MediaLog's
         *  copy, and with the probe.js copy. alpha.57 adds
         *  the init-segment tokens; the v3 audit (rec 2)
         *  widens them to _init. / -init. + mp4 or m4s, plus
         *  a bare init.mp4 / init.m4s path segment. */
        private val MANIFEST_URL_RE = Regex(
            """\.(m3u8|mpd)(\?|#|$)""",
            RegexOption.IGNORE_CASE
        )
        private val SEGMENT_NOISE_RE = Regex(
            """\.m4s(\?|#|$)|segment-|fragment|bytes=|[_-]init\.(mp4|m4s)(\?|#|$)|/init\.(mp4|m4s)(\?|#|$)""",
            RegexOption.IGNORE_CASE
        )

        fun isSegmentNoise(url: String): Boolean {
            if (url.isEmpty()) return false
            if (MANIFEST_URL_RE.containsMatchIn(url)) return false
            return SEGMENT_NOISE_RE.containsMatchIn(url)
        }

        /** True for a manifest observation: a manifest URL,
         *  or a manifest MIME. The size floor never applies
         *  to one: HLS / DASH manifests are routinely a few
         *  KB, and flooring them would break the detection
         *  the floor exists to clean up. Pure, so unit
         *  tests pin it down. */
        fun isManifest(url: String, mime: String?): Boolean {
            if (MANIFEST_URL_RE.containsMatchIn(url)) return true
            val type = mime?.substringBefore(";")?.trim()?.lowercase()
                ?: return false
            return type == "application/x-mpegurl" ||
                type == "application/dash+xml"
        }

        /** 10 KB size floor (alpha.50): an observation whose
         *  size is known and below this is not media. */
        const val MEDIA_SIZE_FLOOR_BYTES: Long = 10240L

        /** Alpha.61: the background-script handshake line —
         *  "background: alive · port connected" once the
         *  background script has posted its startup
         *  handshake on the native port, "background: not
         *  seen yet" until then. Pure, so the Diagnostics
         *  text is pinned in tests. */
        fun backgroundStateText(alive: Boolean): String =
            "background: " + if (alive) "alive · port connected" else "not seen yet"

        /** Response-header classification (alpha.47): the MIME
         *  companion to [STREAM_RE]. A response whose
         *  Content-Type matches is media even when its URL has
         *  no media extension (YouTube
         *  `googlevideo.com/videoplayback`, Twitch /
         *  Dailymotion CDN URLs). Parameters after ';' are
         *  stripped before matching; the match is
         *  case-insensitive. `video/mp2t` is covered by the
         *  `video/` prefix. Must agree with background.js
         *  `MEDIA_MIME_RE` and MediaLog's copy. */
        private val MEDIA_MIME_RE = Regex(
            """^(video/|audio/|application/x-mpegurl|application/dash\+xml)""",
            RegexOption.IGNORE_CASE
        )

        /** "video" / "audio" for a response MIME, null when the
         *  MIME is not media. Pure, so unit tests pin it down. */
        fun mediaKindForMime(mime: String?): String? {
            if (mime.isNullOrBlank()) return null
            val type = mime.substringBefore(";").trim().lowercase()
            if (type.isEmpty()) return null
            if (!MEDIA_MIME_RE.containsMatchIn(type)) return null
            return if (type.startsWith("audio/")) "audio" else "video"
        }
    }

    /** The background script's native port, if it has connected.
     *  `rules-update` is pushed on it whenever the compiled set
     *  changes (list refresh / reload / user-rule / per-site
     *  change). Until it connects, the background script gets its
     *  rules from the `rules-request` reply instead. */
    private var backgroundPort: WebExtension.Port? = null

    /** Response-header observation state (alpha.47): whether
     *  the background script registered `onHeadersReceived`
     *  (null = not reported yet), and how many observations
     *  matched by MIME. Read by the engine for Diagnostics.
     *  The count is exact: both `media-net` sinks (the live
     *  Worker C routing via `noteNetMedia`, and [onMediaNet])
     *  funnel through [noteMimeHit]. */
    var headersReceivedOn: Boolean? = null
        private set
    var mimeHits: Int = 0
        private set

    /** Fired when the on/off state arrives or a MIME hit
     *  lands, so the engine can refresh its Diagnostics
     *  state text (same shape as
     *  `FilterEngine.onListsChanged`). */
    var onHeadersStateChanged: (() -> Unit)? = null

    /** Count one MIME-classified media observation. */
    fun noteMimeHit() {
        mimeHits++
        onHeadersStateChanged?.invoke()
    }

    /** media-net sightings (alpha.50): every media-classified
     *  `webRequest` observation the live sink counted, and how
     *  many of them were manifests. Read by the engine for
     *  Diagnostics. The count is exact: both `media-net`
     *  sinks (the live Worker C routing via `noteNetMedia`,
     *  and [onMediaNet]) funnel through
     *  [noteMediaNetSighting], exactly like [noteMimeHit].
     *  Sightings are observation events, not distinct rows —
     *  a manifest re-fetched on seek counts again. */
    var mediaNetSightings: Int = 0
        private set
    var mediaNetManifests: Int = 0
        private set

    /** Fired when a media-net sighting lands, so the engine
     *  can refresh its Diagnostics state text (same shape as
     *  [onHeadersStateChanged]). */
    var onMediaNetStateChanged: (() -> Unit)? = null

    /** Count one media-classified media-net observation. */
    fun noteMediaNetSighting(isManifest: Boolean) {
        mediaNetSightings++
        if (isManifest) mediaNetManifests++
        onMediaNetStateChanged?.invoke()
    }

    /** "media-net sightings: N (M manifests)". */
    fun mediaNetStateText(): String =
        "media-net sightings: $mediaNetSightings ($mediaNetManifests manifests)"

    /** "webRequest onHeadersReceived: on/off · N MIME hits"
     *  ("unknown" until the background script reports). */
    fun headersReceivedStateText(): String {
        val state = when (headersReceivedOn) {
            true -> "on"
            false -> "off"
            null -> "unknown"
        }
        return "webRequest onHeadersReceived: $state · $mimeHits MIME hits"
    }

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
     * fire-and-forget observations (`media-net`, `blocked`,
     * `headers-state`) and for types this observer does not
     * own (`video-popup-tap`, `video-popup-longpress` —
     * Worker C; anything else) so the delegate chain can
     * continue.
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
            // alpha.47: the background script's `headers-state`
            // report — whether it registered
            // `onHeadersReceived`.
            "headers-state" -> {
                onHeadersState(message.optBoolean("on", false))
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
        val mime = message.optString("mime").takeIf { it.isNotEmpty() }
        // alpha.50: the size floor reads the response
        // Content-Length when the observation carries one
        // (an `onHeadersReceived` report); absent or
        // unparseable means no size is known, no floor.
        val contentLength = message.optString("contentLength").toLongOrNull()
        val kind = mediaKindFor(url, mime, contentLength) ?: return
        // alpha.47: a MIME-classified observation counts a
        // MIME hit for Diagnostics (kind != null with a MIME
        // present means the MIME was media).
        if (mime != null) noteMimeHit()
        // alpha.50: count the sighting (and the manifest)
        // for Diagnostics, before attribution — like the
        // MIME hit above.
        noteMediaNetSighting(isManifest(url, mime))
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

    /** The background script's `headers-state` report
     *  (alpha.47): whether it registered
     *  `onHeadersReceived`. A status line marks the arrival
     *  (Copy status shows it); the Diagnostics state text
     *  carries the live value. */
    private fun onHeadersState(on: Boolean) {
        headersReceivedOn = on
        host.noteStatus(
            "webRequest onHeadersReceived: ${if (on) "on" else "off"}"
        )
        onHeadersStateChanged?.invoke()
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
            // Alpha.59: the cookie-capture experiment flag
            // rides top-level with the flags; the background
            // script reads it fresh on every push.
            put("cookieCapture", host.cookieCaptureEnabled())
            // Alpha.63: the cookies-API experiment flag
            // rides top-level with the flags; the background
            // script reads it fresh on every push.
            put("cookiesApi", host.cookiesApiEnabled())
            put("rules", rules)
        }
    }

    /** Push the current compiled set to the background script's
     *  port. Returns false when no background port is open (the
     *  background script then gets its rules from the port
     *  reply to its next `rules-request` instead — alpha.61,
     *  the reply goes back on the port, not on a one-shot
     *  promise). */
    fun pushRulesUpdate(): Boolean {
        val port = backgroundPort ?: return false
        return runCatching {
            port.postMessage(rulesUpdateJson())
            true
        }.onFailure {
            host.noteStatus("rules-update: push failed: ${it.message}")
        }.getOrDefault(false)
    }

    /** Post one message to the background script's port.
     *  Alpha.64: the cookie requester sends
     *  `cookies-request` on this same port. Returns false
     *  when no background port is open — the caller treats
     *  that as "no channel" and proceeds without cookies.
     *  Never throws. */
    fun sendToBackground(message: JSONObject): Boolean {
        val port = backgroundPort ?: return false
        return runCatching {
            port.postMessage(message)
            true
        }.getOrDefault(false)
    }

    /** The background script connected its native port
     *  (`sender.session == null`). Hand it here — NOT through
     *  the tab-port registration, which disconnects session-less
     *  ports. Pushes the current rule set at once. */
    fun onBackgroundPortConnected(port: WebExtension.Port) {
        backgroundPort = port
        // Alpha.64: the cookie requester sends on this port
        // from now until it disconnects.
        CookieRequester.registerSender { sendToBackground(it) }
        host.noteStatus("background port: connected")
        pushRulesUpdate()
    }

    fun onBackgroundPortDisconnected(port: WebExtension.Port) {
        if (backgroundPort === port) {
            backgroundPort = null
            // Alpha.64: the port is gone — cookie requests
            // answer empty at once until it reconnects.
            CookieRequester.clearSender()
            host.noteStatus("background port: disconnected")
        }
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
     *  `filterListsState`, design §5): "N lists • N rules •
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
            "${FilterEngine.listUrls.size} lists • ${FilterEngine.ruleCount} rules • updated $whenText"
        }
        else -> "Lists not downloaded yet (built-in rules active)"
    }

    /** Re-download + re-parse the lists (Engine
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

    /** Alpha.59: the cookie-capture experiment flag
     *  (`probeCookieCapture`, default OFF). */
    fun cookieCaptureEnabled(): Boolean = false

    /** Alpha.63: the cookies-API experiment flag
     *  (`probeCookiesApi`, default OFF). */
    fun cookiesApiEnabled(): Boolean = false

    /** Status-panel line (diagnostics only). */
    fun noteStatus(line: String) {}
}
