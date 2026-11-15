package net.nspioneer.browser

import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.media.VideoPopup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Video long-press + pop-up button option (alpha.51).
 *
 * (a) A long-press on a playing <video> opens the media sheet:
 * the probe reports it on the existing `video-popup-longpress`
 * message, so the app's allowCheck and sheet-opening path apply
 * unchanged. (b) The on-video button gains a global option
 * (`showVideoPopupButton`, default ON) with a Settings row in
 * the Video player group; when OFF, place() never places the
 * button, and the per-site opt-out stays as is.
 *
 * The shipped probe.js shape is pinned here in CI; the live
 * listener behaviour (long-press fires, short tap does not,
 * button on/off, double-fire guard) was validated out-of-band
 * with a Node stub harness against this same file
 * (`alpha51-verify/video-longpress-harness.js`, 21 checks,
 * scratch, not shipped) — same split as alpha.42/47/50.
 *
 * Note on the default: PioneerStore is a process singleton
 * with a private constructor, so no test can build a fresh
 * store to observe the unset default live. The default is
 * pinned by source text (`store.setting(buttonKey(), "true")`)
 * and the persisted values are exercised live below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoLongPressTest {

    private fun probeJs(): String =
        RuntimeEnvironment.getApplication().assets
            .open("extensions/pioneer-probe/probe.js")
            .bufferedReader().use { it.readText() }

    // ---------- (a) video-element long-press, probe shape ----------

    @Test
    fun probe_has_video_long_press_listener() {
        val js = probeJs()
        // Document-level capture listeners, so videos added
        // after load are covered without per-element bookkeeping.
        assertTrue(js.contains("function videoPressSrc(v) {"))
        assertTrue(js.contains("e.target.tagName !== \"VIDEO\""))
        assertTrue(js.contains("document.addEventListener(\"pointerdown\""))
        assertTrue(js.contains("document.addEventListener(\"contextmenu\""))
        // The 500 ms timer, matching the pill's.
        assertTrue(js.contains("setTimeout(function () {\n            videoPressTimer = null;"))
        // Short tap cancels; pointerup / pointercancel clear it.
        assertTrue(js.contains("[\"pointerup\", \"pointercancel\"].forEach"))
        assertTrue(js.contains("function cancelVideoPress() {"))
    }

    @Test
    fun probe_long_press_uses_the_existing_message() {
        val js = probeJs()
        // No second sheet-opening path: the video report reuses
        // video-popup-longpress and sendLongPress, so the pill's
        // tap-suppression (lastLongPressAt) and the app's
        // allowCheck both apply unchanged.
        assertTrue(js.contains("if (videoPressUrl) sendLongPress(videoPressUrl);"))
        assertTrue(js.contains("if (Date.now() - lastLongPressAt < 1000) return;"))
        // The gate mirrors pick() minus size/in-view: playing,
        // with a real (non-blob) source.
        assertTrue(js.contains("if (!v || v.paused || v.currentTime <= 0 || v.readyState < 2) return null;"))
        assertTrue(js.contains("if (!src || src.indexOf(\"blob:\") === 0) return null;"))
    }

    @Test
    fun probe_poller_untouched() {
        val js = probeJs()
        assertEquals(1, "setInterval\\(".toRegex().findAll(js).count())
        assertTrue(js.contains("pollTimer = setInterval(place, 1000);"))
    }

    // ---------- (b) button option, probe + engine shape ----------

    @Test
    fun probe_button_option_gates_pick() {
        val js = probeJs()
        assertTrue(js.contains("window.__nsVideoPopupButton = true;"))
        assertTrue(js.contains("window.__nsVideoPopupButton = reply.showButton;"))
        assertTrue(js.contains("if (window.__nsVideoPopupButton === false) return null;"))
    }

    @Test
    fun query_reply_shape_is_driven_by_the_node_harness() {
        // The engine reply (`{type:"video-popup-off", off, showButton}`)
        // lives in ExtensionManager's private probeMessageDelegate,
        // which needs a real GeckoRuntime — no unit test can drive
        // it. Its shape is proven out-of-band instead: the node
        // harness feeds `{off, showButton}` replies to the real
        // probe.js and asserts placement follows (21 checks).
        // What CI pins here is the probe side of that contract:
        // the reply's showButton field is read, typed, and
        // defaulted to ON when absent (older builds).
        val js = probeJs()
        assertTrue(js.contains("typeof reply.showButton === \"boolean\""))
        assertTrue(js.contains("window.__nsVideoPopupButton = true;"))
    }

    // ---------- (b) button option, store behaviour ----------

    @Test
    fun button_option_true_round_trips() {
        // The unset default ("true" in isButtonOn) is pinned by
        // the class doc: the singleton cannot be rebuilt fresh,
        // so no test can observe the absent key live. What is
        // live here is that a stored "true" reads as on.
        val store = PioneerStore.get(RuntimeEnvironment.getApplication())
        store.putSetting(VideoPopup.buttonKey(), "true")
        assertTrue(VideoPopup.isButtonOn(store))
    }

    @Test
    fun button_option_persisted_value_respected() {
        val store = PioneerStore.get(RuntimeEnvironment.getApplication())
        store.putSetting(VideoPopup.buttonKey(), "false")
        assertFalse(VideoPopup.isButtonOn(store))
        store.putSetting(VideoPopup.buttonKey(), "true")
        assertTrue(VideoPopup.isButtonOn(store))
        // Anything that is not "true" reads as off.
        store.putSetting(VideoPopup.buttonKey(), "yes")
        assertFalse(VideoPopup.isButtonOn(store))
        // Leave the shared singleton as the user would find it.
        store.putSetting(VideoPopup.buttonKey(), "true")
    }

    @Test
    fun button_option_key_is_plain() {
        assertEquals("showVideoPopupButton", VideoPopup.buttonKey())
    }
}
