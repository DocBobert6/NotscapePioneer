package net.nspioneer.browser

import net.nspioneer.browser.data.PioneerStore
import net.nspioneer.browser.engine.PermPrompt
import net.nspioneer.browser.engine.PioneerPermissionDelegate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * S-A1 (alpha.32): closing a tab after answering a camera /
 * mic / geolocation prompt crashed the app on a double-completed
 * GeckoResult — the pending maps were only cleaned by answer(),
 * which nothing calls, so destroyTab's rejectOutstanding
 * re-completed the already-answered request.
 *
 * These tests drive the delegate directly: answer a prompt
 * (grant or deny), then rejectOutstanding for the same session
 * — the tab-close path — and assert the request is answered
 * exactly once and nothing throws. An unanswered prompt must
 * still be denied exactly once by rejectOutstanding (the
 * legitimate path that must never hang).
 *
 * Same shape as DownloadsStoreTest: Robolectric, runtime SDK
 * 35, a real PioneerStore. The GeckoRuntime is a Mockito mock
 * — the delegate only touches it through mirrorContentDecision
 * / setTrackingException (both runCatching), and no test below
 * takes a path that reaches it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PermissionDelegateTest {

    private lateinit var store: PioneerStore

    /** MediaSource has only a protected empty constructor
     *  ("for tests") — subclass it to get an instance. */
    private class FakeMediaSource : GeckoSession.PermissionDelegate.MediaSource()

    private class FakeMediaCallback : GeckoSession.PermissionDelegate.MediaCallback {
        var grants = 0
        var rejects = 0
        var lastVideo: GeckoSession.PermissionDelegate.MediaSource? = null
        var lastAudio: GeckoSession.PermissionDelegate.MediaSource? = null

        override fun grant(
            video: GeckoSession.PermissionDelegate.MediaSource?,
            audio: GeckoSession.PermissionDelegate.MediaSource?,
        ) {
            grants++
            lastVideo = video
            lastAudio = audio
        }

        override fun reject() {
            rejects++
        }
    }

    @Before
    fun setUp() {
        store = PioneerStore.get(RuntimeEnvironment.getApplication())
        store.settings.clear()
    }

    private fun delegate(
        delivered: MutableList<PermPrompt>,
    ): PioneerPermissionDelegate = PioneerPermissionDelegate(
        appContext = RuntimeEnvironment.getApplication(),
        store = store,
        runtime = mock(GeckoRuntime::class.java),
        deliverPrompt = { delivered += it },
        // The OS gate grants everything in tests.
        checkOs = { resources, onResult -> onResult(resources) },
    )

    private fun geoRequest(uri: String): GeckoSession.PermissionDelegate.ContentPermission {
        val json = JSONObject().apply {
            put("uri", uri)
            put("perm", "geolocation")
            put(
                "value",
                GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT,
            )
            put("privateMode", false)
        }
        return GeckoSession.PermissionDelegate.ContentPermission.fromJson(json)
            ?: error("fromJson returned null for geolocation")
    }

    @Test
    fun media_allow_answer_then_tab_close_does_not_reanswer() {
        val host = "cam-allow.example"
        store.putSitePermission(host, "camera", "allow")
        val delivered = mutableListOf<PermPrompt>()
        val d = delegate(delivered)
        val session = GeckoSession()
        val callback = FakeMediaCallback()

        d.onMediaPermissionRequest(
            session, "https://$host/",
            arrayOf(FakeMediaSource()), null, callback,
        )

        assertEquals(1, callback.grants)
        assertEquals(0, callback.rejects)
        assertTrue(delivered.isEmpty())
        // Tab close after the answer: must not throw, and must
        // not answer the request a second time.
        d.rejectOutstanding(session)
        assertEquals(1, callback.grants)
        assertEquals(0, callback.rejects)
    }

    @Test
    fun media_deny_answer_then_tab_close_does_not_reanswer() {
        val host = "cam-deny.example"
        store.putSitePermission(host, "camera", "deny")
        val delivered = mutableListOf<PermPrompt>()
        val d = delegate(delivered)
        val session = GeckoSession()
        val callback = FakeMediaCallback()

        d.onMediaPermissionRequest(
            session, "https://$host/",
            arrayOf(FakeMediaSource()), null, callback,
        )

        assertEquals(0, callback.grants)
        assertEquals(1, callback.rejects)
        d.rejectOutstanding(session)
        assertEquals(0, callback.grants)
        assertEquals(1, callback.rejects)
    }

    @Test
    fun media_unanswered_then_tab_close_denies_once() {
        val host = "cam-ask.example"
        val delivered = mutableListOf<PermPrompt>()
        val d = delegate(delivered)
        val session = GeckoSession()
        val callback = FakeMediaCallback()

        d.onMediaPermissionRequest(
            session, "https://$host/",
            arrayOf(FakeMediaSource()), null, callback,
        )

        assertEquals(1, delivered.size)
        assertEquals(0, callback.grants)
        assertEquals(0, callback.rejects)
        d.rejectOutstanding(session)
        assertEquals(0, callback.grants)
        assertEquals(1, callback.rejects)
    }

    @Test
    fun media_mixed_answer_then_tab_close_does_not_reanswer() {
        val host = "cam-mixed.example"
        store.putSitePermission(host, "camera", "allow")
        // Microphone stays "ask" — the mixed prompt asks about
        // it while granting the decided camera together.
        val delivered = mutableListOf<PermPrompt>()
        val d = delegate(delivered)
        val session = GeckoSession()
        val callback = FakeMediaCallback()

        d.onMediaPermissionRequest(
            session, "https://$host/",
            arrayOf(FakeMediaSource()), arrayOf(FakeMediaSource()), callback,
        )

        assertEquals(1, delivered.size)
        assertEquals(listOf("microphone"), delivered[0].resources)
        delivered[0].onGrant(listOf("microphone"))
        assertEquals(1, callback.grants)
        assertEquals(0, callback.rejects)
        assertNotNull(callback.lastVideo)
        assertNotNull(callback.lastAudio)
        d.rejectOutstanding(session)
        assertEquals(1, callback.grants)
        assertEquals(0, callback.rejects)
    }

    @Test
    fun content_geolocation_deny_then_tab_close_does_not_reanswer() {
        val host = "geo-deny.example"
        store.putSitePermission(host, "geolocation", "deny")
        val delivered = mutableListOf<PermPrompt>()
        val d = delegate(delivered)
        val session = GeckoSession()

        val result = d.onContentPermissionRequest(session, geoRequest("https://$host/"))

        assertNotNull(result)
        // Tab close after the denial: the already-completed
        // GeckoResult must not be completed a second time.
        d.rejectOutstanding(session)
    }
}
