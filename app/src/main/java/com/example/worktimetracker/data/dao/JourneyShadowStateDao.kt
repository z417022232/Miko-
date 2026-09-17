package com.example.worktimetracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.worktimetracker.data.entity.JourneyShadowStateEntity

/** 新行程状态机影子快照的唯一数据入口。 */
@Dao
interface JourneyShadowStateDao {
    @Query("SELECT * FROM journey_shadow_state WHERE id = 1 LIMIT 1")
    suspend fun get(): JourneyShadowStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: JourneyShadowStateEntity)

    @Query("DELETE FROM journey_shadow_state")
    suspend fun clear()
}
