package net.nspioneer.browser.engine

/**
 * Custom-search-URL validation (Batch 4, Mariner
 * `browser/CleanLink.kt` `SearchTemplate`, ported
 * verbatim). Pure, so Settings can pin it without a store.
 */
object SearchTemplate {

    /** Returns an error message, or null when [raw] is usable. */
    fun validate(raw: String): String? {
        val u = raw.trim()
        if (u.isEmpty()) return "Enter a search URL."
        val lower = u.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("data:") ||
            lower.startsWith("file:")
        ) {
            return "That kind of URL can't be used for search."
        }
        if (!lower.startsWith("https://")) {
            return "The search URL must start with https://"
        }
        if (!u.contains("%s")) {
            return "The URL must contain %s where the search terms go."
        }
        return null
    }

    fun isValid(raw: String): Boolean = validate(raw) == null
}
