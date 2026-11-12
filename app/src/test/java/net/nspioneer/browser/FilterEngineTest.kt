package net.nspioneer.browser

import net.nspioneer.browser.engine.FilterEngine
import net.nspioneer.browser.engine.UserBlockRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Scorecard row A (Kotlin half): the fixed sample-URL table.
 * Every verdict here must agree with Mariner's FilterEngine on
 * the same inputs AND with the probe background script's JS
 * mirror (`background.js`) on the same compiled rule set — the
 * JS half is checked by the builder's harness against this
 * table's verdicts, and row K (popups) has its own cases below
 * in the same table.
 *
 * The rule set is synthetic and fixed (no network): list 0 is
 * parsed as an ad list, list 1 as a tracker list, then merged
 * and loaded via [FilterEngine.loadForTests]. Real-list parse
 * counts (per list, from the downloaded 7 lists) are reported
 * by the builder's verification run, not asserted here — CI
 * must not need the network.
 */
class FilterEngineTest {

    companion object {
        /** Fixed sample ad list (parsed as list 0, CAT_ADS). */
        const val SAMPLE_ADS = """
! sample ad list
||ads.example.com^
||tracker.example.com^${'$'}third-party
||cdn.example.net^/ads/
||video.example.org^${'$'}domain=pub.example|news.example
||telemetry.example^${'$'}important
@@||telemetry.example^
||popunder.example^${'$'}document
||popupnet.example^${'$'}popup
||sneaky.example^${'$'}document,domain=pub.example
||sneaky2.example^${'$'}document
@@||sneaky2.example^${'$'}document
||gone.example^${'$'}badfilter
||gone.example^
||doubleclick.net^
||jwplayer.com^${'$'}domain=publisher.example
||shared.example.net^
@@||good.example.com^
@@||ads.example.com^${'$'}domain=good.example
example.com##.ad-banner
pub.example##.sponsored-box
##.generic-ad
example.com#@#.generic-ad
@@||generichide.example^${'$'}generichide
@@${'$'}generichide,domain=ghide.example
"""

        /** Fixed sample tracker list (parsed as list 1, CAT_TRACKER). */
        const val SAMPLE_TRACKERS = """
||track.example.org^
||shared.example.net^
"""

        /** The sample-URL table: url, isMainFrame, pageHost,
         *  expected category (null = allow). */
        data class Row(
            val url: String,
            val mainFrame: Boolean,
            val pageHost: String,
            val expected: Int?
        )

        val SAMPLE_URLS = listOf(
            Row("https://ads.example.com/banner.js", false, "news.example", FilterEngine.CAT_ADS),
            Row("https://sub.ads.example.com/img.png", false, "news.example", FilterEngine.CAT_ADS),
            Row("https://tracker.example.com/x.js", false, "tracker.example.com", null),
            Row("https://tracker.example.com/x.js", false, "news.example", FilterEngine.CAT_ADS),
            Row("https://cdn.example.net/ads/banner.js", false, "news.example", FilterEngine.CAT_ADS),
            Row("https://cdn.example.net/other.js", false, "news.example", null),
            Row("https://video.example.org/play", false, "pub.example", FilterEngine.CAT_ADS),
            Row("https://video.example.org/play", false, "other.example", null),
            Row("https://telemetry.example/ping", false, "any.example", FilterEngine.CAT_ADS),
            Row("https://good.example.com/ads.js", false, "news.example", null),
            Row("https://ads.example.com/x.js", false, "good.example", null),
            Row("https://ads.example.com/x.js", false, "other.example", FilterEngine.CAT_ADS),
            Row("https://doubleclick.net/x", false, "news.example", FilterEngine.CAT_TRACKER),
            Row("https://track.example.org/x", false, "news.example", FilterEngine.CAT_TRACKER),
            Row("https://shared.example.net/x", false, "news.example", FilterEngine.CAT_TRACKER),
            Row("https://googlevideo.com/videoplayback", false, "youtube.com", null),
            // neverBlock beats a matching $domain= scoped rule.
            Row("https://jwplayer.com/x", false, "publisher.example", null),
            Row("https://example.com/page", true, "", null),
            Row("https://ads.example.com/x", true, "news.example", null),
            Row("https://gone.example/x", false, "news.example", null),
            Row("https://news.example/ads/banner", false, "news.example", FilterEngine.CAT_ADS),
            Row("https://example.com/gtag/js?id=1", false, "example.com", FilterEngine.CAT_ADS),
            Row("https://userblocked.example/x", false, "news.example", FilterEngine.CAT_OTHER),
            Row("https://exact.example/path", false, "news.example", FilterEngine.CAT_OTHER),
            Row("https://exact.example/path?cachebuster=1", false, "news.example", FilterEngine.CAT_OTHER),
            Row("https://exact.example/other", false, "news.example", null)
        )

        /** Scorecard row K: $document/$popup cases — url,
         *  pageHost, expected isDocumentBlocked. */
        data class DocRow(val url: String, val pageHost: String, val expected: Boolean)

        val SAMPLE_DOCUMENT_URLS = listOf(
            DocRow("https://popunder.example/", "news.example", true),
            DocRow("https://popupnet.example/x", "", true),
            DocRow("https://sneaky.example/x", "pub.example", true),
            DocRow("https://sneaky.example/x", "other.example", false),
            DocRow("https://sneaky2.example/x", "news.example", false),
            DocRow("https://googlevideo.com/x", "", false),
            DocRow("https://ads.example.com/x", "", false),
            DocRow("https://sub.popunder.example/x", "news.example", true)
        )
    }

    private fun loadSampleLists() {
        val data = FilterEngine.FilterData()
        FilterEngine.parseInto(0, SAMPLE_ADS, data)
        FilterEngine.parseInto(1, SAMPLE_TRACKERS, data, FilterEngine.CAT_TRACKER)
        FilterEngine.loadForTests(data)
    }

    @Before
    fun setUp() {
        FilterEngine.resetForTests()
        loadSampleLists()
        FilterEngine.userNetRules = listOf(
            UserBlockRules.NetRule.Host("userblocked.example"),
            UserBlockRules.NetRule.Url("https://exact.example/path")
        )
    }

    @Test
    fun sampleTableVerdictsAgreeWithMarinerOrder() {
        for (row in SAMPLE_URLS) {
            val got = FilterEngine.blockCategory(
                row.url, row.mainFrame, true, true, row.pageHost
            )
            assertEquals(
                "verdict for ${row.url} (mainFrame=${row.mainFrame}, page=${row.pageHost})",
                row.expected, got
            )
        }
    }

    @Test
    fun documentPopupTableVerdicts() {
        for (row in SAMPLE_DOCUMENT_URLS) {
            assertEquals(
                "isDocumentBlocked for ${row.url} on ${row.pageHost}",
                row.expected,
                FilterEngine.isDocumentBlocked(row.url, row.pageHost)
            )
        }
    }

    @Test
    fun documentHostParentWalk() {
        assertTrue(FilterEngine.isDocumentHost("popunder.example"))
        assertTrue(FilterEngine.isDocumentHost("sub.popunder.example"))
        assertFalse(FilterEngine.isDocumentHost("good.example.com"))
        assertFalse(FilterEngine.isDocumentHost("ads.example.com"))
    }

    @Test
    fun documentRuleCountsFromSampleLists() {
        val counts = FilterEngine.parseListCounts(0, SAMPLE_ADS)
        // popunder + popupnet + sneaky (scoped) + sneaky2 = 4.
        assertEquals(4, counts.documentRules)
        val trackerCounts = FilterEngine.parseListCounts(1, SAMPLE_TRACKERS)
        assertEquals(0, trackerCounts.documentRules)
    }

    @Test
    fun categoryForListMapsAdsAndTrackers() {
        assertEquals(FilterEngine.CAT_ADS, FilterEngine.categoryForList(0))
        assertEquals(FilterEngine.CAT_TRACKER, FilterEngine.categoryForList(1))
        assertEquals(FilterEngine.CAT_ADS, FilterEngine.categoryForList(2))
        assertEquals(FilterEngine.CAT_TRACKER, FilterEngine.categoryForList(3))
        assertEquals(FilterEngine.CAT_ADS, FilterEngine.categoryForList(4))
        assertEquals(FilterEngine.CAT_ADS, FilterEngine.categoryForList(5))
        assertEquals(FilterEngine.CAT_ADS, FilterEngine.categoryForList(6))
    }

    @Test
    fun parseDisabledFilterLists() {
        assertEquals(setOf(1, 3), FilterEngine.parseDisabledFilterLists("1,3"))
        assertEquals(setOf(2), FilterEngine.parseDisabledFilterLists("a,2"))
        assertEquals(setOf(1, 3), FilterEngine.parseDisabledFilterLists("1, 3"))
        assertEquals(emptySet<Int>(), FilterEngine.parseDisabledFilterLists(""))
    }

    @Test
    fun validateAbpRulesReportsOneBasedBadLines() {
        val text = "||ok.example^\nnot a rule at all\n! comment\n\n##.generic"
        assertEquals(listOf(2), FilterEngine.validateAbpRules(text))
        assertEquals(emptyList<Int>(), FilterEngine.validateAbpRules("||ok.example^\n##.x"))
    }

    @Test
    fun cosmeticCssDomainFirstGenerichideSuppressesGenericOnly() {
        val example = FilterEngine.cosmeticCss("example.com")
        assertTrue(example.contains(".ad-banner"))
        // #@# removes the generic selector on example.com only.
        assertFalse(example.contains(".generic-ad"))
        val pub = FilterEngine.cosmeticCss("pub.example")
        assertTrue(pub.contains(".sponsored-box"))
        assertTrue(pub.contains(".generic-ad"))
        // $generichide hosts lose generic hiding entirely (and
        // have no domain selectors here, so the sheet is empty).
        assertEquals("", FilterEngine.cosmeticCss("generichide.example"))
        assertEquals("", FilterEngine.cosmeticCss("ghide.example"))
        assertEquals("", FilterEngine.cosmeticCss("sub.ghide.example"))
        // A host with no domain rules still gets the generic sheet.
        assertTrue(FilterEngine.cosmeticCss("plain.example").contains(".generic-ad"))
    }

    @Test
    fun cosmeticCssPartsSplitPreciseAndGeneric() {
        val parts = FilterEngine.cosmeticCssParts("pub.example")
        assertTrue(parts.preciseCss.contains(".sponsored-box"))
        assertTrue(parts.genericCss.contains(".generic-ad"))
        assertFalse(parts.generichide)
        val off = FilterEngine.cosmeticCssParts("generichide.example")
        assertTrue(off.generichide)
        assertEquals("", off.genericCss)
        assertEquals("", FilterEngine.cosmeticCssParts("plain.example").preciseCss)
    }

    @Test
    fun blockedCountsPerTabAndSession() {
        FilterEngine.noteBlockedFor(1, FilterEngine.CAT_ADS)
        FilterEngine.noteBlockedFor(1, FilterEngine.CAT_ADS)
        FilterEngine.noteBlockedFor(2, FilterEngine.CAT_TRACKER)
        assertEquals(3L, FilterEngine.sessionBlockedCount())
        assertEquals(2L, FilterEngine.blockedCountFor(1))
        assertEquals(1L, FilterEngine.blockedCountFor(2))
        FilterEngine.resetTabCount(1)
        assertEquals(0L, FilterEngine.blockedCountFor(1))
        assertEquals(3L, FilterEngine.sessionBlockedCount())
    }

    @Test
    fun historyRoundTripsAndKeepsNewestFirst() {
        FilterEngine.loadBlockedHistory(
            "2026-11-07=3,1,0\n2026-11-06=0,0,2\n"
        )
        val view = FilterEngine.blockedHistory()
        assertEquals(listOf(3L, 1L, 0L), view["2026-11-07"])
        assertEquals(listOf(0L, 0L, 2L), view["2026-11-06"])
    }

    @Test
    fun historyIgnoresMalformedLines() {
        FilterEngine.loadBlockedHistory(
            "not-a-date=1,2,3\n2099-01-01=3,1\n2026-11-05=1,2,3\n"
        )
        val view = FilterEngine.blockedHistory()
        assertFalse(view.containsKey("not-a-date"))
        assertFalse(view.containsKey("2099-01-01"))
        assertEquals(listOf(1L, 2L, 3L), view["2026-11-05"])
    }

    @Test
    fun historyFlushIsNullWhenNothingChanged() {
        FilterEngine.loadBlockedHistory("2026-11-05=1,2,3\n")
        assertNull(FilterEngine.flushBlockedHistory())
    }

    @Test
    fun splitUrlStripsQueryAndFragmentFromPath() {
        val parts = FilterEngine.splitUrl("https://sub.example.com:8080/a/b?x=1#f")
        assertEquals("https", parts.scheme)
        assertEquals("sub.example.com", parts.host)
        assertEquals("/a/b", parts.path)
        assertEquals("http", FilterEngine.splitUrl("http://user@evil.example/x").scheme)
        assertEquals("evil.example", FilterEngine.splitUrl("http://user@evil.example/x").host)
        assertEquals("", FilterEngine.splitUrl("not a url").host)
    }

    @Test
    fun sameSiteUsesRegistrableDomain() {
        assertTrue(FilterEngine.sameSite("a.example.co.uk", "b.example.co.uk"))
        assertFalse(FilterEngine.sameSite("a.example.com", "b.other.com"))
        assertTrue(FilterEngine.sameSite("1.2.3.4", "1.2.3.4"))
        assertFalse(FilterEngine.sameSite("1.2.3.4", "5.6.7.8"))
        assertEquals("example.co.uk", FilterEngine.registrableDomain("www.example.co.uk"))
        assertEquals("example.com", FilterEngine.registrableDomain("a.b.example.com"))
    }

    @Test
    fun disabledHostsGateByPageHost() {
        assertFalse(FilterEngine.isDisabledForPage("news.example"))
        FilterEngine.updateDisabledHosts(setOf("news.example"))
        assertTrue(FilterEngine.isDisabledForPage("news.example"))
        assertTrue(FilterEngine.isDisabledForPage("sub.news.example"))
        assertFalse(FilterEngine.isDisabledForPage("other.example"))
    }

    @Test
    fun compiledRulesCarryHostsScopedAndDocumentSets() {
        val compiled = FilterEngine.compiledRules()
        assertTrue(compiled.hosts.contains("ads.example.com"))
        assertTrue(compiled.docHosts.contains("popunder.example"))
        assertTrue(compiled.trackerHosts.contains("shared.example.net"))
        assertTrue(compiled.neverBlock.contains("googlevideo.com"))
        assertTrue(compiled.pathKeywords.contains("/ads/"))
        assertTrue(compiled.scoped.any { it.host == "cdn.example.net" })
        assertTrue(compiled.scoped.any { it.host == "sneaky.example" && it.document })
        assertEquals(2, compiled.userNet.size)
    }
}
