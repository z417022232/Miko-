package com.example.worktimetracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.worktimetracker.data.entity.LocationLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: LocationLogEntity): Long

    @Query("SELECT * FROM location_logs WHERE time BETWEEN :startTime AND :endTime ORDER BY time ASC")
    fun observeLogs(startTime: Long, endTime: Long): Flow<List<LocationLogEntity>>

    @Query("SELECT * FROM location_logs WHERE time BETWEEN :startTime AND :endTime ORDER BY time ASC")
    suspend fun getLogs(startTime: Long, endTime: Long): List<LocationLogEntity>

    @Query("SELECT * FROM location_logs WHERE time < :time ORDER BY time DESC LIMIT 1")
    suspend fun latestBefore(time: Long): LocationLogEntity?

    @Query("SELECT * FROM location_logs ORDER BY time DESC LIMIT 1")
    suspend fun latest(): LocationLogEntity?

    @Query("SELECT COUNT(*) FROM location_logs WHERE time = :time")
    suspend fun countByTime(time: Long): Int

    @Query("DELETE FROM location_logs")
    suspend fun deleteAll()
}
