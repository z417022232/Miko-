package com.example.worktimetracker.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 计薪参数的**分段常量**（DB v12，计薪规则 v2）。
 *
 * 同一个参数可以有多条记录，各带一个生效月份；查询某计薪月的取值时，
 * 取 `effectiveFrom <= 该月` 的最近一条。这样「7 月调薪」只加一条新段，
 * 回看 1–6 月仍是旧数值，不会把历史算歪，也不需要按月份复制常量。
 *
 * `value` 的单位由 [com.example.worktimetracker.domain.payroll.PayRateKey.unit] 决定
 * （分 / 小时×100 / 基点）。
 */
@Entity(
    tableName = "pay_rate_segments",
    indices = [Index(value = ["paramKey", "effectiveFrom"], unique = true)]
)
data class PayRateSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** [com.example.worktimetracker.domain.payroll.PayRateKey.storageKey] */
    val paramKey: String,
    /** 生效月份，格式 `YYYY-MM`（字典序即时间序，解析时直接字符串比较） */
    val effectiveFrom: String,
    val value: Long,
    /** 备注，如「7 月调薪」 */
    val note: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
