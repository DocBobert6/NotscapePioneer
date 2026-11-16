package net.nspioneer.browser.engine

import net.nspioneer.browser.data.PioneerStore

/**
 * Alpha.59 — the cookie-capture experiment.
 *
 * Quetta attaches the page's cookies to its downloads;
 * Pioneer cannot — GeckoView exposes no cookie API to the
 * app, so the download bundle's cookie is always empty.
 * The one candidate workaround is the webRequest observer:
 * desktop Firefox delivers the `Cookie` header in
 * `onBeforeSendHeaders` requestHeaders, and the app already
 * has the host-scoped plumbing waiting for a source
 * (Downloads.kt `CookieSnapshot` + `cookieForRequest`).
 * This class is the experiment, not a feature: it records
 * whether GeckoView delivers a `Cookie` header to the
 * probe's `onBeforeSendHeaders` listener, and nothing else
 * is built on it.
 *
 * SECURITY — names only, NEVER values: the probe reports
 * cookie NAMES (e.g. "sessionid, csrftoken") and the
 * request count. No cookie value is logged, stored,
 * persisted, or sent anywhere in this alpha. The verdict
 * is first-wins and conservative: it is ACCEPTED only when
 * a `Cookie` header is seen on an in-scope request; a
 * bounded wait with requests observed and zero `Cookie`
 * headers is REJECTED. A rejection is a successful
 * experiment, not a failure — it confirms the ceiling.
 *
 * The expected outcome is unknown — that is the point of
 * the experiment. Turning the flag off stops new
 * observations and the Diagnostics line reads "off".
 */
class CookieCaptureExperiment {

    enum class Verdict { UNKNOWN, ACCEPTED, REJECTED }

    companion object {
        /** Store flag for the experiment (default OFF). */
        fun flagKey(): String = "probeCookieCapture"

        fun isEnabled(store: PioneerStore): Boolean =
            store.setting(flagKey(), "false") == "true"

        /** How many hosts the ACCEPTED line shows before
         *  "+N more" — the Diagnostics line must stay one
         *  readable line. */
        const val MAX_HOSTS_SHOWN = 5

        /** How many cookie names per host the ACCEPTED line
         *  shows before "+N more". */
        const val MAX_NAMES_SHOWN = 8

        /** Alpha.60: the observer line — "cookie observer: N
         *  events · M in scope · flag on/off". Pure, so the
         *  Diagnostics text is pinned in tests. */
        fun observerStateText(events: Int, inScope: Int, flagOn: Boolean): String =
            "cookie observer: $events events · $inScope in scope · " +
                "flag ${if (flagOn) "on" else "off"}"
    }

    var verdict: Verdict = Verdict.UNKNOWN
        private set

    /** Why a REJECTED verdict was recorded, for the status log. */
    var rejectReason: String? = null
        private set

    /** In-scope requests observed while the flag was on
     *  (last tick count from the probe). */
    var requestsObserved: Int = 0
        private set

    /** Hosts that delivered a `Cookie` header, with the
     *  cookie NAMES seen there (never values). Grows after
     *  ACCEPTED too — it is evidence, not verdict. */
    private val _hosts = linkedMapOf<String, MutableList<String>>()
    val hosts: Map<String, List<String>> get() = _hosts

    /** Fired when the verdict changes, so the engine can refresh
     *  its Diagnostics state text (same shape as
     *  MainWorldExperiment.onStateChanged). */
    var onStateChanged: (() -> Unit)? = null

    /**
     * One `Cookie` header seen on [host] for [names]
     * (names only — the probe never sends values).
     * [requests] is the probe's in-scope request count at
     * that moment. ACCEPTED on the first call; later calls
     * only merge names.
     */
    fun noteCookieNames(host: String, names: List<String>, requests: Int) {
        if (requests > requestsObserved) requestsObserved = requests
        if (host.isNotEmpty()) {
            val known = _hosts.getOrPut(host) { mutableListOf() }
            for (name in names) {
                if (name.isNotEmpty() && name !in known) known.add(name)
            }
        }
        setAccepted()
    }

    /** A tick from the probe: [requests] in-scope requests
     *  observed with no `Cookie` header yet. Monotonic. */
    fun noteTick(requests: Int) {
        if (requests > requestsObserved) requestsObserved = requests
    }

    /**
     * The bounded wait after a page load expired with no
     * `Cookie` header. Rejects only when requests were
     * actually observed — with zero requests there is no
     * evidence either way, so the verdict stays UNKNOWN and
     * the engine re-arms on the next page load.
     */
    fun noteTimeout() {
        if (verdict != Verdict.UNKNOWN) return
        if (requestsObserved <= 0) return
        setRejected("$requestsObserved requests observed, 0 Cookie headers")
    }

    /** "cookie capture: off/unknown/accepted/rejected" — for
     *  Diagnostics. "off" when the flag is off (no attempt
     *  was made); "unknown" until the first report. The
     *  ACCEPTED line names the hosts and cookie names seen
     *  (names only — never values). */
    fun cookieCaptureStateText(flagOn: Boolean): String {
        if (!flagOn) return "cookie capture: off"
        return when (verdict) {
            Verdict.UNKNOWN -> "cookie capture: unknown"
            Verdict.ACCEPTED -> {
                val shown = _hosts.entries.take(MAX_HOSTS_SHOWN)
                val hostsText = shown.joinToString("; ") { (host, names) ->
                    val shownNames = names.take(MAX_NAMES_SHOWN)
                    val extra = names.size - shownNames.size
                    "$host (${shownNames.joinToString(", ")}" +
                        (if (extra > 0) ", +$extra more" else "") + ")"
                }
                val extraHosts = _hosts.size - shown.size
                "cookie capture: accepted · ${_hosts.size} host(s): $hostsText" +
                    (if (extraHosts > 0) " · +$extraHosts more host(s)" else "")
            }
            Verdict.REJECTED -> "cookie capture: rejected ($rejectReason)"
        }
    }

    private fun setAccepted() {
        if (verdict != Verdict.UNKNOWN) return
        verdict = Verdict.ACCEPTED
        onStateChanged?.invoke()
    }

    private fun setRejected(reason: String) {
        if (verdict != Verdict.UNKNOWN) return
        verdict = Verdict.REJECTED
        rejectReason = reason
        onStateChanged?.invoke()
    }
}
