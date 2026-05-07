package com.chaya.app.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY updated_at DESC")
    fun getAllFlow(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY updated_at DESC")
    suspend fun getAllOnce(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: DownloadEntity): Long

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
