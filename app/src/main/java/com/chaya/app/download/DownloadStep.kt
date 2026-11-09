package com.chaya.app.download

/**
 * What a running download is doing besides fetching one file's bytes, for its card; null while that is all it does.
 *
 * Only a DOWNLOADING task has a step: [DownloadManager] drops it on every other state. It is kept in memory only.
 * After a restart, what is left of a download is worked out again from its files, and the step with it.
 *
 * This used to be two fields (an archive's progress and a fraction for saving a stream), with the join between a
 * picture and its sound shown as neither, so its card sat at its last byte count. One value means one step at a
 * time, and the card asks one `when` what to say.
 */
sealed interface DownloadStep {
    /**
     * A quality whose picture and sound come apart, fetching its picture. Its bytes and total count both files, the
     * picture first, so the share runs on through [FetchingSound].
     */
    data object FetchingPicture : DownloadStep

    /** The same, fetching its sound once the picture is whole. */
    data object FetchingSound : DownloadStep

    /** An account's ZIP finding out what was posted: [found] files so far. */
    data class FindingPosts(val found: Int) : DownloadStep

    /** An account's ZIP fetching its files: [saved] (fetched, or found gone) of [found]. */
    data class FetchingFiles(val saved: Int, val found: Int) : DownloadStep {
        /** Every file is in, so the ZIP is being made. */
        val packing: Boolean get() = saved >= found
    }

    /** A picture and its sound, both arrived, being joined into one file. */
    data object Joining : DownloadStep

    /** A finished stream being saved as an MP4 file, [fraction] of the way there (0 to 1). */
    data class SavingAsMp4(val fraction: Float) : DownloadStep
}
