package com.chaya.app.download

import org.json.JSONObject
import java.io.File

/**
 * Lists what goes into a ZIP archive of many files, such as everything an account has posted. The platform
 * layer provides it; [DownloadManager] fetches the files and makes the archive.
 */
fun interface ArchiveLister {
    /**
     * Lists the files for [source] into [list], one JSON object per line ({"name", "url", "http_headers"}), and
     * creates [list] only once the listing is complete. [onFound] hears how many files have been found so far.
     * A file at [stop] asks the listing to end early; [list] is then not created.
     * @throws ArchiveListingException with a message for the downloads screen.
     */
    suspend fun list(source: String, list: File, stop: File, onFound: (Int) -> Unit)
}

/** Why an archive could not be listed, in words fit for the downloads screen. */
class ArchiveListingException(message: String, val retryable: Boolean = true) : Exception(message)

/**
 * A ZIP archive to make. [source] says what it holds ("chaya-archive:instagram:someone"); the lister knows how
 * to read it. [fileName] includes ".zip".
 */
data class ArchiveRequest(
    val source: String,
    val fileName: String,
    val title: String,
    val pageUrl: String? = null,
    val thumbnailUrl: String? = null,
)

/** How far an archive has got. Kept in memory only, and worked out again from its files when it resumes. */
data class ArchiveProgress(
    /** Files found so far while listing, or in the finished list. */
    val found: Int,
    /** Files fetched (or found gone) so far. */
    val saved: Int,
    /** Still finding out what the archive holds. */
    val listing: Boolean,
)

/** One file in an archive's list. [name] is its name inside the archive, the same however often it is listed. */
internal data class ArchiveEntry(val name: String, val url: String, val headers: Map<String, String>) {
    companion object {
        /** The entries of a finished list, each name once, skipping lines that cannot be used. */
        fun readAll(list: File): List<ArchiveEntry> =
            list.readLines()
                .mapNotNull { line -> runCatching { parse(JSONObject(line)) }.getOrNull() }
                .distinctBy { it.name }

        private fun parse(json: JSONObject): ArchiveEntry? {
            val url = json.optString("url").takeIf { it.startsWith("http") } ?: return null
            // A name is a plain file name: nothing that could reach outside the archive's folder.
            val name = json.optString("name").substringAfterLast('/').substringAfterLast('\\').trim()
            if (name.isEmpty() || name.startsWith(".")) return null
            val headers = json.optJSONObject("http_headers")?.let { h ->
                h.keys().asSequence().associateWith { h.optString(it) }.filterValues { it.isNotEmpty() }
            }.orEmpty()
            return ArchiveEntry(name, url, headers)
        }
    }
}

/** Addresses of archive tasks start with this, so they are never mistaken for a file or a stream. */
const val ARCHIVE_SCHEME = "chaya-archive:"
