package net.nspioneer.browser

import net.nspioneer.browser.engine.BlockingObserver
import net.nspioneer.browser.engine.ExtensionManager
import net.nspioneer.browser.engine.ProbeHost
import net.nspioneer.browser.engine.StatusPanel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Port-based background reporting (alpha.61).
 *
 * Device evidence (alpha.60) showed the background script's
 * one-shot native messages never arrive — the headers-state
 * report, sent once unconditionally at startup, never reached
 * Diagnostics — while the content script's one-shot reports
 * arrive fine. Since alpha.61 every background-script report
 * goes out on the already-connected native port instead, and
 * the background script posts a startup handshake on it.
 *
 * What is pinned here: the handshake state text (pure), the
 * shipped background.js shape (port send + handshake + every
 * moved type, no one-shot send left, still names-only), and
 * the port delegate's routing — each background type reaches
 * exactly one handler, once. The delegate is private by
 * design, so the routing tests reach it by reflection; the
 * end-to-end proof (a handshake arriving on a real device)
 * is the Diagnostics line itself, device-proven only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundPortTest {

    private fun asset(path: String): String =
        RuntimeEnvironment.getApplication().assets
            .open(path).bufferedReader().use { it.readText() }

    // ---------- handshake state text ----------

    @Test
    fun background_state_text_initial() {
        assertEquals(
            "background: not seen yet",
            BlockingObserver.backgroundStateText(false)
        )
    }

    @Test
    fun background_state_text_alive() {
        assertEquals(
            "background: alive · port connected",
            BlockingObserver.backgroundStateText(true)
        )
    }

    // ---------- shipped background.js shape ----------

    @Test
    fun background_js_port_shape() {
        val js = asset("extensions/pioneer-probe/background.js")
        // The port send + handshake + every moved type.
        assertTrue(js.contains("background-alive"))
        assertTrue(js.contains("nativePort.postMessage"))
        assertTrue(js.contains("type: \"rules-request\""))
        assertTrue(js.contains("connectNative"))
        assertTrue(js.contains("onDisconnect"))
        assertTrue(js.contains("type: \"cookie-capture-probe\""))
        assertTrue(js.contains("type: \"cookie-capture\""))
        assertTrue(js.contains("type: \"cookie-capture-tick\""))
        assertTrue(js.contains("type: \"headers-state\""))
        // No one-shot send left for background code.
        assertFalse(js.contains("sendNativeMessage"))
        assertFalse(js.contains("function sendNative"))
        // Still names-only: no value of any kind anywhere.
        assertFalse(js.contains("cookieValue"))
        assertFalse(js.contains("document.cookie"))
    }

    // ---------- port delegate routing ----------

    /** Records every ProbeHost call the delegate makes. */
    private class RecordingHost : ProbeHost {
        val calls = mutableListOf<String>()
        val netMedia = mutableListOf<Triple<String, String?, Long?>>()
        var lastCapture: Triple<String, List<String>, Int>? = null
        var lastTick: Int? = null
        var lastProbe: Triple<Int, Int, Boolean>? = null

        override fun tabNumberFor(session: GeckoSession?): Int = -1
        override fun zoomFactorFor(session: GeckoSession?): Float = 1.0f
        override fun noteProbeMedia(tabNumber: Int, kind: String, url: String) {
            calls.add("probeMedia")
        }
        override fun registerProbePort(session: GeckoSession?, port: WebExtension.Port) {
            calls.add("registerProbePort")
        }
        override fun unregisterProbePort(port: WebExtension.Port) {
            calls.add("unregisterProbePort")
        }
        override fun noteNetMedia(
            session: GeckoSession?,
            url: String,
            mime: String?,
            contentLength: Long?
        ) {
            calls.add("noteNetMedia")
            netMedia.add(Triple(url, mime, contentLength))
        }
        override fun noteVideoPopupTap(session: GeckoSession?, url: String) {
            calls.add("videoPopupTap")
        }
        override fun noteVideoPopupLongPress(session: GeckoSession?, url: String) {
            calls.add("videoPopupLongPress")
        }
        override fun videoPopupOff(host: String): Boolean = false
        override fun showVideoPopupButton(): Boolean = true
        override fun notePageHtml(session: GeckoSession?, html: String) {
            calls.add("pageHtml")
        }
        override fun handleBlockingMessage(
            message: JSONObject,
            senderSession: GeckoSession?
        ): GeckoResult<Any>? {
            // The real bridge is a no-op for types it does
            // not own; only owned types are recorded, so an
            // unknown type leaves no trace — as in production.
            val type = message.optString("type")
            if (type in setOf(
                    "media-net", "headers-state", "blocked",
                    "cosmetic-query", "rules-request"
                )
            ) {
                calls.add("blocking:$type")
            }
            return null
        }
        override fun onBackgroundPortConnected(port: WebExtension.Port) {
            calls.add("portConnected")
        }
        override fun onBackgroundPortDisconnected(port: WebExtension.Port) {
            calls.add("portDisconnected")
        }
        override fun noteCookieCapture(host: String, names: List<String>, requests: Int) {
            calls.add("cookieCapture")
            lastCapture = Triple(host, names, requests)
        }
        override fun noteCookieCaptureTick(requests: Int) {
            calls.add("cookieCaptureTick")
            lastTick = requests
        }
        override fun noteCookieCaptureProbe(events: Int, inScope: Int, flagOn: Boolean) {
            calls.add("cookieCaptureProbe")
            lastProbe = Triple(events, inScope, flagOn)
        }
        override fun noteBackgroundAlive() {
            calls.add("backgroundAlive")
        }
        override fun pushRulesUpdateToBackground(): Boolean {
            calls.add("pushRulesUpdate")
            return true
        }
    }

    private fun manager(host: ProbeHost): ExtensionManager {
        return ExtensionManager(
            mock(GeckoRuntime::class.java),
            mock(StatusPanel::class.java),
            host,
            { emptyList() },
            { null }
        )
    }

    private fun portDelegate(manager: ExtensionManager): WebExtension.PortDelegate {
        val field = ExtensionManager::class.java.getDeclaredField("backgroundPortDelegate")
        field.isAccessible = true
        return field.get(manager) as WebExtension.PortDelegate
    }

    private fun port(): WebExtension.Port = mock(WebExtension.Port::class.java)

    private fun routed(host: RecordingHost, json: String): RecordingHost {
        portDelegate(manager(host)).onPortMessage(JSONObject(json), port())
        return host
    }

    @Test
    fun port_routes_background_alive() {
        val host = routed(RecordingHost(), """{"type":"background-alive"}""")
        assertEquals(listOf("backgroundAlive"), host.calls)
    }

    @Test
    fun port_routes_cookie_capture() {
        val host = routed(
            RecordingHost(),
            """{"type":"cookie-capture","host":"example.com","names":["sessionid","csrftoken"],"requests":7}"""
        )
        assertEquals(listOf("cookieCapture"), host.calls)
        assertEquals(
            Triple("example.com", listOf("sessionid", "csrftoken"), 7),
            host.lastCapture
        )
    }

    @Test
    fun port_routes_cookie_capture_tick() {
        val host = routed(
            RecordingHost(),
            """{"type":"cookie-capture-tick","requests":9}"""
        )
        assertEquals(listOf("cookieCaptureTick"), host.calls)
        assertEquals(9, host.lastTick)
    }

    @Test
    fun port_routes_cookie_capture_probe() {
        val host = routed(
            RecordingHost(),
            """{"type":"cookie-capture-probe","events":27,"inScope":12,"flagOn":true}"""
        )
        assertEquals(listOf("cookieCaptureProbe"), host.calls)
        assertEquals(Triple(27, 12, true), host.lastProbe)
    }

    @Test
    fun port_routes_media_net() {
        val host = routed(
            RecordingHost(),
            """{"type":"media-net","url":"https://example.com/clip.m3u8","mime":"application/x-mpegurl","contentLength":"1234"}"""
        )
        assertEquals(listOf("noteNetMedia"), host.calls)
        assertEquals(
            listOf(Triple("https://example.com/clip.m3u8", "application/x-mpegurl", 1234L)),
            host.netMedia
        )
    }

    @Test
    fun port_routes_headers_state_to_bridge() {
        val host = routed(
            RecordingHost(),
            """{"type":"headers-state","on":true}"""
        )
        assertEquals(listOf("blocking:headers-state"), host.calls)
    }

    @Test
    fun port_routes_blocked_to_bridge() {
        val host = routed(
            RecordingHost(),
            """{"type":"blocked","url":"https://ads.example.org/x.png","category":0}"""
        )
        assertEquals(listOf("blocking:blocked"), host.calls)
    }

    @Test
    fun port_routes_rules_request_to_push() {
        val host = routed(RecordingHost(), """{"type":"rules-request"}""")
        assertEquals(listOf("pushRulesUpdate"), host.calls)
    }

    @Test
    fun port_ignores_unknown_type() {
        val host = routed(RecordingHost(), """{"type":"zoom-query"}""")
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test
    fun port_ignores_non_json_message() {
        val host = RecordingHost()
        portDelegate(manager(host)).onPortMessage("not json", port())
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test
    fun port_routes_each_type_exactly_once() {
        val host = RecordingHost()
        val delegate = portDelegate(manager(host))
        val p = port()
        delegate.onPortMessage(JSONObject("""{"type":"background-alive"}"""), p)
        delegate.onPortMessage(
            JSONObject("""{"type":"cookie-capture","host":"h","names":[],"requests":1}"""), p
        )
        delegate.onPortMessage(JSONObject("""{"type":"cookie-capture-tick","requests":2}"""), p)
        delegate.onPortMessage(
            JSONObject("""{"type":"cookie-capture-probe","events":3,"inScope":3,"flagOn":false}"""), p
        )
        delegate.onPortMessage(
            JSONObject("""{"type":"media-net","url":"https://example.com/a.mp4"}"""), p
        )
        delegate.onPortMessage(JSONObject("""{"type":"headers-state","on":false}"""), p)
        delegate.onPortMessage(JSONObject("""{"type":"blocked","url":"https://x/y"}"""), p)
        delegate.onPortMessage(JSONObject("""{"type":"rules-request"}"""), p)
        assertEquals(
            listOf(
                "backgroundAlive",
                "cookieCapture",
                "cookieCaptureTick",
                "cookieCaptureProbe",
                "noteNetMedia",
                "blocking:headers-state",
                "blocking:blocked",
                "pushRulesUpdate"
            ),
            host.calls
        )
    }
}
