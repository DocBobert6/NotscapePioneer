package net.nspioneer.browser.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.nspioneer.browser.engine.CookieRequester
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Alpha.65 — the YouTube-only InnerTube route.
 *
 * YouTube's web client no longer serves segments as
 * range-GETs: since July 2026 it uses SABR/UMP (one
 * POST to serverAbrStreamingUrl, binary protobuf), so
 * Pioneer's webRequest/MIME detection sees no video
 * responses on YouTube. The fix is to ask YouTube's
 * InnerTube player endpoint AS A DIFFERENT CLIENT
 * that still returns an HLS (or DASH) manifest, then
 * feed that manifest into the EXISTING HLS/DASH path
 * (quality picker + runHlsJob / runDashJob).
 *
 * The ladder, first success wins (client constants are
 * data, in one place — YouTube rotates them):
 *  1. VISIONOS — returns an HLS manifest URL; needs
 *     only visitorData, the VISITOR_INFO1_LIVE cookie,
 *     obtained through the alpha.64 production cookie
 *     wiring (CookieRequester — the app asks the
 *     background script, which calls the same
 *     browser.cookies.getAll the alpha.63 probe proved
 *     works). No PO token, no JS player.
 *  2. ANDROID_VR — client 28, v1.65.10 pinned (yt-dlp:
 *     a version above 1.65 returns SABR-only; since
 *     2026-08-17 all formats are 403'd with 1.65.10,
 *     so this rung may be degraded — the ladder falls
 *     through to iOS).
 *  3. iOS — client 5, v21.26.4 (yt-dlp master as of
 *     2026-11-16; the research brief said 21.02.3,
 *     superseded by the current pin), pre-signed URLs.
 *
 * A rung succeeds only when its manifest FETCHES and
 * parses as a manifest (yt-dlp skips a manifest whose
 * `n` challenge is unsolved; a PO-gated manifest
 * 403s). The validation fetch runs under the same
 * conditions as the download job's own fetch (the
 * alpha.64 job cookie jar for the manifest host), so
 * a reported manifest is one the job can fetch too.
 * If every rung fails, NOTHING is reported — the
 * existing detection/download paths proceed exactly
 * as before, and other sites are never touched: the
 * trigger is a YouTube watch URL only, and the only
 * Kotlin changes outside this file are recognising
 * the extension-less InnerTube manifest shape
 * (MediaLog / Downloads / BlockingObserver) and the
 * player mime hint.
 *
 * Privacy: the visitorData value goes to youtube.com
 * only, as part of the player request — the same
 * identifier YouTube itself set, sent back to the
 * site that set it, exactly as the web client's own
 * InnerTube calls do. It is never logged, never
 * persisted, never put in Diagnostics. The POST
 * carries no cookies (HttpURLConnection sends none
 * unless a CookieManager is installed, and none is).
 */
object InnerTube {

    /** The player endpoint (yt-dlp's INNERTUBE_HOST
     *  default, www.youtube.com). */
    const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"

    /** The visitor cookie the VISIONOS client needs. */
    const val VISITOR_COOKIE = "VISITOR_INFO1_LIVE"

    /** Whole-ladder wall-clock bound: a hung rung must
     *  never hold the manifest past this. A timeout
     *  reports nothing — the safe fallback. */
    const val LADDER_TIMEOUT_MS = 90_000L

    data class Client(
        val key: String,
        val clientName: String,
        val clientVersion: String,
        val deviceMake: String,
        val deviceModel: String,
        val androidSdkVersion: Int?,
        val userAgent: String,
        val osName: String,
        val osVersion: String,
    )

    /** The ladder, in order. Client constants as data:
     *  when YouTube rotates a version, change the one
     *  line here. */
    val CLIENTS = listOf(
        Client(
            key = "visionos",
            clientName = "VISIONOS",
            clientVersion = "1.02",
            deviceMake = "Apple",
            deviceModel = "RealityDevice17,1",
            androidSdkVersion = null,
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            osName = "visionOS",
            osVersion = "26.5.23O471",
        ),
        Client(
            key = "android_vr",
            clientName = "ANDROID_VR",
            clientVersion = "1.65.10",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = 32,
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
                "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            osName = "Android",
            osVersion = "12L",
        ),
        Client(
            key = "ios",
            clientName = "IOS",
            clientVersion = "21.26.4",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            androidSdkVersion = null,
            userAgent = "com.google.ios.youtube/21.26.4 " +
                "(iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            osName = "iPhone",
            osVersion = "18.3.2.22D82",
        ),
    )

    data class LadderResult(
        val manifestUrl: String,
        val mime: String,
        val clientKey: String,
    )

    /** The network surface, so the ladder is testable
     *  without a network. The real transport is built
     *  per ladder run (one cookie jar per run). */
    interface Transport {
        /** The player response JSON for one rung, or
         *  null when the call failed. */
        fun postPlayer(client: Client, videoId: String, visitorData: String?): String?

        /** The manifest text for a candidate URL, or
         *  null when the fetch failed. */
        fun fetchManifest(url: String): String?
    }

    class RealTransport(private val pageUrl: String) : Transport {
        private val jar = Downloads.JobCookieJar()

        override fun postPlayer(
            client: Client, videoId: String, visitorData: String?
        ): String? = runCatching {
            Downloads.postJsonText(
                PLAYER_URL,
                buildPlayerRequest(client, videoId, visitorData),
                referer = pageUrl,
                userAgent = client.userAgent,
            )
        }.getOrNull()

        override fun fetchManifest(url: String): String? = runCatching {
            Downloads.fetchText(url, pageUrl, jar.headerFor(url))
        }.getOrNull()
    }

    private val videoIdPattern = Regex("""[0-9A-Za-z_-]{11}""")
    private val vParamPattern = Regex("""[?&#]v=([0-9A-Za-z_-]{11})(?:[&#]|$)""")
    private val pathIdPattern =
        Regex("""/(?:embed|v|e|shorts|live)/([0-9A-Za-z_-]{11})(?:[/?#]|$)""")

    /** True for a YouTube host (the ladder's trigger
     *  set, briefed: youtube.com, www/m., youtu.be,
     *  youtube-nocookie.com, music.youtube.com). */
    fun isYouTubeHost(host: String): Boolean {
        val h = host.lowercase().removeSuffix(".")
        return h == "youtube.com" || h.endsWith(".youtube.com") ||
            h == "youtu.be" ||
            h == "youtube-nocookie.com" || h.endsWith(".youtube-nocookie.com")
    }

    /** The 11-char video id of a YouTube watch URL, or
     *  null when the URL is not a watch page. Covers
     *  /watch?v= (and /?v=, watch_popup), youtu.be/<id>,
     *  and /embed/ /v/ /e/ /shorts/ /live/ paths. Pure. */
    fun extractVideoId(pageUrl: String): String? {
        val host = runCatching { java.net.URI(pageUrl).host?.lowercase() }
            .getOrNull() ?: return null
        if (!isYouTubeHost(host)) return null
        if (host == "youtu.be") {
            val seg = runCatching { java.net.URI(pageUrl).path }
                .getOrNull()?.trim('/')?.substringBefore('/') ?: return null
            return seg.takeIf { videoIdPattern.matches(it) }
        }
        vParamPattern.find(pageUrl)?.groupValues?.getOrNull(1)?.let { return it }
        return pathIdPattern.find(pageUrl)?.groupValues?.getOrNull(1)
    }

    /** The player-request JSON for one rung. The
     *  checkok params are top-level, as yt-dlp sends
     *  them; visitorData rides in the client object
     *  when the cookie was available. Pure. */
    fun buildPlayerRequest(
        client: Client, videoId: String, visitorData: String?
    ): String {
        val clientJson = JSONObject().apply {
            put("clientName", client.clientName)
            put("clientVersion", client.clientVersion)
            put("deviceMake", client.deviceMake)
            put("deviceModel", client.deviceModel)
            client.androidSdkVersion?.let { put("androidSdkVersion", it) }
            put("userAgent", client.userAgent)
            put("osName", client.osName)
            put("osVersion", client.osVersion)
            visitorData?.let { put("visitorData", it) }
        }
        return JSONObject().apply {
            put("context", JSONObject().put("client", clientJson))
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }.toString()
    }

    /** (hlsManifestUrl, dashManifestUrl) from a player
     *  response, each null when absent. Malformed JSON
     *  yields (null, null) — never a throw. Pure. */
    fun extractManifests(playerJson: String): Pair<String?, String?> {
        return runCatching {
            val streaming = JSONObject(playerJson).optJSONObject("streamingData")
                ?: return null to null
            val hls = streaming.optString("hlsManifestUrl")
                .takeIf { it.isNotBlank() }
            val dash = streaming.optString("dashManifestUrl")
                .takeIf { it.isNotBlank() }
            hls to dash
        }.getOrDefault(null to null)
    }

    /** True when fetched text is an HLS master/media
     *  playlist. Pure. */
    fun isHlsManifestText(text: String): Boolean =
        text.trimStart().startsWith("#EXTM3U")

    /** True when fetched text is a DASH manifest. Pure. */
    fun isDashManifestText(text: String): Boolean =
        text.contains("<MPD")

    /**
     * One ladder run: VISIONOS → ANDROID_VR → iOS.
     * Returns the first manifest that fetches and
     * parses, or null when every rung failed. The
     * visitor cookie is asked once, up front, through
     * the production cookie wiring; an unavailable
     * cookie falls through the ladder, never fails it.
     * Suspends on IO; never throws.
     */
    suspend fun runLadder(
        videoId: String,
        pageUrl: String,
        transport: Transport,
        cookieProvider: (String) -> List<Pair<String, String>> =
            CookieRequester::requestCookies,
    ): LadderResult? = withTimeoutOrNull(LADDER_TIMEOUT_MS) {
        val visitorData = runCatching {
            cookieProvider(pageUrl)
                .firstOrNull { it.first == VISITOR_COOKIE }?.second
        }.getOrNull()
        for (client in CLIENTS) {
            val json = transport.postPlayer(client, videoId, visitorData)
                ?: continue
            val (hls, dash) = extractManifests(json)
            val candidate = hls?.let { it to "application/x-mpegurl" }
                ?: dash?.let { it to "application/dash+xml" }
                ?: continue
            val text = transport.fetchManifest(candidate.first) ?: continue
            val valid = if (candidate.second == "application/x-mpegurl") {
                isHlsManifestText(text)
            } else {
                isDashManifestText(text)
            }
            if (valid) return@withTimeoutOrNull LadderResult(
                manifestUrl = candidate.first,
                mime = candidate.second,
                clientKey = client.key,
            )
        }
        null
    }

    /** Per-tab bookkeeping: the video id whose manifest
     *  was already reported, and the (tab, video) pair
     *  with a ladder in flight. A failed ladder leaves
     *  no mark, so a later navigation retries; a
     *  navigation away clears the mark, so coming back
     *  re-runs. */
    private val lastReported = ConcurrentHashMap<Int, String>()
    private val running = ConcurrentHashMap<Int, String>()

    /** Scope for ladder runs (IO). Replaceable in
     *  tests. */
    internal var scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Transport factory, so tests can run the ladder
     *  against a fake. Production builds the real
     *  transport per ladder run. */
    internal var transportFactory: (String) -> Transport = { RealTransport(it) }

    /**
     * The engine calls this on every committed
     * top-level navigation (full load or SPA — both
     * flow through onLocationChange). A YouTube watch
     * URL starts the ladder for that tab; anything
     * else clears the tab's mark. [isStillOn] must
     * answer whether the tab is still on [videoId]
     * when the ladder completes; [report] receives
     * the validated manifest (tab id, URL, MIME).
     * Fire-and-forget: never blocks navigation.
     */
    fun onNavigate(
        tabId: Int,
        pageUrl: String,
        isStillOn: (tabId: Int, videoId: String) -> Boolean,
        report: (tabId: Int, url: String, mime: String) -> Unit,
    ) {
        val videoId = extractVideoId(pageUrl)
        if (videoId == null) {
            lastReported.remove(tabId)
            return
        }
        // The mark belongs to the video the tab was on:
        // navigating to a different video clears it, so
        // coming back re-runs (MediaLog was cleared on
        // the navigation too). A failed ladder leaves no
        // mark, so a later navigation retries.
        val prev = lastReported[tabId]
        if (prev == videoId) return
        if (prev != null) lastReported.remove(tabId)
        if (running[tabId] == videoId) return
        running[tabId] = videoId
        scope.launch {
            try {
                val result = runLadder(
                    videoId, pageUrl, transportFactory(pageUrl)
                ) ?: return@launch
                if (!isStillOn(tabId, videoId)) return@launch
                lastReported[tabId] = videoId
                report(tabId, result.manifestUrl, result.mime)
            } finally {
                running.remove(tabId, videoId)
            }
        }
    }
}
