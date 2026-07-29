package com.example.worktimetracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.worktimetracker.data.entity.AppLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: AppLogEntity): Long

    @Query("SELECT * FROM app_logs ORDER BY time DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<AppLogEntity>>

    @Query("SELECT * FROM app_logs ORDER BY time DESC LIMIT :limit")
    suspend fun latestLogs(limit: Int = 100): List<AppLogEntity>

    @Query("DELETE FROM app_logs")
    suspend fun deleteAll()
}

