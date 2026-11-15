package net.nspioneer.browser.ui

import net.nspioneer.browser.data.Bookmark
import net.nspioneer.browser.data.DownloadRecord
import net.nspioneer.browser.data.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P-A2 (alpha.41): the pure filter + sort behind the
 * virtualized Library lists. The `items(shown, key = …)`
 * leaves are Compose code and cannot run on the JVM; these
 * functions are the behaviour they encode — the remembered
 * lists must be byte-identical to the inline computations
 * they replace, or rows change order, vanish, or land in
 * the wrong list.
 */
class LibraryVirtualizationTest {

    private fun bm(id: Long, title: String, createdAt: Long, folder: String = "") =
        Bookmark(id, title, "https://example.com/$id", createdAt, folder)

    private fun hist(id: Long, title: String, visitedAt: Long) =
        HistoryEntry(id, title, "https://example.com/$id", visitedAt)

    private fun rec(
        id: Long, title: String, addedAt: Long,
        media: Boolean = false, isPrivate: Boolean = false
    ) = DownloadRecord(
        id, title, "https://example.com/$id", "$title.bin",
        dmId = -1, status = "complete", addedAt = addedAt,
        media = media, isPrivate = isPrivate
    )

    // ---- filterBookmarks ----

    @Test
    fun bookmarksAllFolderKeepsEverythingNewestFirst() {
        val list = listOf(
            bm(1, "a", 1000, "Work"), bm(2, "b", 3000), bm(3, "c", 2000, "Work")
        )
        assertEquals(
            listOf(2L, 3L, 1L),
            filterBookmarks(list, null, "", "newest").map { it.id }
        )
    }

    @Test
    fun bookmarksFolderFilter() {
        val list = listOf(
            bm(1, "a", 1000, "Work"), bm(2, "b", 3000), bm(3, "c", 2000, "Work/Sub")
        )
        assertEquals(listOf(1L), filterBookmarks(list, "Work", "", "newest").map { it.id })
        assertEquals(listOf(2L), filterBookmarks(list, "", "", "newest").map { it.id })
    }

    @Test
    fun bookmarksQueryMatchesTitleOrUrlCaseInsensitive() {
        val list = listOf(
            bm(1, "Alpha", 1000), bm(2, "beta", 2000), bm(3, "Gamma", 3000)
        )
        assertEquals(
            listOf(1L),
            filterBookmarks(list, null, "ALP", "newest").map { it.id }
        )
        assertEquals(
            listOf(2L),
            filterBookmarks(list, null, "example.com/2", "newest").map { it.id }
        )
    }

    @Test
    fun bookmarksSortOldestAndTitle() {
        val list = listOf(
            bm(1, "banana", 3000), bm(2, "Apple", 1000), bm(3, "cherry", 2000)
        )
        assertEquals(
            listOf(2L, 3L, 1L),
            filterBookmarks(list, null, "", "oldest").map { it.id }
        )
        assertEquals(
            listOf(2L, 1L, 3L),
            filterBookmarks(list, null, "", "title").map { it.id }
        )
        // Unknown sort falls back to newest, as the inline code did.
        assertEquals(
            listOf(1L, 3L, 2L),
            filterBookmarks(list, null, "", "bogus").map { it.id }
        )
    }

    // ---- filterHistory ----

    @Test
    fun historyQueryAndSorts() {
        val list = listOf(
            hist(1, "Alpha", 1000), hist(2, "beta", 3000), hist(3, "Gamma", 2000)
        )
        assertEquals(
            listOf(2L),
            filterHistory(list, "BET", "newest").map { it.id }
        )
        assertEquals(
            listOf(1L, 3L, 2L),
            filterHistory(list, "", "oldest").map { it.id }
        )
        assertEquals(
            listOf(1L, 2L, 3L),
            filterHistory(list, "", "title").map { it.id }
        )
    }

    // ---- filterDownloads ----

    @Test
    fun downloadsMediaFileAndPrivateAreDisjoint() {
        val list = listOf(
            rec(1, "song", 1000, media = true),
            rec(2, "doc", 2000),
            rec(3, "secret", 3000, isPrivate = true),
            rec(4, "clip", 4000, media = true, isPrivate = true)
        )
        assertEquals(
            listOf(1L),
            filterDownloads(list, true, false, "", "newest").map { it.id }
        )
        assertEquals(
            listOf(2L),
            filterDownloads(list, false, false, "", "newest").map { it.id }
        )
        assertEquals(
            listOf(3L),
            filterDownloads(list, false, true, "", "newest").map { it.id }
        )
        // A private media record belongs to neither the media
        // list (not private) nor the private list (not a file).
        assertEquals(
            emptyList<Long>(),
            filterDownloads(list, true, true, "", "newest").map { it.id }
        )
    }

    @Test
    fun downloadsQueryMatchesTitleOrFileName() {
        val list = listOf(
            rec(1, "song", 1000, media = true), rec(2, "doc", 2000, media = true)
        )
        assertEquals(
            listOf(1L),
            filterDownloads(list, true, false, "SONG", "newest").map { it.id }
        )
        assertEquals(
            listOf(2L),
            filterDownloads(list, true, false, "doc.bin", "newest").map { it.id }
        )
    }

    @Test
    fun downloadsSorts() {
        val list = listOf(
            rec(1, "banana", 3000, media = true),
            rec(2, "Apple", 1000, media = true),
            rec(3, "cherry", 2000, media = true)
        )
        assertEquals(
            listOf(2L, 3L, 1L),
            filterDownloads(list, true, false, "", "oldest").map { it.id }
        )
        assertEquals(
            listOf(2L, 1L, 3L),
            filterDownloads(list, true, false, "", "title").map { it.id }
        )
    }

    // ---- groupHistoryByDay ----

    @Test
    fun historyGroupsKeepFirstSeenOrder() {
        val now = System.currentTimeMillis()
        val day = 24L * 3600_000L
        val list = listOf(
            hist(1, "a", now), hist(2, "b", now - 3 * day),
            hist(3, "c", now), hist(4, "d", now - 3 * day)
        )
        val grouped = groupHistoryByDay(list)
        assertEquals(2, grouped.size)
        val keys = grouped.keys.toList()
        // First-seen order: today's group first, then the
        // 3-days-ago group — never re-sorted.
        assertEquals(listOf(1L, 3L), grouped[keys[0]]!!.map { it.id })
        assertEquals(listOf(2L, 4L), grouped[keys[1]]!!.map { it.id })
    }

    @Test
    fun historyGroupsEmptyAndSingleDay() {
        assertTrue(groupHistoryByDay(emptyList()).isEmpty())
        val now = System.currentTimeMillis()
        val grouped = groupHistoryByDay(
            listOf(hist(1, "a", now), hist(2, "b", now))
        )
        assertEquals(1, grouped.size)
        assertEquals(listOf(1L, 2L), grouped.values.first().map { it.id })
    }
}
