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
import net.nspioneer.browser.engine.HTTPS_EXCEPTIONS_KEY
import net.nspioneer.browser.engine.isHttpsException
import net.nspioneer.browser.engine.withHttpsException
import net.nspioneer.browser.engine.withoutHttpsException
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
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
    val desktopMode: Boolean = false,
    /** Root scroll offset in CSS px at save time (Mariner
     *  Store.kt TabSnapshot.scrollY). Restored through the
     *  probe when no session state exists for the tab
     *  (Batch 2). */
    val scrollY: Int = 0,
    /** GeckoSession.SessionState JSON (Batch 2): history,
     *  scroll position, zoom and form data for this tab.
     *  "" means URL-only restore (a pre-alpha.6 save, or a
     *  tab whose state never pushed). */
    val sessionState: String = ""
)

// ---- Phase 3: downloads (Mariner Store.kt:23-47, ported verbatim) ----
data class DownloadRecord(
    val id: Long, val title: String, val url: String, val fileName: String,
    var dmId: Long, var status: String, val addedAt: Long,
    // True for media downloads (the offline library); plain file/image
    // downloads go to the Downloads folder and stay out of the library.
    val media: Boolean = false,
    // Download progress 0–100, or -1 when unknown.
    var progress: Int = -1,
    // Where a finished download actually lives: a MediaStore content://
    // URI for public downloads on modern Android, or an absolute path
    // as a fallback. "" while the legacy location (setting-based file
    // path) is the only record.
    var contentUri: String = "",
    // True when the download started from a private tab: the record and
    // its file are wiped with the private profile and never shown in the
    // Downloads or Offline lists.
    val isPrivate: Boolean = false,
) {
    // Transient size/speed detail, in the class body so it stays out of
    // equals, copy and the persisted document. -1 means unknown.
    var bytesDownloaded: Long = -1
    var totalBytes: Long = -1
    var speedBps: Long = -1
}

/** The private-downloads gate (Mariner Store.kt:180, ported
 *  verbatim). Private downloads stay hidden until the user
 *  explicitly confirms, in this session only. */
class PrivateDownloadsGate internal constructor() {
    private val unlockedState = mutableStateOf(false)
    /** True once the user confirms, this session only. */
    val unlocked: Boolean by unlockedState
    fun confirm() { unlockedState.value = true }
    fun hide() { unlockedState.value = false }
}

/** The media download for [url], if any — the match behind
 *  each playlist row's download / offline state. Media only:
 *  a plain file download of the same URL is not an offline
 *  copy of the video. (Mariner Store.kt:192.) */
fun findMediaDownload(
    downloads: List<DownloadRecord>, url: String
): DownloadRecord? =
    downloads.firstOrNull { it.media && it.url == url }

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
    private val downloadsFile = File(storeDir, "downloads.json")

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
        BOOKMARKS, FOLDERS, HISTORY, FAVOURITES, SITE_SETTINGS, SETTINGS, SESSION, DOWNLOADS
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

    // ---- Phase 3: downloads (Mariner Store.kt:23-47, ported verbatim) ----
    val downloads: SnapshotStateList<DownloadRecord> = mutableStateListOf()

    /** Bumped by progress-only download writes, so the
     *  Downloads / Offline screens recompose on a tick
     *  without bumping the global [revision] (Mariner
     *  `downloadProgressRev`, audit P-M5). */
    var downloadProgressRev by mutableStateOf(0)

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

    /** Mirrors Mariner `restoreHistory` (undo): the exact
     *  objects, ids and order are kept, then the list is
     *  sorted newest-first. Batch 3: history undo used to
     *  re-add via [addHistory], which minted a new id and a
     *  fresh timestamp and so moved the entry. */
    fun restoreHistory(items: List<HistoryEntry>) {
        synchronized(lock) {
            for (h in items) {
                if (history.none { it.id == h.id }) history.add(h)
            }
            history.sortByDescending { it.visitedAt }
            mutated(Part.HISTORY)
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

    /** Hosts whose `SiteSetting.disableBlocking` is on — the
     *  per-site exception set the blocking bridge
     *  (`BlockingHost.disabledHosts`) pushes to the probe
     *  background script in `rules-update`. */
    fun disabledBlockingHosts(): Set<String> =
        synchronized(lock) {
            siteSettings.values
                .filter { it.disableBlocking }
                .map { it.host }
                .toSet()
        }

    fun putSiteSetting(setting: SiteSetting) {
        synchronized(lock) {
            siteSettings[setting.host] = setting
            mutated(Part.SITE_SETTINGS)
        }
    }

    /** Every host with a stored per-site setting — the
     *  per-site controls panel unions this with the hosts
     *  in history (Mariner SettingsScreen.kt:394-397). */
    fun siteHosts(): Set<String> =
        synchronized(lock) { siteSettings.keys.toSet() }

    /** Batch 4: per-site HTTPS-only exceptions (Mariner
     *  Store.kt:1180-1196, pure helpers in
     *  engine/HttpsOnly.kt). One comma-separated setting
     *  ("httpsExceptions") of lowercase hosts. */
    fun isHttpsException(host: String): Boolean =
        isHttpsException(setting(HTTPS_EXCEPTIONS_KEY, ""), host)

    fun setHttpsException(host: String, on: Boolean) {
        val raw = setting(HTTPS_EXCEPTIONS_KEY, "")
        putSetting(
            HTTPS_EXCEPTIONS_KEY,
            if (on) withHttpsException(raw, host)
            else withoutHttpsException(raw, host)
        )
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

    // ---------- Phase 3: downloads ----------
    // Storage: JSON file `downloads.json` under
    // `filesDir/pioneer-store/` (PHASE3-DESIGN.md §6).

    fun downloadsSnapshot(): List<DownloadRecord> =
        synchronized(lock) { downloads.toList() }

    /** Mirrors Mariner `addDownload`: newest first, with the
     *  same retention cap — terminal records older than 90
     *  days go, the list never keeps more than 500 entries;
     *  active downloads are never pruned and files on disk
     *  are never touched — only records. A pruned record
     *  leaves a `dltombstone:<id>` setting with its file
     *  name, so the file can still be removed deliberately. */
    fun addDownload(rec: DownloadRecord) {
        synchronized(lock) {
            downloads.add(0, rec)
            val cutoff = System.currentTimeMillis() - 90L * 24 * 3600 * 1000
            val terminal = setOf("complete", "failed", "cancelled")
            val pruned = downloads.filter {
                it.status in terminal && it.addedAt < cutoff
            }
            if (pruned.isNotEmpty()) {
                downloads.removeAll(pruned.toSet())
                pruned.forEach {
                    settings.remove("dlerror:${it.id}")
                    settings.remove("dljob:${it.id}")
                    settings["dltombstone:${it.id}"] = it.fileName
                }
                mutated(Part.SETTINGS)
            }
            if (downloads.size > 500) {
                val excess = downloads.filter { it.status in terminal }
                    .takeLast(downloads.size - 500)
                if (excess.isNotEmpty()) {
                    downloads.removeAll(excess.toSet())
                    excess.forEach {
                        settings.remove("dlerror:${it.id}")
                        settings.remove("dljob:${it.id}")
                        settings["dltombstone:${it.id}"] = it.fileName
                    }
                    mutated(Part.SETTINGS)
                }
            }
            mutated(Part.DOWNLOADS)
        }
    }

    /** Persist [rec]'s current fields, matched by id.
     *  The record object itself is mutated in place by
     *  the download engines (status / progress / dmId /
     *  contentUri are `var`s on the record); this call
     *  only writes the change to disk. */
    fun updateDownload(rec: DownloadRecord) {
        synchronized(lock) {
            if (downloads.any { it.id == rec.id }) mutated(Part.DOWNLOADS)
        }
    }

    /** Mariner-shaped status update used by the download
     *  engines: set status / dmId / progress on the record
     *  for [id] and persist. A structural change (status
     *  or dmId) saves the file and bumps [revision]; a
     *  progress-only change only bumps [downloadProgressRev].
     *  (Extra beyond the §6 contract text — the contract's
     *  `updateDownload(rec)` cannot express a progress tick
     *  without the caller mutating the record first, and the
     *  engines tick from worker threads.) */
    fun updateDownloadState(
        id: Long, status: String, dmId: Long? = null, progress: Int? = null
    ) {
        synchronized(lock) {
            val rec = downloads.firstOrNull { it.id == id } ?: return
            var structural = false
            if (rec.status != status) structural = true
            if (dmId != null && rec.dmId != dmId) structural = true
            rec.status = status
            if (dmId != null) rec.dmId = dmId
            if (progress != null && rec.progress != progress) {
                rec.progress = progress
                downloadProgressRev++
            }
            if (structural) mutated(Part.DOWNLOADS)
        }
    }

    /** Progress-only update (Mariner `updateDownloadProgress`):
     *  engines tick per percent; only 5% steps reach the
     *  store (0 and 100 are multiples of 5, so the start and
     *  the finish still land). Never resurrects a paused,
     *  cancelled or finished record: the tick is a no-op
     *  unless the record is currently downloading/queued.
     *  (Extra beyond the §6 contract text, same reason as
     *  [updateDownloadState].) */
    fun updateDownloadProgress(id: Long, progress: Int) {
        if (progress % 5 != 0) return
        synchronized(lock) {
            val current = downloads.firstOrNull { it.id == id }?.status
            if (current != "downloading" && current != "queued") return
            updateDownloadState(id, current, progress = progress)
        }
    }

    /** Transient byte/speed detail for a download — never
     *  persisted. Bumps only [downloadProgressRev], and only
     *  when values move. (Extra beyond the §6 contract text;
     *  Mariner `updateDownloadBytes`.) */
    fun updateDownloadBytes(
        id: Long, downloaded: Long, total: Long, speedBps: Long
    ) {
        synchronized(lock) {
            val rec = downloads.firstOrNull { it.id == id } ?: return
            if (rec.bytesDownloaded == downloaded &&
                rec.totalBytes == total && rec.speedBps == speedBps
            ) return
            rec.bytesDownloaded = downloaded
            rec.totalBytes = total
            rec.speedBps = speedBps
            downloadProgressRev++
        }
    }

    /** Rename a download's file (e.g. an HLS join that turned
     *  out to be an MP4, not the .ts the record was created
     *  with). Replaces the record so the new file name is
     *  persisted. (Extra beyond the §6 contract text;
     *  Mariner `updateDownloadFileName`.) */
    fun updateDownloadFileName(id: Long, name: String) {
        synchronized(lock) {
            val i = downloads.indexOfFirst { it.id == id }
            if (i >= 0) {
                val r = downloads[i]
                downloads[i] = DownloadRecord(
                    r.id, r.title, r.url, name, r.dmId, r.status, r.addedAt,
                    r.media, r.progress, r.contentUri, r.isPrivate
                )
                mutated(Part.DOWNLOADS)
            }
        }
    }

    /** Record where a finished download actually lives
     *  (MediaStore content:// URI or absolute path).
     *  (Extra beyond the §6 contract text;
     *  Mariner `updateDownloadUri`.) */
    fun updateDownloadUri(id: Long, uri: String) {
        synchronized(lock) {
            downloads.firstOrNull { it.id == id }?.let { it.contentUri = uri }
            mutated(Part.DOWNLOADS)
        }
    }

    /** Record only — files stay (Mariner rule: multi-select
     *  delete removes records, never files). */
    fun removeDownload(id: Long) {
        synchronized(lock) {
            if (downloads.removeAll { it.id == id }) mutated(Part.DOWNLOADS)
        }
    }

    /** "Clear completed, keep files": records only — files
     *  on disk are never touched. */
    fun clearCompletedDownloads(media: Boolean) {
        synchronized(lock) {
            val gone = downloads.filter { it.status == "complete" && it.media == media }
            if (gone.isEmpty()) return
            gone.forEach {
                downloads.remove(it)
                settings.remove("dldowngrade:${it.id}")
            }
            mutated(Part.DOWNLOADS, Part.SETTINGS)
        }
    }

    // ---------- Phase 3: site permissions + autoplay ----------
    // (Mariner Store.kt:1135-1149.) Decision strings:
    // "allow" | "deny" | "ask" (unset). Stored as
    // setting("siteperm:$host:$resource"). The engine ALSO
    // mirrors content decisions into Gecko's
    // StorageController; this store is the source of truth
    // for what Settings shows, because
    // StorageController.getPermissions is unreliable in
    // private mode (bug 1723280).
    fun sitePermission(host: String, resource: String): String =
        setting("siteperm:$host:$resource", "ask")

    fun putSitePermission(host: String, resource: String, decision: String) {
        putSetting("siteperm:$host:$resource", decision)
    }

    // Autoplay: "" (unset → global) | "allow" | "block" | "muted".
    // Stored as setting("siteautoplay:$host").
    fun siteAutoplay(host: String): String =
        setting("siteautoplay:$host", "")

    fun putSiteAutoplay(host: String, mode: String) {
        if (mode.isBlank()) removeSetting("siteautoplay:$host")
        else putSetting("siteautoplay:$host", mode)
    }

    // ---------- Phase 3: private-downloads gate ----------
    // (Mariner LibraryScreens.kt:2219+.) Private downloads
    // stay hidden until the user explicitly confirms, in
    // this session only — the gate starts locked on every
    // launch and is never persisted.
    val privateDownloadsGate: PrivateDownloadsGate = PrivateDownloadsGate()

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

    // ---------- recently closed ----------

    /** Mirrors Mariner Store.saveClosedTabs (Batch 2):
     *  snapshots of NORMAL tabs at close time, newest first,
     *  capped at 20. Private tabs are never recorded. Entries
     *  older than 30 days drop on load; explicit clear wipes
     *  the key. */
    fun saveClosedTabs(tabs: List<TabSnapshot>) {
        synchronized(lock) {
            val arr = JSONArray()
            tabs.take(20).forEach {
                arr.put(
                    JSONObject().put("url", it.url)
                        .put("title", it.title)
                        .put("at", System.currentTimeMillis())
                )
            }
            settings["closedTabs"] = arr.toString()
            mutated(Part.SETTINGS)
        }
    }

    fun loadClosedTabs(): List<TabSnapshot> = synchronized(lock) {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        runCatching {
            val arr = JSONArray(settings["closedTabs"] ?: "")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val at = o.optLong("at", 0L)
                if (at != 0L && at < cutoff) return@mapNotNull null
                val url = o.optString("url")
                if (url.isBlank()) return@mapNotNull null
                TabSnapshot(url, o.optString("title"))
            }
        }.getOrDefault(emptyList())
    }

    fun clearClosedTabs() {
        synchronized(lock) {
            settings.remove("closedTabs")
            mutated(Part.SETTINGS)
        }
    }

    // ---------- favicons ----------

    // In-memory favicon cache, so a host fetched once is never
    // fetched again in this process (Mariner's engineFaviconCache).
    private val faviconMemoryCache = ConcurrentHashMap<String, Bitmap>()

    /** Mirrors Mariner `saveFavicon`: persist one host's icon,
     *  scaled to 96x96, as `favicons/<host.hashCode()>.png`. */
    fun saveFavicon(host: String, bitmap: Bitmap) {
        if (host.isBlank()) return
        runCatching {
            val scaled = Bitmap.createScaledBitmap(bitmap, 96, 96, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            File(File(filesDir, "favicons"), host.hashCode().toString() + ".png")
                .apply { parentFile?.mkdirs() }
                .writeBytes(out.toByteArray())
            if (scaled != bitmap) scaled.recycle()
        }
    }

    /** Returns the cached file only — the same
     *  `favicons/<host.hashCode()>.png` location Mariner's
     *  `loadFavicon` reads. */
    fun loadFavicon(host: String): Bitmap? {
        if (host.isBlank()) return null
        val file = File(File(filesDir, "favicons"), host.hashCode().toString() + ".png")
        if (!file.exists()) return null
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull()
    }

    /** Batch 3: fetch a host's favicon and cache it (memory +
     *  disk), mirroring Mariner's `loadEngineFavicon`
     *  (SettingsScreen.kt): one GET of `https://<host>/favicon.ico`
     *  with short timeouts, decoded, then saved via
     *  [saveFavicon] so [loadFavicon] finds it next time.
     *  GeckoView supplies no favicon path (Bug 1770989, still
     *  open), so fetching is the only way tiles and rows ever
     *  show a real icon. A real ICO container decodes via
     *  [IcoDecoder] — `BitmapFactory` alone returns null for
     *  ICO bytes. Blocking: call from Dispatchers.IO. Returns
     *  null on any failure, so callers fall back to the generic
     *  icon. */
    fun fetchFavicon(host: String): Bitmap? {
        if (host.isBlank()) return null
        faviconMemoryCache[host]?.let { return it }
        loadFavicon(host)?.let {
            faviconMemoryCache[host] = it
            return it
        }
        val fetched = runCatching {
            val conn = URL("https://$host/favicon.ico")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.setRequestProperty(
                "User-Agent", "Mozilla/5.0 (Linux; Android 14)"
            )
            conn.inputStream.use { stream ->
                decodeFaviconBytes(stream.readBytes())
            }
        }.getOrNull()
        if (fetched != null) {
            faviconMemoryCache[host] = fetched
            saveFavicon(host, fetched)
        }
        return fetched
    }

    /** Decode fetched favicon bytes: a PNG/JPEG/WebP decodes
     *  directly; a real ICO container decodes via [IcoDecoder]
     *  (largest entry — PNG-embedded entries go back through
     *  `BitmapFactory`, BMP entries become a Bitmap here). */
    private fun decodeFaviconBytes(bytes: ByteArray): Bitmap? {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
        val entry = extractLargestIcoEntry(bytes) ?: return null
        BitmapFactory.decodeByteArray(entry, 0, entry.size)?.let { return it }
        val decoded = parseIcoBmp(entry) ?: return null
        return Bitmap.createBitmap(
            decoded.pixels, decoded.width, decoded.height,
            Bitmap.Config.ARGB_8888
        )
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
        Part.DOWNLOADS -> downloadsFile
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
        loadDownloads()
        revision++
    }

    private fun loadDownloads() {
        val text = readText(downloadsFile) ?: return
        runCatching {
            val arr = JSONObject(text).optJSONArray("downloads") ?: return
            val items = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                if (url.isBlank()) return@mapNotNull null
                DownloadRecord(
                    o.optLong("id"), o.optString("title"), url,
                    o.optString("fileName"), o.optLong("dmId", -1),
                    o.optString("status", "failed"), o.optLong("t"),
                    o.optBoolean("media", false),
                    o.optInt("progress", -1),
                    o.optString("contentUri", ""),
                    o.optBoolean("isPrivate", false)
                )
            }
            downloads.addAll(items)
        }.onFailure { Log.w(TAG, "load downloads: ${it.javaClass.simpleName}") }
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
                TabSnapshot(
                    url, o.optString("title"), o.optBoolean("desktop", false),
                    o.optInt("scrollY", 0), o.optString("sessionState", "")
                )
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
                                .put("scrollY", it.scrollY)
                                .put("sessionState", it.sessionState)
                        )
                    }
                }
            ).toString()
        Part.DOWNLOADS -> JSONObject().put(
            "downloads",
            JSONArray().apply {
                downloads.forEach {
                    put(
                        JSONObject().put("id", it.id).put("title", it.title)
                            .put("url", it.url).put("fileName", it.fileName)
                            .put("dmId", it.dmId).put("status", it.status)
                            .put("t", it.addedAt).put("media", it.media)
                            .put("progress", it.progress)
                            .put("contentUri", it.contentUri)
                            .put("isPrivate", it.isPrivate)
                    )
                }
            }
        ).toString()
    }

    /** Mirrors Mariner `newId`: millisecond time plus a
     *  random suffix, so ids are unique and roughly
     *  time-ordered. */
    private fun newId(): Long = System.currentTimeMillis() * 1000 + (0..999).random()

    // ---------- backup ----------
    /** Full data backup as JSON (alpha.10, Mariner's
     *  exportBackup shape, Pioneer areas only): bookmarks,
     *  folders, favourites, history, downloads, site
     *  settings and settings, under the same keys the
     *  store files use (so the document stays readable
     *  next to a Mariner backup). There is no vault in
     *  Pioneer and no encrypted variant — Pioneer has no
     *  EncryptedBackup. */
    fun exportBackup(): String {
        synchronized(lock) {
            val root = JSONObject()
            root.put("schemaVersion", 1)
            for (part in listOf(
                Part.BOOKMARKS, Part.FOLDERS, Part.HISTORY,
                Part.FAVOURITES, Part.SITE_SETTINGS, Part.SETTINGS,
                Part.DOWNLOADS
            )) {
                val obj = JSONObject(serialize(part))
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    root.put(k, obj.get(k))
                }
            }
            return root.toString(2)
        }
    }

    /** Restores a backup produced by [exportBackup]. A
     *  missing section keeps what is on the device; a
     *  present section replaces it wholesale. Returns
     *  false when the document is not a backup at all. */
    fun importBackup(json: String): Boolean {
        synchronized(lock) {
            return runCatching {
                val root = JSONObject(json)
                require(
                    root.has("bookmarks") || root.has("history") ||
                        root.has("favourites")
                )
                root.optJSONArray("bookmarks")?.let { arr ->
                    val items = (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val url = o.optString("url")
                        if (url.isBlank()) return@mapNotNull null
                        Bookmark(
                            o.optLong("id"), o.optString("title"), url,
                            o.optLong("t"), o.optString("f")
                        )
                    }
                    bookmarks.clear()
                    bookmarks.addAll(items)
                }
                root.optJSONArray("bookmarkFolders")?.let { arr ->
                    val items = (0 until arr.length()).mapNotNull { i ->
                        arr.optString(i).takeIf { it.isNotBlank() }
                    }
                    bookmarkFolders.clear()
                    bookmarkFolders.addAll(items)
                }
                root.optJSONArray("history")?.let { arr ->
                    val items = (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val url = o.optString("url")
                        if (url.isBlank()) return@mapNotNull null
                        HistoryEntry(
                            o.optLong("id"), o.optString("title"), url,
                            o.optLong("t")
                        )
                    }
                    history.clear()
                    history.addAll(items)
                }
                root.optJSONArray("favourites")?.let { arr ->
                    val items = (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val url = o.optString("url")
                        if (url.isBlank()) return@mapNotNull null
                        Bookmark(
                            o.optLong("id"), o.optString("title"), url,
                            o.optLong("t")
                        )
                    }
                    favourites.clear()
                    favourites.addAll(items)
                }
                root.optJSONArray("downloads")?.let { arr ->
                    val items = (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val url = o.optString("url")
                        if (url.isBlank()) return@mapNotNull null
                        DownloadRecord(
                            o.optLong("id"), o.optString("title"), url,
                            o.optString("fileName"), o.optLong("dmId", -1),
                            o.optString("status", "failed"), o.optLong("t"),
                            o.optBoolean("media", false),
                            o.optInt("progress", -1),
                            o.optString("contentUri", ""),
                            o.optBoolean("isPrivate", false)
                        )
                    }
                    downloads.clear()
                    downloads.addAll(items)
                }
                root.optJSONObject("siteSettings")?.let { obj ->
                    val keys = obj.keys()
                    val parsed = mutableMapOf<String, SiteSetting>()
                    while (keys.hasNext()) {
                        val host = keys.next()
                        val s = obj.optJSONObject(host) ?: continue
                        parsed[host] = SiteSetting(
                            host,
                            s.optBoolean("js", true),
                            s.optBoolean("img", false),
                            s.optBoolean("pop", false),
                            s.optBoolean("nb", false),
                            s.optBoolean("bp", false),
                            s.optBoolean("c3", false)
                        )
                    }
                    siteSettings.clear()
                    siteSettings.putAll(parsed)
                }
                root.optJSONObject("settings")?.let { obj ->
                    val keys = obj.keys()
                    val parsed = mutableMapOf<String, String>()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        parsed[k] = obj.optString(k)
                    }
                    settings.clear()
                    settings.putAll(parsed)
                }
                mutated(
                    Part.BOOKMARKS, Part.FOLDERS, Part.HISTORY,
                    Part.FAVOURITES, Part.SITE_SETTINGS, Part.SETTINGS,
                    Part.DOWNLOADS
                )
                flush()
                true
            }.getOrDefault(false)
        }
    }

    /** Erase all user data (alpha.10, Mariner's
     *  clearAllData, Pioneer areas only): bookmarks,
     *  folders, history, favourites, downloads list,
     *  site settings, saved tab session and closed tabs,
     *  plus the download job/error/tombstone settings.
     *  General settings (theme, home page, …) survive,
     *  as in Mariner. Files on disk are never touched —
     *  only the records. The caller also wipes engine
     *  data (cookies/cache/site data) separately. */
    fun clearAllData() {
        synchronized(lock) {
            bookmarks.clear(); bookmarkFolders.clear(); history.clear()
            favourites.clear(); downloads.clear(); siteSettings.clear()
            settings.keys.filter {
                it.startsWith("dljob:") || it.startsWith("dlerror:") ||
                    it.startsWith("dldowngrade:") ||
                    it.startsWith("dltombstone:") || it == "closedTabs"
            }.forEach { settings.remove(it) }
            tabSession.clear()
            mutated(
                Part.BOOKMARKS, Part.FOLDERS, Part.HISTORY,
                Part.FAVOURITES, Part.SITE_SETTINGS, Part.SETTINGS,
                Part.SESSION, Part.DOWNLOADS
            )
            flush()
        }
    }
}
