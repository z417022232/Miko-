package com.example.worktimetracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.worktimetracker.data.entity.WorkSegmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkSegmentDao {
    @Query("SELECT * FROM work_segments WHERE recordId = :recordId ORDER BY startTime ASC")
    fun observeForRecord(recordId: Long): Flow<List<WorkSegmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(segments: List<WorkSegmentEntity>)

    @Query("DELETE FROM work_segments WHERE recordId = :recordId")
    suspend fun deleteForRecord(recordId: Long)

    @Query("DELETE FROM work_segments")
    suspend fun deleteAll()
}
