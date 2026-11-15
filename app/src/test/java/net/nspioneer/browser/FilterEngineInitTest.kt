package net.nspioneer.browser

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import net.nspioneer.browser.engine.FilterEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P-A5 (alpha.35): the filter lists never loaded on launch —
 * `FilterEngine.initAsync` had no caller, so blocking was inert
 * until Blocking Settings was opened.
 *
 * These tests prove the startup path itself: `initAsync` loads
 * the lists already on disk into memory, fires `onListsChanged`,
 * and does NOT re-download when the on-disk lists are fresh
 * (all 8 files present, written just now) — so the test needs
 * no network and CI stays offline. The wiring that calls
 * `initAsync` from `MainActivity.onCreate` is one line; the
 * behavior it triggers is what is tested here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FilterEngineInitTest {

    private lateinit var filtersDir: File
    private var prevOnListsChanged: (() -> Unit)? = null
    private val changed = AtomicBoolean(false)
    private val changedLatch = CountDownLatch(1)

    @Before
    fun setUp() {
        FilterEngine.resetForTests()
        prevOnListsChanged = FilterEngine.onListsChanged
        FilterEngine.onListsChanged = {
            changed.set(true)
            changedLatch.countDown()
        }
        val app = RuntimeEnvironment.getApplication()
        filtersDir = File(app.filesDir, "filters").apply {
            deleteRecursively()
            mkdirs()
        }
        // One small file per list index, all written just now,
        // so initAsync sees nothing missing and nothing stale.
        writeList(0, "||ads.example.com^\n||cdn.example.net^/ads/\n")
        writeList(1, "||tracker.example.com^\$third-party\n")
        writeList(2, "0.0.0.0 badhost.example\n0.0.0.0 evil.example\n")
        writeList(3, "||track3.example^\n")
        writeList(4, "||ad4.example^\n")
        writeList(5, "||ad5.example^\n")
        writeList(6, "||ad6.example^\n")
        writeList(7, "phish.example\nscam.example\n")
    }

    @After
    fun tearDown() {
        FilterEngine.onListsChanged = prevOnListsChanged
        FilterEngine.resetForTests()
    }

    private fun writeList(index: Int, text: String) {
        File(filtersDir, "list$index.txt").writeText(text)
    }

    /** initAsync loads the on-disk lists and fires onListsChanged. */
    @Test
    fun initAsync_loadsSeededListsFromDisk() {
        FilterEngine.initAsync(RuntimeEnvironment.getApplication())

        assertTrue(
            "initAsync did not finish loading within 10s",
            changedLatch.await(10, TimeUnit.SECONDS)
        )
        assertTrue(changed.get())
        assertTrue("no rules loaded", FilterEngine.ruleCount > 0)
        assertEquals(
            FilterEngine.CAT_ADS,
            FilterEngine.blockCategory(
                "https://ads.example.com/banner.js",
                isForMainFrame = false,
                blockAds = true,
                blockTrackers = true
            )
        )
        assertNull(
            FilterEngine.blockCategory(
                "https://example.com/",
                isForMainFrame = false,
                blockAds = true,
                blockTrackers = true
            )
        )
    }

    /** The phishing list (index 7) loads with the rest, so the
     *  alpha.31 interstitial has a live list on a fresh launch. */
    @Test
    fun initAsync_loadsPhishingList() {
        FilterEngine.initAsync(RuntimeEnvironment.getApplication())

        assertTrue(
            "initAsync did not finish loading within 10s",
            changedLatch.await(10, TimeUnit.SECONDS)
        )
        assertTrue(FilterEngine.isPhishingHost("phish.example"))
        assertTrue(FilterEngine.isPhishingHost("www.scam.example"))
        assertFalse(FilterEngine.isPhishingHost("example.com"))
        assertTrue(FilterEngine.phishingHostsCount() > 0)
    }

    /** Fresh on-disk lists are NOT re-downloaded: a real refresh
     *  would load hundreds of thousands of rules; the seeded set
     *  is a dozen. (A network fetch in CI would also be slow
     *  and flaky; this guards the offline property.) */
    @Test
    fun initAsync_doesNotRefreshFreshLists() {
        FilterEngine.initAsync(RuntimeEnvironment.getApplication())

        assertTrue(
            "initAsync did not finish loading within 10s",
            changedLatch.await(10, TimeUnit.SECONDS)
        )
        assertTrue("no rules loaded", FilterEngine.ruleCount > 0)
        assertTrue(
            "ruleCount ${FilterEngine.ruleCount} looks like a real " +
                "download, not the seeded set",
            FilterEngine.ruleCount < 100
        )
        assertFalse(FilterEngine.updating)
    }
}
