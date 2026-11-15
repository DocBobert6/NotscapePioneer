package net.nspioneer.browser

import net.nspioneer.browser.engine.BlockingHost
import net.nspioneer.browser.engine.BlockingObserver
import net.nspioneer.browser.engine.FilterEngine
import net.nspioneer.browser.media.MediaLog
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

/**
 * Probe timing + coverage (alpha.50, gap plan row A-3):
 * segment-noise filtering, the 10 KB size floor, and the
 * `media-net sightings: N (M manifests)` counter. The
 * classifier cases are pinned on both Kotlin classifiers
 * ([BlockingObserver] and [MediaLog]) and on the shipped
 * `background.js` / `probe.js` shapes; the JS behaviour
 * itself (listener-level filtering, the floor at the
 * report) was validated out-of-band with a Node stub
 * harness against the same file
 * (`alpha50-verify/background-js-harness.js`, 40 checks,
 * scratch, not shipped) — same split as alpha.47.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaNetCounterTest {

    // Distinct tab ids per test; MediaLog is a process-global
    // object, so every test clears its own ids afterwards.
    private val tabIds = listOf(50001, 50002, 50003, 50004, 50005, 50006, 50007, 50008)

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
            if (pageHost == "www.youtube.com") 50001 else -1

        override fun onMediaObserved(tabId: Int, url: String, kind: String) {
            observed.add(Triple(tabId, url, kind))
        }

        override fun disabledHosts(): Set<String> = emptySet()
    }

    private fun backgroundJs(): String =
        RuntimeEnvironment.getApplication().assets
            .open("extensions/pioneer-probe/background.js")
            .bufferedReader().use { it.readText() }

    private fun probeJs(): String =
        RuntimeEnvironment.getApplication().assets
            .open("extensions/pioneer-probe/probe.js")
            .bufferedReader().use { it.readText() }

    private fun mediaNetJson(
        url: String,
        mime: String? = null,
        contentLength: String? = null
    ): JSONObject {
        val json = JSONObject().apply {
            put("type", "media-net")
            put("url", url)
            put("pageHost", "www.youtube.com")
            put("tabId", 1)
            if (mime != null) put("mime", mime)
            if (contentLength != null) put("contentLength", contentLength)
        }
        return json
    }

    // ---- counter state text ----

    @Test
    fun counter_starts_at_zero() {
        val observer = BlockingObserver(stubHost())
        assertEquals("media-net sightings: 0 (0 manifests)", observer.mediaNetStateText())
        assertEquals(0, observer.mediaNetSightings)
        assertEquals(0, observer.mediaNetManifests)
    }

    @Test
    fun noteMediaNetSighting_increments_both_counts() {
        val observer = BlockingObserver(stubHost())
        observer.noteMediaNetSighting(false)
        assertEquals("media-net sightings: 1 (0 manifests)", observer.mediaNetStateText())
        observer.noteMediaNetSighting(true)
        assertEquals("media-net sightings: 2 (1 manifests)", observer.mediaNetStateText())
        observer.noteMediaNetSighting(false)
        assertEquals("media-net sightings: 3 (1 manifests)", observer.mediaNetStateText())
        assertEquals(3, observer.mediaNetSightings)
        assertEquals(1, observer.mediaNetManifests)
    }

    // ---- onMediaNet: counting + filtering end to end ----

    @Test
    fun onMediaNet_counts_sightings_and_manifests_only_for_manifests() {
        val observed = mutableListOf<Triple<Int, String, String>>()
        val observer = BlockingObserver(stubHost(observed))
        // A manifest counts as a sighting AND a manifest.
        observer.handleNativeMessage(
            mediaNetJson("https://example.com/hls/master.m3u8"), null
        )
        assertEquals(1, observer.mediaNetSightings)
        assertEquals(1, observer.mediaNetManifests)
        assertEquals(1, observed.size)
        // An extension-less video MIME hit counts as a
        // sighting only.
        observer.handleNativeMessage(
            mediaNetJson(
                "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1",
                "video/mp4"
            ),
            null
        )
        assertEquals(2, observer.mediaNetSightings)
        assertEquals(1, observer.mediaNetManifests)
        assertEquals(2, observed.size)
        // A plain direct file counts as a sighting only.
        observer.handleNativeMessage(
            mediaNetJson("https://example.com/clip.mp4", "video/mp4", "51200"),
            null
        )
        assertEquals(3, observer.mediaNetSightings)
        assertEquals(1, observer.mediaNetManifests)
        assertEquals(3, observed.size)
        // A non-media MIME vetoes: no count, no observation.
        observer.handleNativeMessage(
            mediaNetJson("https://example.com/clip.mp4", "text/html"), null
        )
        assertEquals(3, observer.mediaNetSightings)
        assertEquals(3, observed.size)
    }

    @Test
    fun onMediaNet_filters_segment_noise_and_small_sizes() {
        val observed = mutableListOf<Triple<Int, String, String>>()
        val observer = BlockingObserver(stubHost(observed))
        // A .m4s segment is filtered: no count, no row.
        observer.handleNativeMessage(
            mediaNetJson("https://cdn.example.com/dash/seg-12.m4s", "video/mp4"), null
        )
        assertEquals(0, observer.mediaNetSightings)
        assertEquals(0, observed.size)
        // A bytes= ranged request is filtered.
        observer.handleNativeMessage(
            mediaNetJson("https://example.com/clip.mp4?bytes=0-99999"), null
        )
        assertEquals(0, observer.mediaNetSightings)
        assertEquals(0, observed.size)
        // A 5 KB known size is floored.
        observer.handleNativeMessage(
            mediaNetJson("https://example.com/clip.mp4", "video/mp4", "5120"), null
        )
        assertEquals(0, observer.mediaNetSightings)
        assertEquals(0, observed.size)
        // A 50 KB known size passes and counts.
        observer.handleNativeMessage(
            mediaNetJson("https://example.com/clip.mp4", "video/mp4", "51200"), null
        )
        assertEquals(1, observer.mediaNetSightings)
        assertEquals(1, observed.size)
        // A small manifest is never floored: counts as a
        // sighting AND a manifest.
        observer.handleNativeMessage(
            mediaNetJson(
                "https://example.com/hls/master.m3u8",
                "application/x-mpegURL",
                "2048"
            ),
            null
        )
        assertEquals(2, observer.mediaNetSightings)
        assertEquals(1, observer.mediaNetManifests)
        assertEquals(2, observed.size)
    }

    @Test
    fun onMediaNet_counts_before_attribution_like_mime_hits() {
        val observed = mutableListOf<Triple<Int, String, String>>()
        val observer = BlockingObserver(stubHost(observed))
        // An unattributable manifest still counts as a
        // sighting (before attribution, like the MIME hit)
        // but never reaches onMediaObserved.
        observer.handleNativeMessage(
            JSONObject(
                """{"type":"media-net","url":"https://example.com/hls/master.m3u8","pageHost":"unknown.example","tabId":1}"""
            ),
            null
        )
        assertEquals(1, observer.mediaNetSightings)
        assertEquals(1, observer.mediaNetManifests)
        assertEquals(0, observed.size)
    }

    // ---- classifier pins (BlockingObserver half) ----

    @Test
    fun isSegmentNoise_pins() {
        assertTrue(BlockingObserver.isSegmentNoise("https://cdn.example.com/dash/seg-12.m4s"))
        assertTrue(BlockingObserver.isSegmentNoise("https://cdn.example.com/dash/segment-42.mp4"))
        assertTrue(BlockingObserver.isSegmentNoise("https://cdn.example.com/dash/fragment-7.mp4"))
        assertTrue(BlockingObserver.isSegmentNoise("https://example.com/clip.mp4?bytes=0-99999"))
        assertFalse(BlockingObserver.isSegmentNoise("https://example.com/hls/master.m3u8"))
        assertFalse(BlockingObserver.isSegmentNoise("https://example.com/dash/manifest.mpd"))
        assertFalse(BlockingObserver.isSegmentNoise("https://example.com/clip.mp4"))
        assertFalse(BlockingObserver.isSegmentNoise(""))
    }

    @Test
    fun isManifest_pins() {
        assertTrue(BlockingObserver.isManifest("https://example.com/hls/master.m3u8", null))
        assertTrue(BlockingObserver.isManifest("https://example.com/dash/manifest.mpd", null))
        assertTrue(
            BlockingObserver.isManifest(
                "https://example.com/live/manifest",
                "application/x-mpegURL"
            )
        )
        assertTrue(
            BlockingObserver.isManifest(
                "https://example.com/live/manifest",
                "application/dash+xml"
            )
        )
        assertFalse(BlockingObserver.isManifest("https://example.com/clip.mp4", "video/mp4"))
        assertFalse(BlockingObserver.isManifest("https://example.com/clip.mp4", null))
    }

    @Test
    fun mediaKindFor_applies_noise_filter_and_size_floor() {
        assertNull(
            BlockingObserver.mediaKindFor("https://cdn.example.com/dash/seg-12.m4s", "video/mp4")
        )
        assertNull(
            BlockingObserver.mediaKindFor("https://example.com/clip.mp4?bytes=0-99999", null)
        )
        assertNull(
            BlockingObserver.mediaKindFor("https://example.com/clip.mp4", "video/mp4", 5120L)
        )
        assertEquals(
            "video",
            BlockingObserver.mediaKindFor("https://example.com/clip.mp4", "video/mp4", 51200L)
        )
        assertEquals(
            "video",
            BlockingObserver.mediaKindFor("https://example.com/clip.mp4", "video/mp4", null)
        )
        assertEquals(
            "video",
            BlockingObserver.mediaKindFor(
                "https://example.com/hls/master.m3u8",
                "application/x-mpegURL",
                2048L
            )
        )
    }

    // ---- MediaLog (the live sink) ----

    @Test
    fun inspect_filters_segment_noise() {
        MediaLog.inspect(50002, "https://cdn.example.com/dash/seg-12.m4s", "video/mp4")
        assertTrue(MediaLog.forTab(50002).isEmpty())
        MediaLog.inspect(50002, "https://example.com/clip.mp4?bytes=0-99999")
        assertTrue(MediaLog.forTab(50002).isEmpty())
    }

    @Test
    fun inspect_applies_size_floor_but_never_to_manifests() {
        MediaLog.inspect(50003, "https://example.com/clip.mp4", "video/mp4", 5120L)
        assertTrue(MediaLog.forTab(50003).isEmpty())
        MediaLog.inspect(50004, "https://example.com/clip.mp4", "video/mp4", 51200L)
        assertEquals(1, MediaLog.forTab(50004).size)
        MediaLog.inspect(
            50005,
            "https://example.com/hls/master.m3u8",
            "application/x-mpegURL",
            2048L
        )
        val found = MediaLog.forTab(50005)
        assertEquals(1, found.size)
        assertEquals("stream (HLS)", found[0].label)
    }

    // ---- shipped probe shape pins ----

    @Test
    fun shipped_background_js_carries_noise_filter_and_floor() {
        val js = backgroundJs()
        assertTrue(js.contains("function isSegmentNoise(url)"))
        assertTrue(js.contains("function isManifest(url, mime)"))
        assertTrue(js.contains("var MEDIA_SIZE_FLOOR_BYTES = 10240;"))
        assertTrue(js.contains("mediaKindFor(url, contentType, contentLength)"))
        assertTrue(js.contains("isSegmentNoise: isSegmentNoise"))
        assertTrue(js.contains("isManifest: isManifest"))
    }

    @Test
    fun shipped_probe_js_rescans_on_metadata_events() {
        val js = probeJs()
        assertTrue(js.contains("document.addEventListener(\"loadedmetadata\""))
        assertTrue(js.contains("document.addEventListener(\"durationchange\""))
        assertTrue(js.contains("report(\"loadedmetadata\")"))
        assertTrue(js.contains("report(\"durationchange\")"))
    }
}
