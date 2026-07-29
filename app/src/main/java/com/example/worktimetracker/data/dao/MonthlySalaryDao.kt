package com.example.worktimetracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.worktimetracker.data.entity.MonthlySalaryEntity

@Dao
interface MonthlySalaryDao {
    @Query("SELECT * FROM monthly_salaries WHERE month = :month LIMIT 1")
    suspend fun get(month: String): MonthlySalaryEntity?

    @Query("SELECT * FROM monthly_salaries WHERE payrollMonth = :payrollMonth LIMIT 1")
    suspend fun getForPayrollMonth(payrollMonth: String): MonthlySalaryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(value: MonthlySalaryEntity)

    @Query("DELETE FROM monthly_salaries")
    suspend fun deleteAll()
}
