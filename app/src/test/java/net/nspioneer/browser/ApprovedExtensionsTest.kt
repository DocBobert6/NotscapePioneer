package net.nspioneer.browser

import net.nspioneer.browser.engine.ApprovedExtensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alpha.33 — onboarding extensions: the by-name-only set.
 * Pure data, so plain JVM tests (no Robolectric): if a third
 * extension is ever added, or an ID/URL changes, these fail
 * first and say so.
 */
class ApprovedExtensionsTest {

    @Test
    fun `exactly two approved extensions, uBlock first`() {
        assertEquals(2, ApprovedExtensions.ALL.size)
        assertEquals("uBlock Origin", ApprovedExtensions.ALL[0].displayName)
        assertEquals("Google Search Fixer", ApprovedExtensions.ALL[1].displayName)
    }

    @Test
    fun `uBlock Origin entry is unchanged`() {
        val u = ApprovedExtensions.UBLOCK_ORIGIN
        assertEquals("uBlock0@raymondhill.net", u.id)
        assertEquals(
            "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi",
            u.amoUrl
        )
    }

    @Test
    fun `Google Search Fixer entry matches AMO`() {
        val g = ApprovedExtensions.GOOGLE_SEARCH_FIXER
        assertEquals("{58c32ac4-0d6c-4d6f-ae2c-96aaf8ffcb66}", g.id)
        assertEquals(
            "https://addons.mozilla.org/firefox/downloads/latest/google-search-fixer/latest.xpi",
            g.amoUrl
        )
        assertEquals("Google Search Fixer", g.displayName)
    }

    @Test
    fun `all entries use the AMO direct-XPI download form`() {
        ApprovedExtensions.ALL.forEach { ext ->
            assertTrue(
                ext.amoUrl.startsWith(
                    "https://addons.mozilla.org/firefox/downloads/latest/"
                )
            )
            assertTrue(ext.amoUrl.endsWith("/latest.xpi"))
            assertTrue(ext.id.isNotBlank())
            assertTrue(ext.displayName.isNotBlank())
            assertTrue(ext.description.isNotBlank())
        }
    }

    @Test
    fun `no duplicate ids`() {
        assertEquals(
            ApprovedExtensions.ALL.size,
            ApprovedExtensions.ALL.map { it.id }.toSet().size
        )
    }
}
