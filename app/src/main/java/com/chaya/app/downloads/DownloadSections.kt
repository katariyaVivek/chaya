package com.chaya.app.downloads

import com.chaya.app.detection.MediaUrlClassifier
import com.chaya.app.download.DownloadNotification.formatFileSize
import com.chaya.app.download.DownloadState
import com.chaya.app.download.DownloadTask
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Which slice of the list the filter chips show. */
enum class DownloadFilter(val label: String) {
    ALL("All"),

    /** Unfinished work: running, queued, paused, and anything that needs attention. */
    ACTIVE("Active"),

    DONE("Done"),
}

/** One titled run of downloads in the list. */
data class DownloadSection(val title: String, val items: List<DownloadTask>)

private val inProgressStates = setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.PAUSED)
private val attentionStates = setOf(DownloadState.FAILED, DownloadState.CANCELLED)

/**
 * Splits downloads into the sections the list shows: what is running, what
 * needs attention, then finished ones grouped by the day they finished.
 * Empty sections are left out.
 */
fun groupDownloads(
    tasks: List<DownloadTask>,
    filter: DownloadFilter,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<DownloadSection> {
    val sections = mutableListOf<DownloadSection>()

    if (filter != DownloadFilter.DONE) {
        tasks.filter { it.state in inProgressStates }
            .sortedByDescending { it.createdAt }
            .takeIf { it.isNotEmpty() }
            ?.let { sections += DownloadSection("In progress", it) }
        tasks.filter { it.state in attentionStates }
            .sortedByDescending { it.updatedAt }
            .takeIf { it.isNotEmpty() }
            ?.let { sections += DownloadSection("Needs attention", it) }
    }

    if (filter != DownloadFilter.ACTIVE) {
        val today = LocalDate.ofInstant(now, zone)
        val dayFormat = DateTimeFormatter.ofPattern("d MMM", locale)
        val byDay = linkedMapOf<LocalDate, MutableList<DownloadTask>>()
        tasks.filter { it.state == DownloadState.COMPLETED }
            .sortedByDescending { it.updatedAt }
            .forEach { task ->
                byDay.getOrPut(LocalDate.ofInstant(Instant.ofEpochMilli(task.updatedAt), zone)) { mutableListOf() } += task
            }
        byDay.forEach { (day, items) ->
            val title = when (day) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                else -> day.format(dayFormat)
            }
            sections += DownloadSection(title, items)
        }
    }

    return sections
}

/** What the list calls a download: its title, or the file name for downloads saved before titles existed. */
fun displayTitle(task: DownloadTask): String =
    task.title?.takeIf { it.isNotBlank() } ?: task.fileName

/** Site the media came from, e.g. "youtube.com"; null when the address has no host. */
fun sourceHost(task: DownloadTask): String? =
    MediaUrlClassifier.hostOf(task.pageUrl ?: task.url)?.removePrefix("www.")

/** Facts shown under a finished download: "48.0 MB · 720p · youtube.com". */
fun finishedDetails(task: DownloadTask): String {
    val size = (task.totalBytes?.takeIf { it > 0 } ?: task.downloadedBytes)
        .takeIf { it > 0 }
        ?.let { bytes -> formatFileSize(bytes) }
    val quality = task.qualityHeight?.takeIf { it >= 144 }?.let { "${it}p" }
    return listOfNotNull(size, quality, sourceHost(task)).joinToString(" · ")
}
