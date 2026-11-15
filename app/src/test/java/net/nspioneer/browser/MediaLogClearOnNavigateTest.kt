package net.nspioneer.browser

import net.nspioneer.browser.engine.GeckoEngine
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
 * Media log on navigation (alpha.53): the picker reads
 * MediaLog.forTab(tab.id), but the log was only cleared when
 * a tab closed — navigating within the same tab left the old
 * page's videos listed next to the new page's. The delegate
 * now clears the tab's log on a committed navigation to a
 * different http(s) URL. The real NavigationDelegate needs a
 * live GeckoSession, so the decision is pinned on the pure
 * helper it calls ([GeckoEngine.shouldClearMediaLogOnNavigate])
 * and the re-detection half is pinned on MediaLog itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaLogClearOnNavigateTest {

    // Distinct tab ids; MediaLog is a process-global object,
    // so every test clears its own ids afterwards.
    private val tabIds = listOf(53001, 53002, 53003)

    @After
    fun clearTabs() {
        for (id in tabIds) MediaLog.clear(id)
    }

    @Test
    fun clear_on_navigation_to_different_url() {
        assertTrue(
            GeckoEngine.shouldClearMediaLogOnNavigate(
                "https://example.com/page-a",
                "https://example.com/page-b",
            )
        )
        assertTrue(
            GeckoEngine.shouldClearMediaLogOnNavigate(
                "https://example.com/page-a",
                "https://other.example/page-a",
            )
        )
        assertTrue(
            GeckoEngine.shouldClearMediaLogOnNavigate(
                "http://example.com/page-a",
                "https://example.com/page-a",
            )
        )
    }

    @Test
    fun clear_on_first_navigation() {
        // No previous URL (fresh tab): the log is empty, so
        // the clear is a no-op — but the decision is still
        // "clear", exactly as the delegate reaches it.
        assertTrue(
            GeckoEngine.shouldClearMediaLogOnNavigate(
                null,
                "https://example.com/page-a",
            )
        )
    }

    @Test
    fun no_clear_on_same_url_reload() {
        assertFalse(
            GeckoEngine.shouldClearMediaLogOnNavigate(
                "https://example.com/page-a",
                "https://example.com/page-a",
            )
        )
    }

    @Test
    fun no_clear_on_about_blank() {
        assertFalse(
            GeckoEngine.shouldClearMediaLogOnNavigate(
                "https://example.com/page-a",
                "about:blank",
            )
        )
        assertFalse(
            GeckoEngine.shouldClearMediaLogOnNavigate(null, "about:blank")
        )
    }

    @Test
    fun no_clear_on_non_http_navigation() {
        assertFalse(
            GeckoEngine.shouldClearMediaLogOnNavigate(
                "https://example.com/page-a",
                "file:///sdcard/video.mp4",
            )
        )
        assertFalse(
            GeckoEngine.shouldClearMediaLogOnNavigate(
                "https://example.com/page-a",
                "data:text/html,<video src=x></video>",
            )
        )
    }

    @Test
    fun redetection_after_clear_lists_only_new_page_media() {
        val tabId = tabIds[0]
        // Old page: a DOM video and a net HLS manifest.
        MediaLog.noteDom(
            tabId,
            listOf(DetectedMedia("https://old.example/video.mp4", "video", "")),
        )
        MediaLog.inspect(tabId, "https://old.example/stream.m3u8")
        assertEquals(2, MediaLog.forTab(tabId).size)

        // The navigation clear.
        MediaLog.clear(tabId)
        assertEquals(0, MediaLog.forTab(tabId).size)

        // New page re-detects: only its own media is listed.
        MediaLog.noteDom(
            tabId,
            listOf(DetectedMedia("https://new.example/clip.webm", "video", "")),
        )
        MediaLog.inspect(tabId, "https://new.example/live.mpd")
        val listed = MediaLog.forTab(tabId)
        assertEquals(2, listed.size)
        assertTrue(listed.all { it.url.contains("new.example") })
        assertTrue(listed.none { it.url.contains("old.example") })
    }

    @Test
    fun reload_after_clear_keeps_redetected_media() {
        val tabId = tabIds[1]
        MediaLog.noteDom(
            tabId,
            listOf(DetectedMedia("https://example.com/video.mp4", "video", "")),
        )
        // Same-URL reload: no clear, and the probe re-reports
        // the same URL — MediaLog dedupes, so the entry is
        // still there exactly once.
        assertFalse(
            GeckoEngine.shouldClearMediaLogOnNavigate(
                "https://example.com/page",
                "https://example.com/page",
            )
        )
        MediaLog.noteDom(
            tabId,
            listOf(DetectedMedia("https://example.com/video.mp4", "video", "")),
        )
        val listed = MediaLog.forTab(tabId)
        assertEquals(1, listed.size)
        assertEquals("https://example.com/video.mp4", listed[0].url)
    }
}
