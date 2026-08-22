package com.example.worktimetracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.worktimetracker.data.entity.ManualOverrideEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ManualOverrideDao {
    @Query("SELECT * FROM manual_overrides WHERE recordId = :recordId ORDER BY modifiedAt DESC")
    fun observeForRecord(recordId: Long): Flow<List<ManualOverrideEntity>>

    @Query("SELECT COUNT(*) FROM manual_overrides WHERE recordId = :recordId")
    suspend fun countForRecord(recordId: Long): Int

    @Query("SELECT * FROM manual_overrides WHERE recordId = :recordId ORDER BY modifiedAt DESC LIMIT 1")
    suspend fun latestForRecord(recordId: Long): ManualOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(override: ManualOverrideEntity): Long

    @Query("DELETE FROM manual_overrides")
    suspend fun deleteAll()
}
