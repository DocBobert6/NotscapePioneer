package net.nspioneer.browser.engine

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Notscape Pioneer — Phase 3 blocking engine.
 *
 * Pure-Kotlin port of Mariner's `FilterEngine` + `UserBlockRules`
 * (Mariner `browser/WebTools.kt`, `browser/UserBlockRules.kt`).
 * Behaviour parity is the goal; the matcher decision order, the
 * list set, the storage layout and the compiled-cache format are
 * carried over verbatim. Deliberate deviations from Mariner, all
 * forced by Pioneer's constraints:
 *
 *  - URLs are plain strings, not `android.net.Uri`: `Uri` is an
 *    Android stub under local JVM unit tests, and this object must
 *    stay unit-testable. [blockCategory] / [isBlocked] /
 *    [isDocumentBlocked] split scheme/host/path themselves
 *    ([UrlParts]); the split is case-insensitive exactly where
 *    Mariner lowercases.
 *  - No Compose state: Mariner publishes `ruleCount`,
 *    `blockedCount` etc. as `mutableStateOf`. Pioneer keeps plain
 *    `@Volatile` vars + `AtomicLong` counters with the same 1s
 *    publish throttle; the Settings UI reads them through the
 *    `…Snapshot()` accessors. Exact counts are always available
 *    via [sessionBlockedCount] / [blockedCountFor].
 *  - No Guava: Mariner's `Hosts.sameSite` uses Guava's full
 *    public-suffix data with a heuristic fallback. Pioneer has no
 *    Guava dependency, so [sameSite] / [registrableDomain] use the
 *    fallback heuristic only (the same multi-label suffix list
 *    Mariner carries). Sibling subdomains of a `co.uk`-style site
 *    still count as first-party; exotic suffixes may differ from
 *    Mariner — recorded as a known deviation, not hidden.
 *  - `documentStartScript` is still built (Mariner builds it for
 *    document-start injection) but Pioneer never registers it:
 *    cosmetics are injected by the probe content script
 *    (`blocking.js`) at `document_idle` via `cosmetic-query`.
 *    Ads may flash before then — design §10 gap 2.
 *
 * Pioneer additions (not in Mariner):
 *  - [disabledHosts]: per-site exceptions (`SiteSetting`
 *    `disableBlocking`, JSON key `"nb"`). Checked by the
 *    enforcement points ([BlockingObserver] and the probe
 *    background script), never inside [blockCategory] — same
 *    layering as Mariner, which checks `disableBlocking` at its
 *    call sites.
 *  - [compiledRules]: the JSON-friendly compiled rule set the app
 *    pushes to the probe background script via the `rules-update`
 *    native message (design §7).
 *  - [cosmeticCssParts]: the page sheet split into domain/user
 *    selectors and generic selectors, so `blocking.js` can apply
 *    the `$generichide` parent-domain walk itself (design §8.1).
 *  - [loadForTests] / [resetForTests]: test-only state control.
 *    The engine is a process-wide singleton; tests reset it.
 *
 * Enforcement lives in the probe background script
 * (`background.js`, a JS mirror of [blockCategory] /
 * [isDocumentBlocked]); this object is the source of truth and
 * the two matchers must agree on the fixed sample-URL table
 * (scorecard row A). `$document` / `$popup` enforcement
 * (Beta.63 #64) is at request level here: [isDocumentBlocked]
 * covers pure `||host^$document/$popup` hosts ([docHosts]) and
 * scoped document rules, and `background.js` cancels main-frame
 * loads it matches — the STRICT POPUP BLOCKER list side.
 * The gesture-aware popunder approximation itself (which
 * navigation was gesture-less) is Worker B's, from
 * `onLocationChange` + the probe's `webRequest` observation
 * (design §8.9); it calls [isDocumentBlocked] / [isDocumentHost].
 */
object FilterEngine {

    // ------------------------------------------------------------------
    // Built-in hosts + path keywords (Mariner, verbatim)
    // ------------------------------------------------------------------

    private val builtinHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "analytics.twitter.com", "ads-twitter.com", "connect.facebook.net",
        "facebook.net", "adservice.google.com", "pagead2.googlesyndication.com",
        "tpc.googlesyndication.com", "partner.googleadservices.com",
        "amazon-adsystem.com", "aax.amazon-adsystem.com", "ads.yahoo.com",
        "analytics.yahoo.com", "advertising.com", "adnxs.com", "adsrvr.org",
        "rubiconproject.com", "pubmatic.com", "openx.net", "criteo.com",
        "criteo.net", "taboola.com", "outbrain.com", "scorecardresearch.com",
        "quantserve.com", "moatads.com", "zedo.com", "smartadserver.com",
        "lijit.com", "sovrn.com", "sharethrough.com", "triplelift.com",
        "indexww.com", "casalemedia.com", "contextweb.com", "spotxchange.com",
        "mathtag.com", "bluekai.com", "demdex.net", "krxd.net", "exelator.com",
        "turn.com", "adform.net", "bidswitch.net", "w55c.net", "rlcdn.com",
        "pippio.com", "eyeota.net", "tapad.com", "drawbridge.com",
        "crossdevice.com", "adsymptotic.com", "nr-data.net", "newrelic.com",
        "hotjar.com", "fullstory.com", "mouseflow.com", "crazyegg.com",
        "mixpanel.com", "segment.com", "segment.io", "amplitude.com",
        "branch.io", "adjust.com", "appsflyer.com", "kochava.com",
        "leanplum.com", "clevertap.com", "onesignal.com", "pushwoosh.com",
        "bat.bing.com", "clarity.ms", "mc.yandex.ru",
        "adskeeper.com", "mgid.com", "revcontent.com", "contentad.net",
        "zergnet.com", "newsmaxfeednetwork.com", "disqusads.com",
        // Ad networks a device ad-block test showed as unblocked
        // (2026-10-21): popunder networks used by streaming sites,
        // mobile ad SDKs, and the big platforms' secondary ad domains.
        "propellerads.com", "propellerclick.com", "onclicks.net",
        "popads.net", "popcash.net", "popmyads.com", "clickadu.com",
        "trafficjunky.net", "exoclick.com", "juicyads.com",
        "applovin.com", "unityads.unity3d.com", "adroll.com",
        "advertising.yahoo.com", "ysm.yahoo.com", "adtech.yahooinc.com",
        "bingads.microsoft.com", "ads.microsoft.com",
        "ads.linkedin.com", "px.ads.linkedin.com", "snap.licdn.com",
        "fls-na.amazon.com", "fingerprintjs.com", "fpjs.io",
        "siftscience.com", "permutive.com", "onetag-sys.com",
        "id5-sync.com", "doubleverify.com",
        "coinimp.com", "webminepool.com", "minero.cc", "mineralt.io",
        "monerominer.rocks", "ads.vk.com", "ad.mail.ru",
        "pixel.quora.com", "qevents.quora.com", "px.arvcs.tumblr.com",
        "widgets.pinterest.com", "advertising.apple.com", "xp.apple.com",
        "metrics.mzstatic.com"
    )

    private val pathKeywords = listOf(
        "/ads/", "/adservice", "/pagead/", "/gtag/js", "google-analytics",
        "/analytics.js", "/beacon", "/pixel.gif", "prebid", "adsbygoogle"
    )

    /** The 7 lists, in this order — the indices matter, because a
     *  new list is appended last so cached indices never shift. */
    val listUrls = listOf(
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt",
        // Hosts-format blocklist (adware/malware domains).
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        // AdGuard Tracking Protection — the trackers EasyPrivacy misses.
        "https://filters.adtidy.org/extension/ublock/filters/3.txt",
        // AdGuard Mobile Ads — in-app/mobile-web ad networks.
        "https://filters.adtidy.org/extension/ublock/filters/11.txt",
        // uBlock Origin's own filters.
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
        // AdGuard Base — appended LAST so cached indices never shift.
        "https://filters.adtidy.org/extension/ublock/filters/2.txt"
    )

    val listNames = listOf(
        "EasyList", "EasyPrivacy", "StevenBlack hosts",
        "AdGuard Tracking Protection", "AdGuard Mobile Ads",
        "uBlock Origin filters", "AdGuard Base"
    )

    // ------------------------------------------------------------------
    // Compiled rule state
    // ------------------------------------------------------------------

    @Volatile private var listHosts: Set<String> = emptySet()
    /** Beta.63 #26: hosts named by a tracker list (subset of
     *  listHosts, kept separately so a block can be attributed). */
    @Volatile private var trackerHosts: Set<String> = emptySet()
    @Volatile private var exceptionHosts: Set<String> = emptySet()
    @Volatile private var scopedRules: Map<String, List<ScopedRule>> = emptyMap()
    @Volatile private var genericSelectors: List<String> = emptyList()
    @Volatile private var domainSelectors: Map<String, List<String>> = emptyMap()
    @Volatile private var docHosts: Set<String> = emptySet()
    @Volatile private var cosmeticExcGeneric: Set<String> = emptySet()
    @Volatile private var cosmeticExcDomain: Map<String, List<String>> = emptyMap()
    @Volatile private var genericHideOffHosts: Set<String> = emptySet()

    /** The document-start cosmetic script, rebuilt whenever filter
     *  data lands; "" until the first lists load. Pioneer never
     *  registers it (see the file header); it is kept so the
     *  compiled state stays identical to Mariner's. */
    @Volatile var documentStartScript: String = ""
        private set
    @Volatile private var cssCache: Pair<String, String>? = null

    /** The user's own picker rules: set from the shell whenever the
     *  stored rules change, read on the request thread. Network
     *  rules beat the compiled lists; cosmetic rules join the sheet
     *  for their exact host. Setting either busts the cosmetic cache. */
    @Volatile var userNetRules: List<UserBlockRules.NetRule> = emptyList()

    @Volatile var disabledListIndices: Set<Int> = emptySet()
    @Volatile var customListUrl: String = ""
    @Volatile var customAbpText: String = ""

    /** Called on the loading thread whenever a new compiled set
     *  lands ([applyData]) — disk load, refresh, or reload. The
     *  [BlockingObserver] sets this to push `rules-update` to the
     *  probe background script. Null by default (unit tests). */
    @Volatile var onListsChanged: (() -> Unit)? = null

    /** Snapshot of the `$generichide` host set, for the
     *  `cosmetic-query` reply (`blocking.js` walks it against
     *  its own hostname). */
    fun genericHideOffHostsSnapshot(): Set<String> = genericHideOffHosts

    /** Per-site exceptions, by page host: hosts whose
     *  `SiteSetting.disableBlocking` is on. Set by the engine from
     *  `PioneerStore` whenever site settings change; the probe
     *  background script receives the same set in `rules-update`
     *  and skips those page hosts entirely. Never consulted inside
     *  [blockCategory] — the enforcement points check it first,
     *  exactly as Mariner checks `disableBlocking` at its call
     *  sites. */
    @Volatile var disabledHosts: Set<String> = emptySet()

    fun updateDisabledHosts(hosts: Set<String>) {
        disabledHosts = hosts.map { it.lowercase().trimEnd('.') }.toSet()
    }

    /** True when blocking is switched off for pages on [pageHost]
     *  (the host itself or any parent domain in [disabledHosts]). */
    fun isDisabledForPage(pageHost: String): Boolean {
        val h = pageHost.lowercase().trimEnd('.')
        if (h.isEmpty()) return false
        return matches(disabledHosts, h)
    }

    /** Beta.66 #60: parse the saved disabled-list indices
     *  ("1,3" -> {1, 3}); unparseable entries are dropped.
     *  Pure, so unit tests pin it down without a store. */
    fun parseDisabledFilterLists(raw: String): Set<Int> =
        raw.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()

    fun setListConfig(disabled: Set<Int>, customUrl: String, abpText: String) {
        disabledListIndices = disabled
        customListUrl = customUrl
        customAbpText = abpText
    }

    /** Line numbers (1-based) that are not valid ABP rules. */
    fun validateAbpRules(text: String): List<Int> {
        val bad = mutableListOf<Int>()
        text.lines().forEachIndexed { idx, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("!")) return@forEachIndexed
            val probe = FilterData()
            var grew = false
            runCatching {
                parseInto(0, line, probe)
                grew = probe.hosts.isNotEmpty() || probe.exceptions.isNotEmpty() ||
                    probe.scoped.isNotEmpty() || probe.generic.isNotEmpty() ||
                    probe.perDomain.isNotEmpty() || probe.docHosts.isNotEmpty() ||
                    probe.excGeneric.isNotEmpty() || probe.excDomain.isNotEmpty() ||
                    probe.hideOff.isNotEmpty()
            }
            if (!grew) bad.add(idx + 1)
        }
        return bad
    }

    /** Re-parse from disk, dropping the compiled cache
     *  (it was built under the previous list config). */
    fun reloadLists(context: Context) {
        val appCtx = context.applicationContext
        Thread {
            runCatching {
                File(dir(appCtx), "compiled.bin").delete()
            }
            loadFromDisk(appCtx)
        }.start()
    }

    @Volatile var userCosmeticRules: List<UserBlockRules.CosmeticRule> =
        emptyList()
        set(value) {
            field = value
            cssCache = null
        }

    /** Beta.63 #26: which kind of rule stopped a request —
     *  ads (ad lists + ad URL patterns), trackers (tracker lists
     *  + the builtin tracker hosts), other (the user's own rules). */
    const val CAT_ADS = 0
    const val CAT_TRACKER = 1
    const val CAT_OTHER = 2

    /**
     * An ABP rule that only applies under conditions: a path prefix on
     * the request host, and/or only on pages whose host is (or is not)
     * in a $domain= list. This is the honest middle ground between
     * ignoring scoped rules entirely and the original bug — flattening
     * them into global host blocks, which took out YouTube (the
     * googlevideo.com rule is restricted to two named sites) and the
     * JW Player hosts (restricted to a few publishers).
     */
    class ScopedRule(
        val pathPrefix: String?,
        val domains: Set<String>?,
        val notDomains: Set<String>?,
        val exception: Boolean,
        /** null = any party; true = only third-party requests; false = only first-party. */
        val thirdParty: Boolean? = null,
        /** $important: beats exception (@@) rules. */
        val important: Boolean = false,
        /** Beta.63 #26: which category a block by this rule counts as. */
        val category: Int = CAT_ADS,
        /** Beta.63 #64: the rule applies to document/popup loads. */
        val document: Boolean = false
    )

    private const val COMPILED_MAGIC = 0x4E464331
    private const val COMPILED_VERSION = 3

    @Volatile var ruleCount: Int = 0
        private set
    @Volatile var updatedAt: Long = 0L
        private set
    @Volatile var updating: Boolean = false
        private set

    /** Requests blocked this session - shown in Settings so the
     *  blocking can be seen firing, not taken on faith. */
    private val blockedCountAtomic =
        java.util.concurrent.atomic.AtomicLong(0)

    // Audit P-m10: the published value catches up at most once a
    // second, so a Settings reader is not recomposed per request.
    // The count stays exact; the published value lags by ≤ 1s.
    private val lastBlockedPublish =
        java.util.concurrent.atomic.AtomicLong(0)

    @Volatile var blockedCount: Long = 0L
        private set

    /** Exact session total, unthrottled. */
    fun sessionBlockedCount(): Long = blockedCountAtomic.get()

    fun noteBlocked() {
        val n = blockedCountAtomic.incrementAndGet()
        val now = System.currentTimeMillis()
        val last = lastBlockedPublish.get()
        if (now - last >= 1000 &&
            lastBlockedPublish.compareAndSet(last, now)
        ) {
            blockedCount = n
        }
    }

    // Audit P-m15: per-tab blocked counts, published
    // the same throttled way as the session total.
    private val perTabCounts =
        java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicLong>()
    @Volatile var perTabBlocked: Map<Int, Long> = emptyMap()
        private set

    /** Beta.63 #26: session counts per category, published
     *  on the same 1s throttle as the session total. */
    private val blockedByCategoryAtomic =
        Array(3) { java.util.concurrent.atomic.AtomicLong(0) }

    @Volatile var blockedByCategory: List<Long> = listOf(0, 0, 0)
        private set

    fun noteBlockedFor(tabId: Int, category: Int) {
        noteBlocked()
        blockedByCategoryAtomic[category.coerceIn(0, 2)].incrementAndGet()
        rollHistoryDay()
        todayCounts[category.coerceIn(0, 2)]++
        historyDirty = true
        perTabCounts.computeIfAbsent(tabId) {
            java.util.concurrent.atomic.AtomicLong(0)
        }.incrementAndGet()
        val now = System.currentTimeMillis()
        val last = lastBlockedPublish.get()
        if (now - last >= 1000 &&
            lastBlockedPublish.compareAndSet(last, now)
        ) {
            blockedByCategory = blockedByCategoryAtomic.map { it.get() }
            perTabBlocked = perTabCounts.entries.associate { it.key to it.value.get() }
        }
    }

    /** Audit P-m15: a navigation resets the tab's count. */
    fun resetTabCount(tabId: Int) {
        if (perTabCounts.remove(tabId) != null) {
            perTabBlocked = perTabCounts.entries.associate { it.key to it.value.get() }
        }
    }

    fun blockedCountFor(tabId: Int): Long =
        perTabCounts[tabId]?.get() ?: 0L

    // Beta.63 #26: per-day history. One line per day,
    // "yyyy-MM-dd=ads,trackers,other", newest 30 days,
    // kept in the "blockedHistory" setting. Counts are
    // real FilterEngine counters only.
    private val historyDays = LinkedHashMap<String, LongArray>()
    private val todayCounts = LongArray(3)
    private val flushedToday = LongArray(3)
    private var historyDay = todayKey()
    private var historyDirty = false

    internal fun todayKey(nowMs: Long = System.currentTimeMillis()): String {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = nowMs
        return "%04d-%02d-%02d".format(
            c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun rollHistoryDay() {
        val day = todayKey()
        if (day == historyDay) return
        // Commit yesterday's unflushed counts before
        // the day rolls, so a midnight crossing never
        // drops a day.
        val row = historyDays.getOrPut(historyDay) { LongArray(3) }
        for (i in 0..2) {
            row[i] += todayCounts[i] - flushedToday[i]
        }
        historyDirty = true
        historyDay = day
        todayCounts.fill(0)
        flushedToday.fill(0)
    }

    /** Live view of the history for the UI: persisted days
     *  plus today's not-yet-flushed counts, newest first. */
    fun blockedHistory(): Map<String, List<Long>> {
        rollHistoryDay()
        val merged = LinkedHashMap<String, LongArray>()
        historyDays.forEach { (d, v) -> merged[d] = v.copyOf() }
        val today = merged.getOrPut(historyDay) { LongArray(3) }
        for (i in 0..2) today[i] += todayCounts[i] - flushedToday[i]
        return merged.entries.sortedByDescending { it.key }
            .associate { it.key to it.value.toList() }
    }

    /** Flush today's counts into the persisted history.
     *  Returns the serialized history, or null when
     *  nothing changed since the last flush. */
    fun flushBlockedHistory(): String? {
        rollHistoryDay()
        if (!historyDirty) return null
        val row = historyDays.getOrPut(historyDay) { LongArray(3) }
        for (i in 0..2) {
            row[i] += todayCounts[i] - flushedToday[i]
            flushedToday[i] = todayCounts[i]
        }
        while (historyDays.size > 30) {
            historyDays.remove(historyDays.keys.minOrNull() ?: break)
        }
        historyDirty = false
        return historyDays.entries.sortedByDescending { it.key }
            .joinToString("\n") { (d, v) -> "$d=${v[0]},${v[1]},${v[2]}" }
    }

    fun loadBlockedHistory(raw: String) {
        historyDays.clear()
        raw.lines().forEach { line ->
            val eq = line.indexOf('=')
            if (eq < 0) return@forEach
            val day = line.substring(0, eq).trim()
            if (!day.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) return@forEach
            val parts = line.substring(eq + 1).split(",")
                .mapNotNull { it.trim().toLongOrNull() }
            if (parts.size != 3 || parts.any { it < 0 }) return@forEach
            historyDays[day] = longArrayOf(parts[0], parts[1], parts[2])
        }
        while (historyDays.size > 30) {
            historyDays.remove(historyDays.keys.minOrNull() ?: break)
        }
        historyDirty = false
    }

    /** Resource-type rule options, ABP and AdGuard spellings. */
    private val TYPE_OPTIONS = setOf(
        "script", "image", "stylesheet", "css", "font", "media",
        "object", "object-subrequest", "xmlhttprequest", "xhr",
        "subdocument", "frame", "document", "popup", "websocket",
        "webrtc", "other", "ping", "beacon", "manifest", "empty", "mp4"
    )

    private fun dir(context: Context) = File(context.filesDir, "filters").apply { mkdirs() }

    /** Load cached lists into memory; refresh in the background if stale (>24 hours). */
    fun initAsync(context: Context) {
        val appCtx = context.applicationContext
        Thread {
            loadFromDisk(appCtx)
            val missing = listUrls.indices.any { !File(dir(appCtx), "list$it.txt").exists() }
            val stale = updatedAt == 0L || System.currentTimeMillis() - updatedAt > 24L * 3600 * 1000
            if (stale || missing) refresh(appCtx)
        }.start()
    }

    fun refreshAsync(context: Context) {
        val appCtx = context.applicationContext
        Thread { refresh(appCtx) }.start()
    }

    /** The ten parsed collections, bundled so load,
     * refresh validation and the compiled cache can pass
     * one thing around (0.0.997). */
    internal class FilterData {
        val hosts = HashSet<String>()
        val exceptions = HashSet<String>()
        val scoped = HashMap<String, MutableList<ScopedRule>>()
        val generic = LinkedHashSet<String>()
        val perDomain = HashMap<String, MutableList<String>>()
        val docHosts = HashSet<String>()
        /** Beta.63 #26: hosts from tracker lists (subset of hosts). */
        val trackerHosts = HashSet<String>()
        val excGeneric = HashSet<String>()
        val excDomain = HashMap<String, MutableList<String>>()
        val hideOff = HashSet<String>()

        fun isEmpty() = hosts.isEmpty() && scoped.isEmpty() &&
            generic.isEmpty()

        fun total() = hosts.size +
            scoped.values.sumOf { it.size } + generic.size

        /** $document/$popup rule count: pure document hosts plus
         *  scoped rules carrying the document flag. Popunder /
         *  popup ad hosts live here — the STRICT POPUP BLOCKER
         *  list side (scorecard row K). */
        fun documentRuleCount(): Int =
            docHosts.size + scoped.values.sumOf { rules ->
                rules.count { it.document }
            }

        fun merge(other: FilterData) {
            hosts.addAll(other.hosts)
            exceptions.addAll(other.exceptions)
            other.scoped.forEach { (k, v) ->
                scoped.getOrPut(k) { mutableListOf() }.addAll(v)
            }
            generic.addAll(other.generic)
            other.perDomain.forEach { (k, v) ->
                perDomain.getOrPut(k) { mutableListOf() }.addAll(v)
            }
            docHosts.addAll(other.docHosts)
            trackerHosts.addAll(other.trackerHosts)
            excGeneric.addAll(other.excGeneric)
            other.excDomain.forEach { (k, v) ->
                excDomain.getOrPut(k) { mutableListOf() }.addAll(v)
            }
            hideOff.addAll(other.hideOff)
        }
    }

    /** Beta.63 #26: which category a list's rules count as.
     *  0 EasyList = ads, 1 EasyPrivacy = trackers, 2 StevenBlack
     *  hosts = ads (adware/malware domains), 3 AdGuard Tracking
     *  Protection = trackers, 4 AdGuard Mobile Ads = ads,
     *  5 uAssets filters = ads, 6 AdGuard Base = ads (its primary
     *  purpose is ad blocking; its tracker rules are a minority). */
    internal fun categoryForList(i: Int): Int = when (i) {
        1, 3 -> CAT_TRACKER
        else -> CAT_ADS
    }

    /** Parse one list's text (or a free-text ABP block) into
     *  [data] under list index [i]. Index 2 is the hosts file;
     *  index 99 is a custom list (its category is passed in). */
    internal fun parseInto(
        i: Int, text: String, data: FilterData, category: Int = categoryForList(i)
    ) {
        if (i == 2) parseHosts(text, data.hosts)
        else parse(
            text, data.hosts, data.exceptions, data.scoped,
            data.generic, data.perDomain, data.docHosts,
            data.excGeneric, data.excDomain, data.hideOff,
            category, data.trackerHosts
        )
    }

    /** Parse one list's text and return its counts, for the
     *  scorecard (row A) and the popup-list report (row K):
     *  hosts, scoped rules, generic selectors, document rules. */
    fun parseListCounts(i: Int, text: String): ListParseCounts {
        val data = FilterData()
        parseInto(i, text, data)
        return ListParseCounts(
            listIndex = i,
            listName = listNames.getOrNull(i) ?: "list$i",
            hosts = data.hosts.size,
            scopedRules = data.scoped.values.sumOf { it.size },
            genericSelectors = data.generic.size,
            documentRules = data.documentRuleCount(),
            total = data.total()
        )
    }

    data class ListParseCounts(
        val listIndex: Int,
        val listName: String,
        val hosts: Int,
        val scopedRules: Int,
        val genericSelectors: Int,
        val documentRules: Int,
        val total: Int
    )

    internal fun applyData(data: FilterData, newest: Long) {
        listHosts = data.hosts
        trackerHosts = data.trackerHosts
        exceptionHosts = data.exceptions
        scopedRules = data.scoped
        genericSelectors = data.generic.toList()
        domainSelectors = data.perDomain
        docHosts = data.docHosts
        cosmeticExcGeneric = data.excGeneric
        cosmeticExcDomain = data.excDomain
        genericHideOffHosts = data.hideOff
        cssCache = null
        documentStartScript = buildDocumentStartScript(
            chunkCosmeticSelectors(
                genericSelectors.filter {
                    it !in cosmeticExcGeneric
                },
                budget = 150_000
            ),
            genericHideOffHosts
        )
        ruleCount = data.hosts.size +
            data.scoped.values.sumOf { it.size }
        updatedAt = newest
        onListsChanged?.invoke()
    }

    /** Test-only: load a parsed [FilterData] as the live rule
     *  set. Unit tests parse fixed sample lists and load them
     *  here, then assert [blockCategory] / [isDocumentBlocked] /
     *  [cosmeticCss] verdicts — the same path the app uses after
     *  a disk load, minus the disk. */
    internal fun loadForTests(data: FilterData) {
        applyData(data, 0L)
    }

    /** Test-only: clear every compiled structure and counter, so
     *  one test's rules cannot leak into the next. The engine is
     *  a process-wide singleton; tests call this first. */
    internal fun resetForTests() {
        listHosts = emptySet()
        trackerHosts = emptySet()
        exceptionHosts = emptySet()
        scopedRules = emptyMap()
        genericSelectors = emptyList()
        domainSelectors = emptyMap()
        docHosts = emptySet()
        cosmeticExcGeneric = emptySet()
        cosmeticExcDomain = emptyMap()
        genericHideOffHosts = emptySet()
        documentStartScript = ""
        cssCache = null
        userNetRules = emptyList()
        userCosmeticRules = emptyList()
        disabledHosts = emptySet()
        disabledListIndices = emptySet()
        customListUrl = ""
        customAbpText = ""
        ruleCount = 0
        updatedAt = 0L
        updating = false
        blockedCountAtomic.set(0)
        blockedCount = 0L
        blockedByCategoryAtomic.forEach { it.set(0) }
        blockedByCategory = listOf(0, 0, 0)
        perTabCounts.clear()
        perTabBlocked = emptyMap()
        historyDays.clear()
        todayCounts.fill(0)
        flushedToday.fill(0)
        historyDay = todayKey()
        historyDirty = false
    }

    /** Live counts for the scorecard / Settings: total rules,
     *  pure document/popup hosts, scoped document rules, and the
     *  per-list document rule counts are reported by the
     *  verification harness (which parses each list separately);
     *  this is the merged live view. */
    fun liveDocumentCounts(): Pair<Int, Int> {
        val scopedDoc = scopedRules.values.sumOf { rules ->
            rules.count { it.document }
        }
        return docHosts.size to scopedDoc
    }

    private fun loadFromDisk(context: Context) {
        val d = dir(context)
        // Compiled cache first (0.0.997): if the binary cache
        // matches the on-disk lists exactly, it IS the parse.
        if (loadCompiled(d)) return
        val total = FilterData()
        var newest = 0L
        listUrls.forEachIndexed { i, _ ->
            if (i in disabledListIndices) return@forEachIndexed
            val f = File(d, "list$i.txt")
            val bak = File(d, "list$i.txt.bak")
            val srcFile = when {
                f.exists() -> f
                bak.exists() -> bak
                else -> null
            } ?: return@forEachIndexed
            newest = maxOf(newest, srcFile.lastModified())
            val one = FilterData()
            runCatching { parseInto(i, srcFile.readText(), one) }
            // Rollback: a list file that parses to nothing
            // (truncated or corrupt) falls back to the
            // previous good copy kept beside it.
            if (one.isEmpty() && srcFile == f && bak.exists()) {
                val fromBak = FilterData()
                runCatching { parseInto(i, bak.readText(), fromBak) }
                if (!fromBak.isEmpty()) {
                    total.merge(fromBak)
                    return@forEachIndexed
                }
            }
            total.merge(one)
        }
        // Audit P-m14: the user's custom list file and
        // free-text rules join the built-in lists.
        runCatching {
            val custom = File(d, "custom.txt")
            if (custom.exists()) {
                val one = FilterData()
                parseInto(99, custom.readText(), one, CAT_OTHER)
                total.merge(one)
            }
        }
        if (customAbpText.isNotBlank()) {
            val one = FilterData()
            runCatching { parseInto(0, customAbpText, one, CAT_OTHER) }
            total.merge(one)
        }
        // Sanity guard: a bad/truncated download must never wipe a good
        // rule set - if the freshly parsed hosts collapse versus what's
        // already loaded, keep the loaded rules.
        if (total.hosts.isNotEmpty() && listHosts.isNotEmpty() &&
            total.hosts.size < listHosts.size / 4
        ) {
            return
        }
        if (total.hosts.isNotEmpty()) {
            applyData(total, newest)
            writeCompiled(d, total, newest)
        }
    }

    private fun refresh(context: Context) {
        if (updating) return
        updating = true
        try {
            val d = dir(context)
            var ok = false
            listUrls.forEachIndexed { i, url ->
                runCatching {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 30_000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) NotscapePioneer")
                    val text = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    // Per-format sanity: a captive-portal HTML page or an
                    // error body must not replace a good list on disk.
                    val sane = text.length > 50_000 && when (i) {
                        2 -> text.contains("0.0.0.0")
                        else -> text.contains("||") && !text.trimStart().startsWith("<")
                    }
                    if (!sane) return@runCatching
                    // Validate-and-rollback (0.0.997): parse the
                    // candidate BEFORE it touches disk. Commit
                    // only if it parses to a real rule set and -
                    // when a copy is already on disk - not to a
                    // collapse versus that copy. The previous
                    // good file is kept as .bak; the commit goes
                    // through a staging file + rename so a crash
                    // mid-write can't leave a torn list.
                    val candidate = FilterData()
                    parseInto(i, text, candidate)
                    if (candidate.isEmpty()) return@runCatching
                    val target = File(d, "list$i.txt")
                    if (target.exists()) {
                        val current = FilterData()
                        runCatching {
                            parseInto(i, target.readText(), current)
                        }
                        if (!current.isEmpty() &&
                            candidate.total() < current.total() / 2
                        ) {
                            return@runCatching
                        }
                        runCatching {
                            target.copyTo(
                                File(d, "list$i.txt.bak"),
                                overwrite = true
                            )
                        }
                    }
                    val tmp = File(d, "list$i.txt.tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    ok = true
                }
            }
            // Audit P-m14: the custom list refreshes here too.
            if (customListUrl.isNotBlank()) {
                runCatching {
                    val conn = URL(customListUrl).openConnection()
                        as HttpURLConnection
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 30_000
                    conn.setRequestProperty(
                        "User-Agent", "Mozilla/5.0 (Android) NotscapePioneer"
                    )
                    val text = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val sane = text.isNotBlank() &&
                        !text.trimStart().startsWith("<")
                    if (sane) {
                        val candidate = FilterData()
                        parseInto(99, text, candidate)
                        if (!candidate.isEmpty()) {
                            val tmp = File(d, "custom.txt.tmp")
                            tmp.writeText(text)
                            if (!tmp.renameTo(File(d, "custom.txt"))) {
                                tmp.copyTo(
                                    File(d, "custom.txt"), overwrite = true
                                )
                                tmp.delete()
                            }
                            ok = true
                        }
                    }
                }
            }
            if (ok) loadFromDisk(context)
        } finally {
            updating = false
        }
    }

    // ---- Compiled cache (0.0.997) ----
    // Parsing seven ABP lists from text costs real background
    // CPU on every cold start. After any healthy full parse,
    // the structures are written to compiled.bin; a later
    // start loads the binary instead - but only when its
    // fingerprint (every list file's length + mtime) still
    // matches the files on disk exactly. Any doubt at all
    // falls back to the full text parse, which rewrites the
    // cache. The format is versioned and all-or-nothing.

    private fun fingerprint(d: File): LongArray {
        val out = LongArray(listUrls.size * 2)
        listUrls.indices.forEach { i ->
            val main = File(d, "list$i.txt")
            val bak = File(d, "list$i.txt.bak")
            val srcFile = when {
                main.exists() -> main
                bak.exists() -> bak
                else -> null
            }
            out[i * 2] = srcFile?.length() ?: -1L
            out[i * 2 + 1] = srcFile?.lastModified() ?: -1L
        }
        return out
    }

    /**
     * Hash of the list configuration that shapes the parsed rule
     * set (beta.56): which lists are disabled, the custom list
     * URL, and the free-text ABP rules. Stored beside the file
     * fingerprint so a config change invalidates the compiled
     * cache - previously a kill between setListConfig and the
     * compiled.bin delete in reloadLists could leave a stale
     * ruleset applied on next launch. (The custom URL only
     * affects refresh downloads, not the parsed set, but
     * including it costs one re-parse and keeps the key simple.)
     */
    private fun configHash(): Long {
        var h = 17L
        for (i in disabledListIndices.sorted()) h = h * 31 + i
        h = h * 31 + customListUrl.hashCode()
        h = h * 31 + customAbpText.hashCode()
        return h
    }

    private fun writeStr(out: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readStr(inp: DataInputStream): String {
        val n = inp.readInt()
        require(n in 0..1_000_000)
        val bytes = ByteArray(n)
        inp.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun writeStrSet(out: DataOutputStream, set: Set<String>) {
        out.writeInt(set.size)
        set.forEach { writeStr(out, it) }
    }

    private fun readStrSet(inp: DataInputStream): Set<String> {
        val n = inp.readInt()
        require(n in 0..2_000_000)
        val set = HashSet<String>()
        repeat(n) { set.add(readStr(inp)) }
        return set
    }

    private fun writeStrList(out: DataOutputStream, list: List<String>) {
        out.writeInt(list.size)
        list.forEach { writeStr(out, it) }
    }

    private fun readStrList(inp: DataInputStream): List<String> {
        val n = inp.readInt()
        require(n in 0..2_000_000)
        return List(n) { readStr(inp) }
    }

    private fun writeStrListMap(
        out: DataOutputStream, map: Map<String, List<String>>
    ) {
        out.writeInt(map.size)
        map.forEach { (k, v) ->
            writeStr(out, k)
            writeStrList(out, v)
        }
    }

    private fun readStrListMap(
        inp: DataInputStream,
        into: MutableMap<String, MutableList<String>>
    ) {
        val n = inp.readInt()
        require(n in 0..500_000)
        repeat(n) {
            val key = readStr(inp)
            into[key] = readStrList(inp).toMutableList()
        }
    }

    private fun writeNullableStr(out: DataOutputStream, s: String?) {
        out.writeBoolean(s != null)
        if (s != null) writeStr(out, s)
    }

    private fun readNullableStr(inp: DataInputStream): String? =
        if (inp.readBoolean()) readStr(inp) else null

    private fun writeNullableStrSet(
        out: DataOutputStream, set: Set<String>?
    ) {
        out.writeBoolean(set != null)
        if (set != null) writeStrSet(out, set)
    }

    private fun readNullableStrSet(
        inp: DataInputStream
    ): Set<String>? =
        if (inp.readBoolean()) readStrSet(inp) else null

    internal fun writeCompiled(
        d: File, data: FilterData, newest: Long
    ) {
        runCatching {
            val tmp = File(d, "compiled.bin.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(COMPILED_MAGIC)
                out.writeInt(COMPILED_VERSION)
                fingerprint(d).forEach { out.writeLong(it) }
                out.writeLong(configHash())
                writeStrSet(out, data.hosts)
                writeStrSet(out, data.trackerHosts)
                writeStrSet(out, data.exceptions)
                out.writeInt(data.scoped.size)
                data.scoped.forEach { (key, list) ->
                    writeStr(out, key)
                    out.writeInt(list.size)
                    list.forEach { rule ->
                        writeNullableStr(out, rule.pathPrefix)
                        writeNullableStrSet(out, rule.domains)
                        writeNullableStrSet(out, rule.notDomains)
                        out.writeBoolean(rule.exception)
                        out.writeByte(
                            when (rule.thirdParty) {
                                null -> -1
                                false -> 0
                                else -> 1
                            }
                        )
                        out.writeBoolean(rule.important)
                        out.writeByte(rule.category)
                        out.writeBoolean(rule.document)
                    }
                }
                writeStrList(out, data.generic.toList())
                writeStrListMap(out, data.perDomain)
                writeStrSet(out, data.docHosts)
                writeStrSet(out, data.excGeneric)
                writeStrListMap(out, data.excDomain)
                writeStrSet(out, data.hideOff)
                out.writeLong(newest)
            }
            val target = File(d, "compiled.bin")
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    /** Loads the compiled cache if - and only if - it is
     * intact and its fingerprint matches the list files on
     * disk exactly. Returns false on any doubt. */
    internal fun readCompiled(d: File): Pair<FilterData, Long>? {
        val f = File(d, "compiled.bin")
        if (!f.exists()) return null
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { inp ->
                if (inp.readInt() != COMPILED_MAGIC) return null
                if (inp.readInt() != COMPILED_VERSION) return null
                val stored = LongArray(listUrls.size * 2) {
                    inp.readLong()
                }
                if (!stored.contentEquals(fingerprint(d))) {
                    return null
                }
                if (inp.readLong() != configHash()) {
                    return null
                }
                val data = FilterData()
                data.hosts.addAll(readStrSet(inp))
                data.trackerHosts.addAll(readStrSet(inp))
                data.exceptions.addAll(readStrSet(inp))
                val scopedCount = inp.readInt()
                require(scopedCount in 0..500_000)
                repeat(scopedCount) {
                    val key = readStr(inp)
                    val ruleCountIn = inp.readInt()
                    require(ruleCountIn in 0..100_000)
                    val list = mutableListOf<ScopedRule>()
                    repeat(ruleCountIn) {
                        list.add(
                            ScopedRule(
                                pathPrefix = readNullableStr(inp),
                                domains = readNullableStrSet(inp),
                                notDomains = readNullableStrSet(inp),
                                exception = inp.readBoolean(),
                                thirdParty = when (
                                    inp.readByte().toInt()
                                ) {
                                    -1 -> null
                                    0 -> false
                                    else -> true
                                },
                                important = inp.readBoolean(),
                                category = inp.readByte().toInt(),
                                document = inp.readBoolean()
                            )
                        )
                    }
                    data.scoped[key] = list
                }
                data.generic.addAll(readStrList(inp))
                readStrListMap(inp, data.perDomain)
                data.docHosts.addAll(readStrSet(inp))
                data.excGeneric.addAll(readStrSet(inp))
                readStrListMap(inp, data.excDomain)
                data.hideOff.addAll(readStrSet(inp))
                val newest = inp.readLong()
                if (data.hosts.isEmpty()) null
                else data to newest
            }
        }.getOrNull()
    }

    private fun loadCompiled(d: File): Boolean {
        val (data, newest) = readCompiled(d) ?: return false
        // Same collapse guard as the text path.
        if (listHosts.isNotEmpty() &&
            data.hosts.size < listHosts.size / 4
        ) {
            return false
        }
        applyData(data, newest)
        return true
    }

    /**
     * Compile ABP rules into three structures:
     *  - pure ||host^ rules → the flat host sets;
     *  - path-scoped and $domain=-scoped rules → [ScopedRule]s keyed by
     *    host, applied only when their conditions match (rules carrying
     *    other $ options — types, $rewrite — are still skipped);
     *  - cosmetic ## rules with simple selectors → generic and
     *    per-domain hide lists, injected as CSS per page.
     */
    internal fun parse(
        text: String,
        hosts: MutableSet<String>,
        exceptions: MutableSet<String>,
        scoped: MutableMap<String, MutableList<ScopedRule>>,
        generic: MutableSet<String>,
        perDomain: MutableMap<String, MutableList<String>>,
        docHosts: MutableSet<String>,
        genericExc: MutableSet<String>,
        perDomainExc: MutableMap<String, MutableList<String>>,
        hideOff: MutableSet<String>,
        category: Int = CAT_ADS,
        trackerHosts: MutableSet<String>? = null
    ) {
        // $badfilter pass: a badfilter rule disables its twin. Collect
        // the twins' signatures first (the badfilter line may precede
        // the rule it disables), then skip both in the main pass.
        val badSignatures = HashSet<String>()
        for (raw in text.lineSequence()) {
            val rawLine = raw.trim()
            if (rawLine.contains("badfilter")) {
                // Bare host pairs normalise the same way the
                // main pass does, so a bare $badfilter twin is
                // recognised; || pairs are unaffected.
                var line = rawLine
                if (!line.startsWith("|") && !line.startsWith("@@") &&
                    !line.contains("##") && !line.contains("#@#")
                ) {
                    line = bareToDoublePipe(line) ?: ""
                }
                ruleSignature(line)?.let { badSignatures.add(it) }
            }
        }
        for (raw in text.lineSequence()) {
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue
            // Bare network patterns (beta.56): a line with no |,
            // @@, or cosmetic marker is an ABP substring pattern;
            // when the whole body is a valid host (optionally
            // with $options) it rewrites to || form so the shared
            // machinery below handles it. Anything else in this
            // shape stays dropped, as before.
            if (!line.startsWith("|") && !line.startsWith("@@") &&
                !line.contains("##") && !line.contains("#@#")
            ) {
                line = bareToDoublePipe(line) ?: continue
            }
            if (!line.startsWith("|") && !line.startsWith("@@")) {
                // Cosmetic rules: ## hides, #@# unhides. (#$# / #?#
                // extended rules stay out - they are not plain CSS.)
                val excIdx = line.indexOf("#@#")
                val hideIdx = line.indexOf("##")
                val idx = when {
                    excIdx >= 0 -> excIdx
                    hideIdx >= 0 -> hideIdx
                    else -> -1
                }
                if (idx >= 0 && !line.contains("#$#") && !line.contains("#?#")) {
                    val isExc = excIdx >= 0
                    val selector = line.substring(idx + if (isExc) 3 else 2).trim()
                    if (isCssSelector(selector)) {
                        val domainPart = line.substring(0, idx).trim()
                        val positives = ArrayList<String>()
                        val negatives = ArrayList<String>()
                        if (domainPart.isNotEmpty()) {
                            for (dom in domainPart.split(',')) {
                                val d = dom.trim().lowercase()
                                if (d.startsWith("~")) negatives.add(d.substring(1))
                                else if (d.isNotEmpty() && d.contains('.')) positives.add(d)
                            }
                        }
                        fun excFor(d: String) {
                            if (d.contains('.')) {
                                val list = perDomainExc.getOrPut(d) { mutableListOf() }
                                if (selector !in list) list.add(selector)
                            }
                        }
                        if (isExc) {
                            when {
                                positives.isNotEmpty() -> for (d in positives) {
                                    val list = perDomainExc.getOrPut(d) { mutableListOf() }
                                    if (selector !in list) list.add(selector)
                                }
                                domainPart.isEmpty() -> genericExc.add(selector)
                            }
                        } else {
                            when {
                                positives.isNotEmpty() -> {
                                    for (d in positives) {
                                        val list = perDomain.getOrPut(d) { mutableListOf() }
                                        if (selector !in list) list.add(selector)
                                    }
                                    for (d in negatives) excFor(d)
                                }
                                negatives.isNotEmpty() -> {
                                    generic.add(selector)
                                    for (d in negatives) excFor(d)
                                }
                                else -> generic.add(selector)
                            }
                        }
                    }
                }
                continue
            }
            if (line.startsWith("@@") && !line.startsWith("@@||")) {
                // Page-level cosmetic exceptions with no host anchor
                // (@@$generichide,domain=a|b): the named domains lose
                // generic hiding. A bare exception host
                // (@@host$options, beta.56) rewrites to @@|| form
                // and falls through to the shared machinery;
                // anything else in this shape stays dropped.
                if (line.contains("generichide") || line.contains("ghide")) {
                    val dm = line.indexOf("domain=")
                    if (dm >= 0) {
                        val rest = line.substring(dm + 7)
                        val end = rest.indexOf('$')
                        val domPart = if (end >= 0) rest.substring(0, end) else rest
                        for (dd in domPart.split('|')) {
                            val d = dd.trim().lowercase()
                            if (d.isNotEmpty() && !d.startsWith("~") && d.contains('.')) {
                                hideOff.add(d)
                            }
                        }
                    }
                    continue
                }
                line = bareExceptionToDouble(line) ?: continue
            }
            val exception = line.startsWith("@@")
            val body0 = when {
                line.startsWith("@@||") -> line.substring(4)
                line.startsWith("||") -> line.substring(2)
                line.startsWith("|") -> singlePipeBody(line.substring(1)) ?: continue
                else -> continue
            }
            val dollar = body0.indexOf('$')
            val bodyRaw = if (dollar >= 0) body0.substring(0, dollar) else body0
            // ||*.ads.example.com^ - a leading wildcard is exactly
            // what our parent-walking matcher already does (a listed
            // host covers its subdomains), so it is stripped here,
            // before the host scan (which treats '*' as a
            // terminator). Interior wildcards still skip the rule.
            val body = if (bodyRaw.startsWith("*.")) bodyRaw.substring(2) else bodyRaw
            val options = if (dollar >= 0) body0.substring(dollar + 1) else ""
            if (options.split(',').any { it.trim().lowercase() == "badfilter" }) continue
            if (badSignatures.isNotEmpty() &&
                ruleSignature(line) in badSignatures
            ) continue
            var domains: Set<String>? = null
            var notDomains: Set<String>? = null
            var thirdParty: Boolean? = null
            var important = false
            var hideModifier = false
            var docType = false
            var otherType = false
            var optionsOk = true
            if (options.isNotEmpty()) {
                for (opt in options.split(',')) {
                    val o = opt.trim().lowercase()
                    when {
                        // Party options, ABP and AdGuard spellings.
                        o == "third-party" || o == "3p" || o == "~1p" -> thirdParty = true
                        o == "~third-party" || o == "~3p" || o == "1p" -> thirdParty = false
                        o == "important" -> important = true
                        // Resource-type options ($script, $image,
                        // $document, $~image, ...): the rule DOES
                        // apply. WebView interception cannot split
                        // requests by resource type, so the host/path
                        // part is honoured for every type. Dropping
                        // these rules (the old gate) silently lost
                        // thousands of live list rules, including
                        // most $document popunder hosts.
                        o in TYPE_OPTIONS ||
                            (o.startsWith("~") && o.substring(1) in TYPE_OPTIONS) -> {
                            val base = o.removePrefix("~")
                            if (base == "document" || base == "popup") docType = true
                            else otherType = true
                        }
                        // Cosmetic-scope modifiers: recognised so the
                        // rule is not dropped whole; resolved after the
                        // loop (exceptions) or ignored (block rules,
                        // where the network part still applies).
                        o == "generichide" || o == "ghide" || o == "elemhide" -> hideModifier = true
                        o == "match-case" || o == "all" -> Unit
                        o.startsWith("domain=") -> {
                            val pos = HashSet<String>()
                            val neg = HashSet<String>()
                            for (dd in o.substring(7).split('|')) {
                                val d = dd.trim()
                                if (d.startsWith("~")) neg.add(d.substring(1))
                                else if (d.isNotEmpty()) pos.add(d)
                            }
                            domains = pos.ifEmpty { null }
                            notDomains = neg.ifEmpty { null }
                        }
                        else -> { optionsOk = false; break }
                    }
                }
            }
            if (!optionsOk) continue
            if (exception && hideModifier && !otherType &&
                thirdParty == null && !important
            ) {
                // A cosmetic-scope exception (@@||host^$generichide
                // and friends): it governs generic hiding on pages,
                // never network requests, so it never reaches the
                // network sets. The $domain= set wins; otherwise the
                // rule's own host is the page scope.
                val targets = domains
                if (targets != null) {
                    hideOff.addAll(targets)
                } else {
                    var he = body.length
                    for (i in body.indices) {
                        val c = body[i]
                        if (c == '/' || c == '^' || c == '|' || c == '*') { he = i; break }
                    }
                    val h = body.substring(0, he).trim().lowercase()
                    if (h.contains('.')) hideOff.add(h)
                }
                continue
            }
            var hostEnd = body.length
            for (i in body.indices) {
                val c = body[i]
                if (c == '/' || c == '^' || c == '|' || c == '*') { hostEnd = i; break }
            }
            val host = body.substring(0, hostEnd).trim().lowercase()
            if (!host.contains('.') ||
                !host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
            ) continue
            val rest = body.substring(hostEnd)
            val isScoped = domains != null || notDomains != null || thirdParty != null || important
            if (rest.isEmpty() || rest == "^") {
                if (isScoped) {
                    scoped.getOrPut(host) { mutableListOf() }
                        .add(ScopedRule(null, domains, notDomains, exception, thirdParty, important, category = category, document = docType))
                } else {
                    (if (exception) exceptions else hosts).add(host)
                    if (!exception && category == CAT_TRACKER) trackerHosts?.add(host)
                    // Popunder provenance for the navigation guard:
                    // a pure host whose rule applies to document
                    // loads ($document and/or $popup, whatever
                    // other types it also carries).
                    if (!exception && docType) docHosts.add(host)
                }
                continue
            }
            // Beta.67 (3): ||host^/path rules carry the path
            // after a ^ separator — the old branch only took a
            // leading /, so these real list rules were dropped.
            // A caret NOT followed by / (||host^foo) still
            // drops, as before.
            if (rest.startsWith("/") || rest.startsWith("^/")) {
                var path = if (rest.startsWith("^")) rest.substring(1) else rest
                val star = path.indexOf('*')
                if (star >= 0) path = path.substring(0, star)
                val caret = path.indexOf('^')
                if (caret >= 0) path = path.substring(0, caret)
                val pipe = path.indexOf('|')
                if (pipe >= 0) path = path.substring(0, pipe)
                if (path.length < 2) continue
                scoped.getOrPut(host) { mutableListOf() }
                    .add(ScopedRule(path.lowercase(), domains, notDomains, exception, thirdParty, important, category = category, document = docType))
                continue
            }
            // Anchored/wildcard leftovers — skip.
        }
    }

    /**
     * Single-| anchor (beta.56): strip one leading pipe and any
     * scheme, leaving host + optional path for the shared ||
     * machinery. Returns null for unanchored wildcard forms
     * (|*ads*) and empty bodies, which stay dropped.
     */
    private fun singlePipeBody(rest: String): String? {
        if (rest.isEmpty() || rest.startsWith("*")) return null
        val scheme = rest.indexOf("://")
        val body = if (scheme >= 0) rest.substring(scheme + 3) else rest
        if (body.isEmpty() || body.startsWith("*")) return null
        return body
    }

    /**
     * Bare host pattern (beta.56): a line that is exactly a valid
     * host, optionally with $options, rewrites to || form. This
     * is strictly narrower than ABP substring semantics (a
     * matching host implies the pattern appears in the URL, but
     * the reverse is not true), so it can never over-block
     * relative to the list author's pattern. Path-bearing,
     * wildcard, and host-less bodies return null (dropped).
     */
    private fun bareToDoublePipe(line: String): String? {
        val dollar = line.indexOf('$')
        val body = if (dollar >= 0) line.substring(0, dollar) else line
        val host = body.trim().lowercase()
        if (!host.contains('.') ||
            !host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
        ) return null
        return if (dollar >= 0) "||$host\$${line.substring(dollar + 1)}"
        else "||$host"
    }

    /**
     * Bare exception host (beta.56): @@host$options rewrites to
     * @@||host$options; a single-| exception (@@|scheme://...)
     * is normalised the same way. Anything else returns null.
     */
    private fun bareExceptionToDouble(line: String): String? {
        val rest = line.substring(2)
        if (rest.startsWith("|")) {
            val body = singlePipeBody(rest.substring(1)) ?: return null
            return "@@||$body"
        }
        val dollar = rest.indexOf('$')
        val body = if (dollar >= 0) rest.substring(0, dollar) else rest
        val host = body.trim().lowercase()
        if (!host.contains('.') ||
            !host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
        ) return null
        return if (dollar >= 0) "@@||$host\$${rest.substring(dollar + 1)}"
        else "@@||$host"
    }

    /** Identity of a || rule for $badfilter matching: exception flag,
     *  body, and the option set with badfilter removed (order-free). */
    private fun ruleSignature(line: String): String? {
        val exception = line.startsWith("@@")
        val body0 = when {
            line.startsWith("@@||") -> line.substring(4)
            line.startsWith("||") -> line.substring(2)
            else -> return null
        }
        val dollar = body0.indexOf('$')
        val body = if (dollar >= 0) body0.substring(0, dollar) else body0
        val options = if (dollar >= 0) body0.substring(dollar + 1) else ""
        val opts = options.split(',').map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it != "badfilter" }.sorted()
        return (if (exception) "@@" else "") + body + "$" + opts.joinToString(",")
    }

    /**
     * Plain-CSS selector gate (widened 0.0.962): combinators, commas
     * and functional :not()/:has() are all valid CSS that Chromium
     * evaluates natively, and the measured lists carry ~10k such
     * rules the old gate dropped. Procedural pseudos (:contains,
     * :has-text, :xpath, :style(), ...) are NOT CSS and stay out -
     * one invalid selector would void its whole rule (the sheet is
     * chunked in cosmeticCss to bound even that).
     */
    private fun isCssSelector(sel: String): Boolean {
        if (sel.isEmpty() || sel.length > 200) return false
        val banned = listOf(
            ":contains", ":has-text", ":xpath", ":matches-css",
            ":matches-attr", ":style(", ":remove(", ":upward",
            ":-abp-", ":if(", ":min-text-length", ":watch-attr", "::"
        )
        if (banned.any { sel.contains(it) }) return false
        return sel.all { it.isLetterOrDigit() || it in "#.[]=\"'^$*|_- >+~,()%!:" }
    }

    /** Hosts-file format (0.0.0.0 domain / 127.0.0.1 domain lines). */
    internal fun parseHosts(text: String, hosts: MutableSet<String>) {
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) continue
            if (parts[0] != "0.0.0.0" && parts[0] != "127.0.0.1") continue
            val host = parts[1].lowercase()
            if (host.contains('.') && host.any { it.isLetter() } &&
                host.all { it.isLetterOrDigit() || it == '.' || it == '-' }
            ) {
                hosts.add(host)
            }
        }
    }

    /** Player/CDN hosts that must never be host-blocked (see parse note). */
    private val neverBlockHosts = setOf(
        "googlevideo.com", "googleapis.com", "youtube.com", "ytimg.com",
        "ggpht.com", "jwplatform.com", "jwpcdn.com", "jwplayer.com",
        "vimeo.com", "vimeocdn.com", "akamaihd.net", "dailymotion.com",
        "twitch.tv"
    )

    // ------------------------------------------------------------------
    // Host matching helpers
    // ------------------------------------------------------------------

    /** True if [host] or any of its parent domains is in [set]. */
    private fun matches(set: Set<String>, host: String): Boolean {
        var h = host
        while (true) {
            if (set.contains(h)) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return set.contains(h)
        }
    }

    /** [host] and each parent domain, longest first. */
    private fun hostChain(host: String): List<String> {
        val out = ArrayList<String>()
        var h = host
        while (true) {
            out.add(h)
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
            if (!h.contains('.')) { out.add(h); break }
        }
        return out
    }

    // Mariner's Hosts.kt fallback, carried over without Guava
    // (see the file header): common multi-label public suffixes
    // plus a last-two-labels rule. Mariner prefers Guava's full
    // public-suffix data when it parses; Pioneer has no Guava,
    // so this heuristic IS the registrable-domain function here.
    private val MULTI_LABEL_SUFFIXES = setOf(
        "ac.uk", "co.uk", "gov.uk", "me.uk", "net.uk", "nhs.uk", "org.uk", "police.uk", "sch.uk",
        "asn.au", "com.au", "edu.au", "gov.au", "id.au", "net.au", "org.au",
        "ac.nz", "co.nz", "geek.nz", "govt.nz", "net.nz", "org.nz", "school.nz",
        "ac.jp", "co.jp", "go.jp", "ne.jp", "or.jp",
        "ac.kr", "co.kr", "go.kr", "ne.kr", "or.kr",
        "ac.in", "co.in", "firm.in", "gen.in", "gov.in", "ind.in", "net.in", "org.in",
        "com.br", "gov.br", "net.br", "org.br",
        "com.ar", "com.co", "com.mx", "gob.mx", "com.pe", "com.ve",
        "com.cn", "gov.cn", "net.cn", "org.cn",
        "com.tw", "gov.tw", "org.tw",
        "com.hk", "gov.hk", "org.hk",
        "com.sg", "gov.sg",
        "ac.za", "co.za", "gov.za", "org.za",
        "ac.il", "co.il", "gov.il", "org.il",
        "com.tr", "gov.tr", "org.tr",
        "com.pl", "com.ua", "com.vn", "com.ph", "com.my", "com.pk", "com.ng", "com.eg",
        "com.sa", "com.ae", "com.qa", "com.bd", "com.np", "com.lk", "com.kh", "com.mm"
    )

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /** The registrable domain (eTLD+1) of [host], by the
     *  multi-label-suffix heuristic (no Guava in Pioneer). */
    internal fun registrableDomain(host: String): String {
        val h = host.lowercase().trimEnd('.')
        if (IPV4.matches(h)) return h
        val labels = h.split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2) return labels.joinToString(".")
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in MULTI_LABEL_SUFFIXES) {
            labels.takeLast(3).joinToString(".")
        } else {
            lastTwo
        }
    }

    /**
     * True when [a] and [b] are the same site: the same host, or
     * hosts under the same registrable domain (e.g. login.example.com
     * and www.example.com). IP addresses only ever match themselves.
     */
    internal fun sameSite(a: String, b: String): Boolean {
        val ha = a.lowercase().trimEnd('.')
        val hb = b.lowercase().trimEnd('.')
        if (ha == hb) return true
        if (IPV4.matches(ha) || IPV4.matches(hb)) return false
        return registrableDomain(ha) == registrableDomain(hb)
    }

    /** True when the request is cross-site relative to the page:
     *  anything not on the page's own registrable domain (eTLD+1)
     *  is third-party — so sibling subdomains of a co.uk-style site
     *  count as first-party, as they should. */
    private fun isThirdParty(reqHost: String, pageHost: String): Boolean {
        if (pageHost.isEmpty() || reqHost == pageHost) return false
        return !sameSite(reqHost, pageHost)
    }

    private fun scopedMatch(
        reqHost: String,
        path: String,
        pageHost: String,
        wantException: Boolean,
        onlyImportant: Boolean = false
    ): Boolean =
        scopedRuleMatch(reqHost, path, pageHost, wantException, onlyImportant) != null

    /** Beta.63 #26: the first scoped rule matching this request,
     *  or null. [scopedMatch] is the boolean view of this. */
    private fun scopedRuleMatch(
        reqHost: String,
        path: String,
        pageHost: String,
        wantException: Boolean,
        onlyImportant: Boolean = false,
        onlyDocument: Boolean = false
    ): ScopedRule? {
        for (h in hostChain(reqHost)) {
            val rules = scopedRules[h] ?: continue
            for (r in rules) {
                if (r.exception != wantException) continue
                if (onlyImportant && !r.important) continue
                if (onlyDocument && !r.document) continue
                if (r.domains != null &&
                    (pageHost.isEmpty() || !matches(r.domains, pageHost))
                ) continue
                if (r.notDomains != null && pageHost.isNotEmpty() &&
                    matches(r.notDomains, pageHost)
                ) continue
                if (r.thirdParty != null &&
                    r.thirdParty != isThirdParty(reqHost, pageHost)
                ) continue
                if (r.pathPrefix != null && !path.startsWith(r.pathPrefix)) continue
                return r
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // URL splitting (string URLs, not android.net.Uri)
    // ------------------------------------------------------------------

    /** Scheme / host / path of a request URL, lowercased exactly
     *  where Mariner lowercases (`uri.host`, `uri.path`, and the
     *  full string for pathKeywords). The authority is stripped
     *  of userinfo and port; a missing or unparseable host yields
     *  an empty host, which never matches — same as Mariner's
     *  `uri.host ?: ""` path. */
    internal data class UrlParts(
        val scheme: String,
        val host: String,
        val path: String,
        /** Full URL, lowercased (for pathKeyword matching). */
        val fullLower: String
    )

    internal fun splitUrl(url: String): UrlParts {
        val fullLower = url.lowercase()
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) {
            // Scheme-relative or bare host/path: no authority to
            // match against, so no host block can apply.
            return UrlParts("", "", "", fullLower)
        }
        val scheme = url.substring(0, schemeEnd).lowercase()
        val rest = url.substring(schemeEnd + 3)
        val slash = rest.indexOf('/')
        val authority = if (slash >= 0) rest.substring(0, slash) else rest
        // Path only — Mariner matches on uri.path, which excludes
        // the query and fragment (a user URL rule must still match
        // an ad URL with a cache-buster). The full URL, query
        // included, stays in fullLower for pathKeywords.
        val rawPath = if (slash >= 0) rest.substring(slash) else ""
        val pathEnd = rawPath.indexOfFirst { it == '?' || it == '#' }
            .let { if (it < 0) rawPath.length else it }
        val path = rawPath.substring(0, pathEnd)
        // Strip userinfo, then port.
        val hostPort = authority.substringAfterLast('@')
        val host = hostPort.substringBefore(':').lowercase().trimEnd('.')
        // Reject hosts with characters a host can never carry
        // (spaces, brackets beyond IPv6, query markers) — they
        // come from malformed URLs, never from real requests.
        val cleanHost =
            if (host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' }) host
            else ""
        return UrlParts(scheme, cleanHost, path.lowercase(), fullLower)
    }

    // ------------------------------------------------------------------
    // The matcher (Mariner decision order, verbatim)
    // ------------------------------------------------------------------

    fun isBlocked(
        url: String,
        isForMainFrame: Boolean,
        blockAds: Boolean,
        blockTrackers: Boolean,
        pageHost: String = ""
    ): Boolean =
        blockCategory(url, isForMainFrame, blockAds, blockTrackers, pageHost) != null

    /** Beta.63 #26: the category of the rule that blocks [url],
     *  or null when nothing blocks it. Same decision order as
     *  Mariner's [isBlocked] — the user's own rules beat the
     *  compiled lists, $important beats exceptions, and main-frame
     *  loads are never blocked, so a rule can never break a
     *  tapped link. A host named by a tracker list counts as
     *  a tracker even when an ad list names it too.
     *
     *  NOTE: main-frame loads return null here by design. The
     *  $document/$popup main-frame case is [isDocumentBlocked],
     *  enforced by the background script and by Worker B's
     *  popunder guard — it is a separate decision on purpose. */
    fun blockCategory(
        url: String,
        isForMainFrame: Boolean,
        blockAds: Boolean,
        blockTrackers: Boolean,
        pageHost: String = ""
    ): Int? {
        if (isForMainFrame) return null
        if (!blockAds && !blockTrackers) return null
        val parts = splitUrl(url)
        val host = parts.host
        if (host.isEmpty()) return null
        if (blockAds) {
            if (userNetRules.any {
                    UserBlockRules.netMatches(it, parts.scheme, host, parts.path)
                }
            ) return CAT_OTHER
        }
        if (matches(neverBlockHosts, host)) return null
        val path = parts.path
        val page = pageHost.lowercase()
        scopedRuleMatch(host, path, page, wantException = false, onlyImportant = true)
            ?.let { return it.category }
        if (matches(exceptionHosts, host)) return null
        if (scopedMatch(host, path, page, wantException = true)) return null
        if (blockTrackers && matches(builtinHosts, host)) return CAT_TRACKER
        if ((blockAds || blockTrackers) && matches(listHosts, host)) {
            return if (matches(trackerHosts, host)) CAT_TRACKER else CAT_ADS
        }
        scopedRuleMatch(host, path, page, wantException = false)
            ?.let { return it.category }
        if (blockAds) {
            if (pathKeywords.any { parts.fullLower.contains(it) }) return CAT_ADS
        }
        return null
    }

    /**
     * True when [host] is a known document/popup ad host: the lists
     * carry a pure ||host^ rule whose only type options are $document
     * and/or $popup - popunder networks, in practice. Consulted by
     * the navigation guard only; typed addresses and tapped links
     * never reach it in Mariner. In Pioneer the background script
     * ALSO cancels main-frame loads to these hosts (steering,
     * STRICT POPUP BLOCKER) — see [isDocumentBlocked] and the
     * `background.js` header for the deliberate-navigation caveat.
     */
    fun isDocumentHost(host: String): Boolean {
        val h = host.lowercase()
        if (h.isEmpty()) return false
        if (matches(neverBlockHosts, h)) return false
        if (matches(exceptionHosts, h)) return false
        return matches(docHosts, h)
    }

    /**
     * Beta.63 #64: full $document/$popup enforcement at request
     * level. True when the main-frame load of [url] is covered by
     * a document rule — a pure ||host^$document/$popup rule, or a
     * scoped one ($domain=, path, $third-party) whose scope
     * matches this navigation. $important document rules beat
     * exceptions; a scoped document exception vetoes. Consulted
     * by the navigation guard (Worker B) and by the probe
     * background script, which cancels main-frame loads it
     * matches via webRequestBlocking.
     */
    fun isDocumentBlocked(url: String, pageHost: String): Boolean {
        val parts = splitUrl(url)
        val host = parts.host
        if (host.isEmpty()) return false
        if (matches(neverBlockHosts, host)) return false
        val path = parts.path
        val page = pageHost.lowercase()
        scopedRuleMatch(
            host, path, page, wantException = false,
            onlyImportant = true, onlyDocument = true
        )?.let { return true }
        if (matches(exceptionHosts, host)) return false
        if (scopedRuleMatch(
                host, path, page, wantException = true, onlyDocument = true
            ) != null
        ) return false
        if (isDocumentHost(host)) return true
        return scopedRuleMatch(
            host, path, page, wantException = false, onlyDocument = true
        ) != null
    }

    // ------------------------------------------------------------------
    // Cosmetics
    // ------------------------------------------------------------------

    /**
     * Chunks selectors into rules of 25 with the standard
     * hiding suffix, under a total size budget - the same
     * shape [cosmeticCss] builds inline for its per-host
     * sheet (0.0.99995).
     */
    internal fun chunkCosmeticSelectors(
        sels: Collection<String>, budget: Int
    ): String {
        if (sels.isEmpty()) return ""
        val suffix = "{display:none!important}"
        val sb = StringBuilder()
        var chunk = StringBuilder()
        var inChunk = 0
        fun flushChunk() {
            if (chunk.isNotEmpty()) {
                sb.append(chunk).append(suffix)
                chunk = StringBuilder()
                inChunk = 0
            }
        }
        for (sel in sels) {
            val extra = if (inChunk == 0) sel.length else sel.length + 1
            if (sb.length + chunk.length + extra + suffix.length > budget) break
            if (inChunk > 0) chunk.append(',')
            chunk.append(sel)
            inChunk++
            if (inChunk >= 25) flushChunk()
        }
        flushChunk()
        return sb.toString()
    }

    /**
     * Builds the document-start script (0.0.99995): injects
     * [css] as a style element the moment the document
     * exists, skipping hosts in [offHosts] (the
     * $generichide set, checked in-script against
     * location.hostname because the script is registered
     * before the target host is known - walking up to parent
     * domains, beta.56, so a $generichide page scope covers
     * its subdomains exactly like the per-navigation sheet
     * does). Empty css yields
     * an empty script (nothing registered). Pioneer builds it
     * for parity but never registers it (see the file header).
     */
    internal fun buildDocumentStartScript(
        css: String, offHosts: Set<String>
    ): String {
        if (css.isEmpty()) return ""
        val hosts = offHosts.joinToString(",") { h ->
            "\"" + h.filter { it != '"' && it != '\\' } + "\""
        }
        val esc = css
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("</", "<\\/")
        return "(function(){try{var OFF=[HOSTS];var h=location.hostname;var off=false;while(h){if(OFF.indexOf(h)>=0){off=true;break;}var d=h.indexOf('.');if(d<0)break;h=h.substring(d+1);}if(off)return;var s=document.createElement('style');s.textContent='CSS';(document.documentElement||document).appendChild(s);}catch(e){}})();"
            .replace("HOSTS", hosts)
            .replace("CSS", esc)
    }

    /**
     * Selector order for a page's sheet (beta.56): domain rules
     * first, then the user's own, then generic - so the size
     * budget in [cosmeticCss] trims generic selectors before it
     * ever touches the precise ones. Previously generic filled
     * the budget first and domain/user selectors were the ones
     * silently dropped on selector-heavy pages. $generichide
     * still suppresses generic only; #@# exceptions subtract
     * after all three sets are merged.
     */
    internal fun orderedSelectors(
        host: String,
        generic: List<String>,
        domain: Map<String, List<String>>,
        user: List<String>,
        excGeneric: Set<String>,
        excDomain: Map<String, List<String>>,
        hideOff: Set<String>
    ): LinkedHashSet<String> {
        val sels = LinkedHashSet<String>()
        for (h in hostChain(host)) domain[h]?.let { sels.addAll(it) }
        sels.addAll(user)
        if (!matches(hideOff, host)) sels.addAll(generic)
        sels.removeAll(excGeneric)
        for (h in hostChain(host)) excDomain[h]?.let { sels.removeAll(it) }
        return sels
    }

    /** The page sheet split in two, so the content script can
     *  apply `$generichide` itself: [CosmeticParts.precise]
     *  (domain + user selectors, minus #@# exceptions) is always
     *  injected; [CosmeticParts.generic] is injected only when
     *  [CosmeticParts.generichide] is false. The two parts chunk
     *  independently under the same 200,000-char total budget. */
    data class CosmeticParts(
        val preciseCss: String,
        val genericCss: String,
        val generichide: Boolean
    )

    fun cosmeticCssParts(pageHost: String): CosmeticParts {
        val host = pageHost.lowercase()
        if (host.isEmpty()) return CosmeticParts("", "", false)
        val hideOff = matches(genericHideOffHosts, host)
        val user = UserBlockRules.selectorsForHost(userCosmeticRules, host)
        val precise = LinkedHashSet<String>()
        for (h in hostChain(host)) domainSelectors[h]?.let { precise.addAll(it) }
        precise.addAll(user)
        precise.removeAll(cosmeticExcGeneric)
        for (h in hostChain(host)) cosmeticExcDomain[h]?.let { precise.removeAll(it) }
        val generic = LinkedHashSet<String>()
        if (!hideOff) {
            generic.addAll(genericSelectors)
            generic.removeAll(cosmeticExcGeneric)
            for (h in hostChain(host)) cosmeticExcDomain[h]?.let { generic.removeAll(it) }
        }
        val budget = 200_000
        val preciseCss = chunkCosmeticSelectors(precise, budget)
        val remaining = (budget - preciseCss.length).coerceAtLeast(0)
        val genericCss = chunkCosmeticSelectors(generic, remaining)
        return CosmeticParts(preciseCss, genericCss, hideOff)
    }

    /** True when [pageHost] (or a parent domain) is in the
     *  `$generichide` set: generic hiding is off on that page. */
    fun isGenericHideOff(pageHost: String): Boolean =
        matches(genericHideOffHosts, pageHost.lowercase())

    /** CSS that hides ad containers on [pageHost]; "" when nothing applies. */
    fun cosmeticCss(pageHost: String): String {
        val host = pageHost.lowercase()
        if (host.isEmpty()) return ""
        cssCache?.let { if (it.first == host) return it.second }
        val sels = orderedSelectors(
            host,
            genericSelectors,
            domainSelectors,
            UserBlockRules.selectorsForHost(userCosmeticRules, host),
            cosmeticExcGeneric,
            cosmeticExcDomain,
            genericHideOffHosts
        )
        if (sels.isEmpty()) return ""
        // No silent count cap on selectors. The only guard is a
        // generous size budget for the injected <style>; past it,
        // selectors are trimmed - generic first, since domain and
        // user selectors lead the order above - instead of
        // dropping the whole sheet. Rules are CHUNKED 25 selectors at a time:
        // one invalid selector voids only its own rule in CSS, so a
        // bad selector can never take the whole sheet down with it.
        val suffix = "{display:none!important}"
        val budget = 200_000
        val sb = StringBuilder()
        var chunk = StringBuilder()
        var inChunk = 0
        fun flushChunk() {
            if (chunk.isNotEmpty()) {
                sb.append(chunk).append(suffix)
                chunk = StringBuilder()
                inChunk = 0
            }
        }
        for (sel in sels) {
            val extra = if (inChunk == 0) sel.length else sel.length + 1
            if (sb.length + chunk.length + extra + suffix.length > budget) break
            if (inChunk > 0) chunk.append(',')
            chunk.append(sel)
            inChunk++
            if (inChunk >= 25) flushChunk()
        }
        flushChunk()
        if (sb.isEmpty()) return ""
        val css = sb.toString()
        cssCache = host to css
        return css
    }

    // ------------------------------------------------------------------
    // Compiled rule set for the probe background script
    // ------------------------------------------------------------------

    /** One scoped rule in JSON-friendly form, for `rules-update`. */
    data class ScopedRuleJson(
        val host: String,
        val pathPrefix: String?,
        val domains: List<String>?,
        val notDomains: List<String>?,
        val exception: Boolean,
        /** null = any party; true = third-party only; false = first-party only. */
        val thirdParty: Boolean?,
        val important: Boolean,
        val category: Int,
        val document: Boolean
    )

    /** The compiled rule set, exactly as the probe background
     *  script receives it in the `rules-update` native message
     *  (design §7), plus the Pioneer extensions the design's
     *  five-key payload does not name — [disabledHosts] (per-site
     *  exceptions; the background script skips those page hosts),
     *  [userNet] (the user's own net rules, which beat the
     *  compiled lists in [blockCategory] and must beat them in
     *  the JS mirror too), [trackerHosts], [docHosts] and
     *  [builtinHosts] (needed for exact category agreement).
     *  All extensions are reported to the coordinator for a §7
     *  amendment; the background script in this phase already
     *  reads them. */
    data class CompiledRules(
        val hosts: List<String>,
        val trackerHosts: List<String>,
        val docHosts: List<String>,
        val scoped: List<ScopedRuleJson>,
        val pathKeywords: List<String>,
        val exceptions: List<String>,
        val neverBlock: List<String>,
        val builtinHosts: List<String>,
        val disabledHosts: List<String>,
        val userNet: List<UserBlockRules.NetRuleJson>
    )

    /** Snapshot the live compiled state for `rules-update`.
     *  The caller ([BlockingObserver]) serialises it to JSON. */
    fun compiledRules(): CompiledRules {
        val scoped = ArrayList<ScopedRuleJson>()
        scopedRules.forEach { (host, rules) ->
            for (r in rules) {
                scoped.add(
                    ScopedRuleJson(
                        host = host,
                        pathPrefix = r.pathPrefix,
                        domains = r.domains?.toList(),
                        notDomains = r.notDomains?.toList(),
                        exception = r.exception,
                        thirdParty = r.thirdParty,
                        important = r.important,
                        category = r.category,
                        document = r.document
                    )
                )
            }
        }
        return CompiledRules(
            hosts = listHosts.toList(),
            trackerHosts = trackerHosts.toList(),
            docHosts = docHosts.toList(),
            scoped = scoped,
            pathKeywords = pathKeywords,
            exceptions = exceptionHosts.toList(),
            neverBlock = neverBlockHosts.toList(),
            builtinHosts = builtinHosts.toList(),
            disabledHosts = disabledHosts.toList(),
            userNet = userNetRules.map { UserBlockRules.toJson(it) }
        )
    }
}

/**
 * The user's own block rules (element picker, beta.18):
 * network rules from long-pressed links/images, and
 * per-site cosmetic selectors from picked elements.
 * Stored as one rule per line in a setting, tab
 * separated, so the logic stays pure-Kotlin and
 * unit-testable:
 *
 *   net \t HOST \t <host>          — everything from a host
 *   net \t URL \t <scheme://host/path> — one address, query
 *     and fragment stripped at save time so ad URLs
 *     with cache-busters still match on reload
 *   cos \t <page host> \t <selector> — hide on one site only
 *
 * A network rule never blocks a main-frame load
 * (that would break tapped links); a cosmetic rule
 * applies to its exact host only, never inherited
 * across hosts, since a selector that is safe on
 * one site can hide real content on another.
 *
 * Ported verbatim from Mariner's `UserBlockRules.kt`,
 * plus [NetRuleJson] / [toJson] so the user's net rules
 * can travel to the probe background script in
 * `rules-update` (they beat the compiled lists there
 * exactly as they do in [FilterEngine.blockCategory]).
 */
object UserBlockRules {
    const val NET_KEY = "userBlockNet"
    const val COSMETIC_KEY = "userBlockCosmetic"

    sealed interface NetRule {
        data class Host(val host: String) : NetRule
        data class Url(val url: String) : NetRule
    }

    data class CosmeticRule(val host: String, val selector: String)

    /** JSON-friendly form of a [NetRule] for `rules-update`. */
    data class NetRuleJson(val kind: String, val value: String)

    fun toJson(rule: NetRule): NetRuleJson = when (rule) {
        is NetRule.Host -> NetRuleJson("host", rule.host)
        is NetRule.Url -> NetRuleJson("url", rule.url)
    }

    fun fromJson(json: NetRuleJson): NetRule? = when (json.kind) {
        "host" -> NetRule.Host(json.value.lowercase())
        "url" -> NetRule.Url(json.value.lowercase())
        else -> null
    }

    fun parseNet(raw: String): List<NetRule> {
        val out = ArrayList<NetRule>()
        for (line in raw.lines()) {
            if (line.isBlank()) continue
            val parts = line.split("\t")
            if (parts.size != 3 || parts[0] != "net") continue
            when (parts[1]) {
                "HOST" -> {
                    val h = parts[2].lowercase()
                    if (h.isNotBlank()) out += NetRule.Host(h)
                }
                "URL" -> {
                    val u = parts[2].lowercase()
                    if (u.isNotBlank()) out += NetRule.Url(u)
                }
            }
        }
        return out
    }

    fun serializeNet(rules: List<NetRule>): String =
        rules.joinToString("\n") { rule ->
            when (rule) {
                is NetRule.Host -> "net\tHOST\t${rule.host}"
                is NetRule.Url -> "net\tURL\t${rule.url}"
            }
        }

    fun parseCosmetic(raw: String): List<CosmeticRule> {
        val out = ArrayList<CosmeticRule>()
        for (line in raw.lines()) {
            if (line.isBlank()) continue
            val parts = line.split("\t")
            if (parts.size != 3 || parts[0] != "cos") continue
            val host = parts[1].lowercase()
            val sel = parts[2]
            if (host.isNotBlank() && isSafeSelector(sel)) {
                out += CosmeticRule(host, sel)
            }
        }
        return out
    }

    fun serializeCosmetic(rules: List<CosmeticRule>): String =
        rules.joinToString("\n") { rule ->
            "cos\t${rule.host}\t${rule.selector}"
        }

    /** A selector is safe to drop into the injected
     * <style> sheet only when it cannot break out
     * of its own rule: no braces, no semicolons, no
     * angle brackets, no newlines. The picker builder
     * never generates those characters; this is the
     * backstop for anything stored by hand. */
    fun isSafeSelector(selector: String): Boolean {
        if (selector.isBlank()) return false
        return selector.none { c ->
            c == '{' || c == '}' || c == ';' || c == '<' ||
                c == '\n' || c == '\r' || c == '\t'
        }
    }

    /** The address part a URL rule matches: scheme,
     * host and path, lowercased, no query/fragment.
     * Plain strings, not android.net.Uri, so the
     * matcher stays JVM-testable; callers split the
     * real URL apart. */
    fun urlKey(scheme: String, host: String, path: String): String {
        return "${scheme.lowercase()}://${host.lowercase()}" +
            path.lowercase()
    }

    fun netMatches(
        rule: NetRule,
        scheme: String,
        hostRaw: String,
        pathRaw: String
    ): Boolean {
        val host = hostRaw.lowercase()
        if (host.isEmpty()) return false
        return when (rule) {
            is NetRule.Host ->
                host == rule.host || host.endsWith("." + rule.host)
            is NetRule.Url ->
                urlKey(scheme, host, pathRaw) == rule.url
        }
    }

    fun selectorsForHost(
        rules: List<CosmeticRule>,
        pageHost: String
    ): List<String> {
        val host = pageHost.lowercase()
        return rules.filter { it.host == host }.map { it.selector }
    }
}
