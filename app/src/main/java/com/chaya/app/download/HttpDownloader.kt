package com.chaya.app.download

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Downloads a single file over HTTP with progress reporting, resume support
 * (`Range` header) and cancellation.
 *
 * Cancellation is *silent*: [cancel] stops the transfer without invoking any
 * callback, so [DownloadManager] stays the single owner of state transitions.
 *
 * A file fetched in pieces ([chunkBytesFor]) can have [piecesAtOnce] of them on the way at the same time, each
 * written at its own place in the file; [PieceLog] keeps which are whole so a resume fetches only the rest.
 */
class HttpDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
    /** How many bytes to ask for per request, or null for the whole file in one request. */
    private val chunkBytesFor: (url: String) -> Long? = ::youTubeChunkBytes,
    /** How many pieces of a file fetched in pieces are asked for at once; 1 fetches them one after another. */
    private val piecesAtOnce: Int = 1,
) : MediaDownloader {

    /** Everything one download has open, so cancel reaches every piece on the way. */
    private class Transfer {
        private val calls = mutableSetOf<okhttp3.Call>()

        @Volatile
        var cancelled = false
            private set

        /** Registers [call]; false (and the call cancelled) when the download was cancelled already. */
        fun add(call: okhttp3.Call): Boolean = synchronized(this) {
            if (cancelled) {
                call.cancel()
                false
            } else {
                calls += call
                true
            }
        }

        fun remove(call: okhttp3.Call) = synchronized(this) { calls -= call }

        /** Stops every call without marking the download cancelled: one piece failed, so the others stop too. */
        fun stopCalls() = synchronized(this) { calls.toList() }.forEach { it.cancel() }

        fun cancel() {
            synchronized(this) { cancelled = true }
            stopCalls()
        }
    }

    private val transfers = mutableMapOf<Long, Transfer>()

    /**
     * Start downloading [url] into [saveFile].
     *
     * @param fromBytes resume offset; a partial file must already exist.
     * @param onMeta invoked once per response with the server-suggested
     *   filename from `Content-Disposition` (null if absent), BEFORE any byte
     *   is written — safe to rename [saveFile] inside this callback.
     * @param onProgress throttled progress callback.
     * @param onComplete terminal result. Never called after [cancel].
     */
    override fun start(
        taskId: Long,
        url: String,
        saveFile: File,
        userAgent: String?,
        cookies: String?,
        referer: String?,
        fromBytes: Long,
        onMeta: ((suggestedName: String?) -> Unit)?,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onComplete: (Result<File>) -> Unit
    ) {
        saveFile.parentFile?.mkdirs()

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")

        userAgent?.let { requestBuilder.header("User-Agent", it) }
        cookies?.let { requestBuilder.header("Cookie", it) }
        if (!referer.isNullOrBlank()) {
            requestBuilder.header("Referer", referer)
        }
        val chunkBytes = chunkBytesFor(url)
        // Several pieces at once need a record of which are whole; one after another, the file's length says it.
        val pieces = chunkBytes?.takeIf { piecesAtOnce > 1 }?.let { PieceLog.load(saveFile, it, fromBytes) }
        // The headers every piece is asked with; each gets its own Range.
        val base = requestBuilder.build()
        // The first piece missing: where a download several at a time starts.
        val firstPiece = pieces?.firstMissing()
        when {
            pieces != null && firstPiece != null ->
                requestBuilder.header("Range", rangeFrom(firstPiece * pieces.pieceBytes, pieces.pieceBytes, pieces.total))
            chunkBytes != null -> requestBuilder.header("Range", rangeFrom(fromBytes, chunkBytes, total = null))
            fromBytes > 0 -> requestBuilder.header("Range", "bytes=$fromBytes-")
        }

        val call = client.newCall(requestBuilder.build())
        // The call fetching right now: a download fetched in pieces moves on to a new call for each one.
        var current = call

        val transfer = Transfer()
        synchronized(transfers) { transfers.put(taskId, transfer) }?.cancel()
        transfer.add(call)

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                release(taskId, transfer)
                // Silent when cancelled — manager owns the state machine.
                if (call.isCanceled() || transfer.cancelled) return
                onComplete(Result.failure(e))
            }

            // The call stays registered until its body is fully read: it used to be dropped as soon as
            // the headers arrived, so a pause, cancel or delete during the transfer found nothing to
            // cancel and the body kept streaming into the file, then reported success.
            override fun onResponse(call: okhttp3.Call, response: Response) = try {
                transfer(call, response)
            } finally {
                release(taskId, transfer)
            }

            private fun transfer(call: okhttp3.Call, response: Response) {
                response.use { resp ->
                    if (call.isCanceled() || transfer.cancelled) return

                    if (!resp.isSuccessful) {
                        onComplete(Result.failure(IOException("HTTP ${resp.code}: ${resp.message}")))
                        return
                    }

                    if (pieces != null && firstPiece != null) {
                        if (resp.code == 206) {
                            onMeta?.invoke(parseContentDisposition(resp.header("Content-Disposition")))
                            fetchInPieces(transfer, base, resp, firstPiece, pieces, saveFile, onProgress, onComplete)
                            return
                        }
                        // The server sends the whole file whatever was asked: written from the start, below.
                        pieces.delete()
                    }

                    // Server ignored our Range header and returned the whole body —
                    // restart from zero instead of corrupting the partial file.
                    val resumed = resp.code == 206 && fromBytes > 0
                    // Fetching in pieces only makes sense once the server has shown it honours ranges.
                    val pieceBytes = chunkBytes.takeIf { resp.code == 206 }

                    val body = resp.body ?: run {
                        onComplete(Result.failure(IOException("Empty response body")))
                        return
                    }

                    val totalBytes: Long? = when {
                        resp.code == 206 -> {
                            val range = resp.header("Content-Range") ?: ""
                            val idx = range.lastIndexOf('/')
                            if (idx >= 0) range.substring(idx + 1).toLongOrNull()?.takeIf { it > 0 }
                            else null
                        }
                        else -> body.contentLength().takeIf { it > 0 }
                    }

                    // Report server-suggested name before first write so callers can rename.
                    onMeta?.invoke(parseContentDisposition(resp.header("Content-Disposition")))

                    val append = resumed
                    var downloaded = if (resumed) fromBytes else 0L

                    val buffer = ByteArray(64 * 1024)
                    var lastReportBytes = downloaded
                    var lastReportTime = System.currentTimeMillis()
                    var piece = resp
                    var source = body.source()

                    try {
                        FileOutputStream(saveFile, append).use { stream ->
                            while (true) {
                                if (current.isCanceled() || transfer.cancelled) return  // silent cancel
                                val read = source.read(buffer)
                                if (read == -1) {
                                    val total = totalBytes
                                    if (pieceBytes == null || total == null || downloaded >= total) break
                                    // This piece is done; ask for the next one on a call cancel can reach.
                                    if (piece !== resp) piece.close()
                                    val next = client.newCall(
                                        requestBuilder.header("Range", rangeFrom(downloaded, pieceBytes, total)).build()
                                    )
                                    transfer.remove(current)
                                    if (!transfer.add(next)) return  // cancelled between pieces
                                    current = next
                                    piece = next.execute()
                                    if (current.isCanceled() || transfer.cancelled) return
                                    if (piece.code != 206) {
                                        throw IOException("HTTP ${piece.code}: ${piece.message} for bytes $downloaded-")
                                    }
                                    source = (piece.body ?: throw IOException("Empty response body")).source()
                                    continue
                                }
                                stream.write(buffer, 0, read)
                                downloaded += read

                                val now = System.currentTimeMillis()
                                if (downloaded - lastReportBytes >= 64 * 1024 ||
                                    now - lastReportTime >= 300
                                ) {
                                    lastReportBytes = downloaded
                                    lastReportTime = now
                                    onProgress(downloaded, totalBytes)
                                }
                            }
                        }
                        onProgress(downloaded, totalBytes)
                        onComplete(Result.success(saveFile))
                    } catch (e: Exception) {
                        if (!current.isCanceled() && !transfer.cancelled) {
                            onComplete(Result.failure(e))
                        }
                    } finally {
                        if (piece !== resp) piece.close()
                    }
                }
            }
        })
    }

    /** Silently stop the transfer for [taskId]. Partial file is preserved. */
    override fun cancel(taskId: Long) {
        synchronized(transfers) { transfers.remove(taskId) }?.cancel()
    }

    fun cancelAll() {
        val all = synchronized(transfers) { transfers.values.toList().also { transfers.clear() } }
        all.forEach { it.cancel() }
    }

    /** Forgets [transfer] once it has ended, unless a resume has already started a newer one for [taskId]. */
    private fun release(taskId: Long, transfer: Transfer) {
        synchronized(transfers) {
            if (transfers[taskId] === transfer) transfers.remove(taskId)
        }
    }

    /**
     * Fetches the pieces [log] lacks, [piecesAtOnce] at a time, each written at its place in [saveFile]; [first]
     * is the server's answer for piece [firstIndex], already here. A piece answered with anything but its range
     * puts the rest one at a time; a piece that fails then fails the download. Runs on the first answer's thread
     * and reports once, when every piece has ended, unless the download was cancelled.
     */
    private fun fetchInPieces(
        transfer: Transfer,
        base: Request,
        first: Response,
        firstIndex: Int,
        log: PieceLog,
        saveFile: File,
        onProgress: (Long, Long?) -> Unit,
        onComplete: (Result<File>) -> Unit,
    ) {
        val total = contentRangeTotal(first.header("Content-Range")) ?: run {
            onComplete(Result.failure(IOException("The server did not say how big the file is")))
            return
        }
        log.begin(total)
        val missing = ArrayDeque((0 until log.count(total)).filter { it != firstIndex && it !in log.done })
        val downloaded = AtomicLong(log.doneBytes())
        val failure = AtomicReference<Throwable?>(null)
        val progress = ProgressThrottle(downloaded, total, onProgress)

        // Read and written only while holding [missing], or after every helper has finished.
        var oneAtATime = false

        fun fail(error: Throwable) {
            if (failure.compareAndSet(null, error)) transfer.stopCalls()
        }

        fun stopping() = transfer.cancelled || failure.get() != null

        /** Takes pieces until none are left, starting with [startIndex] when its reply [startAnswer] is already here. */
        fun fetch(startIndex: Int?, startAnswer: Response?, alone: Boolean) {
            var index = startIndex
            var answer = startAnswer
            while (true) {
                if (stopping()) {
                    answer?.close()
                    return
                }
                val piece = index ?: synchronized(missing) {
                    if (oneAtATime && !alone) null else missing.pollFirst()
                } ?: return
                val range = log.range(piece, total)
                var call: okhttp3.Call? = null
                try {
                    val reply = answer ?: run {
                        val next = client.newCall(
                            base.newBuilder().header("Range", "bytes=${range.first}-${range.last}").build()
                        )
                        if (!transfer.add(next)) return
                        call = next
                        next.execute()
                    }
                    reply.use {
                        if (reply.code != 206 || contentRangeStart(reply.header("Content-Range")) != range.first) {
                            val error = IOException("HTTP ${reply.code}: ${reply.message} for bytes ${range.first}-")
                            if (alone) {
                                fail(error)
                            } else {
                                // Anything but this range: give the piece back and fetch the rest one at a time.
                                synchronized(missing) {
                                    missing.addFirst(piece)
                                    oneAtATime = true
                                }
                            }
                            return
                        }
                        val body = reply.body ?: throw IOException("Empty response body")
                        writePiece(body.source(), saveFile, range, downloaded, progress) { stopping() }
                    }
                    if (stopping()) return
                    log.markDone(piece)
                } catch (e: Exception) {
                    if (!transfer.cancelled) fail(e)
                    return
                } finally {
                    call?.let(transfer::remove)
                }
                index = null
                answer = null
            }
        }

        val helpers = (1 until piecesAtOnce).map { n ->
            thread(name = "chaya-piece-$n", isDaemon = true) { fetch(null, null, alone = false) }
        }
        fetch(firstIndex, first, alone = false)
        helpers.forEach { it.join() }
        // A server that refused a piece's range: the rest, one at a time.
        if (oneAtATime && !stopping()) fetch(null, null, alone = true)

        if (transfer.cancelled) return
        failure.get()?.let {
            onComplete(Result.failure(it))
            return
        }
        if (!log.isComplete()) {
            onComplete(Result.failure(IOException("Some pieces of the file are missing")))
            return
        }
        try {
            // A longer file left from before (a different size) is cut to this one.
            RandomAccessFile(saveFile, "rw").use { it.setLength(total) }
        } catch (e: IOException) {
            onComplete(Result.failure(e))
            return
        }
        log.delete()
        onProgress(total, total)
        onComplete(Result.success(saveFile))
    }

    /** Progress for pieces written on several threads: at most every 64 KB or 300 ms, from whichever thread. */
    private class ProgressThrottle(
        private val downloaded: AtomicLong,
        private val total: Long,
        private val onProgress: (Long, Long?) -> Unit,
    ) {
        private var lastBytes = downloaded.get()
        private var lastTime = System.currentTimeMillis()

        fun add(bytes: Int) {
            val now = downloaded.addAndGet(bytes.toLong())
            val time = System.currentTimeMillis()
            val report = synchronized(this) {
                if (now - lastBytes < 64 * 1024 && time - lastTime < 300) return@synchronized false
                lastBytes = now
                lastTime = time
                true
            }
            if (report) onProgress(now, total)
        }
    }

    /** Writes one piece's body at its place in [file]; fails if it ends early or [stop] says so. */
    private fun writePiece(
        source: okio.BufferedSource,
        file: File,
        range: LongRange,
        downloaded: AtomicLong,
        progress: ProgressThrottle,
        stop: () -> Boolean,
    ) {
        val expected = range.last - range.first + 1
        var written = 0L
        val buffer = ByteArray(64 * 1024)
        RandomAccessFile(file, "rw").use { out ->
            out.seek(range.first)
            while (written < expected) {
                if (stop()) return
                val read = source.read(buffer, 0, minOf(buffer.size.toLong(), expected - written).toInt())
                if (read == -1) throw IOException("The piece at ${range.first} ended after $written of $expected bytes")
                out.write(buffer, 0, read)
                written += read
                progress.add(read)
            }
        }
    }

    companion object {
        /** yt-dlp's piece size for YouTube, which serves one long request at about the speed of playback. */
        const val YOUTUBE_CHUNK_BYTES = 10L * 1024 * 1024

        /**
         * YouTube pieces asked for at once. One request goes at about the speed of playback; three together
         * should fill most connections, as yt-dlp's `--concurrent-fragments` does. Whether YouTube limits by
         * address rather than by connection is for a phone to show.
         */
        const val PIECES_AT_ONCE = 3

        /** The file's size from a `Content-Range: bytes a-b/total` header, when it gives one. */
        internal fun contentRangeTotal(header: String?): Long? =
            header?.substringAfterLast('/', "")?.toLongOrNull()?.takeIf { it > 0 }

        /** Where a `Content-Range: bytes a-b/total` answer starts. */
        internal fun contentRangeStart(header: String?): Long? =
            header?.let { Regex("""bytes\s+(\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() }

        /**
         * YouTube's files ([url] on googlevideo.com) are fetched in pieces, as yt-dlp does; anything else in
         * one request. Decided from the address, so a download resumed after the app was closed keeps it.
         */
        fun youTubeChunkBytes(url: String): Long? {
            val host = url.toHttpUrlOrNull()?.host ?: return null
            return YOUTUBE_CHUNK_BYTES.takeIf { host == "googlevideo.com" || host.endsWith(".googlevideo.com") }
        }

        /** The Range header for the piece starting at [from]: [size] bytes, or what is left of [total]. */
        internal fun rangeFrom(from: Long, size: Long, total: Long?): String {
            val end = from + size - 1
            return "bytes=$from-${if (total != null) minOf(end, total - 1) else end}"
        }

        /** Extract filename from `Content-Disposition`; supports RFC 5987 `filename*`. */
        fun parseContentDisposition(header: String?): String? {
            if (header.isNullOrBlank()) return null
            Regex("filename\\*=(?:UTF-8|utf-8)''([^;]+)").find(header)
                ?.groupValues?.get(1)?.let { encoded ->
                    return runCatching { URLDecoder.decode(encoded.trim(), "UTF-8") }
                        .getOrNull()?.takeIf { it.isNotEmpty() }
                }
            return Regex("filename\\s*=\\s*\"?([^\";]+)\"?").find(header)
                ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}
