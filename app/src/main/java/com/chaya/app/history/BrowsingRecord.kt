package com.chaya.app.history

import android.content.Context
import android.content.SharedPreferences
import com.chaya.app.database.BookmarkDao
import com.chaya.app.database.BookmarkEntity
import com.chaya.app.database.ChayaDatabase
import com.chaya.app.database.HistoryDao
import com.chaya.app.database.HistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A match under the address bar while typing: a bookmark, or a page from history. */
data class Suggestion(val url: String, val title: String, val bookmarked: Boolean)

/** How far back *Clear history* goes. */
enum class ClearRange(val label: String, val millis: Long?) {
    LAST_HOUR("Last hour", 60L * 60 * 1000),
    LAST_DAY("Last day", 24L * 60 * 60 * 1000),
    EVERYTHING("Everything", null),
}

/**
 * The browser's history and bookmarks, kept on the phone only. Each page that finishes loading is recorded
 * (one row per address, its visits counted) while recording is on, which it is unless the person turns it off
 * on the History screen.
 */
class BrowsingRecord(
    private val history: HistoryDao,
    private val bookmarks: BookmarkDao,
    private val prefs: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _recording = MutableStateFlow(prefs.getBoolean(KEY_RECORDING, true))
    /** Whether pages are added to history as they load. */
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    fun setRecording(on: Boolean) {
        prefs.edit().putBoolean(KEY_RECORDING, on).apply()
        _recording.value = on
    }

    val historyRows: Flow<List<HistoryEntity>> get() = history.all()
    val bookmarkRows: Flow<List<BookmarkEntity>> get() = bookmarks.all()

    fun isBookmarked(url: String): Flow<Boolean> = bookmarks.isBookmarked(url)

    /** A page finished loading. Only web pages are kept, and nothing while recording is off. */
    suspend fun visited(url: String, title: String) {
        if (!_recording.value || !isWebPage(url)) return
        history.recordVisit(url, title.trim(), clock())
    }

    /** Stars or unstars [url]; returns whether it is a bookmark now. */
    suspend fun toggleBookmark(url: String, title: String): Boolean {
        if (!isWebPage(url)) return false
        return if (bookmarks.contains(url)) {
            bookmarks.delete(url)
            false
        } else {
            bookmarks.put(BookmarkEntity(url, title.trim(), clock()))
            true
        }
    }

    suspend fun removeBookmark(url: String) = bookmarks.delete(url)

    suspend fun removeFromHistory(url: String) = history.delete(url)

    suspend fun clearHistory(range: ClearRange) {
        val millis = range.millis
        if (millis == null) history.deleteAll() else history.deleteSince(clock() - millis)
    }

    /**
     * What to offer under the address bar for [typed]: bookmarks first, then history ranked by how often and how
     * recently each page was seen, no address twice.
     */
    suspend fun suggestions(typed: String, limit: Int = SUGGESTIONS): List<Suggestion> {
        val query = typed.trim()
        if (query.isEmpty()) return emptyList()
        val starred = bookmarks.matching(query, limit).map { Suggestion(it.url, it.title, bookmarked = true) }
        val seen = starred.map { it.url }.toSet()
        val pages = rank(history.matching(query, CANDIDATES), clock())
            .filter { it.url !in seen }
            .map { Suggestion(it.url, it.title, bookmarked = false) }
        return (starred + pages).take(limit)
    }

    companion object {
        const val KEY_RECORDING = "history_recording"
        const val SUGGESTIONS = 6
        private const val CANDIDATES = 50
        private const val DAY_MILLIS = 24.0 * 60 * 60 * 1000

        fun isWebPage(url: String) = url.startsWith("https://") || url.startsWith("http://")

        /** Most visited and most recent first: visits, worth less for every day since the last one. */
        internal fun rank(rows: List<HistoryEntity>, now: Long): List<HistoryEntity> =
            rows.sortedByDescending { it.visits / (1.0 + (now - it.visitedAt).coerceAtLeast(0) / DAY_MILLIS) }

        fun from(context: Context, database: ChayaDatabase) = BrowsingRecord(
            database.historyDao(),
            database.bookmarkDao(),
            context.getSharedPreferences("chaya_prefs", Context.MODE_PRIVATE),
        )
    }
}
