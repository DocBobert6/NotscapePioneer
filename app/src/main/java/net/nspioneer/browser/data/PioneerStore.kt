package net.nspioneer.browser.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.EnumSet
import java.util.concurrent.Executors

/**
 * Pioneer data store (Phase 2).
 *
 * A fresh, local-only store: JSON files under `filesDir/pioneer-store/`,
 * one file per collection plus one settings file. No Room, no KSP —
 * the AGP 9 toolchain has no Kotlin plugin and Room would need KSP,
 * so JSON keeps the Phase 2 build risk low (see PHASE2-DESIGN.md,
 * "PioneerStore API").
 *
 * Behaviour is copied from Mariner's `AppStore` (Store.kt) wherever
 * the contract says so; each copied behaviour cites the Mariner
 * function it mirrors. Where the contract narrows or changes Mariner
 * behaviour, the difference is called out in the comment.
 *
 * Persistence: mutations mark their file(s) dirty, bump [revision],
 * and schedule a debounced save (500 ms). The shell must also call
 * [flush] from `Activity.onPause()` so nothing is lost if the
 * process dies soon after pausing. Saves are crash-safe: write to a
 * temp file, keep the previous good copy as `.bak`, then rename.
 * A missing, blank, or corrupt file loads as an empty collection —
 * a fresh profile is just an empty store.
 */
data class Bookmark(
    val id: Long, val title: String, val url: String, val createdAt: Long,
    /** Folder path ("/"-separated), or "" for unfiled. */
    val folder: String = ""
)

data class HistoryEntry(val id: Long, val title: String, val url: String, val visitedAt: Long)

data class SiteSetting(
    val host: String, val javaScript: Boolean = true,
    val blockImages: Boolean = false, val allowPopups: Boolean = false,
    val disableBlocking: Boolean = false, val blockPopups: Boolean = false,
    val allowThirdPartyCookies: Boolean = false
)

data class TabSnapshot(
    val url: String, val title: String,
    val desktopMode: Boolean = false
)

/** Parent path of [path] ("" for a top-level folder).
 *  Copied from Mariner Store.kt: `folderParent`. */
fun folderParent(path: String): String =
    path.substringBeforeLast("/", "")

/** Last segment of [path].
 *  Copied from Mariner Store.kt: `folderName`. */
fun folderName(path: String): String =
    path.substringAfterLast("/")

/** Child path of [parent] + [name]; top level when
 *  [parent] is "". Copied from Mariner Store.kt: `folderChild`. */
fun folderChild(parent: String, name: String): String =
    if (parent.isBlank()) name.trim()
    else "$parent/${name.trim()}"

/** True when [name] is a usable single folder segment:
 *  non-blank and free of the path separator.
 *  Copied from Mariner Store.kt: `isFolderSegment`. */
fun isFolderSegment(name: String): Boolean =
    name.isNotBlank() && "/" !in name

/** The folders that must exist for [path] to exist:
 *  the path itself plus every ancestor.
 *  Copied from Mariner Store.kt: `folderAncestors`. */
fun folderAncestors(path: String): List<String> {
    val segs = path.split("/").filter { it.isNotBlank() }
    return (1..segs.size).map { segs.take(it).joinToString("/") }
}

/** Direct children of [parent], as full paths, sorted.
 *  Copied from Mariner Store.kt: `folderChildren`. */
fun folderChildren(folders: List<String>, parent: String): List<String> =
    folders.filter { folderParent(it) == parent }
        .distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

/** Rename folder [old] (and every folder below it) to
 *  [newPath]: descendants are re-rooted under it.
 *  Copied from Mariner Store.kt: `renameFolderPath`. */
fun renameFolderPath(
    folders: List<String>, old: String, newPath: String
): List<String> =
    folders.map {
        when {
            it == old -> newPath
            it.startsWith("$old/") -> newPath + it.removePrefix(old)
            else -> it
        }
    }

/** Re-file every bookmark under [old] (or a folder
 *  below it) under [newPath].
 *  Copied from Mariner Store.kt: `refileBookmarksAfterRename`. */
fun refileBookmarksAfterRename(
    bookmarks: List<Bookmark>, old: String, newPath: String
): List<Bookmark> =
    bookmarks.map {
        when {
            it.folder == old -> it.copy(folder = newPath)
            it.folder.startsWith("$old/") ->
                it.copy(folder = newPath + it.folder.removePrefix(old))
            else -> it
        }
    }

class PioneerStore private constructor(context: Context) {

    private val filesDir: File = context.filesDir
    private val storeDir = File(filesDir, "pioneer-store").apply { mkdirs() }

    private val bookmarksFile = File(storeDir, "bookmarks.json")
    private val foldersFile = File(storeDir, "bookmark-folders.json")
    private val historyFile = File(storeDir, "history.json")
    private val favouritesFile = File(storeDir, "favourites.json")
    private val siteSettingsFile = File(storeDir, "site-settings.json")
    private val settingsFile = File(storeDir, "settings.json")
    private val sessionFile = File(storeDir, "tab-session.json")

    private val lock = Any()

    /** Single writer thread for debounced saves, mirroring
     *  Mariner's store-writer pattern: disk writes happen
     *  off the store lock, so a save never stalls the UI. */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pioneer-store-writer").apply { isDaemon = true }
    }

    /** Debounced save: posted on the main looper, fired at
     *  most 500 ms after the last mutation. */
    private val saveHandler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { writer.execute { writeDirtyNow() } }

    /** Which files have unsaved changes. Guarded by [lock]. */
    private enum class Part {
        BOOKMARKS, FOLDERS, HISTORY, FAVOURITES, SITE_SETTINGS, SETTINGS, SESSION
    }
    private val dirty: EnumSet<Part> = EnumSet.noneOf(Part::class.java)

    /** Bumped on every mutation so Compose recomposes.
     *  (Contract type: public `var`; only this class writes it.) */
    var revision by mutableStateOf(0)

    val bookmarks: SnapshotStateList<Bookmark> = mutableStateListOf()
    val bookmarkFolders: SnapshotStateList<String> = mutableStateListOf()
    val history: SnapshotStateList<HistoryEntry> = mutableStateListOf()
    val favourites: SnapshotStateList<Bookmark> = mutableStateListOf()
    val settings: MutableMap<String, String> = mutableStateMapOf()
    val tabSession: MutableList<TabSnapshot> = mutableStateListOf()
    var tabSessionActive: Int = 0

    /** Per-site settings live in a plain map (internal only —
     *  the shell reads them through [siteSetting]); the
     *  snapshot pattern Mariner used for hot-path reads is
     *  unnecessary here, nothing reads them per-request. */
    private val siteSettings = mutableMapOf<String, SiteSetting>()

    init {
        loadAll()
    }

    companion object {
        private const val TAG = "PioneerStore"
        private const val SAVE_DELAY_MS = 500L

        @Volatile private var instance: PioneerStore? = null
        fun get(context: Context): PioneerStore =
            instance ?: synchronized(this) {
                instance ?: PioneerStore(context.applicationContext).also { instance = it }
            }
    }

    // ---------- generic settings ----------

    fun setting(key: String, default: String): String =
        synchronized(lock) { settings[key] ?: default }

    /** Mirrors Mariner `putSetting`: always stores and saves. */
    fun putSetting(key: String, value: String) {
        synchronized(lock) {
            settings[key] = value
            mutated(Part.SETTINGS)
        }
    }

    /** Mirrors Mariner `removeSetting`: saves only when
     *  the key was actually present. */
    fun removeSetting(key: String) {
        synchronized(lock) {
            if (settings.remove(key) != null) {
                mutated(Part.SETTINGS)
            }
        }
    }

    // ---------- bookmarks / history / favourites ----------

    fun isBookmarked(url: String): Boolean =
        synchronized(lock) { bookmarks.any { it.url == url } }

    /** Mirrors Mariner `toggleBookmark`: remove the existing
     *  row for the URL, or add a new one at the front (newest
     *  first). A blank title falls back to the URL. */
    fun toggleBookmark(title: String, url: String) {
        synchronized(lock) {
            val existing = bookmarks.firstOrNull { it.url == url }
            if (existing != null) bookmarks.remove(existing)
            else bookmarks.add(
                0,
                Bookmark(newId(), title.ifBlank { url }, url, System.currentTimeMillis())
            )
            mutated(Part.BOOKMARKS)
        }
    }

    fun removeBookmark(id: Long) {
        synchronized(lock) {
            bookmarks.removeAll { it.id == id }
            mutated(Part.BOOKMARKS)
        }
    }

    /** Mirrors Mariner `restoreBookmarks` (undo): the exact
     *  objects, ids and order are kept, then the list is
     *  sorted newest-first. */
    fun restoreBookmarks(items: List<Bookmark>) {
        synchronized(lock) {
            for (b in items) {
                if (bookmarks.none { it.id == b.id }) bookmarks.add(b)
            }
            bookmarks.sortByDescending { it.createdAt }
            mutated(Part.BOOKMARKS)
        }
    }

    /** Mirrors Mariner `updateBookmark`: edit title and URL
     *  in place. A blank URL is a no-op; a blank title falls
     *  back to the URL. */
    fun updateBookmark(id: Long, title: String, url: String) {
        val u = url.trim()
        if (u.isBlank()) return
        synchronized(lock) {
            val i = bookmarks.indexOfFirst { it.id == id }
            if (i >= 0) {
                bookmarks[i] = bookmarks[i].copy(
                    title = title.ifBlank { u }, url = u
                )
                mutated(Part.BOOKMARKS)
            }
        }
    }

    /** Mirrors Mariner `importBookmarks`: deduped by URL
     *  against everything already shelved, creating folders
     *  as needed. Returns how many were added.
     *
     *  One fix over Mariner's literal code: Mariner built
     *  its `known` URL set once before the loop, so two
     *  identical URLs inside one import batch were both
     *  added. Here the set grows as items are added, so a
     *  URL is added at most once per import. */
    fun importBookmarks(items: List<Triple<String, String, String>>): Int {
        var added = 0
        synchronized(lock) {
            val known = bookmarks.map { it.url }.toMutableSet()
            for ((title, url, folder) in items) {
                if (url.isBlank() || url in known) continue
                if (folder.isNotBlank()) {
                    for (ancestor in folderAncestors(folder)) {
                        if (bookmarkFolders.none {
                                it.equals(ancestor, ignoreCase = true)
                            }
                        ) bookmarkFolders.add(ancestor)
                    }
                }
                bookmarks.add(
                    Bookmark(
                        newId(), title.ifBlank { url }, url,
                        System.currentTimeMillis(), folder
                    )
                )
                known.add(url)
                added++
            }
            if (added > 0) mutated(Part.BOOKMARKS, Part.FOLDERS)
        }
        return added
    }

    // ---------- bookmark folders ----------

    /** Mirrors Mariner `addBookmarkFolder`: [name] is one
     *  segment — a "/" in it is rejected, paths are built,
     *  not typed. Duplicate (case-insensitive) is a no-op. */
    fun addBookmarkFolder(name: String, parent: String = "") {
        if (!isFolderSegment(name)) return
        val path = folderChild(parent, name)
        synchronized(lock) {
            if (bookmarkFolders.none { it.equals(path, ignoreCase = true) }) {
                bookmarkFolders.add(path)
                mutated(Part.FOLDERS)
            }
        }
    }

    /** Mirrors Mariner `renameBookmarkFolder`: rename the
     *  last segment of [old] to [newSegment]. The folder,
     *  every folder below it, and their bookmarks are
     *  re-rooted under the new path; a clash with an
     *  existing folder is a no-op, as is an unknown [old]
     *  or an invalid [newSegment]. */
    fun renameBookmarkFolder(old: String, newSegment: String) {
        if (!isFolderSegment(newSegment)) return
        val newPath = folderChild(folderParent(old), newSegment)
        synchronized(lock) {
            val idx = bookmarkFolders.indexOfFirst { it.equals(old, ignoreCase = true) }
            if (idx < 0) return@synchronized
            if (bookmarkFolders.any {
                    it.equals(newPath, ignoreCase = true) &&
                        !it.equals(old, ignoreCase = true)
                }
            ) {
                return@synchronized
            }
            val updated = renameFolderPath(bookmarkFolders, old, newPath)
            bookmarkFolders.clear()
            bookmarkFolders.addAll(updated)
            val refiled = refileBookmarksAfterRename(bookmarks, old, newPath)
            for (i in bookmarks.indices) bookmarks[i] = refiled[i]
            mutated(Part.FOLDERS, Part.BOOKMARKS)
        }
    }

    /** Mirrors Mariner `deleteBookmarkFolder`: the folder
     *  and every folder below it go; their bookmarks
     *  become Unfiled. */
    fun deleteBookmarkFolder(name: String) {
        synchronized(lock) {
            bookmarkFolders.removeAll {
                it.equals(name, ignoreCase = true) ||
                    it.startsWith("$name/", ignoreCase = true)
            }
            for (i in bookmarks.indices) {
                if (bookmarks[i].folder.equals(name, ignoreCase = true) ||
                    bookmarks[i].folder.startsWith("$name/", ignoreCase = true)
                ) {
                    bookmarks[i] = bookmarks[i].copy(folder = "")
                }
            }
            mutated(Part.FOLDERS, Part.BOOKMARKS)
        }
    }

    /** Mirrors Mariner `setBookmarkFolder`: move one
     *  bookmark into [folder] ("" for Unfiled), verbatim. */
    fun setBookmarkFolder(id: Long, folder: String) {
        synchronized(lock) {
            val i = bookmarks.indexOfFirst { it.id == id }
            if (i >= 0) {
                bookmarks[i] = bookmarks[i].copy(folder = folder)
                mutated(Part.BOOKMARKS)
            }
        }
    }

    /** Contract rule (narrower than Mariner `addHistory`):
     *  no-op for a blank URL, for a non-http(s) URL, or
     *  when the URL is the same as the last (most recent)
     *  entry. Mariner recorded every visit as its own row
     *  with no dedupe; Pioneer keeps one row per consecutive
     *  visit so a reload does not stack identical rows.
     *  New visits go at the front (newest first). */
    fun addHistory(title: String, url: String) {
        if (url.isBlank()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        synchronized(lock) {
            if (history.firstOrNull()?.url == url) return
            history.add(
                0,
                HistoryEntry(newId(), title.ifBlank { url }, url, System.currentTimeMillis())
            )
            mutated(Part.HISTORY)
        }
    }

    fun clearHistory() {
        synchronized(lock) {
            history.clear()
            mutated(Part.HISTORY)
        }
    }

    /** Mirrors Mariner `removeHistory`: saves only when an
     *  entry was actually removed. */
    fun removeHistory(id: Long) {
        synchronized(lock) {
            if (history.removeAll { it.id == id }) mutated(Part.HISTORY)
        }
    }

    /** Mirrors Mariner `clearHistoryOlderThan`: 0 clears
     *  everything, like [clearHistory]. */
    fun clearHistoryOlderThan(cutoffMs: Long) {
        synchronized(lock) {
            if (cutoffMs <= 0L) history.clear()
            else history.removeAll { it.visitedAt < cutoffMs }
            mutated(Part.HISTORY)
        }
    }

    /** Mirrors Mariner `toggleFavourite`: remove the existing
     *  row for the URL, or append a new one. A blank title
     *  falls back to the URL. */
    fun toggleFavourite(title: String, url: String) {
        synchronized(lock) {
            val existing = favourites.firstOrNull { it.url == url }
            if (existing != null) favourites.remove(existing)
            else favourites.add(
                Bookmark(newId(), title.ifBlank { url }, url, System.currentTimeMillis())
            )
            mutated(Part.FAVOURITES)
        }
    }

    fun isFavourite(url: String): Boolean =
        synchronized(lock) { favourites.any { it.url == url } }

    // ---------- site settings ----------

    fun siteSetting(host: String): SiteSetting =
        synchronized(lock) { siteSettings[host] ?: SiteSetting(host) }

    fun putSiteSetting(setting: SiteSetting) {
        synchronized(lock) {
            siteSettings[setting.host] = setting
            mutated(Part.SITE_SETTINGS)
        }
    }

    /** Per-site text-zoom factor, 1.0f when unset.
     *
     *  Stored under Mariner's key ("sitezoom:$host"), but
     *  as the Float factor string ("1.25") — Mariner stored
     *  an Int percent ("125") behind the same key, which the
     *  contract's Float API replaces. A stored 1.0f is
     *  unset (the key is removed), so the file only carries
     *  real overrides. */
    fun siteZoom(host: String): Float =
        setting("sitezoom:$host", "").toFloatOrNull() ?: 1.0f

    fun putSiteZoom(host: String, zoom: Float) {
        if (zoom <= 0f || zoom == 1.0f) removeSetting("sitezoom:$host")
        else putSetting("sitezoom:$host", zoom.toString())
    }

    /** Per-site desktop mode, false when unset. Unlike
     *  Mariner's nullable `siteDesktop` (null = unset),
     *  the contract is a plain Boolean, so false removes
     *  the key and reads back as the default. */
    fun siteDesktop(host: String): Boolean =
        setting("sitedesktop:$host", "") == "true"

    fun putSiteDesktop(host: String, desktop: Boolean) {
        if (desktop) putSetting("sitedesktop:$host", "true")
        else removeSetting("sitedesktop:$host")
    }

    // ---------- tab session ----------

    /** Mirrors Mariner `saveSession`: persist the open
     *  (non-private) tabs and which one is active. The
     *  active index is clamped into range; with no tabs it
     *  is 0. Private tabs are never passed here. */
    fun saveTabSession(tabs: List<TabSnapshot>, active: Int) {
        synchronized(lock) {
            tabSession.clear()
            tabSession.addAll(tabs)
            tabSessionActive = if (tabs.isEmpty()) 0
            else active.coerceIn(0, tabs.size - 1)
            mutated(Part.SESSION)
        }
    }

    // ---------- favicons ----------

    /** Phase 2: no favicon fetching. Returns the cached
     *  file only — the same `favicons/<host.hashCode()>.png`
     *  location Mariner's `loadFavicon` reads, so a later
     *  phase that captures favicons can reuse it — and
     *  null otherwise, so start-page tiles fall back to the
     *  title letter as Mariner already does. */
    fun loadFavicon(host: String): Bitmap? {
        if (host.isBlank()) return null
        val file = File(File(filesDir, "favicons"), host.hashCode().toString() + ".png")
        if (!file.exists()) return null
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull()
    }

    // ---------- bookmark HTML ----------

    /** Netscape-format export of every bookmark, in
     *  Mariner's exact format (see [BookmarkHtml]). */
    fun exportBookmarksHtml(): String =
        synchronized(lock) { BookmarkHtml.export(bookmarks.toList()) }

    /** Parse Netscape-format HTML and import it, deduped
     *  by URL. Returns how many bookmarks were added. */
    fun importBookmarksHtml(text: String): Int =
        importBookmarks(BookmarkHtml.parse(text))

    // ---------- saving ----------

    /**
     * Write every dirty file to disk NOW, on the calling
     * thread. The shell calls this from `Activity.onPause()`
     * — the debounced save alone is not enough, because the
     * process may die before the 500 ms timer fires. This
     * is an addition to the contract's API (the contract
     * lists no save/pause hook); it changes no listed
     * signature.
     */
    fun flush() {
        saveHandler.removeCallbacks(saveRunnable)
        writeDirtyNow()
    }

    /** Mark [parts] dirty, bump [revision], and (re)start
     *  the debounced save. Caller holds [lock]; the lock
     *  is re-entrant, so calling this inside a
     *  `synchronized(lock)` block is safe. */
    private fun mutated(vararg parts: Part) {
        synchronized(lock) {
            dirty.addAll(parts.toList())
            revision++
        }
        scheduleSave()
    }

    private fun scheduleSave() {
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, SAVE_DELAY_MS)
    }

    /** Serialize each dirty file (under the lock) and write
     *  it (off the lock). A file that fails to write stays
     *  out of the dirty set only if it serialized — a
     *  failed write is logged and retried on the next
     *  mutation; the in-memory state is unaffected. */
    private fun writeDirtyNow() {
        val texts: Map<Part, String>
        synchronized(lock) {
            if (dirty.isEmpty()) return
            texts = dirty.associateWith { serialize(it) }
            dirty.clear()
        }
        for ((part, text) in texts) {
            runCatching { writeAtomic(text, fileFor(part)) }
                .onFailure { Log.w(TAG, "save $part: ${it.javaClass.simpleName}") }
        }
    }

    private fun fileFor(part: Part): File = when (part) {
        Part.BOOKMARKS -> bookmarksFile
        Part.FOLDERS -> foldersFile
        Part.HISTORY -> historyFile
        Part.FAVOURITES -> favouritesFile
        Part.SITE_SETTINGS -> siteSettingsFile
        Part.SETTINGS -> settingsFile
        Part.SESSION -> sessionFile
    }

    /** Crash-safe save, copied from Mariner `writeAtomic`:
     *  write the new document to a temp file, keep the
     *  previous good document as .bak, then rename the
     *  temp over the real file. */
    private fun writeAtomic(text: String, target: File) {
        val tmp = File(target.path + ".tmp")
        val bak = File(target.path + ".bak")
        tmp.writeText(text)
        if (target.exists()) {
            bak.delete()
            target.renameTo(bak)
        }
        if (!tmp.renameTo(target)) {
            // Rename failed (shouldn't on the same
            // filesystem): fall back to a direct copy
            // rather than losing the save.
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    // ---------- loading ----------

    /** Load every file; a missing, blank, or corrupt file
     *  is an empty collection, never a crash. Each file is
     *  independent — one bad file cannot lose the rest. */
    private fun loadAll() {
        loadBookmarks()
        loadFolders()
        loadHistory()
        loadFavourites()
        loadSiteSettings()
        loadSettings()
        loadSession()
        revision++
    }

    private fun readText(file: File): String? =
        runCatching {
            if (!file.exists()) null else file.readText().takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun loadBookmarks() {
        val text = readText(bookmarksFile) ?: return
        runCatching {
            val arr = JSONObject(text).optJSONArray("bookmarks") ?: return
            val items = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isBlank()) return@mapNotNull null
                Bookmark(
                    o.optLong("id"), o.optString("title"), url,
                    o.optLong("t"), o.optString("f")
                )
            }
            bookmarks.addAll(items)
        }.onFailure { Log.w(TAG, "load bookmarks: ${it.javaClass.simpleName}") }
    }

    private fun loadFolders() {
        val text = readText(foldersFile) ?: return
        runCatching {
            val arr = JSONObject(text).optJSONArray("folders") ?: return
            val items = (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }
            bookmarkFolders.addAll(items)
        }.onFailure { Log.w(TAG, "load folders: ${it.javaClass.simpleName}") }
    }

    private fun loadHistory() {
        val text = readText(historyFile) ?: return
        runCatching {
            val arr = JSONObject(text).optJSONArray("history") ?: return
            val items = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isBlank()) return@mapNotNull null
                HistoryEntry(
                    o.optLong("id"), o.optString("title"), url, o.optLong("t")
                )
            }
            history.addAll(items)
        }.onFailure { Log.w(TAG, "load history: ${it.javaClass.simpleName}") }
    }

    private fun loadFavourites() {
        val text = readText(favouritesFile) ?: return
        runCatching {
            val arr = JSONObject(text).optJSONArray("favourites") ?: return
            val items = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isBlank()) return@mapNotNull null
                Bookmark(
                    o.optLong("id"), o.optString("title"), url, o.optLong("t")
                )
            }
            favourites.addAll(items)
        }.onFailure { Log.w(TAG, "load favourites: ${it.javaClass.simpleName}") }
    }

    private fun loadSiteSettings() {
        val text = readText(siteSettingsFile) ?: return
        runCatching {
            val obj = JSONObject(text).optJSONObject("siteSettings") ?: return
            val keys = obj.keys()
            while (keys.hasNext()) {
                val host = keys.next()
                val s = obj.optJSONObject(host) ?: continue
                siteSettings[host] = SiteSetting(
                    host,
                    s.optBoolean("js", true),
                    s.optBoolean("img", false),
                    s.optBoolean("pop", false),
                    s.optBoolean("nb", false),
                    s.optBoolean("bp", false),
                    s.optBoolean("c3", false)
                )
            }
        }.onFailure { Log.w(TAG, "load siteSettings: ${it.javaClass.simpleName}") }
    }

    private fun loadSettings() {
        val text = readText(settingsFile) ?: return
        runCatching {
            val obj = JSONObject(text).optJSONObject("settings") ?: return
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                settings[k] = obj.optString(k)
            }
        }.onFailure { Log.w(TAG, "load settings: ${it.javaClass.simpleName}") }
    }

    private fun loadSession() {
        val text = readText(sessionFile) ?: return
        runCatching {
            val root = JSONObject(text)
            val arr = root.optJSONArray("tabs") ?: return
            val items = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isBlank()) return@mapNotNull null
                TabSnapshot(url, o.optString("title"), o.optBoolean("desktop", false))
            }
            tabSession.addAll(items)
            tabSessionActive = if (items.isEmpty()) 0
            else root.optInt("active", 0).coerceIn(0, items.size - 1)
        }.onFailure { Log.w(TAG, "load session: ${it.javaClass.simpleName}") }
    }

    // ---------- serialization ----------
    // JSON key shapes mirror Mariner's `buildJsonLocked`
    // ("t" = epoch ms, "f" = folder, site-setting shorts
    // js/img/pop/nb/bp/c3) so the files stay readable next
    // to a Mariner document. Caller holds [lock].

    private fun serialize(part: Part): String = when (part) {
        Part.BOOKMARKS -> JSONObject().put(
            "bookmarks",
            JSONArray().apply {
                bookmarks.forEach {
                    put(
                        JSONObject().put("id", it.id).put("title", it.title)
                            .put("url", it.url).put("t", it.createdAt)
                            .put("f", it.folder)
                    )
                }
            }
        ).toString()
        Part.FOLDERS -> JSONObject().put(
            "folders",
            JSONArray().apply { bookmarkFolders.forEach { put(it) } }
        ).toString()
        Part.HISTORY -> JSONObject().put(
            "history",
            JSONArray().apply {
                history.forEach {
                    put(
                        JSONObject().put("id", it.id).put("title", it.title)
                            .put("url", it.url).put("t", it.visitedAt)
                    )
                }
            }
        ).toString()
        Part.FAVOURITES -> JSONObject().put(
            "favourites",
            JSONArray().apply {
                favourites.forEach {
                    put(
                        JSONObject().put("id", it.id).put("title", it.title)
                            .put("url", it.url).put("t", it.createdAt)
                    )
                }
            }
        ).toString()
        Part.SITE_SETTINGS -> JSONObject().put(
            "siteSettings",
            JSONObject().apply {
                siteSettings.forEach { (host, s) ->
                    put(
                        host,
                        JSONObject().put("js", s.javaScript)
                            .put("img", s.blockImages)
                            .put("pop", s.allowPopups)
                            .put("nb", s.disableBlocking)
                            .put("bp", s.blockPopups)
                            .put("c3", s.allowThirdPartyCookies)
                    )
                }
            }
        ).toString()
        Part.SETTINGS -> JSONObject().put(
            "settings",
            JSONObject().apply { settings.forEach { (k, v) -> put(k, v) } }
        ).toString()
        Part.SESSION -> JSONObject()
            .put("active", tabSessionActive)
            .put(
                "tabs",
                JSONArray().apply {
                    tabSession.forEach {
                        put(
                            JSONObject().put("url", it.url)
                                .put("title", it.title)
                                .put("desktop", it.desktopMode)
                        )
                    }
                }
            ).toString()
    }

    /** Mirrors Mariner `newId`: millisecond time plus a
     *  random suffix, so ids are unique and roughly
     *  time-ordered. */
    private fun newId(): Long = System.currentTimeMillis() * 1000 + (0..999).random()
}
