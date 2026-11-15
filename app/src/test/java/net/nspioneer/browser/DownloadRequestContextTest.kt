package net.nspioneer.browser

import net.nspioneer.browser.data.DownloadRecord
import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.media.Downloads
import org.json.JSONObject
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
 * Alpha.49 (gap plan A-4): the download record carries its
 * request context — referer, originHost, userAgent — so a
 * retried or resumed job reads it from the record instead of
 * the one-shot dljob:<id> bundle.
 *
 * Robolectric, like DownloadsStoreTest: a PioneerStore needs
 * a real filesDir and a main looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadRequestContextTest {

    private lateinit var store: PioneerStore

    private fun rec(
        id: Long,
        referer: String = "",
        originHost: String = "",
        userAgent: String = "",
        url: String = "https://cdn.example.com/f.bin",
    ): DownloadRecord = DownloadRecord(
        id = id, title = "t", url = url,
        fileName = "f.bin", dmId = -1, status = "queued",
        addedAt = 0L,
        referer = referer, originHost = originHost, userAgent = userAgent
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

    // ---------- record persistence ----------

    @Test
    fun contextSurvivesExportImportRoundTrip() {
        store.addDownload(
            rec(5001L, "https://page.example.com/watch", "page.example.com", "TestUA/1.0")
        )
        assertTrue(store.importBackup(store.exportBackup()))
        val back = store.downloadsSnapshot().firstOrNull { it.id == 5001L }
            ?: error("record lost in round-trip")
        assertEquals("https://page.example.com/watch", back.referer)
        assertEquals("page.example.com", back.originHost)
        assertEquals("TestUA/1.0", back.userAgent)
    }

    @Test
    fun oldRecordReadsBackWithEmptyContext() {
        // A pre-alpha.49 backup has no context keys at all.
        val json = JSONObject().apply {
            put("bookmarks", org.json.JSONArray())
            put("downloads", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("id", 5002L); put("title", "t")
                    put("url", "https://cdn.example.com/f.bin")
                    put("fileName", "f.bin"); put("dmId", -1)
                    put("status", "failed"); put("t", 0)
                    put("media", false); put("progress", -1)
                    put("contentUri", ""); put("isPrivate", false)
                })
            })
        }.toString()
        assertTrue(store.importBackup(json))
        val back = store.downloadsSnapshot().firstOrNull { it.id == 5002L }
            ?: error("record lost in import")
        assertEquals("", back.referer)
        assertEquals("", back.originHost)
        assertEquals("", back.userAgent)
    }

    @Test
    fun contextWrittenToDownloadsJsonOnDisk() {
        store.addDownload(
            rec(5003L, "https://page.example.com/watch", "page.example.com", "TestUA/1.0")
        )
        store.flush()
        val file = File(
            RuntimeEnvironment.getApplication().filesDir,
            "pioneer-store/downloads.json"
        )
        assertTrue(file.exists())
        val arr = JSONObject(file.readText()).getJSONArray("downloads")
        assertEquals(1, arr.length())
        val o = arr.getJSONObject(0)
        assertEquals("https://page.example.com/watch", o.getString("referer"))
        assertEquals("page.example.com", o.getString("originHost"))
        assertEquals("TestUA/1.0", o.getString("userAgent"))
    }

    @Test
    fun renameKeepsRequestContext() {
        store.addDownload(
            rec(5004L, "https://page.example.com/watch", "page.example.com", "TestUA/1.0")
        )
        store.updateDownloadFileName(5004L, "renamed.bin")
        val back = store.downloadsSnapshot().firstOrNull { it.id == 5004L }
            ?: error("record lost in rename")
        assertEquals("renamed.bin", back.fileName)
        assertEquals("https://page.example.com/watch", back.referer)
        assertEquals("page.example.com", back.originHost)
        assertEquals("TestUA/1.0", back.userAgent)
    }

    // ---------- pure helpers ----------

    @Test
    fun originHostOfCases() {
        assertEquals("page.example.com", Downloads.originHostOf("https://page.example.com/watch"))
        assertEquals("cdn.example.com", Downloads.originHostOf("https://cdn.example.com/f.bin"))
        assertEquals("", Downloads.originHostOf(null))
        assertEquals("", Downloads.originHostOf(""))
        assertEquals("", Downloads.originHostOf("not a url :::"))
    }

    @Test
    fun fillRequestContextFillsBlanksOnly() {
        val blank = rec(5005L)
        assertTrue(Downloads.fillRequestContext(blank, "https://page.example.com/watch"))
        assertEquals("https://page.example.com/watch", blank.referer)
        assertEquals("page.example.com", blank.originHost)
        assertFalse(blank.userAgent.isBlank())

        // Already-filled fields are never overwritten, and a
        // second fill reports no change.
        assertFalse(Downloads.fillRequestContext(blank, "https://other.example.com/"))
        assertEquals("https://page.example.com/watch", blank.referer)
        assertEquals("page.example.com", blank.originHost)

        // No referer: the origin falls back to the download URL.
        val noReferer = rec(5006L)
        assertTrue(Downloads.fillRequestContext(noReferer, null))
        assertEquals("", noReferer.referer)
        assertEquals("cdn.example.com", noReferer.originHost)
        assertFalse(noReferer.userAgent.isBlank())
    }

    // ---------- worker input ----------

    @Test
    fun workerInputDataCarriesRecordReferer() {
        val data = Downloads.workerInputData(
            rec(5007L, "https://page.example.com/watch", "page.example.com", "TestUA/1.0"),
            mapOf("total" to "12345", "finalUrl" to "https://cdn.example.com/f.bin")
        )
        assertEquals(5007L, data.getLong("recId", -1L))
        assertEquals("https://page.example.com/watch", data.getString("referer"))
        assertEquals(12345L, data.getLong("total", -1L))
        assertEquals("https://cdn.example.com/f.bin", data.getString("finalUrl"))
    }

    @Test
    fun workerInputDataBlankRefererIsNull() {
        // The workers already treat a null referer as "no
        // referer"; a blank record field must not become a
        // blank string in the input.
        val data = Downloads.workerInputData(rec(5008L), null)
        assertEquals(5008L, data.getLong("recId", -1L))
        assertNull(data.getString("referer"))
    }
}
