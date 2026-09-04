package com.chaya.app.diagnostics

import android.content.Context
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Bounded on-device diagnostics log: an in-memory ring buffer (latest 500)
 * mirrored to rotating newline-delimited JSON files (1MB each, keep 2).
 *
 * Held on [com.chaya.app.ChayaApplication] like downloadManager/database.
 * Writes go through a single IO coroutine — record() never blocks callers.
 * URLs are scrubbed at the [ChayaEvent] boundary, so exports shared by a
 * user for debugging cannot leak session tokens.
 */
class EventLog(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val buffer = ArrayDeque<ChayaEvent>(MAX_ENTRIES + 1)
    private val logDir = File(context.filesDir, "event_log").also { it.mkdirs() }

    /** Appends an event to the buffer and the on-disk log. Never suspends. */
    fun record(event: ChayaEvent) {
        scope.launch {
            mutex.withLock {
                if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
                buffer.addLast(event)
            }
            appendToFile(event)
        }
    }

    /** Test-only synchronous variant: records inline so Robolectric tests stay deterministic. */
    suspend fun recordSync(event: ChayaEvent) {
        mutex.withLock {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(event)
        }
        appendToFile(event)
    }

    /** Latest events, newest last. Suspends briefly for the lock. */
    suspend fun snapshot(): List<ChayaEvent> = mutex.withLock { buffer.toList() }

    /** Chronological human-readable export for the share sheet. */
    suspend fun exportText(): String = mutex.withLock {
        buildString {
            for (event in buffer) {
                appendLine(format(event))
            }
        }
    }

    private fun format(event: ChayaEvent): String = when (event) {
        is ChayaEvent.MediaDetected ->
            "[${event.timestamp}] detected ${event.mimeType ?: "unknown-type"} " +
                "via ${event.source}: ${event.url}"
        is ChayaEvent.DownloadStateChanged ->
            "[${event.timestamp}] task ${event.taskId}: ${event.from} -> ${event.to}"
        is ChayaEvent.DownloadFailed ->
            "[${event.timestamp}] task ${event.taskId} failed " +
                "(${event.errorKind}, retryable=${event.retryable})"
        is ChayaEvent.PageLoaded -> "[${event.timestamp}] page: ${event.url}"
        is ChayaEvent.CaughtException ->
            "[${event.timestamp}] ${event.tag}: ${event.message ?: "no message"}"
    }

    private fun appendToFile(event: ChayaEvent) {
        try {
            val file = currentFile()
            val json = JSONObject().apply {
                put("ts", event.timestamp)
                put("type", event::class.simpleName)
                put("text", format(event))
            }
            file.appendText(json.toString() + "\n")
            rotateIfNeeded(file)
        } catch (_: Exception) {
            // Diagnostics must never crash the app it observes.
        }
    }

    private fun currentFile(): File = File(logDir, "$LOG_PREFIX-0.log")

    private fun rotateIfNeeded(file: File) {
        if (file.length() < MAX_FILE_BYTES) return
        File(logDir, "$LOG_PREFIX-1.log").delete()
        file.renameTo(File(logDir, "$LOG_PREFIX-1.log"))
    }

    companion object {
        const val MAX_ENTRIES = 500
        const val MAX_FILE_BYTES = 1024 * 1024L
        private const val LOG_PREFIX = "events"
    }
}
