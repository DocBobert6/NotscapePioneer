package net.nspioneer.browser.engine

import android.net.Uri

/**
 * De-AMP (alpha.34, Brave-style Layer 1): rewrite AMP URLs to
 * their canonical form at navigation time, so a tap on a Google
 * search result (Chrome page, via Google Search Fixer Refreshed) lands on
 * the normal page instead of the AMP version.
 *
 * Pure, so the unit suite can pin it without an engine — same
 * shape as [HttpsOnly.kt]. [deAmpUrl] returns the canonical URL,
 * or null when the URL is not an AMP URL (the caller then lets
 * the load through untouched).
 *
 * Forms handled:
 * - Google AMP cache: `https://<host-dashes>.cdn.ampproject.org/c/s/<real-host>/<path>`
 *   and the `/v/s/` variant, plus the `/c/` and `/v/` forms (no
 *   `/s/`) used for http-origin pages — the scheme follows the
 *   `/s/`: present means the canonical page is https, absent
 *   means http. The bare `cdn.ampproject.org` host works too.
 *   The canonical host is read from the path, so the dash
 *   encoding of the cache subdomain never needs decoding.
 * - Google AMP viewer: `https://www.google.com/amp/s/<host>/<path>`
 *   (any Google search domain).
 * - Publisher AMP: a trailing `/amp` (or `/amp/`) suffix, and
 *   the `?amp=1` / `?output=amp` query parameters.
 *
 * The rewrite also drops Google's `amp_js_v` / `amp_gsa`
 * tracking parameters.
 *
 * Care with `/amp`: a path that IS just `/amp` (a section page,
 * not an article) is left alone — only a trailing `/amp` with a
 * real path before it is stripped. A middle `/amp` segment (e.g.
 * `/amp/page`) is never touched. The settings toggle is the
 * escape for anything else that misfires.
 *
 * Caveats (Brave notes the same): a DNS lookup for
 * `cdn.ampproject.org` may still happen before the redirect, but
 * the AMP page itself is never fetched. When a URL does not
 * encode its canonical (some publisher AMP pages), only the
 * canonical-link fallback — step two, not v1 — could recover
 * it; v1 covers the Search Fixer case, where the URL does
 * encode it.
 */

/** Setting key for the De-AMP toggle (default ON). */
const val DE_AMP_KEY = "deAmp"

/** Query parameters dropped on every rewrite. */
private val AMP_TRACKING_PARAMS = setOf("amp_js_v", "amp_gsa")

/** A Google search host: google.com, www.google.com, and the
 *  country domains (www.google.co.uk, google.de, ...). */
private val GOOGLE_SEARCH_HOST =
    Regex("""^([a-z0-9-]+\.)*google\.[a-z]{2,}(\.[a-z]{2})?$""")

/**
 * The canonical URL for an AMP URL, or null when [raw] is not
 * an AMP URL. Never throws: a malformed URL returns null.
 */
fun deAmpUrl(raw: String): String? {
    if (raw.isBlank()) return null
    val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
    val scheme = uri.scheme ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.lowercase() ?: return null
    val encodedPath = uri.encodedPath ?: ""
    val fragment = uri.encodedFragment

    // 1. Google AMP cache.
    if (host == "cdn.ampproject.org" || host.endsWith(".cdn.ampproject.org")) {
        val parts = encodedPath.split("/").filter { it.isNotEmpty() }
        // parts: [c|v, (s)?, realHost, ...rest]
        if (parts.size >= 3 && (parts[0] == "c" || parts[0] == "v")) {
            var idx = 1
            var secure = false
            if (parts[1] == "s") {
                secure = true
                idx = 2
            }
            if (parts.size > idx) {
                val realHost = parts[idx]
                val rest = parts.drop(idx + 1)
                val path = if (rest.isEmpty()) "/" else "/" + rest.joinToString("/")
                return buildUrl(
                    if (secure) "https" else "http",
                    realHost, path, stripAmpParams(uri.encodedQuery), fragment
                )
            }
        }
        return null
    }

    // 2. Google AMP viewer: /amp/s/<host>/<path>.
    if (GOOGLE_SEARCH_HOST.matches(host)) {
        val parts = encodedPath.split("/").filter { it.isNotEmpty() }
        if (parts.size >= 3 && parts[0] == "amp" && parts[1] == "s") {
            val realHost = parts[2]
            val rest = parts.drop(3)
            val path = if (rest.isEmpty()) "/" else "/" + rest.joinToString("/")
            return buildUrl(
                "https", realHost, path, stripAmpParams(uri.encodedQuery), fragment
            )
        }
    }

    // 3. Publisher AMP: trailing /amp, ?amp=1, ?output=amp.
    var path = encodedPath
    var changed = false
    if ((path.endsWith("/amp") || path.endsWith("/amp/")) &&
        path != "/amp" && path != "/amp/"
    ) {
        path = path.removeSuffix("/").removeSuffix("/amp")
        if (path.isEmpty()) path = "/"
        changed = true
    }
    val query = stripAmpParams(uri.encodedQuery)
    if (query != uri.encodedQuery) changed = true
    if (!changed) return null
    val authority = uri.encodedAuthority ?: return null
    return buildUrl(scheme, authority, path, query, fragment)
}

/**
 * The query string with AMP parameters removed, or null when
 * there is no query left. Everything else is kept verbatim, in
 * order. Removed: `amp` (bare, `=1`, `=true`, `=yes`),
 * `output=amp`, and the `amp_js_v` / `amp_gsa` tracking
 * parameters. A value of `amp=0` is left alone — it means "not
 * AMP", not "AMP".
 */
private fun stripAmpParams(encodedQuery: String?): String? {
    if (encodedQuery.isNullOrEmpty()) return encodedQuery
    val kept = encodedQuery.split("&").filter { pair ->
        val eq = pair.indexOf('=')
        val name = if (eq < 0) pair else pair.substring(0, eq)
        val value = if (eq < 0) "" else pair.substring(eq + 1)
        when (name) {
            "amp" -> value !in setOf("", "1", "true", "yes")
            "output" -> value != "amp"
            in AMP_TRACKING_PARAMS -> false
            else -> true
        }
    }
    return if (kept.isEmpty()) null else kept.joinToString("&")
}

private fun buildUrl(
    scheme: String,
    authority: String,
    path: String,
    query: String?,
    fragment: String?,
): String {
    val sb = StringBuilder()
    sb.append(scheme).append("://").append(authority)
    sb.append(if (path.isEmpty()) "/" else path)
    if (!query.isNullOrEmpty()) sb.append('?').append(query)
    if (!fragment.isNullOrEmpty()) sb.append('#').append(fragment)
    return sb.toString()
}
