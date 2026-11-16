package net.nspioneer.browser

import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.engine.MainWorldExperiment
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
 * MAIN-world experiment (alpha.52, gap plan row A-2).
 *
 * The verdict state machine is pure: ACCEPTED only when the
 * sentinel proves MAIN world; install failure, an
 * isolated-world sentinel, or no sentinel within the bounded
 * wait is REJECTED; first-wins. The flag defaults OFF.
 *
 * Note on the default: PioneerStore is a process singleton
 * with a private constructor, so no test can build a fresh
 * store to observe the unset default live. The default is
 * pinned by source text (`store.setting(flagKey(), "false")`)
 * and the persisted values are exercised live below — the
 * same split as alpha.51's button option.
 *
 * The shipped manifest/script shape is pinned here in CI;
 * the live accept/reject outcome is device-proven only.
 * Alpha.66 re-tests after the alpha.52 false negative: the
 * old script reported via the extension messaging API,
 * which does not exist in MAIN world, so its "rejection"
 * proved nothing about whether MAIN-world code runs.
 * The expected outcome is unknown — that is the point of
 * the re-test. A rejection now would confirm the ceiling
 * for real; an acceptance opens page-context interception
 * as a later, separately approved build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainWorldExperimentTest {

    private fun asset(path: String): String =
        RuntimeEnvironment.getApplication().assets
            .open(path).bufferedReader().use { it.readText() }

    // ---------- flag ----------

    @Test
    fun flag_key_is_plain() {
        assertEquals("probeMainWorld", MainWorldExperiment.flagKey())
    }

    @Test
    fun flag_stored_values_round_trip() {
        val store = PioneerStore.get(RuntimeEnvironment.getApplication())
        store.putSetting(MainWorldExperiment.flagKey(), "false")
        assertFalse(MainWorldExperiment.isEnabled(store))
        store.putSetting(MainWorldExperiment.flagKey(), "true")
        assertTrue(MainWorldExperiment.isEnabled(store))
        // Anything that is not "true" reads as off.
        store.putSetting(MainWorldExperiment.flagKey(), "yes")
        assertFalse(MainWorldExperiment.isEnabled(store))
        // Leave the shared singleton as the user would find it.
        store.putSetting(MainWorldExperiment.flagKey(), "false")
    }

    // ---------- state text ----------

    @Test
    fun state_text_off_when_flag_off() {
        val exp = MainWorldExperiment()
        assertEquals("probe MAIN world: off", exp.mainWorldStateText(false))
        // Even a recorded verdict does not show while off.
        exp.noteSentinel(true)
        assertEquals("probe MAIN world: off", exp.mainWorldStateText(false))
    }

    @Test
    fun state_text_unknown_until_first_report() {
        val exp = MainWorldExperiment()
        assertEquals(MainWorldExperiment.Verdict.UNKNOWN, exp.verdict)
        assertNull(exp.rejectReason)
        assertEquals("probe MAIN world: unknown", exp.mainWorldStateText(true))
    }

    @Test
    fun state_text_accepted_on_main_world_sentinel() {
        val exp = MainWorldExperiment()
        exp.noteSentinel(true)
        assertEquals(MainWorldExperiment.Verdict.ACCEPTED, exp.verdict)
        assertNull(exp.rejectReason)
        assertEquals("probe MAIN world: accepted", exp.mainWorldStateText(true))
    }

    @Test
    fun state_text_rejected_on_isolated_sentinel() {
        val exp = MainWorldExperiment()
        exp.noteSentinel(false)
        assertEquals(MainWorldExperiment.Verdict.REJECTED, exp.verdict)
        assertEquals("sentinel: isolated world", exp.rejectReason)
        assertEquals("probe MAIN world: rejected", exp.mainWorldStateText(true))
    }

    @Test
    fun state_text_rejected_on_install_failure() {
        val exp = MainWorldExperiment()
        exp.noteInstallFailed("boom")
        assertEquals(MainWorldExperiment.Verdict.REJECTED, exp.verdict)
        assertEquals("install failed: boom", exp.rejectReason)
        assertEquals("probe MAIN world: rejected", exp.mainWorldStateText(true))
        val noReason = MainWorldExperiment()
        noReason.noteInstallFailed(null)
        assertEquals("install failed: unknown error", noReason.rejectReason)
    }

    @Test
    fun state_text_rejected_on_timeout() {
        val exp = MainWorldExperiment()
        exp.noteTimeout()
        assertEquals(MainWorldExperiment.Verdict.REJECTED, exp.verdict)
        assertEquals("no sentinel 10 s after page load", exp.rejectReason)
        assertEquals("probe MAIN world: rejected", exp.mainWorldStateText(true))
    }

    // ---------- first-wins ----------

    @Test
    fun verdict_is_first_wins() {
        val rejectedFirst = MainWorldExperiment()
        rejectedFirst.noteSentinel(false)
        rejectedFirst.noteSentinel(true)
        rejectedFirst.noteTimeout()
        assertEquals(MainWorldExperiment.Verdict.REJECTED, rejectedFirst.verdict)
        assertEquals("sentinel: isolated world", rejectedFirst.rejectReason)

        val acceptedFirst = MainWorldExperiment()
        acceptedFirst.noteSentinel(true)
        acceptedFirst.noteSentinel(false)
        acceptedFirst.noteInstallFailed("boom")
        assertEquals(MainWorldExperiment.Verdict.ACCEPTED, acceptedFirst.verdict)
        assertNull(acceptedFirst.rejectReason)
    }

    @Test
    fun state_changed_fires_once_per_verdict() {
        val exp = MainWorldExperiment()
        var fires = 0
        exp.onStateChanged = { fires++ }
        exp.noteSentinel(false)
        exp.noteSentinel(true)
        exp.noteTimeout()
        assertEquals(1, fires)
    }

    // ---------- shipped asset shape ----------

    @Test
    fun manifest_asks_for_main_world() {
        val manifest = asset("extensions/pioneer-main-world/manifest.json")
        assertTrue(manifest.contains("\"world\":\"MAIN\""))
        assertTrue(manifest.contains("pioneer-main-world@pioneer"))
        assertTrue(manifest.contains("main-world.js"))
        assertTrue(manifest.contains("\"manifest_version\":2"))
        // Alpha.66 re-test: document_start (page-context work
        // needs the earliest run) and the host permission the
        // alpha.52 manifest lacked.
        assertTrue(manifest.contains("\"run_at\":\"document_start\""))
        assertTrue(manifest.contains("\"*://*/*\""))
    }

    @Test
    fun sentinel_script_shape() {
        val js = asset("extensions/pioneer-main-world/main-world.js")
        // Alpha.66 re-test: the script reports by writing to
        // the page itself — a window flag + a CustomEvent —
        // and the ISOLATED-world probe relays it. The
        // structural MAIN-world discriminator is unchanged:
        // both checks must agree, so a disagreement reports
        // isolated.
        assertTrue(js.contains("__pioneerMainWorldProbe"))
        assertTrue(js.contains("CustomEvent(\"pioneer-main-world-probe\""))
        assertTrue(js.contains("typeof window.wrappedJSObject === \"undefined\""))
        assertTrue(js.contains("this === window"))
        // The alpha.52 bug, pinned as absent: MAIN world has
        // NO extension APIs, so the script must never call
        // one — and it no longer sends the native message
        // itself (probe.js relays it). No patching, no fetch.
        assertFalse(js.contains("browser."))
        assertFalse(js.contains("probe-main-world-ok"))
        assertFalse(js.contains("XMLHttpRequest"))
        assertFalse(js.contains("fetch("))
    }
}
