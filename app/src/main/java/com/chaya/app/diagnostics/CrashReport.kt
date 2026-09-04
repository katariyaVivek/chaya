package com.chaya.app.diagnostics

import java.io.File

/**
 * A persisted uncaught-exception report: app/device context, the full
 * stack trace, and the last event-log entries (2.3) preceding the crash.
 * Files live under filesDir/crash_reports/ as plain text the user can
 * share manually - nothing is ever uploaded automatically.
 *
 * Field separators are ASCII (" | ") so the writer and the parser below
 * can never disagree on encoding; display strings may use richer glyphs
 * but persisted lines stay plain.
 */
data class CrashReport(
    val fileName: String,
    val timestamp: Long,
    val threadName: String,
    val exceptionName: String,
    val stackTrace: String,
    val appVersion: String,
    val apiLevel: Int,
    val deviceModel: String,
    val recentEvents: List<String>,
) {
    /** Human-readable rendering shared by the viewer and the share sheet. */
    fun toText(): String = buildString {
        appendLine("$HEADER_PREFIX$HEADER_SEPARATOR$fileName")
        appendLine("App:$FIELD_SEPARATOR$appVersion$FIELD_SEPARATOR$apiLevel$FIELD_SEPARATOR$deviceModel")
        appendLine("Thread:$FIELD_SEPARATOR$threadName$FIELD_SEPARATOR$exceptionName")
        appendLine()
        appendLine(TRACE_MARKER)
        appendLine(stackTrace)
        appendLine(EVENTS_MARKER)
        if (recentEvents.isEmpty()) appendLine(EMPTY_EVENTS_LINE) else recentEvents.forEach { appendLine(it) }
    }

    companion object {
        const val DIR_NAME = "crash_reports"
        const val MAX_BODY_CHARS = 64 * 1024

        /** Header markers shared by the writer ([CrashReporter]) and this parser. */
        const val HEADER_PREFIX = "Chaya crash report"
        const val HEADER_SEPARATOR = " - "
        const val FIELD_SEPARATOR = " | "
        const val TRACE_MARKER = "--- stack trace ---"
        const val EVENTS_MARKER = "--- recent events ---"
        const val EMPTY_EVENTS_LINE = "(no events recorded)"

        /** Parses a report file written by [CrashReporter]; throws on unreadable input. */
        fun read(file: File): CrashReport {
            val lines = file.readText().take(MAX_BODY_CHARS).lines()
            var appVersion = "?"; var apiLevel = 0; var deviceModel = "?"
            var threadName = "?"; var exceptionName = "?"
            var timestamp = file.lastModified()
            val trace = StringBuilder()
            val events = mutableListOf<String>()
            var section = 0 // 0 header, 1 trace, 2 events
            for (line in lines) {
                when {
                    line.startsWith(HEADER_PREFIX) -> Unit
                    line.startsWith("App:") -> {
                        val parts = line.removePrefix("App:").split(FIELD_SEPARATOR)
                        appVersion = parts.getOrElse(1) { "?" }.trim()
                        apiLevel = parts.getOrElse(2) { "?" }.trim().toIntOrNull() ?: 0
                        deviceModel = parts.getOrElse(3) { "?" }.trim()
                    }
                    line.startsWith("Thread:") -> {
                        val rest = line.removePrefix("Thread:").split(FIELD_SEPARATOR)
                        threadName = rest.getOrElse(1) { "?" }.trim()
                        exceptionName = rest.getOrElse(2) { "?" }.trim()
                    }
                    line == TRACE_MARKER -> section = 1
                    line == EVENTS_MARKER -> section = 2
                    section == 1 -> trace.appendLine(line)
                    section == 2 -> if (line != EMPTY_EVENTS_LINE) events.add(line)
                }
            }
            return CrashReport(
                fileName = file.name,
                timestamp = timestamp,
                threadName = threadName,
                exceptionName = exceptionName,
                stackTrace = trace.toString().trimEnd(),
                appVersion = appVersion,
                apiLevel = apiLevel,
                deviceModel = deviceModel,
                recentEvents = events,
            )
        }
    }
}
