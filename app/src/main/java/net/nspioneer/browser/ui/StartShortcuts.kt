package net.nspioneer.browser.ui

import net.nspioneer.browser.data.PioneerStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Start-page shortcuts (Batch 4, Mariner `emu/StartShortcuts.kt`,
 * Pioneer scope only). Mariner pins four library sections, eight
 * consoles and individual games; the consoles and games are
 * games scope and stay out of Pioneer, and the playlists /
 * bookshelf sections are Phase 3/4 feeds Pioneer does not have.
 * What Pioneer pins: individual sites (a bookmark, a favourite,
 * or a custom URL) and the Offline library section. Stored as
 * one JSON object under "startShortcuts":
 * {"sections":[…], "sites":[…]} — the same key and the same
 * sites shape as Mariner, so the shape stays compatible.
 * Reads tolerate garbage (bad JSON, unknown ids) by dropping
 * what doesn't parse — a corrupt pin list hides pins, never
 * crashes the start page.
 */
object StartShortcuts {
    const val KEY = "startShortcuts"

    data class Pins(
        val sections: List<String> = emptyList(),
        val sites: List<String> = emptyList()
    )

    /** The only pinnable section in Pioneer: the Offline library. */
    fun sectionIds(): List<String> = listOf("library")

    fun sectionLabel(id: String): String = when (id) {
        "library" -> "Offline library"
        else -> id
    }

    fun load(lookup: (String, String) -> String): Pins {
        val raw = lookup(KEY, "")
        if (raw.isBlank()) return Pins()
        return runCatching {
            val obj = JSONObject(raw)
            Pins(
                sections = strings(obj, "sections")
                    .filter { it in sectionIds() },
                sites = strings(obj, "sites")
                    .filter {
                        it.startsWith("http://") || it.startsWith("https://")
                    }
            )
        }.getOrDefault(Pins())
    }

    private fun strings(obj: JSONObject, key: String): List<String> {
        val arr = obj.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull {
            arr.optString(it).takeIf { s -> s.isNotBlank() }
        }
    }

    fun save(store: PioneerStore, pins: Pins) {
        val obj = JSONObject()
        obj.put("sections", JSONArray(pins.sections))
        obj.put("sites", JSONArray(pins.sites))
        store.putSetting(KEY, obj.toString())
    }

    private fun toggle(list: List<String>, id: String): List<String> =
        if (id in list) list - id else list + id

    fun toggleSection(store: PioneerStore, id: String) {
        val pins = load(store::setting)
        save(store, pins.copy(sections = toggle(pins.sections, id)))
    }

    fun toggleSite(store: PioneerStore, url: String) {
        val pins = load(store::setting)
        save(store, pins.copy(sites = toggle(pins.sites, url)))
    }
}
