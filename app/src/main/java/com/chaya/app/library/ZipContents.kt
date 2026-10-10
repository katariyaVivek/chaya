package com.chaya.app.library

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** One file inside an account's ZIP. */
data class ZipItem(
    /** The entry's full name inside the ZIP. */
    val path: String,
    /** The name shown, without folders. */
    val name: String,
    val size: Long,
    val kind: FileKind?,
)

/**
 * Reads what an account's ZIP holds, and takes one file out at a time to view, share or save: the ZIP is
 * never unpacked whole.
 */
object ZipContents {

    /** The files in [zip], in the order they were packed; folders and empty names are left out. */
    fun list(zip: File): List<ZipItem> = ZipFile(zip).use { file ->
        file.entries().asSequence()
            .filter { !it.isDirectory }
            .mapNotNull { entry ->
                val name = safeName(entry.name) ?: return@mapNotNull null
                ZipItem(entry.name, name, entry.size.coerceAtLeast(0), kindOf(null, name))
            }
            .toList()
    }

    /**
     * [item] taken out of [zip] into [dir], or the copy already there. The name comes from the entry's last part
     * only, so an entry named "../x" can never land outside [dir].
     */
    fun extract(zip: File, item: ZipItem, dir: File): File {
        val name = safeName(item.path) ?: throw IOException("Unusable name in the ZIP: ${item.path}")
        dir.mkdirs()
        val target = File(dir, name)
        if (target.canonicalFile.parentFile != dir.canonicalFile) throw IOException("Unusable name in the ZIP: ${item.path}")
        if (target.isFile && target.length() == item.size) return target
        ZipFile(zip).use { file ->
            val entry = file.getEntry(item.path) ?: throw IOException("${item.name} is not in the ZIP")
            val partial = File(dir, "$name.part")
            file.getInputStream(entry).use { input -> partial.outputStream().use { input.copyTo(it) } }
            if (!partial.renameTo(target)) {
                target.delete()
                if (!partial.renameTo(target)) throw IOException("Couldn't keep ${item.name}")
            }
        }
        return target
    }

    /** The last part of an entry's name, or null when nothing usable is left. */
    internal fun safeName(path: String): String? =
        path.replace('\\', '/').substringAfterLast('/').trim()
            .takeIf { it.isNotEmpty() && it != "." && it != ".." }
}
