package com.chaya.app.browser

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream

/**
 * The open tabs on disk, so they come back after Android closes the app: a small JSON index (order, address,
 * title, the tab shown), and per tab its WebView state (back and forward history, `<id>.state`), its last
 * thumbnail (`<id>.webp`) and its site's icon (`<id>.icon`). Everything stays in the app's private files;
 * closing a tab deletes its files, and [clear] deletes them all.
 *
 * Plain Kotlin and files only, so it can be tested anywhere; turning a WebView's state into bytes is the
 * caller's part.
 */
class TabStore(private val dir: File) {

    /** One tab as saved: [url] is empty for the start screen. */
    data class SavedTab(val id: Long, val url: String, val title: String)

    data class Saved(val tabs: List<SavedTab>, val activeId: Long)

    private val index = File(dir, "index.json")

    /** The tabs saved last, or null when there are none or the index cannot be read. */
    @Synchronized
    fun load(): Saved? {
        if (!index.isFile) return null
        return runCatching {
            val json = JSONObject(index.readText())
            val list = json.getJSONArray("tabs")
            val tabs = (0 until list.length()).map { i ->
                val tab = list.getJSONObject(i)
                SavedTab(tab.getLong("id"), tab.optString("url"), tab.optString("title"))
            }
            Saved(tabs, json.optLong("active", tabs.firstOrNull()?.id ?: -1L))
        }.getOrNull()?.takeIf { it.tabs.isNotEmpty() }
    }

    /** Saves the list of tabs, written whole or not at all; files of tabs no longer listed are deleted. */
    @Synchronized
    fun save(saved: Saved) {
        dir.mkdirs()
        val json = JSONObject()
            .put("active", saved.activeId)
            .put("tabs", JSONArray().apply {
                saved.tabs.forEach { put(JSONObject().put("id", it.id).put("url", it.url).put("title", it.title)) }
            })
        val temp = File(dir, "index.json.tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(index)) {
            index.delete()
            temp.renameTo(index)
        }
        val kept = saved.tabs.map { it.id.toString() }.toSet()
        dir.listFiles()?.forEach { file ->
            val id = file.name.substringBefore('.')
            if (file != index && id.toLongOrNull() != null && id !in kept) file.delete()
        }
    }

    /** A tab's WebView state, as [writeState] stored it; null when there is none. */
    fun readState(id: Long): ByteArray? = File(dir, "$id.state").takeIf { it.isFile }?.readBytes()

    fun writeState(id: Long, bytes: ByteArray) = writeAtomically(File(dir, "$id.state")) { it.write(bytes) }

    /** Where a tab's thumbnail is kept (it may not exist yet). */
    fun thumbnail(id: Long): File = File(dir, "$id.webp")

    fun writeThumbnail(id: Long, write: (OutputStream) -> Unit) = writeAtomically(thumbnail(id), write)

    /** Where a tab's site icon is kept (it may not exist yet). */
    fun icon(id: Long): File = File(dir, "$id.icon")

    fun writeIcon(id: Long, write: (OutputStream) -> Unit) = writeAtomically(icon(id), write)

    /** Deletes one tab's files, when it is closed. */
    fun remove(id: Long) {
        listOf("state", "webp", "icon").forEach { File(dir, "$id.$it").delete() }
    }

    /** Deletes every tab's files and the index: *Close all tabs*. */
    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun writeAtomically(target: File, write: (OutputStream) -> Unit) {
        dir.mkdirs()
        val temp = File(dir, target.name + ".tmp")
        try {
            temp.outputStream().use(write)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) temp.delete()
            }
        } catch (e: Exception) {
            temp.delete()
        }
    }
}
