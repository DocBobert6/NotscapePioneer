package net.nspioneer.browser.engine

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * Crash-safe trace persistence (alpha.26).
 *
 * While a trace is running, every status line is appended to
 * this file (in the app's private files dir) and flushed per
 * line. A crash leaves the file on disk; Stop trace closes the
 * writer and deletes the file, so a normally-stopped trace
 * leaves nothing to recover. Recovery reads the file back on
 * the next launch (StatusPanel.recoverCrashedTrace).
 *
 * Crash-safety: each line is written as one write() + flush()
 * of "line\n". A crash can tear at most the final line — and a
 * trailing line with no terminating newline is dropped on read
 * (recoveredLines), so a torn write can never corrupt or
 * mislabel the lines before it. All I/O is runCatching: the
 * trace file must never crash the app.
 *
 * Pure java.io only (no Android classes), so the whole class
 * runs on the plain JVM in unit tests.
 */
class TraceLogFile(private val file: File) {

    private var writer: BufferedWriter? = null

    /** Open a fresh trace file, discarding any previous content. */
    @Synchronized
    fun begin() {
        runCatching {
            file.parentFile?.mkdirs()
            writer?.close()
            writer = BufferedWriter(
                OutputStreamWriter(FileOutputStream(file, false), Charsets.UTF_8)
            )
        }
    }

    /** Append one line, flushed immediately. No-op if not begun. */
    @Synchronized
    fun append(line: String) {
        val w = writer ?: return
        runCatching {
            w.write(line)
            w.newLine()
            w.flush()
        }
    }

    /** Close the writer and delete the file (trace stopped normally). */
    @Synchronized
    fun finish() {
        runCatching { writer?.close() }
        writer = null
        runCatching { file.delete() }
    }

    /** Close the writer (if any) and delete the file without reading it. */
    @Synchronized
    fun discard() {
        runCatching { writer?.close() }
        writer = null
        runCatching { file.delete() }
    }

    /**
     * Lines of an unterminated trace, or null when there is none
     * (no file, or an empty file). A trailing partial line — the
     * file's last bytes with no terminating newline, i.e. a write
     * torn by the crash — is dropped.
     */
    @Synchronized
    fun readRecovered(): List<String>? = runCatching {
        if (!file.exists()) return null
        val text = file.readText(Charsets.UTF_8)
        if (text.isEmpty()) return null
        recoveredLines(text)
    }.getOrNull()

    companion object {
        /**
         * Split persisted trace text into lines, dropping a
         * trailing partial line (no terminating newline). Pure,
         * so unit tests can drive it directly.
         */
        fun recoveredLines(text: String): List<String> {
            if (text.isEmpty()) return emptyList()
            // split() yields one trailing "" for a final newline
            // (dropped) or the torn partial line (dropped) —
            // either way the last element is never a complete
            // line, so dropLast(1) is correct in both cases.
            return text.split("\n").dropLast(1)
        }
    }
}
