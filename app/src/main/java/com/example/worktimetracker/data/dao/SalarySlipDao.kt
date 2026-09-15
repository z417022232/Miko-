package com.example.worktimetracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.worktimetracker.data.entity.SalarySlipEntity
import com.example.worktimetracker.data.entity.SalarySlipItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * 工资条（DB v13）的数据访问。
 *
 * ⚠️ 本 DAO **不写** `monthly_salaries` / `work_records` —— 只读它们做兼容校验，
 * 用户的原始工资与工时记录一律只读。
 */
@Dao
interface SalarySlipDao {

    // ----------------------------------------------------------- 主表

    @Query("SELECT * FROM salary_slips ORDER BY payrollMonth ASC")
    fun observeSlips(): Flow<List<SalarySlipEntity>>

    @Query("SELECT * FROM salary_slips ORDER BY payrollMonth ASC")
    suspend fun allSlips(): List<SalarySlipEntity>

    @Query("SELECT * FROM salary_slips WHERE payrollMonth = :payrollMonth LIMIT 1")
    suspend fun slip(payrollMonth: String): SalarySlipEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSlip(row: SalarySlipEntity)

    @Query("DELETE FROM salary_slips WHERE payrollMonth = :payrollMonth")
    suspend fun deleteSlip(payrollMonth: String)

    @Query("SELECT COUNT(*) FROM salary_slips")
    suspend fun slipCount(): Int

    // ----------------------------------------------------------- 分项

    @Query("SELECT * FROM salary_slip_items WHERE payrollMonth = :payrollMonth ORDER BY itemKey ASC")
    fun observeItems(payrollMonth: String): Flow<List<SalarySlipItemEntity>>

    @Query("SELECT * FROM salary_slip_items WHERE payrollMonth = :payrollMonth ORDER BY itemKey ASC")
    suspend fun items(payrollMonth: String): List<SalarySlipItemEntity>

    @Query("SELECT * FROM salary_slip_items ORDER BY payrollMonth ASC, itemKey ASC")
    suspend fun allItems(): List<SalarySlipItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveItem(row: SalarySlipItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveItems(rows: List<SalarySlipItemEntity>)

    @Query("DELETE FROM salary_slip_items WHERE payrollMonth = :payrollMonth")
    suspend fun deleteItems(payrollMonth: String)

    @Query("SELECT COUNT(*) FROM salary_slip_items")
    suspend fun itemCount(): Int
}
