package net.nspioneer.browser.engine

import net.nspioneer.browser.data.PioneerStore

/**
 * Alpha.52 — the MAIN-world experiment (gap plan row A-2).
 *
 * Pioneer's content scripts run in the ISOLATED world: the
 * probe sees the DOM but cannot patch the page's own XHR/fetch,
 * which is how Quetta's detection sees MSE traffic at the
 * network layer. This class is the experiment, not a feature:
 * it records whether GeckoView accepts a content script
 * registered in the page's own (MAIN) world, and nothing else
 * is built on it.
 *
 * The experiment extension (pioneer-main-world: own manifest
 * asking for `"world": "MAIN"` + main-world.js) is installed
 * ONLY when the [flagKey] store flag is on (default OFF). The
 * script reports by writing to the page itself — a window
 * flag + a CustomEvent — and the ISOLATED-world probe relays
 * the result as one sentinel native message
 * (`probe-main-world-ok`) per page load, carrying only whether
 * the script found itself in the MAIN world. The verdict is
 * first-wins and conservative: it is ACCEPTED only when the
 * sentinel proves MAIN world; anything else (install failure,
 * an isolated-world sentinel, no sentinel within 10 s of a
 * page load) is REJECTED.
 *
 * Alpha.66 re-test: the alpha.52 verdict was a FALSE
 * NEGATIVE, never a proven ceiling. The old script reported
 * via the extension messaging API, which does not exist in
 * MAIN world, so its "rejection" proved nothing about
 * whether MAIN-world code runs. The expected outcome is
 * unknown — that is the point of the re-test. A rejection
 * now would confirm the ceiling for real; an acceptance
 * opens page-context interception as a later, separately
 * approved build, and nothing is built on it here. A
 * rejection is a successful experiment, not a failure.
 * Turning the flag off stops new attempts and the Diagnostics
 * line reads "off"; an already-installed experiment script
 * stays installed for the session (no uninstall is wired —
 * deliberate, out of scope).
 */
class MainWorldExperiment {

    enum class Verdict { UNKNOWN, ACCEPTED, REJECTED }

    companion object {
        /** Store flag for the experiment (default OFF). */
        fun flagKey(): String = "probeMainWorld"

        fun isEnabled(store: PioneerStore): Boolean =
            store.setting(flagKey(), "false") == "true"
    }

    var verdict: Verdict = Verdict.UNKNOWN
        private set

    /** Why a REJECTED verdict was recorded, for the status log. */
    var rejectReason: String? = null
        private set

    /** Fired when the verdict changes, so the engine can refresh
     *  its Diagnostics state text (same shape as
     *  BlockingObserver.onHeadersStateChanged). */
    var onStateChanged: (() -> Unit)? = null

    fun noteInstallFailed(reason: String?) {
        setRejected("install failed: ${reason ?: "unknown error"}")
    }

    /**
     * One sentinel arrived. ACCEPTED only when the script proved
     * it runs in the MAIN world; an isolated-world sentinel is
     * a REJECTION — the manifest's "world" was ignored.
     */
    fun noteSentinel(mainWorld: Boolean) {
        if (mainWorld) setAccepted() else setRejected("sentinel: isolated world")
    }

    /** No sentinel arrived within the bounded wait after a page
     *  load — the script never ran on that load. */
    fun noteTimeout() {
        setRejected("no sentinel 10 s after page load")
    }

    /** "probe MAIN world: off/unknown/accepted/rejected" — for
     *  Diagnostics. "off" when the flag is off (no attempt was
     *  made); "unknown" until the first report. */
    fun mainWorldStateText(flagOn: Boolean): String {
        if (!flagOn) return "probe MAIN world: off"
        return when (verdict) {
            Verdict.UNKNOWN -> "probe MAIN world: unknown"
            Verdict.ACCEPTED -> "probe MAIN world: accepted"
            Verdict.REJECTED -> "probe MAIN world: rejected"
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
