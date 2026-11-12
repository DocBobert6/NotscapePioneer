package net.nspioneer.browser.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notscape Pioneer — Phase 2 status panel.
 *
 * Carried from the Phase 1 spike (MainActivity, commits 4609fbb5 /
 * fb3180f5): the on-screen status + media-probe lines, the cold-start /
 * PSS / APK-size stats line, and the exact text the spike's "Copy
 * status" button produced. Robert uses Copy status to send results,
 * so the format here must not drift: header line, "— status —",
 * status lines, "— media probe —", media lines (or the empty note).
 *
 * Logcat tag stays "PioneerSpike" so a bug report reads the same way
 * the spike's did.
 */
class StatusPanel(context: Context) {

    companion object {
        const val TAG = "PioneerSpike"

        /** GeckoView version in app/build.gradle.kts (stable pin). */
        const val GECKOVIEW_VERSION = "159.0.20261102144711"
    }

    private val appContext = context.applicationContext
    private val bootMs = SystemClock.elapsedRealtime()
    private var coldStartMs: Long? = null

    /** Status lines for the on-screen panel (newest last, capped at 60). */
    val statusLines = mutableStateListOf<String>()

    /** Media URLs reported by the probe extension (newest first, capped at 20). */
    val mediaLines = mutableStateListOf<String>()

    /**
     * Diagnostics trace window (alpha.16): while tracing, the status
     * log is bracketed by "--- trace start … ---" / "--- trace end
     * … ---" marker lines, so Copy status shows exactly what
     * happened between them. The markers are ordinary status lines
     * (same log, same cap) — no separate store, no export file.
     */
    val isTracing = mutableStateOf(false)
    private var traceStartStamp: String? = null

    /**
     * Crash-safe trace persistence (alpha.26): while a trace is
     * running, every status line is also appended (flushed) to
     * trace-live.log, so a crash leaves the trace on disk.
     * traceLock guards the file against stopTrace's finish()
     * racing an in-flight append; synchronized is re-entrant,
     * so startTrace/stopTrace may call status() while holding it.
     */
    private val traceLock = Any()
    private val traceFile = TraceLogFile(File(appContext.filesDir, "trace-live.log"))

    /**
     * A trace recovered from a previous run that ended while
     * tracing (crash, force-stop, OS kill). Kept apart from the
     * live statusLines: the live log keeps its 60-line cap and
     * its behaviour, and the recovered trace cannot be evicted
     * by it. Cleared by clearRecoveredTrace() from Diagnostics.
     */
    val recoveredTraceLines = mutableStateListOf<String>()
    val recoveredTraceHeader = mutableStateOf<String?>(null)

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    init {
        // Version name read from the installed package, so this line
        // tracks the coordinator's version bump with no code change.
        val version = runCatching {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            info.versionName ?: "?"
        }.getOrDefault("?")
        status("Notscape Pioneer $version — GeckoView $GECKOVIEW_VERSION (stable)")
    }

    fun status(line: String) {
        Log.i(TAG, line)
        statusLines.add(line)
        while (statusLines.size > 60) statusLines.removeAt(0)
        // alpha.26: while a trace is running, the line is also
        // appended (flushed) to the trace file, so a crash leaves
        // the trace on disk.
        synchronized(traceLock) {
            if (isTracing.value) traceFile.append(line)
        }
    }

    /** Open a trace window: marker line first, then every status line
     *  until stopTrace() is part of the trace. No-op if already open. */
    fun startTrace() {
        if (isTracing.value) return
        val stamp = stamp()
        traceStartStamp = stamp
        synchronized(traceLock) {
            // Fresh file first, so the start marker below is the
            // file's first line too.
            traceFile.begin()
            isTracing.value = true
            status("--- trace start $stamp ---")
        }
    }

    /** Close the trace window with its end marker. No-op if none open. */
    fun stopTrace() {
        if (!isTracing.value) return
        synchronized(traceLock) {
            status("--- trace end ${stamp()} ---")
            // End marker flushed, then the file is gone: a
            // normally-stopped trace leaves nothing to recover.
            traceFile.finish()
            isTracing.value = false
            traceStartStamp = null
        }
    }

    /**
     * Recover a trace left running when the app ended (alpha.26).
     * crashStamp is the crash time from crash-last.log when a
     * crash was recorded, null otherwise (force-stop, OS kill —
     * still recovered, marked as ended-while-tracing, so the
     * recovery is never mislabelled as a crash). Returns true
     * when a trace was recovered. A stale or empty file is
     * discarded, so it is not re-read on every launch.
     */
    fun recoverCrashedTrace(crashStamp: String?): Boolean {
        val lines = traceFile.readRecovered()
        if (lines.isNullOrEmpty()) {
            traceFile.discard()
            return false
        }
        recoveredTraceLines.clear()
        recoveredTraceLines.addAll(lines)
        recoveredTraceLines.add(
            if (crashStamp != null) "--- trace interrupted by crash $crashStamp ---"
            else "--- trace interrupted — app ended while tracing (no crash recorded) ---"
        )
        recoveredTraceHeader.value =
            if (crashStamp != null) "— recovered trace (crash $crashStamp) —"
            else "— recovered trace (app ended while tracing) —"
        return true
    }

    /** Dismiss a recovered trace (Diagnostics): clears the panel
     *  and deletes the trace file, so it is not recovered again. */
    fun clearRecoveredTrace() {
        recoveredTraceLines.clear()
        recoveredTraceHeader.value = null
        traceFile.discard()
    }

    fun noteMedia(tabNumber: Int, kind: String, url: String) {
        if (url.isBlank()) return
        val line = "tab $tabNumber [$kind] ${url.take(120)}"
        if (!mediaLines.contains(line)) {
            mediaLines.add(0, line)
            while (mediaLines.size > 20) mediaLines.removeAt(mediaLines.lastIndex)
        }
        Log.i(TAG, "MEDIA $line")
    }

    /** First composite across any tab: the cold-start figure. Once only. */
    fun noteFirstComposite() {
        if (coldStartMs == null) {
            coldStartMs = SystemClock.elapsedRealtime() - bootMs
            status("cold start: first composite in ${coldStartMs}ms")
        }
    }

    fun refreshStats() {
        val pssKb = try {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getProcessMemoryInfo(intArrayOf(Process.myPid()))[0].totalPss
        } catch (e: Throwable) {
            -1
        }
        val apkMb = try {
            File(appContext.applicationInfo.sourceDir).length() / 1_048_576.0
        } catch (e: Throwable) {
            -1.0
        }
        val stats = "stats: APK %.1f MB | PSS %d MB | cold start %s".format(
            apkMb, pssKb / 1024, coldStartMs?.let { "${it}ms" } ?: "—"
        )
        statusLines.removeAll { it.startsWith("stats:") }
        statusLines.add(stats)
        // alpha.26: refreshStats writes to the log directly (not
        // via status()), so the trace file gets the stats line
        // here — the file records every line that enters the
        // status log while tracing, exactly as Copy status shows.
        synchronized(traceLock) {
            if (isTracing.value) traceFile.append(stats)
        }
    }

    /**
     * The exact text the spike's Copy status button produced
     * (alpha.2, commit fb3180f5): header line, status lines, media
     * probe lines. As the spike did, the confirmation line is added
     * to the panel AFTER the text is built, so it shows on the next
     * copy rather than in this one.
     */
    fun copyStatusText(castLine: String): String {
        val lines = mutableListOf<String>()
        lines += "SPIKE STATUS — $castLine"
        lines += "— status —"
        lines += statusLines
        // A trace still open at copy time: say so in the copied text
        // (the start marker is already among the status lines above).
        // Added to the copied text only — never to the panel itself.
        if (isTracing.value) {
            lines += "--- trace running since ${traceStartStamp ?: "?"} ---"
        }
        // A trace recovered from a previous run that ended while
        // tracing (alpha.26): its own section, never merged into
        // the live log. Absent when there is no recovered trace,
        // so the copied text is byte-identical to before when no
        // crash happened.
        if (recoveredTraceLines.isNotEmpty()) {
            lines += recoveredTraceHeader.value ?: "— recovered trace —"
            lines += recoveredTraceLines
        }
        lines += "— media probe —"
        if (mediaLines.isEmpty()) {
            lines += "(no media URLs reported yet — load a video page)"
        } else {
            lines += mediaLines
        }
        val text = lines.joinToString("\n")
        status("status copied to clipboard (${lines.size} lines)")
        return text
    }
}
