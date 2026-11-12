package net.nspioneer.browser.engine

import android.net.Uri

/**
 * HTTPS-only helpers (Batch 4, Mariner BrowserScreen.kt
 * `httpsUpgradeFor` + Store.kt `parseHttpsExceptions` /
 * `withHttpsException` / `withoutHttpsException` /
 * `isHttpsException`, ported verbatim).
 *
 * GeckoView 159 has NO public HTTPS-only API (verified
 * against the pinned AAR: `GeckoRuntimeSettings` carries
 * `mHttpsOnly` / `mHttpsOnlyPrivateMode` fields but no
 * public setter on the settings, the Builder, or
 * `RuntimeSettings`). So HTTPS-only is enforced at the
 * app layer: the load path upgrades, [GeckoEngine]
 * intercepts in-page loads, and a non-upgradeable host
 * gets the interstitial. Pure, so the unit suite can pin
 * it without an engine.
 */

/** Per-site HTTPS-only exceptions, stored as one
 *  comma-separated setting ("httpsExceptions") of lowercase
 *  hosts. */
const val HTTPS_EXCEPTIONS_KEY = "httpsExceptions"

fun parseHttpsExceptions(raw: String): Set<String> =
    raw.split(",").map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }.toSet()

fun withHttpsException(raw: String, host: String): String =
    (parseHttpsExceptions(raw) + host.trim().lowercase())
        .sorted().joinToString(",")

fun withoutHttpsException(raw: String, host: String): String =
    (parseHttpsExceptions(raw) - host.trim().lowercase())
        .sorted().joinToString(",")

fun isHttpsException(raw: String, host: String): Boolean =
    host.trim().lowercase() in parseHttpsExceptions(raw)

/** True when [host] can be upgraded at all: not localhost,
 *  not a `.local` name, not a bare IPv4 address. (Mariner
 *  `httpsUpgradeFor`'s guards, factored out so the shell
 *  and the engine agree.) */
fun isUpgradeableHost(host: String): Boolean {
    if (host.isBlank()) return false
    if (host == "localhost" || host.endsWith(".local")) return false
    if (host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return false
    return true
}

/** The https version of an http URL, or null when the URL
 *  is not an http URL, its host cannot be upgraded, or the
 *  host is in [httpOnlyHosts] (a previous upgrade failed
 *  there — Mariner `httpsUpgradeFor`, TabState.httpOnlyHosts). */
fun httpsUpgradeFor(raw: String, httpOnlyHosts: Set<String>): String? {
    val uri = Uri.parse(raw)
    if (uri.scheme != "http") return null
    val host = uri.host ?: return null
    if (!isUpgradeableHost(host)) return null
    if (host in httpOnlyHosts) return null
    return uri.buildUpon().scheme("https").build().toString()
}

/** True when [newUrl] is the same page as [currentUrl]
 *  ignoring only the fragment — a same-page anchor, which
 *  "open links in a new tab" must never split off. */
fun isAnchorLink(currentUrl: String, newUrl: String): Boolean {
    if (currentUrl.isBlank() || newUrl.isBlank()) return false
    return currentUrl.substringBefore("#") == newUrl.substringBefore("#")
}
