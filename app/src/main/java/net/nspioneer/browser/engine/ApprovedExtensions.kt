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
 * Google Search Fixer (AMO id 869140, GUID
 * {58c32ac4-0d6c-4d6f-ae2c-96aaf8ffcb66}, v1.6, last updated
 * 2020-10-06, MPL-2.0): "Override the user-agent string
 * presented to Google Search pages to receive the search
 * experience shown to Chrome." Installed from the same
 * direct-XPI download path as uBlock Origin; the AMO
 * /android/ listing note ("unavailable on your platform")
 * concerns the web listing page, not the XPI download.
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
        id = "{58c32ac4-0d6c-4d6f-ae2c-96aaf8ffcb66}",
        amoUrl = "https://addons.mozilla.org/firefox/downloads/latest/google-search-fixer/latest.xpi",
        displayName = "Google Search Fixer",
        description = "Shows Google Search the Chrome experience.",
    )

    /** The full set, in onboarding order. Nothing else is installable. */
    val ALL: List<ApprovedExtension> = listOf(UBLOCK_ORIGIN, GOOGLE_SEARCH_FIXER)
}
