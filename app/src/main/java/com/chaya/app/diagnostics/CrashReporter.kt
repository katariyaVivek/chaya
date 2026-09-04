package com.chaya.app.diagnostics

import android.content.Context
import android.os.Build
import com.chaya.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.runBlocking

/**
 * Self-hosted crash capture (Phase 2.1): installs the default uncaught
 * exception handler, writes a [CrashReport] file including the last 50
 * event-log entries, then chains to the previous handler so the process
 * still crashes/logs normally. Crashes are never swallowed silently and
 * never uploaded — the user shares them manually from DiagnosticsScreen.
 *
 * Installed once from ChayaApplication.onCreate().
 */
object CrashReporter {
    @Volatile
    private var installed = false

    /** Installs the handler; safe to call repeatedly (first call wins). */
    fun install(context: Context, eventLog: EventLog) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
        }
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                writeReport(appContext, eventLog, thread, throwable)
            }
            // Chain: the process still crashes and logcat still gets the trace.
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            }
        }
    }

    /** Test-only reset so Robolectric tests can reinstall per case. */
    fun resetForTests() {
        installed = false
    }

    /** Writes one report file; returns it, or null when anything fails. */
    fun writeReport(
        context: Context,
        eventLog: EventLog,
        thread: Thread,
        throwable: Throwable,
        // Injected in tests so the file under assertion is unambiguous even
        // when tests share a filesDir; production passes nothing (timestamped).
        fileName: String? = null,
    ): File? {
        return try {
            val dir = File(context.filesDir, CrashReport.DIR_NAME).also { it.mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val file = File(dir, fileName ?: "crash-$stamp.log")
            val traceWriter = StringWriter()
            throwable.printStackTrace(PrintWriter(traceWriter))
            val recentEvents = runCatching {
                runBlocking { eventLog.snapshot() }
            }.getOrElse { emptyList() }.takeLast(50).map { formatEvent(it) }
            val report = CrashReport(
                fileName = file.name,
                timestamp = System.currentTimeMillis(),
                threadName = thread.name,
                exceptionName = throwable::class.java.name,
                stackTrace = traceWriter.toString().take(CrashReport.MAX_BODY_CHARS / 2),
                appVersion = appVersion(context),
                apiLevel = Build.VERSION.SDK_INT,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                recentEvents = recentEvents,
            )
            file.writeText(report.toText())
            pruneOldReports(dir)
            file
        } catch (_: Exception) {
            null
        }
    }

    /** Reads the version from the package manager so unit tests (no BuildConfig) work too. */
    private fun appVersion(context: Context): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        }.getOrElse { BuildConfig.VERSION_NAME }
    }

    /** Lists persisted reports newest-first for the viewer. */
    fun listReports(context: Context): List<CrashReport> {
        val dir = File(context.filesDir, CrashReport.DIR_NAME)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { runCatching { CrashReport.read(it) }.getOrNull() }
    }

    /** Deletes a single report by file name; true when gone. */
    fun deleteReport(context: Context, fileName: String): Boolean {
        val safe = fileName.substringAfterLast('/').substringAfterLast('\\')
        if (!safe.startsWith("crash-") || "/" in fileName || "\\" in fileName) return false
        return File(File(context.filesDir, CrashReport.DIR_NAME), safe).delete()
    }

    /** Keeps the 20 newest reports so a crash loop cannot fill storage. */
    private fun pruneOldReports(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
        files.drop(20).forEach { runCatching { it.delete() } }
    }

    private fun formatEvent(event: ChayaEvent): String = when (event) {
        is ChayaEvent.MediaDetected -> "detected ${event.mimeType ?: "?"} via ${event.source}: ${event.url}"
        is ChayaEvent.DownloadStateChanged -> "task ${event.taskId}: ${event.from} -> ${event.to}"
        is ChayaEvent.DownloadFailed -> "task ${event.taskId} failed (${event.errorKind})"
        is ChayaEvent.PageLoaded -> "page: ${event.url}"
        is ChayaEvent.CaughtException -> "${event.tag}: ${event.message ?: "?"}"
    }
}
