package net.nspioneer.browser.engine

/**
 * The only extensions Pioneer may install, by name (Robert's
 * settled rule — no general store, no AMO browsing, no "find
 * more extensions" link). Each entry carries everything the
 * install path needs: the AMO add-on ID, the direct XPI
 * download URL, the display name shown in the install prompt
 * and the UI, and the one-line description shown in
 * onboarding and Settings.
 *
 * Google Search Fixer Refreshed (AMO id 2850695, GUID
 * {abea9bb3-7bd0-48bc-88b1-39f0560744d6}, v2.0, last updated
 * 2024-06-14, MPL-2.0, by TheDoggyBrad Software Lab): "Override
 * the user-agent string presented to Google Search pages to
 * receive the search experience shown to Chrome." A maintained
 * fork of Thomas Wisniewski's original Google Search Fixer
 * (AMO id 869140, v1.6, last updated 2020-10-06) — the fork
 * fixes the upstream not working on images.google.com,
 * ipv4.google.com and ipv6.google.com, and presents the device
 * as a Pixel 8. The original is NOT the approved entry: it is
 * stale, and recent AMO reviews report it broken/freezing on
 * current Firefox mobile. Installed from the same direct-XPI
 * download path as uBlock Origin; the AMO /android/ listing
 * note ("unavailable on your platform") concerns the web
 * listing page, not the XPI download.
 */
data class ApprovedExtension(
    val id: String,
    val amoUrl: String,
    val displayName: String,
    val description: String,
)

object ApprovedExtensions {
    val UBLOCK_ORIGIN = ApprovedExtension(
        id = "uBlock0@raymondhill.net",
        amoUrl = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi",
        displayName = "uBlock Origin",
        description = "Blocks ads and trackers, on-device.",
    )
    val GOOGLE_SEARCH_FIXER = ApprovedExtension(
        id = "{abea9bb3-7bd0-48bc-88b1-39f0560744d6}",
        amoUrl = "https://addons.mozilla.org/firefox/downloads/latest/google-search-fixer-refreshed/latest.xpi",
        displayName = "Google Search Fixer Refreshed",
        description = "Shows Google Search the Chrome experience.",
    )

    /** The full set, in onboarding order. Nothing else is installable. */
    val ALL: List<ApprovedExtension> = listOf(UBLOCK_ORIGIN, GOOGLE_SEARCH_FIXER)
}
