package com.chaya.app.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.chaya.app.download.DownloadState
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY updated_at DESC")
    fun getAllFlow(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY updated_at DESC")
    suspend fun getAllOnce(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadEntity?

    @Query("SELECT COALESCE(MAX(id), 0) FROM downloads")
    suspend fun getMaxId(): Long

    @Query("UPDATE downloads SET fileName = :name, file_path = :path, updated_at = :now WHERE id = :id")
    suspend fun updateNameAndPath(
        id: Long,
        name: String,
        path: String,
        now: Long = System.currentTimeMillis()
    )

    /** Ids are explicit — REPLACE lets us upsert rows safely. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: DownloadEntity)

    @Update
    suspend fun update(task: DownloadEntity)

    @Query("UPDATE downloads SET downloaded_bytes = :bytes, total_bytes = :total, updated_at = :now WHERE id = :id")
    suspend fun updateProgress(id: Long, bytes: Long, total: Long?, now: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET state = :state, updated_at = :now WHERE id = :id")
    suspend fun updateState(id: Long, state: DownloadState, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()
}
