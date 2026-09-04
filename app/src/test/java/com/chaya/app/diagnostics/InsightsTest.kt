package com.chaya.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the pure aggregation in [Insights.compute]: per-domain detection
 * counts, success rate over finished downloads, and failures by kind.
 */
class InsightsTest {

    /** Mixed trail: 3 detections, 2 completions, 1 classified failure. */
    private fun trail() = listOf(
        ChayaEvent.MediaDetected(url = "https://cdn.a.com/x.mp4", source = "NETWORK", mimeType = "video/mp4"),
        ChayaEvent.MediaDetected(url = "https://cdn.a.com/y.mp4", source = "DOM", mimeType = "video/mp4"),
        ChayaEvent.MediaDetected(url = "https://cdn.b.com/z.mp3?sig=s", source = "NETWORK", mimeType = "audio/mpeg"),
        ChayaEvent.DownloadStateChanged(taskId = 1, from = "NONE", to = "DOWNLOADING"),
        ChayaEvent.DownloadStateChanged(taskId = 1, from = "DOWNLOADING", to = "COMPLETED"),
        ChayaEvent.DownloadStateChanged(taskId = 2, from = "NONE", to = "DOWNLOADING"),
        ChayaEvent.DownloadStateChanged(taskId = 2, from = "DOWNLOADING", to = "COMPLETED"),
        ChayaEvent.DownloadStateChanged(taskId = 3, from = "NONE", to = "DOWNLOADING"),
        ChayaEvent.DownloadStateChanged(taskId = 3, from = "DOWNLOADING", to = "FAILED"),
        ChayaEvent.DownloadFailed(taskId = 3, errorKind = "HttpStatus", retryable = false),
    )

    @Test
    fun `detection counts group by host most-active first`() {
        val insights = Insights.compute(trail())

        assertEquals(
            listOf("cdn.a.com" to 2, "cdn.b.com" to 1),
            insights.detectedPerDomain,
        )
    }

    @Test
    fun `success rate covers finished downloads only`() {
        val insights = Insights.compute(trail())

        assertEquals(2, insights.completed)
        assertEquals(1, insights.failed)
        assertEquals(2f / 3f, insights.successRate!!, 0.001f)
        assertEquals(listOf("HttpStatus" to 1), insights.failuresByKind)
    }

    @Test
    fun `empty trail yields null rate and empty lists`() {
        val insights = Insights.compute(emptyList())

        assertNull(insights.successRate)
        assertEquals(emptyList<Pair<String, Int>>(), insights.detectedPerDomain)
        assertEquals(emptyList<Pair<String, Int>>(), insights.failuresByKind)
    }

    @Test
    fun `in-flight downloads do not move the rate`() {
        val events = listOf(
            ChayaEvent.DownloadStateChanged(taskId = 1, from = "NONE", to = "DOWNLOADING"),
        )

        assertNull(Insights.compute(events).successRate)
    }
}
