package com.chaya.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pins the crash-report contract: write → list → read round-trip, text
 * rendering includes trace + recent events, deletion is path-safe, and the
 * installed handler chains to the previous one instead of swallowing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrashReporterTest {

    private fun context() = RuntimeEnvironment.getApplication()

    @Test
    fun `writeReport persists trace plus recent events and reads back`() {
        val log = EventLog(context())
        val crashDir = File(context().filesDir, CrashReport.DIR_NAME)
        crashDir.deleteRecursively()

        // Synchronous seed so the report deterministically includes events.
        kotlinx.coroutines.runBlocking {
            log.recordSync(ChayaEvent.PageLoaded(url = "https://example.com/a"))
            log.recordSync(ChayaEvent.DownloadFailed(taskId = 3, errorKind = "HttpStatus", retryable = false))
        }
        val file = CrashReporter.writeReport(
            context(), log, Thread.currentThread(), RuntimeException("boom-test"),
        )

        assertTrue(file != null && file.exists())
        val reports = CrashReporter.listReports(context())
        assertEquals(1, reports.size)
        val report = reports.single()
        assertTrue(report.exceptionName.contains("RuntimeException"))
        assertTrue(report.stackTrace.contains("boom-test"))
        assertEquals(2, report.recentEvents.size)
        val text = report.toText()
        assertTrue(text.contains("boom-test"))
        assertTrue(text.contains("https://example.com/a"))
        assertTrue(text.contains("HttpStatus"))
    }

    @Test
    fun `report text round-trips through the parser`() {
        val original = CrashReport(
            fileName = "crash-x.log",
            timestamp = 123L,
            threadName = "main",
            exceptionName = "java.lang.RuntimeException",
            stackTrace = "java.lang.RuntimeException: k\n\tat a.B.c(B.java:1)",
            appVersion = "0.3.0",
            apiLevel = 35,
            deviceModel = "m d",
            recentEvents = listOf("page: https://example.com/"),
        )

        val dir = createTempDir("crash-parse")
        try {
            val file = File(dir, "crash-x.log")
            file.writeText(original.toText())
            val parsed = CrashReport.read(file)!!

            assertEquals("main", parsed.threadName)
            assertEquals("java.lang.RuntimeException", parsed.exceptionName)
            assertEquals("0.3.0", parsed.appVersion)
            assertEquals(35, parsed.apiLevel)
            assertEquals("m d", parsed.deviceModel)
            assertTrue(parsed.stackTrace.contains("B.c"))
            assertEquals(listOf("page: https://example.com/"), parsed.recentEvents)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `deleteReport removes one file and rejects path tricks`() {
        val log = EventLog(context())
        val file = CrashReporter.writeReport(
            context(), log, Thread.currentThread(), RuntimeException("del"),
        )!!

        assertTrue(CrashReporter.deleteReport(context(), file.name))
        assertTrue(!file.exists())
        assertTrue(!CrashReporter.deleteReport(context(), "../shared_prefs/x"))
        assertTrue(!CrashReporter.deleteReport(context(), "other.log"))
        assertNull(CrashReport.read(File(context().filesDir, "nope.log")))
    }

    @Test
    fun `installed handler chains to previous instead of swallowing`() {
        CrashReporter.resetForTests()
        val previousCalls = mutableListOf<Pair<Thread, Throwable>>()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> previousCalls += t to e }
        val log = EventLog(context())
        CrashReporter.install(context(), log)

        val error = RuntimeException("chain-test")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)

        assertEquals(1, previousCalls.size)
        assertEquals(error, previousCalls.single().second)
        assertEquals(1, CrashReporter.listReports(context()).size)
        // Restore a neutral handler so later tests start clean.
        CrashReporter.resetForTests()
        Thread.setDefaultUncaughtExceptionHandler(null)
    }
}
