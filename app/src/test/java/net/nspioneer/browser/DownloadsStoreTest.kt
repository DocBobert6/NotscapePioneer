package net.nspioneer.browser

import net.nspioneer.browser.data.DownloadRecord
import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.media.Downloads
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
 * Scorecard row F (store half): the download helpers that need a
 * [PioneerStore] — `engineFor`, `locationFor`, the job-bundle
 * store round-trip, `activeDownloadCount`, `nextDownloadId`, and
 * the pause-aware terminal write.
 *
 * Plain JVM unit tests cannot build a PioneerStore: its
 * constructor reads `context.filesDir` and builds a
 * `Handler(Looper.getMainLooper())`, both of which throw on the
 * android.jar stubs. So this class runs under Robolectric
 * (pinned runtime SDK 35 — 4.14.1's highest — the app targets
 * 36, but nothing under test touches an API past 35).
 *
 * The store is a process singleton; each test starts from an
 * empty downloads list and empty settings, and uses its own
 * record ids so the in-memory pause/cancel flags (which have
 * no public clear) can never leak across tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadsStoreTest {

    private lateinit var store: PioneerStore

    private fun rec(
        id: Long,
        status: String = "queued",
        isPrivate: Boolean = false,
        fileName: String = "f.bin"
    ): DownloadRecord = DownloadRecord(
        id = id, title = "t", url = "https://example.com/f.bin",
        fileName = fileName, dmId = -1, status = status,
        addedAt = 0L, isPrivate = isPrivate
    )

    @Before
    fun setUp() {
        store = PioneerStore.get(RuntimeEnvironment.getApplication())
        store.downloads.clear()
        store.settings.clear()
    }

    @After
    fun tearDown() {
        store.downloads.clear()
        store.settings.clear()
    }

    // ---------- engineFor ----------

    @Test
    fun engineForDefaultsToDmWithoutBundle() {
        store.addDownload(rec(1001L))
        assertEquals("dm", Downloads.engineFor(store, rec(1001L)))
    }

    @Test
    fun engineForReadsBundleEngine() {
        store.addDownload(rec(1002L))
        Downloads.saveJobBundle(store, 1002L, mapOf("engine" to "hls", "url" to "https://example.com/x.m3u8"))
        assertEquals("hls", Downloads.engineFor(store, rec(1002L)))
        Downloads.updateJobBundle(store, 1002L, mapOf("engine" to "dash"))
        assertEquals("dash", Downloads.engineFor(store, rec(1002L)))
    }

    @Test
    fun engineForIgnoresBundleWithoutEngineKey() {
        store.addDownload(rec(1003L))
        Downloads.saveJobBundle(store, 1003L, mapOf("url" to "https://example.com/f.bin"))
        assertEquals("dm", Downloads.engineFor(store, rec(1003L)))
    }

    // ---------- locationFor ----------

    @Test
    fun locationForBundleWinsOverSettingAndPrivacy() {
        val r = rec(1011L, isPrivate = true)
        store.addDownload(r)
        Downloads.saveJobBundle(store, 1011L, mapOf("location" to "public"))
        assertEquals("public", Downloads.locationFor(store, r))
    }

    @Test
    fun locationForPrivateDefaultsToApp() {
        val r = rec(1012L, isPrivate = true)
        store.addDownload(r)
        assertEquals("app", Downloads.locationFor(store, r))
    }

    @Test
    fun locationForFallsBackToSettingThenPublic() {
        val r = rec(1013L)
        store.addDownload(r)
        assertEquals("public", Downloads.locationFor(store, r))
        store.putSetting("downloadLocation", "app")
        assertEquals("app", Downloads.locationFor(store, r))
    }

    @Test
    fun locationForIgnoresBlankBundleLocation() {
        val r = rec(1014L)
        store.addDownload(r)
        Downloads.saveJobBundle(store, 1014L, mapOf("location" to ""))
        assertEquals("public", Downloads.locationFor(store, r))
    }

    // ---------- job bundle store round-trip ----------

    @Test
    fun jobBundleStoreRoundTrip() {
        val fields = mapOf(
            "url" to "https://example.com/a b?x=1&y=2",
            "engine" to "seg",
            "total" to "123456"
        )
        Downloads.saveJobBundle(store, 1021L, fields)
        assertEquals(fields, Downloads.readJobBundle(store, 1021L))
        // Update merges: untouched keys survive, patched keys win.
        Downloads.updateJobBundle(store, 1021L, mapOf("engine" to "dm", "extra" to "1"))
        assertEquals(
            mapOf(
                "url" to "https://example.com/a b?x=1&y=2",
                "engine" to "dm",
                "total" to "123456",
                "extra" to "1"
            ),
            Downloads.readJobBundle(store, 1021L)
        )
        Downloads.clearJobBundle(store, 1021L)
        assertNull(Downloads.readJobBundle(store, 1021L))
    }

    @Test
    fun jobBundleReadMissingIsNull() {
        assertNull(Downloads.readJobBundle(store, 1022L))
    }

    // ---------- activeDownloadCount / nextDownloadId ----------

    @Test
    fun activeDownloadCountCountsOnlyDownloading() {
        store.addDownload(rec(1031L, status = "downloading"))
        store.addDownload(rec(1032L, status = "downloading"))
        store.addDownload(rec(1033L, status = "queued"))
        store.addDownload(rec(1034L, status = "complete"))
        store.addDownload(rec(1035L, status = "paused"))
        assertEquals(2, Downloads.activeDownloadCount(store))
    }

    @Test
    fun nextDownloadIdAvoidsExistingIds() {
        val first = Downloads.nextDownloadId(store)
        assertTrue(first > 0)
        store.addDownload(rec(first, status = "downloading"))
        val second = Downloads.nextDownloadId(store)
        assertTrue(second > 0)
        assertFalse(second == first)
    }

    // ---------- pause-aware terminal write ----------

    @Test
    fun pauseOnlyResumableEngines() {
        val resumable = rec(1041L)
        store.addDownload(resumable)
        Downloads.saveJobBundle(store, 1041L, mapOf("engine" to "hls"))
        assertTrue(
            Downloads.pause(RuntimeEnvironment.getApplication(), store, resumable)
        )
        assertEquals(
            "paused",
            store.downloadsSnapshot().first { it.id == 1041L }.status
        )

        // A DownloadManager job has no pause path.
        val plain = rec(1042L)
        store.addDownload(plain)
        assertFalse(
            Downloads.pause(RuntimeEnvironment.getApplication(), store, plain)
        )
        assertEquals(
            "queued",
            store.downloadsSnapshot().first { it.id == 1042L }.status
        )
    }

    @Test
    fun writeTerminalIfNotPausedRespectsPause() {
        val r = rec(1051L)
        store.addDownload(r)
        Downloads.saveJobBundle(store, 1051L, mapOf("engine" to "hls"))
        Downloads.pause(RuntimeEnvironment.getApplication(), store, r)
        Downloads.writeTerminalIfNotPaused(store, 1051L, "complete", 100)
        val paused = store.downloadsSnapshot().first { it.id == 1051L }
        assertEquals("paused", paused.status)
        assertEquals(-1, paused.progress)

        val r2 = rec(1052L)
        store.addDownload(r2)
        Downloads.writeTerminalIfNotPaused(store, 1052L, "complete", 100)
        val done = store.downloadsSnapshot().first { it.id == 1052L }
        assertEquals("complete", done.status)
        assertEquals(100, done.progress)
    }
}
