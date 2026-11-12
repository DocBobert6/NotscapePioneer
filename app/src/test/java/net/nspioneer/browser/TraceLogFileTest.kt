package net.nspioneer.browser

import net.nspioneer.browser.engine.TraceLogFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Alpha.26 tests for crash-safe trace persistence: while a
 * trace runs, its lines are appended (flushed) to
 * trace-live.log; a crash leaves the file behind and the next
 * launch recovers it. A normally-stopped trace deletes the
 * file, so there is nothing to recover. A write torn by the
 * crash can only lose the final partial line.
 * Plain JVM, no Android classes — TraceLogFile is java.io only.
 */
class TraceLogFileTest {

    private fun tempDir(): File =
        Files.createTempDirectory("trace-test").toFile()

    @Test fun crashLeavesLinesBehind() {
        val dir = tempDir()
        try {
            val log = TraceLogFile(File(dir, "trace-live.log"))
            log.begin()
            log.append("--- trace start 2026-11-12 18:15:00 ---")
            log.append("tab 1: loading about:blank")
            log.append("tab 1: load OK")
            // No finish(): the app crashed mid-trace.
            val recovered = TraceLogFile(File(dir, "trace-live.log")).readRecovered()
            assertEquals(
                listOf(
                    "--- trace start 2026-11-12 18:15:00 ---",
                    "tab 1: loading about:blank",
                    "tab 1: load OK"
                ),
                recovered
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun stopDeletesTheFile() {
        val dir = tempDir()
        try {
            val file = File(dir, "trace-live.log")
            val log = TraceLogFile(file)
            log.begin()
            log.append("--- trace start 2026-11-12 18:15:00 ---")
            log.finish()
            assertFalse(file.exists())
            assertNull(TraceLogFile(file).readRecovered())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun tornFinalLineIsDropped() {
        val dir = tempDir()
        try {
            val file = File(dir, "trace-live.log")
            // A write torn by the crash: last line has no newline.
            file.writeText(
                "--- trace start 2026-11-12 18:15:00 ---\ntab 1: load",
                Charsets.UTF_8
            )
            assertEquals(
                listOf("--- trace start 2026-11-12 18:15:00 ---"),
                TraceLogFile(file).readRecovered()
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun noFileMeansNoRecovery() {
        val dir = tempDir()
        try {
            assertNull(TraceLogFile(File(dir, "trace-live.log")).readRecovered())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun emptyFileMeansNoRecovery() {
        val dir = tempDir()
        try {
            val file = File(dir, "trace-live.log")
            file.writeText("", Charsets.UTF_8)
            assertNull(TraceLogFile(file).readRecovered())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun recoveredLinesFraming() {
        // Complete text: every line survives.
        assertEquals(
            listOf("a", "b"),
            TraceLogFile.recoveredLines("a\nb\n")
        )
        // No trailing newline: the last line is partial, dropped.
        assertEquals(
            listOf("a"),
            TraceLogFile.recoveredLines("a\nb")
        )
        assertEquals(emptyList<String>(), TraceLogFile.recoveredLines(""))
    }
}
