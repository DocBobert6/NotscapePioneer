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
import java.io.File

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
        fileName: String = "f.bin",
        url: String = "https://example.com/f.bin",
        addedAt: Long = 0L
    ): DownloadRecord = DownloadRecord(
        id = id, title = "t", url = url,
        fileName = fileName, dmId = -1, status = status,
        addedAt = addedAt, isPrivate = isPrivate
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

    // ---------- blob downloads (alpha.27) ----------

    private fun blobBundle(
        url: String, staged: File?, fileName: String,
        location: String = "public"
    ) =
        mapOf(
            "url" to url,
            "title" to "Adblock test",
            "media" to "false",
            "contentType" to "application/json",
            "location" to location,
            "fileName" to fileName,
            "stagedFile" to (staged?.absolutePath ?: "")
        )

    @Test
    fun blobDownloadCompletesFromStagedFile() {
        val app = RuntimeEnvironment.getApplication()
        val staged = File(
            Downloads.blobStagingDir(app), "blob-test-complete.bin"
        ).apply {
            parentFile?.mkdirs()
            writeText("hello-blob")
        }
        val url = "blob:https://d3ward.com/63efe48b-5427-4abb-94a6-0d66b378f924"
        store.addDownload(rec(2001L, url = url, fileName = "report-2001.json"))
        // App location: the finished file is a real path, so the
        // test can read the bytes back. (The public location
        // goes through MediaStore, whose URI is not a file.)
        Downloads.saveJobBundle(
            store, 2001L,
            blobBundle(url, staged, "report-2001.json", location = "app")
        )
        Downloads.promoteQueued(app, store)
        val done = store.downloadsSnapshot().first { it.id == 2001L }
        assertEquals("complete", done.status)
        assertEquals(100, done.progress)
        assertTrue(done.contentUri.isNotBlank())
        val dest = File(done.contentUri)
        try {
            assertEquals("hello-blob", dest.readText())
        } finally {
            runCatching { dest.delete() }
        }
        assertFalse(staged.exists())
    }

    @Test
    fun blobDownloadCompletesToPublicLocation() {
        val app = RuntimeEnvironment.getApplication()
        val staged = File(
            Downloads.blobStagingDir(app), "blob-test-public.bin"
        ).apply {
            parentFile?.mkdirs()
            writeText("hello-public")
        }
        val url = "blob:https://d3ward.com/63efe48b-5427-4abb-94a6-0d66b378f924"
        store.addDownload(rec(2005L, url = url, fileName = "report-2005.json"))
        Downloads.saveJobBundle(store, 2005L, blobBundle(url, staged, "report-2005.json"))
        Downloads.promoteQueued(app, store)
        val done = store.downloadsSnapshot().first { it.id == 2005L }
        assertEquals("complete", done.status)
        assertEquals(100, done.progress)
        assertTrue(done.contentUri.isNotBlank())
        assertFalse(staged.exists())
    }

    @Test
    fun blobDownloadWithoutStagedFileFailsHonestly() {
        val app = RuntimeEnvironment.getApplication()
        val url = "blob:https://d3ward.com/63efe48b-5427-4abb-94a6-0d66b378f924"
        store.addDownload(rec(2002L, url = url, fileName = "report-2002.json"))
        Downloads.saveJobBundle(store, 2002L, blobBundle(url, null, "report-2002.json"))
        Downloads.promoteQueued(app, store)
        val failed = store.downloadsSnapshot().first { it.id == 2002L }
        assertEquals("failed", failed.status)
        assertEquals(
            "the page's download expired — tap Download on the page again",
            store.setting("dlerror:2002", "")
        )
    }

    @Test
    fun blobRetryReusesStagedBytes() {
        val app = RuntimeEnvironment.getApplication()
        val staged = File(
            Downloads.blobStagingDir(app), "blob-test-retry.bin"
        ).apply {
            parentFile?.mkdirs()
            writeText("hello-retry")
        }
        val url = "blob:https://d3ward.com/63efe48b-5427-4abb-94a6-0d66b378f924"
        // A "failed" record with addedAt=0 would be pruned by
        // addDownload's 90-day retention cap before retry runs.
        val record = rec(
            2003L, status = "failed", url = url, fileName = "report-2003.json",
            addedAt = System.currentTimeMillis()
        )
        store.addDownload(record)
        Downloads.saveJobBundle(
            store, 2003L,
            blobBundle(url, staged, "report-2003.json", location = "app")
        )
        assertTrue(Downloads.retry(app, store, record))
        val done = store.downloadsSnapshot().first { it.id == 2003L }
        assertEquals("complete", done.status)
        val dest = File(done.contentUri)
        try {
            assertEquals("hello-retry", dest.readText())
        } finally {
            runCatching { dest.delete() }
        }
    }

    @Test
    fun stageResponseBodyWritesBytesToDisk() {
        val app = RuntimeEnvironment.getApplication()
        val staged = Downloads.stageResponseBody(app, "blob-bytes".byteInputStream())
        assertTrue(staged != null)
        try {
            assertEquals("blob-bytes", staged!!.readText())
            assertTrue(staged.name.startsWith("blob-"))
        } finally {
            runCatching { staged?.delete() }
        }
    }

    @Test
    fun sweepOrphanBlobStagingDeletesUnreferencedFiles() {
        val app = RuntimeEnvironment.getApplication()
        val dir = Downloads.blobStagingDir(app).apply { mkdirs() }
        val orphan = File(dir, "blob-orphan.bin").apply { writeText("orphan") }
        val kept = File(dir, "blob-kept.bin").apply { writeText("kept") }
        store.addDownload(rec(2004L, url = "blob:https://example.com/x", fileName = "k.json"))
        Downloads.saveJobBundle(store, 2004L, mapOf("stagedFile" to kept.absolutePath))
        try {
            Downloads.sweepOrphanBlobStaging(app, store)
            assertFalse(orphan.exists())
            assertTrue(kept.exists())
        } finally {
            runCatching { kept.delete() }
        }
    }
}
