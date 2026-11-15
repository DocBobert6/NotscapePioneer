package net.nspioneer.browser

import net.nspioneer.browser.engine.deAmpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * De-AMP (alpha.34, Brave-style Layer 1): the pure URL rewriter.
 *
 * Runs under Robolectric like DownloadsStoreTest: the rewriter
 * uses `android.net.Uri`, which throws on the plain-JVM
 * android.jar stubs.
 *
 * Every AMP form from the investigation report is covered, plus
 * the non-misfire cases: a URL with a genuine `/amp` segment
 * that is not AMP must pass through untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeAmpTest {

    // -- Google AMP cache forms --

    @Test
    fun cache_c_s_rewrites_to_canonical_https() {
        assertEquals(
            "https://www.amarujala.com/amp/some-article",
            deAmpUrl(
                "https://www-amarujala-com.cdn.ampproject.org/c/s/www.amarujala.com/amp/some-article"
            )
        )
    }

    @Test
    fun cache_v_s_rewrites_to_canonical_https() {
        assertEquals(
            "https://m.haber7.com/amphtml/story-1",
            deAmpUrl(
                "https://m-haber7-com.cdn.ampproject.org/v/s/m.haber7.com/amphtml/story-1"
            )
        )
    }

    @Test
    fun cache_c_without_s_rewrites_to_canonical_http() {
        // /c/ with no /s/ is the http-origin cache form.
        assertEquals(
            "http://example.com/news/item",
            deAmpUrl("https://example-com.cdn.ampproject.org/c/example.com/news/item")
        )
    }

    @Test
    fun cache_v_without_s_rewrites_to_canonical_http() {
        assertEquals(
            "http://example.com/news/item",
            deAmpUrl("https://example-com.cdn.ampproject.org/v/example.com/news/item")
        )
    }

    @Test
    fun cache_bare_ampproject_host_rewrites() {
        assertEquals(
            "https://example.com/article",
            deAmpUrl("https://cdn.ampproject.org/c/s/example.com/article")
        )
    }

    @Test
    fun cache_empty_rest_rewrites_to_root() {
        assertEquals(
            "https://example.com/",
            deAmpUrl("https://example-com.cdn.ampproject.org/c/s/example.com")
        )
    }

    @Test
    fun cache_keeps_query_and_fragment_drops_tracking_params() {
        assertEquals(
            "https://example.com/article?x=1#section",
            deAmpUrl(
                "https://example-com.cdn.ampproject.org/c/s/example.com/article" +
                    "?x=1&amp_js_v=0.1&amp_gsa=2#section"
            )
        )
    }

    @Test
    fun cache_malformed_path_is_untouched() {
        assertNull(deAmpUrl("https://example-com.cdn.ampproject.org/x/y/z"))
        assertNull(deAmpUrl("https://example-com.cdn.ampproject.org/c/s"))
    }

    // -- Google AMP viewer --

    @Test
    fun google_amp_viewer_rewrites() {
        assertEquals(
            "https://example.com/news/item",
            deAmpUrl("https://www.google.com/amp/s/example.com/news/item")
        )
    }

    @Test
    fun google_country_domain_amp_viewer_rewrites() {
        assertEquals(
            "https://example.co.uk/news/item",
            deAmpUrl("https://www.google.co.uk/amp/s/example.co.uk/news/item")
        )
    }

    @Test
    fun non_google_amp_s_path_is_untouched() {
        // /amp/s/ on a non-Google host is not the viewer form.
        assertNull(deAmpUrl("https://example.com/amp/s/foo.com/bar"))
    }

    // -- Publisher AMP forms --

    @Test
    fun publisher_trailing_amp_suffix_stripped() {
        assertEquals(
            "https://example.com/article/123",
            deAmpUrl("https://example.com/article/123/amp")
        )
    }

    @Test
    fun publisher_trailing_amp_slash_stripped() {
        assertEquals(
            "https://example.com/article/123",
            deAmpUrl("https://example.com/article/123/amp/")
        )
    }

    @Test
    fun publisher_amp_query_param_stripped_others_kept() {
        assertEquals(
            "https://example.com/article?foo=1",
            deAmpUrl("https://example.com/article?foo=1&amp=1")
        )
        assertEquals(
            "https://example.com/article",
            deAmpUrl("https://example.com/article?amp=1")
        )
    }

    @Test
    fun publisher_output_amp_param_stripped() {
        assertEquals(
            "https://example.com/article?foo=1",
            deAmpUrl("https://example.com/article?foo=1&output=amp")
        )
        assertEquals(
            "https://example.com/article",
            deAmpUrl("https://example.com/article?output=amp")
        )
    }

    @Test
    fun publisher_suffix_and_param_stripped_together() {
        assertEquals(
            "https://example.com/article/123",
            deAmpUrl("https://example.com/article/123/amp?amp=1")
        )
    }

    @Test
    fun publisher_tracking_params_dropped_on_rewrite() {
        assertEquals(
            "https://example.com/article",
            deAmpUrl("https://example.com/article/amp?amp_js_v=0.1&amp_gsa=3")
        )
    }

    // -- Non-misfire cases: not AMP, must pass through --

    @Test
    fun bare_amp_path_is_untouched() {
        // /amp on its own is a section page, not an article.
        assertNull(deAmpUrl("https://example.com/amp"))
        assertNull(deAmpUrl("https://example.com/amp/"))
    }

    @Test
    fun middle_amp_segment_is_untouched() {
        assertNull(deAmpUrl("https://example.com/amp/page"))
    }

    @Test
    fun amp_word_segment_is_untouched() {
        assertNull(deAmpUrl("https://example.com/ampersand?x=1"))
    }

    @Test
    fun output_other_value_is_untouched() {
        assertNull(deAmpUrl("https://example.com/article?output=json"))
    }

    @Test
    fun amp_zero_is_untouched() {
        assertNull(deAmpUrl("https://example.com/article?amp=0"))
    }

    @Test
    fun normal_url_is_untouched() {
        assertNull(deAmpUrl("https://example.com/article/123"))
        assertNull(deAmpUrl("https://example.com/article?foo=1&bar=2"))
    }

    @Test
    fun non_http_scheme_is_untouched() {
        assertNull(deAmpUrl("ftp://example.com/article/amp"))
    }

    @Test
    fun blank_and_malformed_are_untouched() {
        assertNull(deAmpUrl(""))
        assertNull(deAmpUrl("not a url at all"))
    }

    // -- Idempotency: rewriting a canonical URL changes nothing --

    @Test
    fun rewrite_is_idempotent() {
        val amp = "https://www-amarujala-com.cdn.ampproject.org/c/s/www.amarujala.com/news/x"
        val canonical = deAmpUrl(amp)
        assertEquals("https://www.amarujala.com/news/x", canonical)
        assertNull(deAmpUrl(canonical!!))

        val pub = "https://example.com/article/123/amp?amp=1"
        val pubCanonical = deAmpUrl(pub)
        assertEquals("https://example.com/article/123", pubCanonical)
        assertNull(deAmpUrl(pubCanonical!!))
    }
}
