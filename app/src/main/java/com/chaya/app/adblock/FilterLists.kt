package com.chaya.app.adblock

import android.content.Context
import android.content.SharedPreferences
import com.chaya.app.adblock.engine.DomainResolver
import com.chaya.app.adblock.engine.FilterEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * The ad lists Chaya blocks with: EasyList (ads) and EasyPrivacy (trackers), the two lists uBlock Origin starts
 * with besides its own. A copy of each ships inside the app, so blocking works from the first page and offline.
 * About once a week Chaya fetches fresh copies straight from easylist.to; a fetch sends nothing about the person
 * (no cookies, no history) and a copy that does not look like the list is thrown away.
 */
class FilterLists(
    /** Where fetched copies are kept; a fetched copy is used instead of the one in the app. */
    private val dir: File,
    /** Opens the copy shipped in the app, by file name. */
    private val openBundled: (String) -> InputStream,
    private val prefs: SharedPreferences,
    private val client: OkHttpClient = defaultClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    val sources: List<Source> = DEFAULT_SOURCES,
) {
    /** One list: [fileName] in the app's assets under `adblock/` and in [dir], fetched from [url]. */
    data class Source(val title: String, val fileName: String, val url: String)

    /** Reads every list, the fetched copy where there is one, into a new engine. Slow (a second or two): not on the main thread. */
    fun buildEngine(resolver: DomainResolver): FilterEngine {
        val builder = FilterEngine.Builder(resolver)
        for (source in sources) {
            val fetched = File(dir, source.fileName)
            val stream = if (fetched.isFile) fetched.inputStream() else openBundled(source.fileName)
            stream.bufferedReader().useLines { builder.addAll(it) }
        }
        return builder.build()
    }

    /** When the lists were last fetched, or null while the copies shipped in the app are in use. */
    fun lastUpdated(): Long? = prefs.getLong(KEY_UPDATED, 0L).takeIf { it > 0 }

    /** Whether it is time to fetch: a week since the last fetch, and not within hours of a failed try. */
    fun isUpdateDue(): Boolean {
        val now = clock()
        val updated = prefs.getLong(KEY_UPDATED, 0L)
        val tried = prefs.getLong(KEY_TRIED, 0L)
        return now - updated >= UPDATE_EVERY_MILLIS && now - tried >= RETRY_AFTER_MILLIS
    }

    /**
     * Fetches fresh copies of every list when [isUpdateDue]. Returns true when new copies were saved, so the
     * engine should be rebuilt; false when nothing was due or the fetch failed (the copies in use stay).
     */
    suspend fun updateIfDue(): Boolean = withContext(Dispatchers.IO) {
        if (!isUpdateDue()) return@withContext false
        prefs.edit().putLong(KEY_TRIED, clock()).apply()
        dir.mkdirs()
        // Every list is fetched before any is replaced: a list from this week beside one from last week could
        // disagree about their exceptions.
        val fetched = ArrayList<Pair<Source, File>>()
        try {
            for (source in sources) fetched += source to fetch(source)
        } catch (e: CancellationException) {
            fetched.forEach { it.second.delete() }
            throw e
        } catch (e: Exception) {
            fetched.forEach { it.second.delete() }
            return@withContext false
        }
        val saved = fetched.all { (source, temp) -> temp.renameTo(File(dir, source.fileName)) }
        fetched.forEach { it.second.delete() }
        if (saved) prefs.edit().putLong(KEY_UPDATED, clock()).apply()
        saved
    }

    private fun fetch(source: Source): File {
        val request = Request.Builder().url(source.url).header("Cache-Control", "no-cache").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${source.fileName}")
            val temp = File(dir, source.fileName + ".download")
            response.body?.byteStream()?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                ?: throw IOException("Empty response for ${source.fileName}")
            if (!looksLikeList(temp)) {
                temp.delete()
                throw IOException("${source.fileName} is not an ad list")
            }
            return temp
        }
    }

    companion object {
        const val KEY_UPDATED = "adblock_lists_updated"
        const val KEY_TRIED = "adblock_lists_tried"
        const val UPDATE_EVERY_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val RETRY_AFTER_MILLIS = 6L * 60 * 60 * 1000

        /** Fewer rules than this means a cut-off or wrong file, not a real copy of the list. */
        const val MIN_RULES = 1_000

        val DEFAULT_SOURCES = listOf(
            Source("EasyList", "easylist.txt", "https://easylist.to/easylist/easylist.txt"),
            Source("EasyPrivacy", "easyprivacy.txt", "https://easylist.to/easylist/easyprivacy.txt"),
        )

        fun from(context: Context) = FilterLists(
            dir = File(context.filesDir, "adblock"),
            openBundled = { name -> context.assets.open("adblock/$name") },
            prefs = context.getSharedPreferences("chaya_prefs", Context.MODE_PRIVATE),
        )

        /** An Adblock Plus list: its header, then at least [MIN_RULES] lines. */
        internal fun looksLikeList(file: File): Boolean {
            val header = file.bufferedReader().use { it.readLine() }.orEmpty()
            if (!header.startsWith("[Adblock Plus")) return false
            return file.bufferedReader().useLines { lines -> lines.take(MIN_RULES + 1).count() > MIN_RULES }
        }

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

/** Sites by the public suffix list OkHttp carries: www.bbc.co.uk → bbc.co.uk. */
object PublicSuffixSites : DomainResolver {
    override fun siteOf(host: String): String =
        "https://$host/".toHttpUrlOrNull()?.topPrivateDomain() ?: host
}
