package com.chaya.app.download

import java.io.File

/**
 * Which pieces of a file fetched several pieces at a time are on disk, kept beside it as `<file>.pieces`.
 * Pieces arrive out of order, so the file can have gaps and its length says nothing; a resume reads this
 * instead and asks only for the pieces still missing.
 *
 * Written before the first byte of the file, and again each time a piece is whole, so a file with gaps always
 * has one. Two lines: "<piece size> <total>", then the finished pieces' numbers, comma separated.
 */
internal class PieceLog private constructor(
    private val record: File,
    val pieceBytes: Long,
    total: Long?,
    done: Set<Int>,
) {
    /** The file's size, once a server has said it. */
    @Volatile
    var total: Long? = total
        private set

    private val finished = done.toMutableSet()

    /** The numbers of the pieces on disk. */
    val done: Set<Int> get() = synchronized(this) { finished.toSet() }

    /** How many pieces [total] makes. */
    fun count(total: Long): Int = ((total + pieceBytes - 1) / pieceBytes).toInt()

    /** The bytes piece [index] covers, given the file's [total]. */
    fun range(index: Int, total: Long): LongRange {
        val start = index * pieceBytes
        return start until minOf(start + pieceBytes, total)
    }

    /** The first piece not on disk, or the last one when all are (it is fetched again, harmlessly). */
    fun firstMissing(): Int = synchronized(this) {
        val total = total
        val count = total?.let(::count)
        val first = generateSequence(0) { it + 1 }.first { it !in finished }
        if (count != null && first >= count) count - 1 else first
    }

    /**
     * The server has said the file is [total] bytes. A different size from the one recorded means a different
     * file: nothing on disk counts. Without a size recorded, the pieces counted from a gapless start stay.
     * Saved before any byte is written.
     */
    fun begin(total: Long) = synchronized(this) {
        if (this.total != null && this.total != total) finished.clear()
        finished.retainAll(0 until count(total))
        this.total = total
        save()
    }

    /** Bytes in the pieces on disk. */
    fun doneBytes(): Long = synchronized(this) {
        val total = total ?: return@synchronized finished.size * pieceBytes
        finished.sumOf { range(it, total).let { r -> r.last - r.first + 1 } }
    }

    fun markDone(index: Int) = synchronized(this) {
        if (finished.add(index)) save()
    }

    fun isComplete(): Boolean = synchronized(this) {
        val total = total ?: return@synchronized false
        (0 until count(total)).all { it in finished }
    }

    fun delete() {
        record.delete()
    }

    private fun save() {
        val text = "$pieceBytes ${total ?: -1}\n${finished.sorted().joinToString(",")}\n"
        val temp = File(record.path + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(record)) {
            record.delete()
            temp.renameTo(record)
        }
    }

    companion object {
        const val SUFFIX = ".pieces"

        fun fileFor(saveFile: File) = File(saveFile.path + SUFFIX)

        /**
         * The record for [saveFile], fetched in [pieceBytes] pieces. Without a usable one (none, unreadable,
         * another piece size, or no file beside it), the pieces wholly inside the first [fromBytes] bytes count
         * as done: a download fetched one piece after another before stopping has no gaps.
         */
        fun load(saveFile: File, pieceBytes: Long, fromBytes: Long): PieceLog {
            val record = fileFor(saveFile)
            read(record, saveFile, pieceBytes)?.let { (total, done) -> return PieceLog(record, pieceBytes, total, done) }
            record.delete()
            val prefix = minOf(fromBytes, if (saveFile.exists()) saveFile.length() else 0L).coerceAtLeast(0)
            return PieceLog(record, pieceBytes, null, (0 until (prefix / pieceBytes).toInt()).toSet())
        }

        /**
         * Bytes of [saveFile] actually downloaded: the finished pieces when it is being fetched in pieces,
         * else its length.
         */
        fun bytesOnDisk(saveFile: File): Long {
            if (!saveFile.exists()) return 0L
            val record = fileFor(saveFile)
            val text = runCatching { record.readText() }.getOrNull() ?: return saveFile.length()
            val pieceBytes = text.lineSequence().firstOrNull()?.split(' ')?.firstOrNull()?.toLongOrNull()
                ?: return saveFile.length()
            val (total, done) = read(record, saveFile, pieceBytes) ?: return saveFile.length()
            return PieceLog(record, pieceBytes, total, done).doneBytes()
        }

        private fun read(record: File, saveFile: File, pieceBytes: Long): Pair<Long?, Set<Int>>? {
            if (!record.exists() || !saveFile.exists()) return null
            val lines = runCatching { record.readLines() }.getOrNull() ?: return null
            val head = lines.firstOrNull()?.split(' ') ?: return null
            if (head.size != 2 || head[0].toLongOrNull() != pieceBytes) return null
            val total = head[1].toLongOrNull()?.takeIf { it > 0 }
            val done = lines.getOrNull(1).orEmpty().split(',').filter { it.isNotBlank() }
                .map { it.trim().toIntOrNull() ?: return null }
                .filter { it >= 0 }
                .toSet()
            return total to done
        }
    }
}
