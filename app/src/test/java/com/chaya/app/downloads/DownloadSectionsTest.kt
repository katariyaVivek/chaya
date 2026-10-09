package com.chaya.app.downloads

import com.chaya.app.download.DownloadState
import com.chaya.app.download.DownloadTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** Pins how downloads are split into sections and how each one is titled and described. */
class DownloadSectionsTest {

    /** Wednesday 8 October 2026, noon UTC. */
    private val now = Instant.parse("2026-10-08T12:00:00Z")
    private val day = 24 * 60 * 60 * 1000L

    private fun at(daysAgo: Int) = now.toEpochMilli() - daysAgo * day

    private fun task(
        id: Long,
        state: DownloadState,
        updatedDaysAgo: Int = 0,
        createdDaysAgo: Int = updatedDaysAgo,
    ) = DownloadTask(
        id = id,
        url = "https://cdn.example.com/$id.mp4",
        pageUrl = "https://www.example.com/watch",
        fileName = "file$id.mp4",
        mimeType = "video/mp4",
        state = state,
        createdAt = at(createdDaysAgo),
        updatedAt = at(updatedDaysAgo),
    )

    private val tasks = listOf(
        task(1, DownloadState.DOWNLOADING),
        task(2, DownloadState.PAUSED, createdDaysAgo = 1),
        task(3, DownloadState.FAILED),
        task(4, DownloadState.COMPLETED, updatedDaysAgo = 0),
        task(5, DownloadState.COMPLETED, updatedDaysAgo = 1),
        task(6, DownloadState.COMPLETED, updatedDaysAgo = 5),
        task(7, DownloadState.COMPLETED, updatedDaysAgo = 0),
    )

    private fun group(filter: DownloadFilter) =
        groupDownloads(tasks, filter, now, ZoneOffset.UTC, Locale.ENGLISH)

    @Test
    fun `all shows running work first, then what needs attention, then finished days`() {
        val sections = group(DownloadFilter.ALL)

        assertEquals(
            listOf("In progress", "Needs attention", "Today", "Yesterday", "3 Oct"),
            sections.map { it.title },
        )
        // Newest started first; same-day finishes keep the most recently updated first.
        assertEquals(listOf(1L, 2L), sections[0].items.map { it.id })
        assertEquals(listOf(3L), sections[1].items.map { it.id })
        assertEquals(setOf(4L, 7L), sections[2].items.map { it.id }.toSet())
        assertEquals(listOf(5L), sections[3].items.map { it.id })
        assertEquals(listOf(6L), sections[4].items.map { it.id })
    }

    @Test
    fun `active keeps unfinished work and failures but drops finished downloads`() {
        val sections = group(DownloadFilter.ACTIVE)

        assertEquals(listOf("In progress", "Needs attention"), sections.map { it.title })
    }

    @Test
    fun `done keeps only finished downloads`() {
        val sections = group(DownloadFilter.DONE)

        assertEquals(listOf("Today", "Yesterday", "3 Oct"), sections.map { it.title })
    }

    @Test
    fun `empty sections are left out`() {
        val onlyDone = listOf(task(1, DownloadState.COMPLETED))

        val sections = groupDownloads(onlyDone, DownloadFilter.ALL, now, ZoneOffset.UTC, Locale.ENGLISH)

        assertEquals(listOf("Today"), sections.map { it.title })
        assertTrue(groupDownloads(emptyList(), DownloadFilter.ALL, now, ZoneOffset.UTC, Locale.ENGLISH).isEmpty())
    }

    @Test
    fun `a download finished late in the evening stays on its own day in the viewer's time zone`() {
        // 23:30 UTC on 7 Oct is already 8 Oct in UTC+2, so it counts as today there.
        val late = task(1, DownloadState.COMPLETED).copy(updatedAt = Instant.parse("2026-10-07T23:30:00Z").toEpochMilli())

        val inUtc = groupDownloads(listOf(late), DownloadFilter.DONE, now, ZoneOffset.UTC, Locale.ENGLISH)
        val inPlusTwo = groupDownloads(listOf(late), DownloadFilter.DONE, now, ZoneOffset.ofHours(2), Locale.ENGLISH)

        assertEquals(listOf("Yesterday"), inUtc.map { it.title })
        assertEquals(listOf("Today"), inPlusTwo.map { it.title })
    }

    @Test
    fun `title prefers the saved title and falls back to the file name`() {
        val base = task(1, DownloadState.COMPLETED)

        assertEquals("Big Buck Bunny", displayTitle(base.copy(title = "Big Buck Bunny")))
        assertEquals("file1.mp4", displayTitle(base))
        assertEquals("file1.mp4", displayTitle(base.copy(title = "   ")))
    }

    @Test
    fun `finished details list size, quality and site, skipping what is unknown`() {
        val base = task(1, DownloadState.COMPLETED).copy(totalBytes = 50_331_648L, qualityHeight = 720)

        assertEquals("48.0 MB · 720p · example.com", finishedDetails(base))
        assertEquals("48.0 MB · example.com", finishedDetails(base.copy(qualityHeight = null)))
        assertEquals("example.com", finishedDetails(base.copy(totalBytes = null, downloadedBytes = 0, qualityHeight = 0)))
    }

    @Test
    fun `finished details fall back to downloaded bytes when no total was reported`() {
        val stream = task(1, DownloadState.COMPLETED).copy(totalBytes = null, downloadedBytes = 2_097_152L, pageUrl = null)

        assertEquals("2.0 MB · cdn.example.com", finishedDetails(stream))
    }
}
