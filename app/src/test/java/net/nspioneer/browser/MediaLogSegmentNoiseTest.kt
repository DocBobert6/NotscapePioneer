package net.nspioneer.browser

import net.nspioneer.browser.engine.BlockingObserver
import net.nspioneer.browser.media.DetectedMedia
import net.nspioneer.browser.media.MediaLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Segment / init noise in the DOM half (alpha.57): the alpha.50
 * filter only ran on the net half, so the DOM scan's resource
 * loop reported every fetched HLS segment and the picker showed
 * 61 rows for one video — the 3 manifests buried under ~58
 * segment and init-segment rows. The filter now runs in
 * probe.js add() and in MediaLog.noteDom, and the shared token
 * set gains the init-segment tokens (_init.mp4 / -init.mp4).
 * Only downloadable items may reach the media sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaLogSegmentNoiseTest {

    // Distinct tab ids; MediaLog is a process-global object,
    // so every test clears its own ids afterwards.
    private val tabIds = listOf(57001, 57002, 57003, 57004)

    @After
    fun clearTabs() {
        for (id in tabIds) MediaLog.clear(id)
    }

    private fun domItem(url: String) = DetectedMedia(url, "video", "", source = "dom")

    @Test
    fun noteDom_drops_segment_and_init_urls() {
        val tabId = tabIds[0]
        MediaLog.noteDom(
            tabId,
            listOf(
                domItem("https://usa7-nas30-1.shegu.net/video/video_360p_000.m4s?KEY1=mFkB77t"),
                domItem("https://usa7-nas30-1.shegu.net/hls/audio_1_1.m4s?KEY1=5PdEWNAOg-pk"),
                domItem("https://cdn.example.com/v/segment-42.mp4"),
                domItem("https://cdn.example.com/v/fragment-7.mp4"),
                domItem("https://example.com/clip.mp4?bytes=0-99999"),
                domItem("https://usa7-nas30-1.shegu.net/video/video_360p_init.mp4?KEY1=4Uz62kr0Y"),
                domItem("https://usa7-nas30-1.shegu.net/hls/audio_1_init.mp4?KEY1=ycRqWDKUgZ"),
                domItem("https://cdn.example.com/dash/video-init.mp4"),
                domItem("https://hls.shegu.net/44487.m3u8?quality=360p&sign=5c92"),
                domItem("https://example.com/clip.mp4"),
            ),
        )
        val urls = MediaLog.forTab(tabId).map { it.url }
        assertEquals(
            listOf(
                "https://hls.shegu.net/44487.m3u8?quality=360p&sign=5c92",
                "https://example.com/clip.mp4",
            ),
            urls,
        )
    }

    @Test
    fun noteDom_keeps_manifests_and_direct_media() {
        val tabId = tabIds[1]
        MediaLog.noteDom(
            tabId,
            listOf(
                domItem("https://hls.shegu.net/m3u8_proxy/video_360p.m3u8?KEY2=1794826500"),
                domItem("https://hls.shegu.net/m3u8_proxy/audio_1.m3u8?KEY2=1794826500"),
                domItem("https://example.com/dash/manifest.mpd"),
                domItem("https://example.com/movie.webm"),
            ),
        )
        assertEquals(4, MediaLog.forTab(tabId).size)
    }

    @Test
    fun isSegmentNoise_pins_init_tokens() {
        // Init segments are noise on either half.
        assertTrue(MediaLog.isSegmentNoise("https://cdn.example.com/v/video_360p_init.mp4?KEY1=x"))
        assertTrue(MediaLog.isSegmentNoise("https://cdn.example.com/v/audio_1_init.mp4"))
        assertTrue(MediaLog.isSegmentNoise("https://cdn.example.com/dash/video-init.mp4"))
        // A bare init.mp4 has no _init / -init token, so it is
        // kept — the scoped token does not touch ordinary files.
        assertFalse(MediaLog.isSegmentNoise("https://example.com/init.mp4"))
        // The pre-existing tokens still hold.
        assertTrue(MediaLog.isSegmentNoise("https://cdn.example.com/dash/seg-12.m4s"))
        assertTrue(MediaLog.isSegmentNoise("https://cdn.example.com/dash/segment-42.mp4"))
        assertTrue(MediaLog.isSegmentNoise("https://cdn.example.com/dash/fragment-7.mp4"))
        assertTrue(MediaLog.isSegmentNoise("https://example.com/clip.mp4?bytes=0-99999"))
        // Manifests are never noise, even with a noise token
        // in the path.
        assertFalse(MediaLog.isSegmentNoise("https://example.com/hls/master.m3u8"))
        assertFalse(MediaLog.isSegmentNoise("https://example.com/hls/segment-9/master.m3u8"))
        assertFalse(MediaLog.isSegmentNoise("https://example.com/dash/manifest.mpd"))
        assertFalse(MediaLog.isSegmentNoise("https://example.com/clip.mp4"))
    }

    @Test
    fun classifiers_agree_on_the_token_set() {
        // The two Kotlin copies must give the same answer on
        // every shape of URL, so the net half and the DOM half
        // can never disagree about what is noise. The JS copy
        // is pinned by the node harness (dom-noise-harness.js).
        val urls = listOf(
            "https://cdn.example.com/dash/seg-12.m4s",
            "https://cdn.example.com/dash/seg-12.m4s?KEY1=x",
            "https://cdn.example.com/dash/segment-42.mp4",
            "https://cdn.example.com/dash/fragment-7.mp4",
            "https://example.com/clip.mp4?bytes=0-99999",
            "https://cdn.example.com/v/video_360p_init.mp4?KEY1=x",
            "https://cdn.example.com/v/audio_1_init.mp4",
            "https://cdn.example.com/dash/video-init.mp4",
            "https://example.com/init.mp4",
            "https://example.com/hls/master.m3u8",
            "https://example.com/hls/segment-9/master.m3u8",
            "https://example.com/dash/manifest.mpd",
            "https://example.com/clip.mp4",
            "https://example.com/movie.webm",
            "https://example.com/song.mp3",
            "",
        )
        for (url in urls) {
            assertEquals(
                "classifiers disagree on $url",
                BlockingObserver.isSegmentNoise(url),
                MediaLog.isSegmentNoise(url),
            )
        }
    }

    @Test
    fun noteDomItem_drops_noise_too() {
        // noteDomItem funnels through noteDom, so a single
        // segment observation is dropped the same way.
        val tabId = tabIds[2]
        MediaLog.noteDomItem(
            tabId,
            "video",
            "https://cdn.example.com/dash/seg-12.m4s",
        )
        MediaLog.noteDomItem(tabId, "video", "https://example.com/clip.mp4")
        assertEquals(
            listOf("https://example.com/clip.mp4"),
            MediaLog.forTab(tabId).map { it.url },
        )
    }
}
