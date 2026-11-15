package net.nspioneer.browser

import net.nspioneer.browser.engine.BlockingHost
import net.nspioneer.browser.engine.BlockingObserver
import net.nspioneer.browser.engine.FilterEngine
import net.nspioneer.browser.media.MediaLog
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Response-header classification (alpha.47, gap plan row A-1):
 * an observation matches on URL regex OR media MIME. The MIME
 * cases are pinned on both Kotlin classifiers
 * ([BlockingObserver] and [MediaLog]) and on the shipped
 * `background.js` shape; the JS behaviour itself (listener
 * registration, the MIME hit report, the text/html veto) was
 * validated out-of-band with a Node stub harness against this
 * same file (`alpha47-verify/background-js-harness.js`, 31
 * checks, scratch, not shipped) — same split as alpha.42.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HeadersReceivedTest {

    // Distinct tab ids per test; MediaLog is a process-global
    // object, so every test clears its own ids afterwards.
    private val tabIds = listOf(47001, 47002, 47003, 47004, 47005, 47006, 47007)

    private var savedOnListsChanged: (() -> Unit)? = null

    @Before
    fun saveListsChanged() {
        // Constructing a BlockingObserver claims
        // FilterEngine.onListsChanged; restore it so other
        // test classes see their own value.
        savedOnListsChanged = FilterEngine.onListsChanged
    }

    @After
    fun restoreListsChanged() {
        FilterEngine.onListsChanged = savedOnListsChanged
        for (id in tabIds) MediaLog.clear(id)
    }

    private fun stubHost(
        observed: MutableList<Triple<Int, String, String>> = mutableListOf()
    ): BlockingHost = object : BlockingHost {
        override fun tabIdForPageHost(pageHost: String): Int =
            if (pageHost == "www.youtube.com") 47001 else -1

        override fun onMediaObserved(tabId: Int, url: String, kind: String) {
            observed.add(Triple(tabId, url, kind))
        }

        override fun disabledHosts(): Set<String> = emptySet()
    }

    private fun backgroundJs(): String =
        RuntimeEnvironment.getApplication().assets
            .open("extensions/pioneer-probe/background.js")
            .bufferedReader().use { it.readText() }

    // ---- MIME classifier (BlockingObserver half) ----

    @Test
    fun mime_video_types_report_video() {
        assertEquals("video", BlockingObserver.mediaKindForMime("video/mp4"))
        assertEquals("video", BlockingObserver.mediaKindForMime("video/webm"))
        assertEquals("video", BlockingObserver.mediaKindForMime("video/mp2t"))
        assertEquals(
            "video",
            BlockingObserver.mediaKindForMime("video/mp4; codecs=\"avc1.64001f\"")
        )
        assertEquals("video", BlockingObserver.mediaKindForMime("VIDEO/MP4"))
    }

    @Test
    fun mime_manifest_types_report_video() {
        assertEquals("video", BlockingObserver.mediaKindForMime("application/x-mpegURL"))
        assertEquals("video", BlockingObserver.mediaKindForMime("application/dash+xml"))
        assertEquals("video", BlockingObserver.mediaKindForMime("Application/X-MpegURL"))
    }

    @Test
    fun mime_audio_types_report_audio() {
        assertEquals("audio", BlockingObserver.mediaKindForMime("audio/mpeg"))
        assertEquals("audio", BlockingObserver.mediaKindForMime("audio/ogg"))
        assertEquals("audio", BlockingObserver.mediaKindForMime("audio/mp4"))
    }

    @Test
    fun mime_non_media_reports_nothing() {
        assertNull(BlockingObserver.mediaKindForMime("text/html"))
        assertNull(BlockingObserver.mediaKindForMime("text/html; charset=utf-8"))
        assertNull(BlockingObserver.mediaKindForMime("application/json"))
        assertNull(BlockingObserver.mediaKindForMime("application/octet-stream"))
        assertNull(BlockingObserver.mediaKindForMime(""))
        assertNull(BlockingObserver.mediaKindForMime(null))
        assertNull(BlockingObserver.mediaKindForMime("   "))
    }

    // ---- URL + MIME combined (the plan's three cases) ----

    @Test
    fun extensionless_videoplayback_with_video_mime_reports_video() {
        assertEquals(
            "video",
            BlockingObserver.mediaKindFor(
                "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1",
                "video/mp4"
            )
        )
    }

    @Test
    fun mp4_url_with_html_mime_is_vetoed() {
        assertNull(
            BlockingObserver.mediaKindFor("https://example.com/clip.mp4", "text/html")
        )
    }

    @Test
    fun no_mime_degrades_to_url_only_as_before() {
        assertEquals(
            "video",
            BlockingObserver.mediaKindFor("https://example.com/clip.mp4", null)
        )
        assertEquals(
            "video",
            BlockingObserver.mediaKindFor("https://example.com/clip.mp4", "")
        )
        assertNull(
            BlockingObserver.mediaKindFor(
                "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1",
                null
            )
        )
        assertEquals(
            "audio",
            BlockingObserver.mediaKindFor("https://example.com/song.mp3", null)
        )
    }

    // ---- MediaLog (the live sink) ----

    @Test
    fun inspect_records_extensionless_url_with_video_mime() {
        val url = "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1"
        MediaLog.inspect(47001, url, "video/mp4")
        val found = MediaLog.forTab(47001)
        assertEquals(1, found.size)
        assertEquals(url, found[0].url)
        assertEquals("video", found[0].kind)
        assertEquals("net", found[0].source)
    }

    @Test
    fun inspect_dedupes_a_mime_hit_already_seen_by_url() {
        val url = "https://example.com/clip.mp4"
        MediaLog.inspect(47002, url)
        MediaLog.inspect(47002, url, "video/mp4")
        assertEquals(1, MediaLog.forTab(47002).size)
    }

    @Test
    fun inspect_vetoes_mp4_url_with_html_mime() {
        MediaLog.inspect(47003, "https://example.com/clip.mp4", "text/html")
        assertTrue(MediaLog.forTab(47003).isEmpty())
    }

    @Test
    fun inspect_labels_mime_manifests() {
        MediaLog.inspect(
            47004,
            "https://example.com/live/manifest",
            "application/x-mpegURL"
        )
        assertEquals("stream (HLS)", MediaLog.forTab(47004)[0].label)
        MediaLog.inspect(
            47005,
            "https://example.com/live/manifest",
            "application/dash+xml"
        )
        assertEquals("stream (DASH)", MediaLog.forTab(47005)[0].label)
    }

    @Test
    fun inspect_still_excludes_blob_even_with_video_mime() {
        MediaLog.inspect(47006, "blob:https://example.com/abc", "video/mp4")
        assertTrue(MediaLog.forTab(47006).isEmpty())
    }

    @Test
    fun inspect_url_only_path_unchanged() {
        MediaLog.inspect(47007, "https://example.com/hls/master.m3u8")
        val found = MediaLog.forTab(47007)
        assertEquals(1, found.size)
        assertEquals("stream (HLS)", found[0].label)
        assertEquals("video", found[0].kind)
    }

    // ---- headers-state + MIME-hit counter (BlockingObserver) ----

    @Test
    fun headers_state_text_starts_unknown_then_tracks_state_and_hits() {
        val observer = BlockingObserver(stubHost())
        assertEquals(
            "webRequest onHeadersReceived: unknown · 0 MIME hits",
            observer.headersReceivedStateText()
        )
        observer.handleNativeMessage(JSONObject("""{"type":"headers-state","on":true}"""), null)
        assertEquals(
            "webRequest onHeadersReceived: on · 0 MIME hits",
            observer.headersReceivedStateText()
        )
        observer.noteMimeHit()
        observer.noteMimeHit()
        assertEquals(
            "webRequest onHeadersReceived: on · 2 MIME hits",
            observer.headersReceivedStateText()
        )
        observer.handleNativeMessage(JSONObject("""{"type":"headers-state","on":false}"""), null)
        assertEquals(
            "webRequest onHeadersReceived: off · 2 MIME hits",
            observer.headersReceivedStateText()
        )
    }

    @Test
    fun onMediaNet_counts_mime_hits_and_vetoes_html() {
        val observed = mutableListOf<Triple<Int, String, String>>()
        val observer = BlockingObserver(stubHost(observed))
        observer.handleNativeMessage(
            JSONObject(
                """{"type":"media-net","url":"https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1","mime":"video/mp4","pageHost":"www.youtube.com","tabId":1}"""
            ),
            null
        )
        assertEquals(1, observed.size)
        assertEquals("video", observed[0].third)
        assertEquals(1, observer.mimeHits)
        observer.handleNativeMessage(
            JSONObject(
                """{"type":"media-net","url":"https://example.com/clip.mp4","mime":"text/html","pageHost":"www.youtube.com","tabId":1}"""
            ),
            null
        )
        assertEquals(1, observed.size)
        assertEquals(1, observer.mimeHits)
    }

    // ---- shipped background.js shape pins ----

    @Test
    fun shipped_background_js_registers_onHeadersReceived_guarded() {
        val js = backgroundJs()
        assertTrue(js.contains("browser.webRequest.onHeadersReceived.addListener("))
        assertTrue(js.contains("browser.webRequest.onHeadersReceived) {"))
        assertTrue(js.contains("\"responseHeaders\""))
        assertTrue(js.contains("{ type: \"headers-state\", on: headersReceivedOn }"))
        assertTrue(js.contains("mediaKindForMime: mediaKindForMime"))
        assertTrue(js.contains("if (mime) msg.mime = mime;"))
    }
}
