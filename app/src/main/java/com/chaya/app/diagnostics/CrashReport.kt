package com.chaya.app.diagnostics

import java.io.File

/**
 * A persisted uncaught-exception report: app/device context, the full
 * stack trace, and the last event-log entries (2.3) preceding the crash.
 * Files live under filesDir/crash_reports/ as plain text the user can
 * share manually — nothing is ever uploaded automatically.
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
        appendLine("Chaya crash report — $fileName")
        appendLine("App: $appVersion · API $apiLevel · $deviceModel")
        appendLine("Thread: $threadName · $exceptionName")
        appendLine()
        appendLine("--- stack trace ---")
        appendLine(stackTrace)
        appendLine("--- recent events ---")
        if (recentEvents.isEmpty()) appendLine("(no events recorded)") else recentEvents.forEach { appendLine(it) }
    }

    companion object {
        const val DIR_NAME = "crash_reports"
        const val MAX_BODY_CHARS = 64 * 1024

        /** Parses a report file written by [CrashReporter]; null when unreadable. */
        fun read(file: File): CrashReport? {
            return try {
                val lines = file.readText().take(MAX_BODY_CHARS).lines()
                var appVersion = "?"; var apiLevel = 0; var deviceModel = "?"
                var threadName = "?"; var exceptionName = "?"
                var timestamp = file.lastModified()
                val trace = StringBuilder()
                val events = mutableListOf<String>()
                var section = 0 // 0 header, 1 trace, 2 events
                for (line in lines) {
                    when {
                        line.startsWith("Chaya crash report — ") -> Unit
                        line.startsWith("App: ") -> {
                            val parts = line.removePrefix("App: ").split(" · ")
                            appVersion = parts.getOrElse(0) { "?" }
                            apiLevel = parts.getOrElse(1) { "API ?" }.removePrefix("API ").toIntOrNull() ?: 0
                            deviceModel = parts.getOrElse(2) { "?" }
                        }
                        line.startsWith("Thread: ") -> {
                            val rest = line.removePrefix("Thread: ")
                            threadName = rest.substringBefore(" · ")
                            exceptionName = rest.substringAfter(" · ", "?")
                        }
                        line == "--- stack trace ---" -> section = 1
                        line == "--- recent events ---" -> section = 2
                        section == 1 -> trace.appendLine(line)
                        section == 2 -> if (line != "(no events recorded)") events.add(line)
                    }
                }
                CrashReport(
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
            } catch (_: Exception) {
                null
            }
        }
    }
}
