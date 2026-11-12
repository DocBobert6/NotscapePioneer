package net.nspioneer.browser.data

import android.content.Context
import java.io.File

/**
 * On-device storage accounting and housekeeping (Batch 4,
 * Mariner `data/StorageInfo.kt`, Pioneer areas only).
 * Mariner's Books / Read-aloud voices / Library database
 * areas do not exist in Pioneer; Pioneer's own areas are
 * the filter lists, the custom UI font, the wallpaper, the
 * site icons, and the store's own JSON files. Everything
 * here is local file work — call it off the main thread.
 */
object StorageInfo {

    data class Area(val label: String, val bytes: Long)

    fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) {
                total += runCatching { f.length() }.getOrDefault(0L)
            }
        }
        return total
    }

    /** The areas worth showing, largest first. Areas that
     * take no room are omitted, except the cache, which is
     * always listed because it can always be cleared. */
    fun measure(context: Context): List<Area> {
        val files = context.filesDir
        val raw = listOf(
            Area("Filter lists", dirSize(File(files, "filters"))),
            Area("Fonts", dirSize(File(files, "fonts"))),
            Area("Wallpaper", dirSize(File(files, "wallpaper"))),
            Area("Site icons", dirSize(File(files, "favicons"))),
            Area("App data", dirSize(File(files, "pioneer-store"))),
            Area("Cache", dirSize(context.cacheDir))
        )
        return raw.filter { it.bytes > 0 || it.label == "Cache" }
            .sortedByDescending { it.bytes }
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) {
            return String.format(java.util.Locale.US, "%.1f KB", kb)
        }
        val mb = kb / 1024.0
        if (mb < 1024) {
            return String.format(java.util.Locale.US, "%.1f MB", mb)
        }
        return String.format(
            java.util.Locale.US, "%.2f GB", mb / 1024.0
        )
    }

    /** Empties the app cache (and code cache); returns the
     * bytes freed. The cache holds temporary copies only —
     * clearing it is always safe. */
    fun clearCache(context: Context): Long {
        val before = dirSize(context.cacheDir) +
            dirSize(context.codeCacheDir)
        listOf(context.cacheDir, context.codeCacheDir)
            .forEach { dir ->
                runCatching {
                    dir.listFiles()?.forEach { child ->
                        if (child.isDirectory) {
                            child.deleteRecursively()
                        } else {
                            child.delete()
                        }
                    }
                }
            }
        val after = dirSize(context.cacheDir) +
            dirSize(context.codeCacheDir)
        return (before - after).coerceAtLeast(0L)
    }
}
