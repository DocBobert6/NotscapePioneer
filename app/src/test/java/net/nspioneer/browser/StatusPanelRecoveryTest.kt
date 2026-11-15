package net.nspioneer.browser

import net.nspioneer.browser.engine.StatusPanel
import net.nspioneer.browser.engine.TraceLogFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * P-A4 (alpha.38): the crashed-trace recovery was split so its
 * disk read can run off the main thread — readRecoveredTraceLines
 * (I/O) + recoverCrashedTraceLines (main-thread state). These
 * tests drive the split pair through a real StatusPanel under
 * Robolectric, and guard the synchronous recoverCrashedTrace
 * path that now delegates to them.
 *
 * Same shape as PermissionDelegateTest: Robolectric, runtime
 * SDK 35, a real StatusPanel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StatusPanelRecoveryTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val traceFile get() = File(context.filesDir, "trace-live.log")

    @After
    fun cleanup() {
        runCatching { traceFile.delete() }
    }

    /** Simulate a crash mid-trace: begin + append, never finish. */
    private fun leaveCrashedTrace(lines: List<String>) {
        val log = TraceLogFile(traceFile)
        log.begin()
        lines.forEach { log.append(it) }
        // No finish() — a crash leaves the file behind.
    }

    @Test
    fun splitPairRecoversLinesAndMarksCrash() {
        leaveCrashedTrace(listOf("line one", "line two"))
        val panel = StatusPanel(context)
        val lines = panel.readRecoveredTraceLines()
        assertEquals(listOf("line one", "line two"), lines)
        assertTrue(panel.recoverCrashedTraceLines(lines, "2026-11-15 00:00:00"))
        assertEquals(
            listOf(
                "line one", "line two",
                "--- trace interrupted by crash 2026-11-15 00:00:00 ---"
            ),
            panel.recoveredTraceLines.toList()
        )
        assertEquals(
            "— recovered trace (crash 2026-11-15 00:00:00) —",
            panel.recoveredTraceHeader.value
        )
    }

    @Test
    fun splitPairWithNoFileRecoversNothing() {
        assertFalse(traceFile.exists())
        val panel = StatusPanel(context)
        assertNull(panel.readRecoveredTraceLines())
        assertFalse(panel.recoverCrashedTraceLines(null, null))
        assertTrue(panel.recoveredTraceLines.isEmpty())
        assertNull(panel.recoveredTraceHeader.value)
    }

    @Test
    fun noCrashStampMarksEndedWhileTracing() {
        leaveCrashedTrace(listOf("only line"))
        val panel = StatusPanel(context)
        assertTrue(panel.recoverCrashedTraceLines(panel.readRecoveredTraceLines(), null))
        assertEquals(
            "--- trace interrupted — app ended while tracing (no crash recorded) ---",
            panel.recoveredTraceLines.last()
        )
        assertEquals(
            "— recovered trace (app ended while tracing) —",
            panel.recoveredTraceHeader.value
        )
    }

    @Test
    fun synchronousPathStillRecovers() {
        leaveCrashedTrace(listOf("sync line"))
        val panel = StatusPanel(context)
        assertTrue(panel.recoverCrashedTrace("2026-11-15 00:00:00"))
        assertEquals(
            listOf(
                "sync line",
                "--- trace interrupted by crash 2026-11-15 00:00:00 ---"
            ),
            panel.recoveredTraceLines.toList()
        )
    }
}
