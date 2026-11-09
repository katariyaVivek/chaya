package com.chaya.app.downloads

import com.chaya.app.detection.MediaUrlClassifier
import com.chaya.app.download.DownloadNotification.formatFileSize
import com.chaya.app.download.DownloadState
import com.chaya.app.download.DownloadStep
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
        tasks.filter { it.state.isUnfinished }
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

/**
 * What a running download's card shows: how far it has got, or null when that can't be told (the bar then moves
 * on its own), the label on the left and the detail on the right.
 */
internal data class CardProgress(val fraction: Float?, val label: String, val detail: String)

/** The card's progress for [task], from what it is doing ([DownloadTask.step]) or, failing that, its bytes. */
internal fun progressOf(task: DownloadTask): CardProgress {
    val size = if (task.downloadedBytes > 0) formatFileSize(task.downloadedBytes) else ""
    return when (val step = task.step) {
        is DownloadStep.SavingAsMp4 -> CardProgress(step.fraction, "Saving as MP4 · ${percent(step.fraction)}", size)
        // An archive counts files, not bytes: it cannot know its size until every file has arrived.
        is DownloadStep.FindingPosts ->
            CardProgress(null, "Finding posts", if (step.found > 0) "${step.found} so far" else "")
        is DownloadStep.FetchingFiles ->
            if (step.packing) CardProgress(null, "Making the ZIP", formatFileSize(task.downloadedBytes))
            else CardProgress(step.saved.toFloat() / step.found, "${step.saved} of ${step.found} files", formatFileSize(task.downloadedBytes))
        DownloadStep.Joining -> CardProgress(null, "Joining picture and sound", size)
        // Which half of a quality whose picture and sound come apart is on its way, as Chaya for Windows says it.
        DownloadStep.FetchingPicture -> transfer(task, "Picture")
        DownloadStep.FetchingSound -> transfer(task, "Sound")
        null -> transfer(task, null)
    }
}

/** Bytes on their way: the share of a known size, or a moving bar without one; [part] names the half fetched. */
private fun transfer(task: DownloadTask, part: String?): CardProgress {
    val total = task.totalBytes ?: 0L
    return if (total > 0L) {
        val share = percent(task.progressFraction)
        CardProgress(task.progressFraction, part?.let { "$it · $share" } ?: share,
            "${formatFileSize(task.downloadedBytes)} of ${formatFileSize(total)}")
    } else {
        CardProgress(null, part ?: "Downloading", if (task.downloadedBytes > 0) formatFileSize(task.downloadedBytes) else "")
    }
}

private fun percent(fraction: Float) = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%"
