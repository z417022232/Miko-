package com.example.worktimetracker.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.worktimetracker.data.entity.WorkRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkRecordDao {
    @Query("SELECT * FROM work_records WHERE workDate BETWEEN :startDate AND :endDate ORDER BY workDate ASC")
    fun observeMonthRecords(startDate: String, endDate: String): Flow<List<WorkRecordEntity>>

    @Query("SELECT * FROM work_records WHERE workDate = :date LIMIT 1")
    suspend fun getByDate(date: String): WorkRecordEntity?

    @Query("SELECT * FROM work_records WHERE workDate BETWEEN :startDate AND :endDate ORDER BY workDate ASC")
    suspend fun getMonthRecords(startDate: String, endDate: String): List<WorkRecordEntity>

    @Query("SELECT * FROM work_records WHERE isManual = 0 AND startTime IS NOT NULL AND endTime IS NOT NULL AND needsReview = 0 AND status = 'WORK' ORDER BY startTime DESC LIMIT 14")
    suspend fun latestValidForLearning(): List<WorkRecordEntity>

    @Query("SELECT COALESCE(MAX(updatedAt), 0) FROM work_records WHERE isManual = 0 AND startTime IS NOT NULL AND endTime IS NOT NULL AND needsReview = 0 AND status = 'WORK'")
    suspend fun learningRevision(): Long

    @Query("SELECT * FROM work_records WHERE isManual = 0 AND startTime IS NOT NULL ORDER BY startTime DESC LIMIT 31")
    suspend fun recentAutomaticRecordsForRepair(): List<WorkRecordEntity>

    @Query("SELECT * FROM work_records WHERE isManual = 0 AND needsReview = 1 AND endTime IS NULL AND note = :note ORDER BY workDate ASC")
    suspend fun incompleteFallbackRecords(note: String): List<WorkRecordEntity>

    @Query("SELECT finalMinutes FROM work_records WHERE isManual = 1 AND finalMinutes > 0 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestManualFinalMinutes(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: WorkRecordEntity): Long

    @Update
    suspend fun update(record: WorkRecordEntity)

    @Delete
    suspend fun delete(record: WorkRecordEntity)

    @Query("DELETE FROM work_records")
    suspend fun deleteAll()
}

