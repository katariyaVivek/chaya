package com.chaya.app.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A page that finished loading, once per address: the same address seen again updates its row. */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val url: String,
    val title: String,
    /** When it was last seen, in milliseconds. */
    val visitedAt: Long,
    val visits: Int,
)

/** A page the person starred. */
@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val url: String,
    val title: String,
    val createdAt: Long,
)

@Dao
abstract class HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC")
    abstract fun all(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE url = :url")
    abstract suspend fun get(url: String): HistoryEntity?

    /** Pages whose address or title holds [query], most recent first. */
    @Query(
        "SELECT * FROM history WHERE url LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' " +
            "ORDER BY visitedAt DESC LIMIT :limit",
    )
    abstract suspend fun matching(query: String, limit: Int): List<HistoryEntity>

    @Insert
    protected abstract suspend fun insert(row: HistoryEntity)

    /** One more visit to [url]; a blank title keeps the one already known. Returns how many rows changed. */
    @Query(
        "UPDATE history SET title = CASE WHEN :title = '' THEN title ELSE :title END, " +
            "visitedAt = :at, visits = visits + 1 WHERE url = :url",
    )
    protected abstract suspend fun bump(url: String, title: String, at: Long): Int

    /** Records a visit: a new row the first time, the same row updated after that. */
    @Transaction
    open suspend fun recordVisit(url: String, title: String, at: Long) {
        if (bump(url, title, at) == 0) insert(HistoryEntity(url, title, at, visits = 1))
    }

    @Query("DELETE FROM history WHERE url = :url")
    abstract suspend fun delete(url: String)

    /** Deletes what was seen at or after [since]: *Clear history* for the last hour or day. */
    @Query("DELETE FROM history WHERE visitedAt >= :since")
    abstract suspend fun deleteSince(since: Long)

    @Query("DELETE FROM history")
    abstract suspend fun deleteAll()
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun all(): Flow<List<BookmarkEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    fun isBookmarked(url: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    suspend fun contains(url: String): Boolean

    /** Bookmarks whose address or title holds [query], newest first. */
    @Query(
        "SELECT * FROM bookmarks WHERE url LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' " +
            "ORDER BY createdAt DESC LIMIT :limit",
    )
    suspend fun matching(query: String, limit: Int): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun delete(url: String)
}
