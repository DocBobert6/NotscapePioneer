package net.nspioneer.browser.engine

import android.os.Looper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Alpha.64: the real cookie wiring — the download job asks
 * the background script for one host's cookies through the
 * cookies API and replays them host-scoped.
 *
 * The background script is proven alive (alpha.62) and the
 * native port carries messages both ways (alpha.61): the
 * app pushes `rules-update` on it, the background script
 * posts its reports on it. This requester is the app side
 * of a new request/response pair on that same port:
 * `cookies-request {id, url}` out, `cookies-response
 * {id, url, cookies:[{name, value}], error?}` back.
 *
 * VALUES ARE REQUIRED HERE — replay needs them — and this
 * is the ONE path where cookie values exist in Kotlin.
 * Rules for values: they live only in the pending reply
 * and in the per-job jar that consumes them; they are
 * never persisted to disk or the store, never logged,
 * never put in Diagnostics or any status line. Only the
 * COUNT per host may be observed, via [noteAttach]
 * ("cookies: N for <host>" — count + host, no names, no
 * values), and only when the engine has installed a sink.
 *
 * Failure is always cookie-less, never fatal: no sender
 * (port down), a send that fails, a timeout, an error
 * reply, or an empty reply all yield an empty list and
 * the job proceeds without cookies. The request never
 * runs on the main thread — a main-thread call returns
 * empty at once instead of blocking the UI.
 */
object CookieRequester {

    /** Bounded wait for one reply. */
    const val TIMEOUT_MS = 5000L

    private class Pending(val latch: CountDownLatch) {
        @Volatile var result: List<Pair<String, String>>? = null
    }

    private val pending = ConcurrentHashMap<String, Pending>()

    /** The port sender, installed by [BlockingObserver]
     *  when the background port connects and cleared when
     *  it disconnects. Null means no channel: every
     *  request answers empty at once. */
    @Volatile
    private var sender: ((JSONObject) -> Boolean)? = null

    /** Count-only status sink ("cookies: N for <host>"),
     *  installed by the engine. Null means nobody is
     *  listening: attaches are silent. Never carries
     *  names or values. */
    @Volatile
    var statusSink: ((String) -> Unit)? = null

    /** Main-thread guard, injectable so tests can call
     *  [requestCookies] from the test thread (Robolectric
     *  runs tests on the main looper). Production always
     *  uses the real check. */
    internal var mainThreadCheck: () -> Boolean =
        { Looper.getMainLooper()?.isCurrentThread == true }

    fun registerSender(send: (JSONObject) -> Boolean) {
        sender = send
    }

    fun clearSender() {
        sender = null
    }

    /** One attach observed: count + host only, via the
     *  sink when one is installed. */
    fun noteAttach(host: String, count: Int) {
        statusSink?.invoke("cookies: $count for $host")
    }

    /**
     * Cookies for [url] (default store, no storeId —
     * alpha.63 proved the tab's cookies live there),
     * as name/value pairs. Empty on every failure path:
     * no sender, a failed send, a timeout, an error
     * reply, or an empty reply. Blocking, bounded by
     * [timeoutMs]; never call from the main thread.
     */
    fun requestCookies(
        url: String,
        timeoutMs: Long = TIMEOUT_MS,
    ): List<Pair<String, String>> {
        val send = sender ?: return emptyList()
        if (mainThreadCheck()) return emptyList()
        val id = UUID.randomUUID().toString()
        val p = Pending(CountDownLatch(1))
        pending[id] = p
        try {
            val message = JSONObject().apply {
                put("type", "cookies-request")
                put("id", id)
                put("url", url)
            }
            if (!runCatching { send(message) }.getOrDefault(false)) {
                return emptyList()
            }
            p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return p.result ?: emptyList()
        } finally {
            pending.remove(id)
        }
    }

    /** Complete one pending request. An error reply (or
     *  a null cookie list) completes with an empty list —
     *  the job proceeds without cookies. Unknown ids are
     *  ignored: a late reply after a timeout must not
     *  complete anything. */
    fun onResponse(
        id: String,
        cookies: List<Pair<String, String>>?,
        error: String?,
    ) {
        val p = pending[id] ?: return
        p.result = if (error.isNullOrBlank()) cookies ?: emptyList() else emptyList()
        p.latch.countDown()
    }

    /** Pending requests (tests only). */
    internal fun pendingCount(): Int = pending.size
}
