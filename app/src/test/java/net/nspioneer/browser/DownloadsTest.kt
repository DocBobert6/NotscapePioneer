package net.nspioneer.browser

import net.nspioneer.browser.data.DownloadRecord
import net.nspioneer.browser.media.Downloads
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Scorecard row F (pure half): unit tests for the small `internal`
 * helpers in `media/Downloads.kt` that need no store and no Context.
 *
 * These run on the plain JVM (android.jar stubs), like
 * FilterEngineTest — no Robolectric. Anything that needs a
 * PioneerStore lives in DownloadsStoreTest instead.
 *
 * Every assertion pins the behaviour as it ships in alpha.9; a
 * value that looks odd (e.g. contentRangeTotal returning -5 for
 * "bytes 0-99/-5") is the current contract, documented here, not a
 * bug being fixed.
 */
class DownloadsTest {

    @After
    fun tearDown() {
        // The cancel/pause flag tests use their own ids; clear them
        // so no flag leaks into another test class in this JVM.
        Downloads.clearCancelled(7001L)
        Downloads.clearCancelled(7002L)
    }

    private fun rec(
        bytesDownloaded: Long = -1,
        totalBytes: Long = -1,
        speedBps: Long = -1
    ): DownloadRecord {
        val r = DownloadRecord(
            id = 1L, title = "t", url = "https://example.com/f.bin",
            fileName = "f.bin", dmId = -1, status = "downloading",
            addedAt = 0L
        )
        r.bytesDownloaded = bytesDownloaded
        r.totalBytes = totalBytes
        r.speedBps = speedBps
        return r
    }

    // ---------- contentRangeTotal ----------

    @Test
    fun contentRangeTotalStandard206() {
        assertEquals(1234L, Downloads.contentRangeTotal("bytes 0-99/1234"))
    }

    @Test
    fun contentRangeTotalSuffixRangeResponse() {
        // A suffix-range request ("bytes=-500") answers with a
        // normal Content-Range whose total is still after the '/'.
        assertEquals(10000L, Downloads.contentRangeTotal("bytes 9500-9999/10000"))
    }

    @Test
    fun contentRangeTotalOpenEndedRange() {
        assertEquals(1234L, Downloads.contentRangeTotal("bytes 0-/1234"))
    }

    @Test
    fun contentRangeTotalStarIsUnknown() {
        assertNull(Downloads.contentRangeTotal("bytes 0-99/*"))
    }

    @Test
    fun contentRangeTotalMissingHeader() {
        assertNull(Downloads.contentRangeTotal(null))
        assertNull(Downloads.contentRangeTotal(""))
    }

    @Test
    fun contentRangeTotalMalformed() {
        assertNull(Downloads.contentRangeTotal("bytes 0-99/abc"))
        assertNull(Downloads.contentRangeTotal("bytes 0-99/"))
        assertNull(Downloads.contentRangeTotal("bytes 0-99/12.5"))
        assertNull(Downloads.contentRangeTotal("garbage"))
        // No '/' at all: substringAfterLast yields "".
        assertNull(Downloads.contentRangeTotal("bytes"))
    }

    @Test
    fun contentRangeTotalTrimsWhitespace() {
        assertEquals(1234L, Downloads.contentRangeTotal("bytes 0-99/ 1234 "))
    }

    @Test
    fun contentRangeTotalNegativeTotalIsPassedThrough() {
        // Current contract: the header is parsed, not validated;
        // callers treat a total <= 0 as unknown.
        assertEquals(-5L, Downloads.contentRangeTotal("bytes 0-99/-5"))
    }

    // ---------- encodeJobBundle / decodeJobBundle ----------

    @Test
    fun jobBundleRoundTrip() {
        val fields = linkedMapOf(
            "url" to "https://example.com/a b?x=1&y=2",
            "title" to "My File = 100%",
            "referer" to "https://example.com/page",
            "cookie" to "a=b; c=d",
            "unicode" to "café ☕",
            "empty" to "",
            "key with space" to "v"
        )
        val decoded = Downloads.decodeJobBundle(Downloads.encodeJobBundle(fields))
        assertEquals(fields, decoded)
    }

    @Test
    fun jobBundleEmptyMapEncodesToEmptyString() {
        assertEquals("", Downloads.encodeJobBundle(emptyMap()))
        assertEquals(emptyMap<String, String>(), Downloads.decodeJobBundle(""))
        assertEquals(emptyMap<String, String>(), Downloads.decodeJobBundle("   "))
    }

    @Test
    fun jobBundleDecodeSkipsPairsWithoutEquals() {
        // Partial / corrupted bundle: the bare token is dropped,
        // the well-formed pairs survive.
        assertEquals(
            mapOf("a" to "1", "c" to "3"),
            Downloads.decodeJobBundle("a=1&b&c=3")
        )
    }

    @Test
    fun jobBundleDecodeKeepsEmptyValues() {
        assertEquals(
            mapOf("a" to "1", "b" to "", "c" to "3"),
            Downloads.decodeJobBundle("a=1&b=&c=3")
        )
    }

    @Test
    fun jobBundleDecodeLastDuplicateWins() {
        assertEquals(mapOf("a" to "2"), Downloads.decodeJobBundle("a=1&a=2"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun jobBundleDecodeMalformedPercentEncodingThrows() {
        // Current contract: decode propagates URLDecoder's
        // IllegalArgumentException on a bad '%' escape instead
        // of silently dropping the pair.
        Downloads.decodeJobBundle("a=%zz")
    }

    // ---------- sanitize ----------

    @Test
    fun sanitizeReplacesIllegalCharsAndTrims() {
        assertEquals("hello_world", Downloads.sanitize("hello world!"))
        assertEquals("a_b_c_d", Downloads.sanitize("a/b\\c:d"))
        assertEquals("spaced", Downloads.sanitize("  spaced  "))
        assertEquals("file.name.mp4", Downloads.sanitize("file.name.mp4"))
        // 'é' and the space each become '_', so the join is doubled.
        assertEquals("caf__au_lait", Downloads.sanitize("café au lait"))
    }

    @Test
    fun sanitizeBlankBecomesDownload() {
        assertEquals("download", Downloads.sanitize(""))
        assertEquals("download", Downloads.sanitize("..."))
        assertEquals("download", Downloads.sanitize("___"))
    }

    @Test
    fun sanitizeCapsAt100Chars() {
        assertEquals(100, Downloads.sanitize("a".repeat(150)).length)
        assertEquals("a".repeat(100), Downloads.sanitize("a".repeat(150)))
    }

    // ---------- dedupFileName ----------

    @Test
    fun dedupFileNameUnchangedWhenFree() {
        assertEquals("a.txt", Downloads.dedupFileName({ false }, "a.txt"))
    }

    @Test
    fun dedupFileNameAddsNumberedSuffix() {
        val taken = setOf("a.txt")
        assertEquals("a(1).txt", Downloads.dedupFileName({ it in taken }, "a.txt"))
        val taken2 = setOf("a.txt", "a(1).txt")
        assertEquals("a(2).txt", Downloads.dedupFileName({ it in taken2 }, "a.txt"))
    }

    @Test
    fun dedupFileNameHandlesNoExtensionAndMultiDot() {
        assertEquals("README(1)", Downloads.dedupFileName({ it == "README" }, "README"))
        assertEquals(
            "archive.tar(1).gz",
            Downloads.dedupFileName({ it == "archive.tar.gz" }, "archive.tar.gz")
        )
        // A leading dot is not an extension separator.
        assertEquals(".hidden(1)", Downloads.dedupFileName({ it == ".hidden" }, ".hidden"))
    }

    // ---------- applyFileNameOverride ----------

    @Test
    fun applyFileNameOverrideBlankKeepsResolved() {
        assertEquals("orig.mp3", Downloads.applyFileNameOverride(null, "orig.mp3", false, false))
        assertEquals("orig.mp3", Downloads.applyFileNameOverride("   ", "orig.mp3", false, false))
    }

    @Test
    fun applyFileNameOverrideKeepsEngineExtension() {
        assertEquals("My_Video.ts", Downloads.applyFileNameOverride("My Video", "x.ts", true, false))
        assertEquals("clip.ts", Downloads.applyFileNameOverride("clip.mp4", "x.ts", true, false))
        assertEquals("clip.mp4", Downloads.applyFileNameOverride("clip", "x.mp4", false, true))
        assertEquals("clip.mp3", Downloads.applyFileNameOverride("clip", "orig.mp3", false, false))
    }

    @Test
    fun applyFileNameOverrideSanitizesAndHandlesNoExtension() {
        assertEquals("a_b_c.bin", Downloads.applyFileNameOverride("a/b:c", "x.bin", false, false))
        assertEquals("name", Downloads.applyFileNameOverride("name", "noext", false, false))
        assertEquals("download.mp3", Downloads.applyFileNameOverride("...", "x.mp3", false, false))
    }

    // ---------- cookieForRequest ----------

    @Test
    fun cookieForRequestSnapshotWinsOnOwnHost() {
        val snap = Downloads.CookieSnapshot("example.com", "session=abc")
        assertEquals(
            "session=abc",
            Downloads.cookieForRequest("https://example.com:8080/x", snap, "live=1")
        )
    }

    @Test
    fun cookieForRequestFallsBackOffHost() {
        val snap = Downloads.CookieSnapshot("example.com", "session=abc")
        assertEquals(
            "live=1",
            Downloads.cookieForRequest("https://other.example/x", snap, "live=1")
        )
        assertNull(Downloads.cookieForRequest("https://other.example/x", snap, null))
    }

    @Test
    fun cookieForRequestBlankSnapshotOrLiveYieldsNull() {
        val blankSnap = Downloads.CookieSnapshot("example.com", "  ")
        assertEquals("live=1", Downloads.cookieForRequest("https://example.com/x", blankSnap, "live=1"))
        assertNull(Downloads.cookieForRequest("https://example.com/x", null, "   "))
        assertNull(Downloads.cookieForRequest("https://example.com/x", null, null))
        // An unparseable URL cannot match the snapshot host.
        val snap = Downloads.CookieSnapshot("example.com", "session=abc")
        assertEquals("live=1", Downloads.cookieForRequest("not a url", snap, "live=1"))
    }

    // ---------- notificationIdFor ----------

    @Test
    fun notificationIdForProgressAndTerminalRanges() {
        assertEquals(0, Downloads.notificationIdFor(0L, false))
        assertEquals(0x40000000, Downloads.notificationIdFor(0L, true))
        assertEquals(1, Downloads.notificationIdFor(1L, false))
        assertEquals(0x40000001, Downloads.notificationIdFor(1L, true))
        assertEquals(123456789, Downloads.notificationIdFor(123456789L, false))
        assertEquals(1197198613, Downloads.notificationIdFor(123456789L, true))
    }

    @Test
    fun notificationIdForFoldsLargeIdsInto30Bits() {
        // Long.MAX_VALUE folds to 0 (high and low halves cancel);
        // -1 folds to 0 too. The done bit is the only difference.
        assertEquals(0, Downloads.notificationIdFor(Long.MAX_VALUE, false))
        assertEquals(0x40000000, Downloads.notificationIdFor(Long.MAX_VALUE, true))
        assertEquals(0, Downloads.notificationIdFor(-1L, false))
        for (id in listOf(0L, 1L, 123456789L, Long.MAX_VALUE, -1L, -123456789L)) {
            val progress = Downloads.notificationIdFor(id, false)
            val done = Downloads.notificationIdFor(id, true)
            assertTrue(progress in 0..0x3FFFFFFF)
            assertEquals(progress or 0x40000000, done)
        }
    }

    // ---------- shouldQueue / notice text / resumable ----------

    @Test
    fun shouldQueueAtMaxConcurrentDownloads() {
        assertFalse(Downloads.shouldQueue(0))
        assertFalse(Downloads.shouldQueue(Downloads.MAX_CONCURRENT_DOWNLOADS - 1))
        assertTrue(Downloads.shouldQueue(Downloads.MAX_CONCURRENT_DOWNLOADS))
        assertTrue(Downloads.shouldQueue(Downloads.MAX_CONCURRENT_DOWNLOADS + 5))
    }

    @Test
    fun progressNoticeTextHidesPrivateTitles() {
        assertEquals("Downloading • 42%", Downloads.progressNoticeText(true, "Secret", 42))
        assertEquals("Movie • 0%", Downloads.progressNoticeText(false, "Movie", 0))
    }

    @Test
    fun foregroundNoticeTextHidesPrivateTitles() {
        assertEquals("Downloading", Downloads.foregroundNoticeText(true, "Secret"))
        assertEquals("Movie", Downloads.foregroundNoticeText(false, "Movie"))
    }

    @Test
    fun isResumableEngineOnlyWorkerEngines() {
        assertTrue(Downloads.isResumableEngine("hls"))
        assertTrue(Downloads.isResumableEngine("dash"))
        assertTrue(Downloads.isResumableEngine("seg"))
        assertTrue(Downloads.isResumableEngine("merge"))
        assertFalse(Downloads.isResumableEngine("dm"))
        assertFalse(Downloads.isResumableEngine(""))
        assertFalse(Downloads.isResumableEngine("HLS"))
    }

    // ---------- formatting ----------

    @Test
    fun formatBytesUnits() {
        assertEquals("?", Downloads.formatBytes(-1))
        assertEquals("0 B", Downloads.formatBytes(0))
        assertEquals("512 B", Downloads.formatBytes(512))
        assertEquals("1023 B", Downloads.formatBytes(1023))
        assertEquals("1.0 KB", Downloads.formatBytes(1024))
        assertEquals("1.5 KB", Downloads.formatBytes(1536))
        assertEquals("1.0 MB", Downloads.formatBytes(1024L * 1024))
        assertEquals("1.0 GB", Downloads.formatBytes(1024L * 1024 * 1024))
        assertEquals("5.0 GB", Downloads.formatBytes(5L * 1024 * 1024 * 1024))
        assertEquals("1.0 TB", Downloads.formatBytes(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun formatSpeedAndEta() {
        assertEquals("?", Downloads.formatSpeed(-1))
        assertEquals("0 B/s", Downloads.formatSpeed(0))
        assertEquals("2.0 KB/s", Downloads.formatSpeed(2048))
        assertEquals("?", Downloads.formatEta(-1))
        assertEquals("0s left", Downloads.formatEta(0))
        assertEquals("59s left", Downloads.formatEta(59))
        assertEquals("1m left", Downloads.formatEta(60))
        assertEquals("1m left", Downloads.formatEta(119))
        assertEquals("1h 0m left", Downloads.formatEta(3600))
        assertEquals("1h 1m left", Downloads.formatEta(3661))
    }

    @Test
    fun progressDetailLine() {
        assertNull(Downloads.progressDetailLine(rec()))
        assertEquals(
            "512 B / 1.0 KB",
            Downloads.progressDetailLine(rec(bytesDownloaded = 512, totalBytes = 1024))
        )
        assertEquals(
            "512 B / 1.0 KB • 1.0 KB/s • 0s left",
            Downloads.progressDetailLine(
                rec(bytesDownloaded = 512, totalBytes = 1024, speedBps = 1024)
            )
        )
        assertEquals(
            "2.0 KB",
            Downloads.progressDetailLine(rec(bytesDownloaded = 2048))
        )
        // Unknown downloaded bytes read as 0 against a known total.
        assertEquals(
            "0 B / 1.0 KB",
            Downloads.progressDetailLine(rec(totalBytes = 1024))
        )
        assertEquals(
            "0 B / 1.0 KB • 512 B/s • 2s left",
            Downloads.progressDetailLine(
                rec(bytesDownloaded = 0, totalBytes = 1024, speedBps = 512)
            )
        )
    }

    // ---------- renameOrCopy / cancel flags / sleepChecked ----------

    @Test
    fun renameOrCopyMovesFileAndPreservesContent() {
        val dir = Files.createTempDirectory("dl-test").toFile()
        try {
            val src = File(dir, "src.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val dst = File(dir, "sub/dst.bin")
            dst.parentFile?.mkdirs()
            assertTrue(Downloads.renameOrCopy(src, dst))
            assertFalse(src.exists())
            assertEquals(listOf<Byte>(1, 2, 3), dst.readBytes().toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun renameOrCopyMissingSourceOrParentReturnsFalse() {
        val dir = Files.createTempDirectory("dl-test").toFile()
        try {
            assertFalse(Downloads.renameOrCopy(File(dir, "nope"), File(dir, "dst")))
            val src = File(dir, "src.bin").apply { writeBytes(byteArrayOf(1)) }
            // Destination parent does not exist: rename fails and
            // the copy fallback fails too.
            assertFalse(Downloads.renameOrCopy(src, File(dir, "missing/dst")))
            assertTrue(src.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cancelFlagsRoundTrip() {
        assertFalse(Downloads.isCancelled(7001L))
        Downloads.markCancelled(7001L)
        assertTrue(Downloads.isCancelled(7001L))
        Downloads.clearCancelled(7001L)
        assertFalse(Downloads.isCancelled(7001L))
        assertFalse(Downloads.isPaused(7001L))
    }

    @Test
    fun sleepCheckedZeroMillisReturnsNotPaused() {
        assertFalse(Downloads.sleepChecked(7002L, 0))
    }

    @Test(expected = InterruptedException::class)
    fun sleepCheckedThrowsPromptlyWhenCancelled() {
        Downloads.markCancelled(7002L)
        try {
            Downloads.sleepChecked(7002L, 5_000)
        } finally {
            Downloads.clearCancelled(7002L)
        }
    }
}
