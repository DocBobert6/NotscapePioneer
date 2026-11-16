package net.nspioneer.browser

import net.nspioneer.browser.engine.CookieRequester
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Alpha.64: the cookie requester — the app side of the
 * `cookies-request` / `cookies-response` pair on the
 * background script's native port.
 *
 * Failure is always cookie-less, never fatal: no sender,
 * a failed send, a timeout, an error reply, or an empty
 * reply all yield an empty list and the job proceeds
 * without cookies. Values flow only through the pending
 * reply into the caller — never into a log, a status
 * line, or Diagnostics; the only observable is the
 * count-only attach line ("cookies: N for <host>").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CookieRequesterTest {

    private var savedMainThreadCheck: (() -> Boolean)? = null

    @Before
    fun setUp() {
        // Robolectric runs tests on the main looper, so the
        // production main-thread guard would answer empty
        // before any test could exercise the round trip.
        savedMainThreadCheck = CookieRequester.mainThreadCheck
        CookieRequester.mainThreadCheck = { false }
        CookieRequester.clearSender()
        CookieRequester.statusSink = null
    }

    @After
    fun tearDown() {
        CookieRequester.clearSender()
        CookieRequester.statusSink = null
        savedMainThreadCheck?.let { CookieRequester.mainThreadCheck = it }
        assertEquals(0, CookieRequester.pendingCount())
    }

    @Test
    fun noSenderAnswersEmptyAtOnce() {
        assertEquals(emptyList<Pair<String, String>>(), CookieRequester.requestCookies("https://a.example.com/x"))
    }

    @Test
    fun failedSendAnswersEmpty() {
        CookieRequester.registerSender { false }
        assertEquals(emptyList<Pair<String, String>>(), CookieRequester.requestCookies("https://a.example.com/x"))
    }

    @Test
    fun responseCompletesWithValues() {
        var sent: JSONObject? = null
        CookieRequester.registerSender { msg ->
            sent = msg
            CookieRequester.onResponse(
                msg.getString("id"),
                listOf("SID" to "SECRET_SID", "HSID" to "SECRET_HSID"),
                null
            )
            true
        }
        val got = CookieRequester.requestCookies("https://a.example.com/x")
        assertEquals(
            listOf("SID" to "SECRET_SID", "HSID" to "SECRET_HSID"),
            got
        )
        assertEquals("cookies-request", sent?.getString("type"))
        assertEquals("https://a.example.com/x", sent?.getString("url"))
        assertTrue(sent?.getString("id")?.isNotBlank() == true)
    }

    @Test
    fun timeoutAnswersEmpty() {
        CookieRequester.registerSender { true }
        val start = System.currentTimeMillis()
        val got = CookieRequester.requestCookies("https://a.example.com/x", timeoutMs = 150)
        val elapsed = System.currentTimeMillis() - start
        assertEquals(emptyList<Pair<String, String>>(), got)
        assertTrue("waited for the timeout, got $elapsed ms", elapsed >= 100)
        assertTrue("did not hang past the timeout, got $elapsed ms", elapsed < 4000)
    }

    @Test
    fun wrongIdIsIgnored() {
        CookieRequester.registerSender { true }
        CookieRequester.onResponse(
            "not-the-pending-id",
            listOf("SID" to "SECRET_SID"),
            null
        )
        val got = CookieRequester.requestCookies("https://a.example.com/x", timeoutMs = 150)
        assertEquals(emptyList<Pair<String, String>>(), got)
    }

    @Test
    fun errorReplyAnswersEmpty() {
        CookieRequester.registerSender { msg ->
            CookieRequester.onResponse(msg.getString("id"), null, "denied")
            true
        }
        val got = CookieRequester.requestCookies("https://a.example.com/x")
        assertEquals(emptyList<Pair<String, String>>(), got)
    }

    @Test
    fun attachLineIsCountOnly() {
        val lines = mutableListOf<String>()
        CookieRequester.statusSink = { lines += it }
        CookieRequester.noteAttach("a.example.com", 3)
        assertEquals(listOf("cookies: 3 for a.example.com"), lines)
    }
}
