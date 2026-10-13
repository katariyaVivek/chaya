package com.chaya.app.streaming

/**
 * A stream's size is not known before all of it is fetched: its playlist lists pieces, not bytes. Media3 still
 * knows what share of the pieces is done, so the total is estimated from the bytes so far once enough is in to
 * make the guess steady.
 */
object StreamProgress {
    /** Below this share of the pieces, an estimate swings too much to show. */
    const val MIN_PERCENT_FOR_ESTIMATE = 1f

    /** [contentLength] when Media3 knows it; else bytes so far scaled up by [percentDone]; else null. */
    fun totalBytes(contentLength: Long, bytesDownloaded: Long, percentDone: Float): Long? = when {
        contentLength > 0 -> contentLength
        percentDone >= MIN_PERCENT_FOR_ESTIMATE && bytesDownloaded > 0 ->
            maxOf(bytesDownloaded, (bytesDownloaded * 100.0 / percentDone.coerceAtMost(100f)).toLong())
        else -> null
    }
}
