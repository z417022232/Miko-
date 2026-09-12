package com.example.worktimetracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.worktimetracker.data.entity.HolidayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HolidayDao {
    @Query("SELECT * FROM holidays WHERE date BETWEEN :startDate AND :endDate ORDER BY date ASC")
    fun observeHolidays(startDate: String, endDate: String): Flow<List<HolidayEntity>>

    @Query("SELECT * FROM holidays ORDER BY date ASC")
    suspend fun getAll(): List<HolidayEntity>

    @Query("SELECT * FROM holidays WHERE date BETWEEN :startDate AND :endDate ORDER BY date ASC")
    suspend fun getRange(startDate: String, endDate: String): List<HolidayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(holidays: List<HolidayEntity>)

    /** 按年份整段替换前先清空，避免新旧公告混杂（如公告改期后旧补班日残留）。 */
    @Query("DELETE FROM holidays WHERE date BETWEEN :startDate AND :endDate")
    suspend fun deleteRange(startDate: String, endDate: String)

    @Query("DELETE FROM holidays")
    suspend fun deleteAll()
}
