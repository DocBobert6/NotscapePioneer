package net.nspioneer.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Probe video-popup polling (alpha.42, audit E-A4): the shipped
 * probe.js must not run a free-running 1 s interval.
 *
 * This test pins the SHAPE of the shipped asset in CI — the one
 * `setInterval` lives inside `startPoll`, `place()` starts it
 * only when it shows the button and stops it on every hide, and
 * the visibility listener re-arms it. It cannot run the script:
 * the start/stop behaviour itself (play/pause/hide/opt-out/
 * scroll-out/fullscreen, 21 checks) was validated out-of-band
 * with a Node stub harness against this same file
 * (`alpha42-verify/probe-poll-harness.js`, scratch, not shipped).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProbePollingTest {

    private fun probeJs(): String =
        RuntimeEnvironment.getApplication().assets
            .open("extensions/pioneer-probe/probe.js")
            .bufferedReader().use { it.readText() }

    @Test
    fun exactly_one_setInterval_and_it_lives_in_startPoll() {
        val js = probeJs()
        val calls = "setInterval\\(".toRegex().findAll(js).count()
        assertEquals(1, calls)
        assertTrue(js.contains("pollTimer = setInterval(place, 1000);"))
        // The old install-time call must be gone: no bare
        // `setInterval(place, 1000);` statement anywhere.
        assertTrue(!js.contains("\n      setInterval(place, 1000);\n"))
    }

    @Test
    fun poller_has_start_and_stop_guards() {
        val js = probeJs()
        assertTrue(js.contains("function startPoll() {"))
        assertTrue(js.contains("function stopPoll() {"))
        // startPoll is idempotent: repeated place() calls while a
        // video is showing must not stack timers.
        assertTrue(js.contains("if (pollTimer !== null) return;"))
        assertTrue(js.contains("clearInterval(pollTimer)"))
    }

    @Test
    fun place_manages_the_poller_on_every_path() {
        val js = probeJs()
        // Success path starts it; both hide paths stop it; the
        // catch path stops it too.
        assertTrue(js.contains("startPoll();"))
        assertTrue(js.contains("stopPoll();"))
        assertTrue(js.contains("return true;"))
        assertTrue(js.contains("return false;"))
    }

    @Test
    fun hidden_page_and_visibility_rearm_are_wired() {
        val js = probeJs()
        assertTrue(js.contains("document.hidden"))
        assertTrue(
            js.contains("document.addEventListener(\"visibilitychange\", place);")
        )
    }

    @Test
    fun event_listeners_still_drive_place() {
        val js = probeJs()
        // The re-arm surface is unchanged: play/playing/pause/
        // ended/scroll/resize/fullscreenchange all still call place().
        assertTrue(js.contains("document.addEventListener(\"play\", place, true);"))
        assertTrue(js.contains("document.addEventListener(\"pause\", place, true);"))
        assertTrue(js.contains("window.addEventListener(\"scroll\", place, true);"))
        assertTrue(js.contains("document.addEventListener(\"fullscreenchange\", place);"))
    }
}
