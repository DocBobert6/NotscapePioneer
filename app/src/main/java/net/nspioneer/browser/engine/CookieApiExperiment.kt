package net.nspioneer.browser.engine

import net.nspioneer.browser.data.PioneerStore

/**
 * Alpha.63 — the cookies-API experiment.
 *
 * Quetta attaches the page's cookies to its downloads;
 * Pioneer cannot — the download bundle's cookie is always
 * empty. The onBeforeSendHeaders workaround (alpha.59) is
 * dead: the listener never fires in GeckoView 160
 * (alpha.60 probe, device-proven). The one remaining
 * candidate is the browser's own cookies API, which IS
 * present in GeckoView 160 — verified in the shipped
 * omni.ja (schema `cookies.json` + `ext-cookies.js`,
 * permission "cookies", functions get / getAll / set /
 * remove / getAllCookieStores, no allowedContexts
 * restriction, no GeckoView gating). `getAll` returns
 * HttpOnly cookies with values, which is exactly what a
 * download job needs.
 *
 * This class is the experiment, not a feature: it records
 * whether `browser.cookies.getAll` returns the page's
 * cookies, and in WHICH store — the default store or a
 * per-session store (the open question: `getAll` defaults
 * to userContextId 0, and Pioneer's tabs may use
 * per-session context IDs). Nothing else is built on it.
 *
 * SECURITY — names only, NEVER values: the probe reports
 * cookie NAMES (e.g. "SID, HSID, SSID") and per-store
 * counts. No cookie value is logged, stored, persisted,
 * or sent anywhere in this alpha. The verdict is
 * first-wins and conservative: it is ACCEPTED only when
 * some store returns at least one cookie name for the
 * probed page host; a bounded wait with probe runs and
 * zero names is REJECTED. A rejection is a successful
 * experiment, not a failure — it confirms the ceiling.
 *
 * The expected outcome is unknown — that is the point of
 * the experiment. Turning the flag off stops new probes
 * and the Diagnostics line reads "off".
 */
class CookieApiExperiment {

    enum class Verdict { UNKNOWN, ACCEPTED, REJECTED }

    /** One store's probe result: its id and the cookie
     *  NAMES seen there (never values). */
    data class StoreResult(val storeId: String, val names: List<String>)

    companion object {
        /** Store flag for the experiment (default OFF). */
        fun flagKey(): String = "probeCookiesApi"

        fun isEnabled(store: PioneerStore): Boolean =
            store.setting(flagKey(), "false") == "true"

        /** How many stores the ACCEPTED line shows before
         *  "+N more" — the Diagnostics line must stay one
         *  readable line. */
        const val MAX_STORES_SHOWN = 5

        /** How many cookie names per store the ACCEPTED line
         *  shows before "+N more". */
        const val MAX_NAMES_SHOWN = 8
    }

    var verdict: Verdict = Verdict.UNKNOWN
        private set

    /** Why a REJECTED verdict was recorded, for the status log. */
    var rejectReason: String? = null
        private set

    /** Probe runs completed while the flag was on (a run is
     *  one `getAll` round across the stores for a page). */
    var probesRun: Int = 0
        private set

    /** Host of the most recent probe run, so a rejection
     *  names the host it was measured on (a 0 on a host
     *  that sets no cookies is not a real rejection). */
    var lastHost: String = ""
        private set

    /** Stores that returned cookie names, with the NAMES
     *  seen there (never values). Grows after ACCEPTED too
     *  — it is evidence, not verdict. */
    private val _stores = linkedMapOf<String, MutableList<String>>()
    val stores: Map<String, List<String>> get() = _stores

    /** Fired when the verdict changes, so the engine can refresh
     *  its Diagnostics state text (same shape as
     *  CookieCaptureExperiment.onStateChanged). */
    var onStateChanged: (() -> Unit)? = null

    /**
     * The background script has no `browser.cookies` at
     * all in its context — a definitive answer, so this
     * rejects at once: the API route does not exist here.
     */
    fun noteApiAbsent(host: String) {
        probesRun++
        lastHost = host
        setRejected("browser.cookies undefined")
    }

    /**
     * A `getAll` call threw (e.g. a missing host
     * permission) — a definitive answer, so this rejects
     * at once: the API is present but unusable.
     */
    fun noteApiError(host: String, errorName: String) {
        probesRun++
        lastHost = host
        setRejected("cookies.getAll threw $errorName")
    }

    /**
     * One probe run for [host] across [results] (one entry
     * per store queried). ACCEPTED on the first run where
     * any store returns a name; later runs only merge
     * names. An all-zero run records the attempt and stays
     * UNKNOWN until the bounded wait ([noteTimeout]) — a
     * single early run can precede the cookies being set.
     */
    fun noteProbe(host: String, results: List<StoreResult>) {
        probesRun++
        lastHost = host
        var anyNames = false
        for ((storeId, names) in results) {
            if (names.isNotEmpty()) anyNames = true
            if (storeId.isNotEmpty()) {
                val known = _stores.getOrPut(storeId) { mutableListOf() }
                for (name in names) {
                    if (name.isNotEmpty() && name !in known) known.add(name)
                }
            }
        }
        if (anyNames) setAccepted()
    }

    /**
     * The bounded wait after a page load expired with no
     * cookie name seen. Rejects only when a probe actually
     * ran — with zero runs there is no evidence either way,
     * so the verdict stays UNKNOWN and the engine re-arms
     * on the next page load.
     */
    fun noteTimeout() {
        if (verdict != Verdict.UNKNOWN) return
        if (probesRun <= 0) return
        setRejected("0 cookies on $lastHost after $probesRun probe(s)")
    }

    /** "cookies API: off/unknown/accepted/rejected" — for
     *  Diagnostics. "off" when the flag is off (no attempt
     *  was made); "unknown" until the first probe. The
     *  ACCEPTED line names the host and, per store, the
     *  cookie names seen (names only — never values). */
    fun cookiesApiStateText(flagOn: Boolean): String {
        if (!flagOn) return "cookies API: off"
        return when (verdict) {
            Verdict.UNKNOWN -> "cookies API: unknown"
            Verdict.ACCEPTED -> {
                val shown = _stores.entries.take(MAX_STORES_SHOWN)
                val storesText = shown.joinToString("; ") { (storeId, names) ->
                    val countText =
                        if (names.size == 1) "1 name" else "${names.size} names"
                    if (names.isEmpty()) {
                        "$storeId: $countText"
                    } else {
                        val shownNames = names.take(MAX_NAMES_SHOWN)
                        val extra = names.size - shownNames.size
                        "$storeId: $countText (${shownNames.joinToString(", ")}" +
                            (if (extra > 0) ", +$extra more" else "") + ")"
                    }
                }
                val extraStores = _stores.size - shown.size
                "cookies API: accepted · $lastHost · $storesText" +
                    (if (extraStores > 0) " · +$extraStores more store(s)" else "")
            }
            Verdict.REJECTED -> "cookies API: rejected ($rejectReason)"
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
