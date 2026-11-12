package net.nspioneer.browser.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONTokener

/**
 * Phase 3 media detection — the per-tab media log.
 *
 * Two routes feed it, merged by URL (design §8.3):
 *  - DOM half: the probe content script's media scan (probe.js),
 *    delivered as `media-probe` items via Extensions.kt ->
 *    GeckoEngine.noteProbeMedia -> [noteDom] (wiring: Worker B).
 *  - Net half: the background-script `webRequest` observer
 *    (`media-net`, Worker A) -> [inspect].
 *
 * Detection limits, stated plainly (design §8.3, §10):
 *  - MSE / blob streams have no URL. The DOM half sees only
 *    `blob:` for them, which is excluded here by design. Only
 *    the `webRequest` observer can see the manifest / segment
 *    URLs, and those are transient and signed — playable as
 *    pass-through, never stable identifiers. Direct-URL recovery
 *    for MSE playback is impossible in principle.
 *  - Observed segment URLs are recorded as-is; the player and
 *    the downloader must treat them as pass-through (no re-fetch
 *    without the page's cookies).
 */
data class DetectedMedia(
    val url: String,
    val kind: String, // "video" | "audio" | "page"
    val label: String,
    val source: String = "dom", // "dom" | "net" | "session"
)

/** True for URLs that are HTML embed/player pages rather than media files. */
fun isEmbedPageUrl(url: String): Boolean {
    val u = url.lowercase()
    return u.contains("youtube.com/embed/") || u.contains("youtube-nocookie.com/embed/") ||
        u.contains("youtube.com/watch") || u.contains("youtu.be/") ||
        u.contains("player.vimeo.com/video/") || u.contains("dailymotion.com/embed/") ||
        u.contains("twitch.tv/") && u.contains("/embed")
}

/**
 * Parses a probe DOM-scan result into [DetectedMedia]. The probe
 * sends a plain JSON array of {url, kind, label} objects; a
 * double-encoded string (Mariner's evaluateJavascript shape) is
 * also accepted, so the same parser works for either transport.
 * Embed-page URLs are reclassified to kind "page" — they are
 * never offered to the player.
 */
fun parseDetectedMedia(raw: String?): List<DetectedMedia> {
    if (raw.isNullOrBlank() || raw == "null") return emptyList()
    return runCatching {
        val arr: JSONArray = runCatching { JSONTokener(raw).nextValue() as JSONArray }
            .getOrElse {
                val inner = JSONTokener(raw).nextValue() as? String ?: return emptyList()
                JSONArray(inner)
            }
        val list = mutableListOf<DetectedMedia>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val url = o.getString("url")
            var kind = o.optString("kind", "video")
            if ((kind == "video" || kind == "audio") && isEmbedPageUrl(url)) kind = "page"
            list.add(DetectedMedia(url, kind, o.optString("label"), source = "dom"))
        }
        list
    }.getOrElse { emptyList() }
}

object MediaLog {
    private val byTab = mutableStateMapOf<Int, List<DetectedMedia>>()
    var revision by mutableStateOf(0)
        private set

    private val streamPattern = Regex(
        """\.(m3u8|mpd|mp4|webm|mkv|mov|mp3|m4a|aac|ogg|opus|flac|wav|m4v|ts)(\?|#|$)""",
        RegexOption.IGNORE_CASE
    )
    private val audioPattern = Regex(
        """\.(mp3|m4a|aac|ogg|opus|flac|wav)(\?|#|$)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * The net half (Mariner `MediaLog.inspect`): one `media-net`
     * observation from the background `webRequest` observer.
     * `blob:` excluded; deduped per tab; kind audio vs video by
     * extension; label "stream (HLS)" / "stream (DASH)".
     */
    fun inspect(tabId: Int, url: String) {
        if (!streamPattern.containsMatchIn(url)) return
        if (url.contains("blob:")) return
        val lower = url.lowercase()
        val kind = if (audioPattern.containsMatchIn(lower)) "audio" else "video"
        val current = byTab[tabId] ?: emptyList()
        if (current.any { it.url == url }) return
        val label = when {
            lower.contains(".m3u8") -> "stream (HLS)"
            lower.contains(".mpd") -> "stream (DASH)"
            else -> ""
        }
        byTab[tabId] = current + DetectedMedia(url, kind, label, source = "net")
        revision++
    }

    /** One DOM-scan item (probe `media-probe`), merged by URL. */
    fun noteDomItem(tabId: Int, kind: String, url: String, label: String = "") {
        noteDom(tabId, listOf(DetectedMedia(url, kind, label, source = "dom")))
    }

    /**
     * The DOM half, merged with the net half: `detected =
     * (dom + net).distinctBy { url }`. A DOM entry never evicts a
     * net entry for the same URL (the net entry keeps its
     * source/label); embed pages are reclassified to "page".
     */
    fun noteDom(tabId: Int, items: List<DetectedMedia>) {
        val current = byTab[tabId] ?: emptyList()
        var merged = current
        var changed = false
        for (raw in items) {
            if (raw.url.isBlank() || raw.url.startsWith("blob:")) continue
            val kind =
                if ((raw.kind == "video" || raw.kind == "audio") && isEmbedPageUrl(raw.url)) "page"
                else raw.kind
            val item = raw.copy(kind = kind, source = "dom")
            if (merged.none { it.url == item.url }) {
                merged = merged + item
                changed = true
            }
        }
        if (changed) {
            byTab[tabId] = merged
            revision++
        }
    }

    /** Merged DOM + net list for a tab, distinct by URL. */
    fun forTab(tabId: Int): List<DetectedMedia> = byTab[tabId] ?: emptyList()

    fun clear(tabId: Int) {
        if (byTab.remove(tabId) != null) revision++
    }

    /** First detected video on the tab, or null (toolbar long-press path). */
    fun firstVideo(items: List<DetectedMedia>): DetectedMedia? =
        items.firstOrNull { it.kind == "video" }
}
