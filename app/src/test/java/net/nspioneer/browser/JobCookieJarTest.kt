package net.nspioneer.browser

import net.nspioneer.browser.media.Downloads
import net.nspioneer.browser.media.Downloads.CookieSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Alpha.64: the per-job cookie jar.
 *
 * One snapshot per host, asked once (the first fetch for
 * that host) and cached for the job's lifetime, in memory
 * only. A host the API cannot serve caches as absent and
 * the job proceeds without cookies for it. A snapshot is
 * replayed ONLY to its own host — a host-A snapshot never
 * reaches a host-B request. The only observable is the
 * count-only attach line ("cookies: N for <host>"): no
 * cookie name or value ever reaches a log, a status line,
 * or Diagnostics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JobCookieJarTest {

    private fun jarOf(
        byHost: Map<String, List<Pair<String, String>>>,
        legacy: CookieSnapshot? = null,
        notes: MutableList<String>? = null,
        calls: MutableList<String>? = null,
        throwFor: String? = null,
    ): Downloads.JobCookieJar = Downloads.JobCookieJar(
        request = { url ->
            calls?.add(url)
            val host = url.split("/").getOrNull(2) ?: ""
            if (throwFor != null && host == throwFor) {
                throw java.io.IOException("boom")
            }
            byHost[host] ?: emptyList()
        },
        legacy = legacy,
        noteAttach = { host, count ->
            notes?.add("cookies: $count for $host")
        }
    )

    @Test
    fun headerForBuildsNameEqualsValueHeader() {
        val jar = jarOf(
            mapOf("a.example.com" to listOf("SID" to "SECRET_SID", "HSID" to "SECRET_HSID"))
        )
        assertEquals(
            "SID=SECRET_SID; HSID=SECRET_HSID",
            jar.headerFor("https://a.example.com/video.m3u8")
        )
    }

    @Test
    fun snapshotNeverLeaksAcrossHosts() {
        val jar = jarOf(
            mapOf(
                "a.example.com" to listOf("SID" to "SECRET_A"),
                "b.example.com" to listOf("TOK" to "SECRET_B")
            )
        )
        assertEquals("SID=SECRET_A", jar.headerFor("https://a.example.com/x"))
        val b = jar.headerFor("https://b.example.com/y")
        assertEquals("TOK=SECRET_B", b)
        assertTrue(b?.contains("SECRET_A") == false)
        assertTrue(jar.headerFor("https://c.example.com/z") == null)
    }

    @Test
    fun asksOncePerHost() {
        val calls = mutableListOf<String>()
        val jar = jarOf(
            mapOf("a.example.com" to listOf("SID" to "SECRET_SID")),
            calls = calls
        )
        jar.headerFor("https://a.example.com/one.m3u8")
        jar.headerFor("https://a.example.com/two.ts")
        jar.headerFor("https://a.example.com:8080/three.ts")
        assertEquals(1, calls.size)
    }

    @Test
    fun emptyReplyCachesAsAbsent() {
        val calls = mutableListOf<String>()
        val jar = jarOf(emptyMap(), calls = calls)
        assertNull(jar.headerFor("https://a.example.com/x"))
        assertNull(jar.headerFor("https://a.example.com/y"))
        assertEquals(1, calls.size)
    }

    @Test
    fun legacySnapshotFallsBackHostScoped() {
        val jar = jarOf(
            emptyMap(),
            legacy = CookieSnapshot("a.example.com", "OLD=1")
        )
        assertEquals("OLD=1", jar.headerFor("https://a.example.com/x"))
        assertNull(jar.headerFor("https://b.example.com/x"))
    }

    @Test
    fun attachLineIsCountOnlyAndOncePerHost() {
        val notes = mutableListOf<String>()
        val jar = jarOf(
            mapOf("a.example.com" to listOf("SID" to "SECRET_SID", "HSID" to "SECRET_HSID")),
            notes = notes
        )
        jar.headerFor("https://a.example.com/one.m3u8")
        jar.headerFor("https://a.example.com/two.ts")
        assertEquals(listOf("cookies: 2 for a.example.com"), notes)
        assertTrue(notes.none { it.contains("SID") || it.contains("SECRET") })
    }

    @Test
    fun throwingRequestProceedsWithoutCookies() {
        val jar = jarOf(
            mapOf("a.example.com" to listOf("SID" to "SECRET_SID")),
            throwFor = "a.example.com"
        )
        assertNull(jar.headerFor("https://a.example.com/x"))
    }

    @Test
    fun hostlessUrlAsksNothing() {
        val calls = mutableListOf<String>()
        val jar = jarOf(
            mapOf("a.example.com" to listOf("SID" to "SECRET_SID")),
            calls = calls
        )
        assertNull(jar.headerFor("not a url"))
        assertNull(jar.headerFor(""))
        assertTrue(calls.isEmpty())
    }
}
