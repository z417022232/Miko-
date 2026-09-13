package com.example.worktimetracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.worktimetracker.data.entity.MonthlyPayParamsEntity
import com.example.worktimetracker.data.entity.PayRateSegmentEntity
import kotlinx.coroutines.flow.Flow

/**
 * 计薪规则 v2 的数据访问（DB v12）。
 *
 * ⚠️ 本 DAO **不碰** `monthly_salaries` / `work_records` —— 用户的真实工资与工时记录
 * 一律只读，推算结果永不落库。
 */
@Dao
interface PayrollDao {

    // ------------------------------------------------------- 分段常量

    @Query("SELECT * FROM pay_rate_segments ORDER BY paramKey ASC, effectiveFrom ASC")
    fun observeRateSegments(): Flow<List<PayRateSegmentEntity>>

    @Query("SELECT * FROM pay_rate_segments ORDER BY paramKey ASC, effectiveFrom ASC")
    suspend fun rateSegments(): List<PayRateSegmentEntity>

    @Query("SELECT COUNT(*) FROM pay_rate_segments")
    suspend fun segmentCount(): Int

    /** 同一 (paramKey, effectiveFrom) 已存在时覆盖（unique index + REPLACE）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRateSegment(row: PayRateSegmentEntity): Long

    @Query("DELETE FROM pay_rate_segments WHERE id = :id")
    suspend fun deleteRateSegment(id: Long)

    @Query("DELETE FROM pay_rate_segments WHERE paramKey = :paramKey")
    suspend fun deleteSegmentsOf(paramKey: String)

    @Query("DELETE FROM pay_rate_segments")
    suspend fun deleteAllSegments()

    // ------------------------------------------------------- 月度参数

    @Query("SELECT * FROM monthly_pay_params WHERE payrollMonth = :payrollMonth LIMIT 1")
    suspend fun payParams(payrollMonth: String): MonthlyPayParamsEntity?

    @Query("SELECT * FROM monthly_pay_params ORDER BY payrollMonth ASC")
    fun observePayParams(): Flow<List<MonthlyPayParamsEntity>>

    @Query("SELECT * FROM monthly_pay_params ORDER BY payrollMonth ASC")
    suspend fun allPayParams(): List<MonthlyPayParamsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePayParams(row: MonthlyPayParamsEntity)

    @Query("DELETE FROM monthly_pay_params WHERE payrollMonth = :payrollMonth")
    suspend fun deletePayParams(payrollMonth: String)

    // ------------------------------------------------- 只读统计（不改用户数据）

    /**
     * 某计薪月的计薪分钟合计 —— 只用于「日工资基准月」校准，**只读**。
     *
     * 用 `LIKE :monthPrefix || '%'` 而不是 `BETWEEN`：`workDate` 是 `YYYY-MM-DD` 文本，
     * 字符串前缀匹配天然覆盖整月，也不用处理月末天数。
     */
    @Query("SELECT COALESCE(SUM(finalMinutes), 0) FROM work_records WHERE workDate LIKE :monthPrefix || '%'")
    suspend fun minutesInMonth(monthPrefix: String): Int
}
