package net.nspioneer.browser

import java.io.File
import net.nspioneer.browser.engine.FilterEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E-A2 (alpha.36): filter-list refresh re-downloaded all 8 lists
 * in full every 24 h, and parsed each list twice per refresh.
 * The refresh is now conditional (If-None-Match /
 * If-Modified-Since from a per-list `.meta` sidecar; 304 skips
 * the body) and the collapse guard compares against the total
 * recorded at commit time instead of re-parsing the on-disk
 * copy.
 *
 * The network half cannot run in CI (offline by design), so
 * these tests pin down every pure piece the refresh relies on:
 * the meta format, the headers, the guard, and the sidecar
 * file round-trip. The wiring (refresh sends the headers,
 * honors 304, writes the meta) is verified on device.
 */
class FilterEngineConditionalTest {

    // ---- Meta format ----

    @Test
    fun validators_roundTrip_allFields() {
        val v = FilterEngine.ListValidators(
            etag = "W/\"abc123\"",
            lastModified = "Wed, 01 Jan 2026 00:00:00 GMT",
            total = 123456
        )
        val parsed = FilterEngine.parseValidators(FilterEngine.formatValidators(v))
        assertEquals(v, parsed)
    }

    @Test
    fun validators_roundTrip_partialFields() {
        // A server that sends only Last-Modified, or neither.
        val onlyModified = FilterEngine.ListValidators(
            lastModified = "Wed, 01 Jan 2026 00:00:00 GMT",
            total = 42
        )
        assertEquals(
            onlyModified,
            FilterEngine.parseValidators(
                FilterEngine.formatValidators(onlyModified)
            )
        )
        val empty = FilterEngine.ListValidators()
        assertEquals(
            empty,
            FilterEngine.parseValidators(FilterEngine.formatValidators(empty))
        )
        assertEquals("", FilterEngine.formatValidators(empty))
    }

    @Test
    fun validators_parse_toleratesUnknownAndGarbageLines() {
        val parsed = FilterEngine.parseValidators(
            "etag=\"x\"\n" +
                "future-field=whatever\n" +
                "\n" +
                "total=not-a-number\n" +
                "last-modified=Wed, 01 Jan 2026 00:00:00 GMT\n"
        )
        assertEquals("\"x\"", parsed.etag)
        assertEquals("Wed, 01 Jan 2026 00:00:00 GMT", parsed.lastModified)
        // An unparseable total is dropped, not fatal.
        assertNull(parsed.total)
    }

    @Test
    fun validators_parse_emptyText() {
        assertEquals(
            FilterEngine.ListValidators(),
            FilterEngine.parseValidators("")
        )
    }

    // ---- Conditional headers ----

    @Test
    fun conditionalHeaders_bothValidators() {
        val headers = FilterEngine.conditionalHeaders(
            FilterEngine.ListValidators(
                etag = "\"abc\"",
                lastModified = "Wed, 01 Jan 2026 00:00:00 GMT",
                total = 10
            )
        )
        assertEquals("\"abc\"", headers["If-None-Match"])
        assertEquals(
            "Wed, 01 Jan 2026 00:00:00 GMT",
            headers["If-Modified-Since"]
        )
        assertEquals(2, headers.size)
    }

    @Test
    fun conditionalHeaders_singleValidator() {
        assertEquals(
            mapOf("If-None-Match" to "\"abc\""),
            FilterEngine.conditionalHeaders(
                FilterEngine.ListValidators(etag = "\"abc\"")
            )
        )
        assertEquals(
            mapOf("If-Modified-Since" to "Wed, 01 Jan 2026 00:00:00 GMT"),
            FilterEngine.conditionalHeaders(
                FilterEngine.ListValidators(
                    lastModified = "Wed, 01 Jan 2026 00:00:00 GMT"
                )
            )
        )
    }

    @Test
    fun conditionalHeaders_noneAndBlank() {
        assertTrue(
            FilterEngine.conditionalHeaders(FilterEngine.ListValidators()).isEmpty()
        )
        // Blank validators send nothing — a refresh with no
        // stored validators is an unconditional GET, as before.
        assertTrue(
            FilterEngine.conditionalHeaders(
                FilterEngine.ListValidators(etag = "  ", lastModified = "")
            ).isEmpty()
        )
    }

    @Test
    fun conditionalHeaders_verbatim() {
        // Weak ETags and quoted strings go back exactly as
        // the server sent them; the engine never re-parses them.
        val etag = "W/\"dead-beef-123\""
        assertEquals(
            etag,
            FilterEngine.conditionalHeaders(
                FilterEngine.ListValidators(etag = etag)
            )["If-None-Match"]
        )
    }

    // ---- Collapse guard (was: re-parse the on-disk copy) ----

    @Test
    fun candidateAcceptable_boundaries() {
        // Mirrors the old rule exactly: reject iff the candidate
        // is less than half the committed set.
        assertTrue(FilterEngine.candidateAcceptable(50, 100))
        assertFalse(FilterEngine.candidateAcceptable(49, 100))
        assertTrue(FilterEngine.candidateAcceptable(100, 100))
        assertTrue(FilterEngine.candidateAcceptable(200, 100))
        assertFalse(FilterEngine.candidateAcceptable(0, 100))
        assertTrue(FilterEngine.candidateAcceptable(1, 1))
        // Degenerate case, identical to the old comparison: a
        // stored total of 1 halves to 0, so any candidate passes.
        assertTrue(FilterEngine.candidateAcceptable(0, 1))
    }

    @Test
    fun candidateAcceptable_noStoredTotalMeansNoGuard() {
        // Pre-alpha.36 lists have no meta file yet; the first
        // refresh after the upgrade falls back to the old
        // re-parse comparison in refresh() — the pure guard
        // itself accepts, so the caller decides.
        assertTrue(FilterEngine.candidateAcceptable(1, null))
        assertTrue(FilterEngine.candidateAcceptable(0, null))
    }

    // ---- Sidecar file round-trip ----

    @Test
    fun validatorsFile_roundTrip() {
        val dir = File(
            System.getProperty("java.io.tmpdir"),
            "pioneer-ea2-test-${System.nanoTime()}"
        ).apply { mkdirs() }
        try {
            val meta = File(dir, "list0.txt.meta")
            // Missing file reads as empty validators.
            assertEquals(
                FilterEngine.ListValidators(),
                FilterEngine.readValidatorsFile(meta)
            )
            val v = FilterEngine.ListValidators(
                etag = "\"etag-1\"",
                lastModified = "Wed, 01 Jan 2026 00:00:00 GMT",
                total = 98765
            )
            FilterEngine.writeValidatorsFile(meta, v)
            assertTrue(meta.exists())
            assertEquals(v, FilterEngine.readValidatorsFile(meta))
            // Overwriting replaces, and a corrupt file reads
            // as empty instead of throwing.
            FilterEngine.writeValidatorsFile(
                meta,
                FilterEngine.ListValidators(total = 5)
            )
            assertEquals(
                FilterEngine.ListValidators(total = 5),
                FilterEngine.readValidatorsFile(meta)
            )
            meta.writeText("garbage\nwithout\nequals\n")
            assertEquals(
                FilterEngine.ListValidators(),
                FilterEngine.readValidatorsFile(meta)
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
