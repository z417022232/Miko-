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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(holidays: List<HolidayEntity>)

    @Query("DELETE FROM holidays")
    suspend fun deleteAll()
}
