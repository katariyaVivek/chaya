package com.chaya.app.library

import com.chaya.app.download.DownloadState
import com.chaya.app.download.DownloadTask
import com.chaya.app.downloads.displayTitle
import com.chaya.app.downloads.sourceHost

/** What a download is, for the library's kind filter and its tiles. */
enum class FileKind(val label: String) {
    VIDEO("Videos"),
    PICTURE("Pictures"),
    AUDIO("Audio"),
    ZIP("ZIPs"),
}

/** Where a download came from, for the library's site filter. */
enum class SourceSite(val label: String) {
    YOUTUBE("YouTube"),
    INSTAGRAM("Instagram"),
    TIKTOK("TikTok"),
    X("X"),
    OTHER("Other"),
}

private val pictureExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif", "bmp")
private val videoExtensions = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp", "ts")
private val audioExtensions = setOf("mp3", "m4a", "aac", "opus", "ogg", "oga", "wav", "flac")

/** The kind of a file named [name] with type [mimeType]; null for anything else (a document, say). */
fun kindOf(mimeType: String?, name: String): FileKind? {
    val mime = mimeType.orEmpty().lowercase()
    val extension = name.substringAfterLast('.', "").lowercase()
    return when {
        mime == "application/zip" || extension == "zip" -> FileKind.ZIP
        mime.startsWith("image/") -> FileKind.PICTURE
        mime.startsWith("audio/") -> FileKind.AUDIO
        mime.startsWith("video/") -> FileKind.VIDEO
        extension in pictureExtensions -> FileKind.PICTURE
        extension in videoExtensions -> FileKind.VIDEO
        extension in audioExtensions -> FileKind.AUDIO
        else -> null
    }
}

fun kindOf(task: DownloadTask): FileKind? = when {
    task.isArchive -> FileKind.ZIP
    // A stream has no file name of its own worth reading; what it holds is a video unless it says sound.
    task.isStream && task.mimeType?.startsWith("audio/") != true -> FileKind.VIDEO
    else -> kindOf(task.mimeType, task.fileName)
}

/** The site a download came from, by the page's address (or the file's, without one). */
fun siteOf(task: DownloadTask): SourceSite {
    val host = sourceHost(task)?.lowercase() ?: return SourceSite.OTHER
    fun on(domain: String) = host == domain || host.endsWith(".$domain")
    return when {
        on("youtube.com") || on("youtu.be") || on("googlevideo.com") -> SourceSite.YOUTUBE
        on("instagram.com") || on("cdninstagram.com") -> SourceSite.INSTAGRAM
        on("tiktok.com") -> SourceSite.TIKTOK
        on("x.com") || on("twitter.com") || on("twimg.com") -> SourceSite.X
        else -> SourceSite.OTHER
    }
}

/** What the library is narrowed to: a kind, a site, and words in the title. Each is optional. */
data class LibraryFilter(
    val kind: FileKind? = null,
    val site: SourceSite? = null,
    val query: String = "",
) {
    val isEmpty: Boolean get() = kind == null && site == null && query.isBlank()

    fun matches(task: DownloadTask): Boolean {
        if (kind != null && kindOf(task) != kind) return false
        if (site != null && siteOf(task) != site) return false
        val words = query.trim()
        if (words.isEmpty()) return true
        return displayTitle(task).contains(words, ignoreCase = true) || task.fileName.contains(words, ignoreCase = true)
    }
}

/** One tile of the library: a finished download, or the files of one post together. */
sealed interface LibraryItem {
    /** Stable across updates, for the grid and for selection. */
    val key: String

    /** The downloads the tile stands for, in the post's order. */
    val tasks: List<DownloadTask>

    val title: String

    /** When the newest of them finished. */
    val finishedAt: Long get() = tasks.maxOf { it.updatedAt }

    /** What the tile shows first. */
    val cover: DownloadTask get() = tasks.first()

    data class Single(val task: DownloadTask) : LibraryItem {
        override val key get() = "task:${task.id}"
        override val tasks get() = listOf(task)
        override val title get() = displayTitle(task)
    }

    data class Post(val groupKey: String, override val tasks: List<DownloadTask>) : LibraryItem {
        override val key get() = "post:$groupKey"
        override val title get() = postTitle(tasks.first())
    }
}

private val placeInPost = Regex("""\s*\(\d+ of \d+\)$""")

/** "Sunset (2 of 5)" is from the post "Sunset". */
fun postTitle(task: DownloadTask): String = displayTitle(task).replace(placeInPost, "").ifBlank { displayTitle(task) }

/**
 * The finished downloads [filter] keeps, newest first, a post's files together on one tile (in the order they
 * were saved). A post with only one file left after filtering is an ordinary tile.
 */
fun libraryItems(tasks: List<DownloadTask>, filter: LibraryFilter = LibraryFilter()): List<LibraryItem> {
    val kept = tasks.filter { it.state == DownloadState.COMPLETED && filter.matches(it) }
    val posts = kept.filter { it.groupKey != null }.groupBy { it.groupKey!! }
    val items = mutableListOf<LibraryItem>()
    kept.forEach { task ->
        val key = task.groupKey
        val siblings = key?.let { posts[it] }
        when {
            siblings == null || siblings.size < 2 -> items += LibraryItem.Single(task)
            siblings.first() === task -> items += LibraryItem.Post(key, siblings.sortedBy { it.id })
        }
    }
    return items.sortedByDescending { it.finishedAt }
}

/** How many downloads are still running, waiting or paused: the grid only shows what is done. */
fun unfinishedCount(tasks: List<DownloadTask>): Int =
    tasks.count { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED || it.state == DownloadState.PAUSED }
