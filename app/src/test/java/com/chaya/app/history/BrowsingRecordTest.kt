package com.chaya.app.history

import android.content.Context
import androidx.room.Room
import com.chaya.app.database.ChayaDatabase
import com.chaya.app.database.HistoryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** History and bookmarks against a real Room database in memory. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowsingRecordTest {

    private lateinit var db: ChayaDatabase
    private lateinit var record: BrowsingRecord
    private var now = 1_800_000_000_000L
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, ChayaDatabase::class.java).allowMainThreadQueries().build()
        val prefs = context.getSharedPreferences("browsing-test", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        record = BrowsingRecord(db.historyDao(), db.bookmarkDao(), prefs, clock = { now })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun history() = record.historyRows.first()

    @Test
    fun `the same address seen again updates its row, newest first`() = runBlocking {
        record.visited("https://news.example/a", "A story")
        now += hour
        record.visited("https://weather.example/", "Weather")
        now += hour
        record.visited("https://news.example/a", "A story, updated")

        val rows = history()
        assertEquals(listOf("https://news.example/a", "https://weather.example/"), rows.map { it.url })
        assertEquals(2, rows[0].visits)
        assertEquals("A story, updated", rows[0].title)
        assertEquals(now, rows[0].visitedAt)
    }

    @Test
    fun `a visit with no title keeps the title already known`() = runBlocking {
        record.visited("https://news.example/a", "A story")
        record.visited("https://news.example/a", "")

        assertEquals("A story", history().single().title)
    }

    @Test
    fun `only web pages are kept, and nothing while recording is off`() = runBlocking {
        record.visited("about:blank", "")
        record.visited("data:text/html,hi", "")
        assertEquals(emptyList<HistoryEntity>(), history())

        record.setRecording(false)
        record.visited("https://news.example/a", "A story")
        assertEquals(emptyList<HistoryEntity>(), history())
        assertFalse(record.recording.value)

        record.setRecording(true)
        record.visited("https://news.example/a", "A story")
        assertEquals(1, history().size)
    }

    @Test
    fun `the star adds a bookmark and takes it away again`() = runBlocking {
        val url = "https://news.example/a"
        assertFalse(record.isBookmarked(url).first())

        assertTrue(record.toggleBookmark(url, "A story"))
        assertTrue(record.isBookmarked(url).first())
        assertEquals("A story", record.bookmarkRows.first().single().title)

        assertFalse(record.toggleBookmark(url, "A story"))
        assertFalse(record.isBookmarked(url).first())
        assertTrue(record.bookmarkRows.first().isEmpty())
    }

    @Test
    fun `suggestions put bookmarks first, then history by visits and how recent, no address twice`() = runBlocking {
        // Seen often but a while ago; seen once just now; seen once long ago.
        repeat(6) { record.visited("https://news.example/often", "News often") }
        now += 2 * day
        record.visited("https://news.example/recent", "News recent")
        now += hour
        record.visited("https://weather.example/", "Weather")
        record.toggleBookmark("https://news.example/starred", "Starred news")
        record.visited("https://news.example/starred", "Starred news")

        val urls = record.suggestions("news").map { it.url }

        assertEquals(
            listOf("https://news.example/starred", "https://news.example/often", "https://news.example/recent"),
            urls,
        )
        assertTrue(record.suggestions("news").first().bookmarked)
        assertEquals(emptyList<Suggestion>(), record.suggestions("  "))
    }

    @Test
    fun `at equal visits the more recent page ranks higher, and at equal age the more visited`() {
        val rows = listOf(
            HistoryEntity("https://a.example/", "A", visitedAt = now - 3 * day, visits = 2),
            HistoryEntity("https://b.example/", "B", visitedAt = now - hour, visits = 2),
            HistoryEntity("https://c.example/", "C", visitedAt = now - hour, visits = 5),
        )

        assertEquals(listOf("C", "B", "A"), BrowsingRecord.rank(rows, now).map { it.title })
    }

    @Test
    fun `clearing the last hour, the last day, or everything removes just those pages`() = runBlocking {
        record.visited("https://old.example/", "Old")
        now += 2 * day
        record.visited("https://yesterday.example/", "Yesterday")
        now += 20 * hour
        record.visited("https://hour.example/", "This hour")

        record.clearHistory(ClearRange.LAST_HOUR)
        assertEquals(listOf("https://yesterday.example/", "https://old.example/"), history().map { it.url })

        record.clearHistory(ClearRange.LAST_DAY)
        assertEquals(listOf("https://old.example/"), history().map { it.url })

        record.clearHistory(ClearRange.EVERYTHING)
        assertTrue(history().isEmpty())
    }

    @Test
    fun `one page can be deleted from history`() = runBlocking {
        record.visited("https://a.example/", "A")
        record.visited("https://b.example/", "B")

        record.removeFromHistory("https://a.example/")

        assertEquals(listOf("https://b.example/"), history().map { it.url })
    }
}
