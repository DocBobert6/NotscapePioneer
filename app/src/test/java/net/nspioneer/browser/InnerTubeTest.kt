package net.nspioneer.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import net.nspioneer.browser.engine.BlockingObserver
import net.nspioneer.browser.media.InnerTube
import net.nspioneer.browser.media.MediaLog
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Alpha.65 — the YouTube-only InnerTube route. The
 * ladder asks YouTube's player endpoint as a different
 * client (VISIONOS → ANDROID_VR → iOS) for an HLS or
 * DASH manifest, validates the manifest by fetching
 * it, and reports it into the tab's media log — or
 * nothing at all. Other sites never reach it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InnerTubeTest {

    private val tabIds = listOf(65001, 65002, 65003, 65004, 65005)
    private val hlsUrl =
        "https://manifest.googlevideo.com/api/manifest/hls_playlist/id/VID12345678/itag/136/source/yt_live_broadcast/requiressl/yes/"
    private val dashUrl =
        "https://manifest.googlevideo.com/api/manifest/dash/id/VID12345678/itag/136/source/yt_live_broadcast/requiressl/yes/"

    @After
    fun clearTabs() {
        for (id in tabIds) MediaLog.clear(id)
        InnerTube.scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        InnerTube.transportFactory = { InnerTube.RealTransport(it) }
    }

    // ---- video-id extraction ----

    @Test
    fun extractVideoId_watch_and_short_forms() {
        assertEquals(
            "mXI-acwMl4c",
            InnerTube.extractVideoId("https://www.youtube.com/watch?v=mXI-acwMl4c"),
        )
        assertEquals(
            "mXI-acwMl4c",
            InnerTube.extractVideoId("https://m.youtube.com/watch?v=mXI-acwMl4c&list=PL1"),
        )
        assertEquals(
            "mXI-acwMl4c",
            InnerTube.extractVideoId("https://music.youtube.com/watch?v=mXI-acwMl4c"),
        )
        assertEquals(
            "mXI-acwMl4c",
            InnerTube.extractVideoId("https://youtu.be/mXI-acwMl4c"),
        )
        assertEquals(
            "mXI-acwMl4c",
            InnerTube.extractVideoId("https://youtu.be/mXI-acwMl4c?t=42"),
        )
        assertEquals(
            "mXI-acwMl4c",
            InnerTube.extractVideoId("https://www.youtube.com/embed/mXI-acwMl4c"),
        )
        assertEquals(
            "mXI-acwMl4c",
            InnerTube.extractVideoId("https://www.youtube-nocookie.com/embed/mXI-acwMl4c"),
        )
        assertEquals(
            "mXI-acwMl4c",
            InnerTube.extractVideoId("https://www.youtube.com/shorts/mXI-acwMl4c"),
        )
        assertEquals(
            "mXI-acwMl4c",
            InnerTube.extractVideoId("https://www.youtube.com/live/mXI-acwMl4c"),
        )
        assertEquals(
            "mXI-acwMl4c",
            InnerTube.extractVideoId("https://www.youtube.com/v/mXI-acwMl4c"),
        )
    }

    @Test
    fun extractVideoId_rejects_non_watch_and_bad_ids() {
        assertNull(InnerTube.extractVideoId("https://www.youtube.com/"))
        assertNull(InnerTube.extractVideoId("https://www.youtube.com/results?search_query=wwe"))
        assertNull(InnerTube.extractVideoId("https://www.youtube.com/playlist?list=PL1234567890"))
        assertNull(InnerTube.extractVideoId("https://example.com/watch?v=mXI-acwMl4c"))
        assertNull(InnerTube.extractVideoId("https://www.youtube.com/watch?v=short"))
        assertNull(InnerTube.extractVideoId("https://www.youtube.com/watch?v=mXI-acwMl4cXX"))
        assertNull(InnerTube.extractVideoId("https://www.youtube.com/watch"))
        assertNull(InnerTube.extractVideoId("not a url"))
        assertNull(InnerTube.extractVideoId(""))
    }

    @Test
    fun isYouTubeHost_pins_the_trigger_set() {
        assertTrue(InnerTube.isYouTubeHost("youtube.com"))
        assertTrue(InnerTube.isYouTubeHost("www.youtube.com"))
        assertTrue(InnerTube.isYouTubeHost("m.youtube.com"))
        assertTrue(InnerTube.isYouTubeHost("music.youtube.com"))
        assertTrue(InnerTube.isYouTubeHost("youtu.be"))
        assertTrue(InnerTube.isYouTubeHost("youtube-nocookie.com"))
        assertTrue(InnerTube.isYouTubeHost("www.youtube-nocookie.com"))
        assertFalse(InnerTube.isYouTubeHost("example.com"))
        assertFalse(InnerTube.isYouTubeHost("notyoutube.com"))
        assertFalse(InnerTube.isYouTubeHost("youtube.com.evil.com"))
        assertFalse(InnerTube.isYouTubeHost("fakeyoutube.com"))
    }

    // ---- player request ----

    @Test
    fun buildPlayerRequest_shape() {
        val json = JSONObject(
            InnerTube.buildPlayerRequest(InnerTube.CLIENTS[0], "mXI-acwMl4c", "VISITOR_X"),
        )
        val client = json.getJSONObject("context").getJSONObject("client")
        assertEquals("VISIONOS", client.getString("clientName"))
        assertEquals("1.02", client.getString("clientVersion"))
        assertEquals("VISITOR_X", client.getString("visitorData"))
        assertEquals("mXI-acwMl4c", json.getString("videoId"))
        assertTrue(json.getBoolean("contentCheckOk"))
        assertTrue(json.getBoolean("racyCheckOk"))
    }

    @Test
    fun buildPlayerRequest_no_visitor_data_when_no_cookie() {
        val json = JSONObject(
            InnerTube.buildPlayerRequest(InnerTube.CLIENTS[1], "mXI-acwMl4c", null),
        )
        val client = json.getJSONObject("context").getJSONObject("client")
        assertEquals("ANDROID_VR", client.getString("clientName"))
        assertEquals("1.65.10", client.getString("clientVersion"))
        assertFalse(client.has("visitorData"))
    }

    @Test
    fun client_constants_are_the_briefed_ladder() {
        assertEquals(listOf("visionos", "android_vr", "ios"), InnerTube.CLIENTS.map { it.key })
        assertEquals("1.65.10", InnerTube.CLIENTS[1].clientVersion)
        // iOS: the research brief said 21.02.3; yt-dlp
        // master as of 2026-11-16 carries 21.26.4 — the
        // current pin wins, the constant is data.
        assertEquals("21.26.4", InnerTube.CLIENTS[2].clientVersion)
    }

    // ---- manifest extraction ----

    @Test
    fun extractManifests_reads_both_manifests() {
        val (hls, dash) = InnerTube.extractManifests(
            """{"streamingData":{"hlsManifestUrl":"$hlsUrl","dashManifestUrl":"$dashUrl"}}""",
        )
        assertEquals(hlsUrl, hls)
        assertEquals(dashUrl, dash)
    }

    @Test
    fun extractManifests_tolerates_sabr_only_and_garbage() {
        val (hls, dash) = InnerTube.extractManifests(
            """{"streamingData":{"adaptiveFormats":[]}}""",
        )
        assertNull(hls)
        assertNull(dash)
        assertEquals(null to null, InnerTube.extractManifests("not json"))
        assertEquals(null to null, InnerTube.extractManifests("""{"playabilityStatus":{}}"""))
    }

    @Test
    fun manifest_text_checks() {
        assertTrue(InnerTube.isHlsManifestText("#EXTM3U\n#EXT-X-VERSION:3\n"))
        assertTrue(InnerTube.isHlsManifestText("\n  #EXTM3U\n"))
        assertFalse(InnerTube.isHlsManifestText("<html>not a manifest</html>"))
        assertFalse(InnerTube.isHlsManifestText(""))
        assertTrue(InnerTube.isDashManifestText("<?xml version=\"1.0\"?>\n<MPD xmlns=\"x\">"))
        assertFalse(InnerTube.isDashManifestText("#EXTM3U\n"))
        assertFalse(InnerTube.isDashManifestText(""))
    }

    // ---- extension-less manifest shape ----

    @Test
    fun innerTube_manifest_shape_is_youtube_only() {
        assertTrue(MediaLog.isInnerTubeHlsManifest(hlsUrl))
        assertFalse(MediaLog.isInnerTubeHlsManifest(dashUrl))
        assertTrue(MediaLog.isInnerTubeDashManifest(dashUrl))
        assertFalse(MediaLog.isInnerTubeDashManifest(hlsUrl))
        // Same path on another host, or another googlevideo
        // path, is not an InnerTube manifest.
        assertFalse(
            MediaLog.isInnerTubeHlsManifest(
                "https://cdn.example.com/api/manifest/hls_playlist/id/x/",
            ),
        )
        assertFalse(
            MediaLog.isInnerTubeHlsManifest(
                "https://manifest.googlevideo.com/videoplayback?id=x&itag=22",
            ),
        )
        assertFalse(MediaLog.isInnerTubeHlsManifest("https://example.com/master.m3u8"))
        assertEquals("application/x-mpegurl", MediaLog.innerTubeManifestMime(hlsUrl))
        assertEquals("application/dash+xml", MediaLog.innerTubeManifestMime(dashUrl))
        assertNull(MediaLog.innerTubeManifestMime("https://example.com/master.m3u8"))
    }

    @Test
    fun isManifest_recognises_the_shape_everywhere() {
        // MediaLog and BlockingObserver must agree, and the
        // sheet-open resolve filter (isManifest with no
        // mime) must see the master — otherwise its media
        // playlists would never be hidden.
        for (url in listOf(hlsUrl, dashUrl)) {
            assertTrue("MediaLog $url", MediaLog.isManifest(url, null))
            assertTrue("BlockingObserver $url", BlockingObserver.isManifest(url, null))
        }
        assertFalse(MediaLog.isManifest("https://example.com/clip.mp4", null))
        assertFalse(BlockingObserver.isManifest("https://example.com/clip.mp4", null))
    }

    @Test
    fun inspect_records_the_extensionless_manifest() {
        val tabId = tabIds[0]
        MediaLog.inspect(tabId, hlsUrl, mime = "application/x-mpegurl")
        val items = MediaLog.forTab(tabId)
        assertEquals(1, items.size)
        assertEquals("video", items[0].kind)
        assertEquals("stream (HLS)", items[0].label)
        assertEquals("net", items[0].source)
        MediaLog.inspect(tabId, dashUrl, mime = "application/dash+xml")
        assertEquals("stream (DASH)", MediaLog.forTab(tabId)[1].label)
    }

    // ---- the ladder, with a fake transport ----

    private class FakeTransport(
        private val playerByClient: Map<String, String?>,
        private val manifestByUrl: Map<String, String?> = emptyMap(),
    ) : InnerTube.Transport {
        val attempted = mutableListOf<String>()
        var lastVisitorData: String? = null
        override fun postPlayer(
            client: InnerTube.Client, videoId: String, visitorData: String?,
        ): String? {
            attempted += client.key
            lastVisitorData = visitorData
            return playerByClient[client.key]
        }

        override fun fetchManifest(url: String): String? = manifestByUrl[url]
    }

    private fun playerJson(hls: String? = null, dash: String? = null): String {
        val sd = buildList {
            hls?.let { add("\"hlsManifestUrl\":\"$it\"") }
            dash?.let { add("\"dashManifestUrl\":\"$it\"") }
        }.joinToString(",")
        return """{"streamingData":{$sd}}"""
    }

    private val hlsMasterText = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nv.m3u8\n"

    @Test
    fun ladder_visionos_hls_success_stops_the_ladder() = runBlocking {
        val transport = FakeTransport(
            playerByClient = mapOf(
                "visionos" to playerJson(hls = hlsUrl),
                "android_vr" to playerJson(hls = hlsUrl),
                "ios" to playerJson(hls = hlsUrl),
            ),
            manifestByUrl = mapOf(hlsUrl to hlsMasterText),
        )
        val result = InnerTube.runLadder(
            "mXI-acwMl4c", "https://www.youtube.com/watch?v=mXI-acwMl4c",
            transport,
        ) { listOf("VISITOR_INFO1_LIVE" to "VISITOR_X") }
        assertEquals(hlsUrl, result?.manifestUrl)
        assertEquals("application/x-mpegurl", result?.mime)
        assertEquals("visionos", result?.clientKey)
        assertEquals(listOf("visionos"), transport.attempted)
        assertEquals("VISITOR_X", transport.lastVisitorData)
    }

    @Test
    fun ladder_sabr_only_falls_through_to_android_vr() = runBlocking {
        val transport = FakeTransport(
            playerByClient = mapOf(
                "visionos" to """{"streamingData":{"adaptiveFormats":[]}}""",
                "android_vr" to playerJson(hls = hlsUrl),
            ),
            manifestByUrl = mapOf(hlsUrl to hlsMasterText),
        )
        val result = InnerTube.runLadder(
            "mXI-acwMl4c", "https://www.youtube.com/watch?v=mXI-acwMl4c",
            transport,
        ) { emptyList() }
        assertEquals("android_vr", result?.clientKey)
        assertEquals(listOf("visionos", "android_vr"), transport.attempted)
        assertNull(transport.lastVisitorData)
    }

    @Test
    fun ladder_unfetchable_manifest_falls_through_to_ios() = runBlocking {
        // The first two rungs' validation fetch returns
        // junk (an unsolved `n` / a PO-gated 403 body);
        // the manifest is only served on the third fetch.
        var fetches = 0
        val attempted = mutableListOf<String>()
        val counting = object : InnerTube.Transport {
            override fun postPlayer(
                client: InnerTube.Client, videoId: String, visitorData: String?,
            ): String? {
                attempted += client.key
                return playerJson(hls = hlsUrl)
            }

            override fun fetchManifest(url: String): String? {
                fetches++
                return if (fetches >= 3) hlsMasterText else "forbidden"
            }
        }
        val result = InnerTube.runLadder(
            "mXI-acwMl4c", "https://www.youtube.com/watch?v=mXI-acwMl4c",
            counting,
        ) { emptyList() }
        assertEquals("ios", result?.clientKey)
        assertEquals(listOf("visionos", "android_vr", "ios"), attempted)
    }

    @Test
    fun ladder_all_rungs_fail_reports_nothing() = runBlocking {
        val transport = FakeTransport(
            playerByClient = mapOf(
                "visionos" to null,
                "android_vr" to """{"streamingData":{}}""",
                "ios" to null,
            ),
        )
        val result = InnerTube.runLadder(
            "mXI-acwMl4c", "https://www.youtube.com/watch?v=mXI-acwMl4c",
            transport,
        ) { emptyList() }
        assertNull(result)
        assertEquals(listOf("visionos", "android_vr", "ios"), transport.attempted)
    }

    @Test
    fun ladder_dash_only_rung_is_accepted() = runBlocking {
        val mpdText = "<?xml version=\"1.0\"?>\n<MPD xmlns=\"x\"></MPD>"
        val transport = FakeTransport(
            playerByClient = mapOf("visionos" to playerJson(dash = dashUrl)),
            manifestByUrl = mapOf(dashUrl to mpdText),
        )
        val result = InnerTube.runLadder(
            "mXI-acwMl4c", "https://www.youtube.com/watch?v=mXI-acwMl4c",
            transport,
        ) { emptyList() }
        assertEquals(dashUrl, result?.manifestUrl)
        assertEquals("application/dash+xml", result?.mime)
        assertEquals("visionos", result?.clientKey)
    }

    @Test
    fun ladder_non_youtube_never_starts() {
        // onNavigate with a non-YouTube URL reports
        // nothing and clears the tab's mark.
        val latch = CountDownLatch(1)
        InnerTube.transportFactory = {
            FakeTransport(mapOf("visionos" to playerJson(hls = hlsUrl)))
        }
        InnerTube.onNavigate(
            tabIds[0], "https://example.com/watch?v=mXI-acwMl4c",
            isStillOn = { _, _ -> true },
            report = { _, _, _ -> latch.countDown() },
        )
        assertFalse(latch.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun onNavigate_reports_once_then_skips_the_same_video() {
        val tabId = tabIds[1]
        val pageUrl = "https://www.youtube.com/watch?v=mXI-acwMl4c"
        InnerTube.transportFactory = {
            FakeTransport(
                playerByClient = mapOf("visionos" to playerJson(hls = hlsUrl)),
                manifestByUrl = mapOf(hlsUrl to hlsMasterText),
            )
        }
        var reports = 0
        val latch = CountDownLatch(1)
        val report: (Int, String, String) -> Unit = { _, _, _ ->
            reports++
            latch.countDown()
        }
        InnerTube.onNavigate(tabId, pageUrl, { _, _ -> true }, report)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, reports)
        // Same video again: the guard skips it.
        InnerTube.onNavigate(tabId, pageUrl, { _, _ -> true }, report)
        Thread.sleep(300)
        assertEquals(1, reports)
        // Away to another video, then back: re-runs.
        val latch2 = CountDownLatch(1)
        InnerTube.onNavigate(
            tabId, "https://www.youtube.com/watch?v=BaW_jenozKc",
            { _, _ -> true },
            { _, _, _ -> latch2.countDown() },
        )
        assertTrue(latch2.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun onNavigate_drops_the_report_when_the_tab_moved_on() {
        val tabId = tabIds[2]
        InnerTube.transportFactory = {
            FakeTransport(
                playerByClient = mapOf("visionos" to playerJson(hls = hlsUrl)),
                manifestByUrl = mapOf(hlsUrl to hlsMasterText),
            )
        }
        val latch = CountDownLatch(1)
        InnerTube.onNavigate(
            tabId, "https://www.youtube.com/watch?v=mXI-acwMl4c",
            isStillOn = { _, _ -> false },
            report = { _, _, _ -> latch.countDown() },
        )
        assertFalse(latch.await(2, TimeUnit.SECONDS))
    }
}
