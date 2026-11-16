package net.nspioneer.browser

import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.engine.CookieCaptureExperiment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Cookie-capture experiment (alpha.59).
 *
 * The verdict state machine is pure: ACCEPTED only when a
 * `Cookie` header is seen on an in-scope request; a bounded
 * wait with requests observed and zero `Cookie` headers is
 * REJECTED; a wait with zero requests observed stays UNKNOWN
 * (no evidence either way); first-wins. The flag defaults
 * OFF. The probe reports cookie NAMES only — never values —
 * and the shipped background.js is pinned here in CI so a
 * value can never slip into a capture message.
 *
 * Note on the default: PioneerStore is a process singleton
 * with a private constructor, so no test can build a fresh
 * store to observe the unset default live. The default is
 * pinned by source text (`store.setting(flagKey(), "false")`)
 * and the persisted values are exercised live below — the
 * same split as alpha.52's MAIN-world test.
 *
 * The live accept/reject outcome is device-proven only
 * (whether GeckoView delivers the `Cookie` header to the
 * observer is the question the experiment settles).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CookieCaptureExperimentTest {

    private fun asset(path: String): String =
        RuntimeEnvironment.getApplication().assets
            .open(path).bufferedReader().use { it.readText() }

    // ---------- flag ----------

    @Test
    fun flag_key_is_plain() {
        assertEquals("probeCookieCapture", CookieCaptureExperiment.flagKey())
    }

    @Test
    fun flag_stored_values_round_trip() {
        val store = PioneerStore.get(RuntimeEnvironment.getApplication())
        store.putSetting(CookieCaptureExperiment.flagKey(), "false")
        assertFalse(CookieCaptureExperiment.isEnabled(store))
        store.putSetting(CookieCaptureExperiment.flagKey(), "true")
        assertTrue(CookieCaptureExperiment.isEnabled(store))
        // Anything that is not "true" reads as off.
        store.putSetting(CookieCaptureExperiment.flagKey(), "yes")
        assertFalse(CookieCaptureExperiment.isEnabled(store))
        // Leave the shared singleton as the user would find it.
        store.putSetting(CookieCaptureExperiment.flagKey(), "false")
    }

    // ---------- state text ----------

    @Test
    fun state_text_off_when_flag_off() {
        val exp = CookieCaptureExperiment()
        assertEquals("cookie capture: off", exp.cookieCaptureStateText(false))
        // Even a recorded verdict does not show while off.
        exp.noteCookieNames("example.com", listOf("sessionid"), 3)
        assertEquals("cookie capture: off", exp.cookieCaptureStateText(false))
    }

    @Test
    fun state_text_unknown_until_first_report() {
        val exp = CookieCaptureExperiment()
        assertEquals(CookieCaptureExperiment.Verdict.UNKNOWN, exp.verdict)
        assertNull(exp.rejectReason)
        assertEquals(0, exp.requestsObserved)
        assertEquals("cookie capture: unknown", exp.cookieCaptureStateText(true))
    }

    @Test
    fun state_text_accepted_on_cookie_names() {
        val exp = CookieCaptureExperiment()
        exp.noteCookieNames("example.com", listOf("sessionid", "csrftoken"), 3)
        assertEquals(CookieCaptureExperiment.Verdict.ACCEPTED, exp.verdict)
        assertNull(exp.rejectReason)
        assertEquals(3, exp.requestsObserved)
        assertEquals(
            "cookie capture: accepted · 1 host(s): " +
                "example.com (sessionid, csrftoken)",
            exp.cookieCaptureStateText(true)
        )
    }

    @Test
    fun state_text_rejected_on_timeout_with_requests() {
        val exp = CookieCaptureExperiment()
        exp.noteTick(27)
        exp.noteTimeout()
        assertEquals(CookieCaptureExperiment.Verdict.REJECTED, exp.verdict)
        assertEquals("27 requests observed, 0 Cookie headers", exp.rejectReason)
        assertEquals(
            "cookie capture: rejected (27 requests observed, 0 Cookie headers)",
            exp.cookieCaptureStateText(true)
        )
    }

    @Test
    fun timeout_with_zero_requests_stays_unknown() {
        val exp = CookieCaptureExperiment()
        exp.noteTimeout()
        assertEquals(CookieCaptureExperiment.Verdict.UNKNOWN, exp.verdict)
        assertNull(exp.rejectReason)
        assertEquals("cookie capture: unknown", exp.cookieCaptureStateText(true))
        // A later load with real requests can still reject.
        exp.noteTick(4)
        exp.noteTimeout()
        assertEquals(CookieCaptureExperiment.Verdict.REJECTED, exp.verdict)
    }

    // ---------- names merging ----------

    @Test
    fun names_merge_across_reports_and_hosts() {
        val exp = CookieCaptureExperiment()
        exp.noteCookieNames("example.com", listOf("sessionid"), 1)
        exp.noteCookieNames("example.com", listOf("sessionid", "csrftoken"), 5)
        exp.noteCookieNames("cdn.example.net", listOf("auth"), 9)
        assertEquals(9, exp.requestsObserved)
        assertEquals(
            mapOf(
                "example.com" to listOf("sessionid", "csrftoken"),
                "cdn.example.net" to listOf("auth")
            ),
            exp.hosts
        )
        assertEquals(
            "cookie capture: accepted · 2 host(s): " +
                "example.com (sessionid, csrftoken); cdn.example.net (auth)",
            exp.cookieCaptureStateText(true)
        )
    }

    @Test
    fun tick_count_is_monotonic() {
        val exp = CookieCaptureExperiment()
        exp.noteTick(10)
        exp.noteTick(4)
        assertEquals(10, exp.requestsObserved)
        exp.noteCookieNames("example.com", listOf("a"), 2)
        assertEquals(10, exp.requestsObserved)
    }

    // ---------- first-wins ----------

    @Test
    fun verdict_is_first_wins() {
        val rejectedFirst = CookieCaptureExperiment()
        rejectedFirst.noteTick(12)
        rejectedFirst.noteTimeout()
        rejectedFirst.noteCookieNames("example.com", listOf("sessionid"), 20)
        assertEquals(CookieCaptureExperiment.Verdict.REJECTED, rejectedFirst.verdict)
        assertEquals("12 requests observed, 0 Cookie headers", rejectedFirst.rejectReason)

        val acceptedFirst = CookieCaptureExperiment()
        acceptedFirst.noteCookieNames("example.com", listOf("sessionid"), 3)
        acceptedFirst.noteTick(40)
        acceptedFirst.noteTimeout()
        assertEquals(CookieCaptureExperiment.Verdict.ACCEPTED, acceptedFirst.verdict)
        assertNull(acceptedFirst.rejectReason)
    }

    @Test
    fun state_changed_fires_once_per_verdict() {
        val exp = CookieCaptureExperiment()
        var fires = 0
        exp.onStateChanged = { fires++ }
        exp.noteTick(12)
        exp.noteTimeout()
        exp.noteCookieNames("example.com", listOf("sessionid"), 20)
        exp.noteTimeout()
        assertEquals(1, fires)
    }

    // ---------- shipped asset shape ----------

    @Test
    fun background_js_reports_names_only() {
        val js = asset("extensions/pioneer-probe/background.js")
        assertTrue(js.contains("cookieNamesFromHeaders"))
        assertTrue(js.contains("cookieCaptureInScope"))
        assertTrue(js.contains("type: \"cookie-capture\""))
        assertTrue(js.contains("type: \"cookie-capture-tick\""))
        // The capture message carries host + names + the request
        // count — and no value field of any kind.
        assertTrue(js.contains("names: fresh"))
        assertFalse(js.contains("cookieValue"))
        assertFalse(js.contains("cookie-value"))
        // The security rule is stated in the source.
        assertTrue(js.contains("names only, NEVER values"))
        // The flag rides the rules-update push.
        assertTrue(js.contains("r.cookieCapture"))
    }

    // ---------- observer line (alpha.60) ----------

    @Test
    fun observer_text_initial() {
        assertEquals(
            "cookie observer: 0 events · 0 in scope · flag off",
            CookieCaptureExperiment.observerStateText(0, 0, false)
        )
    }

    @Test
    fun observer_text_counts_and_flag() {
        assertEquals(
            "cookie observer: 27 events · 12 in scope · flag on",
            CookieCaptureExperiment.observerStateText(27, 12, true)
        )
        assertEquals(
            "cookie observer: 10 events · 0 in scope · flag off",
            CookieCaptureExperiment.observerStateText(10, 0, false)
        )
    }

    @Test
    fun background_js_probe_shape() {
        val js = asset("extensions/pioneer-probe/background.js")
        assertTrue(js.contains("probeCookieListener"))
        assertTrue(js.contains("type: \"cookie-capture-probe\""))
        assertTrue(js.contains("flagOn: !!flags.cookieCapture"))
        // The probe message carries counts + the flag only.
        assertTrue(js.contains("events: cookieProbe.events"))
        assertTrue(js.contains("inScope: cookieProbe.inScope"))
        // Still names-only: no value of any kind anywhere.
        assertFalse(js.contains("cookieValue"))
        assertFalse(js.contains("cookie-value"))
    }
}
