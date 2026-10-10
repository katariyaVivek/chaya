package com.chaya.app.download

/**
 * Categorized download failure: a human message for the downloads screen
 * plus whether retrying could plausibly help.
 *
 * Raw OkHttp/Media3 exception text (e.g. "timeout", "Unable to resolve
 * host...") is never shown verbatim — [DownloadManager.failTask] classifies
 * every failure through [from] first.
 */
sealed class DownloadError(
    val userMessage: String,
    val retryable: Boolean,
) {
    /** Lost connectivity, DNS failure, or socket timeout. */
    data class Network(val cause: Throwable) : DownloadError(
        "Connection lost — check your network",
        retryable = true,
    )

    /** HTTP error status from the server. */
    data class HttpStatus(val code: Int) : DownloadError(
        userMessage = when (code) {
            401, 403 -> "Access denied — the link may have expired"
            404 -> "File not found on the server"
            429 -> "Too many requests — try again shortly"
            in 500..599 -> "Server error — try again shortly"
            else -> "Download failed (HTTP $code)"
        },
        retryable = code == 429 || code >= 500,
    )

    /** Disk filled mid-download. */
    object StorageFull : DownloadError(
        "Not enough storage space",
        retryable = false,
    )

    /** Media the pipeline cannot handle yet. */
    object UnsupportedFormat : DownloadError(
        "This format isn't supported yet",
        retryable = false,
    )

    /** Picture and sound arrived but could not be joined into one file; retrying only repeats the join. */
    data class CouldNotCombine(val cause: Throwable) : DownloadError(
        "Couldn't combine the picture and sound",
        retryable = true,
    )

    /** An archive's contents could not be listed; [message] says why (a sign-in is needed, the account is private). */
    data class Listing(val message: String, val canRetry: Boolean = true) : DownloadError(message, retryable = canRetry)

    /** User-cancelled; recorded for completeness, never shown as a failure. */
    object Cancelled : DownloadError("Cancelled", retryable = false)

    /** Anything the classifier cannot place; retry stays available. */
    data class Unknown(val cause: Throwable) : DownloadError(
        "Something went wrong",
        retryable = true,
    )

    companion object {
        /** Matches the `IOException("HTTP <code>: ...")` shape HttpDownloader already emits. */
        private val httpStatusPattern = Regex("""HTTP\s+(\d{3})\b""")

        /** Classifies a raw failure into the taxonomy above. */
        fun from(throwable: Throwable): DownloadError {
            if (throwable is java.util.concurrent.CancellationException) return Cancelled
            // Before the join check: a join that ran out of room says so deep in its cause chain.
            if (isStorageFull(throwable)) return StorageFull
            if (throwable is CombineException) return CouldNotCombine(throwable)
            if (throwable is ArchiveListingException) {
                return Listing(throwable.message ?: "Couldn't list what to save", throwable.retryable)
            }
            httpStatusPattern.find(throwable.message ?: "")?.let { match ->
                return HttpStatus(match.groupValues[1].toInt())
            }
            return when (throwable) {
                is java.net.UnknownHostException,
                is java.net.SocketTimeoutException,
                is java.net.ConnectException,
                is java.net.SocketException,
                is java.io.InterruptedIOException,
                -> Network(throwable)
                is java.io.IOException -> {
                    // IOExceptions wrapping a network cause (e.g. OkHttp's
                    // RouteException chains) are connectivity, not server bugs.
                    if (hasNetworkCause(throwable)) Network(throwable) else Unknown(throwable)
                }
                else -> Unknown(throwable)
            }
        }

        /** Walks the cause chain for ENOSPC / "no space left" signals. */
        private fun isStorageFull(throwable: Throwable): Boolean {
            var current: Throwable? = throwable
            while (current != null) {
                val message = current.message?.lowercase() ?: ""
                if ("enospc" in message || "no space left" in message) return true
                current = current.cause
            }
            return false
        }

        /** Walks the cause chain for connectivity failures wrapped in generic IOExceptions. */
        private fun hasNetworkCause(throwable: Throwable): Boolean {
            var current = throwable.cause
            while (current != null) {
                if (current is java.net.UnknownHostException ||
                    current is java.net.SocketTimeoutException ||
                    current is java.net.ConnectException ||
                    current is java.net.SocketException
                ) return true
                current = current.cause
            }
            return false
        }
    }
}
