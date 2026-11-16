package net.nspioneer.browser

import com.sun.net.httpserver.HttpServer
import net.nspioneer.browser.data.DownloadRecord
import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.media.Downloads
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * DASH journal (alpha.48, gap plan B-4): `downloadDashTrack`
 * keeps a per-track journal (init segment as index -1, one
 * file per media segment) so a resumed job skips init +
 * finished segments instead of re-fetching them — the same
 * shape as the HLS job.
 *
 * The tests drive the real fetcher against a local HTTP server
 * on 127.0.0.1 and count hits per path, so "not re-fetched"
 * is observed, not assumed. A seeded track dir (journal +
 * segment files, no network state) is exactly what a killed
 * job leaves behind, so the partial-resume test reproduces
 * the kill-mid-download case deterministically.
 *
 * Runs under Robolectric like DownloadsStoreTest: the store
 * needs a real Context, and the manifest parser needs
 * XmlPullParserFactory. Pinned runtime SDK 35.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DashJournalTest {

    private lateinit var store: PioneerStore
    private lateinit var server: HttpServer
    private lateinit var tmp: File
    private val hits = ConcurrentHashMap<String, AtomicInteger>()

    private val bodies = mapOf(
        "/init.mp4" to "INIT",
        "/seg-1.m4s" to "SEG1",
        "/seg-2.m4s" to "SEG2",
        "/seg-3.m4s" to "SEG3",
    )

    private fun rec(id: Long): DownloadRecord = DownloadRecord(
        id = id, title = "t", url = "https://example.com/manifest.mpd",
        fileName = "clip.mp4", dmId = -1, status = "downloading",
        addedAt = 0L
    )

    private fun mpd(): String = """
        <MPD type="static" mediaPresentationDuration="PT3S">
          <Period>
            <AdaptationSet mimeType="video/mp4">
              <Representation id="v1" bandwidth="1000000" width="1280" height="720">
                <SegmentTemplate media="seg-${'$'}Number${'$'}.m4s"
                    initialization="init.mp4" timescale="1000" startNumber="1">
                  <SegmentTimeline><S t="0" d="1000" r="2"/></SegmentTimeline>
                </SegmentTemplate>
              </Representation>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    @Before
    fun setUp() {
        store = PioneerStore.get(RuntimeEnvironment.getApplication())
        store.downloads.clear()
        store.settings.clear()
        tmp = Files.createTempDirectory("dash-journal-test").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            hits.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            val body = bodies[path]
            if (body == null) {
                ex.sendResponseHeaders(404, -1)
            } else {
                val bytes = body.toByteArray()
                ex.responseHeaders.set("Content-Type", "video/iso.segment")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            ex.close()
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        tmp.deleteRecursively()
        store.downloads.clear()
        store.settings.clear()
    }

    private fun baseUrl(): String =
        "http://127.0.0.1:${server.address.port}/manifest.mpd"

    private fun videoRep() =
        Downloads.parseDashManifest(mpd(), baseUrl())
            .first { it.mime.contains("video") }

    private fun runTrack(id: Long, dir: File): File {
        val r = rec(id)
        store.addDownload(r)
        return Downloads.downloadDashTrack(
            RuntimeEnvironment.getApplication().applicationContext,
            store, r, videoRep(), baseUrl(), null, dir, "track0",
            onProgress = {}, progressBase = 0.0, progressSpan = 1.0,
            cookies = Downloads.JobCookieJar()
        )
    }

    private fun hitCount(path: String): Int = hits[path]?.get() ?: 0

    private fun journalOf(dir: File): Set<Int> =
        File(dir, "track0/journal.txt").readLines()
            .mapNotNull { it.toIntOrNull() }.toSet()

    @Test
    fun freshRunFetchesInitAndAllSegmentsAndJournalsThem() {
        val out = runTrack(8001L, tmp)
        assertEquals("INITSEG1SEG2SEG3", out.readBytes().decodeToString())
        assertEquals(setOf(Downloads.DASH_INIT_JOURNAL_INDEX, 0, 1, 2), journalOf(tmp))
        assertEquals(1, hitCount("/init.mp4"))
        assertEquals(1, hitCount("/seg-1.m4s"))
        assertEquals(1, hitCount("/seg-2.m4s"))
        assertEquals(1, hitCount("/seg-3.m4s"))
        // Segment files + init file survive in the track dir.
        assertTrue(File(tmp, "track0/seg-00000.m4s").exists())
        assertTrue(File(tmp, "track0/init.mp4").exists())
    }

    @Test
    fun partialJournalResumeSkipsInitAndFinishedSegments() {
        // Seed exactly what a job killed after segment 0 leaves
        // behind: init + segment 0 on disk, journal {-1, 0}.
        val trackDir = File(tmp, "track0").apply { mkdirs() }
        File(trackDir, "init.mp4").writeBytes("INIT".toByteArray())
        File(trackDir, "seg-00000.m4s").writeBytes("SEG1".toByteArray())
        File(trackDir, "journal.txt").writeText("-1\n0")

        val out = runTrack(8002L, tmp)
        assertEquals("INITSEG1SEG2SEG3", out.readBytes().decodeToString())
        // Init and segment 0 were NOT re-fetched; only the two
        // missing segments hit the network.
        assertEquals(0, hitCount("/init.mp4"))
        assertEquals(0, hitCount("/seg-1.m4s"))
        assertEquals(1, hitCount("/seg-2.m4s"))
        assertEquals(1, hitCount("/seg-3.m4s"))
        assertEquals(setOf(Downloads.DASH_INIT_JOURNAL_INDEX, 0, 1, 2), journalOf(tmp))
    }

    @Test
    fun fullJournalResumeFetchesNothing() {
        runTrack(8003L, tmp)
        val firstHits = bodies.keys.associateWith { hitCount(it) }
        assertEquals(mapOf(
            "/init.mp4" to 1, "/seg-1.m4s" to 1,
            "/seg-2.m4s" to 1, "/seg-3.m4s" to 1
        ), firstHits)

        val out = runTrack(8003L, tmp)
        assertEquals("INITSEG1SEG2SEG3", out.readBytes().decodeToString())
        // A second run on the same dir makes no requests at all.
        assertEquals(firstHits, bodies.keys.associateWith { hitCount(it) })
        assertEquals(setOf(Downloads.DASH_INIT_JOURNAL_INDEX, 0, 1, 2), journalOf(tmp))
    }
}
