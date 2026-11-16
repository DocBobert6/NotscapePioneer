package net.nspioneer.browser

import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.engine.CookieApiExperiment
import net.nspioneer.browser.engine.CookieApiExperiment.StoreResult
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
 * Cookies-API experiment (alpha.63).
 *
 * The verdict state machine is pure: ACCEPTED only when
 * some store returns at least one cookie name for the
 * probed page host; a bounded wait with probe runs and
 * zero names is REJECTED; a wait with zero runs stays
 * UNKNOWN (no evidence either way); an absent API or a
 * throwing `getAll` rejects at once; first-wins. The flag
 * defaults OFF. The probe reports cookie NAMES only —
 * never values — and the shipped background.js is pinned
 * here in CI so a value can never slip into a probe
 * message, and the manifest is pinned for the
 * permissions the API needs.
 *
 * Note on the default: PioneerStore is a process singleton
 * with a private constructor, so no test can build a fresh
 * store to observe the unset default live. The default is
 * pinned by source text (`store.setting(flagKey(), "false")`)
 * and the persisted values are exercised live below — the
 * same split as alpha.52's MAIN-world test.
 *
 * The live accept/reject outcome is device-proven only
 * (whether `browser.cookies.getAll` returns the tab's
 * cookies, and in which store, is the question the
 * experiment settles).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CookieApiExperimentTest {

    private fun asset(path: String): String =
        RuntimeEnvironment.getApplication().assets
            .open(path).bufferedReader().use { it.readText() }

    // ---------- flag ----------

    @Test
    fun flag_key_is_plain() {
        assertEquals("probeCookiesApi", CookieApiExperiment.flagKey())
    }

    @Test
    fun flag_stored_values_round_trip() {
        val store = PioneerStore.get(RuntimeEnvironment.getApplication())
        store.putSetting(CookieApiExperiment.flagKey(), "false")
        assertFalse(CookieApiExperiment.isEnabled(store))
        store.putSetting(CookieApiExperiment.flagKey(), "true")
        assertTrue(CookieApiExperiment.isEnabled(store))
        // Anything that is not "true" reads as off.
        store.putSetting(CookieApiExperiment.flagKey(), "yes")
        assertFalse(CookieApiExperiment.isEnabled(store))
        // Leave the shared singleton as the user would find it.
        store.putSetting(CookieApiExperiment.flagKey(), "false")
    }

    // ---------- state text ----------

    @Test
    fun state_text_off_when_flag_off() {
        val exp = CookieApiExperiment()
        assertEquals("cookies API: off", exp.cookiesApiStateText(false))
        // Even a recorded verdict does not show while off.
        exp.noteProbe("example.com", listOf(StoreResult("default", listOf("SID"))))
        assertEquals("cookies API: off", exp.cookiesApiStateText(false))
    }

    @Test
    fun state_text_unknown_until_first_probe() {
        val exp = CookieApiExperiment()
        assertEquals(CookieApiExperiment.Verdict.UNKNOWN, exp.verdict)
        assertNull(exp.rejectReason)
        assertEquals(0, exp.probesRun)
        assertEquals("", exp.lastHost)
        assertEquals("cookies API: unknown", exp.cookiesApiStateText(true))
    }

    @Test
    fun state_text_accepted_on_names_in_default_store() {
        val exp = CookieApiExperiment()
        exp.noteProbe(
            "youtube.com",
            listOf(StoreResult("default", listOf("SID", "HSID", "SSID")))
        )
        assertEquals(CookieApiExperiment.Verdict.ACCEPTED, exp.verdict)
        assertNull(exp.rejectReason)
        assertEquals(1, exp.probesRun)
        assertEquals("youtube.com", exp.lastHost)
        assertEquals(
            "cookies API: accepted · youtube.com · " +
                "default: 3 names (SID, HSID, SSID)",
            exp.cookiesApiStateText(true)
        )
    }

    @Test
    fun state_text_accepted_on_names_in_session_store_only() {
        val exp = CookieApiExperiment()
        exp.noteProbe(
            "youtube.com",
            listOf(
                StoreResult("default", emptyList()),
                StoreResult("firefox-container-1", listOf("SID"))
            )
        )
        assertEquals(CookieApiExperiment.Verdict.ACCEPTED, exp.verdict)
        assertEquals(
            "cookies API: accepted · youtube.com · " +
                "default: 0 names; firefox-container-1: 1 name (SID)",
            exp.cookiesApiStateText(true)
        )
    }

    @Test
    fun state_text_rejected_on_timeout_with_probes() {
        val exp = CookieApiExperiment()
        exp.noteProbe("youtube.com", listOf(StoreResult("default", emptyList())))
        exp.noteProbe("youtube.com", listOf(StoreResult("default", emptyList())))
        exp.noteTimeout()
        assertEquals(CookieApiExperiment.Verdict.REJECTED, exp.verdict)
        assertEquals("0 cookies on youtube.com after 2 probe(s)", exp.rejectReason)
        assertEquals(
            "cookies API: rejected (0 cookies on youtube.com after 2 probe(s))",
            exp.cookiesApiStateText(true)
        )
    }

    @Test
    fun timeout_with_zero_probes_stays_unknown() {
        val exp = CookieApiExperiment()
        exp.noteTimeout()
        assertEquals(CookieApiExperiment.Verdict.UNKNOWN, exp.verdict)
        assertNull(exp.rejectReason)
        assertEquals("cookies API: unknown", exp.cookiesApiStateText(true))
        // A later load with real probes can still reject.
        exp.noteProbe("youtube.com", listOf(StoreResult("default", emptyList())))
        exp.noteTimeout()
        assertEquals(CookieApiExperiment.Verdict.REJECTED, exp.verdict)
    }

    @Test
    fun api_absent_rejects_at_once() {
        val exp = CookieApiExperiment()
        exp.noteApiAbsent("youtube.com")
        assertEquals(CookieApiExperiment.Verdict.REJECTED, exp.verdict)
        assertEquals("browser.cookies undefined", exp.rejectReason)
        assertEquals("youtube.com", exp.lastHost)
        assertEquals(
            "cookies API: rejected (browser.cookies undefined)",
            exp.cookiesApiStateText(true)
        )
    }

    @Test
    fun api_error_rejects_at_once() {
        val exp = CookieApiExperiment()
        exp.noteApiError("youtube.com", "SecurityError")
        assertEquals(CookieApiExperiment.Verdict.REJECTED, exp.verdict)
        assertEquals("cookies.getAll threw SecurityError", exp.rejectReason)
        assertEquals(
            "cookies API: rejected (cookies.getAll threw SecurityError)",
            exp.cookiesApiStateText(true)
        )
    }

    // ---------- names merging ----------

    @Test
    fun names_merge_across_probes_and_stores() {
        val exp = CookieApiExperiment()
        exp.noteProbe(
            "youtube.com",
            listOf(StoreResult("default", listOf("SID")))
        )
        exp.noteProbe(
            "youtube.com",
            listOf(
                StoreResult("default", listOf("SID", "HSID")),
                StoreResult("firefox-container-1", listOf("SSID"))
            )
        )
        assertEquals(2, exp.probesRun)
        assertEquals(
            mapOf(
                "default" to listOf("SID", "HSID"),
                "firefox-container-1" to listOf("SSID")
            ),
            exp.stores
        )
        assertEquals(
            "cookies API: accepted · youtube.com · " +
                "default: 2 names (SID, HSID); firefox-container-1: 1 name (SSID)",
            exp.cookiesApiStateText(true)
        )
    }

    // ---------- first-wins ----------

    @Test
    fun verdict_is_first_wins() {
        val rejectedFirst = CookieApiExperiment()
        rejectedFirst.noteProbe("youtube.com", listOf(StoreResult("default", emptyList())))
        rejectedFirst.noteTimeout()
        rejectedFirst.noteProbe(
            "youtube.com", listOf(StoreResult("default", listOf("SID")))
        )
        assertEquals(CookieApiExperiment.Verdict.REJECTED, rejectedFirst.verdict)
        assertEquals(
            "0 cookies on youtube.com after 1 probe(s)",
            rejectedFirst.rejectReason
        )

        val acceptedFirst = CookieApiExperiment()
        acceptedFirst.noteProbe(
            "youtube.com", listOf(StoreResult("default", listOf("SID")))
        )
        acceptedFirst.noteProbe("youtube.com", listOf(StoreResult("default", emptyList())))
        acceptedFirst.noteTimeout()
        assertEquals(CookieApiExperiment.Verdict.ACCEPTED, acceptedFirst.verdict)
        assertNull(acceptedFirst.rejectReason)
    }

    @Test
    fun state_changed_fires_once_per_verdict() {
        val exp = CookieApiExperiment()
        var fires = 0
        exp.onStateChanged = { fires++ }
        exp.noteProbe("youtube.com", listOf(StoreResult("default", emptyList())))
        exp.noteTimeout()
        exp.noteProbe(
            "youtube.com", listOf(StoreResult("default", listOf("SID")))
        )
        exp.noteTimeout()
        assertEquals(1, fires)
    }

    // ---------- shipped asset shape ----------

    @Test
    fun background_js_probe_shape() {
        val js = asset("extensions/pioneer-probe/background.js")
        assertTrue(js.contains("probeCookiesApi"))
        assertTrue(js.contains("type: \"cookie-api-probe\""))
        // The probe queries the default store without a
        // storeId, then each listed store by id.
        assertTrue(js.contains("browser.cookies.getAll"))
        assertTrue(js.contains("browser.cookies.getAllCookieStores"))
        // The probe message carries api/host/stores/error —
        // names only, no value field of any kind.
        assertTrue(js.contains("stores:"))
        assertFalse(js.contains("cookieValue"))
        assertFalse(js.contains("cookie-value"))
        assertFalse(js.contains("cookie.value"))
        assertFalse(js.contains("document.cookie"))
        // The security rule is stated in the source.
        assertTrue(js.contains("names only, NEVER values"))
        // The flag rides the rules-update push.
        assertTrue(js.contains("r.cookiesApi"))
    }

    @Test
    fun manifest_grants_cookies_and_host_permission() {
        val manifest = asset("extensions/pioneer-probe/manifest.json")
        assertTrue(manifest.contains("\"cookies\""))
        // The cookies API needs host permission for the
        // queried URL — the probe has none today.
        assertTrue(manifest.contains("\"*://*/*\""))
    }
}
