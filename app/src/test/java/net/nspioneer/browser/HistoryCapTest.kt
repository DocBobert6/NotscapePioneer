package net.nspioneer.browser

import net.nspioneer.browser.data.HistoryEntry
import net.nspioneer.browser.data.PioneerStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P-A3: history is capped on add, mirroring `addDownload` —
 * entries older than 90 days go, and the list never keeps
 * more than 500 entries (oldest first).
 *
 * Plain JVM unit tests cannot build a PioneerStore: its
 * constructor reads `context.filesDir` and builds a
 * `Handler(Looper.getMainLooper())`, both of which throw on the
 * android.jar stubs. So this class runs under Robolectric
 * (pinned runtime SDK 35 — 4.14.1's highest — the app targets
 * 36, but nothing under test touches an API past 35).
 *
 * The store is a process singleton; each test starts from an
 * empty history list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryCapTest {

    private lateinit var store: PioneerStore

    private val now get() = System.currentTimeMillis()
    private val dayMs = 24L * 3600 * 1000

    @Before
    fun setUp() {
        store = PioneerStore.get(RuntimeEnvironment.getApplication())
        store.history.clear()
    }

    @After
    fun tearDown() {
        store.history.clear()
    }

    @Test
    fun addHistoryKeepsVisitsUnderCap() {
        store.addHistory("One", "https://example.com/1")
        store.addHistory("Two", "https://example.com/2")
        store.addHistory("Three", "https://example.com/3")
        assertEquals(3, store.history.size)
        assertEquals("https://example.com/3", store.history.first().url)
        assertEquals("https://example.com/1", store.history.last().url)
    }

    @Test
    fun addHistoryCapsAt500KeepingNewest() {
        for (i in 1..600) {
            store.addHistory("Page $i", "https://example.com/$i")
        }
        assertEquals(500, store.history.size)
        assertEquals("https://example.com/600", store.history.first().url)
        assertEquals("https://example.com/101", store.history.last().url)
        assertTrue(store.history.none { it.url == "https://example.com/100" })
    }

    @Test
    fun addHistoryPrunesEntriesOlderThan90Days() {
        store.history.add(
            HistoryEntry(9001L, "Old", "https://example.com/old", now - 91 * dayMs)
        )
        store.history.add(
            HistoryEntry(9002L, "Recent", "https://example.com/recent", now - 10 * dayMs)
        )
        store.addHistory("New", "https://example.com/new")
        assertEquals(2, store.history.size)
        assertEquals("https://example.com/new", store.history.first().url)
        assertEquals("https://example.com/recent", store.history.last().url)
        assertTrue(store.history.none { it.url == "https://example.com/old" })
    }

    @Test
    fun addHistoryPrunesPreExistingOversizeList() {
        // A history file that predates the cap: 600 rows already
        // on disk. The next visit prunes all excess, not just
        // the one new row.
        for (i in 1..600) {
            store.history.add(
                HistoryEntry(10000L + i, "Page $i", "https://example.com/$i", now)
            )
        }
        store.addHistory("New", "https://example.com/new")
        assertEquals(500, store.history.size)
        assertEquals("https://example.com/new", store.history.first().url)
    }

    @Test
    fun addHistoryDedupeStillSkipsConsecutiveRepeat() {
        store.addHistory("One", "https://example.com/1")
        store.addHistory("One again", "https://example.com/1")
        assertEquals(1, store.history.size)
        assertEquals("One", store.history.first().title)
    }
}
