package com.example.worktimetracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.worktimetracker.data.entity.WorkStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkStateDao {
    @Query("SELECT * FROM work_state WHERE id = 1")
    fun observeState(): Flow<WorkStateEntity?>

    @Query("SELECT * FROM work_state WHERE id = 1")
    suspend fun getState(): WorkStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(state: WorkStateEntity)
}
