package com.chaya.app.download

/**
 * Pairs finished downloads that lost their file with the files in the download folder that no download claims, by
 * size. A pair is made only when it cannot be another: the size belongs to one such download and one such file, so
 * a video is never given someone else's file.
 */
internal object LooseFiles {

    /** [missing] (download id to its size in bytes) and [loose] (path to size): the download each file goes back to. */
    fun match(missing: Map<Long, Long>, loose: Map<String, Long>): Map<Long, String> {
        val downloadsBySize = missing.entries.groupBy({ it.value }, { it.key })
        val filesBySize = loose.entries.groupBy({ it.value }, { it.key })
        val out = HashMap<Long, String>()
        for ((size, ids) in downloadsBySize) {
            if (size <= 0) continue
            val files = filesBySize[size] ?: continue
            if (ids.size == 1 && files.size == 1) out[ids.single()] = files.single()
        }
        return out
    }
}
