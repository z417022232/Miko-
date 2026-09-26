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

    /**
     * 同步读取（仅限低频的广播/通知领取路径：睡眠豁免复核要判断是否正处工作会话）。
     * 业务路径一律用 [getState]。
     */
    @Query("SELECT * FROM work_state WHERE id = 1")
    fun getStateBlocking(): WorkStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(state: WorkStateEntity)
}
